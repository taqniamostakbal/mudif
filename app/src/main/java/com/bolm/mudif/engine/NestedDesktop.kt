package com.bolm.mudif.engine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Option B phase 2 (p121): the desktop lives on its own nested
 * display :7, detached from the app, so it survives the app dying
 * or being force-stopped and reattaches on reopen.
 *
 * Layout:
 * - :7 runs Xvfb (depth 24, Unix-socket DISPLAY=:7) + startlxqt
 *   with DISPLAY=:7 + x11vnc -display :7 -forever -shared
 *   -noxdamage -nopw -listen localhost -rfbport 5900.
 * - :6 becomes viewer-host ONLY: the Termux:X11 server plus a
 *   windowed vncviewer showing :7 (windowed, NOT fullscreen:
 *   -FullScreen recreates its window ~1/s and never paints,
 *   diagnosed 20 Nov 2026, p121).
 * - The :7 tree starts detached: setsid + proot, so it is in its
 *   own session and process group, and Android's force-stop (which
 *   kills the app's process group) does not touch it. The launcher
 *   script (assets/lxqt7_session.sh, staged to /root in the guest)
 *   waits on the Xvfb pid, so the proot lives exactly as long as
 *   Xvfb. The pid files land in the guest /tmp, which is bound to
 *   the app's filesDir/tmp, so the app reads them from the host.
 *
 * Aliveness is host-side only (pid files + /proc + one TCP probe
 * on 5900): no guest one-shot is needed to ask whether :7 is up.
 * The one guest one-shot in this file is the xdpyinfo proof inside
 * ensureNestedDesktop (the display really answers) and the
 * DISPLAY-scoped kill inside killNestedTree.
 *
 * Honest scope: this is a DESKTOP-survival fix, NOT an app-death
 * fix. The app can still die; the desktop is what survives it.
 * When the nested tree cannot start (Xvfb missing, start fails),
 * every caller falls back to the legacy :6 PTY desktop instead of
 * leaving the user with no desktop at all.
 */
object NestedDesktop {

    private const val TAG = "NestedDesktop"

    /** Nested display (Unix socket, NOT 127.0.0.1:7). */
    const val NESTED_DISPLAY = ":7"

    /** x11vnc RFB port on the nested display. */
    const val VNC_PORT = 5900

    /** The :6 viewer display (TCP, the proven InstaBox workaround). */
    const val VIEWER_DISPLAY = "127.0.0.1:6"

    private const val LAUNCHER_ASSET = "lxqt7_session.sh"
    private const val LAUNCHER_GUEST_PATH = "/root/lxqt7_session.sh"

    private const val XVFB_PID_FILE = "mudif-xvfb7.pid"
    private const val X11VNC_PID_FILE = "mudif-x11vnc.pid"
    private const val LXQT_PID_FILE = "mudif-lxqt7.pid"

    private const val VIEWER_LOG = "/tmp/vncviewer.log"

    // p121 (Option B): the nested :7 desktop needs Xvfb + x11vnc +
    // tigervnc-viewer, none of which is in the p23 image. The sets
    // were resolved from the Debian trixie snapshot of 2026-11-13
    // (the image build date, so no version drift can hide a missing
    // dependency): the full Depends closure of the three packages
    // minus everything the clean image already provides, 17
    // packages per arch. xserver-common is arch "all", so it lives
    // in mudif-debs/all/ once; the other 16 are per-arch. The app
    // installs only its own arch's set (RootfsManager.guestArch).
    // The install lives HERE, not in ensureLxqtLook: the look's
    // pcmanfm/settings .debs are amd64-only assets, so their dpkg
    // -i fails on the arm64 phone and ensureLxqtLook returns false
    // before any later step runs. A VNC install inside the look
    // would never execute on the phone. This installer has its own
    // marker (VNC_DEBS_MARKER) and runs from ensureNestedDesktop,
    // independent of the look.
    private val VNC_DEBS_AMD64 = listOf(
        "mudif-debs/amd64/libfltk-images1.3t64_1.3.11-2_amd64.deb",
        "mudif-debs/amd64/libfltk1.3t64_1.3.11-2_amd64.deb",
        "mudif-debs/amd64/liblzo2-2_2.10-3+b1_amd64.deb",
        "mudif-debs/amd64/libtcl8.6_8.6.16+dfsg-1_amd64.deb",
        "mudif-debs/amd64/libtk8.6_8.6.16-1_amd64.deb",
        "mudif-debs/amd64/libunwind8_1.8.1-0.1_amd64.deb",
        "mudif-debs/amd64/libvncclient1_0.9.15+dfsg-1+deb13u2_amd64.deb",
        "mudif-debs/amd64/libvncserver1_0.9.15+dfsg-1+deb13u2_amd64.deb",
        "mudif-debs/amd64/libxfont2_2.0.6-1+deb13u1_amd64.deb",
        "mudif-debs/amd64/tcl8.6_8.6.16+dfsg-1_amd64.deb",
        "mudif-debs/amd64/tcl_8.6.16_amd64.deb",
        "mudif-debs/amd64/tigervnc-viewer_1.15.0+dfsg-2.1~deb13u1_amd64.deb",
        "mudif-debs/amd64/tk8.6_8.6.16-1_amd64.deb",
        "mudif-debs/amd64/tk_8.6.16_amd64.deb",
        "mudif-debs/amd64/x11vnc_0.9.17-1_amd64.deb",
        "mudif-debs/amd64/xvfb_21.1.16-1.3+deb13u3_amd64.deb"
    )
    private val VNC_DEBS_ARM64 = listOf(
        "mudif-debs/arm64/libfltk-images1.3t64_1.3.11-2_arm64.deb",
        "mudif-debs/arm64/libfltk1.3t64_1.3.11-2_arm64.deb",
        "mudif-debs/arm64/liblzo2-2_2.10-3+b1_arm64.deb",
        "mudif-debs/arm64/libtcl8.6_8.6.16+dfsg-1_arm64.deb",
        "mudif-debs/arm64/libtk8.6_8.6.16-1_arm64.deb",
        "mudif-debs/arm64/libunwind8_1.8.1-0.1_arm64.deb",
        "mudif-debs/arm64/libvncclient1_0.9.15+dfsg-1+deb13u2_arm64.deb",
        "mudif-debs/arm64/libvncserver1_0.9.15+dfsg-1+deb13u2_arm64.deb",
        "mudif-debs/arm64/libxfont2_2.0.6-1+deb13u1_arm64.deb",
        "mudif-debs/arm64/tcl8.6_8.6.16+dfsg-1_arm64.deb",
        "mudif-debs/arm64/tcl_8.6.16_arm64.deb",
        "mudif-debs/arm64/tigervnc-viewer_1.15.0+dfsg-2.1~deb13u1_arm64.deb",
        "mudif-debs/arm64/tk8.6_8.6.16-1_arm64.deb",
        "mudif-debs/arm64/tk_8.6.16_arm64.deb",
        "mudif-debs/arm64/x11vnc_0.9.17-1_arm64.deb",
        "mudif-debs/arm64/xvfb_21.1.16-1.3+deb13u3_arm64.deb"
    )
    private val VNC_DEBS_ALL = listOf(
        "mudif-debs/all/xserver-common_21.1.16-1.3+deb13u3_all.deb"
    )
    private const val VNC_DEBS_MARKER = "vnc-debs-p63"

    /** The VNC .deb asset paths for this device's arch. */
    private fun vncDebAssets(): List<String> {
        val arch = if (RootfsManager.guestArch() == "x86_64") VNC_DEBS_AMD64 else VNC_DEBS_ARM64
        return arch + VNC_DEBS_ALL
    }

    /**
     * Install the VNC .deb set (this arch only) into the guest.
     * Marker-gated (root/.config/mudif/vnc-debs-p63, content
     * "installed:<arch>"): a successful install never runs again.
     * Stage the .debs into Mudif's own dir (persists), then one
     * dpkg -i for the whole set (the closure is complete, so no
     * dependency can be missing). dpkg -i is idempotent (same
     * version reinstalls cleanly). A failure writes no marker, so
     * the next entry retries; a failed install never claims the
     * stack is present. Runs on first desktop entry after p121,
     * never across a desktop start (the caller runs before the
     * viewer opens). Never throws.
     */
    fun ensureVncDebsInstalled(context: Context): Boolean {
        val app = context.applicationContext
        val arch = RootfsManager.guestArch()
        val rootfs = DebianSystem.debianRootfsDir(app)
        val marker = File(rootfs, "root/.config/mudif/$VNC_DEBS_MARKER")
        try {
            if (marker.exists() && marker.readText().trim() == "installed:$arch") return true
        } catch (_: Exception) {
        }
        return try {
            val mudifConfDir = File(rootfs, "root/.config/mudif")
            mudifConfDir.mkdirs()
            for (asset in vncDebAssets()) {
                app.assets.open(asset).use { input ->
                    File(mudifConfDir, asset.substringAfterLast("/")).outputStream().use { out ->
                        input.copyTo(out)
                    }
                }
            }
            val paths = vncDebAssets().joinToString(" ") {
                "/root/.config/mudif/${it.substringAfterLast("/")}"
            }
            // dpkg goes silent while unpacking (p16 lesson), so the
            // idle timeout is generous: silence alone must never
            // kill this install, only the 10 min total cap can.
            val result = TerminalSession.runCommandDebian(
                app, "dpkg -i $paths && echo VNC_DEBS_OK",
                idleTimeoutSec = 120, maxTimeoutSec = 600
            )
            MudifLog.d(TAG, "VNC debs install exit=${result.exitCode} out=${result.output.takeLast(300)}")
            if (!result.output.contains("VNC_DEBS_OK")) {
                MudifLog.d(TAG, "VNC debs install failed - no marker, next entry retries")
                return false
            }
            marker.writeText("installed:$arch\n")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ensureVncDebsInstalled failed: ${e.message}")
            false
        }
    }

    /** Last lines of the detached proot's own output (proot errors
     *  land here, the launcher script redirects its children to
     *  files). Read by diagnostics; never blocks the start. */
    @Volatile
    var lastProotOutput: String = ""
        private set

    /**
     * Set by the drain thread when the detached proot's output
     * stream closes, i.e. the proot (and its launcher script)
     * exited. ensureNestedDesktop uses it to fail fast when the
     * tree will never come up (e.g. Xvfb failed to start), instead
     * of polling the full 30 s. Android's java.lang.Process has no
     * pid() method, so the proot pid is not recorded anywhere; the
     * kill path does not need it (killing Xvfb ends the script,
     * the proot exits on its own, --kill-on-exit cleans up).
     */
    @Volatile
    private var detachedProotExited = false

    /** Host-side view of the guest /tmp (bind-mounted by proot). */
    private fun tmpDir(context: Context): File =
        File(context.applicationContext.filesDir, "tmp")

    private fun readPid(context: Context, name: String): Int? {
        return try {
            File(tmpDir(context), name).readText().trim().toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Host-side /proc check: the pid exists, is not a zombie, and
     * its cmdline names the expected binary (guards against pid
     * reuse across app restarts, the pid files persist in
     * filesDir/tmp). [comm] is the binary name as it appears in
     * /proc/<pid>/cmdline, e.g. "Xvfb", "x11vnc", "lxqt-session".
     * A null [comm] skips the name check (used for the proot pid,
     * whose cmdline is the proot binary path).
     */
    private fun isPidAlive(pid: Int, comm: String?): Boolean {
        if (pid <= 1) return false
        val stat = try {
            File("/proc/$pid/stat").readText()
        } catch (_: Exception) {
            return false
        }
        // "pid (comm) state ..." - the state is the token after the
        // last ')'; the comm may itself contain spaces or parens.
        val closeParen = stat.lastIndexOf(')')
        if (closeParen < 0) return false
        val state = stat.substring(closeParen + 1).trim().split(Regex("\\s+")).getOrNull(0)
        if (state.isNullOrEmpty() || state == "Z") return false
        if (comm == null) return true
        return try {
            val cmdline = File("/proc/$pid/cmdline").readBytes()
                .toString(Charsets.UTF_8).split('\u0000').firstOrNull() ?: ""
            File(cmdline).name == comm || cmdline.contains(comm)
        } catch (_: Exception) {
            false
        }
    }

    /** One 800ms TCP probe on the x11vnc port (host side). */
    private fun isVncPortOpen(): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", VNC_PORT), 800)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** True when the three nested binaries exist in the guest. */
    fun isVncStackInstalled(context: Context): Boolean {
        val rootfs = DebianSystem.debianRootfsDir(context)
        return File(rootfs, "usr/bin/Xvfb").exists() &&
            File(rootfs, "usr/bin/x11vnc").exists() &&
            File(rootfs, "usr/bin/vncviewer").exists()
    }

    /**
     * True only when the whole nested tree is up and usable: Xvfb,
     * x11vnc and the lxqt-session pids are alive (cmdline-checked)
     * AND x11vnc answers on TCP 5900. Host-side only, fast enough
     * to call from any thread, including the UI thread.
     */
    fun isNestedDesktopAlive(context: Context): Boolean {
        val app = context.applicationContext
        val xvfb = readPid(app, XVFB_PID_FILE)
        if (xvfb == null || !isPidAlive(xvfb, "Xvfb")) return false
        val vnc = readPid(app, X11VNC_PID_FILE)
        if (vnc == null || !isPidAlive(vnc, "x11vnc")) return false
        val lxqt = readPid(app, LXQT_PID_FILE)
        if (lxqt == null || !isPidAlive(lxqt, "lxqt-session")) {
            // startlxqt is a shell script: until it execs
            // lxqt-session the pid names the script itself.
            if (lxqt == null || !isPidAlive(lxqt, "startlxqt")) return false
        }
        if (!isVncPortOpen()) return false
        return true
    }

    /** True when the :6 vncviewer process is alive in the guest. */
    fun isVncViewerAlive(context: Context): Boolean {
        return X11Session.isGuestProcessAlive(context, "vncviewer")
    }

    /**
     * Start the detached nested tree. Stages the launcher script
     * into the guest (host-side file write, same pattern as the
     * theme files), then runs it under setsid + proot. The guest
     * command carries a clean env (env -i) with DISPLAY=:7, the
     * same sandbox-disable flags the :6 boot env uses, so apps
     * launched on :7 behave exactly as they did on :6.
     * Returns true when the detached proot started (not when the
     * desktop is ready: ensureNestedDesktop polls for that).
     * Never throws.
     */
    /**
     * The :7 resolution. This MUST be 8 px shorter than the :6
     * Termux:X11 server's ACTUAL size, not equal to it. TigerVNC
     * shrinks a window that equals the screen size exactly by 40 px
     * vertically ("Adjusting window size to avoid accidental
     * full-screen request", window 848x440+0+20 with scrollbars, the
     * bottom panel below the fold). Proven 20 Nov 2026, p123 probe
     * B2: :7 at 848x472 + viewer -geometry 848x472 gives a window
     * exactly 848x472, no Adjusting line, no scrollbars, the panel
     * visible. The width stays equal to :6 (width equality alone
     * never triggered the shrink). The size is set at Xvfb start:
     * a live xrandr switch kills x11vnc (probe A2: its listener
     * dies, the viewer gets End of stream), so the mode is never
     * changed after start.
     */
    private fun nestedResolution(app: Context): Pair<Int, Int> {
        return try {
            val res = X11Session.viewerResolution(app)
            val parts = res.split("x")
            val w = parts[0].toInt() / 8 * 8
            val h = parts[1].toInt() / 8 * 8 - 8
            MudifLog.d(TAG, "nestedResolution: requested $res, :7 ${w}x$h")
            w to h
        } catch (_: Exception) {
            848 to 472
        }
    }

    /**
     * True only where Android's phantom-process killer exists
     * (Android 12, API 31+). The minimal :7 session (no gvfs,
     * at-spi, pulseaudio, xiccd, qlipper) applies ONLY there: it
     * costs in-guest audio, network locations in the file manager,
     * accessibility tools and clipboard history, so a phone without
     * the killer (Anas's Huawei Nova 9, API 30) must never lose
     * them. The rig (Android 14, API 34) takes the minimal path,
     * which is what lets it prove survival under the 32 cap.
     */
    fun isMinimalSession(): Boolean = Build.VERSION.SDK_INT >= 31

    fun startDetached(context: Context): Boolean {
        val app = context.applicationContext
        return try {
            // Stage the launcher script (overwrite every time:
            // cheap, and it guarantees the running script is the
            // one this build ships). The guest command runs it via
            // /bin/sh, so +x is belt-and-braces only.
            val rootfs = DebianSystem.debianRootfsDir(app)
            val script = File(rootfs, LAUNCHER_GUEST_PATH.removePrefix("/"))
            script.parentFile?.mkdirs()
            app.assets.open(LAUNCHER_ASSET).use { input ->
                script.outputStream().use { out -> input.copyTo(out) }
            }
            try {
                script.setExecutable(true, false)
            } catch (_: Exception) {
            }
            val (w, h) = nestedResolution(app)
            val guestCommand = listOf(
                "/usr/bin/env", "-i",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LD_LIBRARY_PATH=",
                "LD_PRELOAD=",
                "DISPLAY=$NESTED_DISPLAY",
                "LANG=ar_MA.UTF-8",
                "LANGUAGE=ar",
                "LC_ALL=ar_MA.UTF-8",
                "SHELL=/bin/bash",
                "MOZ_FAKE_NO_SANDBOX=1",
                "MOZ_DISABLE_CONTENT_SANDBOX=1",
                "QTWEBENGINE_DISABLE_SANDBOX=1",
                "ELECTRON_DISABLE_SANDBOX=1",
                "GSK_RENDERER=cairo",
                "HOME=/root",
                "USER=root",
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "XDG_RUNTIME_DIR=/run/user/0",
                "MUDIF_MINIMAL_SESSION=" + if (isMinimalSession()) "1" else "0",
                "/bin/sh", LAUNCHER_GUEST_PATH, w.toString(), h.toString()
            )
            val (argv, env) = RootfsManager.prootCommandLineFor(
                app, rootfs, DebianSystem.isReady(app), guestCommand
            ) ?: run {
                Log.e(TAG, "startDetached: proot command line unavailable (rootfs not ready)")
                return false
            }
            // setsid puts the proot in its own session and process
            // group, so Android's force-stop (which kills the app's
            // process group) does not touch it. Absolute path first:
            // the app-side ProcessBuilder PATH is not guaranteed to
            // contain /system/bin on every device.
            val setsid = if (File("/system/bin/setsid").exists()) "/system/bin/setsid" else "setsid"
            val pb = ProcessBuilder(listOf(setsid) + argv)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            val process = pb.start()
            detachedProotExited = false
            // Drain the proot's own output so a spewing proot can
            // never block on a full pipe. The launcher script
            // redirects its children to files; this stream carries
            // only proot errors. The thread ends when proot exits,
            // which also sets the fail-fast flag for
            // ensureNestedDesktop.
            Thread {
                try {
                    val lines = ArrayDeque<String>()
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            lines.addLast(line!!)
                            if (lines.size > 20) lines.removeFirst()
                            lastProotOutput = lines.joinToString("\n")
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    detachedProotExited = true
                }
            }.start()
            MudifLog.d(TAG, "startDetached: proot started resolution=${w}x$h")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startDetached failed: ${e.message}")
            false
        }
    }

    /**
     * Make sure the nested tree is up. Fast path when it already
     * is. Otherwise kill any stale remnant, start detached, then
     * poll up to 30 s for the alive check, and finally prove the
     * display really answers with one xdpyinfo one-shot. Fails
     * fast when the detached proot itself died (the launcher
     * script exited, e.g. Xvfb failed to start). Serialized: two
     * concurrent calls must never start two trees.
     * Never throws; false means the caller falls back to :6.
     */
    fun ensureNestedDesktop(context: Context): Boolean {
        val app = context.applicationContext
        synchronized(this) {
            if (isNestedDesktopAlive(app)) return true
            // The .debs must land before the tree can start. The
            // install is marker-gated and independent of the look
            // (see ensureVncDebsInstalled): on a clean phone it
            // runs once here, on the first entry after p121.
            if (!isVncStackInstalled(app) && !ensureVncDebsInstalled(app)) {
                MudifLog.d(TAG, "ensureNestedDesktop: VNC .debs not installed, not starting :7")
                return false
            }
            killNestedTree(app)
            if (!startDetached(app)) return false
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (isNestedDesktopAlive(app)) break
                // Fail fast: the proot died, the tree will never come up.
                if (detachedProotExited) {
                    MudifLog.d(TAG, "ensureNestedDesktop: detached proot exited, failing fast")
                    return false
                }
                Thread.sleep(500)
            }
            if (!isNestedDesktopAlive(app)) {
                MudifLog.d(TAG, "ensureNestedDesktop: :7 did not come up in 30 s")
                return false
            }
            // Guest-level proof: the display answers queries.
            val xd = TerminalSession.runCommandDebian(
                app, "DISPLAY=:7 xdpyinfo >/dev/null 2>&1 && echo XD7_OK",
                idleTimeoutSec = 10, maxTimeoutSec = 20
            )
            val ok = xd.output.contains("XD7_OK")
            MudifLog.d(TAG, "ensureNestedDesktop: alive, xdpyinfo ok=$ok")
            return ok
        }
    }

    /**
     * Kill the nested tree and start it fresh. Used by the theme
     * restart rule: after ensureLxqtLook re-stages, the running
     * :7 session still holds the old theme files, so only a fresh
     * tree picks the new ones up. Never throws.
     */
    fun restartNestedDesktop(context: Context): Boolean {
        val app = context.applicationContext
        synchronized(this) {
            killNestedTree(app)
            return ensureNestedDesktop(app)
        }
    }

    /**
     * Kill the nested tree, best-effort. Kills the recorded pids
     * from the host (same UID, same pid namespace under proot),
     * then a DISPLAY-scoped guest one-shot for anything the pid
     * files missed (a wedged-but-alive component with no pid
     * file). The scope is DISPLAY=:7 only: the :6 desktop is never
     * touched. Waits up to 5 s for TCP 5900 to close, then drops
     * the stale pid files. Never throws.
     */
    fun killNestedTree(context: Context) {
        val app = context.applicationContext
        for (name in listOf(XVFB_PID_FILE, X11VNC_PID_FILE, LXQT_PID_FILE)) {
            val pid = readPid(app, name) ?: continue
            if (pid <= 1) continue
            try {
                Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString())).waitFor()
            } catch (_: Exception) {
            }
        }
        try {
            TerminalSession.runCommandDebian(
                app, nestedKillCommand(), idleTimeoutSec = 10, maxTimeoutSec = 20
            )
        } catch (_: Exception) {
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isVncPortOpen()) break
            Thread.sleep(250)
        }
        for (name in listOf(XVFB_PID_FILE, X11VNC_PID_FILE, LXQT_PID_FILE)) {
            try {
                File(tmpDir(app), name).delete()
            } catch (_: Exception) {
            }
        }
        MudifLog.d(TAG, "killNestedTree done")
    }

    /**
     * DISPLAY-scoped kill for the :7 tree (mirror of
     * X11Session.scopedDesktopKillCommand, opposite scope). Kills
     * a pattern match only when its DISPLAY names :7, and spares
     * every other display. The [x]/[l] bracket trick keeps pgrep
     * from matching this one-shot's own shell, and the literal
     * desktop names appear nowhere else in the rendered command.
     */
    private fun nestedKillCommand(): String {
        val d = "$"
        val pattern = "[X]vfb|[x]11vnc|[l]xqt-session|[l]xqt-panel|[l]xqt-runner|" +
            "[l]xqt-notificationd|[l]xqt-globalkeysd|[p]cmanfm-qt|[q]terminal|" +
            "[o]penbox|[s]tartlxqt|[d]bus-daemon"
        return "for pid in ${d}(pgrep -f '$pattern' 2>/dev/null); do " +
            "disp=${d}(tr '\\000' '\\n' < /proc/${d}pid/environ 2>/dev/null | sed -n 's/^DISPLAY=//p'); " +
            "case \"${d}disp\" in :7*) kill -9 \"${d}pid\" 2>/dev/null ;; esac; " +
            "done; echo NESTED_CLEANED"
    }

    /**
     * Start the windowed vncviewer on :6 (the viewer-host side).
     * Windowed, NOT fullscreen: TigerVNC -FullScreen recreates its :6
     * window about once a second and never paints on this server
     * (diagnosed 20 Nov 2026, p121). Windowed at the :7 size the
     * window is stable and the desktop renders (p123: :7 is 8 px
     * shorter than :6, so the viewer keeps the exact window size
     * with no scrollbars and the bottom panel visible).
     * The viewer is launched from the HOST (ProcessBuilder + setsid
     * + proot, mirroring startDetached), NOT as a child of the PTY
     * shell: the PTY shell never reaps its dead children, so every
     * dead viewer lingered as a zombie phantom (p122 diagnosis:
     * viewer 32343 zombie, 1 phantom per dead viewer). A host-side
     * child is reaped by the runtime when its proot exits, and when
     * the app dies the viewer is reparented to init, which reaps
     * it. The viewer still needs :6, which dies with the PTY, so
     * it exits on its own when the app dies. Polls up to 10 s for
     * the viewer process. Never throws.
     */
    fun ensureViewer(context: Context): Boolean {
        val app = context.applicationContext
        if (!GuestSession.alive || !GuestSession.booted) return false
        if (isVncViewerAlive(app)) return true
        val (w, h) = nestedResolution(app)
        val rootfs = DebianSystem.debianRootfsDir(app)
        val guestCommand = listOf(
            "/usr/bin/env", "-i",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LD_LIBRARY_PATH=",
            "LD_PRELOAD=",
            "DISPLAY=$VIEWER_DISPLAY",
            "LANG=ar_MA.UTF-8",
            "HOME=/root",
            "USER=root",
            "/bin/sh", "-c",
            "vncviewer -SecurityTypes None -Shared -geometry ${w}x$h localhost:$VNC_PORT > $VIEWER_LOG 2>&1"
        )
        val (argv, env) = RootfsManager.prootCommandLineFor(
            app, rootfs, DebianSystem.isReady(app), guestCommand
        ) ?: run {
            Log.e(TAG, "ensureViewer: proot command line unavailable (rootfs not ready)")
            return false
        }
        val setsid = if (File("/system/bin/setsid").exists()) "/system/bin/setsid" else "setsid"
        val started = try {
            val pb = ProcessBuilder(listOf(setsid) + argv)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            val process = pb.start()
            // Drain the proot's own output so a spewing proot can
            // never block on a full pipe. The viewer logs to the
            // guest file; this stream carries only proot errors.
            // The thread ends when the proot exits, which is also
            // when the runtime reaps it: no zombie either way.
            Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        while (reader.readLine() != null) {
                        }
                    }
                } catch (_: Exception) {
                }
            }.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "ensureViewer: viewer proot failed: ${e.message}")
            false
        }
        if (!started) return false
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (isVncViewerAlive(app)) {
                MudifLog.d(TAG, "ensureViewer: vncviewer up on :6")
                return true
            }
            Thread.sleep(1000)
        }
        MudifLog.d(TAG, "ensureViewer: vncviewer did not appear in 10 s")
        return false
    }

    /** Kill the :6 vncviewer (theme restart: the viewer is killed
     *  before the :7 tree goes down, so it never shows a reconnect
     *  dialog). Best-effort, PTY only. */
    fun killViewer(context: Context) {
        try {
            if (GuestSession.alive && GuestSession.booted) {
                GuestSession.sendCommand("pkill -x vncviewer 2>/dev/null; echo VIEWER_KILLED")
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Prefix a guest launch command with DISPLAY=:7 when the nested
     * desktop is the live one, else leave it untouched (the PTY
     * shell's DISPLAY=127.0.0.1:6 then targets the legacy :6
     * desktop). Decided at FIRE time, never at build time: a
     * command handed to VncActivity as a pending launch is fired
     * after the desktop boots, when :7 may be up although it was
     * down when the command was built.
     */
    fun withDesktopDisplay(context: Context, command: String): String {
        return if (isNestedDesktopAlive(context)) "DISPLAY=:7 $command" else command
    }

    /**
     * The :7 session bus address, read host-side from the :7
     * lxqt-session's /proc environ (fast, bounded, best-effort).
     * Null when no :7 session is running. Used by the theme switch
     * so the live wallpaper flip reaches the :7 desktop instead of
     * the :6 one. Runs on the UI thread from stageThemeSwitch, so
     * it never shells out and never blocks on the guest.
     */
    fun sessionBusAddress(context: Context): String? {
        return try {
            val proc = File("/proc")
            val entries = proc.listFiles() ?: return null
            for (entry in entries) {
                val pid = entry.name.toIntOrNull() ?: continue
                val cmdline = try {
                    File(entry, "cmdline").readBytes().toString(Charsets.UTF_8)
                        .split('\u0000').firstOrNull() ?: ""
                } catch (_: Exception) {
                    continue
                }
                if (File(cmdline).name != "lxqt-session") continue
                val environ = try {
                    File(entry, "environ").readBytes().toString(Charsets.UTF_8).split('\u0000')
                } catch (_: Exception) {
                    continue
                }
                var display: String? = null
                var bus: String? = null
                for (kv in environ) {
                    when {
                        kv.startsWith("DISPLAY=") -> display = kv.substringAfter("=")
                        kv.startsWith("DBUS_SESSION_BUS_ADDRESS=") -> bus = kv.substringAfter("=")
                    }
                }
                if (display == NESTED_DISPLAY && !bus.isNullOrEmpty()) return bus
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Last 30 lines of the newest :7 session log, plus the tails
     * of the Xvfb and x11vnc logs (diagnostics only). Host-side
     * file reads, never throws.
     */
    fun getNestedDesktopLog(context: Context): String {
        val app = context.applicationContext
        return try {
            val sb = StringBuilder()
            val dir = File(DebianSystem.debianRootfsDir(app), "root")
            val logs = dir.listFiles { f ->
                f.name.startsWith("lxqt7-session-") && f.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() }
            val file = logs?.firstOrNull()
            if (file == null || !file.exists()) {
                sb.append("(lxqt7-session.log not found - :7 never started)\n")
            } else {
                val lines = file.readLines()
                if (lines.isEmpty()) sb.append("(lxqt7-session.log empty)\n")
                else sb.append(lines.takeLast(30).joinToString("\n")).append("\n")
            }
            for (name in listOf("xvfb7.log", "x11vnc7.log")) {
                val f = File(tmpDir(app), name)
                sb.append("--- /tmp/$name ---\n")
                if (!f.exists()) sb.append("(not found)\n")
                else {
                    val lines = f.readLines()
                    if (lines.isEmpty()) sb.append("(empty)\n")
                    else sb.append(lines.takeLast(10).joinToString("\n")).append("\n")
                }
            }
            if (lastProotOutput.isNotEmpty()) {
                sb.append("--- detached proot output ---\n").append(lastProotOutput).append("\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }
}
