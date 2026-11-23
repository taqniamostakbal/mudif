package com.bolm.mudif.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * F8 half B: web remote control — any device's browser on the same
 * Wi-Fi sees and fully controls the Mudif desktop.
 *
 * Chain (p119 design, do not redesign): browser → websockify
 * (guest, 0.0.0.0:6080, serves the custom Mudif page + bridges
 * WebSocket→RFB) → x11vnc (guest, 127.0.0.1:5900, -rfbauth
 * per-session password) → Termux:X11 :6 (TCP 127.0.0.1:6006).
 * LAN-only, plain ws:// in v1 (honest risk in the activity),
 * per-session random 8-char password (VNC DES limit), QR encodes
 * the address only, never the password.
 *
 * Rig-proven facts baked in here (22 Nov):
 * - x11vnc against :6 REQUIRES -noshm: plain x11vnc dies with
 *   "X Error BadAccess, Major opcode 130 (MIT-SHM), Minor opcode
 *   1 (X_ShmAttach)". The service always passes -noshm.
 * - Guest apt install of websockify + novnc leaves the packages
 *   in state iU (unpacked, not configured) on the RIG ONLY: the
 *   python postinsts spawn /usr/bin/dpkg via posix_spawn, which
 *   fails with ENOEXEC under qemu-user-static. The files are all
 *   present and websockify runs. So install success is judged by
 *   FILE PRESENCE + a working binary, never by dpkg exit code or
 *   `dpkg -l` status. On a real ARM64 phone the configure step
 *   completes normally.
 * - Install (update + install + clean) measured 15 s on the rig,
 *   disk 1.2 GB free after clean: the design's D5 falsifier
 *   (120 s / 500 MB) passes with the apt path, no asset baking.
 *
 * All start/stop/install calls must run on a BACKGROUND thread
 * (they sleep and run one-shot guest commands). State is polled
 * by RemoteControlActivity on a 2 s ticker (same pattern as the
 * F4 HUD ticker).
 */
object RemoteControlService {

    private const val TAG = "RemoteControl"

    /** First / last websockify port tried (6080, then 6081..6089). */
    private const val PORT_START = 6080
    private const val PORT_END = 6089

    private const val NSD_SERVICE_TYPE = "_mudifremote._tcp."
    private const val NSD_SERVICE_NAME = "Mudif"

    private const val X11VNC_PID = "/tmp/mudif-remote-x11vnc.pid"
    private const val WEBSOCKIFY_PID = "/tmp/mudif-remote-websockify.pid"
    private const val PASSWD_FILE = "/tmp/mudif-remote-passwd"
    private const val WEB_ROOT = "/tmp/mudif-remote-web"
    private const val READY_MARKER = "mudif-remote-ready"
    private const val CLIENTS_FILE = "mudif-remote-clients"

    private const val PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    @Volatile var state: State = State.STOPPED
        private set

    @Volatile var lastError: String? = null
        private set

    /** The per-session password (memory + prefs, cleared on stop, never logged). */
    @Volatile var password: String? = null
        private set

    /** The websockify port actually in use (6080 unless a conflict moved it). */
    @Volatile var port: Int = PORT_START
        private set

    /** The LAN IP the address is built from (recomputed on Wi-Fi change). */
    @Volatile var lanIp: String? = null
        private set

    fun address(): String? {
        val ip = lanIp ?: return null
        return "http://$ip:$port/"
    }

    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Pure: a random 8-char password from [A-Za-z0-9] (VNC DES limit). */
    fun generatePassword(random: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder(8)
        repeat(8) { sb.append(PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)]) }
        return sb.toString()
    }

    /** Pure: true when [pw] is exactly the 8-char session-password shape. */
    fun isValidPassword(pw: String?): Boolean {
        if (pw == null || pw.length != 8) return false
        return pw.all { it in PASSWORD_CHARS }
    }

    private fun tmpDir(context: Context): File = File(context.filesDir, "tmp")

    private fun prefs(context: Context) =
        context.getSharedPreferences("mudif_remote", Context.MODE_PRIVATE)

    /**
     * R6 self-heal, as a testable seam. The in-memory RUNNING flag
     * can outlive the guest processes (the rig killed them once
     * while the flag stayed RUNNING, 22 Nov). When the port is
     * dead the flag is stale: reset it and start fresh instead
     * of claiming a live session that is not there. Returns true
     * when it reset a stale session.
     */
    fun healStaleRunning(portLive: Boolean): Boolean {
        if (state == State.RUNNING && !portLive) {
            state = State.STOPPED
            password = null
            lanIp = null
            return true
        }
        return false
    }

    /**
     * Start the remote session: install the guest packages on first
     * enable, write the password file, stage the web root, start
     * x11vnc + websockify, register NSD. Returns true when the
     * address is live. Background thread only.
     */
    @Synchronized
    fun start(context: Context): Boolean {
        healStaleRunning(guestPortListening(context, port))
        if (state == State.RUNNING) return true
        state = State.STARTING
        lastError = null
        val app = context.applicationContext

        // The desktop must really be up (TCP :6 open) and the
        // guest PTY session alive (so sendCommand works). Do NOT
        // gate on GuestSession.booted: it means "this process
        // booted the desktop", so it is false after an app restart
        // even though :6 is up and the viewer shows it — the
        // service then refuses to start for a desktop that is
        // running (rig-proven 22 Nov). `alive` is the real
        // session signal.
        if (!X11Session.isTcpDisplayOpen() || !GuestSession.alive) {
            lastError = app.getString(com.bolm.mudif.R.string.ui_vnc_desktop_not_ready)
            state = State.ERROR
            return false
        }
        val ip = TvStreamer.lanIpAddress()
        if (ip == null) {
            lastError = app.getString(com.bolm.mudif.R.string.ui_tv_err_no_ip)
            state = State.ERROR
            return false
        }
        lanIp = ip

        // Fresh password every start; the old one dies with the stop.
        val pw = generatePassword()
        password = pw
        prefs(app).edit().putString("password", pw).apply()

        if (!ensureGuestPackages(app)) {
            state = State.ERROR
            return false
        }
        if (!stageWebRoot(app)) {
            lastError = "web root staging failed"
            state = State.ERROR
            return false
        }
        if (!startX11vnc(app, pw)) {
            state = State.ERROR
            return false
        }
        if (!startWebsockify(app)) {
            stopGuestProcesses(app)
            state = State.ERROR
            return false
        }
        registerNsd(app)
        registerNetworkCallback(app)
        state = State.RUNNING
        return true
    }

    /**
     * Stop everything: kill both guest processes, delete the
     * password file, unregister NSD, clear the session password.
     * Safe to call when already stopped. Background thread only.
     */
    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        stopGuestProcesses(app)
        unregisterNsd()
        unregisterNetworkCallback(app)
        // The password file lives in the app filesDir/tmp (guest
        // /tmp is a bind mount of it), so delete it directly: a
        // guest-shell rm depends on the PTY session being alive,
        // and a dead session silently leaves the file behind
        // (rig-proven 22 Nov: stop() left the file, mtime 22:59).
        try {
            File(tmpDir(app), "mudif-remote-passwd").delete()
        } catch (_: Exception) {
        }
        try {
            GuestSession.sendCommand("rm -f $PASSWD_FILE 2>/dev/null")
        } catch (_: Exception) {
        }
        password = null
        prefs(app).edit().remove("password").apply()
        lanIp = null
        state = State.STOPPED
        lastError = null
    }

    /**
     * Disconnect every connected browser without stopping the
     * session: websockify is restarted on the same port (x11vnc
     * keeps running, the password is unchanged). Background thread.
     */
    @Synchronized
    fun disconnectAll(context: Context): Boolean {
        if (state != State.RUNNING) return false
        val app = context.applicationContext
        killPidFile(app, WEBSOCKIFY_PID)
        Thread.sleep(500)
        return startWebsockify(app)
    }

    /** Live client count: established TCP connections to the websockify port. */
    fun clientCount(context: Context): Int {
        val app = context.applicationContext
        val marker = File(tmpDir(app), CLIENTS_FILE)
        try { marker.delete() } catch (_: Exception) { }
        // /proc/net/tcp, not ss: the guest has no ss (iproute2 is
        // not installed) and ss always fails there. Local port is
        // field 2, state is field 4 (01 = ESTABLISHED).
        val hex = "%04X".format(port)
        if (!GuestSession.sendCommand(
                "awk '\$2 ~ /:$hex\$/ && \$4 == \"01\" {n++} END {print n+0}' /proc/net/tcp " +
                    "> /tmp/$CLIENTS_FILE 2>/dev/null || echo 0 > /tmp/$CLIENTS_FILE"
            )
        ) return 0
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            try {
                if (marker.exists()) {
                    return marker.readText().trim().toIntOrNull() ?: 0
                }
            } catch (_: Exception) { }
            Thread.sleep(200)
        }
        return 0
    }

    /**
     * Recompute the LAN IP (Wi-Fi change): the websockify bind is
     * 0.0.0.0 so the service itself never restarts, only the
     * displayed address + QR + NSD update. Returns the new address
     * or null when there is no LAN IP. Background thread only.
     */
    @Synchronized
    fun refreshAddress(context: Context): String? {
        if (state != State.RUNNING) return address()
        val app = context.applicationContext
        val ip = TvStreamer.lanIpAddress() ?: return null
        if (ip == lanIp) return address()
        lanIp = ip
        unregisterNsd()
        registerNsd(app)
        return address()
    }

    // ---- guest packages (first enable only) ----

    private fun guestReadyMarker(context: Context): File = File(tmpDir(context), READY_MARKER)

    private fun ensureGuestPackages(context: Context): Boolean {
        val marker = guestReadyMarker(context)
        try { marker.delete() } catch (_: Exception) { }
        GuestSession.sendCommand(
            "test -x /usr/bin/websockify && test -f /usr/share/novnc/core/rfb.js " +
                "&& echo OK > /tmp/$READY_MARKER || echo MISSING > /tmp/$READY_MARKER"
        )
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            try {
                if (marker.exists()) {
                    if (marker.readText().trim() == "OK") return true
                    break
                }
            } catch (_: Exception) { }
            Thread.sleep(200)
        }
        // First enable: install, then clean (reclaims the apt lists).
        // Success is judged by file presence afterwards, never by
        // apt/dpkg exit codes (rig qemu caveat, see the file header).
        Log.i(TAG, "remote: first enable, installing websockify + novnc")
        val started = System.currentTimeMillis()
        GuestSession.sendCommand(
            "apt-get update -qq && apt-get install -y -qq websockify novnc " +
                "> /tmp/mudif-remote-install.log 2>&1; " +
                "apt-get clean; rm -rf /var/lib/apt/lists/*; " +
                "test -x /usr/bin/websockify && test -f /usr/share/novnc/core/rfb.js " +
                "&& echo OK > /tmp/$READY_MARKER || echo FAIL > /tmp/$READY_MARKER"
        )
        val installDeadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < installDeadline) {
            try {
                if (marker.exists()) {
                    val ok = marker.readText().trim() == "OK"
                    if (!ok) lastError = "guest install failed (see /tmp/mudif-remote-install.log)"
                    Log.i(TAG, "remote: install took ${System.currentTimeMillis() - started} ms, ok=$ok")
                    return ok
                }
            } catch (_: Exception) { }
            Thread.sleep(500)
        }
        lastError = "guest install timed out after 120 s"
        return false
    }

    // ---- web root staging ----

    /**
     * The web root lives in the guest /tmp (= the app filesDir/tmp
     * bind mount, so the app writes it directly): the custom Mudif
     * page (index.html + CSS + JS from assets), the Cairo font
     * (from the app asset), the penguin logo (from the launcher
     * icon). The guest copies noVNC core + vendor in on start
     * (startWebsockify); the stock vnc.html is never copied, so
     * the stock UI is not reachable at any URL.
     */
    private fun stageWebRoot(context: Context): Boolean {
        return try {
            val root = File(tmpDir(context), "mudif-remote-web")
            if (!root.exists()) root.mkdirs()
            copyAsset(context, "remote-web/index.html", File(root, "index.html"))
            copyAsset(context, "remote-web/app.css", File(root, "app.css"))
            copyAsset(context, "remote-web/app.js", File(root, "app.js"))
            copyAsset(context, "fonts-cairo/Cairo[slnt,wght].ttf", File(root, "Cairo.ttf"))
            // Penguin logo: the launcher icon, rendered to a PNG.
            // The launcher icon is an adaptive icon, not a bitmap,
            // so draw any drawable onto a canvas (a BitmapDrawable
            // cast alone silently skips it and the login page shows
            // a broken logo).
            try {
                val drawable = context.getDrawable(com.bolm.mudif.R.mipmap.ic_launcher)
                if (drawable != null) {
                    val size = 192
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        size, size, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(canvas)
                    File(root, "penguin.png").outputStream().use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "penguin staging failed (non-fatal): ${e.message}")
            }
            File(root, "index.html").exists() && File(root, "app.js").exists()
        } catch (e: Exception) {
            Log.e(TAG, "stageWebRoot failed: ${e.message}")
            false
        }
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyAsset $assetPath failed: ${e.message}")
            false
        }
    }

    // ---- guest processes ----

    private fun startX11vnc(context: Context, pw: String): Boolean {
        // The password reaches x11vnc through a file the app writes
        // (filesDir/tmp = guest /tmp), never on a command line or in
        // the shell history: the guest command only cats the file.
        // -noshm is mandatory on :6 (MIT-SHM attach fails, rig-proven).
        try {
            File(tmpDir(context), "mudif-remote-passwd-plain").writeText(pw)
        } catch (e: Exception) {
            lastError = "could not write the password file: ${e.message}"
            return false
        }
        GuestSession.sendCommand(
            "x11vnc -storepasswd \$(cat /tmp/mudif-remote-passwd-plain) $PASSWD_FILE >/dev/null 2>&1; " +
                "rm -f /tmp/mudif-remote-passwd-plain; " +
                "chmod 600 $PASSWD_FILE; " +
                "x11vnc -display 127.0.0.1:6 -forever -shared " +
                "-rfbauth $PASSWD_FILE -listen localhost -rfbport 5900 " +
                "-quiet -noshm > /tmp/mudif-remote-x11vnc.log 2>&1 & " +
                "echo \$! > $X11VNC_PID"
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (guestPortListening(context, 5900)) return true
            Thread.sleep(500)
        }
        lastError = "x11vnc did not listen on 5900 (see /tmp/mudif-remote-x11vnc.log)"
        return false
    }

    private fun startWebsockify(context: Context): Boolean {
        // Pick the first free port (pre-bind test, TvStreamer pattern).
        var chosen = PORT_START
        while (chosen <= PORT_END) {
            try {
                ServerSocket(chosen).use { /* free */ }
                break
            } catch (_: Exception) {
                chosen++
            }
        }
        if (chosen > PORT_END) {
            lastError = "no free port 6080..6089"
            return false
        }
        port = chosen
        GuestSession.sendCommand(
            "mkdir -p $WEB_ROOT && cp -r /usr/share/novnc/core /usr/share/novnc/vendor $WEB_ROOT/ 2>/dev/null; " +
                "websockify --web $WEB_ROOT 0.0.0.0:$port 127.0.0.1:5900 " +
                "> /tmp/mudif-remote-websockify.log 2>&1 & " +
                "echo \$! > $WEBSOCKIFY_PID"
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (guestPortListening(context, port)) return true
            Thread.sleep(500)
        }
        lastError = "websockify did not listen on $port (see /tmp/mudif-remote-websockify.log)"
        return false
    }

    private fun guestPortListening(context: Context, port: Int): Boolean {
        val marker = File(tmpDir(context), "mudif-remote-port-$port")
        return try {
            marker.delete()
            // /proc/net/tcp, not ss: the guest has no ss (iproute2
            // is not installed), so an ss check always fails and
            // the start falsely reports the port dead (rig-proven
            // 22 Nov: x11vnc was up on 5900 while ss was missing).
            // A LISTEN socket has remote 00000000:0000, state 0A.
            val hex = "%04X".format(port)
            GuestSession.sendCommand(
                "grep -qi \":$hex 00000000:0000 0A\" /proc/net/tcp && echo OK > /tmp/mudif-remote-port-$port"
            )
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                if (marker.exists()) return true
                Thread.sleep(200)
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun stopGuestProcesses(context: Context) {
        killPidFile(context, X11VNC_PID)
        killPidFile(context, WEBSOCKIFY_PID)
    }

    private fun killPidFile(context: Context, pidFile: String) {
        GuestSession.sendCommand(
            "kill \$(cat $pidFile 2>/dev/null) 2>/dev/null; " +
                "sleep 1; kill -9 \$(cat $pidFile 2>/dev/null) 2>/dev/null; " +
                "rm -f $pidFile"
        )
    }

    // ---- NSD + network ----

    private fun registerNsd(context: Context) {
        try {
            val mgr = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = NSD_SERVICE_NAME
                serviceType = NSD_SERVICE_TYPE
                port = this@RemoteControlService.port
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    MudifLog.d(TAG, "NSD registered: ${info.serviceName} :${info.port}")
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

    private fun registerNetworkCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Thread { refreshAddress(context.applicationContext) }.start()
                }
                override fun onLost(network: Network) {
                    Thread { refreshAddress(context.applicationContext) }.start()
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "network callback failed (non-fatal): ${e.message}")
        }
    }

    private fun unregisterNetworkCallback(context: Context) {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) { }
    }
}
