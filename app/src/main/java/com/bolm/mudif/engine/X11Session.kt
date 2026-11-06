package com.bolm.mudif.engine

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.termux.x11.CmdEntryPointService
import java.io.File

/**
 * P5.22 RADICAL REBUILD — Tiny Container's X11 flow, exactly.
 *
 * Tiny (`ContainerMainViewModel`, x11 feature):
 * - `launchXServer(args)`: CmdEntryPointService ACTION_START, env
 *   TMPDIR / XKB_CONFIG_ROOT / TERMUX_X11_DEBUG=1 /
 *   TERMUX_X11_OVERRIDE_PACKAGE. `xserverStarted` flag guards the stop.
 * - `waitForFile(.../.X11-unix/X6)` — 15 s, 500 ms poll, `exists()`
 *   ONLY — then `Global.sendCommand(command)` into the PTY session,
 *   then open the viewer.
 *
 * Mudif adaptations (proven, kept):
 * - Display :6 (TC), args `[":6", "+extension", "MIT-SHM",
 *   "-listen", "tcp"]` — the `-listen tcp` is Mudif's P5.10
 *   workaround: the Unix-socket connect from inside proot is broken
 *   on this x86_64 device, so the guest uses TCP 127.0.0.1:6006
 *   (DISPLAY is set to 127.0.0.1:6 in the boot env, so every guest
 *   command inherits it — no per-command DISPLAY prefix anywhere).
 * - waitForTcpDisplay is kept after the socket wait: the socket file
 *   can exist before the server accepts connections.
 *
 * DELETED in P5.22 (the pipe-session machinery that never worked):
 * launchGuiApp / launchXterm / launchLxqt, isOpenboxRunning /
 * isXtermRunning / isLxqtRunning and their *_PID_FILE / *_STATE_FILE
 * marker files, ensureXtermFonts, and xterm itself. Tiny Container
 * has ZERO xterm references: its desktops ship their own terminals
 * (xfce4-terminal, qterminal). The desktop is launched with ONE
 * backgrounded command into the PTY session — no setsid, no pid
 * file, no launch gate.
 */
object X11Session {

    private const val TAG = "X11Session"
    private const val DISPLAY = ":6"

    @Volatile
    var xserverRunning = false
        private set

    /** Result of the last waitForXSocket call (true/false/null = never waited). */
    @Volatile
    var lastWaitResult: Boolean? = null
        private set

    /**
     * Tiny `launchXServer`: start CmdEntryPointService with ACTION_START.
     * Sets the started flag FIRST (Tiny does the same) so a later stop
     * always pairs with a start.
     */
    fun startXServer(context: Context) {
        val app = context.applicationContext
        val tmpDir = File(app.filesDir, "tmp").apply { mkdirs() }

        // EXACT match to Tiny Container's launchXServer (plus Mudif's
        // proven -listen tcp flag).
        val xkbRoot = File(RootfsManager.rootfsDir(context), "usr/share/X11/xkb")
            .takeIf { it.exists() }?.absolutePath ?: ""

        val envKeys = arrayOf(
            "TMPDIR",
            "XKB_CONFIG_ROOT",
            "TERMUX_X11_DEBUG",
            "TERMUX_X11_OVERRIDE_PACKAGE"
        )
        val envVals = arrayOf(
            tmpDir.absolutePath,
            xkbRoot,
            "1",
            app.packageName
        )

        val intent = Intent(app, CmdEntryPointService::class.java).apply {
            action = CmdEntryPointService.ACTION_START
            putExtra(CmdEntryPointService.EXTRA_ARGS, arrayOf(DISPLAY, "+extension", "MIT-SHM", "-listen", "tcp"))
            putExtra(CmdEntryPointService.EXTRA_ENV_KEYS, envKeys)
            putExtra(CmdEntryPointService.EXTRA_ENV_VALUES, envVals)
        }
        app.startService(intent)
        xserverRunning = true
        Log.d(TAG, "X server start requested for $DISPLAY (+TCP)")
    }

    /**
     * Tiny `killXServer`: ACTION_STOP only if the server was started.
     * Sending ACTION_STOP without a start would spawn a stray :xserver
     * process — that is why the guard exists.
     */
    fun stopXServer(context: Context) {
        if (!xserverRunning) return
        val app = context.applicationContext
        app.startService(Intent(app, CmdEntryPointService::class.java).apply {
            action = CmdEntryPointService.ACTION_STOP
        })
        xserverRunning = false
    }

    /**
     * Tiny `waitForFile`: poll `exists()` every 500 ms, 15 s timeout.
     * No connection test, no extra sleep — Tiny waits ONLY for the file.
     */
    fun waitForXSocket(context: Context, timeoutMs: Long = 15000): Boolean {
        val socket = File(context.filesDir, "tmp/.X11-unix/X6")
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (socket.exists()) {
                Log.d(TAG, "X socket ready")
                lastWaitResult = true
                return true
            }
            Thread.sleep(500)
        }
        Log.e(TAG, "X socket not ready after ${timeoutMs}ms")
        lastWaitResult = false
        return false
    }

    /** True if the X socket file exists right now (diagnostics). */
    fun isXSocketPresent(context: Context): Boolean =
        File(context.filesDir, "tmp/.X11-unix/X6").exists()

    /** TCP port of display :6 (6000 + display number). */
    const val TCP_PORT = 6006

    /** Single TCP probe — is anything accepting on 127.0.0.1:6006? */
    fun isTcpDisplayOpen(): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", TCP_PORT), 800)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Wait until the X server accepts TCP connections on
     * 127.0.0.1:6006. The Unix socket file can exist before the server
     * is ready to accept, so the socket check alone is not enough.
     * A connect+immediate-close is harmless to the server.
     */
    fun waitForTcpDisplay(timeoutMs: Long = 10000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (isTcpDisplayOpen()) {
                Log.d(TAG, "TCP display ready")
                return true
            }
            Thread.sleep(500)
        }
        Log.e(TAG, "TCP display not ready after ${timeoutMs}ms")
        return false
    }

    /** Open the Termux:X11 viewer (Tiny: OpenX11 navigation event). */
    fun openViewer(context: Context) {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(intent)
    }

    /**
     * P5.22 — THE desktop launch (Tiny Container's x11 branch): ONE
     * backgrounded command into the PTY session. DISPLAY is already
     * in the boot env (127.0.0.1:6), so no prefix is needed. No
     * setsid, no pid file, no marker file, no launch gate — the
     * desktop is a background job of the container shell, exactly as
     * in Tiny Container (`startxfce4 > /dev/null 2>&1 &`).
     * [desktop] is "lxqt" (startlxqt) or "xfce" (startxfce4).
     */
    fun launchDesktop(context: Context, desktop: String): Boolean {
        val cmd = if (desktop == "xfce") "startxfce4" else "startlxqt"
        val ok = GuestSession.sendCommand("$cmd > /root/$desktop.log 2>&1 &")
        if (ok) Log.d(TAG, "$desktop command sent through PTY session")
        else Log.e(TAG, "PTY session not available for $desktop")
        return ok
    }

    /** Last 30 lines of the desktop session log (diagnostics only). */
    fun getDesktopLog(context: Context, desktop: String): String {
        return try {
            val file = File(RootfsManager.rootfsDir(context), "root/$desktop.log")
            if (!file.exists()) return "($desktop.log not found - $desktop never started)"
            val lines = file.readLines()
            if (lines.isEmpty()) return "($desktop.log empty)"
            lines.takeLast(30).joinToString("\n")
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }

    /**
     * Install xkeyboard-config in the guest so the X server can find
     * its keyboard data (XKB_CONFIG_ROOT = <rootfs>/usr/share/X11/xkb).
     * One-shot command (TerminalSession, best-effort, idempotent).
     * Called BEFORE startXServer so the env var picks it up.
     */
    fun ensureXkb(context: Context): Boolean {
        val result = TerminalSession.runCommand(
            context,
            "apk add --no-cache xkeyboard-config",
            idleTimeoutSec = 60,
            maxTimeoutSec = 300
        )
        Log.d(TAG, "ensureXkb exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /**
     * Install openbox in the guest (the window manager LXQt uses by
     * default). One-shot, best-effort, idempotent. xterm is GONE in
     * P5.22 (Tiny Container has none; its pty allocation fails under
     * proot on this device even with /proc bound).
     */
    fun ensureDesktopPackages(context: Context): Boolean {
        val result = TerminalSession.runCommand(
            context,
            "apk add --no-cache openbox",
            idleTimeoutSec = 60,
            maxTimeoutSec = 300
        )
        Log.d(TAG, "ensureDesktopPackages exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /**
     * Install firefox + libreoffice in the guest (full acceptance).
     * LARGE downloads: generous timeouts; the first run downloads
     * them once, later runs are a fast no-op (already installed).
     * One-shot, best-effort, idempotent.
     */
    fun ensureDesktopApps(context: Context): Boolean {
        val result = TerminalSession.runCommand(
            context,
            "apk add --no-cache firefox libreoffice",
            idleTimeoutSec = 120,
            maxTimeoutSec = 1200
        )
        Log.d(TAG, "ensureDesktopApps exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /**
     * Install the LXQt desktop core in the guest + qterminal (LXQt's
     * own terminal, the Tiny Container way — the desktop ships its
     * terminal, the app launches none). NOTE: there is NO `lxqt`
     * meta-package in Alpine 3.24, so the core components are listed
     * explicitly (29 packages, LXQt 2.4.0). One-shot, best-effort,
     * idempotent.
     */
    fun ensureLxqt(context: Context): Boolean {
        val result = TerminalSession.runCommand(
            context,
            "apk add --no-cache lxqt-session lxqt-panel pcmanfm-qt lxqt-config lxqt-runner lxqt-notificationd lxqt-themes qterminal",
            idleTimeoutSec = 120,
            maxTimeoutSec = 900
        )
        Log.d(TAG, "ensureLxqt exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /**
     * Install the XFCE desktop in the guest (xfce4 4.20-r0, 83
     * packages) + xfce4-terminal (XFCE's own terminal). LARGE
     * download: call from a background thread, never blocking the
     * desktop. One-shot, best-effort, idempotent.
     */
    fun ensureXfce(context: Context): Boolean {
        val result = TerminalSession.runCommand(
            context,
            "apk add --no-cache xfce4 xfce4-terminal",
            idleTimeoutSec = 120,
            maxTimeoutSec = 1200
        )
        Log.d(TAG, "ensureXfce exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }
}
