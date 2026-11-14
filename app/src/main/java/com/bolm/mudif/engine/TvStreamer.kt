package com.bolm.mudif.engine

import android.content.Context
import com.bolm.mudif.R
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * Phase 2, wireless TV: the shared sender pipeline.
 *
 * HYPOTHESIS (original, DISPROVEN by reading X11Session.kt):
 * "The app can feed its own VNC view into a MediaCodec encoder
 * Surface directly." False: X11Session.openViewer() starts
 * com.termux.x11.MainActivity (the JitPack fork's activity); the
 * desktop framebuffer is rendered by THAT activity, not by a
 * Mudif-owned View. Grep over app/src/main/java finds zero
 * MediaCodec / MediaProjection / VNC-client code in this app, so
 * there is no app-owned Surface to feed an encoder.
 *
 * CORRECTED pipeline (reuses the PROVEN path): the guest ffmpeg
 * grabs the X display with x11grab over 127.0.0.1:6, exactly like
 * ScreenRecorder, whose command was verified live on the rig
 * (ffmpeg PID 4150, ~10 s, SIGINT clean stop). One ffmpeg process
 * encodes once (libx264 ultrafast, 15 fps) and splits the output
 * with the tee muxer into:
 *  (a) HLS segments (ffmpeg's native HLS muxer, 2 s segments) in
 *      the shared folder, served by the tiny HTTP server below ,
 *      this is what Google Cast loads (Default Media Receiver).
 *  (b) MPEG-TS over HTTP PUT/POST to the same server
 *      (/live.ts), re-broadcast chunked to TV clients, the
 *      low-latency path the companion TV app plays (< 1 s target).
 * No MediaProjection (would need a per-session consent dialog plus
 * a mediaProjection foreground service on API 34, would capture the
 * whole phone screen including the overlay and notifications, and
 * would still need a hand-rolled TS/fMP4 segmenter because
 * MediaMuxer cannot emit HLS). No new Gradle dependency: the HTTP
 * server is plain java.net, stdlib only.
 *
 * UNVERIFIED until rig/phone test: the tee muxer in the guest
 * ffmpeg build; the exact LL behaviour (this ships standard HLS
 * with short 2 s segments, NOT LL-HLS partial segments, the
 * research's 2-5 s LL-HLS figure is the protocol range, not a
 * measurement of this app); LAN reachability of the server port.
 * start() verifies at runtime that stream.m3u8 appears, and fails
 * loudly with the ffmpeg log tail when it does not.
 *
 * All start/stop/isStreaming calls must run on a BACKGROUND thread
 * (they sleep and run one-shot guest commands).
 */
object TvStreamer {

    private const val TAG = "TvStreamer"

    /** First / last HTTP port tried (8089, then 8090..8099). */
    private const val HTTP_PORT_START = 8089
    private const val HTTP_PORT_END = 8099

    /** HLS segments live in the shared folder, visible both sides. */
    private const val HLS_DIR_NAME = "mudif-tv"

    /** Guest path of the segments dir (bind-mounted shared folder). */
    private const val HLS_GUEST_DIR = "/mnt/mudif-shared/mudif-tv"

    private const val PID_FILE = "/tmp/mudif-tv.pid"
    private const val LOG_FILE = "/tmp/mudif-tv.log"

    /** NSD service type the companion TV app discovers. */
    const val NSD_SERVICE_TYPE = "_mudiftv._tcp."
    const val NSD_SERVICE_NAME = "Mudif-TV"

    enum class TvState { STOPPED, STARTING, STREAMING, ERROR }

    @Volatile
    var state: TvState = TvState.STOPPED
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Actual HTTP port bound (8089..8099), -1 when stopped. */
    @Volatile
    var httpPort: Int = -1
        private set

    /** Phone LAN IPv4 address, null when unknown. */
    @Volatile
    var lanIp: String? = null
        private set

    private var httpServer: HttpServer? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    /** Live HLS playlist URL for Cast / the companion app. Null when not streaming. */
    fun streamUrl(): String? {
        val ip = lanIp ?: return null
        val port = httpPort
        if (port < 0) return null
        if (state != TvState.STREAMING && state != TvState.STARTING) return null
        return "http://$ip:$port/stream.m3u8"
    }

    /** Low-latency progressive MPEG-TS URL for the companion app. Null when not streaming. */
    fun tsUrl(): String? {
        val ip = lanIp ?: return null
        val port = httpPort
        if (port < 0) return null
        if (state != TvState.STREAMING && state != TvState.STARTING) return null
        return "http://$ip:$port/live.ts"
    }

    private fun segmentsDir(context: Context): File =
        File(RootfsManager.sharedDir(context), HLS_DIR_NAME)

    private fun pidFile(context: Context): File =
        File(File(context.filesDir, "tmp"), "mudif-tv.pid")

    /** Phone LAN IPv4: first site-local non-loopback address, else first non-loopback. */
    fun lanIpAddress(): String? {
        return try {
            var fallback: String? = null
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val iface = ifs.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        if (addr.isSiteLocalAddress) return addr.hostAddress
                        if (fallback == null) fallback = addr.hostAddress
                    }
                }
            }
            fallback
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Starts the stream. Returns true when the HLS playlist exists
     * and the server answers. Call from a background thread.
     * Order matters: the HTTP server starts FIRST (ffmpeg's TS
     * output PUTs to it at startup; a failed ingest must not kill
     * the HLS output, the tee slave carries onfail=ignore).
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (state == TvState.STREAMING && isStreaming(context)) return true
        state = TvState.STARTING
        lastError = null
        if (!GuestSession.alive || !GuestSession.booted) {
            lastError = context.getString(R.string.ui_tv_err_not_booted)
            state = TvState.ERROR
            return false
        }
        if (!X11Session.isTcpDisplayOpen()) {
            lastError = context.getString(R.string.ui_tv_err_no_x)
            state = TvState.ERROR
            return false
        }
        val ip = lanIpAddress()
        if (ip == null) {
            lastError = context.getString(R.string.ui_tv_err_no_ip)
            state = TvState.ERROR
            return false
        }
        lanIp = ip
        // Clean stale segments so a new run never serves old video.
        val dir = segmentsDir(context)
        dir.mkdirs()
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".ts") || f.name.endsWith(".m3u8")) {
                try { f.delete() } catch (_: Exception) { }
            }
        }
        val server = HttpServer(dir, context.applicationContext)
        if (!server.start()) {
            lastError = context.getString(R.string.ui_tv_err_no_server)
            state = TvState.ERROR
            return false
        }
        httpServer = server
        httpPort = server.port
        registerNsd(context)
        val resolution = X11Session.viewerResolution(context)
        // One encode, two outputs via tee: HLS segments for Cast,
        // MPEG-TS over HTTP to our own server for low latency.
        // onfail=ignore on the TS slave: a TS ingest failure must
        // not kill the HLS output (and vice versa the HLS slave
        // keeps the default abort: if HLS fails we want to know).
        val cmd = "mkdir -p $HLS_GUEST_DIR; ffmpeg -y -f x11grab " +
            "-video_size $resolution -framerate 15 -i 127.0.0.1:6 " +
            "-map 0:v -c:v libx264 -preset ultrafast -pix_fmt yuv420p " +
            "-f tee \"[f=hls:hls_time=2:hls_list_size=6:" +
            "hls_flags=delete_segments+independent_segments]" +
            "$HLS_GUEST_DIR/stream.m3u8|" +
            "[f=mpegts:onfail=ignore]http://127.0.0.1:$httpPort/live.ts\" " +
            "> $LOG_FILE 2>&1 & echo \$! > $PID_FILE"
        if (!GuestSession.sendCommand(cmd)) {
            lastError = context.getString(R.string.ui_tv_err_no_send)
            stopServerOnly(context)
            state = TvState.ERROR
            return false
        }
        // The pid file appears once the shell backgrounds ffmpeg.
        val pidDeadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < pidDeadline) {
            if (pidFile(context).exists()) break
            Thread.sleep(200)
        }
        if (!pidFile(context).exists()) {
            lastError = context.getString(R.string.ui_tv_err_no_ffmpeg, lastLog(context).takeLast(200))
            stopServerOnly(context)
            state = TvState.ERROR
            return false
        }
        // The playlist appears once the first 2 s segment is done.
        val playlist = File(dir, "stream.m3u8")
        val playDeadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < playDeadline) {
            if (playlist.exists() && playlist.length() > 0) {
                state = TvState.STREAMING
                Log.d(TAG, "streaming: ${streamUrl()} ts=${tsUrl()}")
                return true
            }
            if (!isPidAlive(context)) break
            Thread.sleep(500)
        }
        lastError = context.getString(R.string.ui_tv_err_no_stream, lastLog(context).takeLast(300))
        stop(context)
        state = TvState.ERROR
        return false
    }

    /**
     * Stops ffmpeg (SIGINT, so it finalizes cleanly), the HTTP
     * server and the NSD advertisement. Call from a background
     * thread. Safe to call when already stopped.
     */
    @Synchronized
    fun stop(context: Context) {
        try {
            val pidF = pidFile(context)
            if (pidF.exists()) {
                GuestSession.sendCommand("kill -INT \$(cat $PID_FILE) 2>/dev/null")
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline) {
                    if (!isPidAlive(context)) break
                    Thread.sleep(500)
                }
                try { pidF.delete() } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        stopServerOnly(context)
        state = TvState.STOPPED
        httpPort = -1
    }

    private fun stopServerOnly(context: Context) {
        try { unregisterNsd() } catch (_: Exception) { }
        try { httpServer?.stop() } catch (_: Exception) { }
        httpServer = null
        httpPort = -1
    }

    /** True when the streaming ffmpeg is alive right now. */
    fun isStreaming(context: Context): Boolean {
        if (!pidFile(context).exists()) return false
        return isPidAlive(context)
    }

    /** Ground truth: does the recorded pid still exist in the guest? */
    private fun isPidAlive(context: Context): Boolean {
        return try {
            val r = TerminalSession.runCommandDebian(
                context,
                "kill -0 \$(cat $PID_FILE) 2>/dev/null && echo ALIVE",
                idleTimeoutSec = 10,
                maxTimeoutSec = 20
            )
            r.exitCode == 0 && r.output.contains("ALIVE")
        } catch (_: Exception) {
            false
        }
    }

    /** Last lines of ffmpeg's log (diagnostics when streaming fails). */
    fun lastLog(context: Context): String {
        return try {
            val f = File(File(context.filesDir, "tmp"), "mudif-tv.log")
            if (!f.exists()) return "(no ffmpeg log yet)"
            f.readLines().takeLast(15).joinToString("\n")
        } catch (e: Exception) {
            "(log unreadable: ${e.message})"
        }
    }

    private fun registerNsd(context: Context) {
        try {
            val mgr = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = NSD_SERVICE_NAME
                serviceType = NSD_SERVICE_TYPE
                port = httpPort
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.d(TAG, "NSD registered: ${info.serviceName} :${info.port}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD registration failed: $errorCode (manual IP still works)")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager = mgr
            nsdListener = listener
        } catch (e: Exception) {
            // Non-fatal: the TV app can still connect by manual IP.
            Log.w(TAG, "NSD register failed (non-fatal): ${e.message}")
        }
    }

    private fun unregisterNsd() {
        val mgr = nsdManager
        val listener = nsdListener
        nsdManager = null
        nsdListener = null
        if (mgr != null && listener != null) {
            try { mgr.unregisterService(listener) } catch (_: Exception) { }
        }
    }

    /**
     * Tiny HTTP server, stdlib only (java.net.ServerSocket).
     * Nested class, not inner: `inner` is illegal inside a
     * standalone object. It reads the streamer's live state via
     * TvStreamer.state / TvStreamer.lanIp for the status page.
     * Serves, on the LAN:
     *  GET /stream.m3u8, live HLS playlist (no-cache).
     *  GET /seg_*.ts, HLS segments.
     *  GET /live.ts, progressive MPEG-TS, chunked, low latency:
     *    the handler registers the client stream and blocks until
     *    the client disconnects; the ingest handler broadcasts
     *    every TS chunk to all registered clients.
     *  PUT/POST /live.ts, TS ingest from guest ffmpeg (body may
     *    be chunked or Content-Length; both are decoded).
     *  GET /, minimal status page.
     * Path traversal is rejected: only basenames inside the
     * segments dir are served (canonical-path check).
     */
    private class HttpServer(
        private val segmentsDir: File,
        private val context: Context
    ) {
        var port: Int = -1
            private set

        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        @Volatile
        private var running = false

        /** Live-TS clients, written by the ingest handler. */
        private val liveClients =
            Collections.synchronizedSet(mutableSetOf<OutputStream>())

        @Volatile
        private var ingestSocket: Socket? = null

        fun start(): Boolean {
            for (p in TvStreamer.HTTP_PORT_START..TvStreamer.HTTP_PORT_END) {
                try {
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress("0.0.0.0", p))
                    serverSocket = ss
                    port = p
                    running = true
                    acceptThread = Thread { acceptLoop() }.apply { isDaemon = true; start() }
                    Log.d(TvStreamer.TAG, "HTTP server on port $port")
                    return true
                } catch (_: Exception) {
                    // Port busy: try the next one.
                }
            }
            return false
        }

        fun stop() {
            running = false
            try { ingestSocket?.close() } catch (_: Exception) { }
            try { serverSocket?.close() } catch (_: Exception) { }
            synchronized(liveClients) {
                liveClients.forEach { try { it.close() } catch (_: Exception) { } }
                liveClients.clear()
            }
            try { acceptThread?.interrupt() } catch (_: Exception) { }
        }

        private fun acceptLoop() {
            while (running) {
                val socket = try {
                    serverSocket?.accept() ?: break
                } catch (_: Exception) {
                    break
                }
                Thread { handle(socket) }.apply { isDaemon = true; start() }
            }
        }

        private fun handle(socket: Socket) {
            try {
                socket.soTimeout = 30000
                val input = socket.getInputStream().buffered()
                val output = socket.getOutputStream().buffered()
                val requestLine = readAsciiLine(input) ?: return
                val parts = requestLine.trim().split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1].substringBefore("?")
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }
                when {
                    (method == "PUT" || method == "POST") && rawPath == "/live.ts" -> {
                        ingestSocket = socket
                        handleIngest(input, headers)
                        // Keep the socket open until ffmpeg closes it;
                        // the ingest reader returns only on EOF/error.
                        try { socket.close() } catch (_: Exception) { }
                    }
                    method == "GET" && rawPath == "/live.ts" -> {
                        handleLiveClient(socket, output)
                    }
                    method == "GET" && rawPath == "/stream.m3u8" -> {
                        serveFile(output, File(segmentsDir, "stream.m3u8"),
                            "application/vnd.apple.mpegurl", noCache = true)
                    }
                    method == "GET" && rawPath.endsWith(".ts") -> {
                        val name = rawPath.substringAfterLast("/")
                        if (!name.matches(Regex("[A-Za-z0-9._-]+"))) {
                            sendStatus(output, 400, "bad segment name")
                        } else {
                            serveFile(output, File(segmentsDir, name),
                                "video/MP2T", noCache = false)
                        }
                    }
                    method == "GET" && rawPath == "/" -> {
                        val body = statusPage().toByteArray(Charsets.UTF_8)
                        sendHeaders(output, 200, "text/html; charset=utf-8", body.size.toLong())
                        output.write(body)
                        output.flush()
                    }
                    else -> sendStatus(output, 404, "not found")
                }
            } catch (_: Exception) {
                // Client went away mid-request: nothing to do.
            } finally {
                // Live-client sockets are closed by stop()/broadcast
                // failure, not here; all other sockets close here.
                try {
                    if (ingestSocket !== socket) socket.close()
                } catch (_: Exception) { }
            }
        }

        /** A GET /live.ts client: headers, then wait until it leaves. */
        private fun handleLiveClient(socket: Socket, output: OutputStream) {
            try {
                socket.soTimeout = 0
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/MP2T\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.US_ASCII))
                output.flush()
                liveClients.add(output)
                // Block until broadcast removes this client (write
                // failure) or the server stops. Poll, so stop() can
                // end the wait by closing the stream.
                while (running && liveClients.contains(output)) {
                    Thread.sleep(500)
                }
            } catch (_: Exception) {
                // Disconnect or server stop.
            } finally {
                liveClients.remove(output)
                try { output.close() } catch (_: Exception) { }
                try { socket.close() } catch (_: Exception) { }
            }
        }

        /** Reads the TS ingest body and broadcasts it to live clients. */
        private fun handleIngest(input: InputStream, headers: Map<String, String>) {
            try {
                val chunked = headers["transfer-encoding"]?.contains("chunked") == true
                if (chunked) {
                    while (running) {
                        val sizeLine = readAsciiLine(input) ?: break
                        val size = sizeLine.trim().split(";")[0]
                            .toIntOrNull(16) ?: break
                        if (size == 0) {
                            // Trailing headers until the empty line.
                            while (true) {
                                val t = readAsciiLine(input) ?: break
                                if (t.isEmpty()) break
                            }
                            break
                        }
                        val buf = readExact(input, size) ?: break
                        broadcast(buf, buf.size)
                        // Trailing CRLF after the chunk.
                        readExact(input, 2)
                    }
                } else {
                    val length = headers["content-length"]?.toLongOrNull() ?: -1L
                    val buf = ByteArray(8192)
                    var remaining = length
                    while (running && (length < 0 || remaining > 0)) {
                        val want = if (length < 0) buf.size
                            else minOf(buf.size.toLong(), remaining).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        broadcast(buf, n)
                        if (length >= 0) remaining -= n
                    }
                }
            } catch (_: Exception) {
                // ffmpeg closed the ingest: the broadcast simply ends.
            } finally {
                ingestSocket = null
            }
        }

        private fun broadcast(buf: ByteArray, len: Int) {
            if (len <= 0 || liveClients.isEmpty()) return
            // One chunked frame per broadcast: "<hex-len>\r\n<bytes>\r\n".
            val header = "${len.toString(16)}\r\n".toByteArray(Charsets.US_ASCII)
            val tail = "\r\n".toByteArray(Charsets.US_ASCII)
            val snapshot: List<OutputStream>
            synchronized(liveClients) { snapshot = liveClients.toList() }
            for (client in snapshot) {
                try {
                    client.write(header)
                    client.write(buf, 0, len)
                    client.write(tail)
                    client.flush()
                } catch (_: Exception) {
                    liveClients.remove(client)
                    try { client.close() } catch (_: Exception) { }
                }
            }
        }

        private fun serveFile(
            output: OutputStream,
            file: File,
            contentType: String,
            noCache: Boolean
        ) {
            try {
                // Canonical-path check: the file must live inside the
                // segments dir (rejects ".." traversal).
                val canonical = file.canonicalFile
                if (!canonical.path.startsWith(segmentsDir.canonicalFile.path)) {
                    sendStatus(output, 403, "forbidden")
                    return
                }
                if (!canonical.exists()) {
                    sendStatus(output, 404, "not found yet")
                    return
                }
                val bytes = canonical.readBytes()
                val cache = if (noCache) "Cache-Control: no-cache\r\n" else ""
                sendHeaders(output, 200, contentType, bytes.size.toLong(), cache)
                output.write(bytes)
                output.flush()
            } catch (_: Exception) {
                try { sendStatus(output, 500, "read failed") } catch (_: Exception) { }
            }
        }

        private fun sendHeaders(
            output: OutputStream,
            code: Int,
            contentType: String,
            length: Long,
            extra: String = ""
        ) {
            val reason = if (code == 200) "OK" else "Error"
            val header = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: $length\r\n" +
                extra +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.US_ASCII))
        }

        private fun sendStatus(output: OutputStream, code: Int, text: String) {
            val body = text.toByteArray(Charsets.UTF_8)
            sendHeaders(output, code, "text/plain; charset=utf-8", body.size.toLong())
            output.write(body)
            output.flush()
        }

        private fun statusPage(): String {
            val url = "http://${TvStreamer.lanIp ?: "?"}:$port/stream.m3u8"
            val count = segmentsDir.listFiles()?.count { it.name.endsWith(".ts") } ?: 0
            return "<!DOCTYPE html><html lang=\"ar\" dir=\"rtl\"><head>" +
                "<meta charset=\"utf-8\"><title>" + context.getString(R.string.ui_tvpage_title) + "</title></head><body>" +
                "<h1>" + context.getString(R.string.ui_tvpage_heading) + "</h1>" +
                "<p>" + context.getString(R.string.ui_tvpage_status, TvStreamer.state.name) + "</p>" +
                "<p>" + context.getString(R.string.ui_tvpage_live, "<a href=\"/stream.m3u8\">$url</a>") + "</p>" +
                "<p>" + context.getString(R.string.ui_tvpage_segments, count) + "</p>" +
                "<p>" + context.getString(R.string.ui_tvpage_fast, "<a href=\"/live.ts\">/live.ts</a>") + "</p>" +
                "</body></html>"
        }

        /** Reads one ASCII line (up to LF, strips a trailing CR). */
        private fun readAsciiLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }

        /** Reads exactly [n] bytes, or null on EOF. */
        private fun readExact(input: InputStream, n: Int): ByteArray? {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) return null
                off += r
            }
            return buf
        }
    }
}
