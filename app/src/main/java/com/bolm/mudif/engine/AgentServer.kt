package com.bolm.mudif.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject

/**
 * Phase 5: the agent brain server inside the Debian guest.
 *
 * The brain is OpenCode, headless (`opencode serve`) on the shared
 * loopback 127.0.0.1:4096, with Ollama (127.0.0.1:11434) as the local
 * model host. The app reaches the guest on the same loopback path
 * the X server (6006) and audio (4713) use. Nothing here ever
 * touches a remote host.
 *
 * Security boundary: loopback-only binding. No server password in
 * Phase 5: the console WebView cannot do basic auth yet, and the
 * rig runs the same way. A password (OPENCODE_SERVER_PASSWORD) is a
 * later hardening step once the client and the WebView can both
 * present credentials; it must be verified on the rig first, not
 * assumed.
 *
 * The server is started by staging the asset script
 * mudif-agent/agent-start.sh into the rootfs
 * (/root/.config/mudif/agent-start.sh) and running it on the
 * GuestSession PTY. The script is idempotent: it never restarts a
 * server that is already running, so starting the agent from the
 * chat cannot kill a brain that is mid-test or mid-task.
 */
object AgentServer {
    private const val TAG = "AgentServer"

    const val AGENT_SERVER_HOST = "127.0.0.1"
    const val AGENT_SERVER_PORT = 4096
    const val AGENT_SERVER_URL = "http://127.0.0.1:4096"

    private const val PROBE_TIMEOUT_MS = 1500
    private const val PROBE_ATTEMPTS = 3
    private const val START_POLL_TIMEOUT_MS = 30_000L
    private const val START_POLL_INTERVAL_MS = 500L
    private const val STOP_WAIT_TIMEOUT_MS = 10_000L
    private const val STOP_KILL_WAIT_TIMEOUT_MS = 5_000L

    private const val START_SCRIPT_ASSET = "mudif-agent/agent-start.sh"
    private const val START_SCRIPT_GUEST_PATH = "root/.config/mudif/agent-start.sh"
    private const val MCP_PROXY_ASSET = "mudif-agent/mudif-mcp-server.py"
    private const val MCP_PROXY_GUEST_PATH = "root/.config/mudif/mudif-mcp-server.py"
    private const val OPENCODE_CONFIG_GUEST_PATH = "root/.config/opencode/opencode.json"
    private const val OPENCODE_CONFIG_BACKUP_GUEST_PATH =
        "root/.config/opencode/opencode.json.mudif-bak"
    private const val PROVIDERS_ASSET = "opencode-providers.json"
    private const val KEYS_ENV_GUEST_PATH = "root/.config/mudif/keys.env"

    /** Result of [ensureStarted]. `reason` is a machine key the chat
     *  maps to a real message; null means the server is up. */
    data class EnsureResult(val up: Boolean, val reason: String? = null)

    const val REASON_ENGINE_NOT_READY = "engine_not_ready"
    const val REASON_STAGE_FAILED = "stage_failed"
    const val REASON_BOOT_FAILED = "boot_failed"
    const val REASON_START_FAILED = "start_failed"

    /** Real TCP probe: is anything accepting on the agent port?
     *  Same pattern as the X-server probe (X11Session). A refused
     *  connection means the server is not running; no reply is faked. */
    fun isUp(): Boolean {
        repeat(PROBE_ATTEMPTS) {
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(AGENT_SERVER_HOST, AGENT_SERVER_PORT),
                        PROBE_TIMEOUT_MS
                    )
                    return true
                }
            } catch (e: Exception) {
                // Down on this attempt; try again.
            }
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
            }
        }
        return false
    }

    /** Local brain model (Ollama). The assistant cannot answer
     *  without it; the image does not bake it (on-demand download). */
    const val BRAIN_MODEL = "qwen3:1.7b"

    /** Local vision model (Ollama): reads the desktop screenshot
     *  into text. Same on-demand rule as the brain. */
    const val VISION_MODEL = "qwen2.5vl:3b"
    private const val OLLAMA_TAGS_URL = "http://127.0.0.1:11434/api/tags"
    private const val OLLAMA_PS_URL = "http://127.0.0.1:11434/api/ps"
    private const val OLLAMA_PULL_URL = "http://127.0.0.1:11434/api/pull"
    private const val PULL_READ_TIMEOUT_MS = 120_000
    private const val STOP_VERIFY_DELAY_MS = 2_000L

    /** Is Ollama answering on 11434? GET /api/tags, 200 means up.
     *  Used by the watchdog; never throws. Never on the UI thread. */
    fun isOllamaUp(): Boolean {
        return try {
            val conn = java.net.URL(OLLAMA_TAGS_URL).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    /** Models Ollama currently has loaded, from GET /api/ps. Empty
     *  when Ollama is down or nothing is loaded. Never throws. */
    fun runningLocalModels(): List<String> {
        return try {
            val conn = java.net.URL(OLLAMA_PS_URL).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val models = JSONObject(body).optJSONArray("models") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until models.length()) {
                val obj = models.getJSONObject(i)
                val name = obj.optString("model", obj.optString("name", ""))
                if (name.isNotEmpty()) out.add(name)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Is the model present in Ollama? Null = Ollama is not
     *  reachable (unknown, the servers are not up yet). Never called
     *  on the UI thread (network). */
    fun hasModel(model: String): Boolean? {
        return try {
            val conn = java.net.URL(OLLAMA_TAGS_URL).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val models = JSONObject(body).optJSONArray("models") ?: return false
            for (i in 0 until models.length()) {
                // Ollama stores a bare pull name with the implicit
                // ":latest" tag ("qwen2.5vl:3b" -> "qwen2.5vl:3b:latest"),
                // so an untagged query must match both spellings.
                val name = models.getJSONObject(i).optString("name")
                if (name == model || name == "$model:latest") return true
            }
            false
        } catch (e: Exception) {
            null
        }
    }

    /** Is the brain model present in Ollama? Null = Ollama is not
     *  reachable (unknown, the servers are not up yet). Never called
     *  on the UI thread (network). */
    fun hasBrainModel(): Boolean? = hasModel(BRAIN_MODEL)

    /** Downloads a model from Ollama (POST /api/pull, SSE
     *  progress). [onProgress] gets (downloadedBytes, totalBytes)
     *  on the calling thread; the caller posts to the UI thread.
     *  Retries once on failure. Returns true when the model is
     *  present afterwards. Never called on the UI thread. */
    fun pullModel(model: String, onProgress: (Long, Long) -> Unit): Boolean {
        repeat(2) { attempt ->
            try {
                val conn = java.net.URL(OLLAMA_PULL_URL).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = PROBE_TIMEOUT_MS
                conn.readTimeout = PULL_READ_TIMEOUT_MS
                val body = JSONObject().put("name", model).put("stream", true).toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode != 200) {
                    Log.w(TAG, "pullModel($model): HTTP ${conn.responseCode} (attempt ${attempt + 1})")
                    return@repeat
                }
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val obj = try { JSONObject(line ?: "") } catch (_: Exception) { continue }
                        val status = obj.optString("status")
                        if (status == "success") return true
                        if (obj.has("completed") && obj.has("total")) {
                            onProgress(obj.optLong("completed"), obj.optLong("total"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pullModel($model) failed (attempt ${attempt + 1})", e)
            }
        }
        return hasModel(model) == true
    }

    /** Downloads the brain model from Ollama (POST /api/pull, SSE
     *  progress). [onProgress] gets (downloadedBytes, totalBytes)
     *  on the calling thread; the caller posts to the UI thread.
     *  Retries once on failure. Returns true when the model is
     *  present afterwards. Never called on the UI thread. */
    fun pullBrainModel(onProgress: (Long, Long) -> Unit): Boolean =
        pullModel(BRAIN_MODEL, onProgress)

    /**
     * Makes sure the brain server is running, starting it when it is
     * not. Never call on the UI thread: it boots the guest (up to
     * 20 s) and polls the port (up to 30 s). Returns [EnsureResult]
     * with the real outcome; the caller shows the reason honestly.
     */
    fun ensureStarted(context: Context): EnsureResult {
        if (isUp()) return EnsureResult(true)
        if (!DebianSystem.isReady(context)) {
            Log.w(TAG, "ensureStarted: engine not ready")
            return EnsureResult(false, REASON_ENGINE_NOT_READY)
        }
        if (!stageStartScript(context)) {
            Log.e(TAG, "ensureStarted: could not stage $START_SCRIPT_ASSET")
            return EnsureResult(false, REASON_STAGE_FAILED)
        }
        // Best-effort: the MCP proxy lets OpenCode reach the Android
        // bridge. If its staging fails, the server still starts and
        // the merge below simply skips the mcp block; the chat works
        // without the Android-layer tools.
        if (!stageAsset(context, MCP_PROXY_ASSET, MCP_PROXY_GUEST_PATH)) {
            Log.w(TAG, "ensureStarted: could not stage $MCP_PROXY_ASSET (mcp block skipped)")
        }
        // Best-effort: wiring the MCP/agent blocks must never block
        // the server itself from starting.
        try {
            mergeAgentConfig(context)
        } catch (e: Exception) {
            Log.w(TAG, "ensureStarted: opencode.json merge failed, continuing", e)
        }
        // Best-effort: the cloud keys (from settings) into keys.env,
        // so agent-start.sh can export them to opencode before it
        // starts. A missing keys.env simply means local only.
        try {
            writeKeysEnv(context)
        } catch (e: Exception) {
            Log.w(TAG, "ensureStarted: keys.env write failed, continuing", e)
        }
        if (!GuestSession.bootContainer(context)) {
            Log.e(TAG, "ensureStarted: guest boot failed: ${GuestSession.lastError}")
            return EnsureResult(false, REASON_BOOT_FAILED)
        }
        if (!GuestSession.sendCommand("sh /root/.config/mudif/agent-start.sh")) {
            Log.e(TAG, "ensureStarted: could not send start command")
            return EnsureResult(false, REASON_START_FAILED)
        }
        val deadline = System.currentTimeMillis() + START_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isUp()) {
                Log.d(TAG, "ensureStarted: server up on 4096")
                return EnsureResult(true)
            }
            try {
                Thread.sleep(START_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
            }
        }
        Log.e(TAG, "ensureStarted: server did not answer within 30 s")
        return EnsureResult(false, REASON_START_FAILED)
    }

    /**
     * Stops the OpenCode server only (never Ollama: other features
     * share it). Best-effort; the guest must be booted. Called when
     * the agent is torn down, never during a turn.
     *
     * Returns only after the agent port stops answering (or the
     * bounded wait expires): `pkill` only signals the process, so
     * without this wait the restart right after this stop would see
     * the old server still up and return without starting a new
     * one, and the new keys would never load. Never on the UI
     * thread: the wait alone can take up to 15 s.
     */
    fun stop(context: Context) {
        if (!GuestSession.booted) return
        try {
            TerminalSession.runCommandDebian(
                context, "pkill -f 'opencode serve' || true", 10, 30
            )
        } catch (e: Exception) {
            Log.w(TAG, "stop: pkill failed", e)
        }
        if (waitForDown(STOP_WAIT_TIMEOUT_MS)) return
        // Graceful stop did not kill it in time; force it, or the
        // restart after this stop starts nothing.
        try {
            TerminalSession.runCommandDebian(
                context, "pkill -9 -f 'opencode serve' || true", 10, 30
            )
        } catch (e: Exception) {
            Log.w(TAG, "stop: pkill -9 failed", e)
        }
        if (!waitForDown(STOP_KILL_WAIT_TIMEOUT_MS)) {
            Log.e(TAG, "stop: server still up after SIGKILL")
        }
    }

    /** True when the agent port stops answering within [timeoutMs].
     *  One confirming probe can itself take up to ~5 s ([isUp]
     *  retries), so the wall time can exceed the timeout by that. */
    private fun waitForDown(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isUp()) return true
            try {
                Thread.sleep(START_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
            }
        }
        return !isUp()
    }

    /**
     * Stops a local model that is still generating after a turn
     * timed out or was cancelled (phase 3: the OpenCode abort alone
     * never stopped the llama-server, which kept computing at 66%
     * CPU for 20 minutes after the timeout). [model] is the Ollama
     * model name, or null to stop every loaded model. Only the
     * named model is stopped, never the vision model or anything
     * else sharing Ollama. Steps: `ollama stop` for each target,
     * verify with /api/ps, and only as a last resort
     * `pkill -f llama-server`. Returns the outcome, never throws:
     * "stopped", "still running", "not running", "ollama down".
     * The caller logs it; the timeout message stays honest about
     * the turn, the model stop is a separate, verified step.
     */
    fun stopLocalModel(context: Context, model: String? = null): String {
        val running = runningLocalModels()
        if (running.isEmpty()) {
            return if (isOllamaUp()) "not running" else "ollama down"
        }
        val targets = if (model == null) {
            running
        } else {
            val bare = model.removeSuffix(":latest")
            running.filter { it == model || it == "$model:latest" || it.removeSuffix(":latest") == bare }
        }
        if (targets.isEmpty()) return "not running"
        try {
            for (name in targets) {
                TerminalSession.runCommandDebian(
                    context, "ollama stop '${name.replace("'", "'\\''")}'", 10, 30
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopLocalModel: ollama stop failed", e)
            return "ollama down"
        }
        try {
            Thread.sleep(STOP_VERIFY_DELAY_MS)
        } catch (_: InterruptedException) {
        }
        if (runningLocalModels().none { it in targets }) return "stopped"
        // Last resort: the generate survived `ollama stop`.
        try {
            TerminalSession.runCommandDebian(
                context, "pkill -f llama-server || true", 10, 30
            )
        } catch (e: Exception) {
            Log.w(TAG, "stopLocalModel: pkill failed", e)
        }
        try {
            Thread.sleep(STOP_VERIFY_DELAY_MS)
        } catch (_: InterruptedException) {
        }
        return if (runningLocalModels().isEmpty()) "stopped" else "still running"
    }

    /**
     * Copies the start script asset into the rootfs (same direct
     * file-write pattern LxqtLook uses for configs). The guest sees
     * it at /root/.config/mudif/agent-start.sh once booted.
     */
    private fun stageStartScript(context: Context): Boolean =
        stageAsset(context, START_SCRIPT_ASSET, START_SCRIPT_GUEST_PATH)

    /** Copies one app asset into the rootfs at the given guest path.
     *  Returns false when the asset is missing or the copy is empty. */
    private fun stageAsset(context: Context, assetPath: String, guestPath: String): Boolean {
        return try {
            val dest = File(DebianSystem.debianRootfsDir(context), guestPath)
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "stageAsset failed: $assetPath", e)
            false
        }
    }

    /**
     * Merges the Mudif agent blocks into the guest's opencode.json
     * WITHOUT touching anything else in the file: the user's model,
     * provider and agent settings are preserved byte-for-byte in
     * meaning (the file is rewritten as JSON, values kept). The
     * original is backed up once to opencode.json.mudif-bak.
     *
     * - "mcp"."mudif": the local MCP proxy to the Android bridge,
     *   added only when the staged proxy script really exists.
     * - "agent"."mudif": the Mudif system prompt (Fusha default);
     *   the chat also sends a per-message system prompt, which wins
     *   when the server honours it.
     *
     * When no config exists at all, a minimal one is created with
     * the rig-proven local Ollama provider (qwen3:1.7b). Nothing is
     * created that needs an account or a key.
     */
    fun mergeAgentConfig(context: Context) {
        val rootfs = DebianSystem.debianRootfsDir(context)
        val configFile = File(rootfs, OPENCODE_CONFIG_GUEST_PATH)
        val config = if (configFile.exists()) {
            try {
                JSONObject(configFile.readText())
            } catch (e: Exception) {
                Log.w(TAG, "mergeAgentConfig: unreadable config, starting fresh", e)
                JSONObject()
            }
        } else {
            JSONObject()
        }
        if (!configFile.exists()) {
            // Rig-proven shape (evidence/opencode.json): local Ollama.
            val ollama = JSONObject().apply {
                put("npm", "@ai-sdk/openai-compatible")
                put("name", "Ollama (local)")
                put("options", JSONObject().put("baseURL", "http://127.0.0.1:11434/v1"))
                put("models", JSONObject().put("qwen3:1.7b", JSONObject().put("name", "Qwen3 1.7B local")))
            }
            config.put("provider", JSONObject().put("ollama", ollama))
            config.put("model", "ollama/qwen3:1.7b")
        } else if (!config.has("model")) {
            // A config with no model cannot answer; keep the user's
            // providers and point at the local model.
            config.put("model", "ollama/qwen3:1.7b")
        }
        // p22 - free cloud providers: merge the 9 providers from the
        // bundled opencode-providers.json (groq, huggingface, llm7,
        // ollama, opencode, openrouter, google, cohere, mistral, tokenlab,
        // 45 models, no keys in the asset; the keys come from
        // settings via keys.env). Only the providers MISSING from
        // the config are added, so the rig-proven local Ollama entry
        // (with its npm package) and any provider the user added
        // himself are never replaced.
        // The asset uses "package"/"settings"; the rig-proven
        // opencode shape is "npm"/"options", so those keys are
        // renamed on the way in. The default model stays Local
        // (ollama/qwen3:1.7b) unless the user picks another brain.
        try {
            val providersJson = JSONObject(
                context.assets.open(PROVIDERS_ASSET).bufferedReader().use { it.readText() }
            ).optJSONObject("providers")
            if (providersJson != null) {
                val providers = config.optJSONObject("provider") ?: JSONObject()
                val names = providersJson.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    if (providers.has(name)) continue
                    val entry = providersJson.optJSONObject(name) ?: continue
                    val normalized = JSONObject(entry.toString())
                    if (normalized.has("package") && !normalized.has("npm")) {
                        normalized.put("npm", normalized.remove("package"))
                    }
                    if (normalized.has("settings") && !normalized.has("options")) {
                        normalized.put("options", normalized.remove("settings"))
                    }
                    // UI-only fields for the provider screen; the
                    // guest opencode.json must not carry them.
                    normalized.remove("keyUrl")
                    normalized.remove("description")
                    normalized.remove("freeNote")
                    providers.put(name, normalized)
                }
                config.put("provider", providers)
            }
        } catch (e: Exception) {
            Log.w(TAG, "mergeAgentConfig: providers asset merge failed, continuing", e)
        }
        // MCP: the Mudif bridge proxy, only when staged.
        val proxyFile = File(rootfs, MCP_PROXY_GUEST_PATH)
        if (proxyFile.exists() && proxyFile.length() > 0) {
            val mcp = config.optJSONObject("mcp") ?: JSONObject()
            mcp.put(
                "mudif",
                JSONObject().apply {
                    put("type", "local")
                    put("command", org.json.JSONArray().put("python3").put("/root/.config/mudif/mudif-mcp-server.py"))
                    put("environment", JSONObject())
                }
            )
            config.put("mcp", mcp)
        } else {
            Log.d(TAG, "mergeAgentConfig: MCP proxy not staged yet, skipping mcp block")
        }
        // Agent: the Mudif identity prompt (Fusha default).
        val agents = config.optJSONObject("agent") ?: JSONObject()
        if (!agents.has("mudif")) {
            agents.put("mudif", JSONObject().put("prompt", SYSTEM_PROMPT_FUSHA))
        }
        config.put("agent", agents)
        configFile.parentFile?.mkdirs()
        if (configFile.exists()) {
            val backup = File(rootfs, OPENCODE_CONFIG_BACKUP_GUEST_PATH)
            if (!backup.exists()) {
                try {
                    configFile.copyTo(backup)
                } catch (e: Exception) {
                    Log.w(TAG, "mergeAgentConfig: backup failed", e)
                }
            }
        }
        configFile.writeText(config.toString(2))
        Log.d(TAG, "mergeAgentConfig: wrote ${configFile.absolutePath}")
    }

    /**
     * API keys for the cloud providers, from the app settings.
     * Written to the guest's keys.env (one KEY=value per line, only
     * the keys the user actually entered). agent-start.sh sources it
     * before starting opencode, so the providers can authenticate.
     * The values are never logged. A missing/empty keys.env simply
     * means local only.
     */
    fun writeKeysEnv(context: Context): Boolean {
        return try {
            val prefs = com.bolm.mudif.ui.settings.ViewerPrefs.prefs(context)
            val lines = StringBuilder()
            var count = 0
            for ((prefKey, envKey) in listOf(
                "agent_key_groq" to "GROQ_API_KEY",
                "agent_key_hf" to "HF_TOKEN",
                "agent_key_llm7" to "LLM7_API_KEY",
                "agent_key_openrouter" to "OPENROUTER_API_KEY",
                "agent_key_gemini" to "GEMINI_API_KEY",
                "agent_key_cohere" to "CO_API_KEY",
                "agent_key_mistral" to "MISTRAL_API_KEY",
                "agent_key_tokenlab" to "TOKENLAB_API_KEY",
                "agent_key_exa" to "EXA_API_KEY",
                "agent_key_tavily" to "TAVILY_API_KEY"
            )) {
                val v = prefs.getString(prefKey, "")?.trim() ?: ""
                if (v.isNotEmpty()) {
                    lines.append("$envKey=$v\n")
                    count++
                }
            }
            val dest = File(DebianSystem.debianRootfsDir(context), KEYS_ENV_GUEST_PATH)
            dest.parentFile?.mkdirs()
            dest.writeText(lines.toString())
            Log.d(TAG, "writeKeysEnv: wrote $count keys")
            true
        } catch (e: Exception) {
            Log.w(TAG, "writeKeysEnv failed", e)
            false
        }
    }

    /**
     * Switches the brain: writes the chosen model id
     * (e.g. "groq/openai/gpt-oss-120b", "ollama/qwen3:1.7b") into
     * opencode.json "model". The caller restarts opencode (stop +
     * ensureStarted) for the change to take effect. Returns false
     * when the config cannot be read or written.
     */
    fun setModel(context: Context, modelId: String): Boolean {
        return try {
            val configFile = File(DebianSystem.debianRootfsDir(context), OPENCODE_CONFIG_GUEST_PATH)
            if (!configFile.exists()) return false
            val config = JSONObject(configFile.readText())
            config.put("model", modelId)
            configFile.writeText(config.toString(2))
            Log.d(TAG, "setModel: model=$modelId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setModel failed", e)
            false
        }
    }

    /**
     * The Mudif identity prompt (Fusha default). The chat sends a
     * per-message system prompt built from this plus the reply
     * language; this copy is the fallback in the config. Short on
     * purpose: the small local brain fails in long contexts (rig
     * rounds 1-2). Safety rules are the user's standing 4-click rule:
     * never delete, send, pay, or confirm identity as him without
     * his explicit tap.
     */
    const val SYSTEM_PROMPT_FUSHA =
        "أنت مساعد مضيف، المساعد العربي داخل نظام مضيف على الهاتف. " +
            "أجب بالعربية الفصحى وباختصار. نفّذ المهام بالأدوات المتاحة، وقل الحقيقة فقط: " +
            "إن لم تعرف شيئا قل لا أعرف، ولا تخترع نتيجة. " +
            "لا تحذف أي شيء، ولا ترسل أي رسالة، ولا تدفع أي مبلغ، ولا تؤكد أي هوية نيابة عن المستخدم " +
            "إلا بعد موافقته الصريحة."
}
