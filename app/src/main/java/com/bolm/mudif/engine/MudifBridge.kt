package com.bolm.mudif.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bolm.mudif.ui.agent.MudifAccessibilityService
import com.bolm.mudif.ui.settings.ViewerPrefs
import com.bolm.mudif.ui.store.ProgramStore
import com.bolm.mudif.ui.store.StoreCatalog
import com.bolm.mudif.ui.vnc.VncActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 6, the Android layer's control plane for the agent.
 *
 * A tiny stdlib HTTP server (ServerSocket, no new dependency) bound
 * to 127.0.0.1:18080 ONLY, never 0.0.0.0: an unrooted phone has no
 * firewall, so a wildcard bind would expose the control plane to
 * the whole Wi-Fi. The Debian guest reaches it on the shared
 * loopback (proot shares Android's network namespace, the same
 * path the X server on 6006 already uses).
 *
 * Auth: every endpoint requires `Authorization: Bearer <token>`.
 * The token is a random 32-byte hex string, generated once and kept
 * in the private SharedPreferences "mudif_bridge" (key "token"), and
 * also written to filesDir/tmp/mudif-bridge-token so the guest sees
 * it at /tmp/mudif-bridge-token (the guest /tmp is bind-mounted over
 * the app's files/tmp, see ProgramStore.stageAssetToGuest). Missing
 * or wrong token answers 401 with a JSON error, never the data.
 * The token is never written to the bridge log and never logged.
 *
 * Endpoints, ONLY what has a real call path. Anything without one
 * is omitted, not faked:
 *
 * - GET  /state             engine/desktop/agent/bridge/battery/a11y snapshot
 * - POST /engine/start      boots the Debian guest ONLY (GuestSession);
 *                           it does NOT start the X11 desktop
 * - POST /store/install     {id} -> real catalog lookup + ProgramStore
 *                           install + verify; synchronous, can take
 *                           minutes, the MCP proxy must use a long
 *                           read timeout (see the Python proxy)
 * - POST /settings          {key, value}; whitelist is exactly the
 *                           settable ViewerPrefs.SECTIONS keys
 *                           (ActionSetting keys are not values and
 *                           are rejected); unknown key -> 400
 * - POST /cast/start|stop   TvStreamer + CastManager, the same calls
 *                           VncActivity's TV button makes. Start
 *                           loads the stream onto an ALREADY
 *                           connected Cast session; when no TV is
 *                           connected it reports
 *                           reason=no_cast_session and the stream
 *                           URL, the bridge cannot pick a TV itself
 *                           (that needs the Cast chooser dialog)
 * - GET  /android/ui, POST /android/tap {x,y}, POST /android/type
 *        {text}, POST /android/swipe {x1,y1,x2,y2},
 *        GET  /android/screenshot, all delegated to
 *        MudifAccessibilityService; when the service is not
 *        enabled/connected they answer 503, never a fake tree
 * - GET  /logs              last 100 lines of the bridge's own log
 *
 * Logging: every request is logged (method, path, status) to
 * filesDir/agent/bridge.log, capped at ~200 KB by truncation.
 * Tokens and request bodies are never logged.
 *
 * Lifecycle: [start]/[stop] are idempotent. The accept loop runs on
 * a daemon thread, one thread per connection. [token] returns the
 * stored token (generating it first) for the supervisor/proxy.
 */
object MudifBridge {

    private const val TAG = "MudifBridge"

    const val HOST = "127.0.0.1"
    const val PORT = 18080

    private const val PREFS_NAME = "mudif_bridge"
    private const val KEY_TOKEN = "token"
    private const val TOKEN_FILE_NAME = "mudif-bridge-token"
    private const val LOG_MAX_BYTES = 200 * 1024L
    private const val LOG_KEEP_BYTES = 100 * 1024L

    @Volatile
    var running = false
        private set

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    private val logLock = Any()

    /** The bridge token: read from private prefs, generated once if absent. */
    fun token(context: Context): String {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TOKEN, null)
        if (existing != null) {
            writeTokenFile(appCtx, existing)
            return existing
        }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val fresh = bytes.joinToString("") { "%02x".format(Locale.US, it) }
        prefs.edit().putString(KEY_TOKEN, fresh).apply()
        writeTokenFile(appCtx, fresh)
        return fresh
    }

    /** Writes the token where the guest can read it: files/tmp is bind-mounted as guest /tmp. */
    private fun writeTokenFile(appCtx: Context, token: String) {
        try {
            val dir = File(appCtx.filesDir, "tmp").apply { mkdirs() }
            File(dir, TOKEN_FILE_NAME).writeText(token)
        } catch (e: Exception) {
            Log.w(TAG, "could not write token file", e)
        }
    }

    /** Idempotent start. Returns true when the server is listening. */
    @Synchronized
    fun start(context: Context): Boolean {
        if (running) return true
        return try {
            // Token first: the guest proxy reads the file at startup.
            token(context)
            val socket = ServerSocket(PORT, 0, InetAddress.getByName(HOST))
            serverSocket = socket
            running = true
            val thread = Thread({
                acceptLoop(context.applicationContext, socket)
            }, "mudif-bridge-accept").apply { isDaemon = true }
            acceptThread = thread
            thread.start()
            Log.d(TAG, "listening on $HOST:$PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            running = false
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
            serverSocket = null
            false
        }
    }

    /** Idempotent stop. */
    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
        Log.d(TAG, "stopped")
    }

    private fun acceptLoop(appCtx: Context, socket: ServerSocket) {
        while (running) {
            val conn = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed", e)
                break
            }
            Thread({
                try {
                    handle(appCtx, conn)
                } catch (e: Exception) {
                    Log.w(TAG, "connection failed", e)
                } finally {
                    try {
                        conn.close()
                    } catch (_: Exception) {
                    }
                }
            }, "mudif-bridge-conn").apply { isDaemon = true }.start()
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun handle(appCtx: Context, conn: Socket) {
        conn.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(conn.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.trim().split(" ")
        if (parts.size < 2) {
            send(conn, 400, errJson("bad_request", "Malformed request line."))
            return
        }
        val method = parts[0].uppercase(Locale.US)
        val rawPath = parts[1]
        val path = rawPath.substringBefore("?")
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                    line.substring(colon + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyChars = CharArray(contentLength.coerceIn(0, 1_000_000))
        var read = 0
        while (read < bodyChars.size) {
            val n = reader.read(bodyChars, read, bodyChars.size - read)
            if (n < 0) break
            read += n
        }
        val body = String(bodyChars, 0, read)
        val request = Request(method, path, headers, body)

        // Auth first: no endpoint is public.
        val auth = headers["authorization"] ?: ""
        if (auth != "Bearer ${token(appCtx)}") {
            send(conn, 401, errJson("unauthorized", "Missing or wrong Authorization: Bearer token."))
            logRequest(appCtx, method, path, 401)
            return
        }
        val (status, json) = route(appCtx, request)
        send(conn, status, json)
        logRequest(appCtx, method, path, status)
    }

    private fun send(conn: Socket, status: Int, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = conn.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun errJson(error: String, detail: String): JSONObject =
        JSONObject().apply {
            put("ok", false)
            put("error", error)
            put("detail", detail)
        }

    private fun route(appCtx: Context, req: Request): Pair<Int, JSONObject> {
        return when (req.method to req.path) {
            "GET" to "/state" -> 200 to state(appCtx)
            "POST" to "/engine/start" -> 200 to engineStart(appCtx)
            "POST" to "/store/install" -> storeInstall(appCtx, req)
            "POST" to "/settings" -> settings(appCtx, req)
            "POST" to "/cast/start" -> 200 to castStart(appCtx)
            "POST" to "/cast/stop" -> 200 to castStop(appCtx)
            "GET" to "/android/ui" -> androidUi()
            "POST" to "/android/tap" -> androidTap(req)
            "POST" to "/android/type" -> androidType(req)
            "POST" to "/android/swipe" -> androidSwipe(req)
            "GET" to "/android/screenshot" -> androidScreenshot()
            "GET" to "/logs" -> 200 to logs(appCtx)
            else -> {
                val known = setOf(
                    "/state", "/engine/start", "/store/install", "/settings",
                    "/cast/start", "/cast/stop", "/android/ui", "/android/tap",
                    "/android/type", "/android/swipe", "/android/screenshot", "/logs"
                )
                if (req.path in known) {
                    405 to errJson("method_not_allowed", "${req.method} is not allowed on ${req.path}.")
                } else {
                    404 to errJson("not_found", "No such endpoint: ${req.path}.")
                }
            }
        }
    }

    /**
     * Live snapshot. desktop_running uses the same check VncActivity
     * uses (X11Session.isDesktopRunning with DESKTOP_LXQT, the
     * desktop value callers actually pass, DESKTOP_MUDIF is only an
     * intent extra, never a desktop name). Cost: when the desktop is
     * DOWN the check polls twice and can take up to ~40 s; when it
     * is up it answers on the first probe. Callers must allow a
     * long timeout on this endpoint.
     */
    private fun state(appCtx: Context): JSONObject {
        return JSONObject().apply {
            put("ok", true)
            put("engine_ready", DebianSystem.isReady(appCtx))
            put("desktop_running", X11Session.isDesktopRunning(appCtx, VncActivity.DESKTOP_LXQT, true))
            put("agent_up", AgentServer.isUp())
            put("bridge_up", true)
            put("battery_unrestricted", AgentSupervisorService.isBatteryUnrestricted(appCtx))
            put("accessibility_enabled", AgentSupervisorService.isAccessibilityEnabled(appCtx))
        }
    }

    /**
     * Boots the Debian guest only. It does NOT start the X11 desktop:
     * the desktop is started by the viewer flow (VncActivity), never
     * as a side effect of booting the engine.
     */
    private fun engineStart(appCtx: Context): JSONObject {
        val ok = GuestSession.bootContainer(appCtx, GuestSession.GuestSystem.DEBIAN)
        return JSONObject().apply {
            put("ok", ok)
            put("booted", ok)
            put("desktop_started", false)
            put("detail", if (ok) "Guest booted. The X11 desktop was not started." else (GuestSession.lastError ?: "Guest boot failed."))
        }
    }

    /**
     * Installs one catalog program, then verifies it for real.
     * Synchronous: apt/deb installs can take minutes (the deb path
     * allows up to 60 min). The caller must use a long read timeout
     * and must not retry on timeout, a timed-out request may still
     * be installing in the guest.
     */
    private fun storeInstall(appCtx: Context, req: Request): Pair<Int, JSONObject> {
        val body = try {
            JSONObject(req.body)
        } catch (e: Exception) {
            return 400 to errJson("bad_json", "Request body is not valid JSON: ${e.message}")
        }
        val id = body.optString("id", "")
        if (id.isEmpty()) return 400 to errJson("missing_field", "Field \"id\" is required.")
        val program = try {
            StoreCatalog.load(appCtx).firstOrNull { it.id == id }
        } catch (e: Exception) {
            return 503 to errJson("catalog_failed", "Could not load the store catalog: ${e.message}")
        } ?: return 404 to errJson("unknown_program", "No program with id \"$id\" in the store catalog.")
        val arch = ProgramStore.guestArch(appCtx)
        if (program.method == "deb") {
            if (arch.isEmpty()) {
                return 200 to JSONObject().apply {
                    put("ok", false)
                    put("installed", false)
                    put("detail", "Guest architecture is unknown, so a .deb install cannot be checked.")
                }
            }
            if (ProgramStore.isArm64(arch) && program.debArm64 == null) {
                return 200 to JSONObject().apply {
                    put("ok", false)
                    put("installed", false)
                    put("detail", "No arm64 build of ${program.name} exists; install refused on this phone.")
                }
            }
        }
        val result = ProgramStore.install(appCtx, program, arch)
        val (verified, evidence) = if (result.exitCode == 0) {
            ProgramStore.verify(appCtx, program)
        } else {
            false to ProgramStore.lastLine(appCtx, result.output)
        }
        val detail = if (result.exitCode == 0 && !verified) {
            ProgramStore.lastLine(appCtx, result.output)
        } else if (verified) {
            evidence
        } else {
            ProgramStore.lastLines(appCtx, result.output)
        }
        return 200 to JSONObject().apply {
            put("ok", result.exitCode == 0 && verified)
            put("installed", verified)
            put("detail", detail)
        }
    }

    /**
     * Writes one viewer setting. The whitelist is exactly the
     * settable keys in ViewerPrefs.SECTIONS (Switch/List/Slider/Text);
     * ActionSetting keys (enableAccessibilityService,
     * configureResponseToUserActions, requestNotificationPermission)
     * are actions, not values, and are rejected like unknown keys.
     * Value types follow SettingsActivity: Switch -> boolean,
     * List -> string (must be one of the setting's options),
     * Slider -> int within min..max, Text -> string.
     */
    private fun settings(appCtx: Context, req: Request): Pair<Int, JSONObject> {
        val body = try {
            JSONObject(req.body)
        } catch (e: Exception) {
            return 400 to errJson("bad_json", "Request body is not valid JSON: ${e.message}")
        }
        val key = body.optString("key", "")
        if (key.isEmpty()) return 400 to errJson("missing_field", "Field \"key\" is required.")
        if (!body.has("value")) return 400 to errJson("missing_field", "Field \"value\" is required.")
        val setting = ViewerPrefs.SECTIONS
            .flatMap { it.settings }
            .firstOrNull { it.key == key }
            ?: return 400 to errJson("unknown_key", "No such viewer setting: \"$key\".")
        val editor = ViewerPrefs.prefs(appCtx).edit()
        when (setting) {
            is ViewerPrefs.SwitchSetting -> {
                val value = when (val v = body.opt("value")) {
                    is Boolean -> v
                    is String -> v.toBooleanStrictOrNull()
                        ?: return 400 to errJson("bad_value", "Setting \"$key\" needs a boolean.")
                    else -> return 400 to errJson("bad_value", "Setting \"$key\" needs a boolean.")
                }
                editor.putBoolean(key, value)
            }
            is ViewerPrefs.ListSetting -> {
                val value = body.optString("value", "")
                val allowed = setting.options.map { it.first }
                if (value !in allowed) {
                    return 400 to errJson("bad_value", "Setting \"$key\" must be one of: ${allowed.joinToString(", ")}.")
                }
                editor.putString(key, value)
            }
            is ViewerPrefs.SliderSetting -> {
                val value = when (val v = body.opt("value")) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull()
                        ?: return 400 to errJson("bad_value", "Setting \"$key\" needs an integer.")
                    else -> return 400 to errJson("bad_value", "Setting \"$key\" needs an integer.")
                }
                if (value < setting.min || value > setting.max) {
                    return 400 to errJson("bad_value", "Setting \"$key\" must be between ${setting.min} and ${setting.max}.")
                }
                editor.putInt(key, value)
            }
            is ViewerPrefs.TextSetting -> {
                editor.putString(key, body.optString("value", ""))
            }
            is ViewerPrefs.ActionSetting -> {
                return 400 to errJson("unknown_key", "\"$key\" is an action, not a settable value.")
            }
        }
        editor.apply()
        return 200 to JSONObject().apply {
            put("ok", true)
            put("key", key)
            put("detail", "Setting \"$key\" saved.")
        }
    }

    /**
     * Starts the TV cast the same way VncActivity's TV button does:
     * TvStreamer.start (needs the guest booted and the X display
     * open) + CastManager.loadNow on the HLS URL. A Cast session
     * must ALREADY be connected for the picture to reach a TV: when
     * none is, the stream still starts (the HLS URL is returned)
     * but ok=false with reason=no_cast_session, picking a TV needs
     * the Cast chooser dialog, which a headless bridge cannot show.
     */
    private fun castStart(appCtx: Context): JSONObject {
        if (!CastManager.available) {
            // CastManager.init must run on the main thread (same
            // requirement as VncActivity.onCreate). Wait for it.
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    CastManager.init(appCtx)
                } catch (_: Exception) {
                }
                latch.countDown()
            }
            try {
                latch.await(10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (!CastManager.available) {
            return JSONObject().apply {
                put("ok", false)
                put("streaming", false)
                put("reason", "cast_unavailable")
                put("detail", "Google Cast is not available on this phone: ${CastManager.lastError ?: "Play Services / Cast missing."}")
            }
        }
        if (!TvStreamer.start(appCtx)) {
            return JSONObject().apply {
                put("ok", false)
                put("streaming", false)
                put("reason", "streamer_failed")
                put("detail", TvStreamer.lastError ?: "The TV streamer could not start (guest booted? desktop running?).")
            }
        }
        val url = TvStreamer.streamUrl()
            ?: return JSONObject().apply {
                put("ok", false)
                put("streaming", true)
                put("reason", "no_stream_url")
                put("detail", "The streamer started but produced no HLS URL.")
            }
        CastManager.setPendingHlsUrl(url)
        val loaded = CastManager.loadNow(url)
        return JSONObject().apply {
            put("ok", loaded)
            put("streaming", true)
            put("stream_url", url)
            put("cast_connected", loaded)
            if (!loaded) {
                put("reason", "no_cast_session")
                put("detail", "Streaming started at $url, but no TV is connected yet: pick a TV in the Cast chooser (VncActivity TV button). The bridge cannot pick a TV itself.")
            } else {
                put("detail", "Casting to the connected TV.")
            }
        }
    }

    /** Stops the streamer and ends the Cast session. Safe when idle. */
    private fun castStop(appCtx: Context): JSONObject {
        TvStreamer.stop(appCtx)
        CastManager.stopCast()
        return JSONObject().apply {
            put("ok", true)
            put("streaming", false)
            put("detail", "TV cast stopped.")
        }
    }

    /** The accessibility service, or a 503 JSON when it is not connected. */
    private fun a11y(): Pair<MudifAccessibilityService?, JSONObject?> {
        val service = MudifAccessibilityService.instance
        if (service != null) return service to null
        return null to errJson(
            "accessibility_unavailable",
            "Mudif Assistant is not enabled in Android Settings > Accessibility. The app cannot enable it itself."
        )
    }

    private fun androidUi(): Pair<Int, JSONObject> {
        val (service, error) = a11y()
        if (service == null) return 503 to error!!
        return 200 to service.getTreeJson()
    }

    private fun androidTap(req: Request): Pair<Int, JSONObject> {
        val (service, error) = a11y()
        if (service == null) return 503 to error!!
        val body = try {
            JSONObject(req.body)
        } catch (e: Exception) {
            return 400 to errJson("bad_json", "Request body is not valid JSON: ${e.message}")
        }
        val x = body.optInt("x", Int.MIN_VALUE)
        val y = body.optInt("y", Int.MIN_VALUE)
        if (x == Int.MIN_VALUE || y == Int.MIN_VALUE || x < 0 || y < 0) {
            return 400 to errJson("missing_field", "Fields \"x\" and \"y\" are required and must be >= 0.")
        }
        return 200 to service.tap(x, y)
    }

    private fun androidType(req: Request): Pair<Int, JSONObject> {
        val (service, error) = a11y()
        if (service == null) return 503 to error!!
        val body = try {
            JSONObject(req.body)
        } catch (e: Exception) {
            return 400 to errJson("bad_json", "Request body is not valid JSON: ${e.message}")
        }
        val text = body.optString("text", "")
        if (text.isEmpty()) return 400 to errJson("missing_field", "Field \"text\" is required and must not be empty.")
        return 200 to service.type(text)
    }

    private fun androidSwipe(req: Request): Pair<Int, JSONObject> {
        val (service, error) = a11y()
        if (service == null) return 503 to error!!
        val body = try {
            JSONObject(req.body)
        } catch (e: Exception) {
            return 400 to errJson("bad_json", "Request body is not valid JSON: ${e.message}")
        }
        val x1 = body.optInt("x1", Int.MIN_VALUE)
        val y1 = body.optInt("y1", Int.MIN_VALUE)
        val x2 = body.optInt("x2", Int.MIN_VALUE)
        val y2 = body.optInt("y2", Int.MIN_VALUE)
        if (x1 == Int.MIN_VALUE || y1 == Int.MIN_VALUE || x2 == Int.MIN_VALUE || y2 == Int.MIN_VALUE ||
            x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0
        ) {
            return 400 to errJson("missing_field", "Fields \"x1\", \"y1\", \"x2\", \"y2\" are required and must be >= 0.")
        }
        return 200 to service.swipe(x1, y1, x2, y2)
    }

    private fun androidScreenshot(): Pair<Int, JSONObject> {
        val (service, error) = a11y()
        if (service == null) return 503 to error!!
        val result = service.screenshot()
        return if (result.optBoolean("ok", false)) 200 to result else 503 to result
    }

    /** Last 100 lines of this bridge's own log. */
    private fun logs(appCtx: Context): JSONObject {
        val file = logFile(appCtx)
        val lines = try {
            if (file.exists()) file.readLines().takeLast(100) else emptyList()
        } catch (e: Exception) {
            emptyList<String>()
        }
        return JSONObject().apply {
            put("ok", true)
            put("logs", org.json.JSONArray(lines))
        }
    }

    private fun logFile(appCtx: Context): File {
        val dir = File(appCtx.filesDir, "agent").apply { mkdirs() }
        return File(dir, "bridge.log")
    }

    /** One line per request: method, path, status. Never the token, never a body. */
    private fun logRequest(appCtx: Context, method: String, path: String, status: Int) {
        synchronized(logLock) {
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                val file = logFile(appCtx)
                file.appendText("$stamp $method $path -> $status\n")
                if (file.length() > LOG_MAX_BYTES) {
                    val bytes = file.readBytes()
                    val keep = bytes.copyOfRange(
                        (bytes.size - LOG_KEEP_BYTES.toInt()).coerceAtLeast(0), bytes.size
                    )
                    file.writeBytes(keep)
                }
            } catch (_: Exception) {
                // Logging must never break a request.
            }
        }
    }
}
