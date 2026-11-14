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
        brainModel = prefs().getString(KEY_BRAIN_MODEL, BRAIN_LOCAL) ?: BRAIN_LOCAL
        voiceOutOn = prefs().getBoolean(KEY_VOICE_OUT, false)
        sessionId = prefs().getString(KEY_SESSION_ID, null)
        messages.addAll(loadMessages())
        ensureGreeting()

        binding.agentRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.agentRecycler.adapter = adapter
        adapter.submitList(messages.toList())
        binding.agentRecycler.scrollToPosition(messages.size - 1)
        binding.btnBrainDownload.setOnClickListener { startBrainDownload() }
        checkBrainModelOnOpen()

        updateLanguageButton()
        updateBrainButton()
        updateVoiceOutButton()
        initTts()

        // Header: avatar + name open the agent About text.
        val showAbout = { showAgentAbout() }
        binding.agentAvatar.setOnClickListener { showAbout() }
        binding.agentIdentity.setOnClickListener { showAbout() }
        binding.btnReplyLanguage.setOnClickListener { showReplyLanguageMenu() }
        binding.btnBrain.setOnClickListener { showBrainPicker() }

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

    // p29: chat_container wrapper removed (it measured 0x0). The chat
    // views are direct children of the root now, so show/hide them
    // individually. The console container takes the recycler's space
    // (both are weight=1 direct children; only one is visible at a time).
    private fun showChat() {
        binding.chipsScroll.visibility = View.VISIBLE
        binding.agentRecycler.visibility = View.VISIBLE
        binding.inputRow.visibility = View.VISIBLE
        binding.consoleContainer.visibility = View.GONE
    }

    private fun showConsole() {
        binding.chipsScroll.visibility = View.GONE
        binding.agentRecycler.visibility = View.GONE
        binding.inputRow.visibility = View.GONE
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
        binding.btnBrain.text = brainLabelFor(brainModel)
    }

    /** Short label for the header button: Local, or the model name. */
    private fun brainLabelFor(modelId: String): String {
        if (modelId == BRAIN_LOCAL) return getString(R.string.agent_brain_local)
        return loadBrainOptions().firstOrNull { it.id == modelId }?.label
            ?: modelId.substringAfter("/", modelId)
    }

    /** One brain option: model id "provider/model", label, env var. */
    private data class BrainOption(val id: String, val label: String, val env: String?)

    /** Reads opencode-providers.json: Local first, then the cloud
     *  models in a fixed provider order (groq, llm7, openrouter,
     *  huggingface, opencode), models sorted by name. A missing or
     *  unreadable asset means Local only — the chat still works. */
    private fun loadBrainOptions(): List<BrainOption> {
        val options = mutableListOf(
            BrainOption(BRAIN_LOCAL, getString(R.string.agent_brain_local), null)
        )
        try {
            val root = JSONObject(
                requireContext().assets.open("opencode-providers.json")
                    .bufferedReader().use { it.readText() }
            ).optJSONObject("providers") ?: return options
            val providerOrder = listOf("groq", "llm7", "openrouter", "huggingface", "opencode")
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

    /** Brain picker (p22): Local by default; a cloud model needs its
     *  key in Settings (مفاتيح الذكاء الاصطناعي) or it fails at
     *  runtime — the user is warned, not blocked. Picking a brain
     *  writes it to opencode.json and restarts the server (only when
     *  it is up); a turn in progress blocks the switch. */
    private fun showBrainPicker() {
        if (turnRunning) {
            Toast.makeText(requireContext(), R.string.agent_thinking, Toast.LENGTH_SHORT).show()
            return
        }
        val options = loadBrainOptions()
        val labels = options.map { it.label }
        val current = options.indexOfFirst { it.id == brainModel }.coerceAtLeast(0)
        MudifDialog.select(
            requireContext(),
            getString(R.string.agent_brain_title),
            labels,
            current
        ) { index ->
            val picked = options[index]
            if (picked.id == brainModel) return@select
            if (!AgentServer.setModel(requireContext(), picked.id)) {
                Toast.makeText(requireContext(), R.string.agent_brain_failed, Toast.LENGTH_SHORT).show()
                return@select
            }
            brainModel = picked.id
            prefs().edit().putString(KEY_BRAIN_MODEL, brainModel).apply()
            updateBrainButton()
            // Cloud model without its key: warn (the model will fail
            // until the key is added), but keep the choice.
            if (picked.env != null) {
                val prefKey = when (picked.env) {
                    "GROQ_API_KEY" -> "agent_key_groq"
                    "HF_TOKEN" -> "agent_key_hf"
                    "LLM7_API_KEY" -> "agent_key_llm7"
                    "OPENROUTER_API_KEY" -> "agent_key_openrouter"
                    else -> null
                }
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
