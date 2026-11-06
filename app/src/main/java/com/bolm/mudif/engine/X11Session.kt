package com.bolm.mudif.engine

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.termux.x11.CmdEntryPointService
import java.io.File

/**
 * P5.8 REBUILD — native X11 via Termux:X11, Tiny Container's flow exactly.
 *
 * Tiny Container (`ContainerMainViewModel.kt`):
 * - `launchXServer(args)`: CmdEntryPointService ACTION_START, args [":0"],
 *   env TMPDIR / XKB_CONFIG_ROOT / TERMUX_X11_DEBUG=1 /
 *   TERMUX_X11_OVERRIDE_PACKAGE. `xserverStarted` flag guards the stop.
 * - x11 feature: launch X in a coroutine, `waitForFile(...X0)` (15 s,
 *   500 ms poll, `exists()` ONLY), then `Global.sendCommand(command)`
 *   into the persistent PTY session, then open the viewer.
 * - `killXServer()`: ACTION_STOP only if started; called from
 *   `exitContainer()` and `onCleared()`.
 *
 * What changed vs P5.5/P5.7 (the patch loop):
 * - openbox is NO LONGER launched as a direct `ProcessBuilder` proot
 *   invocation. It is sent through the persistent PTY container session
 *   (`ContainerSession.sendCommand`), exactly like Tiny — one session
 *   owns the container, and the GUI app runs inside it.
 * - `waitForXSocket()` no longer sleeps 2 s after the socket appears
 *   (Tiny does not do that).
 * - `stopXServer()` is guarded by the started flag (Tiny's killXServer).
 * - `isOpenboxRunning()` no longer reads a `Process` handle (there is
 *   none anymore). It asks the guest shell via the PTY and reads the
 *   answer from a marker file on the shared /tmp bind-mount.
 */
object X11Session {

    private const val TAG = "X11Session"
    private const val DISPLAY = ":0"
    private const val OPENBOX_PID_FILE = ".mudif_openbox_pid"
    private const val OPENBOX_STATE_FILE = ".mudif_openbox_state"
    private const val XTERM_PID_FILE = ".mudif_xterm_pid"
    private const val XTERM_STATE_FILE = ".mudif_xterm_state"

    @Volatile
    var xserverRunning = false
        private set

    /** P5.9: result of the last waitForXSocket call (true/false/null = never waited). */
    @Volatile
    var lastWaitResult: Boolean? = null
        private set

    /**
     * Tiny `launchXServer`: start CmdEntryPointService with ACTION_START.
     * Sets the started flag FIRST (Tiny does the same) so a later stop
     * always pairs with a start.
     *
     * P5.10: the Unix socket connect from inside proot is broken on this
     * device (guest sees X0, lock is live, server is up, yet every X
     * client fails XOpenDisplay — proven by the TMPDIR test and the
     * xterm test). So the server ALSO listens on TCP (`-listen tcp`,
     * additive: the Unix socket stays) and the guest uses TCP
     * (DISPLAY=127.0.0.1:0), which proot does not translate at all.
     * NOTE: TCP :6000 is unauthenticated and bound on all interfaces;
     * on a home WiFi this is acceptable for a debug desktop, but it is
     * not a setup to run on a public network.
     */
    fun startXServer(context: Context) {
        val app = context.applicationContext
        val tmpDir = File(app.filesDir, "tmp").apply { mkdirs() }

        // EXACT match to Tiny Container's launchXServer.
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
            putExtra(CmdEntryPointService.EXTRA_ARGS, arrayOf(DISPLAY, "-listen", "tcp"))
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
        val socket = File(context.filesDir, "tmp/.X11-unix/X0")
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
        File(context.filesDir, "tmp/.X11-unix/X0").exists()

    /** P5.10: TCP port of display :0 (6000 + display number). */
    const val TCP_PORT = 6000

    /** P5.10: single TCP probe — is anything accepting on 127.0.0.1:6000? */
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
     * P5.10: wait until the X server accepts TCP connections on
     * 127.0.0.1:6000. The Unix socket file can exist before the server
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

    /**
     * Tiny's GUI step: send the GUI command into the persistent PTY
     * container session. openbox runs backgrounded (`&`) with its output
     * redirected to /root/openbox.log, and its pid recorded so the
     * running-state check below can test it.
     *
     * P5.10: DISPLAY=127.0.0.1:0 (TCP) instead of :0 (Unix socket) —
     * the Unix socket connect from inside proot fails on this device.
     */
    fun launchGuiApp(context: Context): Boolean {
        val ok = ContainerSession.sendCommand(
            "DISPLAY=127.0.0.1:0 openbox > /root/openbox.log 2>&1 & echo \$! > /tmp/$OPENBOX_PID_FILE"
        )
        if (ok) Log.d(TAG, "openbox command sent through PTY session")
        else Log.e(TAG, "PTY session not available for openbox")
        return ok
    }

    /**
     * Is openbox alive? Asks the guest shell (through the PTY) to test
     * the recorded pid with `kill -0` and write RUNNING/DEAD to a marker
     * file on the shared /tmp bind-mount, then reads that file from the
     * Android side. Returns false on any doubt.
     */
    fun isOpenboxRunning(context: Context, timeoutMs: Long = 5000): Boolean {
        val app = context.applicationContext
        val stateFile = File(app.filesDir, "tmp/$OPENBOX_STATE_FILE")
        try {
            stateFile.delete()
        } catch (_: Exception) {
        }
        if (!ContainerSession.sendCommand(
                "kill -0 \$(cat /tmp/$OPENBOX_PID_FILE 2>/dev/null) 2>/dev/null" +
                    " && echo RUNNING > /tmp/$OPENBOX_STATE_FILE" +
                    " || echo DEAD > /tmp/$OPENBOX_STATE_FILE"
            )
        ) {
            return false
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                if (stateFile.exists()) {
                    return stateFile.readText().trim() == "RUNNING"
                }
            } catch (_: Exception) {
            }
            Thread.sleep(200)
        }
        return false
    }

    /** Open the Termux:X11 viewer (Tiny: OpenX11 navigation event). */
    fun openViewer(context: Context) {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(intent)
    }

    fun getOpenboxLog(context: Context): String {
        return try {
            val file = File(RootfsManager.rootfsDir(context), "root/openbox.log")
            if (!file.exists()) return "(openbox.log not found - openbox never started)"
            val lines = file.readLines()
            if (lines.isEmpty()) return "(openbox.log empty)"
            lines.takeLast(30).joinToString("\n")
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }

    /**
     * P5.13 — xterm MUST be launched through the persistent PTY
     * container session, exactly like openbox. The Terminal tab
     * (`TerminalSession.runCommand`) is a one-shot proot per command:
     * xterm starts there with no error, but it DIES the moment the
     * command returns (proven 2026-11-06 04:04: `ps | grep xterm`
     * in the terminal returned "(لا مخرجات)" — no xterm process).
     * The persistent session is a real terminal where `&` jobs
     * survive, so xterm launched here stays alive and its window
     * can appear in the viewer.
     *
     * P5.14 — two failures revealed by the P5.13 xterm.log:
     *  1. `cannot load font "-misc-fixed-..."`: Termux:X11 has no
     *     misc-fixed core font. Fix: fontconfig (`-fa`) with a TTF
     *     font installed in the guest (DejaVu Sans Mono) — rendered
     *     client-side, bypasses the X server's core fonts entirely.
     *  2. `open ttydev: No such file or directory`: xterm cannot
     *     open its pty slave when launched from the PTY session's
     *     shell (controlling terminal). Fix: `setsid -w` gives it a
     *     fresh session with no controlling terminal, so it can
     *     allocate its own pty cleanly.
     */
    fun launchXterm(context: Context): Boolean {
        val ok = ContainerSession.sendCommand(
            "DISPLAY=127.0.0.1:0 setsid -w xterm -fa \"DejaVu Sans Mono\" -fs 12 > /root/xterm.log 2>&1 & echo \$! > /tmp/$XTERM_PID_FILE"
        )
        if (ok) Log.d(TAG, "xterm command sent through PTY session")
        else Log.e(TAG, "PTY session not available for xterm")
        return ok
    }

    /**
     * P5.14 — install fontconfig + a TTF font in the guest so the
     * `-fa` (fontconfig) xterm launch works. One-shot command, so
     * the TerminalSession is the right tool (it does not need to
     * survive). Best-effort: returns false on failure, and the
     * caller still tries to launch xterm (the log will show why).
     * Idempotent — a second run is a fast no-op if installed.
     */
    fun ensureXtermFonts(context: Context): Boolean {
        val result = TerminalSession.runCommand(
            context,
            "apk add --no-cache fontconfig ttf-dejavu",
            idleTimeoutSec = 60,
            maxTimeoutSec = 300
        )
        Log.d(TAG, "ensureXtermFonts exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /** Is xterm alive? Same marker-file pattern as isOpenboxRunning. */
    fun isXtermRunning(context: Context, timeoutMs: Long = 5000): Boolean {
        val app = context.applicationContext
        val stateFile = File(app.filesDir, "tmp/$XTERM_STATE_FILE")
        try {
            stateFile.delete()
        } catch (_: Exception) {
        }
        if (!ContainerSession.sendCommand(
                "kill -0 \$(cat /tmp/$XTERM_PID_FILE 2>/dev/null) 2>/dev/null" +
                    " && echo RUNNING > /tmp/$XTERM_STATE_FILE" +
                    " || echo DEAD > /tmp/$XTERM_STATE_FILE"
            )
        ) {
            return false
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                if (stateFile.exists()) {
                    return stateFile.readText().trim() == "RUNNING"
                }
            } catch (_: Exception) {
            }
            Thread.sleep(200)
        }
        return false
    }

    fun getXtermLog(context: Context): String {
        return try {
            val file = File(RootfsManager.rootfsDir(context), "root/xterm.log")
            if (!file.exists()) return "(xterm.log not found - xterm never started)"
            val lines = file.readLines()
            if (lines.isEmpty()) return "(xterm.log empty)"
            lines.takeLast(30).joinToString("\n")
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }
}
