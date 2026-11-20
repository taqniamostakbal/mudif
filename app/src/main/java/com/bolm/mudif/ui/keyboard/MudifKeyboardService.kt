package com.bolm.mudif.ui.keyboard

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.bolm.mudif.ui.keyboard.KeyboardLayout.Action
import com.bolm.mudif.ui.keyboard.KeyboardLayout.Language
import com.bolm.mudif.ui.keyboard.KeyboardLayout.ShiftState
import com.bolm.mudif.ui.settings.AppTheme

/**
 * p118: the Mudif keyboard as a system input method.
 *
 * Two routes, one implementation:
 *  1. In the Mudif viewer the system picks this IME (the user
 *     selects it once; see KeyboardSetup + the VncActivity prompt),
 *     so it appears instead of Gboard whenever typing is needed.
 *     Android cannot force an IME silently, so the app never claims
 *     otherwise; the in-viewer prompt is the honest route.
 *  2. Anywhere else on the phone, the user can pick it in
 *     Settings > Language & input, and it stays the default.
 *
 * How keys reach the guest (the proven Gboard path, p82):
 * the viewer keeps a hidden EditText whose LorieInputConnection
 * forwards commitText() to replaceText() -> sendTextEvent(UTF-8)
 * -> native EVENT_UNICODE with the raw codepoint, and
 * sendKeyEvent() to LorieView.dispatchKeyEvent(). So a character
 * key commits its text (Arabic arrives as Unicode, exactly like
 * Gboard's), and a special key (Enter, arrows, F-keys) sends a
 * real KeyEvent. Nothing here talks to the guest directly.
 *
 * State (all owned here, rendered by the view):
 *  - language: Arabic (default) <-> Latin.
 *  - shift: OFF, ONCE (next key only), LOCKED (double-tap Shift
 *    = Caps lock for Latin; the view shows the locked state).
 *  - ctrl / alt: tap to hold (highlighted), tap again to release;
 *    the first non-modifier key consumes them (down + key + up),
 *    so Ctrl+Alt+Del works and a modifier can never get stuck.
 *  - fn: layout switch, number row becomes F1..F12.
 *  - symbolsOpen: the 123 page; its return key goes back to
 *    ABC / عربي.
 *
 * Theme: AppTheme.currentTag (the app's own pref, no new system):
 * dark or gradient -> the dark keyboard, light -> the light one.
 * The view is re-themed on every onStartInputView, so a theme
 * switch in the Control Center reaches the keyboard next time
 * it opens.
 */
class MudifKeyboardService : InputMethodService(), MudifKeyboardView.Listener {

    companion object {
        private const val TAG = "MudifKb"
    }

    private var keyboardView: MudifKeyboardView? = null
    /** Package of the last input session (set in onStartInput). The re-show
     *  below must only fire for outside-app input, like the deferred show. */
    private var lastInputPackage: String? = null
    /** Re-show budget: the system hides this client's keyboard ~500 ms
     *  after each show (explicit hideSoftInput, H5). If the hide is a
     *  one-shot per tap/event, one delayed re-show sticks; if the system
     *  hides after every show, the budget stops an infinite flicker loop
     *  (the log then shows the loop and the fix is reverted). */
    private var reshowBudget = 0
    /** Uptime (ms) of the last onWindowShown. The re-show only fires when
     *  the hide came soon after a show (the H5 signature, ~500 ms) — NOT
     *  when the user had the keyboard up a while and pressed BACK to hide
     *  it deliberately. */
    private var lastShownAtMs = 0L

    private var language: Language = Language.ARABIC
    private var shift: ShiftState = ShiftState.OFF
    private var ctrlHeld: Boolean = false
    private var altHeld: Boolean = false
    private var fn: Boolean = false
    private var symbolsOpen: Boolean = false

    /** Last Shift tap, for the double-tap = Caps lock gesture. */
    private var lastShiftTapMs: Long = 0

    override fun onCreateInputView(): View {
        val view = MudifKeyboardView(this)
        view.listener = this
        keyboardView = view
        return view
    }

    /** Some clients never ask the system to show the keyboard: the
     *  Settings search field in landscape starts input (the
     *  connection is live, the field is served) but no show is ever
     *  requested, and the framework only starts the input view when
     *  the window is already visible AND a show was requested
     *  (InputMethodService.doStartInput) — so the keyboard never
     *  appears. Attempt 1 (requestShowSelf from here) did not work:
     *  the system round-trip never sets mShowInputRequested for
     *  this client. Attempt 2 (showWindow(true) synchronously from
     *  here) DID raise the keyboard — but only for one frame
     *  (~0.1 s): showing the window inside onStartInput, i.e.
     *  inside the system's startInput binder transaction while the
     *  tap's focus is still settling, disrupts that transaction and
     *  the field ends up unfocused, so the system finishes input
     *  and hides the window again. So defer the show to the next
     *  main-loop turn, after the transaction and the focus have
     *  settled; the guard re-checks the input is still live so a
     *  stale post can never raise the keyboard for a finished
     *  input. Attempt 3 PROVED the !restarting gate is wrong for
     *  this field: the SearchActivity starts input on open
     *  (mInputStarted=true BEFORE any tap), so every tap is a
     *  restart and the gate skipped the fix entirely — the
     *  keyboard still flashed (onStartInputView 34.909,
     *  onWindowShown 34.938) via the client's own show, and hid
     *  129 ms later (onFinishInputView finishingInput=false,
     *  onWindowHidden 35.067) with no onFinishInput: the session
     *  stayed live, the window was hidden from outside the
     *  service (the service calls hideWindow nowhere). So the
     *  gate is dropped: every input start, fresh or restart,
     *  posts the deferred show. Outside our own app only; inside
     *  the app the app itself decides when the keyboard opens
     *  (viewer terminal, assistant), so in-app behaviour is
     *  unchanged. */
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        Log.d(TAG, "onStartInput pkg=${info?.packageName} restarting=$restarting")
        lastInputPackage = info?.packageName
        reshowBudget = 3
        if (info?.packageName != packageName) {
            Handler(mainLooper).post {
                if (currentInputConnection != null) {
                    Log.d(TAG, "deferred showWindow")
                    showWindow(true)
                } else {
                    Log.d(TAG, "deferred show skipped: input finished")
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView restarting=$restarting")
        // A fresh input starts with a clean one-shot shift, but the
        // language and the Caps lock stay as the user left them.
        shift = ShiftState.OFF
        applyStateToView(animate = !restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "onFinishInputView finishingInput=$finishingInput\n" +
            Log.getStackTraceString(Throwable()))
        // A held modifier must never survive the keyboard closing:
        // release both, so the next open starts clean.
        ctrlHeld = false
        altHeld = false
        shift = ShiftState.OFF
        fn = false
        symbolsOpen = false
    }

    /** Never fullscreen: the keyboard is a strip above the viewer,
     *  never a full-screen input box. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onWindowShown() {
        super.onWindowShown()
        Log.d(TAG, "onWindowShown")
        lastShownAtMs = SystemClock.uptimeMillis()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        Log.d(TAG, "onWindowHidden\n" + Log.getStackTraceString(Throwable()))
        // H6: the system hides this client's keyboard ~500 ms after each
        // show (explicit hideSoftInput; the show itself works). If that
        // hide is a one-shot per tap/event, a delayed re-show — after the
        // hide trigger has passed — sticks. If the system hides after
        // every show, the budget below stops the flicker loop (the log
        // then shows it and this is reverted). Only when the hide came
        // soon after a show (H5 signature), never for a deliberate
        // user hide after the keyboard was up a while. Outside our own
        // app only.
        val sinceShown = SystemClock.uptimeMillis() - lastShownAtMs
        Log.d(TAG, "onWindowHidden check: lastInputPackage=$lastInputPackage pkg=$packageName budget=$reshowBudget sinceShown=$sinceShown")
        if (lastInputPackage != packageName && reshowBudget > 0 && sinceShown < 2000) {
            reshowBudget--
            Log.d(TAG, "reshow in 500ms, budget left=$reshowBudget")
            Handler(mainLooper).postDelayed({
                if (currentInputConnection != null) {
                    Log.d(TAG, "reshow showWindow")
                    showWindow(true)
                } else {
                    Log.d(TAG, "reshow skipped: input finished")
                }
            }, 500)
        }
    }

    /** PROBE (attempt 5): log every hideWindow call with its stack so the
     *  hide caller is named — the keyboard is raised by the deferred show
     *  and hidden ~0.2 s later through onFinishInputView(finishingInput=
     *  false) with the input session still live, and the system logs
     *  nothing about it. Diagnostic only; the behaviour is attempt 4's. */
    override fun hideWindow() {
        Log.d(TAG, "hideWindow\n" + Log.getStackTraceString(Throwable()))
        super.hideWindow()
    }

    /** PROBE: does the client's show request reach this service at all? */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        val r = super.onShowInputRequested(flags, configChange)
        Log.d(TAG, "onShowInputRequested flags=$flags configChange=$configChange -> $r")
        return r
    }

    override fun onFinishInput() {
        super.onFinishInput()
        Log.d(TAG, "onFinishInput")
    }

    private fun palette(): MudifKeyboardView.Palette =
        when (AppTheme.currentTag(this)) {
            AppTheme.TAG_LIGHT -> MudifKeyboardView.PALETTE_LIGHT
            else -> MudifKeyboardView.PALETTE_DARK
        }

    private fun applyStateToView(animate: Boolean) {
        keyboardView?.setLayoutState(
            language = language,
            shift = shift,
            capsLock = shift == ShiftState.LOCKED,
            fn = fn,
            symbolsOpen = symbolsOpen,
            ctrlHeld = ctrlHeld,
            altHeld = altHeld,
            palette = palette()
        )
        if (animate) keyboardView?.playAppearAnimation()
    }

    /** Highlights only (no row rebuild): the modifier keys tint
     *  in place when held. */
    private fun refreshHighlights() {
        keyboardView?.setHighlights(
            shift = shift,
            capsLock = shift == ShiftState.LOCKED,
            ctrlHeld = ctrlHeld,
            altHeld = altHeld,
            fn = fn
        )
    }

    // ---- key handling -------------------------------------------------

    override fun onKeyPressed(key: KeyboardLayout.Key) {
        val ic = currentInputConnection ?: return
        when (key.action) {
            Action.TEXT -> onTextKey(ic, key)
            Action.BACKSPACE -> {
                // With a modifier held this is a real chord
                // (Ctrl+Backspace = delete word); otherwise the
                // plain delete is what every soft keyboard does.
                if (ctrlHeld || altHeld) {
                    sendKeyWithModifiers(ic, KeyEvent.KEYCODE_DEL)
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
                consumeOneShot()
            }
            // ENTER, TAB, ESC, ARROW and FUNCTION all carry their
            // real Android keycode in the layout: one real KeyEvent
            // (wrapped in any held modifier, see below).
            Action.ENTER, Action.TAB, Action.ESC, Action.ARROW, Action.FUNCTION -> {
                val code = key.keyCode ?: return
                sendKeyWithModifiers(ic, code)
                consumeOneShot()
            }
            Action.SPACE -> {
                // Space has no Latin keycode in the layout map, so
                // it is handled here: with a modifier held it is
                // the real KEYCODE_SPACE (Ctrl+Space is a shortcut
                // in some apps); otherwise it commits " ".
                if (ctrlHeld || altHeld) {
                    sendKeyWithModifiers(ic, KeyEvent.KEYCODE_SPACE)
                } else {
                    ic.commitText(" ", 1)
                }
                consumeOneShot()
            }
            Action.SHIFT -> onShiftKey()
            Action.CAPS -> onCapsKey()
            Action.CTRL -> { ctrlHeld = !ctrlHeld; refreshHighlights() }
            Action.ALT -> { altHeld = !altHeld; refreshHighlights() }
            Action.FN -> { fn = !fn; applyStateToView(animate = false) }
            Action.LANG -> { language = if (language == Language.ARABIC) Language.LATIN else Language.ARABIC; applyStateToView(animate = false) }
            Action.SYMBOLS -> { symbolsOpen = !symbolsOpen; applyStateToView(animate = false) }
        }
    }

    /** A text key (letter, digit, symbol, diacritic).
     *  With a modifier held: Latin goes as a raw KeyEvent with the
     *  modifier (Ctrl+C, Alt+F4: keyCodeForLatin maps the letter);
     *  Arabic has no Android keycode, so it is swallowed (a
     *  modifier + an Arabic letter cannot be a shortcut anywhere).
     *  Without a modifier: the text is committed as Unicode,
     *  exactly like Gboard. The case follows the shift state
     *  (KeyboardLayout.commitTextFor: lowercase unless Shift is
     *  one-shot or locked). */
    private fun onTextKey(ic: InputConnection, key: KeyboardLayout.Key) {
        val text = KeyboardLayout.commitTextFor(key, shift)
        if (ctrlHeld || altHeld) {
            val code = KeyboardLayout.keyCodeForLatin(text)
            if (code != null) {
                sendKeyWithModifiers(ic, code)
            }
            // Arabic / unmapped symbols are swallowed by design:
            // there is no KeyEvent to send for them.
        } else {
            ic.commitText(text, 1)
        }
        consumeOneShot()
    }

    /** Shift: one tap = one-shot (next key only); a second tap
     *  within 300 ms locks it (Caps lock for Latin). A third tap
     *  releases the lock. */
    private fun onShiftKey() {
        val now = SystemClock.uptimeMillis()
        when {
            shift == ShiftState.LOCKED -> {
                shift = ShiftState.OFF
            }
            now - lastShiftTapMs < 300L -> {
                shift = ShiftState.LOCKED
            }
            shift == ShiftState.ONCE -> {
                shift = ShiftState.OFF
            }
            else -> {
                shift = ShiftState.ONCE
            }
        }
        lastShiftTapMs = now
        applyStateToView(animate = false)
    }

    /** Caps: a plain toggle (the same lock the double-tap Shift
     *  sets, for users who prefer a dedicated key). */
    private fun onCapsKey() {
        shift = if (shift == ShiftState.LOCKED) ShiftState.OFF else ShiftState.LOCKED
        applyStateToView(animate = false)
    }

    /** After any non-modifier key: the one-shot shift is spent and
     *  the held modifiers are released (they were applied to this
     *  keypress). The Caps lock survives. A spent shift changes
     *  the row content (shifted -> unshifted), so it rebuilds;
     *  released modifiers only change the highlight. */
    private fun consumeOneShot() {
        var shiftChanged = false
        var modifiersChanged = false
        if (shift == ShiftState.ONCE) {
            shift = ShiftState.OFF
            shiftChanged = true
        }
        if (ctrlHeld || altHeld) {
            ctrlHeld = false
            altHeld = false
            modifiersChanged = true
        }
        if (shiftChanged) applyStateToView(animate = false)
        else if (modifiersChanged) refreshHighlights()
    }

    /** A real keypress to the guest, wrapped in any held modifier.
     *  The viewer carries NO metaState: its native sendKeyEvent
     *  (termux-x11 activity.c) takes only a keycode + down/up, so
     *  a KeyEvent whose META_CTRL_ON is set still arrives in the
     *  guest as a plain letter (proven on the rig: Ctrl+U typed a
     *  literal "u"). The modifier must be pressed as its own key:
     *  Ctrl DOWN, key DOWN+UP, Ctrl UP, exactly how a physical
     *  keyboard and the viewer's own keys bar do it. The X server
     *  then sees the modifier held while the key is pressed.
     *  Shift (one-shot / locked) is included the same way, so
     *  Shift+Tab reaches the guest as a real backtab. */
    private fun sendKeyWithModifiers(ic: InputConnection, keyCode: Int) {
        val mods = mutableListOf<Int>()
        if (ctrlHeld) mods.add(KeyEvent.KEYCODE_CTRL_LEFT)
        if (altHeld) mods.add(KeyEvent.KEYCODE_ALT_LEFT)
        if (shift == ShiftState.ONCE || shift == ShiftState.LOCKED) {
            mods.add(KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        val now = SystemClock.uptimeMillis()
        for (mod in mods) {
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, mod, 0, 0))
        }
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0))
        for (mod in mods.asReversed()) {
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, mod, 0, 0))
        }
    }
}
