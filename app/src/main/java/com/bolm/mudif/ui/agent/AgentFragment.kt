package com.bolm.mudif.ui.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.bolm.mudif.MainActivity
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentAgentBinding
import com.bolm.mudif.engine.AgentClient
import com.bolm.mudif.engine.AgentServer
import com.bolm.mudif.engine.AgentSupervisorService
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.InstallFlow
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.settings.SettingsActivity
import com.bolm.mudif.ui.vnc.VncActivity
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 4: the مساعد مضيف tab. Phase 5: the native chat is wired to
 * the local agent server (AgentServer + AgentClient).
 *
 * Two modes, chat first:
 * - Chat: native message list (persisted locally), quick-action chips,
 *   voice input/output, and a manual reply-language switch (Fusha
 *   default / Darija / English) that is stored and sent with every
 *   message as the brain's system prompt.
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
    // p32: keyboard state (the layout listener updates it). While
    // the keyboard is open the header + scroll row + divider + card
    // hide so the message list keeps a usable height (they used to
    // squeeze agent_recycler to 6px); they return when it closes.
    private var keyboardOpen: Boolean = false
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

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
                applyBrainPick(picked)
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
        replyLanguage = prefs().getString(KEY_REPLY_LANGUAGE, AgentMessage.REPLY_FUSHA)
            ?: AgentMessage.REPLY_FUSHA
        brainModel = prefs().getString(KEY_BRAIN_MODEL, BRAIN_LOCAL) ?: BRAIN_LOCAL
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
        updateProfileCardVisibility()
        binding.btnBrainDownload.setOnClickListener { startBrainDownload() }
        checkBrainModelOnOpen()

        updateLanguageButton()
        updateBrainButton()
        initTts()

        // Header: avatar + name open the agent About text.
        val showAbout = { showAgentAbout() }
        binding.agentAvatar.setOnClickListener { showAbout() }
        binding.agentIdentity.setOnClickListener { showAbout() }
        binding.btnReplyLanguage.setOnClickListener { showReplyLanguageMenu() }
        // p31: the brain pill and the profile card both open the
        // provider-selection screen (9 free platforms).
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
        currentTurn?.cancel()
        currentTurn = null
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
        binding.chipOpenApp.visibility = v
        binding.chipSettings.visibility = v
    }

    /** p32: one place that decides the chrome (header, scroll row,
     *  divider, profile card, Clear button) from the current mode
     *  and the keyboard state. p33: the HEADER STAYS VISIBLE while
     *  typing — p32 hid it and Anas reports it gone (his verdict:
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
        } else {
            activity?.setBottomNavVisible(true)
            binding.chipsScroll.visibility = View.VISIBLE
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

    private fun sendCurrentInput() {
        val text = binding.inputText.text.toString()
        if (text.isBlank()) return
        binding.inputText.setText("")
        sendMessage(text)
    }

    // ------------------------------------------------- brain download (p22)

    private var brainDownloadRunning = false

    /** Shows the one-tap brain download card (model missing). */
    private fun showBrainDownloadCard() {
        binding.brainDownloadCard.visibility = View.VISIBLE
        binding.brainDownloadProgress.visibility = View.GONE
        binding.brainDownloadProgress.progress = 0
        binding.brainDownloadText.text = getString(R.string.brain_download_title)
        binding.btnBrainDownload.isEnabled = true
    }

    private fun hideBrainDownloadCard() {
        binding.brainDownloadCard.visibility = View.GONE
    }

    /** Background check when the tab opens: if Ollama answers but the
     *  model is missing, show the download card (no turn is started). */
    private fun checkBrainModelOnOpen() {
        Thread {
            val has = AgentServer.hasBrainModel() ?: return@Thread
            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (has) hideBrainDownloadCard() else showBrainDownloadCard()
            }
        }.start()
    }

    /** One-tap pull with a real progress bar (no terminal). */
    private fun startBrainDownload() {
        if (brainDownloadRunning) return
        brainDownloadRunning = true
        binding.btnBrainDownload.isEnabled = false
        binding.brainDownloadProgress.visibility = View.VISIBLE
        binding.brainDownloadProgress.progress = 0
        val appContext = context?.applicationContext
        if (appContext == null) {
            brainDownloadRunning = false
            binding.btnBrainDownload.isEnabled = true
            return
        }
        Thread {
            // The pull needs the servers up; ensureStarted is cheap
            // when they already run (it returns immediately).
            AgentServer.ensureStarted(appContext)
            val ok = AgentServer.pullBrainModel { completed, total ->
                val act = activity ?: return@pullBrainModel
                act.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    if (total > 0) {
                        val pct = (completed * 100 / total).toInt().coerceIn(0, 100)
                        binding.brainDownloadProgress.progress = pct
                        binding.brainDownloadText.text =
                            getString(R.string.brain_download_downloading, pct)
                    }
                }
            }
            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                brainDownloadRunning = false
                binding.btnBrainDownload.isEnabled = true
                if (ok) {
                    hideBrainDownloadCard()
                    Toast.makeText(
                        requireContext(), R.string.brain_download_done, Toast.LENGTH_SHORT
                    ).show()
                } else {
                    binding.brainDownloadText.text =
                        getString(R.string.brain_download_failed)
                    binding.brainDownloadProgress.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun sendMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        if (turnRunning) {
            // Never swallow a send silently: the button is disabled
            // during a turn, but the keyboard send key and the quick
            // chips can still reach here.
            Toast.makeText(requireContext(), R.string.agent_thinking, Toast.LENGTH_SHORT).show()
            return
        }
        hideAgentAction()
        val userMessage = AgentMessage(AgentMessage.ROLE_USER, text, replyLanguage)
        appendMessage(userMessage)
        lastUserMessage = userMessage
        startTurn(text)
    }

    private fun retryLastMessage() {
        val last = lastUserMessage ?: return
        if (turnRunning) return
        hideAgentAction()
        startTurn(last.text)
    }

    /**
     * Phase 5: one real chat turn. Background thread: make sure the
     * brain server is running (AgentServer.ensureStarted boots the
     * guest and starts opencode when needed), get or create the
     * session, then stream the reply into the agent bubble.
     * Every failure lands as an agent error message with a real
     * action (Retry, or "open desktop" when the engine is not
     * installed). Nothing is faked.
     */
    private fun startTurn(text: String) {
        turnRunning = true
        binding.agentTyping.text = getString(R.string.agent_starting_server)
        binding.agentTyping.visibility = View.VISIBLE
        binding.btnSend.isEnabled = false
        // Application context: the thread outlives the view, and
        // requireContext() would throw if the fragment detaches.
        val appContext = context?.applicationContext ?: run {
            onTurnFailed(null)
            return
        }
        Thread {
            // Supervise the turn: the foreground service keeps the
            // bridge and the brain server alive while the agent
            // works (Android 12+ phantom killer / Doze). It is
            // idempotent; starting it again while it runs is a no-op.
            // ensureStarted below still runs so this turn gets the
            // real up/down verdict for its error message.
            AgentSupervisorService.start(appContext)
            val ensured = AgentServer.ensureStarted(appContext)
            val act = activity ?: return@Thread
            if (!ensured.up) {
                act.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    onTurnFailed(ensured.reason)
                }
                return@Thread
            }
            // p22: the servers are up, but the brain model may still
            // be missing (fresh image). Never start a turn that can
            // only fail — show the download card instead.
            if (AgentServer.hasBrainModel() == false) {
                act.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    showBrainDownloadCard()
                    endTurnUi()
                }
                return@Thread
            }
            var sid = sessionId
            if (sid.isNullOrBlank()) {
                sid = AgentClient.createSession()
                if (sid.isNullOrBlank()) {
                    act.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        onTurnFailed(null)
                    }
                    return@Thread
                }
                sessionId = sid
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_SESSION_ID, sid).apply()
            }
            val callbacks = object : AgentClient.TurnCallbacks {
                override fun onText(fullText: String) {
                    act.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        showStreamingText(fullText)
                    }
                }

                override fun onTool(tool: String) {
                    act.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        binding.agentTyping.text =
                            getString(R.string.agent_tool_running, tool)
                        binding.agentTyping.visibility = View.VISIBLE
                    }
                }

                override fun onDone(finalText: String) {
                    act.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        finishTurn(finalText)
                    }
                }

                override fun onError(message: String) {
                    act.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        onTurnFailed(null, message)
                    }
                }
            }
            val handle = AgentClient.sendPrompt(
                sid, text, AgentClient.systemPrompt(replyLanguage), callbacks
            )
            act.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                currentTurn = handle
                binding.agentTyping.text = getString(R.string.agent_thinking)
                binding.btnAgentAction.text = getString(R.string.agent_stop)
                binding.btnAgentAction.setOnClickListener { stopCurrentTurn() }
                binding.btnAgentAction.visibility = View.VISIBLE
            }
        }.start()
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
    }

    private fun finishTurn(finalText: String) {
        if (streamingIndex < 0 && finalText.isNotEmpty()) {
            appendMessage(AgentMessage(AgentMessage.ROLE_AGENT, finalText, replyLanguage))
        }
        val spoken = if (streamingIndex >= 0) messages[streamingIndex].text else finalText
        streamingIndex = -1
        currentTurn = null
        endTurnUi()
        if (spoken.isNotEmpty()) speakAgent(spoken)
    }

    private fun endTurnUi() {
        turnRunning = false
        streamingIndex = -1
        binding.agentTyping.visibility = View.GONE
        binding.btnSend.isEnabled = true
        hideAgentAction()
    }

    /** A turn failed: real error message in the chat plus a real
     *  action. `reason` is AgentServer's machine key (null = the
     *  server was up but the turn itself failed). */
    private fun onTurnFailed(reason: String?, detail: String? = null) {
        turnRunning = false
        currentTurn = null
        streamingIndex = -1
        binding.agentTyping.visibility = View.GONE
        binding.btnSend.isEnabled = true
        val verdict = when (reason) {
            AgentServer.REASON_ENGINE_NOT_READY ->
                getString(R.string.agent_engine_not_ready)
            AgentServer.REASON_STAGE_FAILED,
            AgentServer.REASON_BOOT_FAILED,
            AgentServer.REASON_START_FAILED ->
                getString(R.string.agent_server_start_failed)
            else -> detail?.takeIf { it.isNotBlank() }
                ?: getString(R.string.agent_error)
        }
        appendMessage(AgentMessage(AgentMessage.ROLE_AGENT, verdict, replyLanguage))
        if (reason == AgentServer.REASON_ENGINE_NOT_READY) {
            binding.btnAgentAction.text = getString(R.string.agent_quick_desktop)
            binding.btnAgentAction.setOnClickListener { openDesktop() }
        } else {
            binding.btnAgentAction.text = getString(R.string.agent_server_retry)
            binding.btnAgentAction.setOnClickListener { retryLastMessage() }
        }
        binding.btnAgentAction.visibility = View.VISIBLE
        speakAgent(verdict)
    }

    private fun hideAgentAction() {
        binding.btnAgentAction.visibility = View.GONE
    }

    private fun appendMessage(message: AgentMessage) {
        messages.add(message)
        while (messages.size > MAX_HISTORY) messages.removeAt(0)
        saveMessages()
        adapter.submitList(messages.toList())
        binding.agentRecycler.scrollToPosition(messages.size - 1)
        updateProfileCardVisibility()
    }

    private fun ensureGreeting() {
        if (messages.isEmpty()) {
            messages.add(
                AgentMessage(
                    AgentMessage.ROLE_AGENT,
                    getString(R.string.agent_greeting),
                    replyLanguage
                )
            )
            saveMessages()
        }
    }

    private fun clearHistory() {
        messages.clear()
        saveMessages()
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

    private fun updateLanguageButton() {
        binding.btnReplyLanguage.text = getString(
            when (replyLanguage) {
                AgentMessage.REPLY_DARIJA -> R.string.language_darija
                AgentMessage.REPLY_EN -> R.string.language_english
                else -> R.string.language_fusha
            }
        )
    }

    private fun showReplyLanguageMenu() {
        // Branded dialog (MudifDialog.select): the old PopupMenu was
        // the classic unstyled system popup, clipped by the screen
        // edge in RTL and overlapping the header (his screenshot).
        val labels = listOf(
            getString(R.string.language_fusha),
            getString(R.string.language_darija),
            getString(R.string.language_english)
        )
        val current = when (replyLanguage) {
            AgentMessage.REPLY_DARIJA -> 1
            AgentMessage.REPLY_EN -> 2
            else -> 0
        }
        MudifDialog.select(
            requireContext(),
            getString(R.string.agent_reply_language),
            labels,
            current
        ) { index ->
            replyLanguage = when (index) {
                1 -> AgentMessage.REPLY_DARIJA
                2 -> AgentMessage.REPLY_EN
                else -> AgentMessage.REPLY_FUSHA
            }
            prefs().edit().putString(KEY_REPLY_LANGUAGE, replyLanguage).apply()
            updateLanguageButton()
        }
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
     *  huggingface, opencode, google, cohere, mistral), models sorted
     *  by name. A missing or unreadable asset means Local only — the
     *  chat still works. */
    private fun loadBrainOptions(): List<BrainOption> {
        val options = mutableListOf(
            BrainOption(BRAIN_LOCAL, getString(R.string.agent_brain_local), null)
        )
        try {
            val root = JSONObject(
                requireContext().assets.open("opencode-providers.json")
                    .bufferedReader().use { it.readText() }
            ).optJSONObject("providers") ?: return options
            val providerOrder = listOf(
                "groq", "llm7", "openrouter", "huggingface", "opencode",
                "google", "cohere", "mistral"
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
                        BrainOption("$providerKey/$modelKey", "$providerName — $modelName", env)
                    )
                }
            }
        } catch (e: Exception) {
            // Local only; the asset is bundled, so this is unexpected.
        }
        return options
    }

    /** p31: opens the provider-selection screen (9 free platforms).
     *  The screen returns the picked model id; applyBrainPick applies
     *  it through the existing setModel backend. A turn in progress
     *  blocks the switch. */
    private fun openBrainProviders() {
        if (turnRunning) {
            Toast.makeText(requireContext(), R.string.agent_thinking, Toast.LENGTH_SHORT).show()
            return
        }
        brainProvidersLauncher.launch(
            Intent(requireContext(), BrainProvidersActivity::class.java).apply {
                putExtra(BrainProvidersActivity.EXTRA_CURRENT_MODEL, brainModel)
            }
        )
    }

    /** Applies a brain picked on the provider screen: writes it to
     *  opencode.json and restarts the server (only when it is up).
     *  A cloud model needs its key in Settings (or on the provider
     *  screen) or it fails at runtime — the user is warned, not
     *  blocked. p31: switching brains also drops the server session,
     *  so the next turn starts fresh under the new brain instead of
     *  inheriting the old brain's thinking thread. */
    private fun applyBrainPick(pickedId: String) {
        if (pickedId == brainModel) return
        val picked = loadBrainOptions().firstOrNull { it.id == pickedId } ?: return
        if (!AgentServer.setModel(requireContext(), picked.id)) {
            Toast.makeText(requireContext(), R.string.agent_brain_failed, Toast.LENGTH_SHORT).show()
            return
        }
        brainModel = picked.id
        prefs().edit().putString(KEY_BRAIN_MODEL, brainModel).apply()
        // p31: fresh session under the new brain.
        sessionId = null
        prefs().edit().remove(KEY_SESSION_ID).apply()
        updateBrainButton()
        // Cloud model without its key: warn (the model will fail
        // until the key is added), but keep the choice.
        if (picked.env != null) {
            val prefKey = BrainProviders.prefKeyForEnv(picked.env)
            val hasKey = prefKey != null &&
                !com.bolm.mudif.ui.settings.ViewerPrefs.prefs(requireContext())
                    .getString(prefKey, "").isNullOrBlank()
            if (!hasKey) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.agent_brain_key_missing, picked.env),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        Toast.makeText(
            requireContext(),
            getString(R.string.agent_brain_changed, picked.label),
            Toast.LENGTH_SHORT
        ).show()
        if (AgentServer.isUp()) {
            Thread {
                AgentServer.stop(requireContext())
                AgentServer.ensureStarted(requireContext())
            }.start()
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
        // Primary locale is ar-SA; if the device has no Saudi voice
        // data the system recognizer falls back on its own, and ar-EG
        // is the documented fallback if a fixed locale is ever forced.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
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

    private fun initTts() {
        tts = TextToSpeech(requireContext(), TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                setTtsAvailable(false)
                return@OnInitListener
            }
            val engine = tts ?: return@OnInitListener
            val result = engine.setLanguage(Locale("ar", "SA"))
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                // Fallback: Egyptian Arabic voice data is the most
                // common Arabic pack on devices without a Saudi pack.
                val fallback = engine.setLanguage(Locale("ar", "EG"))
                if (fallback == TextToSpeech.LANG_MISSING_DATA ||
                    fallback == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    setTtsAvailable(false)
                    return@OnInitListener
                }
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
        // AgentServer.ensureStarted; the probe lives in
        // AgentServer.isUp().
        private const val PREFS_NAME = "mudif_agent_chat"
        private const val KEY_MESSAGES = "messages"
        private const val KEY_REPLY_LANGUAGE = "reply_language"
        private const val KEY_VOICE_OUT = "voice_out"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_BRAIN_MODEL = "brain_model"
        private const val BRAIN_LOCAL = "ollama/qwen3:1.7b"
        private const val MAX_HISTORY = 100
        private const val UTTERANCE_ID = "mudif_agent_reply"
    }
}
