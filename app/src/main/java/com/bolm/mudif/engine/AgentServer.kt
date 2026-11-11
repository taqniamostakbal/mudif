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

    private const val START_SCRIPT_ASSET = "mudif-agent/agent-start.sh"
    private const val START_SCRIPT_GUEST_PATH = "root/.config/mudif/agent-start.sh"
    private const val MCP_PROXY_ASSET = "mudif-agent/mudif-mcp-server.py"
    private const val MCP_PROXY_GUEST_PATH = "root/.config/mudif/mudif-mcp-server.py"
    private const val OPENCODE_CONFIG_GUEST_PATH = "root/.config/opencode/opencode.json"
    private const val OPENCODE_CONFIG_BACKUP_GUEST_PATH =
        "root/.config/opencode/opencode.json.mudif-bak"

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
     * The Mudif identity prompt (Fusha default). The chat sends a
     * per-message system prompt built from this plus the reply
     * language; this copy is the fallback in the config. Short on
     * purpose: the small local brain fails in long contexts (rig
     * rounds 1-2). Safety rules are the user's standing 4-click rule:
     * never delete, send, pay, or confirm identity as him without
     * his explicit tap.
     */
    const val SYSTEM_PROMPT_FUSHA =
        "أنت مساعد مضيف، المساعد العربي داخل نظام مضيف (ديبيان + LXQt على الهاتف). " +
            "أجب بالعربية الفصحى وباختصار. نفّذ المهام بالأدوات المتاحة، وقل الحقيقة فقط: " +
            "إن لم تعرف شيئا قل لا أعرف، ولا تخترع نتيجة. " +
            "لا تحذف أي شيء، ولا ترسل أي رسالة، ولا تدفع أي مبلغ، ولا تؤكد أي هوية نيابة عن المستخدم " +
            "إلا بعد موافقته الصريحة."
}
