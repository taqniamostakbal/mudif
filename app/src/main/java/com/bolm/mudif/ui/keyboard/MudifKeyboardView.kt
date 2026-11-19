package com.bolm.mudif.ui.keyboard

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import com.bolm.mudif.ui.keyboard.KeyboardLayout.Action
import com.bolm.mudif.ui.keyboard.KeyboardLayout.Key
import com.bolm.mudif.ui.keyboard.KeyboardLayout.Language
import com.bolm.mudif.ui.keyboard.KeyboardLayout.ShiftState

/**
 * p118: the Mudif keyboard view. A mechanical computer keyboard on
 * the phone screen: chunky keys with a real base + face, visible
 * gaps, purple underglow. Only two colors: #593BFE + #120C27
 * (dark) / white (light). The palette follows the app theme
 * (AppTheme.currentTag); dark is the default.
 *
 * The view is dumb: it builds rows from KeyboardLayout and reports
 * presses to [Listener]. All state (shift / caps / modifiers /
 * language / symbols / Fn) lives in MudifKeyboardService; the view
 * only renders it (setLayoutState rebuilds rows, setHighlights
 * tints the held modifiers in place).
 *
 * Keys fire on ACTION_DOWN (a keyboard must not wait for the
 * finger to lift). Backspace repeats while held (first delete at
 * once, then every 50 ms after 400 ms), the one repeat a real
 * keyboard has. Haptics: KEYBOARD_TAP on every press.
 */
class MudifKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    interface Listener {
        fun onKeyPressed(key: Key)
    }

    /** Palette for one theme. Faces are the key tops, bases the
     *  darker slab under each key (the mechanical depth), text the
     *  key labels, accent the purple action keys (#593BFE). */
    data class Palette(
        val background: Int,
        val face: Int,
        val base: Int,
        val text: Int,
        val accentFace: Int,
        val accentText: Int,
        val glow: Int
    )

    companion object {
        val PALETTE_DARK = Palette(
            background = Color.parseColor("#0E0A1F"),
            face = Color.parseColor("#2A2440"),
            base = Color.parseColor("#120C27"),
            text = Color.parseColor("#F6F3FE"),
            accentFace = Color.parseColor("#593BFE"),
            accentText = Color.WHITE,
            glow = Color.parseColor("#593BFE")
        )
        val PALETTE_LIGHT = Palette(
            background = Color.parseColor("#EDE9FB"),
            face = Color.WHITE,
            base = Color.parseColor("#D9D2F5"),
            text = Color.parseColor("#120C27"),
            accentFace = Color.parseColor("#593BFE"),
            accentText = Color.WHITE,
            glow = Color.parseColor("#593BFE")
        )

        private const val KEY_HEIGHT_DP = 56
        private const val KEY_GAP_DP = 4
        private const val ROW_GAP_DP = 6
        private const val KEY_RADIUS_DP = 10
        private const val KEY_BASE_DP = 3
        private const val BACKSPACE_REPEAT_START_MS = 400L
        private const val BACKSPACE_REPEAT_MS = 50L
    }

    var listener: Listener? = null

    private var palette: Palette = PALETTE_DARK
    private var language: Language = Language.ARABIC
    private var shift: ShiftState = ShiftState.OFF
    private var capsLock: Boolean = false
    private var fn: Boolean = false
    private var symbolsOpen: Boolean = false
    private var ctrlHeld: Boolean = false
    private var altHeld: Boolean = false

    /** Stateful keys, for in-place highlight updates. */
    private val stateKeys = mutableMapOf<Action, MutableList<AppCompatButton>>()

    private val handler = Handler(Looper.getMainLooper())
    private var backspaceRepeat: Runnable? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        val pad = dp(8)
        setPadding(pad, dp(6), pad, dp(10))
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    /** Full state in; rebuilds the rows. Called when the row
     *  CONTENT changes (language / shift / Fn / symbols page). */
    fun setLayoutState(
        language: Language,
        shift: ShiftState,
        capsLock: Boolean,
        fn: Boolean,
        symbolsOpen: Boolean,
        ctrlHeld: Boolean,
        altHeld: Boolean,
        palette: Palette
    ) {
        this.language = language
        this.shift = shift
        this.capsLock = capsLock
        this.fn = fn
        this.symbolsOpen = symbolsOpen
        this.ctrlHeld = ctrlHeld
        this.altHeld = altHeld
        this.palette = palette
        setBackgroundColor(palette.background)
        rebuild()
    }

    /** Highlight-only update (modifier held states); no rebuild. */
    fun setHighlights(
        shift: ShiftState,
        capsLock: Boolean,
        ctrlHeld: Boolean,
        altHeld: Boolean,
        fn: Boolean
    ) {
        this.shift = shift
        this.capsLock = capsLock
        this.ctrlHeld = ctrlHeld
        this.altHeld = altHeld
        this.fn = fn
        refreshHighlights()
    }

    private fun rebuild() {
        removeAllViews()
        stateKeys.clear()
        val rows = KeyboardLayout.rows(language, shift, fn, symbolsOpen)
        for ((rowIndex, row) in rows.withIndex()) {
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(KEY_HEIGHT_DP)
                ).apply {
                    if (rowIndex > 0) topMargin = dp(ROW_GAP_DP)
                }
            }
            for (key in row) {
                rowView.addView(buildKey(key))
            }
            addView(rowView)
        }
        refreshHighlights()
    }

    private fun buildKey(key: Key): AppCompatButton {
        val isAccent = key.kind == KeyboardLayout.Kind.ACCENT
        val face = if (isAccent) palette.accentFace else palette.face
        val textColor = if (isAccent) palette.accentText else palette.text
        val button = AppCompatButton(context).apply {
            text = key.label
            setTextColor(textColor)
            // The label MUST be centered: the IME service's context
            // does not apply the Button style's gravity (the
            // service has no Activity theme), so without this the
            // text sits top-start (Latin top-left, Arabic
            // top-right), seen on the rig at 3x. Explicit CENTER
            // is the fix, not a theme change.
            gravity = Gravity.CENTER
            // Wide labels (Esc, Backspace, Tab, Caps, Enter, مسافة)
            // get a smaller size so they fit on one line.
            textSize = if (key.label.length > 2) 13f else 18f
            isAllCaps = false
            isFocusable = false
            isFocusableInTouchMode = false
            // Tag: refreshHighlights() needs to know whether this
            // stateful key is purple by design (accent) or only
            // while held.
            tag = isAccent
            // The mechanical face: base slab + top face; pressed =
            // face down + purple glow (see KeyFaceDrawable).
            background = KeyFaceDrawable(
                faceColor = face,
                baseColor = palette.base,
                glowColor = palette.glow,
                radiusPx = dp(KEY_RADIUS_DP).toFloat(),
                basePx = dp(KEY_BASE_DP).toFloat()
            )
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight).apply {
                marginStart = dp(KEY_GAP_DP) / 2
                marginEnd = dp(KEY_GAP_DP) / 2
            }
            // Fire on DOWN (not on click/up): a keyboard must not
            // wait for the finger to lift.
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onKeyDown(key)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        stopBackspaceRepeat()
                        true
                    }
                    else -> true
                }
            }
        }
        // Keep the stateful keys reachable for highlight updates.
        when (key.action) {
            Action.SHIFT, Action.CAPS, Action.CTRL, Action.ALT, Action.FN -> {
                stateKeys.getOrPut(key.action) { mutableListOf() }.add(button)
            }
            else -> Unit
        }
        return button
    }

    private fun onKeyDown(key: Key) {
        if (key.action == Action.BACKSPACE) startBackspaceRepeat(key)
        listener?.onKeyPressed(key)
    }

    private fun startBackspaceRepeat(key: Key) {
        stopBackspaceRepeat()
        val repeat = object : Runnable {
            override fun run() {
                listener?.onKeyPressed(key)
                handler.postDelayed(this, BACKSPACE_REPEAT_MS)
            }
        }
        backspaceRepeat = repeat
        handler.postDelayed(repeat, BACKSPACE_REPEAT_START_MS)
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeat?.let { handler.removeCallbacks(it) }
        backspaceRepeat = null
    }

    /** Tint the held modifiers (and the locked Shift/Caps).
     *  All stateful keys are purple by design (Kind.ACCENT), so a
     *  held key cannot be told apart by its face colour alone:
     *  a held key keeps the persistent purple glow ring
     *  (KeyFaceDrawable.held), so the user can see at a glance
     *  that Ctrl / Alt / Fn / Shift is armed. */
    private fun refreshHighlights() {
        fun tint(action: Action, held: Boolean) {
            stateKeys[action]?.forEach { button ->
                val drawable = button.background as? KeyFaceDrawable ?: return@forEach
                val isAccent = (button.tag as? Boolean) ?: false
                drawable.faceColor = when {
                    held -> palette.accentFace
                    isAccent -> palette.accentFace
                    else -> palette.face
                }
                drawable.held = held
                button.setTextColor(
                    if (held || isAccent) palette.accentText else palette.text
                )
                drawable.invalidateSelf()
            }
        }
        // Tag accent stateful keys once (their normal face is purple
        // already; tint() keeps them purple and only changes held
        // plain keys). The tag is set lazily here because buildKey
        // does not know the held state yet.
        tint(Action.SHIFT, shift != ShiftState.OFF || capsLock)
        tint(Action.CAPS, capsLock)
        tint(Action.CTRL, ctrlHeld)
        tint(Action.ALT, altHeld)
        tint(Action.FN, fn)
    }

    /** Slide the keyboard up from the bottom on appearance
     *  (180 ms, decelerate). Called by the service in
     *  onStartInputView. */
    fun playAppearAnimation() {
        post {
            val fromY = height.toFloat()
            if (fromY <= 0f) return@post
            ObjectAnimator.ofFloat(this, "translationY", fromY, 0f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopBackspaceRepeat()
    }

    /**
     * One key's mechanical face, drawn in code (no XML drawable
     * set: the colors change with the theme, so the drawable takes
     * them as constructor args). A darker base slab fills the
     * bounds; the face sits 3dp above the bottom edge, so the base
     * shows as a shadow line under the key. Pressed: the face drops
     * to the bottom edge and a purple glow stroke appears around it.
     */
    class KeyFaceDrawable(
        var faceColor: Int,
        private val baseColor: Int,
        private val glowColor: Int,
        private val radiusPx: Float,
        private val basePx: Float
    ) : Drawable() {

        /** A held modifier (Ctrl / Alt / Fn / locked Shift) keeps
         *  the purple glow ring even when the finger is off it, so
         *  the armed state is visible (see refreshHighlights). */
        var held: Boolean = false

        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = baseColor
            style = Paint.Style.FILL
        }
        private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = faceColor
            style = Paint.Style.FILL
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glowColor
            style = Paint.Style.STROKE
            strokeWidth = basePx
        }
        private var pressed = false

        override fun onStateChange(state: IntArray): Boolean {
            val nowPressed = state.contains(android.R.attr.state_pressed)
            if (nowPressed != pressed) {
                pressed = nowPressed
                invalidateSelf()
                return true
            }
            return false
        }

        override fun isStateful(): Boolean = true

        override fun draw(canvas: Canvas) {
            val b = bounds
            val baseRect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            canvas.drawRoundRect(baseRect, radiusPx, radiusPx, basePaint)
            val faceBottom = if (pressed) b.bottom.toFloat() else b.bottom - basePx
            val faceRect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), faceBottom)
            // The face keeps the same top edge when pressed (it
            // drops down, like a real key travelling).
            facePaint.color = faceColor
            canvas.drawRoundRect(faceRect, radiusPx, radiusPx, facePaint)
            if (pressed || held) {
                canvas.drawRoundRect(faceRect, radiusPx, radiusPx, glowPaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            facePaint.alpha = alpha
            basePaint.alpha = alpha
            glowPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            facePaint.colorFilter = colorFilter
            basePaint.colorFilter = colorFilter
            glowPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
