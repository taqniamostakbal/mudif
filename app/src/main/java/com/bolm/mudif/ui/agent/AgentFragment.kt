package com.bolm.mudif.ui.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.bolm.mudif.MainActivity
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentAgentBinding
import com.bolm.mudif.engine.AgentClient
import com.bolm.mudif.engine.AgentServer
import com.bolm.mudif.engine.AgentSupervisorService
import com.bolm.mudif.engine.AppDeathDetector
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.InstallFlow
import com.bolm.mudif.engine.TerminalSession
import com.bolm.mudif.engine.UsageLog
import com.bolm.mudif.engine.VisionCapture
import com.bolm.mudif.engine.VisionClient
import com.bolm.mudif.engine.X11Session
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.settings.AppLanguage
import com.bolm.mudif.ui.settings.SettingsActivity
import com.bolm.mudif.ui.vnc.VncActivity
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.bolm.mudif.engine.MudifLog

/**
 * Phase 4: the مساعد مضيف tab. Phase 5: the native chat is wired to
 * the local agent server (AgentServer + AgentClient).
 *
 * Two modes, chat first:
 * - Chat: native message list (persisted locally), quick-action chips,
 *   voice input/output, and the reply language, which always follows
 *   the app language (Fusha / Darija / English) and is read live at
 *   every turn start, so a language change takes effect on the next
 *   turn. The header chip opens the app language picker.
 * - Console: a WebView on the agent server, localhost only. The
 *   WebView refuses any URL that is not localhost.
 *
 * Honesty rule (no fake replies): a send starts the real server when
 * needed (AgentServer.ensureStarted), then streams the real reply
 * into the agent bubble. Server cannot start -> an agent error
 * message in the chat plus Retry (or "open desktop" when the engine
 * is not installed). The reply streams live; the Stop button aborts
 * the turn. Nothing here fakes a reply.
 */
class AgentFragment : Fragment() {
    private var _binding: FragmentAgentBinding? = null
    private val binding get() = _binding!!

    private val adapter = AgentAdapter()
    private val messages = mutableListOf<AgentMessage>()
    private var replyLanguage: String = AgentMessage.REPLY_FUSHA
    private var brainModel: String = BRAIN_LOCAL
    private var voiceOutOn: Boolean = false
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var turnRunning: Boolean = false
    private var lastUserMessage: AgentMessage? = null
    private var consoleLoaded: Boolean = false
    private var sessionId: String? = null
    private var currentTurn: AgentClient.TurnHandle? = null
    private var streamingIndex: Int = -1
    // Brain fix (plan sections 5 and 11): a brain picked mid-turn
    // waits here until the turn ends; the local-slow chip state.
    private var pendingBrainPick: String? = null
    private var turnSeq: Int = 0
    // p34f fix 4: seq of the last turn that reached a terminal
    // state (failed, finished, stopped). The post-call re-arm in
    // startTurn must not revive it: a terminal state sticks.
    private var endedTurnSeq: Int = 0
    private var firstTokenArrived: Boolean = false
    // Phase 3: true while a turn-setup thread runs (startTurn /
    // startVisionTurn). onViewCreated uses it to tell a turn that
    // is still starting apart from a wedged flag.
    @Volatile private var turnSetupRunning: Boolean = false
    // p35c: a brain-model restart is tracked, never fire-and-forget.
    // A turn that starts while it runs must wait for it; a turn that
    // starts after it failed must not answer on the old server.
    // brainRestartAgain arms one more run when a second pick lands
    // mid-restart, so the newest pick is the one verified.
    @Volatile private var brainRestartRunning: Boolean = false
    @Volatile private var brainRestartFailed: Boolean = false
    @Volatile private var brainRestartAgain: Boolean = false
    @Volatile private var brainRestartThread: Thread? = null
    // p35c second attempt: one lock guards all four restart
    // fields, so no sampler can see a half-updated state.
    private val brainRestartLock = Any()
    // p32: keyboard state (the layout listener updates it). While
    // the keyboard is open the header + scroll row + divider + card
    // hide so the message list keeps a usable height (they used to
    // squeeze agent_recycler to 6px); they return when it closes.
    private var keyboardOpen: Boolean = false
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var serversDownReceiver: BroadcastReceiver? = null

    private val voiceInputLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank() && _binding != null) {
                binding.inputText.setText(text)
                binding.inputText.setSelection(text.length)
            }
        }

    // p31: the provider-selection screen returns the picked model id;
    // the fragment applies it through the existing setModel backend.
    private val brainProvidersLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && _binding != null) {
                val picked = result.data
                    ?.getStringExtra(BrainProvidersActivity.EXTRA_PICKED_MODEL)
                    ?: return@registerForActivityResult
                // Brain fix (plan section 5): a pick during a turn
                // cannot apply at once (it restarts opencode and
                // kills the turn), so ask: stop now or wait.
                if (turnRunning) {
                    showBrainSwitchDialog(picked)
                } else {
                    applyBrainPick(picked)
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAgentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // p57: the reply language is the app language, read live.
        // The stored pref is only a cache; it never overrides the
        // app language, so a language change takes effect here
        // (and again at every turn start) with no reinstall.
        replyLanguage = liveReplyLanguage()
        prefs().edit().putString(KEY_REPLY_LANGUAGE, replyLanguage).apply()
        brainModel = prefs().getString(KEY_BRAIN_MODEL, BRAIN_LOCAL) ?: BRAIN_LOCAL
        // Vision increment 2: a cloud vision turn that died with
        // the view (rotation, process death) leaves its restore
        // pending in prefs; apply it now, before the server starts,
        // so the text brain is back. No restart here: the server
        // is not up yet, and the next ensureStarted reads the
        // config as written. A failed write keeps the pref, so a
        // later turn can still restore.
        val pendingRestore = prefs().getString(KEY_PENDING_BRAIN_PICK, null)
        if (!pendingRestore.isNullOrBlank() && pendingRestore != brainModel) {
            if (AgentServer.setModel(requireContext(), pendingRestore)) {
                brainModel = pendingRestore
                prefs().edit().putString(KEY_BRAIN_MODEL, brainModel).apply()
                prefs().edit().remove(KEY_PENDING_BRAIN_PICK).apply()
            }
        }
        voiceOutOn = prefs().getBoolean(KEY_VOICE_OUT, false)
        sessionId = prefs().getString(KEY_SESSION_ID, null)
        messages.addAll(loadMessages())
        ensureGreeting()

        binding.agentRecycler.layoutManager = LinearLayoutManager(requireContext()).apply {
            // p33: stack from the end, so the TAIL of the last reply
            // stays visible. scrollToPosition(last) only shows the
            // TOP of the last item; when the reply is taller than the
            // viewport (the "15% of 200" steps) the 30 stayed below
            // the fold on Anas's phone. With stackFromEnd the list
            // anchors to the bottom edge, like a normal chat app.
            stackFromEnd = true
        }
        binding.agentRecycler.adapter = adapter
        adapter.submitList(messages.toList())
        binding.agentRecycler.scrollToPosition(messages.size - 1)
        // Phase 3: re-sync after a wedge. A turn whose terminal
        // callback was dropped while the view was destroyed left
        // turnRunning true with no handle and no setup thread; that
        // flag is stale and must not block the next send. A live turn
        // always has a handle (or is still starting), so it is never
        // reset here.
        if (turnRunning && currentTurn == null && !turnSetupRunning) {
            turnRunning = false
            streamingIndex = -1
            binding.btnSend.isEnabled = true
            binding.agentTyping.visibility = View.GONE
            hideAgentAction()
        }
        updateProfileCardVisibility()
        binding.btnBrainDownload.setOnClickListener { onBrainDownloadAction() }
        // p60: the X is state-aware. While a download runs (or is
        // paused) it cancels, exactly as in p47. In idle state it
        // dismisses the card instead: the card hides and the
        // dismissal persists, so the nag stays gone across restarts.
        binding.btnBrainDownloadCancel.setOnClickListener {
            if (brainDownloadRunning || downloadPaused) cancelBrainDownload()
            else dismissBrainDownloadCard()
        }
        checkBrainModelOnOpen()
        refreshVisionChip()

        updateLanguageButton()
        updateBrainButton()
        initTts()

        // Header: avatar + name open the agent About text.
        val showAbout = { showAgentAbout() }
        binding.agentAvatar.setOnClickListener { showAbout() }
        binding.agentIdentity.setOnClickListener { showAbout() }
        binding.btnReplyLanguage.setOnClickListener { openLanguagePicker() }
        // p31: the brain pill and the profile card both open the
        // provider-selection screen (10 free platforms).
        binding.btnBrain.setOnClickListener { openBrainProviders() }
        binding.agentProfileCard.setOnClickListener { openBrainProviders() }

        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.mode_chat -> showChat()
                R.id.mode_console -> showConsole()
            }
        }
        binding.btnClearHistory.setOnClickListener { clearHistory() }

        // Quick actions. Command chips do the real thing now; the
        // search chip sends its text through the honest send flow.
        binding.chipDesktop.setOnClickListener { openDesktop() }
        binding.chipApps.setOnClickListener { selectTab(R.id.nav_apps) }
        binding.chipOpenApp.setOnClickListener { selectTab(R.id.nav_apps) }
        binding.chipSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.chipHelp.setOnClickListener { showAgentAbout() }
        binding.chipSearch.setOnClickListener {
            sendMessage(getString(R.string.agent_quick_search))
        }
        // Vision increment 1: "شوف الشاشة" looks at the desktop and
        // describes it. The chip is disabled with the reason when
        // the desktop is down or the vision model is missing
        // (refreshVisionChip, checked when the tab opens).
        binding.chipVision.setOnClickListener { startVisionLook() }

        binding.btnSend.setOnClickListener { sendCurrentInput() }
        binding.inputText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else {
                false
            }
        }
        binding.btnAgentAction.visibility = View.GONE

        // Voice input: disabled with an honest message when the
        // device has no speech recognizer.
        if (!recognizerAvailable()) {
            binding.btnVoiceIn.isEnabled = false
            binding.btnVoiceIn.alpha = 0.4f
            binding.btnVoiceIn.contentDescription =
                getString(R.string.agent_voice_in_unavailable)
        }
        binding.btnVoiceIn.setOnClickListener { startVoiceInput() }
        // p31: the paperclip opens the voice menu (voice input +
        // voice-output toggle); the speaker button is gone from the
        // input row so the field stays wide.
        binding.btnAttach.setOnClickListener { showAttachMenu() }

        binding.btnConsoleRetry.setOnClickListener {
            consoleLoaded = false
            loadConsole()
        }

        // p32: when the keyboard opens, hide the chrome (header +
        // scroll row + divider + profile card) so the message list
        // keeps a usable height above the keyboard; restore it when
        // the keyboard closes. The 15% threshold separates the
        // keyboard from smaller resizes.
        layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val b = _binding ?: return@OnGlobalLayoutListener
            val rect = Rect()
            b.root.getWindowVisibleDisplayFrame(rect)
            val screenHeight = b.root.rootView.height
            if (screenHeight <= 0) return@OnGlobalLayoutListener
            val open = screenHeight - rect.bottom > screenHeight * 0.15
            if (open != keyboardOpen) {
                keyboardOpen = open
                applyChromeVisibility()
                // p33: when the keyboard opens the list resizes; pin
                // it to the newest message so its tail is visible
                // without the user scrolling (stackFromEnd anchors
                // it, this re-pins after the resize settles).
                if (open) {
                    b.agentRecycler.post {
                        if (_binding != null && messages.isNotEmpty()) {
                            b.agentRecycler.scrollToPosition(messages.size - 1)
                        }
                    }
                }
            }
        }
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)

        // Phase 3: the watchdog broadcasts when the servers died
        // and its one restart failed; the chat shows it with a
        // Retry action that retries the restart.
        val appCtx = requireContext().applicationContext
        serversDownReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (_binding == null) return
                if (turnRunning) return
                appendMessage(
                    AgentMessage(
                        AgentMessage.ROLE_AGENT,
                        getString(R.string.agent_servers_down),
                        replyLanguage
                    )
                )
                binding.btnAgentAction.text = getString(R.string.agent_server_retry)
                binding.btnAgentAction.setOnClickListener { retryServerRestart() }
                binding.btnAgentAction.visibility = View.VISIBLE
            }
        }
        try {
            ContextCompat.registerReceiver(
                appCtx, serversDownReceiver!!,
                IntentFilter(AgentSupervisorService.ACTION_SERVERS_DOWN),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.w("AgentFragment", "servers-down receiver register failed", e)
        }
        // p34f fix 4: a failed turn from before a relaunch still
        // has its Retry; re-derive it from the stored state here.
        // p34h: logged so the verifier can prove this runs on the
        // force-stop relaunch path.
        MudifLog.i("MudifAgent", "onViewCreated calling restoreFailedTurnUi")
        restoreFailedTurnUi()
    }

    override fun onResume() {
        super.onResume()
        // p34f fix 6: the desktop / service state may have changed
        // while the tab was away; re-check on every entry.
        refreshVisionChip()
        // Consolidated fix item 1: narrow re-check for a dangling
        // turn the onViewCreated pass had to skip while setup exited.
        detectDanglingTurn()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // p34f fix 6: show/hide navigation never calls onResume,
        // so tab entry through it needs its own refresh.
        if (!hidden) refreshVisionChip()
    }

    override fun onDestroyView() {
        // p33: the fragment may have hidden the activity bottom nav
        // while the keyboard was open; restore it here or the next
        // tab would open without a nav.
        (activity as? MainActivity)?.setBottomNavVisible(true)
        layoutListener?.let {
            binding.root.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
        layoutListener = null
        val supersededTurn = currentTurn
        currentTurn?.supersede()
        currentTurn = null
        if (supersededTurn != null) {
            // Hole 1: the supersede aborts the live server session,
            // so the stored id must go with it. A next turn reusing
            // the aborted id gets an instant empty reply (prompt
            // async 204, no text) until a model switch makes a new
            // session. Same drop as Clear and model switch.
            sessionId = null
            prefs().edit().remove(KEY_SESSION_ID).apply()
            MudifLog.i("MudifAgent", "onDestroyView superseded live turn, dropped stored session id")
        }
        // Re-gate hole 5 (round 2): the marker must go here,
        // unconditionally. The turn is dead in every onDestroyView
        // in this app: a replaced instance is removed (its handle
        // superseded and aborted, or its setup post can never
        // re-arm because _binding stays null), and activity
        // teardown kills it too. There is no same-instance view
        // recreation (every config change is absorbed, no
        // retain/detach/back-stack), so there is no keep-branch.
        // No isAdded guard: it is false here after a replace()
        // (FragmentStore clears mAdded before the state move that
        // runs onDestroyView), while requireContext() is valid
        // until onDetach. Otherwise the next instance's dangling
        // detector waits out the full 300 s on a fresh same-pid
        // marker and Retry appears only after a re-entry past
        // send+300 s.
        clearTurnInFlightMarker(requireContext())
        // Phase 3: the turn is cancelled with the view; the flag
        // must not survive it, or the next send is dropped silently
        // (the wedge: every terminal callback posts behind a
        // _binding guard that now drops it). The callbacks finalise
        // the reply in the list even with the view gone (below).
        turnRunning = false
        streamingIndex = -1
        // Phase 3: the watchdog receiver belongs to this view; a
        // stale registration would fire into a dead binding.
        serversDownReceiver?.let { receiver ->
            try {
                requireContext().unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
        serversDownReceiver = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (consoleLoaded) {
            binding.agentWebview.stopLoading()
            binding.agentWebview.destroy()
        }
        super.onDestroyView()
        _binding = null
    }

    // ---------------------------------------------------------- modes

    // p29: chat_container wrapper removed (it measured 0x0). The chat
    // views are direct children of the root now, so show/hide them
    // individually. The console container takes the recycler's space
    // (both are weight=1 direct children; only one is visible at a time).
    private fun showChat() {
        setChatRowItemsVisible(true)
        binding.agentRecycler.visibility = View.VISIBLE
        binding.inputRow.visibility = View.VISIBLE
        binding.consoleContainer.visibility = View.GONE
        applyChromeVisibility()
    }

    private fun showConsole() {
        // p31: the scroll row itself stays visible (the mode toggle
        // must always be able to switch back); only the chat items
        // inside it hide.
        setChatRowItemsVisible(false)
        binding.agentRecycler.visibility = View.GONE
        binding.inputRow.visibility = View.GONE
        binding.consoleContainer.visibility = View.VISIBLE
        applyChromeVisibility()
        binding.modeToggle.check(R.id.mode_console)
        if (!consoleLoaded) loadConsole()
    }

    /** The chat-only items inside the single scroll row (language,
     *  chips). The mode toggle is not one of them: it stays
     *  visible in console mode so the user can switch back. p32:
     *  Clear moved to the header; its visibility is owned by
     *  applyChromeVisibility, not this row. */
    private fun setChatRowItemsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.btnReplyLanguage.visibility = v
        binding.chipDesktop.visibility = v
        binding.chipApps.visibility = v
        binding.chipHelp.visibility = v
        binding.chipSearch.visibility = v
        binding.chipVision.visibility = v
        binding.chipOpenApp.visibility = v
        binding.chipSettings.visibility = v
    }

    /** p32: one place that decides the chrome (header, scroll row,
     *  divider, profile card, Clear button) from the current mode
     *  and the keyboard state. p33: the HEADER STAYS VISIBLE while
     *  typing, p32 hid it and Anas reports it gone (his verdict:
     *  a chat header must stay pinned, like Rami's). What hides
     *  instead is the activity BOTTOM NAV (via MainActivity; it is
     *  activity chrome the fragment never owned and it sat between
     *  the input bar and the keyboard, eating ~110px) plus the
     *  scroll row / divider / profile card, so the message list
     *  still gets most of the visible area. */
    private fun applyChromeVisibility() {
        if (_binding == null) return
        val consoleMode = binding.consoleContainer.visibility == View.VISIBLE
        val activity = activity as? MainActivity
        if (keyboardOpen) {
            activity?.setBottomNavVisible(false)
            binding.chipsScroll.visibility = View.GONE
            binding.agentTodayDivider.visibility = View.GONE
            binding.agentProfileCard.visibility = View.GONE
            // p33 follow-up (keyboard CROWDS the app): with the
            // keyboard open the message list measured only 720x183
            // on the rig, the "thinking" line + Stop button took
            // ~145px of the ~640px visible window. While a turn runs
            // AND the keyboard is open, those two hide: the streaming
            // bubble itself shows progress, and Stop returns the
            // moment the keyboard closes (BACK), or the turn ends.
            // A failed turn (Retry) is NOT hidden: turnRunning is
            // false then, and the user needs the Retry button.
            if (turnRunning) {
                // Phase 3: the typing line hides (the list needs the
                // room), but a running turn must never have zero
                // abort controls: the compact Stop button stays.
                // It is always the Stop button here (Retry only
                // shows when turnRunning is false); its label and
                // action are armed up front in startTurn.
                binding.agentTyping.visibility = View.GONE
                binding.btnAgentAction.visibility = View.VISIBLE
            }
        } else {
            activity?.setBottomNavVisible(true)
            binding.chipsScroll.visibility = View.VISIBLE
            // A turn running while the keyboard closes gets its
            // "thinking" line + Stop button back.
            if (turnRunning) {
                binding.agentTyping.visibility = View.VISIBLE
                binding.btnAgentAction.visibility = View.VISIBLE
            }
            binding.btnClearHistory.visibility =
                if (consoleMode) View.GONE else View.VISIBLE
            if (consoleMode) {
                binding.agentTodayDivider.visibility = View.GONE
                binding.agentProfileCard.visibility = View.GONE
            } else {
                binding.agentTodayDivider.visibility = View.VISIBLE
                binding.agentProfileCard.visibility =
                    if (messages.size <= 1) View.VISIBLE else View.GONE
            }
        }
    }

    /** p31: the profile card shows only while the chat is still just
     *  the greeting. Once the user has a real exchange it hides, so
     *  it stops eating 155px of the message list forever. p32: the
     *  card also stays hidden while the keyboard is open (chrome);
     *  this now goes through applyChromeVisibility. */
    private fun updateProfileCardVisibility() {
        applyChromeVisibility()
    }

    // ---------------------------------------------------------- chat

    /** p34f fix 4: mark / clear the durable failed-turn state.
     *  Takes a Context so the background failure path can persist
     *  it without touching views. */
    private fun markTurnFailed(ctx: Context, isVision: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LAST_TURN_FAILED, true)
            .putBoolean(KEY_FAILED_TURN_VISION, isVision)
            .remove(KEY_FAILED_TURN_PICK)
            .apply()
    }

    /** p34h: a refused brain pick is the only failure with no chat
     *  turn behind it (a model pick is not a turn, so no user
     *  message exists to re-send). Its re-runnable payload is the
     *  picked model id; Retry re-applies the pick. */
    private fun markRefusedPick(ctx: Context, pickedId: String) {
        // Consolidated fix item 2: a real turn failure always
        // supersedes a pick refusal. A stored failure with no pick
        // is an unanswered question; overwriting it would orphan
        // its Retry, so the refusal leaves the state alone.
        val stored = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (stored.getBoolean(KEY_LAST_TURN_FAILED, false) &&
            stored.getString(KEY_FAILED_TURN_PICK, null) == null
        ) {
            MudifLog.i("MudifAgent", "markRefusedPick skipped, real turn failure pending")
            return
        }
        stored.edit()
            .putBoolean(KEY_LAST_TURN_FAILED, true)
            .putBoolean(KEY_FAILED_TURN_VISION, false)
            .putString(KEY_FAILED_TURN_PICK, pickedId)
            .apply()
    }

    private fun clearTurnFailed(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_LAST_TURN_FAILED)
            .remove(KEY_FAILED_TURN_VISION)
            .remove(KEY_FAILED_TURN_PICK)
            .apply()
    }

    // Hole 5: the marker is spent once its turn ends. Takes a
    // Context so background terminal paths can clear it without
    // touching views (prefs() needs an attached fragment).
    private fun clearTurnInFlightMarker(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_TURN_IN_FLIGHT_AT)
            .remove(KEY_TURN_IN_FLIGHT_PID)
            .apply()
    }

    /** p34f fix 4: re-derive Retry from the STORED failed turn when
     *  the view is created. The last user message comes from the
     *  stored messages, not the dead in-memory field; a vision
     *  failure re-runs the vision turn, a plain one re-sends.
     *  p34h: a refused brain pick restores from its picked model
     *  id instead; it has no user message to re-send. */
    private fun restoreFailedTurnUi() {
        if (_binding == null) return
        val failed = prefs().getBoolean(KEY_LAST_TURN_FAILED, false)
        val pick = prefs().getString(KEY_FAILED_TURN_PICK, null)
        MudifLog.i("MudifAgent", "restoreFailedTurnUi failed=$failed pick=$pick")
        if (!failed) {
            // Consolidated fix item 1: a turn that died with the
            // process left no mark; detect it from the stored
            // messages instead of returning here.
            detectDanglingTurn()
            return
        }
        // Consolidated fix item 2 invariant: a pick present here
        // means no real turn failure is stored, because a refusal
        // cannot overwrite one and a turn failure removes the pick.
        // The pick branch therefore owns Retry.
        if (!pick.isNullOrBlank()) {
            val retryPick: String = pick
            showTurnFailedUi(null) { applyBrainPick(retryPick) }
            return
        }
        val lastUser = messages.lastOrNull { it.role == AgentMessage.ROLE_USER }
        if (lastUser == null) {
            // Hole 8: a stored failure with no stored question has
            // no re-runnable payload, so Retry would be a no-op
            // (retryLastMessage returns when lastUserMessage is
            // null). No in-code path stores such a state: every
            // markTurnFailed site runs after a user message is
            // stored, so this is stale or hand-edited prefs.
            // Clear it instead of showing a dead Retry.
            lastUserMessage = null
            clearTurnFailed(requireContext())
            Log.w("MudifAgent", "restoreFailedTurnUi stale failed turn with no stored user message, cleared")
            return
        }
        lastUserMessage = lastUser
        val isVision = prefs().getBoolean(KEY_FAILED_TURN_VISION, false)
        showTurnFailedUi(
            null,
            if (isVision) ({ startVisionTurn(lastUser.text) }) else null
        )
    }

    /** Consolidated fix item 1: a mid-turn process death leaves the
     *  user message unanswered with no stored mark, because every
     *  terminal path died with the process. Detect it from the
     *  stored messages and surface it as a failed turn with Retry.
     *  The guards rule out a live turn, a clean end, and a user
     *  Stop. Returns true when a dangling turn was surfaced. */
    private fun detectDanglingTurn(): Boolean {
        if (_binding == null) return false
        if (turnRunning || currentTurn != null || turnSetupRunning) return false
        if (prefs().getBoolean(KEY_LAST_TURN_FAILED, false)) return false
        if (prefs().getString(KEY_FAILED_TURN_PICK, null) != null) return false
        // Hole 5: durable in-flight marker. The per-instance guards
        // above are all fresh false on a new instance after a tab
        // switch, so a turn still starting in this process would
        // look dangling. A fresh marker from this pid means the turn
        // may still be in flight; wait for its deadline.
        val inFlightAt = prefs().getLong(KEY_TURN_IN_FLIGHT_AT, -1L)
        val inFlightPid = prefs().getInt(KEY_TURN_IN_FLIGHT_PID, -1)
        if (inFlightAt >= 0L && inFlightPid == Process.myPid() &&
            System.currentTimeMillis() - inFlightAt < 300_000L
        ) {
            return false
        }
        val last = messages.lastOrNull() ?: return false
        val ctx = requireContext()
        if (last.role == AgentMessage.ROLE_USER) {
            val isVision = last.text == getString(R.string.agent_vision_question)
            clearTurnInFlightMarker(ctx)
            markTurnFailed(ctx, isVision)
            lastUserMessage = last
            appendMessage(
                AgentMessage(
                    AgentMessage.ROLE_AGENT,
                    getString(R.string.agent_error),
                    replyLanguage
                )
            )
            showTurnFailedUi(
                null,
                if (isVision) ({ startVisionTurn(last.text) }) else null
            )
            MudifLog.i("MudifAgent", "restoreFailedTurnUi dangling user message detected isVision=$isVision")
            return true
        }
        val verdicts = setOf(
            getString(R.string.agent_error),
            getString(R.string.agent_server_start_failed),
            getString(R.string.agent_engine_not_ready),
            getString(R.string.agent_empty_reply),
            getString(R.string.agent_vision_failed),
            getString(R.string.agent_vision_capture_failed),
            getString(R.string.agent_vision_desktop_down)
        )
        if (last.role == AgentMessage.ROLE_AGENT && last.text in verdicts) {
            val prevUser = messages.dropLast(1)
                .lastOrNull { it.role == AgentMessage.ROLE_USER }
            val isVision = prevUser != null &&
                prevUser.text == getString(R.string.agent_vision_question)
            clearTurnInFlightMarker(ctx)
            markTurnFailed(ctx, isVision)
            lastUserMessage = prevUser
            showTurnFailedUi(
                null,
                if (isVision && prevUser != null) ({ startVisionTurn(prevUser.text) }) else null
            )
            MudifLog.i("MudifAgent", "restoreFailedTurnUi dangling failure verdict detected isVision=$isVision")
            return true
        }
        return false
    }

    private fun sendCurrentInput() {
        val text = binding.inputText.text.toString()
        if (text.isBlank()) return
        binding.inputText.setText("")
        playSendIcon(R.drawable.avd_send)
        sendMessage(text)
    }

    // Send button icon animation: the plane flies on tap (200ms),
    // the check draws itself on a successful reply (300ms) and the
    // plane returns 100ms later. No text or logic changes.
    private fun playSendIcon(@DrawableRes res: Int) {
        val drawable = ContextCompat.getDrawable(requireContext(), res) ?: return
        binding.btnSend.setImageDrawable(drawable)
        (drawable as? AnimatedVectorDrawable)?.start()
    }

    // ------------------------------------------------- brain download (p22)

    private var brainDownloadRunning = false
    // p47: pause/resume/cancel for the download card. [pullHandle]
    // is the live handle of the pull in flight, if any;
    // [lastDownloadPct] keeps the percent across a pause so the
    // paused state shows it; [downloadPaused] and
    // [downloadCancelled] are set on the UI thread by the card
    // buttons and read by the pull thread's completion block.
    private var pullHandle: AgentServer.PullHandle? = null
    private var downloadPaused = false
    private var downloadCancelled = false
    private var lastDownloadPct = 0
    // Vision increment 1: the same card downloads the vision model
    // too. [downloadModel] remembers which one the card is offering.
    private var downloadModel: String = AgentServer.BRAIN_MODEL

    /** Shows the one-tap model download card (brain or vision model
     *  missing). */
    private fun showBrainDownloadCard(model: String = AgentServer.BRAIN_MODEL) {
        downloadModel = model
        downloadPaused = false
        downloadCancelled = false
        lastDownloadPct = 0
        pullHandle = null
        binding.brainDownloadCard.visibility = View.VISIBLE
        // p60: the X is visible in idle state too, as dismiss (not
        // cancel: nothing is downloading). Its description says so.
        binding.btnBrainDownloadCancel.visibility = View.VISIBLE
        binding.btnBrainDownloadCancel.contentDescription =
            getString(R.string.download_dismiss)
        // p34f fix 8: the banner overlays the list; lift the
        // bottom padding so the newest message never hides behind it.
        // p47: the card is compact now (~51dp), so the lift drops
        // from 64dp to 56dp.
        val pad = (12 * resources.displayMetrics.density).toInt()
        val lift = (56 * resources.displayMetrics.density).toInt()
        binding.agentRecycler.setPadding(pad, pad, pad, lift)
        binding.brainDownloadProgress.visibility = View.GONE
        binding.brainDownloadProgress.progress = 0
        val isVision = model == AgentServer.VISION_MODEL
        binding.brainDownloadText.text = getString(
            if (isVision) R.string.vision_download_title
            else R.string.brain_download_title
        )
        binding.btnBrainDownload.text = getString(
            if (isVision) R.string.vision_download_button
            else R.string.brain_download_button
        )
        binding.btnBrainDownload.isEnabled = true
    }

    private fun hideBrainDownloadCard() {
        binding.brainDownloadCard.visibility = View.GONE
        binding.btnBrainDownloadCancel.visibility = View.GONE
        // p34f fix 8: banner gone, the padding lift goes with it.
        val pad = (12 * resources.displayMetrics.density).toInt()
        binding.agentRecycler.setPadding(pad, pad, pad, pad)
    }

    /** p60: was this model's download card dismissed? One flag per
     *  model id, so dismissing the vision offer never hides the
     *  brain offer (chat cannot work at all without the brain). */
    private fun isDownloadDismissed(model: String): Boolean =
        prefs().getString(KEY_DISMISSED_DOWNLOAD_MODEL, null) == model

    /** p60: dismiss the idle card. Records the dismissed model so
     *  the on-open nag stays hidden across restarts, then hides
     *  the card. The download itself stays reachable: pick the
     *  model on the provider screen and the next turn for it shows
     *  the card again as the remedy. */
    private fun dismissBrainDownloadCard() {
        if (brainDownloadRunning || downloadPaused) return
        prefs().edit().putString(KEY_DISMISSED_DOWNLOAD_MODEL, downloadModel).apply()
        hideBrainDownloadCard()
    }

    /** Background check when the tab opens: if Ollama answers but
     *  the model is missing, show the download card (no turn is started).
     *  The brain comes first (the chat needs it); when it is present
     *  but the vision model is missing, the card offers the vision
     *  model instead, so the "شوف الشاشة" chip can be enabled. */
    private fun checkBrainModelOnOpen() {
        Thread {
            val hasBrain = AgentServer.hasBrainModel() ?: return@Thread
            val hasVision = if (hasBrain) AgentServer.hasModel(AgentServer.VISION_MODEL) else null
            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                when {
                    // p43: a cloud brain needs no local model
                    // download, so the brain card stays hidden;
                    // the vision offer below is untouched.
                    // p60: a dismissed offer stays hidden here;
                    // the turn-failure gates below still show the
                    // card as the remedy when the model is needed.
                    !hasBrain && brainModel == BRAIN_LOCAL ->
                        if (isDownloadDismissed(AgentServer.BRAIN_MODEL)) hideBrainDownloadCard()
                        else showBrainDownloadCard()
                    hasVision == false ->
                        if (isDownloadDismissed(AgentServer.VISION_MODEL)) hideBrainDownloadCard()
                        else showBrainDownloadCard(AgentServer.VISION_MODEL)
                    else -> hideBrainDownloadCard()
                }
            }
        }.start()
    }

    /** The card's main button does three jobs by state: start the
     *  download when idle, pause it while it runs, resume it when
     *  paused. */
    private fun onBrainDownloadAction() {
        when {
            brainDownloadRunning -> pauseBrainDownload()
            downloadPaused -> resumeBrainDownload()
            else -> startBrainDownload()
        }
    }

    /** Pause: abort the pull in flight. The partial blobs stay on
     *  disk, so resume re-issues the pull and Ollama continues
     *  from them instead of starting over. */
    private fun pauseBrainDownload() {
        if (!brainDownloadRunning) return
        downloadPaused = true
        binding.btnBrainDownload.isEnabled = false
        pullHandle?.requestCancel()
    }

    /** Resume after a pause: a fresh pull for the same model.
     *  Ollama skips blobs it already has and resumes partial
     *  ones, so the percent climbs from where it stopped. */
    private fun resumeBrainDownload() {
        if (brainDownloadRunning || !downloadPaused) return
        downloadPaused = false
        startBrainDownload()
    }

    /** Cancel: abort the pull in flight (if any) and delete the
     *  model, so the card goes back to the not-downloaded state. */
    private fun cancelBrainDownload() {
        if (brainDownloadRunning) {
            downloadCancelled = true
            binding.btnBrainDownload.isEnabled = false
            binding.btnBrainDownloadCancel.isEnabled = false
            pullHandle?.requestCancel()
        } else if (downloadPaused) {
            downloadCancelled = true
            downloadPaused = false
            deleteDownloadedModel()
        }
    }

    /** Best-effort delete of the (partial) model, then the card
     *  back to its idle state. The delete runs off the UI thread;
     *  the card reset does not wait for it. */
    private fun deleteDownloadedModel() {
        val model = downloadModel
        Thread {
            val deleted = AgentServer.deleteModel(model)
            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                MudifLog.i("MudifAgent", "cancelBrainDownload delete $model -> $deleted")
                Toast.makeText(
                    requireContext(),
                    if (deleted) R.string.download_cancelled
                    else R.string.download_cancel_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
        resetBrainDownloadCard()
    }

    /** The card back to its idle (not downloading) state. */
    private fun resetBrainDownloadCard() {
        brainDownloadRunning = false
        downloadPaused = false
        downloadCancelled = false
        lastDownloadPct = 0
        pullHandle = null
        binding.brainDownloadProgress.visibility = View.GONE
        binding.brainDownloadProgress.progress = 0
        // p60: back to idle, so the X is dismiss again, not cancel.
        binding.btnBrainDownloadCancel.visibility = View.VISIBLE
        binding.btnBrainDownloadCancel.contentDescription =
            getString(R.string.download_dismiss)
        binding.btnBrainDownload.isEnabled = true
        binding.btnBrainDownloadCancel.isEnabled = true
        val isVision = downloadModel == AgentServer.VISION_MODEL
        binding.brainDownloadText.text = getString(
            if (isVision) R.string.vision_download_title
            else R.string.brain_download_title
        )
        binding.btnBrainDownload.text = getString(
            if (isVision) R.string.vision_download_button
            else R.string.brain_download_button
        )
    }

    /** One-tap pull with a real progress bar (no terminal). While
     *  it runs, the main button pauses and the X button cancels;
     *  while paused, the main button resumes. */
    private fun startBrainDownload() {
        if (brainDownloadRunning) return
        brainDownloadRunning = true
        downloadCancelled = false
        downloadPaused = false
        val handle = AgentServer.PullHandle()
        pullHandle = handle
        binding.btnBrainDownload.text = getString(R.string.download_pause)
        binding.btnBrainDownloadCancel.visibility = View.VISIBLE
        // p60: while a download runs the X is cancel again (it was
        // dismiss in idle); the tap dispatches on this state.
        binding.btnBrainDownloadCancel.contentDescription =
            getString(R.string.download_cancel)
        binding.brainDownloadProgress.visibility = View.VISIBLE
        binding.brainDownloadProgress.progress = lastDownloadPct
        val appContext = context?.applicationContext
        if (appContext == null) {
            brainDownloadRunning = false
            pullHandle = null
            resetBrainDownloadCard()
            return
        }
        Thread {
            // The pull needs the servers up; ensureStarted is cheap
            // when they already run (it returns immediately).
            AgentServer.ensureStarted(appContext)
            val model = downloadModel
            val isVision = model == AgentServer.VISION_MODEL
            val ok = AgentServer.pullModel(model, { completed, total ->
                val act = activity ?: return@pullModel
                act.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    if (total > 0) {
                        val pct = (completed * 100 / total).toInt().coerceIn(0, 100)
                        lastDownloadPct = pct
                        binding.brainDownloadProgress.progress = pct
                        binding.brainDownloadText.text =
                            getString(
                                if (isVision) R.string.vision_download_downloading
                                else R.string.brain_download_downloading, pct
                            )
                    }
                }
            }, handle)
            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                brainDownloadRunning = false
                pullHandle = null
                when {
                    downloadCancelled -> {
                        // Aborted by the cancel button: delete the
                        // (partial) model and reset the card.
                        downloadCancelled = false
                        deleteDownloadedModel()
                    }
                    ok -> {
                        downloadPaused = false
                        hideBrainDownloadCard()
                        Toast.makeText(
                            requireContext(),
                            if (isVision) R.string.vision_download_done
                            else R.string.brain_download_done,
                            Toast.LENGTH_SHORT
                        ).show()
                        // A downloaded vision model enables the chip.
                        refreshVisionChip()
                    }
                    downloadPaused -> {
                        // Aborted by the pause button: keep the
                        // percent, offer resume, keep cancel.
                        binding.btnBrainDownload.isEnabled = true
                        binding.btnBrainDownload.text =
                            getString(R.string.download_resume)
                        binding.brainDownloadText.text =
                            getString(R.string.download_paused, lastDownloadPct)
                    }
                    else -> {
                        binding.btnBrainDownload.isEnabled = true
                        binding.brainDownloadText.text =
                            getString(R.string.brain_download_failed)
                        binding.brainDownloadProgress.visibility = View.GONE
                        binding.btnBrainDownloadCancel.visibility = View.GONE
                        binding.btnBrainDownload.text = getString(
                            if (isVision) R.string.vision_download_button
                            else R.string.brain_download_button
                        )
                    }
                }
            }
        }.start()
    }

    private fun sendMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        if (turnRunning) {
            // Consolidated fix item 8: a send rejected while a turn
            // runs must not leave its text in the field, or the next
            // send stores it concatenated with the new question.
            // The guard is the real gate: the button stays enabled
            // during a turn so a rejected tap reaches this code.
            _binding?.inputText?.setText("")
            // Never swallow a send silently: the keyboard send key
            // and the quick chips can still reach here.
            Toast.makeText(requireContext(), R.string.agent_thinking, Toast.LENGTH_SHORT).show()
            return
        }
        hideAgentAction()
        // p57: read the reply language live, so the stored
        // per-message language matches the turn that follows.
        replyLanguage = liveReplyLanguage()
        val userMessage = AgentMessage(AgentMessage.ROLE_USER, text, replyLanguage)
        appendMessage(userMessage)
        lastUserMessage = userMessage
        // Hole 5: durable in-flight marker, one-shot per send. A
        // new send overwrites it. The detector reads it to tell a
        // turn still starting in this process apart from a turn
        // that died with it.
        prefs().edit()
            .putLong(KEY_TURN_IN_FLIGHT_AT, System.currentTimeMillis())
            .putInt(KEY_TURN_IN_FLIGHT_PID, Process.myPid())
            .apply()
        startTurn(text)
    }

    private fun retryLastMessage() {
        val last = lastUserMessage ?: return
        if (turnRunning) return
        // p107: never re-send a blank question. A blank last text
        // would append an empty user bubble; log it and stop.
        if (last.text.isBlank()) {
            Log.w("MudifAgent", "retryLastMessage dropped blank last text")
            return
        }
        hideAgentAction()
        // p57: read the reply language live, like sendMessage.
        replyLanguage = liveReplyLanguage()
        // Phase 3: the retry re-sends visibly, as a user message of
        // its own, so the user can tell it re-sent anything.
        val userMessage = AgentMessage(AgentMessage.ROLE_USER, last.text, replyLanguage)
        appendMessage(userMessage)
        lastUserMessage = userMessage
        startTurn(last.text)
    }

    /**
     * Phase 5: one real chat turn. Background thread: make sure the
     * brain server is running (AgentServer.ensureStarted boots the
     * guest and starts opencode when needed), get or create the
     * session, then stream the reply into the agent bubble.
     * Every failure lands as an agent error message with a real
     * action (Retry, or "open desktop" when the engine is not
     * installed). Nothing is faked. [screenContext] (vision
     * increment 1) is what the agent saw on the screen; it is sent
     * as a second text part, observed context, never an instruction.
     */
    private fun startTurn(text: String, screenContext: String? = null, imageBase64: String? = null, isCloudVision: Boolean = false) {
        // p74: heartbeat before the heavy work starts, so a
        // death mid-turn leaves a fresh memory snapshot behind.
        // requireContext is safe here: startTurn only runs with
        // a live fragment (clearTurnFailed below uses it too).
        AppDeathDetector.heartbeat(requireContext(), tab = "AgentFragment", action = "turn_send", force = true, foreground = true)
        // p57: read the reply language live here, at turn start, so
        // a language change takes effect on this turn. The system
        // prompt, the vision prefix, and every built-in reply below
        // all use this field; no session restart is needed, because
        // AgentClient sends the system prompt again on every turn.
        replyLanguage = liveReplyLanguage()
        // p34f fix 4: a new turn starts, the old failure is spent.
        clearTurnFailed(requireContext())
        turnRunning = true
        // Brain fix (plan section 11): the local brain is slow to
        // first token; after 60 s without one, offer a free cloud
        // brain. The check no-ops when the turn already ended.
        turnSeq++
        val thisTurnSeq = turnSeq
        firstTokenArrived = false
        hideSlowBrainChip()
        binding.root.postDelayed({
            // Phase 3: the chip names the LOCAL brain ("المحلي
            // بطيء"); a cloud turn must never show it.
            if (_binding != null && thisTurnSeq == turnSeq && turnRunning && !firstTokenArrived &&
                brainModel == BRAIN_LOCAL
            ) {
                showSlowBrainChip()
            }
        }, LOCAL_SLOW_CHIP_DELAY_MS)
        // p33: while the keyboard is open the thinking line + action
        // button stay hidden (applyChromeVisibility owns them then);
        // showing them here would re-squeeze the message list.
        if (!keyboardOpen) {
            binding.agentTyping.text = getString(R.string.agent_starting_server)
            binding.agentTyping.visibility = View.VISIBLE
        }
        // Consolidated fix item 8: the button stays enabled during
        // a turn. The turnRunning guard in sendMessage is the real
        // gate; a disabled button swallows the tap, so a rejected
        // send never reached the code that clears the field.
        // Phase 3: the Stop button is armed up front, so it is a
        // valid abort control for the whole turn, keyboard open or
        // not (applyChromeVisibility shows it while typing).
        binding.btnAgentAction.text = getString(R.string.agent_stop)
        binding.btnAgentAction.setOnClickListener { stopCurrentTurn() }
        applyChromeVisibility()
        // Application context: the thread outlives the view, and
        // requireContext() would throw if the fragment detaches.
        val appContext = context?.applicationContext ?: run {
            onTurnFailed(null)
            return
        }
        // Slice 1 (agent-as-OS): the turn itself is logged, with
        // the CLI verb when the user wrote one, else "chat".
        UsageLog.append(appContext, "chat_command", UsageLog.verbOrChat(text), true)
        runTurnThread {
            // Brain fix (plan section 10): user-pasted URLs join the
            // fetch allowlist so the brain may open them. Best-effort,
            // never blocks the turn on failure.
            try {
                val pasted = PASTED_URL_REGEX.findAll(text)
                    .map { it.value.trimEnd(',', '.', ';', ':', '!', '?', ')', '"', '\'') }
                    .filter { it.isNotEmpty() }.toList()
                if (pasted.isNotEmpty()) {
                    val epoch = System.currentTimeMillis() / 1000
                    val args = pasted.joinToString(" ") { url ->
                        "'$epoch\t${url.replace("'", "'\\''")}'"
                    }
                    TerminalSession.runCommandDebian(
                        appContext,
                        "printf '%s\\n' $args >> /tmp/mudif-allowed-urls.txt",
                        10L, 30L
                    )
                }
            } catch (_: Exception) {
                // Best-effort only; the turn continues without it.
            }
            // Supervise the turn: the foreground service keeps the
            // bridge and the brain server alive while the agent
            // works (Android 12+ phantom killer / Doze). It is
            // idempotent; starting it again while it runs is a no-op.
            // ensureStarted below still runs so this turn gets the
            // real up/down verdict for its error message.
            // Phase 3: best-effort; on Android 12+ this throws when
            // the app is backgrounded, and an uncaught throw here
            // wedged the chat with turnRunning stuck true.
            try {
                AgentSupervisorService.start(appContext)
            } catch (e: Exception) {
                android.util.Log.w("AgentFragment", "supervisor start failed (best-effort)", e)
            }
            // p35c: a model pick restarts the server on a tracked
            // thread; the turn waits for that restart (or its one
            // retry) before ensureStarted, or ensureStarted would
            // short-circuit on the still-running old server and the
            // fresh session would pin the old model.
            val restartOk = waitForBrainRestart(appContext)
            val ensured = AgentServer.ensureStarted(appContext)
            MudifLog.i("MudifAgent", "startTurn ensureStarted up=${ensured.up} reason=${ensured.reason} brainModel=$brainModel")
            val act = activity
            if (act == null) {
                // Phase 3: no activity to post to; clear the flag
                // here or the chat wedges with no error.
                turnRunning = false
                currentTurn = null
                streamingIndex = -1
                return@runTurnThread
            }
            if (!restartOk) {
                // p35c: the server never verified on the picked
                // model; answering now would answer with the old
                // one. Fail honestly instead, with Retry.
                failTurnInBackground(act, appContext, AgentServer.REASON_START_FAILED, null)
                return@runTurnThread
            }
            if (!ensured.up) {
                failTurnInBackground(act, appContext, ensured.reason, null)
                return@runTurnThread
            }
            // p34g fix 1: the missing-local-model gate applies ONLY
            // to a local turn. A cloud brain needs no local model;
            // the old unconditional gate killed every cloud turn on
            // a rig without qwen3:1.7b, before any session existed.
            val hasBrain = AgentServer.hasBrainModel()
            MudifLog.i("MudifAgent", "startTurn gate hasBrainModel=$hasBrain brainModel=$brainModel")
            if (brainModel == BRAIN_LOCAL && hasBrain == false) {
                // Phase 3: the flag clears in the background thread
                // first; endTurnUi only runs when the view is alive.
                turnRunning = false
                currentTurn = null
                streamingIndex = -1
                // p34g fix 3: a failed turn, not a silent stop:
                // persist it so Retry survives a relaunch. The
                // download card stays as the model remedy.
                markTurnFailed(appContext, false)
                act.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    showBrainDownloadCard()
                    // p34g fix 3: endTurnUi would clear the stored
                    // failure and hide the action button, so the
                    // terminal tail is done here instead: the turn
                    // is ended, the typing line hides, Send returns,
                    // and Retry shows.
                    endedTurnSeq = turnSeq
                    // Hole 5: terminal path that bypasses endTurnUi;
                    // the in-flight marker is spent.
                    clearTurnInFlightMarker(appContext)
                    showTurnFailedUi(null, null)
                    hideSlowBrainChip()
                }
                return@runTurnThread
            }
            // p35c second attempt, hole 3: re-check immediately
            // before session creation, so a pick landing after
            // the first gate cannot slip a session onto the
            // old-model server. The re-check alone only narrowed
            // the window to microseconds; it could not close it.
            if (!waitForBrainRestart(appContext)) {
                failTurnInBackground(act, appContext, AgentServer.REASON_START_FAILED, null)
                return@runTurnThread
            }
            // p35d: the flag re-check, the session read, the
            // creation POST and the publish run under
            // brainRestartLock, so no pick can land between them.
            // waitForBrainRestart joins the restart thread, so it
            // can never run inside the lock: the restart thread
            // needs the lock to clear its flags, and a join under
            // the lock would deadlock. The locked re-check is a
            // flag snapshot instead. A pick that landed after the
            // unlocked wait fails this turn honestly with Retry
            // instead of answering on the old model; no second
            // wait runs here, so the wait budget does not grow.
            // The lock is held only across session creation, never
            // across the prompt post or the whole turn. The
            // restart thread needs the lock only at the end of a
            // run, to set its flags; it holds nothing the turn
            // needs, so it waits and a hung POST ends the hold.
            var sid: String? = null
            var restartPending = false
            synchronized(brainRestartLock) {
                restartPending = brainRestartRunning || brainRestartFailed
                if (!restartPending) {
                    sid = sessionId
                    if (sid.isNullOrBlank()) {
                        sid = AgentClient.createSession()
                        MudifLog.i("MudifAgent", "startTurn createSession ok=${!sid.isNullOrBlank()}")
                        if (!sid.isNullOrBlank()) {
                            sessionId = sid
                            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putString(KEY_SESSION_ID, sid).apply()
                        }
                    }
                }
            }
            if (restartPending) {
                failTurnInBackground(act, appContext, AgentServer.REASON_START_FAILED, null)
                return@runTurnThread
            }
            val useSid = sid
            if (useSid.isNullOrBlank()) {
                failTurnInBackground(act, appContext, null, null)
                return@runTurnThread
            }
            val callbacks = object : AgentClient.TurnCallbacks {
                override fun onText(fullText: String) {
                    act.runOnUiThread {
                        // Consolidated fix item 4b: a terminal event
                        // from a superseded turn must never touch
                        // the new turn. Streaming text is useless
                        // without its terminal event, so drop it.
                        if (thisTurnSeq != turnSeq) return@runOnUiThread
                        if (_binding == null) {
                            // Phase 3: view gone (tab switch). A turn
                            // already ended drops late text, like
                            // showStreamingText does when alive; a
                            // live turn keeps the text in the list
                            // so the reply survives the switch.
                            if (!turnRunning) return@runOnUiThread
                            if (streamingIndex < 0) {
                                messages.add(
                                    AgentMessage(
                                        AgentMessage.ROLE_AGENT, fullText, replyLanguage
                                    )
                                )
                                streamingIndex = messages.size - 1
                            } else {
                                messages[streamingIndex] =
                                    AgentMessage(
                                        AgentMessage.ROLE_AGENT, fullText, replyLanguage
                                    )
                            }
                            saveMessages()
                            return@runOnUiThread
                        }
                        firstTokenArrived = true
                        hideSlowBrainChip()
                        showStreamingText(fullText)
                    }
                }

                override fun onTool(tool: String) {
                    act.runOnUiThread {
                        // Hole 11: same stale gate as onText, onDone
                        // and onError. A superseded turn must not
                        // rewrite the typing line of the new turn.
                        if (thisTurnSeq != turnSeq) return@runOnUiThread
                        if (_binding == null) return@runOnUiThread
                        // p33: hidden while the keyboard is open (the
                        // message list needs the room); it returns
                        // when the keyboard closes.
                        if (keyboardOpen) return@runOnUiThread
                        binding.agentTyping.text =
                            getString(R.string.agent_tool_running, tool)
                        binding.agentTyping.visibility = View.VISIBLE
                    }
                }

                override fun onDone(finalText: String) {
                    act.runOnUiThread {
                        // Consolidated fix item 4b: a superseded
                        // turn's reply is still real text; persist
                        // it durably (refresh first, the old
                        // instance's list is stale after a
                        // recreation) and never touch the new turn.
                        if (thisTurnSeq != turnSeq) {
                            // Holes 3 and 6: this turn already ended
                            // in this instance (Stop, clean end, or
                            // failure), so a late terminal event must
                            // never add a second verdict or revive the
                            // failure mark.
                            if (endedTurnSeq >= thisTurnSeq) return@runOnUiThread
                            mergeReloadedMessages()
                            if (finalText.isNotEmpty()) {
                                persistMessage(
                                    AgentMessage(
                                        AgentMessage.ROLE_AGENT, finalText, replyLanguage
                                    )
                                )
                            }
                            clearTurnInFlightMarker(act.applicationContext)
                            return@runOnUiThread
                        }
                        if (_binding == null) {
                            // Phase 3: view gone. A turn already
                            // ended drops the late done, like
                            // finishTurn does when alive; a live turn
                            // keeps its reply, then clears the flags.
                            if (!turnRunning) return@runOnUiThread
                            if (streamingIndex < 0 && finalText.isNotEmpty()) {
                                persistMessage(
                                    AgentMessage(
                                        AgentMessage.ROLE_AGENT, finalText, replyLanguage
                                    )
                                )
                            }
                            // Holes 3 and 6: this view-gone path ends
                            // the turn without endTurnUi, so record
                            // the terminal seq here.
                            endedTurnSeq = turnSeq
                            // Hole 5: terminal path; the in-flight
                            // marker is spent.
                            clearTurnInFlightMarker(act.applicationContext)
                            turnRunning = false
                            currentTurn = null
                            streamingIndex = -1
                            return@runOnUiThread
                        }
                        finishTurn(finalText)
                    }
                }

                override fun onError(message: String) {
                    act.runOnUiThread {
                        // Consolidated fix item 4b: a superseded
                        // turn's failure must be durable, or its
                        // timeout vanishes with no verdict and no
                        // Retry. Persist it (refresh first) and
                        // mark it failed; never touch the new turn.
                        if (thisTurnSeq != turnSeq) {
                            // Holes 3 and 6: this turn already ended
                            // in this instance (Stop, clean end, or
                            // failure), so a late terminal event must
                            // never add a second verdict or revive the
                            // failure mark.
                            if (endedTurnSeq >= thisTurnSeq) return@runOnUiThread
                            mergeReloadedMessages()
                            persistMessage(
                                AgentMessage(
                                    AgentMessage.ROLE_AGENT,
                                    verdictFor(null, message, act),
                                    replyLanguage
                                )
                            )
                            markTurnFailed(act.applicationContext, false)
                            clearTurnInFlightMarker(act.applicationContext)
                            MudifLog.i("MudifAgent", "stale onError persisted seq=$thisTurnSeq")
                            return@runOnUiThread
                        }
                        if (_binding == null) {
                            // Phase 3: view gone. A turn already
                            // ended drops the late error; a live turn
                            // keeps its verdict, then clears the
                            // flags, so the next send can start.
                            if (!turnRunning) return@runOnUiThread
                            persistMessage(
                                AgentMessage(
                                    AgentMessage.ROLE_AGENT,
                                    verdictFor(null, message, act),
                                    replyLanguage
                                )
                            )
                            // Consolidated fix item 3: a failure
                            // recorded while the view is gone must
                            // still be durable, or no Retry survives
                            // a relaunch for it.
                            markTurnFailed(act, false)
                            MudifLog.i("MudifAgent", "onError view-gone marked failed")
                            // Holes 3 and 6: this view-gone path ends
                            // the turn without endTurnUi, so record
                            // the terminal seq here.
                            endedTurnSeq = turnSeq
                            // Hole 5: terminal path; the in-flight
                            // marker is spent.
                            clearTurnInFlightMarker(act.applicationContext)
                            turnRunning = false
                            currentTurn = null
                            streamingIndex = -1
                            return@runOnUiThread
                        }
                        // p34g: a late onError after the turn already
                        // ended is stale, like the view-gone branch
                        // above. A user Stop with zero tokens ends
                        // the turn synchronously (agent_stopped +
                        // endTurnUi), and the post thread's done("")
                        // now routes through failed(), so without
                        // this guard the Stop gains a second verdict
                        // plus a durable Retry. A genuine failure
                        // fires while turnRunning is still true and
                        // still reaches onTurnFailed.
                        if (!turnRunning) return@runOnUiThread
                        onTurnFailed(null, message)
                    }
                }
            }
            // Phase 3: the user may have stopped the turn while the
            // server was starting (Stop is armed up front now); a
            // stopped turn must not post a prompt it will only drop.
            if (!turnRunning) return@runTurnThread
            // Phase 3: a local turn's model must be stopped when the
            // turn times out or is cancelled; the OpenCode abort
            // alone never stopped it. Cloud turns pass no stopper.
            val localStop: (() -> String)? =
                if (brainModel == BRAIN_LOCAL) {
                    {
                        AgentServer.stopLocalModel(
                            appContext,
                            brainModel.removePrefix("ollama/")
                        )
                    }
                } else {
                    null
                }
            // Consolidated fix item 4: one-shot silent-turn hook
            // for the timeout test. Read and cleared here, not at
            // the top of startTurn, so a turn that fails earlier
            // never consumes the arming.
            // Hole 10: createSession() above runs before this read,
            // so a hooked turn after Clear or on a fresh install
            // already POSTed a new session row before parking. The
            // verifier must have a stored session id before arming,
            // or the DB check is conditional on that.
            val silentHookPrefs = appContext.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
            )
            val silentHook = silentHookPrefs.getBoolean(KEY_TEST_SILENT_TURN, false)
            if (silentHook) {
                silentHookPrefs.edit().remove(KEY_TEST_SILENT_TURN).apply()
            }
            val handle = AgentClient.sendPrompt(
                useSid, text, AgentClient.systemPrompt(replyLanguage), callbacks,
                screenContext, imageBase64, localStop, testSilentTurn = silentHook,
                replyLanguage = replyLanguage, isCloudVision = isCloudVision
            )
            act.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                // p34f fix 4: the terminal state sticks. This post
                // runs after sendPrompt returns, but a terminal event
                // can land first: a fast failure in sendPrompt's
                // worker, or a Stop tapped between the pre-send
                // guard and this post. Re-arming here would revive
                // a dead turn, hide its Retry, and wedge the chat.
                // View recreation alone never marks a turn ended,
                // so the wedge-reset re-arm still works.
                if (thisTurnSeq != turnSeq || endedTurnSeq == thisTurnSeq) {
                    return@runOnUiThread
                }
                currentTurn = handle
                // Phase 3: the view may have been recreated after a
                // wedge reset while this turn was starting; the turn
                // is running now, so the flag must be true again.
                turnRunning = true
                // The Stop button was armed up front in startTurn;
                // only the thinking line is (re)set here, and the
                // chrome is re-synced for the keyboard state.
                if (!keyboardOpen) {
                    binding.agentTyping.text = getString(R.string.agent_thinking)
                    binding.agentTyping.visibility = View.VISIBLE
                }
                applyChromeVisibility()
            }
        }
    }

    // ---------------------------------------------------------- vision (increment 1)

    /** The "شوف الشاشة" chip: the assistant looks at the desktop
     *  and describes it. Blocked while a turn runs, like a send. */
    private fun startVisionLook() {
        if (turnRunning) {
            Toast.makeText(requireContext(), R.string.agent_thinking, Toast.LENGTH_SHORT).show()
            return
        }
        hideAgentAction()
        // p57: read the reply language live, like sendMessage.
        replyLanguage = liveReplyLanguage()
        val question = getString(R.string.agent_vision_question)
        val userMessage = AgentMessage(AgentMessage.ROLE_USER, question, replyLanguage)
        appendMessage(userMessage)
        lastUserMessage = userMessage
        startVisionTurn(question)
    }

    /**
     * Vision increment 1 flow (background thread, UI posts only):
     * servers up -> vision model present -> capture one frame ->
     * qwen2.5vl:3b describes it -> the description is shown in the
     * chat -> the brain answers with it as observed context.
     * Every failure ends the turn with the real reason; nothing is
     * faked and no stale frame is ever read as new.
     */
    private fun startVisionTurn(question: String) {
        turnRunning = true
        if (!keyboardOpen) {
            binding.agentTyping.text = getString(R.string.agent_vision_looking)
            binding.agentTyping.visibility = View.VISIBLE
        }
        // Consolidated fix item 8: same as startTurn, the button
        // stays enabled during a vision turn so a rejected send
        // reaches the guard in sendMessage and clears the field.
        val appContext = context?.applicationContext ?: run {
            onTurnFailed(null)
            return
        }
        runTurnThread {
            // Phase 3: best-effort (see startTurn: an uncaught
            // throw here wedged the chat with turnRunning stuck).
            try {
                AgentSupervisorService.start(appContext)
            } catch (e: Exception) {
                android.util.Log.w("AgentFragment", "supervisor start failed (best-effort)", e)
            }
            // p35c second attempt, hole 5: wait out a brain
            // restart before ensureStarted, so vision never
            // starts a server beside the restart thread.
            val restartOk = waitForBrainRestart(appContext)
            val ensured = AgentServer.ensureStarted(appContext)
            MudifLog.i("MudifAgent", "startVisionTurn ensureStarted up=${ensured.up} reason=${ensured.reason}")
            val act = activity
            if (act == null) {
                // Phase 3: no activity to post to; clear the flag
                // here or the chat wedges with no error.
                turnRunning = false
                currentTurn = null
                streamingIndex = -1
                return@runTurnThread
            }
            if (!restartOk) {
                failTurnInBackground(act, appContext, AgentServer.REASON_START_FAILED, null) { startVisionTurn(question) }
                refreshVisionChip()
                return@runTurnThread
            }
            if (!ensured.up) {
                failTurnInBackground(act, appContext, ensured.reason, null) { startVisionTurn(question) }
                refreshVisionChip()
                return@runTurnThread
            }
            // Vision increment 2: a picked cloud vision model (with
            // its key) replaces the local describe step; the local
            // model gate is skipped then, because the local model
            // is not needed at all.
            val visionModelId = getVisionModel(appContext)
            // loadBrainOptions reads the asset through
            // requireContext; a detach in this window must fail
            // the cloud lookup, never wedge the turn.
            val cloudOption = try {
                visionModelId.takeIf { it.isNotBlank() }?.let { id ->
                    loadBrainOptions().firstOrNull { it.id == id }
                }
            } catch (e: Exception) {
                null
            }
            val cloudKeyMissing = cloudOption?.let { opt ->
                opt.env?.let { env ->
                    val prefKey = BrainProviders.prefKeyForEnv(env)
                    prefKey == null ||
                        com.bolm.mudif.ui.settings.ViewerPrefs.prefs(appContext)
                            .getString(prefKey, "").isNullOrBlank()
                } ?: false
            } ?: false
            val cloudActive = cloudOption != null && !cloudKeyMissing
            if (cloudOption != null && cloudKeyMissing) {
                // The user picked cloud vision but never pasted the
                // key: fail honestly with the remedy, never fall
                // back to local silently.
                failTurnInBackground(
                    act, appContext, null,
                    appContext.getString(
                        R.string.agent_vision_key_missing, cloudOption.env ?: ""
                    )
                ) { startVisionTurn(question) }
                refreshVisionChip()
                return@runTurnThread
            }
            // Vision model gate: missing means the download card,
            // not a turn that can only fail. Skipped for cloud
            // vision: the local model is not needed there.
            val hasVisionModel = if (cloudActive) true
                else AgentServer.hasModel(AgentServer.VISION_MODEL)
            MudifLog.i("MudifAgent", "startVisionTurn gate hasVisionModel=$hasVisionModel")
            if (hasVisionModel == false) {
                turnRunning = false
                currentTurn = null
                streamingIndex = -1
                // p34g fix 3: persist the failure so Retry survives
                // a relaunch; the retry re-runs the vision turn.
                markTurnFailed(appContext, true)
                act.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    showBrainDownloadCard(AgentServer.VISION_MODEL)
                    // p34g fix 3: endTurnUi would clear the stored
                    // failure and hide the action button, so the
                    // terminal tail is done here instead.
                    endedTurnSeq = turnSeq
                    // Hole 5: terminal path that bypasses endTurnUi;
                    // the in-flight marker is spent.
                    clearTurnInFlightMarker(appContext)
                    showTurnFailedUi(null) { startVisionTurn(question) }
                    refreshVisionChip()
                }
                return@runTurnThread
            }
            val t0 = SystemClock.elapsedRealtime()
            val capture = VisionCapture.capture(appContext)
            if (capture is VisionCapture.CaptureResult.Failed) {
                failTurnInBackground(
                    act, appContext, null,
                    appContext.getString(
                        if (capture.reason == VisionCapture.REASON_DESKTOP_DOWN)
                            R.string.agent_vision_desktop_down
                        else R.string.agent_vision_capture_failed
                    )
                ) { startVisionTurn(question) }
                refreshVisionChip()
                return@runTurnThread
            }
            val shot = (capture as VisionCapture.CaptureResult.Ok).file
            val jpeg = try {
                shot.readBytes()
            } catch (e: Exception) {
                null
            }
            if (cloudActive && cloudOption != null) {
                // Cloud vision: the screenshot itself goes to the
                // vision model as a file part, and the reply IS the
                // description. The text brain is restored when this
                // turn ends (see applyPendingBrainPick).
                val imageBase64 = jpeg?.let {
                    android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
                }
                // Charter: the screenshot is deleted after the turn,
                // success or failure; the bytes already live in memory.
                try { shot.delete() } catch (e: Exception) { }
                if (imageBase64 == null) {
                    failTurnInBackground(
                        act, appContext, null,
                        appContext.getString(R.string.agent_vision_capture_failed)
                    ) { startVisionTurn(question) }
                    return@runTurnThread
                }
                act.runOnUiThread {
                    if (_binding == null) {
                        // Phase 3: view gone. No description exists
                        // yet, so there is nothing to keep; the
                        // flags clear so the chat never wedges.
                        turnRunning = false
                        currentTurn = null
                        streamingIndex = -1
                        return@runOnUiThread
                    }
                    // Switch to the vision model first; the text
                    // brain is recorded for the restore. If the
                    // switch refuses (no key, write failed) the
                    // turn fails honestly instead of sending the
                    // image to a model that cannot see it.
                    pendingBrainPick = brainModel
                    prefs().edit().putString(KEY_PENDING_BRAIN_PICK, brainModel).apply()
                    applyBrainPick(cloudOption.id)
                    if (brainModel != cloudOption.id) {
                        onTurnFailed(null, getString(R.string.agent_brain_failed))
                        return@runOnUiThread
                    }
                    appendMessage(
                        AgentMessage(
                            AgentMessage.ROLE_AGENT,
                            getString(R.string.agent_vision_cloud_sent, cloudOption.label),
                            replyLanguage
                        )
                    )
                    // Slice 1 (agent-as-OS): the cloud vision turn
                    // is sent now; the description comes back as the
                    // turn's reply.
                    UsageLog.append(appContext, "vision_turn", "cloud", true)
                    startTurn(question, null, imageBase64, isCloudVision = true)
                }
                return@runTurnThread
            }
            val description = jpeg?.let { VisionClient.describe(it, question) }
            // Charter: the screenshot is deleted after the turn,
            // success or failure; the bytes already live in memory.
            val deleted = try { shot.delete() } catch (e: Exception) { false }
            MudifLog.d(
                "AgentFragment",
                "vision shot deleted after turn: $deleted"
            )
            val seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0
            MudifLog.d(
                "AgentFragment",
                "vision read: capture+describe took ${"%.1f".format(Locale.US, seconds)} s"
            )
            if (description.isNullOrBlank()) {
                // Retry re-runs the VISION turn, not a plain
                // brain turn: a plain retry would answer the
                // question without ever looking at the screen.
                failTurnInBackground(
                    act, appContext, null,
                    appContext.getString(R.string.agent_vision_failed)
                ) { startVisionTurn(question) }
                return@runTurnThread
            }
            // The agent shows what it saw, then the brain answers
            // with it. startTurn runs on the UI thread here.
            act.runOnUiThread {
                if (_binding == null) {
                    // Phase 3: view gone. The description text is
                    // kept in the list, the brain answer cannot
                    // start without a view, and the flags clear so
                    // the chat never wedges.
                    persistMessage(
                        AgentMessage(
                            AgentMessage.ROLE_AGENT,
                            appContext.getString(R.string.agent_vision_saw, description),
                            replyLanguage
                        )
                    )
                    turnRunning = false
                    currentTurn = null
                    streamingIndex = -1
                    return@runOnUiThread
                }
                appendMessage(
                    AgentMessage(
                        AgentMessage.ROLE_AGENT,
                        getString(R.string.agent_vision_saw, description),
                        replyLanguage
                    )
                )
                // Slice 1 (agent-as-OS): the local description was
                // just shown; the brain answers with it now.
                UsageLog.append(appContext, "vision_turn", "local", true)
                startTurn(question, description)
            }
        }
    }

    /** Enables the "شوف الشاشة" chip only when vision can really
     *  run (desktop up AND qwen2.5vl:3b present); otherwise it stays
     *  disabled with the reason as its visible text. Background
     *  thread: the checks are TCP probes, never on the UI thread. */
    private fun refreshVisionChip() {
        // p34g fix 4: the application context is captured here, on
        // the UI thread, so the background probe never touches a
        // detached fragment context.
        val appContext = context?.applicationContext
        Thread {
            // p34g fix 4: the TCP probe alone stays open when
            // lxqt-session is a zombie, so the chip read "ready"
            // while the desktop was down. The session check runs
            // second, only when the display socket answers.
            val desktopUp = appContext != null &&
                X11Session.isTcpDisplayOpen() &&
                X11Session.isDesktopSessionAlive(appContext)
            val hasVision = AgentServer.hasModel(AgentServer.VISION_MODEL)
            // Vision increment 2: a picked cloud vision model makes
            // the chip work without the local model. A picked model
            // whose key is missing still enables the chip: tapping
            // it fails honestly with the key remedy.
            val cloudVision = appContext != null &&
                getVisionModel(appContext).isNotBlank()
            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                val enabled = desktopUp && (hasVision == true || cloudVision)
                // Phase 3: three states, three reasons. hasModel
                // returns null when Ollama is unreachable; that
                // is a dead SERVICE, not a missing model.
                val labelRes = when {
                    !desktopUp -> R.string.agent_vision_chip_unavailable_desktop
                    hasVision == null && !cloudVision -> R.string.agent_vision_chip_unavailable_service
                    hasVision == false && !cloudVision -> R.string.agent_vision_chip_unavailable_model
                    else -> R.string.agent_vision_chip
                }
                binding.chipVision.isEnabled = enabled
                binding.chipVision.alpha = if (enabled) 1.0f else 0.4f
                // p34f fix 6: the state must be VISIBLE text, not
                // only a contentDescription. The label never
                // changed, so a disabled chip still read "See the
                // screen" and looked ready, and the greyed disabled
                // rendering read as a fourth state. No fourth state
                // exists now: the text always names the state.
                binding.chipVision.text = getString(labelRes)
                binding.chipVision.contentDescription = getString(labelRes)
            }
        }.start()
    }

    /** Brain fix (plan section 11): the local-slow chip. It
     *  offers a switch to a free cloud brain (Nemotron Free, Token
     *  Lab DeepSeek V4.1 Flash) after 60 s without a first token. It
     *  stops the turn and opens the provider screen; applying the
     *  pick is the user's tap in that screen. */
    private fun showSlowBrainChip() {
        // Phase 3: the chip replaces the typing line as the status
        // (it must not stack with it and crush the list).
        _binding?.agentTyping?.visibility = View.GONE
        _binding?.chipSlowBrain?.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                hideSlowBrainChip()
                // Phase 3: do nothing about stopping when no turn
                // is running; a stale chip must never store a
                // spurious "توقّف الرد" or clear Retry.
                if (turnRunning) stopCurrentTurn()
                openBrainProviders()
            }
        }
    }

    private fun hideSlowBrainChip() {
        _binding?.chipSlowBrain?.visibility = View.GONE
    }

    /** Stops the running turn (user tapped إيقاف). The partial reply
     *  stays as it is: a real partial result, not a fake. */
    private fun stopCurrentTurn() {
        currentTurn?.cancel()
        currentTurn = null
        if (streamingIndex < 0) {
            appendMessage(
                AgentMessage(AgentMessage.ROLE_AGENT, getString(R.string.agent_stopped), replyLanguage)
            )
        }
        endTurnUi()
    }

    /** Live-streaming update: the bubble text is SET to the full
     *  current reply (never appended), so a re-sent full part cannot
     *  duplicate text. */
    private fun showStreamingText(fullText: String) {
        // p33: a streaming event that arrives after the turn
        // ended (timeout, Stop, or the done event racing it) is
        // stale. Without this guard it appends a duplicate bubble
        // with the same text (seen on the rig: the full reply
        // appeared twice, byte-identical, after a 300 s timeout
        // followed by a retry).
        if (!turnRunning) return
        if (streamingIndex < 0) {
            messages.add(AgentMessage(AgentMessage.ROLE_AGENT, fullText, replyLanguage))
            streamingIndex = messages.size - 1
        } else {
            messages[streamingIndex] =
                AgentMessage(AgentMessage.ROLE_AGENT, fullText, replyLanguage)
        }
        saveMessages()
        adapter.submitList(messages.toList())
        binding.agentRecycler.scrollToPosition(messages.size - 1)
        // p74: throttled heartbeat during a long turn, so a death
        // mid-stream leaves a memory snapshot no older than 5 s.
        AppDeathDetector.heartbeat(requireContext(), tab = "AgentFragment", action = "turn_stream", foreground = true)
    }

    private fun finishTurn(finalText: String) {
        // p33: a late done event after the turn already ended
        // (timeout, Stop, retry) is stale; without this guard it
        // appends a second copy of the reply, and a stopped turn
        // would speak its aborted text.
        if (!turnRunning) return
        // p74: the turn completed; record where we are and how
        // much memory the device has left.
        AppDeathDetector.heartbeat(requireContext(), tab = "AgentFragment", action = "turn_reply", force = true, foreground = true)
        // Brain fix (plan section 3): an empty final text is a
        // failure, never a silent success. The Nemotron empty reply
        // used to end the turn with no message and no Retry.
        val emptyReply = (streamingIndex < 0 && finalText.isEmpty()) ||
            (streamingIndex >= 0 && messages[streamingIndex].text.isEmpty())
        if (emptyReply) {
            currentTurn = null
            // The empty streaming bubble is already in messages (and
            // in prefs: showStreamingText saves on every update), so
            // drop it, or the empty reply stays stored as a blank
            // bubble.
            if (streamingIndex >= 0) {
                messages.removeAt(streamingIndex)
                saveMessages()
                adapter.submitList(messages.toList())
            }
            streamingIndex = -1
            onTurnFailed(null, getString(R.string.agent_empty_reply))
            return
        }
        if (streamingIndex < 0 && finalText.isNotEmpty()) {
            appendMessage(AgentMessage(AgentMessage.ROLE_AGENT, finalText, replyLanguage))
        }
        val spoken = if (streamingIndex >= 0) messages[streamingIndex].text else finalText
        streamingIndex = -1
        currentTurn = null
        endTurnUi()
        playSendIcon(R.drawable.avd_check)
        binding.root.postDelayed({
            if (_binding != null) binding.btnSend.setImageResource(R.drawable.ic_send)
        }, 400)
        if (spoken.isNotEmpty()) speakAgent(spoken)
    }

    /**
     * Vision increment 2: a cloud vision turn switches the brain to
     * the vision model and records the text brain in
     * pendingBrainPick (plus the durable pref); the turn's terminal
     * paths call this, so the brain is restored. A user pick made
     * mid-turn overwrites the field and wins. When the view is gone
     * the pref is left alone, so the next launch still restores.
     * UI thread only (applyBrainPick toasts).
     */
    private fun applyPendingBrainPick() {
        if (!isAdded) return
        val pending = pendingBrainPick
            ?: prefs().getString(KEY_PENDING_BRAIN_PICK, null)
            ?: return
        if (_binding == null) return
        pendingBrainPick = null
        prefs().edit().remove(KEY_PENDING_BRAIN_PICK).apply()
        applyBrainPick(pending)
    }

    private fun endTurnUi() {
        turnRunning = false
        streamingIndex = -1
        // p34f fix 4: this turn terminally ended (done or stopped);
        // the post-call re-arm must not revive it.
        endedTurnSeq = turnSeq
        // Hole 5: the turn is terminally over; its in-flight
        // marker is spent.
        if (isAdded) clearTurnInFlightMarker(requireContext())
        // p34f fix 4: the turn ended cleanly (done or stopped), so
        // no stored failure may offer Retry afterwards.
        if (isAdded) clearTurnFailed(requireContext())
        binding.agentTyping.visibility = View.GONE
        binding.btnSend.isEnabled = true
        hideAgentAction()
        hideSlowBrainChip()
        // Brain fix (plan section 5): a brain picked mid-turn applies
        // now that the turn ended. Vision increment 2 reuses this
        // for the cloud-vision restore (see applyPendingBrainPick).
        applyPendingBrainPick()
    }

    /** The visible verdict for a failed turn, from a machine reason
     *  or a detail string. Takes a Context so the background thread
     *  can compute (and persist) it when the view is gone. */
    private fun verdictFor(reason: String?, detail: String?, ctx: Context): String {
        return when (reason) {
            AgentServer.REASON_ENGINE_NOT_READY ->
                ctx.getString(R.string.agent_engine_not_ready)
            AgentServer.REASON_STAGE_FAILED,
            AgentServer.REASON_BOOT_FAILED,
            AgentServer.REASON_START_FAILED ->
                ctx.getString(R.string.agent_server_start_failed)
            else -> detail?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.agent_error)
        }
    }

    /** A turn failed: real error message in the chat plus a real
     *  action. `reason` is AgentServer's machine key (null = the
     *  server was up but the turn itself failed). `onRetry`, when
     *  given, replaces the plain re-send: a vision failure retries
     *  the vision turn, which is the only retry that can succeed. */
    private fun onTurnFailed(
        reason: String?,
        detail: String? = null,
        onRetry: (() -> Unit)? = null
    ) {
        // p34g logging: every failed turn is observable in logcat
        // by its machine reason; the detail text is never logged.
        MudifLog.i("MudifAgent", "onTurnFailed reason=$reason")
        turnRunning = false
        currentTurn = null
        streamingIndex = -1
        // p34f fix 4: this turn terminally failed; the post-call
        // re-arm must not revive it.
        endedTurnSeq = turnSeq
        // Hole 5: the turn is terminally over; its in-flight
        // marker is spent.
        clearTurnInFlightMarker(requireContext())
        // Phase 3: the third turn-end path that never hid the chip;
        // the local-timeout failure left it stale over the list.
        hideSlowBrainChip()
        // p34f fix 4: persist the failure, so Retry survives a
        // relaunch. A vision retry is its own turn (onRetry set).
        markTurnFailed(requireContext(), onRetry != null)
        val verdict = verdictFor(reason, detail, requireContext())
        appendMessage(AgentMessage(AgentMessage.ROLE_AGENT, verdict, replyLanguage))
        showTurnFailedUi(reason, onRetry)
        speakAgent(verdict)
        // Vision increment 2: a failed cloud vision turn still
        // restores the text brain (same hook as endTurnUi).
        applyPendingBrainPick()
    }

    /** Phase 3: the watchdog's restart failed; retries it from the
     *  action button, then shows the honest outcome. Never
     *  fabricates; a still-down server keeps the Retry button. */
    private fun retryServerRestart() {
        val bindingNow = _binding ?: return
        bindingNow.btnAgentAction.visibility = View.GONE
        val appContext = context?.applicationContext ?: return
        Thread {
            val result = AgentServer.ensureStarted(appContext)
            bindingNow.root.post {
                val view = _binding ?: return@post
                Toast.makeText(
                    appContext,
                    if (result.up) {
                        appContext.getString(R.string.agent_servers_restarted)
                    } else {
                        appContext.getString(R.string.agent_servers_down)
                    },
                    Toast.LENGTH_LONG
                ).show()
                if (!result.up) {
                    // Keep the Retry button: the servers are
                    // still down, and silently dropping it would
                    // leave the user with no way to retry.
                    view.btnAgentAction.text =
                        appContext.getString(R.string.agent_server_retry)
                    view.btnAgentAction.setOnClickListener {
                        retryServerRestart()
                    }
                    view.btnAgentAction.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    /** View-only part of a failed turn (typing line, Send, action
     *  button). Split out so a background thread can fail a turn
     *  without touching views. */
    private fun showTurnFailedUi(reason: String?, onRetry: (() -> Unit)?) {
        binding.agentTyping.visibility = View.GONE
        binding.btnSend.isEnabled = true
        if (reason == AgentServer.REASON_ENGINE_NOT_READY) {
            binding.btnAgentAction.text = getString(R.string.agent_quick_desktop)
            binding.btnAgentAction.setOnClickListener { openDesktop() }
        } else {
            binding.btnAgentAction.text = getString(R.string.agent_server_retry)
            binding.btnAgentAction.setOnClickListener { (onRetry ?: ::retryLastMessage)() }
        }
        binding.btnAgentAction.visibility = View.VISIBLE
    }

    /** Fails a turn from a background thread: the flag reset and
     *  the verdict persistence run HERE, never only inside a
     *  _binding-guarded lambda (a view destroyed at that moment
     *  wedged the chat and lost the error). The posted lambda only
     *  refreshes the views. */
    private fun failTurnInBackground(
        act: Activity,
        ctx: Context,
        reason: String?,
        detail: String?,
        onRetry: (() -> Unit)? = null
    ) {
        // p34g logging: every background-path failure is observable
        // in logcat by its machine reason; the detail text is never
        // logged, only whether one was present.
        MudifLog.i("MudifAgent", "failTurnInBackground reason=$reason detailPresent=${!detail.isNullOrBlank()}")
        turnRunning = false
        currentTurn = null
        streamingIndex = -1
        // Holes 3 and 6: this is a terminal path that bypasses
        // endTurnUi, so record the terminal seq here, or a late
        // stale event for this turn would not be suppressed.
        endedTurnSeq = turnSeq
        // Hole 5: the turn is terminally over; its in-flight
        // marker is spent.
        clearTurnInFlightMarker(ctx)
        val verdict = verdictFor(reason, detail, ctx)
        persistMessage(AgentMessage(AgentMessage.ROLE_AGENT, verdict, replyLanguage))
        // p34f fix 4: same durable failure as onTurnFailed, from
        // the background path. A vision retry is its own turn.
        markTurnFailed(ctx, onRetry != null)
        act.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            refreshMessagesUi()
            showTurnFailedUi(reason, onRetry)
            speakAgent(verdict)
            // Vision increment 2: a background-path failure of a
            // cloud vision turn still restores the text brain.
            applyPendingBrainPick()
        }
    }

    /** View-only tail of appendMessage: re-renders the list from
     *  the persisted messages. */
    private fun refreshMessagesUi() {
        adapter.submitList(messages.toList())
        binding.agentRecycler.scrollToPosition(messages.size - 1)
        updateProfileCardVisibility()
    }

    private fun hideAgentAction() {
        binding.btnAgentAction.visibility = View.GONE
    }

    /** List-only part of appendMessage: no view access, safe from
     *  any thread. Terminal callbacks use it when the view is gone
     *  so a reply or an error verdict survives a tab switch.
     *  p107: a user message with blank text is never stored. The
     *  16:14 empty bubble was a user row whose text was blank at
     *  bind time while the stored JSON held the full question, so
     *  the row reached the list with empty text through an
     *  in-memory path; dropping it here (with a log) keeps the
     *  blank bubble out of the list and out of the prefs. */
    private fun persistMessage(message: AgentMessage) {
        if (message.role == AgentMessage.ROLE_USER && message.text.isBlank()) {
            Log.w("MudifAgent", "persistMessage dropped blank user message")
            return
        }
        messages.add(message)
        while (messages.size > MAX_HISTORY) messages.removeAt(0)
        saveMessages()
    }

    /** p107: merge a reloaded snapshot with the in-memory list
     *  instead of clearing it. The stale-terminal branches used to
     *  do messages.clear() then addAll(loadMessages()): a reload
     *  that races a retry's in-memory append drops the row the
     *  user already sees (the retry's user message is appended
     *  in memory and saved with async apply()). Rows are keyed by
     *  (time, role, text); in-memory rows absent from the
     *  snapshot are re-appended in order, so a stale event can
     *  never delete a newer row. The merged list is saved by the
     *  persistMessage that follows, so a later reload sees it. */
    private fun mergeReloadedMessages() {
        val persisted = loadMessages()
        val seen = persisted.map { Triple(it.time, it.role, it.text) }.toHashSet()
        val merged = persisted.toMutableList()
        for (m in messages) {
            val key = Triple(m.time, m.role, m.text)
            if (key !in seen) {
                merged.add(m)
                seen.add(key)
            }
        }
        messages.clear()
        messages.addAll(merged)
    }

    /** Runs a turn-setup body on a background thread and tracks
     *  it, so onViewCreated can tell a turn that is still starting
     *  apart from a wedged flag. The flag clears in a finally even
     *  if the body throws. */
    private fun runTurnThread(body: () -> Unit) {
        turnSetupRunning = true
        Thread {
            try {
                body()
            } finally {
                turnSetupRunning = false
            }
        }.start()
    }

    private fun appendMessage(message: AgentMessage) {
        persistMessage(message)
        refreshMessagesUi()
    }

    private fun ensureGreeting() {
        val live = liveReplyLanguage()
        if (messages.isEmpty()) {
            messages.add(
                AgentMessage(
                    AgentMessage.ROLE_AGENT,
                    getString(R.string.agent_greeting),
                    live
                )
            )
            saveMessages()
            return
        }
        // p57: the greeting is the only message and it was written
        // in an old language (the persisted bubble never changed
        // when the language did). Re-localize it in place instead
        // of showing a stale-language greeting under a new UI.
        // A greeting followed by a real exchange is history and
        // stays as it was said.
        if (messages.size == 1 &&
            messages[0].role == AgentMessage.ROLE_AGENT &&
            messages[0].replyLanguage != live
        ) {
            messages[0] = AgentMessage(
                AgentMessage.ROLE_AGENT,
                getString(R.string.agent_greeting),
                live,
                messages[0].time
            )
            saveMessages()
        }
    }

    private fun clearHistory() {
        messages.clear()
        saveMessages()
        // p34f fix 4: no history, no failed turn to retry.
        clearTurnFailed(requireContext())
        // p31: clearing the chat also drops the server session, so
        // the next turn starts fresh instead of inheriting a stale
        // thinking thread that asks for clarification.
        sessionId = null
        prefs().edit().remove(KEY_SESSION_ID).apply()
        hideAgentAction()
        ensureGreeting()
        adapter.submitList(messages.toList())
        binding.agentRecycler.scrollToPosition(messages.size - 1)
        updateProfileCardVisibility()
        Toast.makeText(
            requireContext(),
            R.string.agent_history_cleared,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun selectTab(itemId: Int) {
        (activity as? MainActivity)?.selectTab(itemId)
    }

    private fun openDesktop() {
        if (DebianSystem.isReady(requireContext())) {
            startActivity(
                Intent(requireContext(), VncActivity::class.java).apply {
                    putExtra(VncActivity.EXTRA_DESKTOP, VncActivity.DESKTOP_MUDIF)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        } else {
            InstallFlow.runDebianInstall(this) { ok, message ->
                if (ok) {
                    openDesktop()
                } else {
                    MudifDialog.info(
                        requireContext(),
                        getString(R.string.system_install_title),
                        message,
                        getString(R.string.ok)
                    )
                }
            }
        }
    }

    private fun showAgentAbout() {
        MudifDialog.info(
            requireContext(),
            getString(R.string.agent_name),
            getString(R.string.agent_about),
            getString(R.string.ok)
        )
    }

    // ------------------------------------------------- reply language

    /** p57: the reply language, derived live from the app language
     *  (ar -> fusha, ar-MA -> darija, en -> en). Read at view
     *  creation and at every turn start, never cached across a
     *  language change. */
    private fun liveReplyLanguage(): String {
        // Null-safe on purpose: speakAgent can run after detach,
        // and then the last synced value is the right fallback.
        val ctx = context ?: return replyLanguage
        return replyLanguageForTag(AppLanguage.currentTag(ctx))
    }

    private fun updateLanguageButton() {
        binding.btnReplyLanguage.text = getString(
            when (replyLanguage) {
                AgentMessage.REPLY_DARIJA -> R.string.language_darija
                AgentMessage.REPLY_EN -> R.string.language_english
                else -> R.string.language_fusha
            }
        )
    }

    /** p57: the header language chip opens the APP language
     *  picker now. The old reply-language menu let the agent keep
     *  a different language from the UI (the reported defect: UI
     *  in English, chip Darija, greeting Fusha), so it is gone:
     *  the reply language always follows the app language, and
     *  AppLanguage.apply() relaunches the task, so the new
     *  language reaches this screen at once. */
    private fun openLanguagePicker() {
        AppLanguage.showPicker(requireActivity() as androidx.appcompat.app.AppCompatActivity, false)
    }

    private fun updateBrainButton() {
        // p31: the chevron marks the pill as a tappable dropdown
        // (it opens the provider-selection screen).
        binding.btnBrain.text = brainLabelFor(brainModel) + " ▼"
        // p30 KissaKoti profile card: full model label + status.
        binding.agentProfileModel.text =
            "${brainFullLabelFor(brainModel)} • ${getString(R.string.agent_subtitle)}"
    }

    /** Full label for the profile card: "محلي (Qwen3 1.7B)" or the
     *  cloud model's full label. The header pill shows the short form. */
    private fun brainFullLabelFor(modelId: String): String {
        if (modelId == BRAIN_LOCAL) return getString(R.string.agent_brain_local)
        return loadBrainOptions().firstOrNull { it.id == modelId }?.label
            ?: modelId.substringAfter("/", modelId)
    }

    /** Short label for the header button: Local, or the model name.
     *  p30: the local label is the SHORT form ("محلي"). The full form
     *  ("محلي (Qwen3 1.7B)") stays in the brain picker; the header
     *  pill is too narrow for it and the long label pushed the agent
     *  name to 0px. */
    private fun brainLabelFor(modelId: String): String {
        if (modelId == BRAIN_LOCAL) return getString(R.string.agent_brain_local_short)
        return loadBrainOptions().firstOrNull { it.id == modelId }?.label
            ?: modelId.substringAfter("/", modelId)
    }

    /** One brain option: model id "provider/model", label, env var. */
    private data class BrainOption(val id: String, val label: String, val env: String?)

    /** Reads opencode-providers.json: Local first, then the cloud
     *  models in a fixed provider order (groq, llm7, openrouter,
     *  huggingface, opencode, google, cohere, mistral, tokenlab),
     *  models sorted by name. A missing or unreadable asset means
     *  Local only, the chat still works. */
    private fun loadBrainOptions(): List<BrainOption> {
        val options = mutableListOf(
            BrainOption(BRAIN_LOCAL, getString(R.string.agent_brain_local), null)
        )
        try {
            val root = JSONObject(
                requireContext().assets.open("opencode-providers.json")
                    .bufferedReader().use { it.readText() }
            ).optJSONObject("providers") ?: return options
            // p93: Groq is out of the brain picker by Anas's order
            // (17 Nov 2026). The saved key and the provider screen
            // card are untouched; only the pick list loses it.
            val providerOrder = listOf(
                "llm7", "openrouter", "huggingface", "opencode",
                "google", "cohere", "mistral", "tokenlab"
            )
            for (providerKey in providerOrder) {
                val provider = root.optJSONObject(providerKey) ?: continue
                val providerName = provider.optString("name", providerKey)
                val env = provider.optJSONArray("env")?.optString(0)
                val models = provider.optJSONObject("models") ?: continue
                val modelKeys = mutableListOf<String>()
                val it = models.keys()
                while (it.hasNext()) modelKeys.add(it.next())
                val sorted = modelKeys.sortedBy {
                    models.optJSONObject(it)?.optString("name", it) ?: it
                }
                for (modelKey in sorted) {
                    val modelName = models.optJSONObject(modelKey)
                        ?.optString("name", modelKey) ?: modelKey
                    options.add(
                        BrainOption("$providerKey/$modelKey", "$providerName: $modelName", env)
                    )
                }
            }
        } catch (e: Exception) {
            // Local only; the asset is bundled, so this is unexpected.
        }
        return options
    }

    /** Brain fix (plan section 5): a brain picked while a turn
     *  runs. Two honest choices, never a silent switch: wait for the
     *  turn to end, or stop the turn and switch now. */
    private fun showBrainSwitchDialog(pickedId: String) {
        MudifDialog.info(
            requireContext(),
            getString(R.string.agent_brain_switch_title),
            getString(R.string.agent_brain_switch_message),
            getString(R.string.agent_brain_switch_stop),
            onPositive = {
                stopCurrentTurn()
                applyBrainPick(pickedId)
            },
            negative = getString(R.string.agent_brain_switch_wait),
            onNegative = {
                pendingBrainPick = pickedId
                Toast.makeText(
                    requireContext(),
                    getString(R.string.agent_brain_switch_pending),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    /** p31: opens the provider-selection screen (10 free platforms).
     *  The screen returns the picked model id; applyBrainPick applies
     *  it through the existing setModel backend. A pick during a turn
     *  asks first (stop now or wait for the turn to end). */
    private fun openBrainProviders() {
        // Brain fix (plan section 5): the screen opens mid-turn
        // (browse only). A pick during a turn is deferred or stops
        // the turn first; see brainProvidersLauncher.
        brainProvidersLauncher.launch(
            Intent(requireContext(), BrainProvidersActivity::class.java).apply {
                putExtra(BrainProvidersActivity.EXTRA_CURRENT_MODEL, brainModel)
            }
        )
    }

    /** p35c: restarts the brain server after a model pick, tracked
     *  to completion instead of fire-and-forget. The running flag
     *  is what the next turn waits on; the failed flag is what
     *  makes it refuse to answer on the old server. A second pick
     *  while a restart runs does not start a second restart: it
     *  arms one more run, so the newest pick is the one verified.
     *  A failed final run posts the honest outcome as a Toast;
     *  the next turn then fails with the same verdict in chat. */
    private fun restartBrainServer() {
        val appContext = context?.applicationContext ?: return
        val thread = Thread {
            var ok = false
            while (true) {
                try {
                    ok = AgentServer.restart(appContext)
                } catch (e: Exception) {
                    Log.w("MudifAgent", "brain restart crashed", e)
                    ok = false
                }
                // p35c second attempt, hole 2: the again check and
                // the running clear happen in one lock hold, so a
                // pick landing after the check cannot be lost.
                val rerun = synchronized(brainRestartLock) {
                    if (brainRestartAgain) {
                        brainRestartAgain = false
                        true
                    } else {
                        brainRestartFailed = !ok
                        brainRestartRunning = false
                        false
                    }
                }
                if (!rerun) break
                MudifLog.i("MudifAgent", "brain restart: another pick arrived mid-restart, running again")
            }
            if (!ok) {
                Log.e("MudifAgent", "brain restart failed, server still on the old model")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(appContext, R.string.agent_brain_failed, Toast.LENGTH_LONG).show()
                }
            } else {
                MudifLog.i("MudifAgent", "brain restart verified on the picked model")
            }
        }
        // p35c second attempt, hole 1: thread reference, running
        // flag and start happen in one lock hold, so no sampler
        // can see running true with no thread to join.
        synchronized(brainRestartLock) {
            if (brainRestartRunning) {
                brainRestartAgain = true
                return
            }
            brainRestartThread = thread
            brainRestartRunning = true
            brainRestartFailed = false
            thread.isDaemon = true
            thread.start()
        }
    }

    /** p35c: waits out a brain restart a pick started, so the next
     *  turn never creates its session on the still-running old
     *  server. Returns true when no restart was pending or the
     *  pending one verified; false when it failed or timed out,
     *  and the caller must then fail the turn honestly instead of
     *  answering. A failed restart gets one synchronous retry
     *  here, so a transient failure self-heals and Retry on the
     *  failed turn has something real to retry. Never on the UI
     *  thread: the wait alone can last past a minute. */
    private fun waitForBrainRestart(ctx: Context): Boolean {
        // p35c second attempt, hole 1: running and thread are
        // snapshotted in one lock hold. Running true with no
        // thread means a restart is starting; fail closed.
        val (runningNow, thread) = synchronized(brainRestartLock) {
            brainRestartRunning to brainRestartThread
        }
        if (runningNow && thread == null) return false
        if (thread != null) {
            try {
                thread.join(BRAIN_RESTART_WAIT_TIMEOUT_MS)
            } catch (_: InterruptedException) {
            }
            val stillRunning = synchronized(brainRestartLock) { brainRestartRunning }
            if (stillRunning) {
                Log.e("MudifAgent", "brain restart still running after wait, turn refused")
                return false
            }
        }
        val failed = synchronized(brainRestartLock) { brainRestartFailed }
        if (failed) {
            MudifLog.i("MudifAgent", "brain restart failed earlier, retrying once for this turn")
            // p35c second attempt, hole 4: the retry runs through
            // the tracked restartBrainServer path, never a bare
            // AgentServer.restart, so it cannot run beside a
            // second restart a pick starts at the same time.
            restartBrainServer()
            val (retryRunning, retryThread) = synchronized(brainRestartLock) {
                brainRestartRunning to brainRestartThread
            }
            if (retryRunning && retryThread == null) return false
            if (retryThread == null) {
                Log.e("MudifAgent", "brain restart retry could not start, turn refused")
                return false
            }
            try {
                retryThread.join(BRAIN_RESTART_WAIT_TIMEOUT_MS)
            } catch (_: InterruptedException) {
            }
            val retryStillRunning = synchronized(brainRestartLock) { brainRestartRunning }
            if (retryStillRunning) {
                Log.e("MudifAgent", "brain restart retry still running after wait, turn refused")
                return false
            }
            val retryFailed = synchronized(brainRestartLock) { brainRestartFailed }
            if (retryFailed) {
                Log.e("MudifAgent", "brain restart retry failed, turn refused")
                return false
            }
        }
        return true
    }

    /** Applies a brain picked on the provider screen: writes it to
     *  opencode.json and restarts the server on a background thread
     *  (p93: the old UI-thread probe never fired, see the p35d note
     *  below).
     *  A cloud model needs its key in Settings (or on the provider
     *  screen): without the key the pick is refused with a visible
     *  warning and the active brain does not change (p34f fix 3,
     *  fail closed). p31: switching brains also drops the server session,
     *  so the next turn starts fresh under the new brain instead of
     *  inheriting the old brain's thinking thread. */
    private fun applyBrainPick(pickedId: String) {
        if (pickedId == brainModel) return
        val picked = loadBrainOptions().firstOrNull { it.id == pickedId } ?: return
        // p34f fix 3: the key gate runs BEFORE any mutation. It
        // used to run after setModel + the pill update, and its
        // LONG warning Toast was at once replaced by the SHORT
        // "brain changed" Toast, so a keyless Token Lab pick
        // switched silently. No key now means a visible warning
        // and no switch at all: no setModel, no pref, no restart.
        if (picked.env != null) {
            val prefKey = BrainProviders.prefKeyForEnv(picked.env)
            val hasKey = prefKey != null &&
                !com.bolm.mudif.ui.settings.ViewerPrefs.prefs(requireContext())
                    .getString(prefKey, "").isNullOrBlank()
            if (!hasKey) {
                val warning = getString(R.string.agent_brain_key_missing, picked.env)
                Log.w("AgentFragment", "brain pick refused, no key for ${picked.env}")
                // A Toast alone is transient and was overwritten
                // before; the warning also stays in the chat where
                // it cannot be missed.
                appendMessage(AgentMessage(AgentMessage.ROLE_AGENT, warning, replyLanguage))
                Toast.makeText(requireContext(), warning, Toast.LENGTH_LONG).show()
                // p34g fix 3: a refused pick is a failed turn for
                // Retry purposes: persist it and show Retry, so the
                // user can re-apply the pick after adding the key.
                // p34h: the pick itself is the payload (a model pick
                // is not a chat turn, so there is no user message
                // to re-send); Retry re-applies it. The updateBrainButton
                // call below is skipped by the return, so the pill
                // is untouched.
                // Consolidated fix item 2: the warning and the
                // Toast stay unconditional; only the pick Retry is
                // guarded, so a pending real turn failure keeps its
                // own Retry instead of being overwritten.
                val turnFailedPending = prefs().getBoolean(KEY_LAST_TURN_FAILED, false) &&
                    prefs().getString(KEY_FAILED_TURN_PICK, null) == null
                MudifLog.i("MudifAgent", "applyBrainPick refused no key env=${picked.env} marked failed")
                markRefusedPick(requireContext(), picked.id)
                if (_binding != null && !turnFailedPending) showTurnFailedUi(null) { applyBrainPick(picked.id) }
                return
            }
        }
        if (!AgentServer.setModel(requireContext(), picked.id)) {
            Toast.makeText(requireContext(), R.string.agent_brain_failed, Toast.LENGTH_SHORT).show()
            return
        }
        brainModel = picked.id
        prefs().edit().putString(KEY_BRAIN_MODEL, brainModel).apply()
        // p34h: this pick completes a pending refused pick of the
        // same model; its Retry is spent, or it would offer to
        // re-apply a pick that already applied.
        if (prefs().getString(KEY_FAILED_TURN_PICK, null) == picked.id) {
            clearTurnFailed(requireContext())
            if (_binding != null) hideAgentAction()
        }
        updateBrainButton()
        // p34f fix 3: the key gate moved above, before the switch.
        // A pick reaching here has its key (or needs none).
        Toast.makeText(
            requireContext(),
            getString(R.string.agent_brain_changed, picked.label),
            Toast.LENGTH_SHORT
        ).show()
        // p31: fresh session under the new brain. p35d: the clear
        // and the restart start under brainRestartLock, as one
        // atomic step, so a turn setting up at the same moment
        // cannot read the old id or create a session on the old
        // server between the two. p93: the probe used to run here
        // on the UI thread (AgentServer.isUp, up to 3 x 1500 ms
        // of socket connect), and Android throws
        // NetworkOnMainThreadException for socket I/O on the main
        // thread, which isUp swallows as "down", so the restart
        // never fired (F4: 3/3 picks). The probe now lives inside
        // AgentServer.restart on the restart thread, which already
        // handles a down server (start only, verified). The lock
        // is held only for the field write, the pref remove, and
        // the thread start. restartBrainServer takes the same lock
        // to set its flags; that nested hold is the same thread,
        // so it cannot deadlock.
        synchronized(brainRestartLock) {
            sessionId = null
            prefs().edit().remove(KEY_SESSION_ID).apply()
            restartBrainServer()
        }
    }

    // ---------------------------------------------------------- voice

    private fun recognizerAvailable(): Boolean {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        return requireContext().packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_DEFAULT_ONLY
        ).isNotEmpty()
    }

    private fun startVoiceInput() {
        if (!recognizerAvailable()) {
            Toast.makeText(
                requireContext(),
                R.string.agent_voice_in_unavailable,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        // p57: the recognizer locale follows the live reply
        // language (en -> en-US, darija -> ar-MA, fusha -> ar-SA),
        // so voice input hears the language the user reads. The
        // system recognizer falls back on its own when a pack is
        // missing.
        val voiceLocale = when (liveReplyLanguage()) {
            AgentMessage.REPLY_EN -> "en-US"
            AgentMessage.REPLY_DARIJA -> "ar-MA"
            else -> "ar-SA"
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLocale)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                getString(R.string.agent_voice_in_desc)
            )
        }
        try {
            voiceInputLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                R.string.agent_voice_in_unavailable,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** p57: the TTS voice follows the reply language, like the
     *  text replies. Chains per language because a missing voice
     *  pack must fall back, never silence the feature: en ->
     *  English; darija -> ar-MA, then ar-SA, then ar-EG (no
     *  device ships a Darija voice pack); fusha -> ar-SA, then
     *  ar-EG. Returns true when a voice was set. */
    private fun applyTtsLanguage(engine: TextToSpeech, reply: String): Boolean {
        val locales = when (reply) {
            AgentMessage.REPLY_EN -> listOf(Locale.ENGLISH)
            AgentMessage.REPLY_DARIJA -> listOf(
                Locale("ar", "MA"), Locale("ar", "SA"), Locale("ar", "EG")
            )
            else -> listOf(Locale("ar", "SA"), Locale("ar", "EG"))
        }
        for (locale in locales) {
            val result = engine.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                return true
            }
        }
        return false
    }

    private fun initTts() {
        tts = TextToSpeech(requireContext(), TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                setTtsAvailable(false)
                return@OnInitListener
            }
            val engine = tts ?: return@OnInitListener
            val reply = context?.let { replyLanguageForTag(AppLanguage.currentTag(it)) }
                ?: AgentMessage.REPLY_FUSHA
            if (!applyTtsLanguage(engine, reply)) {
                setTtsAvailable(false)
                return@OnInitListener
            }
            ttsReady = true
        })
    }

    private fun setTtsAvailable(available: Boolean) {
        ttsReady = available
        if (_binding == null) return
        context?.let {
            Toast.makeText(
                it,
                R.string.agent_voice_out_unavailable,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** p31: the paperclip menu. Voice input (one tap, same as the
     *  mic inside the field) and the voice-output toggle. The label
     *  shows the current voice-output state. */
    private fun showAttachMenu() {
        val voiceOutLabel = getString(
            if (voiceOutOn) R.string.agent_attach_voice_out_off
            else R.string.agent_attach_voice_out_on
        )
        MudifDialog.select(
            requireContext(),
            getString(R.string.agent_attach_title),
            listOf(getString(R.string.agent_attach_voice_in), voiceOutLabel),
            -1
        ) { index ->
            when (index) {
                0 -> startVoiceInput()
                1 -> toggleVoiceOut()
            }
        }
    }

    private fun toggleVoiceOut() {
        if (!ttsReady) {
            Toast.makeText(
                requireContext(),
                R.string.agent_voice_out_unavailable,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        voiceOutOn = !voiceOutOn
        prefs().edit().putBoolean(KEY_VOICE_OUT, voiceOutOn).apply()
        if (voiceOutOn) {
            // Speak the last agent message so the toggle proves
            // itself at once instead of waiting for the next reply.
            val lastAgent = messages.lastOrNull { it.role == AgentMessage.ROLE_AGENT }
            if (lastAgent != null) speakAgent(lastAgent.text)
        } else {
            tts?.stop()
        }
    }

    private fun speakAgent(text: String) {
        if (!voiceOutOn || !ttsReady) return
        // p57: the voice follows the live reply language, so a
        // language change speaks the new language at once.
        tts?.let { applyTtsLanguage(it, liveReplyLanguage()) }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    // -------------------------------------------------------- console

    private fun loadConsole() {
        consoleLoaded = true
        binding.agentServerError.visibility = View.GONE
        binding.agentWebview.settings.javaScriptEnabled = true
        binding.agentWebview.settings.domStorageEnabled = true
        binding.agentWebview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Localhost only: any navigation away from the agent
                // server is refused inside this view.
                return !isLocalhostUrl(request.url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (_binding == null) return
                if (request.isForMainFrame) {
                    binding.agentServerError.visibility = View.VISIBLE
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (_binding == null) return
                if (url != null && isLocalhostUrl(Uri.parse(url))) {
                    binding.agentServerError.visibility = View.GONE
                }
            }
        }
        binding.agentWebview.loadUrl(AgentServer.AGENT_SERVER_URL)
    }

    private fun isLocalhostUrl(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return host.equals("127.0.0.1", ignoreCase = true) ||
            host.equals("localhost", ignoreCase = true)
    }

    // ----------------------------------------------------- persistence

    private fun prefs() =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadMessages(): MutableList<AgentMessage> {
        val list = mutableListOf<AgentMessage>()
        try {
            val array = JSONArray(prefs().getString(KEY_MESSAGES, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    AgentMessage(
                        obj.optString("role", AgentMessage.ROLE_AGENT),
                        obj.optString("text", ""),
                        obj.optString("lang", AgentMessage.REPLY_FUSHA),
                        obj.optLong("time", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            list.clear()
        }
        return list
    }

    private fun saveMessages() {
        val array = JSONArray()
        for (message in messages) {
            array.put(
                JSONObject().apply {
                    put("role", message.role)
                    put("text", message.text)
                    put("lang", message.replyLanguage)
                    put("time", message.time)
                }
            )
        }
        prefs().edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    companion object {
        // Agent server in the guest (composite path): the web UI is
        // on localhost port 4096 (AgentServer.AGENT_SERVER_URL). The
        // app reaches the guest on 127.0.0.1, the same loopback path
        // the X server (6006) and audio (4713) use. Nothing here ever
        // loads a remote URL. The server is started on demand by
        // AgentServer.ensureStarted; the health probe lives in
        // AgentServer.isSessionHealthy() (GET /session, p81).
        // AgentServer.isUp() is only the TCP check.
        private const val PREFS_NAME = "mudif_agent_chat"
        private const val KEY_MESSAGES = "messages"
        private const val KEY_REPLY_LANGUAGE = "reply_language"

        /** p57: the app-language tag mapped to the reply language
         *  (ar -> fusha, ar-MA -> darija, en -> en). One mapping,
         *  used by the live read and by the app-language sync. */
        fun replyLanguageForTag(tag: String): String = when (tag) {
            AppLanguage.TAG_DARIJA -> AgentMessage.REPLY_DARIJA
            AppLanguage.TAG_ENGLISH -> AgentMessage.REPLY_EN
            else -> AgentMessage.REPLY_FUSHA
        }

        /**
         * p57: link the reply language to the app language.
         * Called from AppLanguage.apply() on every app-language
         * pick. The sync is unconditional now: the old manual
         * reply-language menu (and its explicit flag) let the
         * agent keep a different language from the UI, so both
         * are gone. The live read in the fragment is the real
         * guarantee; this write only keeps the stored cache in
         * sync for any reader of the pref.
         */
        fun applyAppLanguageReply(context: Context, tag: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_REPLY_LANGUAGE, replyLanguageForTag(tag)).apply()
        }

        /** Vision increment 2: the picked cloud vision model id
         *  ("" = local vision). Read by the provider screen to mark
         *  the picked row and by the vision turn and the chip. */
        fun getVisionModel(context: Context): String =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_VISION_MODEL, "") ?: ""

        /** Vision increment 2: stores the picked cloud vision model
         *  id ("" = back to local vision). */
        fun setVisionModel(context: Context, modelId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_VISION_MODEL, modelId).apply()
        }
        private const val KEY_VOICE_OUT = "voice_out"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_BRAIN_MODEL = "brain_model"
        // p60: the model id whose download card the user dismissed
        // (brain or vision). The on-open nag respects it; the
        // turn-failure gates do not, because the card is the remedy
        // there and the picker has no download button of its own.
        private const val KEY_DISMISSED_DOWNLOAD_MODEL = "dismissed_download_model"
        // Vision increment 2: the picked cloud vision model id
        // ("google/gemini-2.5-flash"), or "" for local vision.
        // Written by the provider screen (long-press a vision
        // model) and read by the vision turn and the chip.
        private const val KEY_VISION_MODEL = "vision_model"
        // Vision increment 2: the text brain a cloud vision turn
        // must restore when it ends. Durable, so a turn that died
        // with the view (rotation, process death) still restores
        // on the next launch (see onViewCreated).
        private const val KEY_PENDING_BRAIN_PICK = "pending_brain_pick"
        // p34f fix 4: a failed turn persists here, so Retry survives
        // a relaunch. The flag alone is view state otherwise, and
        // the last-user-message field dies with the process.
        private const val KEY_LAST_TURN_FAILED = "last_turn_failed"
        private const val KEY_FAILED_TURN_VISION = "failed_turn_vision"
        // p34h: the picked model id of a refused brain pick; the
        // re-runnable payload for a failure that has no user
        // message to re-send.
        private const val KEY_FAILED_TURN_PICK = "failed_turn_pick"
        // Consolidated fix item 4: one-shot hidden pref that arms
        // a silent turn for the timeout test. Never referenced in
        // any res/ file or any UI.
        private const val KEY_TEST_SILENT_TURN = "test_silent_turn"
        // Hole 5: durable in-flight marker. Written on every send
        // in sendMessage, cleared on every terminal path. A new
        // instance uses it to tell a turn still starting in this
        // process apart from a turn that died with it.
        private const val KEY_TURN_IN_FLIGHT_AT = "turn_in_flight_at"
        private const val KEY_TURN_IN_FLIGHT_PID = "turn_in_flight_pid"
        private const val BRAIN_LOCAL = "ollama/qwen3:1.7b"
        // Brain fix (plan section 11): first-token wait before the
        // local-slow chip offers a free cloud brain.
        private const val LOCAL_SLOW_CHIP_DELAY_MS = 60_000L
        // p35c: longest a turn waits for a brain restart a pick
        // started (stop up to 15 s, start poll up to 30 s, pid
        // probes on top). Past this the turn refuses honestly.
        private const val BRAIN_RESTART_WAIT_TIMEOUT_MS = 120_000L
        // Brain fix (plan section 10): user-pasted URLs, appended to
        // the fetch allowlist so the brain may open them.
        private val PASTED_URL_REGEX = Regex("https?://\\S+")
        private const val MAX_HISTORY = 100
        private const val UTTERANCE_ID = "mudif_agent_reply"
    }
}
