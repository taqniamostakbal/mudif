package com.bolm.mudif.engine

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.bolm.mudif.ui.settings.ViewerPrefs
import com.termux.x11.CmdEntryPointService
import java.io.File
import kotlin.math.roundToInt

/**
 * P5.22 RADICAL REBUILD, Tiny Container's X11 flow, exactly.
 *
 * Tiny (`ContainerMainViewModel`, x11 feature):
 * - `launchXServer(args)`: CmdEntryPointService ACTION_START, env
 *   TMPDIR / XKB_CONFIG_ROOT / TERMUX_X11_DEBUG=1 /
 *   TERMUX_X11_OVERRIDE_PACKAGE. `xserverStarted` flag guards the stop.
 * - `waitForFile(.../.X11-unix/X6)`, 15 s, 500 ms poll, `exists()`
 *   ONLY, then `Global.sendCommand(command)` into the PTY session,
 *   then open the viewer.
 *
 * Mudif adaptations (proven, kept):
 * - Display :6 (TC), args `[":6", "+extension", "MIT-SHM",
 *   "-listen", "tcp", "-disable-dri3"]`, the `-listen tcp` is
 *   Mudif's P5.10 workaround: the Unix-socket connect from inside
 *   proot is broken on this x86_64 device, so the guest uses TCP
 *   127.0.0.1:6006 (DISPLAY is set to 127.0.0.1:6 in the boot env,
 *   so every guest command inherits it, no per-command DISPLAY
 *   prefix anywhere). The `-disable-dri3` is p80, UNPROVEN until
 *   the rig A/B passes: opcode 147 IS DRI3 on this server (p79
 *   rig round, xdpyinfo on Lorie X.Org 21.1.99) and all three
 *   env-matrix combos panic identically, so the flag targets the
 *   right extension, but the flag itself was never tested; if
 *   the A/B fails (Craft still panics, or Firefox/LXImage/Ollama
 *   stop launching) it reverts in p81. Lorie documents the flag
 *   as "disabling DRI3 support (to let lavapipe work)".
 * - waitForTcpDisplay is kept after the socket wait: the socket file
 *   can exist before the server accepts connections.
 *
 * DELETED in P5.22 (the pipe-session machinery that never worked):
 * launchGuiApp / launchXterm / launchLxqt, isOpenboxRunning /
 * isXtermRunning / isLxqtRunning and their *_PID_FILE / *_STATE_FILE
 * marker files, ensureXtermFonts, and xterm itself. Tiny Container
 * has ZERO xterm references: its desktops ship their own terminals
 * (xfce4-terminal, qterminal). The desktop is launched with ONE
 * backgrounded command into the PTY session, no setsid, no pid
 * file, no launch gate.
 */
object X11Session {

    private const val TAG = "X11Session"
    private const val DISPLAY = ":6"

    /**
     * Phase 1e, adaptive since p23: the phone desktop height comes
     * from DesktopScale (short side / 2, clamped 480..720), never a
     * fixed number. A phone screen is small and dense, so a 720-high
     * desktop draws its panel, icons and text physically tiny. At
     * 540 the same UI is ~1.33x bigger and readable/tappable. The TV
     * path (openViewerOnDisplay) keeps 720: a TV is big, so 720 is
     * fine there.
     */
    @Volatile
    var xserverRunning = false
        private set

    /** Result of the last waitForXSocket call (true/false/null = never waited). */
    @Volatile
    var lastWaitResult: Boolean? = null
        private set

    /** P16.5: result of the last ensureDebianDesktop install attempt (null = none yet). Read by the diagnostics so the log can show WHY the install failed. */
    @Volatile
    var lastInstallResult: TerminalResult? = null
        private set

    /**
     * Tiny `launchXServer`: start CmdEntryPointService with ACTION_START.
     * Sets the started flag FIRST (Tiny does the same) so a later stop
     * always pairs with a start.
     */
    /**
     * P16 XKB GUARD (proven 2026-11-08, XKB-PROOF.md): an empty
     * XKB_CONFIG_ROOT kills Lorie instantly (" is unaccessible", no X6
     * socket, 15 s timeout, the exact Nova 9 failure). So this function
     * REFUSES to start the server when the xkb dir is missing and returns
     * false; the caller shows a clear dialog instead of a cryptic timeout.
     * Returns true when the server was actually started.
     */
    fun startXServer(context: Context, rootfs: File = RootfsManager.rootfsDir(context)): Boolean {
        val app = context.applicationContext
        val tmpDir = File(app.filesDir, "tmp").apply { mkdirs() }

        // EXACT match to Tiny Container's launchXServer (plus Mudif's
        // proven -listen tcp flag).
        val xkbDir = File(rootfs, "usr/share/X11/xkb")
        if (!xkbDir.exists()) {
            Log.e(TAG, "XKB GUARD: ${xkbDir.absolutePath} missing, refusing to start the X server with an empty XKB_CONFIG_ROOT")
            return false
        }
        val xkbRoot = xkbDir.absolutePath

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
            putExtra(CmdEntryPointService.EXTRA_ARGS, arrayOf(DISPLAY, "+extension", "MIT-SHM", "-listen", "tcp", "-disable-dri3"))
            putExtra(CmdEntryPointService.EXTRA_ENV_KEYS, envKeys)
            putExtra(CmdEntryPointService.EXTRA_ENV_VALUES, envVals)
        }
        app.startService(intent)
        xserverRunning = true
        MudifLog.d(TAG, "X server start requested for $DISPLAY (+TCP, -disable-dri3)")
        return true
    }

    /**
     * P16 XKB GUARD: make sure the Debian guest has its XKB data before
     * the X server starts. If /usr/share/X11/xkb is missing, reinstall
     * xkb-data once (a renamed/deleted dir is NOT restored by the
     * no-op apt in ensureDebianDesktop, the package is already
     * "installed") and re-check. Returns true when the dir exists.
     * Call from a background thread.
     */
    fun ensureXkbData(context: Context): Boolean {
        val xkbDir = File(DebianSystem.debianRootfsDir(context), "usr/share/X11/xkb")
        if (xkbDir.exists()) return true
        MudifLog.d(TAG, "XKB GUARD: xkb dir missing, reinstalling xkb-data")
        val result = TerminalSession.runCommandDebian(
            context,
            "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a; apt-get update && apt-get install --reinstall -y xkb-data",
            idleTimeoutSec = 120,
            maxTimeoutSec = 600
        )
        MudifLog.d(TAG, "ensureXkbData exit=${result.exitCode} out=${result.output.takeLast(300)}")
        return xkbDir.exists()
    }

    /**
     * Tiny `killXServer`: ACTION_STOP only if the server was started.
     * Sending ACTION_STOP without a start would spawn a stray :xserver
     * process, that is why the guard exists.
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
     * No connection test, no extra sleep, Tiny waits ONLY for the file.
     */
    fun waitForXSocket(context: Context, timeoutMs: Long = 15000): Boolean {
        val socket = File(context.filesDir, "tmp/.X11-unix/X6")
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (socket.exists()) {
                MudifLog.d(TAG, "X socket ready")
                lastWaitResult = true
                return true
            }
            Thread.sleep(500)
        }
        Log.e(TAG, "X socket not ready after ${timeoutMs}ms")
        lastWaitResult = false
        return false
    }

    /**
     * P6.3.3, mirror of waitForXSocket for a fresh restart: poll
     * until the X6 socket file DISAPPEARS (the old server is really
     * gone) before starting a new server. Best-effort: returns false
     * on timeout and the caller proceeds with the surviving server
     * (the old behaviour) rather than blocking the flow.
     * P6.3.6, SUPERSEDED for the restart path, kept for
     * diagnostics: a SIGKILLed server NEVER deletes its socket file,
     * so this always times out (10 s wasted) even when the server
     * died instantly (proven by the P6.3.5 resume run: the stale X6
     * survived the stop and this call logged its timeout AFTER two
     * fresh servers had already started and died). The restart path
     * now waits for TCP to CLOSE instead (waitForTcpDisplayClosed)
     * and deletes the stale files itself.
     */
    fun waitForXSocketGone(context: Context, timeoutMs: Long = 10000): Boolean {
        val socket = File(context.filesDir, "tmp/.X11-unix/X6")
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!socket.exists()) {
                MudifLog.d(TAG, "X socket gone, old server stopped")
                return true
            }
            Thread.sleep(500)
        }
        Log.e(TAG, "X socket still present after ${timeoutMs}ms, old server may survive")
        return false
    }

    /** True if the X socket file exists right now (diagnostics). */
    fun isXSocketPresent(context: Context): Boolean =
        File(context.filesDir, "tmp/.X11-unix/X6").exists()

    /** TCP port of display :6 (6000 + display number). */
    const val TCP_PORT = 6006

    /**
     * P6.3.6, delete the STALE X server socket files. A SIGKILLed
     * server never cleans up: after the stop, `tmp/.X11-unix/X6`
     * and `tmp/.X6-lock` survive as garbage (proven by the P6.3.5
     * resume run, the stale X6 from the LXQt run fooled
     * waitForXSocket into reporting a dead server as up, and
     * waitForXSocketGone into a 10 s timeout). ONLY call this when
     * TCP 6006 is CLOSED, i.e. no live server holds the display:
     * deleting the files while a server lives would corrupt its
     * Unix socket path. After the delete, waitForXSocket can only
     * succeed on a file the NEW server created.
     */
    fun deleteStaleXSocketFiles(context: Context): Boolean {
        val tmpDir = File(context.filesDir, "tmp")
        val socket = File(tmpDir, ".X11-unix/X6")
        val lock = File(tmpDir, ".X6-lock")
        var deleted = false
        if (socket.exists() && socket.delete()) {
            MudifLog.d(TAG, "Deleted stale X6 socket file")
            deleted = true
        }
        if (lock.exists() && lock.delete()) {
            MudifLog.d(TAG, "Deleted stale .X6-lock file")
            deleted = true
        }
        return deleted
    }

    /** Single TCP probe, is anything accepting on 127.0.0.1:6006?
     * P5.24: one 800ms connect can fail under load right after the
     * desktop starts (P5.23 test: the dialog said false while
     * /proc/net/tcp proved LISTEN + ESTABLISHED). Retry 3 times so a
     * single slow connect does not report a dead socket. Decision
     * points (fast reattach, the step-4 gate, the post-close guard
     * in runXStartup) keep this 3-attempt probe. The two polling
     * waits below use isTcpDisplayOpenOnce instead: the wait loop
     * itself retries, so the inner retry only made each poll
     * iteration cost up to 3 s (3x800ms + 2x300ms). */
    fun isTcpDisplayOpen(): Boolean {
        repeat(3) { attempt ->
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", TCP_PORT), 800)
                    return true
                }
            } catch (_: Exception) {
                // fall through to the next attempt
            }
            if (attempt < 2) Thread.sleep(300)
        }
        return false
    }

    /** One 800ms TCP probe, no retries. ONLY for the polling waits
     *  below: the wait loop itself is the retry, so a failed single
     *  attempt just means one more loop iteration 500 ms later.
     *  Never use this for a one-shot decision (fast reattach, the
     *  step-4 gate, the post-close guard): those keep the 3-attempt
     *  isTcpDisplayOpen above (P5.24). */
    private fun isTcpDisplayOpenOnce(): Boolean {
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
     * p69: polls with the single-attempt probe (the loop is the
     * retry); the old 3-attempt probe made each iteration cost up
     * to 3 s, so the 10 s timeout only polled ~3 times.
     */
    fun waitForTcpDisplay(timeoutMs: Long = 10000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (isTcpDisplayOpenOnce()) {
                MudifLog.d(TAG, "TCP display ready")
                return true
            }
            Thread.sleep(500)
        }
        Log.e(TAG, "TCP display not ready after ${timeoutMs}ms")
        return false
    }

    /**
     * P6.3.6, mirror of waitForTcpDisplay for the restart path:
     * poll until NOTHING accepts on 127.0.0.1:6006, i.e. the old
     * server process is really dead. A listening socket closes the
     * instant its process dies, so TCP closing is the true death
     * signal, unlike the socket FILE, which a SIGKILLed server
     * never deletes (see waitForXSocketGone). Returns false on
     * timeout: the old server survived the stop.
     * p69: polls with the single-attempt probe (the loop is the
     * retry). The destructive step after this wait
     * (deleteStaleXSocketFiles) stays guarded by the 3-attempt
     * isTcpDisplayOpen in runXStartup, so a transient single
     * failure can never delete a live server's socket files.
     */
    fun waitForTcpDisplayClosed(timeoutMs: Long = 10000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isTcpDisplayOpenOnce()) {
                MudifLog.d(TAG, "TCP display closed, old server dead")
                return true
            }
            Thread.sleep(500)
        }
        Log.e(TAG, "TCP display still open after ${timeoutMs}ms, old server survived the stop")
        return false
    }

    /**
     * P17.3, adaptive since p23: match the desktop resolution to the
     * phone's screen aspect ratio (guest height from DesktopScale,
     * width scaled) so the desktop fills the screen without
     * letterboxing and without stretching (which would distort it).
     * Orientation-independent: long side / short side, so it works
     * whether the phone is held portrait or landscape when the
     * viewer opens. Width is rounded to even. Shared by openViewer
     * and the screen recorder (the recording must grab the exact
     * size the server runs at).
     */
    fun viewerResolution(context: Context): String {
        val scale = DesktopScaleCalculator.compute(context)
        return "${scale.guestWidth}x${scale.guestHeight}"
    }

    /** Open the Termux:X11 viewer (Tiny: OpenX11 navigation event).
     * P6.5: pin the viewer to an exact resolution BEFORE it opens. The
     * library reads these keys from the default SharedPreferences
     * (displayResolutionMode/displayResolutionExact/adjustResolution,
     * preferences.xml); with "exact" the viewer resizes the server to
     * that resolution on attach instead of halving it to 640x360 (the
     * default scaled mode at scale 200). P17.3: the resolution is no
     * longer a fixed 1280x720, it is computed from the phone's screen
     * aspect ratio (guest height from DesktopScale, width scaled) so
     * the desktop fills the screen without letterboxing. The desktop
     * launches after the viewer opens (VncActivity), so it lays out at
     * the settled resolution. adjustResolution is forced false so a
     * portrait surface never swaps the dimensions. */
    fun openViewer(context: Context) {
        // P17.3, adaptive since p23: match the desktop resolution to
        // the phone's screen aspect ratio (guest height from
        // DesktopScale, width scaled) so the desktop fills the screen
        // without letterboxing and without stretching (which would
        // distort it). Orientation-independent: long side / short
        // side, so it works whether the phone is held portrait or
        // landscape when the viewer opens. Width is rounded to even.
        val resolution = viewerResolution(context)
        // P18.3: Mudif defaults are applied ONLY when a pref is absent
        // (first launch). The old code overwrote touchMode / exact /
        // stretch / additionalKbdVisible on EVERY launch, which silently
        // erased the user's choices from the new settings screen. The
        // user's choice now always wins after first launch. Defaults:
        // touchMode=2 (SIMULATED_TOUCH, pointer follows the finger),
        // exact + stretch (fullscreen, no black bars), fullscreen=true,
        // tapToMove=false, additionalKbdVisible=false (bar hidden until
        // the FAB is tapped), forceOrientation=landscape (a desktop is
        // landscape; in portrait the wallpaper is cropped and the layout
        // breaks, the user's "فاشل" verdict on the portrait shot).
        ViewerPrefs.applyMudifDefaults(context, resolution)
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(intent)
    }

    /**
     * Real-computer #9, open the viewer on an EXTERNAL display
     * (TV over Smart View / DeX / HDMI). Same as openViewer, but the
     * activity is launched on [display] (ActivityOptions
     * setLaunchDisplayId, API 26+) and the resolution is computed
     * from THAT display's real metrics. The fork already keeps
     * separate prefs for the secondary display
     * (storeSecondaryDisplayPreferencesSeparately); the caller
     * enables that flag first so the TV gets its own settings.
     * Returns false when the launch fails (e.g. the display went
     * away). Unverified on a real TV: the fork supports secondary
     * displays, but no TV was available to test this path.
     */
    fun openViewerOnDisplay(context: Context, display: android.view.Display): Boolean {
        return try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
            val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
            val aspect = longSide.toDouble() / shortSide.toDouble()
            var width = (720.0 * aspect).roundToInt()
            if (width % 2 != 0) width += 1
            val resolution = "${width}x720"
            // migrateExact=false: this writes into the default prefs
            // file too, and the TV resolution must never replace the
            // phone's stored exact (the phone's own path migrates).
            ViewerPrefs.applyMudifDefaults(context, resolution, migrateExact = false)
            val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            val options = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(display.displayId)
            context.startActivity(intent, options.toBundle())
            MudifLog.d(TAG, "viewer opened on external display ${display.displayId} ($resolution)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "openViewerOnDisplay failed: ${e.message}")
            false
        }
    }

    /**
     * P5.22, THE desktop launch (Tiny Container's x11 branch): ONE
     * backgrounded command into the PTY session. DISPLAY is already
     * in the boot env (127.0.0.1:6), so no prefix is needed. No
     * setsid, no pid file, no marker file, no launch gate, the
     * desktop is a background job of the container shell, exactly as
     * in Tiny Container (`startxfce4 > /dev/null 2>&1 &`).
     * [desktop] is "lxqt" (startlxqt) or "xfce" (startxfce4).
     */
    fun launchDesktop(context: Context, desktop: String): Boolean {
        val cmd = if (desktop == "xfce") "startxfce4" else "startlxqt"
        // p28 ROOT FIX (desktop icon red "!"): Debian's libfm-qt
        // checks the GIO attribute "metadata::trust" (NOT
        // "metadata::trusted", that is Ubuntu's patched name).
        // The p22/p23 fixes set "metadata::trusted" (gio set in the
        // image build, and the xattr user.metadata::trusted), which
        // libfm-qt never reads, so the "!" stayed. Set the CORRECT
        // attribute (user.metadata::trust) on the launcher before
        // the desktop reads it, so pcmanfm-qt shows it trusted
        // from the first launch. Idempotent; a failure here must
        // never block the desktop, so it is best-effort.
        // p29: the p28 xattr alone did NOT clear the "!" on the rig
        // (gio info confirmed both xattrs true, icon still "!").
        // The freedesktop trust rule also requires the .desktop file
        // itself to be executable, so chmod +x both copies as well.
        // p30: the xattr + chmod +x are BOTH set and the "!" is
        // STILL there (rig-proven). The trust hypothesis is dead:
        // libfm-qt reads metadata::trust from the gvfs-metadata
        // store (/root/.local/share/gvfs-metadata/), NOT the xattr.
        // gio set only works with the desktop session's LIVE bus,
        // and only BEFORE pcmanfm-qt first reads the icon (a set
        // while pcmanfm-qt runs does not clear the "!" live). So a
        // backgrounded loop polls for lxqt-session, reads its bus
        // from /proc, and sets the trust on both copies. It must
        // beat pcmanfm-qt's first icon read (~1-3 s); if it loses,
        // the store persists, so the NEXT launch is clean.
        // dbus-launch does NOT work (its temporary bus never
        // reaches the store). Keep chmod +x and the xattr as
        // belt-and-suspenders. Best-effort; never blocks the desktop.
        // p78: the trust treatment covers EVERY .desktop file on
        // ~/Desktop, not just mudif-launcher.desktop (rig-proven:
        // any other staged icon, e.g. Craft Anything, stays
        // untrusted with the red "!" and double-click shows the
        // trust dialog instead of launching). The glob covers
        // whatever the store or the user placed there; the
        // /usr/share/applications copy of the launcher keeps its
        // own line (that dir is NOT globbed: trusting 1000+
        // system entries is pointless, the desktop icon is the
        // ~/Desktop copy).
        try {
            GuestSession.sendCommand(
                "chmod +x /root/Desktop/*.desktop /usr/share/applications/mudif-launcher.desktop 2>/dev/null; " +
                "python3 -c \"import os,glob; [os.setxattr(f, 'user.metadata::trust', b'true') for f in glob.glob('/root/Desktop/*.desktop')]\" 2>/dev/null; " +
                "python3 -c \"import os; os.setxattr('/usr/share/applications/mudif-launcher.desktop', 'user.metadata::trust', b'true')\" 2>/dev/null"
            )
        } catch (_: Exception) { }
        // P6.3.5: unique timestamped log per run. The P6.3.2/P6.3.4
        // tests believed the Terminal re-ran startxfce4 and overwrote
        // the log, a grep proves NO code re-runs it (only VncActivity
        // references startxfce4; what the Terminal shows is the PTY
        // echo of this command). A timestamped file ends the
        // ambiguity: the startup stderr is provably this run's.
        val stamp = System.currentTimeMillis() / 1000
        val ok = GuestSession.sendCommand("$cmd > /root/$desktop-session-$stamp.log 2>&1 &")
        if (ok) MudifLog.d(TAG, "$desktop command sent through PTY session")
        else Log.e(TAG, "PTY session not available for $desktop")
        // p30 icon fix (LXQt only): backgrounded loop in the PTY
        // shell. Polls every 0.5 s (up to 30 s) for lxqt-session,
        // reads its live bus from /proc, and sets metadata::trust
        // on both .desktop copies with that bus. Tries EVERY
        // lxqt-session pid (a killed session can linger as a zombie
        // whose /proc environ is empty, so the first pid may be
        // unreadable). Best-effort; the loop exits on its own.
        if (ok && desktop == "lxqt") {
            val d = "$"
            // p78: gio set runs over EVERY staged desktop file on
            // ~/Desktop (same glob as the chmod/xattr step above);
            // the loop still breaks on the first fully-successful
            // pass, and still retries for 30 s when a set fails.
            val trustLoop = "( for i in ${d}(seq 1 60); do for pid in ${d}(pgrep -f '[l]xqt-session'); do " +
                "bus=${d}(tr '\\0' '\\n' < /proc/${d}pid/environ 2>/dev/null | grep '^DBUS_SESSION_BUS_ADDRESS=' | cut -d= -f2-); " +
                "if [ -n \"${d}bus\" ]; then for f in /root/Desktop/*.desktop; do DBUS_SESSION_BUS_ADDRESS=\"${d}bus\" gio set \"${d}f\" metadata::trust true 2>/dev/null; done && " +
                "DBUS_SESSION_BUS_ADDRESS=\"${d}bus\" gio set /usr/share/applications/mudif-launcher.desktop metadata::trust true 2>/dev/null && break 2; fi; done; sleep 0.5; done ) " +
                "> /root/icon-trust-$stamp.log 2>&1 &"
            try {
                GuestSession.sendCommand(trustLoop)
            } catch (_: Exception) { }
        }
        return ok
    }

    /** Last 30 lines of the desktop session log (diagnostics only).
     * P6.3.5: reads the NEWEST timestamped log
     * ($desktop-session-<epoch>.log), falling back to the pre-P6.3.5
     * fixed name ($desktop-session.log) from older builds. */
    fun getDesktopLog(context: Context, desktop: String, rootfs: File = RootfsManager.rootfsDir(context)): String {
        return try {
            val dir = File(rootfs, "root")
            val logs = dir.listFiles { f ->
                f.name.startsWith("$desktop-session-") && f.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() }
            // Fall back to the pre-P6.3.5 name (runs from older builds).
            val file = logs?.firstOrNull() ?: File(rootfs, "root/$desktop-session.log")
            if (!file.exists()) return "($desktop-session.log not found - $desktop never started)"
            val lines = file.readLines()
            if (lines.isEmpty()) return "($desktop.log empty)"
            lines.takeLast(30).joinToString("\n")
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }

    /**
     * P5.24, kill the previous desktop's processes before a fresh
     * boot. Tapping a desktop row finishes the old PTY session, but
     * the desktop's processes (openbox, panel, pcmanfm, qterminal...)
     * survive as orphans (PPid 1) and keep owning the display, so the
     * new desktop's window manager refuses to start (xfwm4: "Another
     * Window Manager is already running", P5.23 test). This one-shot
     * pkill runs inside the guest (same PID namespace) before the
     * fresh boot. The [x] bracket trick keeps pkill from matching its
     * own shell, whose cmdline contains the pattern text. pkill exits
     * 1 when nothing matched, that is fine, not a failure.
     */
    fun killDesktopProcesses(context: Context): Boolean {
        val pattern = "[o]penbox|[l]xqt-session|[l]xqt-panel|[l]xqt-runner|" +
            "[l]xqt-notificationd|[l]xqt-globalkeysd|[p]cmanfm-qt|[q]terminal|" +
            "[x]fwm4|[x]fce4-session|[x]fce4-panel|[x]fdesktop|[x]fce4-terminal|" +
            "[f]irefox|[s]tartlxqt|[s]tartxfce4|[d]bus-daemon"
        val result = TerminalSession.runCommand(
            context,
            "pkill -f '$pattern'; echo DESKTOP_CLEANED",
            idleTimeoutSec = 15,
            maxTimeoutSec = 30
        )
        MudifLog.d(TAG, "killDesktopProcesses exit=${result.exitCode} out=${result.output}")
        return result.output.contains("DESKTOP_CLEANED")
    }

    /**
     * P6, same desktop cleanup as killDesktopProcesses, but inside
     * the DEBIAN guest (the previous نظام مضيف session's orphans).
     * P6.4: the pattern covers LXQt (the system now) AND the old
     * XFCE names, the box may still hold a childless XFCE session
     * from the fresh-rootfs tap, and pkill matches nothing when a
     * name is absent, so keeping both costs nothing.
     * Best-effort; a failure here is not fatal, the fresh boot
     * proceeds anyway.
     */
    fun killDebianDesktopProcesses(context: Context): Boolean {
        val pattern = "[l]xqt-session|[l]xqt-panel|[l]xqt-runner|[l]xqt-notificationd|" +
            "[l]xqt-globalkeysd|[p]cmanfm-qt|[q]terminal|[o]penbox|[s]tartlxqt|" +
            "[x]fwm4|[x]fce4-session|[x]fce4-panel|[x]fdesktop|[x]fce4-terminal|" +
            "[f]irefox-esr|[s]tartxfce4|[d]bus-daemon|" +
            "[a]pt-get|[d]pkg"
        val result = TerminalSession.runCommandDebian(
            context,
            "pkill -f '$pattern'; echo DESKTOP_CLEANED",
            idleTimeoutSec = 15,
            maxTimeoutSec = 30
        )
        MudifLog.d(TAG, "killDebianDesktopProcesses exit=${result.exitCode} out=${result.output}")
        return result.output.contains("DESKTOP_CLEANED")
    }

    /**
     * P16.5, kill orphaned apt-get/dpkg before the install.
     * PROVEN 2026-11-09 by reading this code + the Nova 9 evidence:
     * when an install is killed mid-operation (P16.3: the 120 s idle
     * timeout), its apt-get child survives, P16.3 screenshot: PID
     * 11794 holding /var/lib/dpkg/lock-frontend, and
     * killDebianDesktopProcesses never killed it (its pkill pattern
     * had no apt-get/dpkg). Every later install then fails fast on
     * the lock: no install, no temp files, exactly the P16.4
     * evidence (Nova 9, 2026-11-09, 30+ min wait, nothing happened).
     * The orphan is a child of this app's proot, same UID, so the
     * app can kill it from the host side with plain ps + kill (no
     * procps needed in the guest). Belt and suspenders: a guest-side
     * pkill too (works when procps is installed; harmless when not).
     * Best-effort; the install's own dpkg --configure -a repairs
     * any interrupted state afterwards.
     */
    fun killOrphanedAptProcesses(context: Context): Boolean {
        var killed = 0
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("ps", "-A"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            for (line in out.lines()) {
                val lower = line.lowercase()
                if (!lower.contains("apt-get") && !lower.contains("dpkg")) continue
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) continue
                val pid = parts[1].toIntOrNull() ?: continue
                if (pid <= 1) continue
                try {
                    Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString())).waitFor()
                    killed++
                    MudifLog.d(TAG, "killOrphanedAptProcesses killed pid=$pid line=${line.trim()}")
                } catch (_: Exception) { /* already gone */ }
            }
        } catch (e: Exception) {
            Log.w(TAG, "killOrphanedAptProcesses ps failed: ${e.message}")
        }
        try {
            TerminalSession.runCommandDebian(
                context,
                "pkill -9 -f apt-get 2>/dev/null; pkill -9 -f dpkg 2>/dev/null; echo APT_CLEANED",
                idleTimeoutSec = 15,
                maxTimeoutSec = 30
            )
        } catch (_: Exception) { /* best-effort */ }
        MudifLog.d(TAG, "killOrphanedAptProcesses killed=$killed")
        return true
    }

    /**
     * P5.25, fix GTK icon loading under proot (the XFCE killer,
     * proven by the 2026-11-06 guest diagnostic, NOT a guess):
     * Alpine 3.24's gdk-pixbuf ships ONLY libpixbufloader-xpm.so,
     * so every PNG/JPEG/SVG icon goes through the glycin loader
     * (/usr/libexec/glycin-loaders/2+/glycin-image-rs) spawned under
     * `bwrap --unshare-all`, which cannot create namespaces under
     * proot. The NULL pixbuf trips the libwnck/Gtk assertions that
     * abort xfce4-session, xfce4-panel and xfdesktop. Deleting
     * glycin would leave NO PNG loader at all, so instead this
     * replaces /usr/bin/bwrap with a shim that skips the sandbox
     * options and execs the loader directly (unsandboxed), keeps the
     * real binary as /usr/bin/bwrap.real (reversible), and installs
     * the Adwaita icon theme (the guest has only hicolor).
     * One-shot, best-effort, idempotent. Must run BEFORE the
     * desktop launch (VncActivity step 2b).
     */
    fun ensurePixbufLoaders(context: Context): Boolean {
        val command = """
            apk add --no-cache adwaita-icon-theme
            if [ -x /usr/bin/bwrap ] && [ ! -e /usr/bin/bwrap.real ]; then mv /usr/bin/bwrap /usr/bin/bwrap.real; fi
            cat > /usr/bin/bwrap <<'BWRAP_SHIM'
            #!/bin/sh
            # P5.25 bwrap shim: bwrap cannot create namespaces under proot,
            # so run the sandboxed program directly (unsandboxed) instead.
            # gdk-pixbuf calls: bwrap <opts...> <program> <args...>
            # Skip every known bwrap option (with its values); exec the rest.
            while [ $# -gt 0 ]; do
              case "$1" in
                --unshare-all|--unshare-user|--unshare-ipc|--unshare-pid|--unshare-net|--unshare-uts|--unshare-cgroup|--unshare-user-try|--unshare-cgroup-try|--die-with-parent|--clearenv|--new-session|--as-pid-1|--userns|--userns2) shift ;;
                --chdir|--tmpfs|--dev|--proc|--mqueue|--dir|--seccomp|--add-seccomp-fd|--unsetenv|--hostname|--domainname|--uid|--gid|--remount-ro|--cap-add|--cap-drop|--args|--lock-file|--info-fd|--json-status-fd|--userns-fd|--seccomp-fd) shift 2 ;;
                --setenv|--ro-bind|--bind|--dev-bind|--ro-bind-try|--bind-try|--symlink|--file|--bind-data|--ro-bind-data) shift 3 ;;
                --) shift; break ;;
                --*) shift ;;
                *) break ;;
              esac
            done
            exec "$@"
            BWRAP_SHIM
            chmod +x /usr/bin/bwrap
            gtk-update-icon-cache -f /usr/share/icons/hicolor /usr/share/icons/Adwaita 2>/dev/null
            grep -q "P5.25 bwrap shim" /usr/bin/bwrap && echo PIXBUF_OK
        """.trimIndent()
        val result = TerminalSession.runCommand(
            context,
            command,
            idleTimeoutSec = 60,
            maxTimeoutSec = 300
        )
        MudifLog.d(TAG, "ensurePixbufLoaders exit=${result.exitCode} out=${result.output}")
        return result.output.contains("PIXBUF_OK")
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
        MudifLog.d(TAG, "ensureXkb exit=${result.exitCode} out=${result.output}")
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
        MudifLog.d(TAG, "ensureDesktopPackages exit=${result.exitCode} out=${result.output}")
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
        MudifLog.d(TAG, "ensureDesktopApps exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /**
     * Install the LXQt desktop core in the guest + qterminal (LXQt's
     * own terminal, the Tiny Container way, the desktop ships its
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
        MudifLog.d(TAG, "ensureLxqt exit=${result.exitCode} out=${result.output}")
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
        MudifLog.d(TAG, "ensureXfce exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /**
     * p34g: the one guest command behind isDesktopSessionAlive and
     * the LXQt branch of isDesktopRunning. For each lxqt-session and
     * openbox pid it reads /proc/<pid>/stat field 3 (the process
     * state; both comm names have no spaces, so field 3 is the
     * state) and requires a state other than Z. Prints SESSION_ALIVE
     * only when a live session AND a live openbox both exist, else
     * SESSION_DEAD with the per-process state (alive/zombie/missing)
     * so the caller can log why. pgrep/ps alone cannot do this:
     * both list a zombie pid as if it were running, with no state
     * column to filter on. The [l]/[o] bracket trick keeps pgrep
     * from matching this one-shot's own shell, and the openbox
     * value travels under the token "wm": the literal word
     * "openbox" must appear NOWHERE in the rendered command except
     * inside the bracket pattern, because pgrep -f matches the
     * invoking shell's own command line, and every literal
     * "openbox" in it (variable names, echo text) makes the shell
     * match itself as a live openbox. The session half is safe the
     * same way: its echo text says "session=", never
     * "lxqt-session".
     */
    private fun desktopAliveCommand(): String {
        val d = "$"
        return "session=missing; for pid in ${d}(pgrep -f '[l]xqt-session' 2>/dev/null); do " +
            "state=${d}(awk '{print ${d}3}' /proc/${d}pid/stat 2>/dev/null); " +
            "if [ \"${d}state\" = \"Z\" ]; then session=zombie; " +
            "elif [ -n \"${d}state\" ]; then session=alive; break; fi; done; " +
            "wm=missing; for pid in ${d}(pgrep -f '[o]penbox' 2>/dev/null); do " +
            "state=${d}(awk '{print ${d}3}' /proc/${d}pid/stat 2>/dev/null); " +
            "if [ \"${d}state\" = \"Z\" ]; then wm=zombie; " +
            "elif [ -n \"${d}state\" ]; then wm=alive; break; fi; done; " +
            "if [ \"${d}session\" = \"alive\" ] && [ \"${d}wm\" = \"alive\" ]; " +
            "then echo SESSION_ALIVE; " +
            "else echo \"SESSION_DEAD session=${d}session wm=${d}wm\"; fi"
    }

    /** Why the last desktop-alive probe reported dead, from the
     * SESSION_DEAD line the guest command prints (wm = openbox).
     * Nulls mean the guest produced no probe output at all (guest
     * down). */
    private fun parseDesktopDeadReason(output: String): String {
        val session = Regex("session=(alive|zombie|missing)").find(output)?.groupValues?.getOrNull(1)
        val wm = Regex("wm=(alive|zombie|missing)").find(output)?.groupValues?.getOrNull(1)
        return when {
            session == null && wm == null -> "guest down or no probe output"
            session == "zombie" && wm == "zombie" -> "zombie lxqt-session and zombie openbox"
            session == "zombie" -> "zombie lxqt-session"
            session == "missing" -> "lxqt-session missing"
            wm == "zombie" -> "zombie openbox"
            wm == "missing" -> "openbox missing"
            else -> "session=$session openbox=$wm"
        }
    }

    /**
     * p34g FIX 4, the desktop-up check the vision chip calls: true
     * only when a NON-ZOMBIE lxqt-session AND a NON-ZOMBIE openbox
     * are both alive in the Debian guest. A TCP probe
     * (isTcpDisplayOpen) proves only that the X server listens on
     * 6006, and pgrep/ps prove only that a pid exists: a killed
     * lxqt-session lingers as a zombie (state Z) and both still
     * report alive, which is the p34f chip gap (chip read ready
     * while the desktop was down). One guest command, Debian guest
     * only. Returns false, never throws, when the guest is down.
     */
    fun isDesktopSessionAlive(context: Context): Boolean {
        val alive: Boolean
        val reason: String
        try {
            val result = TerminalSession.runCommandDebian(
                context, desktopAliveCommand(), idleTimeoutSec = 10, maxTimeoutSec = 20
            )
            alive = result.output.contains("SESSION_ALIVE")
            reason = if (alive) "live lxqt-session and live openbox"
            else parseDesktopDeadReason(result.output)
        } catch (e: Exception) {
            MudifLog.i("MudifAgent", "isDesktopSessionAlive alive=false reason=guest down (${e.message})")
            return false
        }
        MudifLog.i("MudifAgent", "isDesktopSessionAlive alive=$alive reason=$reason")
        return alive
    }

    /**
     * P6.1, is the desktop REALLY running? pgrep for the session
     * process in the target guest (same PID namespace, so a one-shot
     * sees the PTY session's processes, proven by P5.24's pkill).
     * The P6 test proved the dialog's "sent" line is NOT enough: run 3
     * reported TCP true + sent true with no desktop at all (the X
     * server was borrowed from the surviving LXQt run). This check is
     * the ground truth. Polls twice (3 s apart) so a slow start is
     * not a false negative. p34g: the LXQt branch is zombie-aware
     * (non-zombie lxqt-session AND openbox, /proc/<pid>/stat state
     * != Z); the ps fallback is removed because ps lists a defunct
     * lxqt-session by its comm name, so a dead desktop reported
     * RUNNING (p34f). There is no ps fallback anymore: when pgrep
     * is absent the check fails closed (dead, triggers a fresh
     * start) instead of guessing from ps.
     * The [x]/[l] bracket trick keeps pgrep/ps from matching the
     * one-shot's own shell, whose cmdline contains the pattern text.
     */
    fun isDesktopRunning(context: Context, desktop: String, debian: Boolean): Boolean {
        // P6.3.3: the session alone is NOT a usable desktop, the
        // P6.3.2 test had xfce4-session alive with NO xfwm4, NO panel,
        // NO xfdesktop, and the dialog still reported "desktop
        // running: true". For XFCE require the session AND the window
        // manager AND the panel. p34g: LXQt now follows the same
        // discipline, non-zombie session AND non-zombie openbox, via
        // the shared desktopAliveCommand (see isDesktopSessionAlive).
        val command = if (desktop == "xfce") {
            "if pgrep -f '[x]fce4-session' >/dev/null 2>&1 && " +
                "pgrep -f '[x]fwm4' >/dev/null 2>&1 && " +
                "pgrep -f '[x]fce4-panel' >/dev/null 2>&1; then echo RUNNING; fi"
        } else {
            desktopAliveCommand()
        }
        val aliveToken = if (desktop == "xfce") "RUNNING" else "SESSION_ALIVE"
        var lastOutput = ""
        repeat(2) { attempt ->
            val result = if (debian) {
                TerminalSession.runCommandDebian(
                    context, command, idleTimeoutSec = 10, maxTimeoutSec = 20
                )
            } else {
                TerminalSession.runCommand(
                    context, command, idleTimeoutSec = 10, maxTimeoutSec = 20
                )
            }
            lastOutput = result.output
            if (result.output.contains(aliveToken)) {
                MudifLog.i("MudifAgent", "isDesktopRunning desktop=$desktop debian=$debian alive=true")
                return true
            }
            if (attempt == 0) Thread.sleep(3000)
        }
        val reason = if (desktop == "xfce") "session, window manager or panel missing"
        else parseDesktopDeadReason(lastOutput)
        MudifLog.i("MudifAgent", "isDesktopRunning desktop=$desktop debian=$debian alive=false reason=$reason")
        return false
    }

    /**
     * P17, SUPERSEDED: the on-device apt install below is GONE.
     * The desktop set now ships INSIDE the pre-built image; this
     * function only checks the /root/.mudif_desktop_ok marker the
     * image bakes in. The P6,P16.6 history is kept below for the
     * record (it is why P17 exists: the install could never finish
     * on the Nova 9, the app's own idle timeout killed it, P16.5/
     * P16.6, and an orphaned apt-get then held the dpkg lock).
     *
     * P6, install the نظام مضيف desktop set in the DEBIAN guest
     * (proven live by the 2026-11-06 diagnostic, Q4): apt update,
     * then ONE apt install with --no-install-recommends.
     * P6.4, the set is LXQt now (XFCE stopped by the user's order
     * 2026-11-07; B2 was environmental, but the decision is final):
     * lxqt metapackage + the core components listed explicitly
     * (belt and braces, --no-install-recommends skips Recommends,
     * so nothing is left to Depends-vs-Recommends guessing) +
     * openbox (LXQt's window manager) + qterminal (LXQt's own
     * terminal) + lxqt-qtplugin (the Qt platform theme plugin, with
     * QT_QPA_PLATFORMTHEME=lxqt it is what applies icon_theme to Qt
     * apps; proven missing-and-fixed on Alpine in the 2026-11-07
     * LXQt test) + adwaita-qt6 (dark Qt style for Qt6 apps) +
     * adwaita-qt (the Qt5 port, qlipper is Qt5-only and its factory
     * looks in qt5/plugins/styles; without it qlipper rejects the
     * override, STYLE-PROBE-P66) +
     * gnome-themes-extra (Adwaita GTK theme for GTK_THEME) +
     * firefox-esr + deepin-icon-theme (ships bloom/bloom-dark) +
     * papirus + adwaita icons + dbus-x11 +
     * xkb-data (the X server's XKB data) + procps (pgrep/ps/pkill,
     * needed by isDesktopRunning and killDebianDesktopProcesses).
     * Kvantum is DELIBERATELY ABSENT: there is NO kvantum or
     * kvantum-themes package in Debian trixie (verified
     * packages.debian.org 2026-11-07: "No such package"), the
     * Alpine recipe (kvantum + KvArcDark) does not transfer. The
     * dark Qt style is Adwaita-dark instead (upstream FedoraQt
     * README lists Adwaita / Adwaita-dark / HighContrast).
     * P6.2 FIX (P6.1 test, 2026-11-07): the list used
     * `xkeyboard-config`, which does NOT exist on Debian, the
     * package is named xkb-data (the upstream project name is
     * xkeyboard-config, the Debian binary package is xkb-data).
     * apt aborted the whole && chain on it, so ZERO packages
     * installed (live logcat: "E: Unable to locate package
     * xkeyboard-config"). DEBIAN_FRONTEND noninteractive so nothing
     * ever prompts. NO bwrap shim here: Debian's gdk-pixbuf has
     * native PNG/JPEG loaders and glycin is not in the closure ,
     * the P5.25 failure mode does not exist. One-shot, best-effort,
     * idempotent. Call from a background thread.
     * P6.3.2: libgl1 + libglx-mesa0, xfwm4 dies at startup without
     * them ("Couldn't open libGL.so.1", proven in /root/xfce.log,
     * P6.3.1 test) and with no window manager the panel stays alive
     * but never shows. x11-utils (xwininfo) is diagnostic tooling
     * for reading the panel window state.
     * P8: fonts-noto-core + fonts-noto-ui-core (Noto Sans Arabic
     * fallback), qttranslations5-l10n (Qt5 Arabic for qlipper),
     * locales (insurance for locale-gen); the chain then enables
     * ar_MA.UTF-8 in /etc/locale.gen, runs locale-gen, and rebuilds
     * the font cache (fc-cache -f runs again on the next launch,
     * after LxqtLook has copied the Cairo files in).
     * P8 fix: less + nano + curl are the Arabic CLI's primary tools
     * (the P8 test found them missing in the guest).
     * P16.3: dpkg --configure -a runs first, an interrupted dpkg
     * (proven on the Nova 9, 2026-11-09: "E: dpkg was interrupted")
     * makes every apt call fail until it is repaired.
     * P16.4: timeouts raised (idle 120 -> 600, max 1800 -> 3600).
     * PROVEN 2026-11-09 by reading this code + the Nova 9 logs: the
     * install was killed by the app's OWN 120 s idle timeout, not by
     * the phone sleeping. apt/dpkg go silent > 2 min while downloading
     * a large .deb (no newline in piped mode), unpacking firefox-esr /
     * fonts-noto-core, running locale-gen or fc-cache, and
     * TerminalSession.destroyForcibly() then kills apt mid-operation,
     * which interrupts dpkg and orphans an apt-get holding the lock
     * (PID 11794 on the Nova 9). The user kept the phone awake and it
     * still failed on a fresh install, the killer is this timeout.
     * P16.5: the 600/3600 raise did NOT fix the Nova 9 (2026-11-09:
     * 30+ min wait, no install, no temp files). The orphaned apt-get
     * from the earlier kill still holds the dpkg lock, and this app
     * never killed it, so the install now fails fast on the lock
     * before apt can start. The caller kills orphans first
     * (killOrphanedAptProcesses) and the result is recorded in
     * lastInstallResult so the diagnostics can show WHY it failed.
     * P16.6: PROVEN by the P16.5 diagnostics on the Nova 9
     * (2026-11-09 03:33): the install DID run this time (reached
     * "Setting up papirus-icon-theme") and was killed by the app's
     * OWN idle timeout, "تم إيقاف الأمر: توقف عن إخراج أي نتيجة
     * منذ 601s", during the silent "Setting up" phase, which on
     * this slow phone takes > 10 min of no stdout. The idle timeout
     * assumes silence = stuck, but dpkg/apt legitimately go silent
     * for long stretches while unpacking and updating caches on
     * slow storage. So idle and max are BOTH 7200 s (2 h) here:
     * silence alone can never kill this install; only the 2 h total
     * cap can. The orphan-kill (P16.5) stays, it cleared the old
     * lock so the install could start, and it will clear the dpkg
     * orphaned by this kill (PID 4302) on the next run.
     */
    fun ensureDebianDesktop(context: Context): Boolean {
        // P17: the desktop set ships INSIDE the pre-built image ,
        // no on-device apt install anymore (that install could never
        // finish on the Nova 9: P16.5/P16.6). The image bakes
        // /root/.mudif_desktop_ok = "DEBIAN_DESKTOP_OK"
        // (build-image.sh); its presence proves the full LXQt set is
        // installed. No install ran, so lastInstallResult stays null.
        val marker = File(DebianSystem.debianRootfsDir(context), "root/.mudif_desktop_ok")
        val ok = try {
            marker.exists() && marker.readText().trim() == "DEBIAN_DESKTOP_OK"
        } catch (e: Exception) {
            false
        }
        lastInstallResult = null
        MudifLog.d(TAG, "ensureDebianDesktop (P17 marker check) ok=$ok")
        return ok
    }
}
