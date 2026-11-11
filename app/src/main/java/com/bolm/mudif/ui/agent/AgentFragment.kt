package com.bolm.mudif.ui.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
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
    private var voiceOutOn: Boolean = false
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var turnRunning: Boolean = false
    private var lastUserMessage: AgentMessage? = null
    private var consoleLoaded: Boolean = false
    private var sessionId: String? = null
    private var currentTurn: AgentClient.TurnHandle? = null
    private var streamingIndex: Int = -1

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
        voiceOutOn = prefs().getBoolean(KEY_VOICE_OUT, false)
        sessionId = prefs().getString(KEY_SESSION_ID, null)
        messages.addAll(loadMessages())
        ensureGreeting()

        binding.agentRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.agentRecycler.adapter = adapter
        adapter.submitList(messages.toList())
        binding.agentRecycler.scrollToPosition(messages.size - 1)

        updateLanguageButton()
        updateVoiceOutButton()
        initTts()

        // Header: avatar + name open the agent About text.
        val showAbout = { showAgentAbout() }
        binding.agentAvatar.setOnClickListener { showAbout() }
        binding.agentIdentity.setOnClickListener { showAbout() }
        binding.btnReplyLanguage.setOnClickListener { showReplyLanguageMenu() }

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
        binding.btnVoiceOut.setOnClickListener { toggleVoiceOut() }

        binding.btnConsoleRetry.setOnClickListener {
            consoleLoaded = false
            loadConsole()
        }
    }

    override fun onDestroyView() {
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

    private fun showChat() {
        binding.chatContainer.visibility = View.VISIBLE
        binding.consoleContainer.visibility = View.GONE
    }

    private fun showConsole() {
        binding.chatContainer.visibility = View.GONE
        binding.consoleContainer.visibility = View.VISIBLE
        binding.modeToggle.check(R.id.mode_console)
        if (!consoleLoaded) loadConsole()
    }

    // ---------------------------------------------------------- chat

    private fun sendCurrentInput() {
        val text = binding.inputText.text.toString()
        if (text.isBlank()) return
        binding.inputText.setText("")
        sendMessage(text)
    }

    private fun sendMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || turnRunning) return
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
        hideAgentAction()
        ensureGreeting()
        adapter.submitList(messages.toList())
        binding.agentRecycler.scrollToPosition(messages.size - 1)
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
        val popup = PopupMenu(requireContext(), binding.btnReplyLanguage)
        popup.menu.add(0, 0, 0, R.string.language_fusha)
        popup.menu.add(0, 1, 1, R.string.language_darija)
        popup.menu.add(0, 2, 2, R.string.language_english)
        popup.setOnMenuItemClickListener { item ->
            replyLanguage = when (item.itemId) {
                1 -> AgentMessage.REPLY_DARIJA
                2 -> AgentMessage.REPLY_EN
                else -> AgentMessage.REPLY_FUSHA
            }
            prefs().edit().putString(KEY_REPLY_LANGUAGE, replyLanguage).apply()
            updateLanguageButton()
            true
        }
        popup.show()
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
            if (_binding != null) updateVoiceOutButton()
        })
    }

    private fun setTtsAvailable(available: Boolean) {
        ttsReady = available
        if (_binding == null) return
        if (!available) {
            binding.btnVoiceOut.isEnabled = false
            binding.btnVoiceOut.alpha = 0.4f
            binding.btnVoiceOut.contentDescription =
                getString(R.string.agent_voice_out_unavailable)
        }
        context?.let {
            Toast.makeText(
                it,
                R.string.agent_voice_out_unavailable,
                Toast.LENGTH_LONG
            ).show()
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
        updateVoiceOutButton()
        if (voiceOutOn) {
            // Speak the last agent message so the toggle proves
            // itself at once instead of waiting for the next reply.
            val lastAgent = messages.lastOrNull { it.role == AgentMessage.ROLE_AGENT }
            if (lastAgent != null) speakAgent(lastAgent.text)
        } else {
            tts?.stop()
        }
    }

    private fun updateVoiceOutButton() {
        binding.btnVoiceOut.alpha = if (voiceOutOn) 1.0f else 0.45f
        binding.btnVoiceOut.contentDescription = getString(
            if (voiceOutOn) R.string.agent_voice_out_on
            else R.string.agent_voice_out_off
        )
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
                        obj.optString("lang", AgentMessage.REPLY_FUSHA)
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
        private const val MAX_HISTORY = 100
        private const val UTTERANCE_ID = "mudif_agent_reply"
    }
}
