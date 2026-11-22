package com.bolm.mudif.engine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Take 1 (droiddesk-takes): the desktop lives directly on the
 * native :6 Termux:X11 server, no nested display, no VNC.
 *
 * Layout:
 * - :6 is the ONLY display: the Termux:X11 server plus the LXQt
 *   session (startlxqt) plus every direct program share it.
 * - The :7 stack is gone: no Xvfb, no x11vnc, no guest vncviewer.
 *   NestedDesktop.kt stays in the tree uncalled (design section
 *   4.2); its deletion is a follow-up cleanup step.
 * - The :6 session starts detached: setsid + proot, so it is in
 *   its own session and process group, and Android's force-stop
 *   (which kills the app's process group) does not touch the
 *   session processes. The launcher script
 *   (assets/lxqt6_session.sh, staged to /root in the guest)
 *   waits on the lxqt-session pid, so the proot lives exactly as
 *   long as the session. The :6 server itself lives in the app's
 *   :xserver process, so after a force-stop the server is gone
 *   and the detached session has a dead X connection: the next
 *   entry cold-starts the desktop instead of reattaching
 *   (design section 6). A plain app crash does NOT kill
 *   :xserver, so crash survival is preserved.
 * - The :6 size is the full viewerResolution (e.g. 848x480 on
 *   the rig). The p123 8 px rule died with TigerVNC.
 *
 * Ordering (design section 5): the :6 size must settle BEFORE
 * the session starts, or the panel and icons lay out at a
 * transient size. ensureDirectDesktop waits for the size, and
 * when it is called from startDesktop (before the viewer has
 * attached) it DEFERS instead of starting at the server's birth
 * size: the post-viewer launch (X11Session.launchDesktop, after
 * waitForViewerAttach) then starts the session at the settled
 * size. That deferral is the one deliberate deviation from the
 * design's literal startDesktop placement: starting at the
 * 1280x1024 birth size (VncActivity step 4c) would lay the panel
 * out off-screen, the exact p65 defect the size rule exists to
 * prevent. When the server is already at the target size (warm
 * reuse), the session starts in startDesktop as designed.
 *
 * Aliveness is host-side first (pid file + /proc, fast enough
 * for the UI thread via isDirectDesktopAliveHost), with a guest
 * one-shot fallback in isDirectDesktopAlive (background threads
 * only: TerminalSession never runs on the UI thread).
 *
 * The :6 session environment carries the take-2 GPU variables
 * (RootfsManager.gpuEnvFor, design section 12): the :7 session
 * never had them, the :6 session must.
 */
object DirectDesktop {

    private const val TAG = "DirectDesktop"

    /** The native display (TCP, the proven InstaBox workaround). */
    const val DIRECT_DISPLAY = "127.0.0.1:6"

    private const val LAUNCHER_ASSET = "lxqt6_session.sh"
    private const val LAUNCHER_GUEST_PATH = "/root/lxqt6_session.sh"

    private const val LXQT_PID_FILE = "mudif-lxqt6.pid"

    /** Last lines of the detached proot's own output (proot errors
     *  land here, the launcher script redirects its children to
     *  files). Read by diagnostics; never blocks the start. */
    @Volatile
    var lastProotOutput: String = ""
        private set

    /**
     * Set by the drain thread when the detached proot's output
     * stream closes, i.e. the proot (and its launcher script)
     * exited. ensureDirectDesktop uses it to fail fast when the
     * tree will never come up (e.g. startlxqt failed to start),
     * instead of polling the full 30 s.
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
     * filesDir/tmp). A null [comm] skips the name check.
     */
    private fun isPidAlive(pid: Int, comm: String?): Boolean {
        if (pid <= 1) return false
        val stat = try {
            File("/proc/$pid/stat").readText()
        } catch (_: Exception) {
            return false
        }
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

    /** True only when the recorded lxqt-session pid is alive
     *  (cmdline-checked). Host-side only, fast enough for the UI
     *  thread. This is the guard the snapshot/backup dialogs use:
     *  "desktop running" means this pid is alive, NOT whether the
     *  PTY session is alive (GuestSession.alive). The direct
     *  session is a detached setsid proot: GuestSession.stop()
     *  closes the PTY only and never touches it, so a guard that
     *  keys off the PTY would let a backup or restore run under
     *  a live desktop. The confirm action must call
     *  killDirectSession, not just GuestSession.stop(). */
    fun isDirectDesktopAliveHost(context: Context): Boolean {
        val lxqt = readPid(context.applicationContext, LXQT_PID_FILE) ?: return false
        if (isPidAlive(lxqt, "lxqt-session")) return true
        // startlxqt is a shell script: until it execs
        // lxqt-session the pid names the script itself.
        return isPidAlive(lxqt, "startlxqt")
    }

    /**
     * True when the direct :6 session is up: the recorded pid is
     * alive, or (pid file missing or stale) a live lxqt-session
     * plus openbox answer the guest probe. Background threads
     * only: the guest probe is a TerminalSession one-shot.
     */
    fun isDirectDesktopAlive(context: Context): Boolean {
        if (isDirectDesktopAliveHost(context)) return true
        return X11Session.isDesktopSessionAlive(context)
    }

    /**
     * True only where Android's phantom-process killer exists
     * (Android 12, API 31+). The minimal session (no gvfs,
     * at-spi, pulseaudio, xiccd, qlipper) applies ONLY there: it
     * costs in-guest audio, network locations in the file manager,
     * accessibility tools and clipboard history, so a phone without
     * the killer (Anas's Huawei Nova 9, API 30) must never lose
     * them. Moved verbatim from NestedDesktop (take 1).
     */
    fun isMinimalSession(): Boolean = Build.VERSION.SDK_INT >= 31

    /**
     * The :6 resolution: the FULL viewerResolution, no 8 px
     * subtraction. The p123 rule (8 px shorter than :6) existed
     * only because TigerVNC shrank an exact-size window; with
     * TigerVNC gone the desktop takes the whole server.
     */
    private fun directResolution(app: Context): Pair<Int, Int> {
        return try {
            val res = X11Session.viewerResolution(app)
            val parts = res.split("x")
            val w = parts[0].toInt() / 8 * 8
            val h = parts[1].toInt() / 8 * 8
            MudifLog.d(TAG, "directResolution: :6 ${w}x$h")
            w to h
        } catch (_: Exception) {
            848 to 480
        }
    }

    /**
     * Start the detached direct session. Stages the launcher
     * script into the guest (host-side file write), then runs it
     * under setsid + proot with DISPLAY=127.0.0.1:6 and the
     * take-2 GPU variables (design section 12). Returns true
     * when the detached proot started (not when the desktop is
     * ready: ensureDirectDesktop polls for that). Never throws.
     */
    fun startDetached(context: Context): Boolean {
        val app = context.applicationContext
        return try {
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
            val (w, h) = directResolution(app)
            val guestCommand = listOf(
                "/usr/bin/env", "-i",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LD_LIBRARY_PATH=",
                "LD_PRELOAD=",
                "DISPLAY=$DIRECT_DISPLAY",
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
                "MUDIF_MINIMAL_SESSION=" + if (isMinimalSession()) "1" else "0"
                // Take 2 (design section 12): the :6 desktop
                // session carries the GPU variables, so the
                // desktop and its programs render through the
                // same driver as the PTY shell and store launches.
            ) + RootfsManager.gpuEnvFor(rootfs) + listOf(
                "/bin/sh", LAUNCHER_GUEST_PATH, w.toString(), h.toString()
            )
            val (argv, env) = RootfsManager.prootCommandLineFor(
                app, rootfs, DebianSystem.isReady(app), guestCommand
            ) ?: run {
                Log.e(TAG, "startDetached: proot command line unavailable (rootfs not ready)")
                return false
            }
            val setsid = if (File("/system/bin/setsid").exists()) "/system/bin/setsid" else "setsid"
            val pb = ProcessBuilder(listOf(setsid) + argv)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            val process = pb.start()
            detachedProotExited = false
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
     * Make sure the direct :6 session is up. Fast path when it
     * already is. Otherwise kill any stale remnant, wait for the
     * :6 server to answer TCP, wait for the :6 size to settle at
     * viewerResolution, then start detached and poll up to 30 s
     * for the alive check, and finally prove the display really
     * answers with one xdpyinfo one-shot against 127.0.0.1:6.
     *
     * [allowUnsettledStart]: when false (the startDesktop call,
     * before the viewer attached) a size that never settles
     * DEFERS the start (returns false, no session started at the
     * birth size); the post-viewer launch retries with true and
     * starts fail-open with a warning. When true (the
     * post-viewer launch) the session starts anyway after the
     * 10 s size wait, so a slow resize never leaves the user
     * with no desktop at all. Fails fast when the detached
     * proot itself died. Serialized: two concurrent calls must
     * never start two sessions. Never throws.
     */
    fun ensureDirectDesktop(context: Context, allowUnsettledStart: Boolean = false): Boolean {
        val app = context.applicationContext
        synchronized(this) {
            if (isDirectDesktopAlive(app)) return true
            killDirectSession(app)
            if (!X11Session.waitForTcpDisplay(10_000)) {
                MudifLog.d(TAG, "ensureDirectDesktop: :6 TCP never came up")
                return false
            }
            val target = X11Session.viewerResolution(app)
            val settled = X11Session.waitForDisplaySize(app, target, 10_000)
            if (!settled && !allowUnsettledStart) {
                MudifLog.d(TAG, "ensureDirectDesktop: :6 size never settled to $target, deferring to the post-viewer launch")
                return false
            }
            if (!settled) {
                MudifLog.d(TAG, "ensureDirectDesktop: :6 size never settled to $target, starting anyway (fail-open)")
            }
            if (!startDetached(app)) return false
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (isDirectDesktopAlive(app)) break
                if (detachedProotExited) {
                    MudifLog.d(TAG, "ensureDirectDesktop: detached proot exited, failing fast")
                    return false
                }
                Thread.sleep(500)
            }
            if (!isDirectDesktopAlive(app)) {
                MudifLog.d(TAG, "ensureDirectDesktop: :6 session did not come up in 30 s")
                return false
            }
            val xd = TerminalSession.runCommandDebian(
                app, "DISPLAY=127.0.0.1:6 xdpyinfo >/dev/null 2>&1 && echo XD6_OK",
                idleTimeoutSec = 10, maxTimeoutSec = 20
            )
            val ok = xd.output.contains("XD6_OK")
            MudifLog.d(TAG, "ensureDirectDesktop: alive, xdpyinfo ok=$ok")
            return ok
        }
    }

    /**
     * Kill the direct session and start it fresh. Used by the
     * theme restart rule: after ensureLxqtLook re-stages, the
     * running session still holds the old theme files, so only
     * a fresh session picks the new ones up. Never throws.
     */
    fun restartDirectDesktop(context: Context): Boolean {
        val app = context.applicationContext
        synchronized(this) {
            killDirectSession(app)
            return ensureDirectDesktop(app)
        }
    }

    /**
     * Kill the direct :6 session, best-effort. Kills the
     * recorded pid from the host (same UID, same pid namespace
     * under proot), then a DISPLAY-scoped guest one-shot for
     * anything the pid file missed. The scope is
     * DISPLAY=127.0.0.1:6 only: a launchDirect program (qemu /
     * notepad-aot) is never in the pattern, so opening the
     * desktop never kills a running Notepad (slice 14, kept).
     * GuestSession.stop() does NOT do this: it closes the PTY
     * only, and this session is a detached setsid proot, so any
     * caller that needs the desktop really dead (snapshot
     * restore, backup) must call this, not just stop the PTY.
     * Never throws.
     */
    fun killDirectSession(context: Context) {
        val app = context.applicationContext
        val pid = readPid(app, LXQT_PID_FILE)
        if (pid != null && pid > 1) {
            try {
                Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString())).waitFor()
            } catch (_: Exception) {
            }
        }
        try {
            TerminalSession.runCommandDebian(
                app, directKillCommand(), idleTimeoutSec = 10, maxTimeoutSec = 20
            )
        } catch (_: Exception) {
        }
        try {
            File(tmpDir(app), LXQT_PID_FILE).delete()
        } catch (_: Exception) {
        }
        MudifLog.d(TAG, "killDirectSession done")
    }

    /**
     * DISPLAY-scoped kill for the :6 session (mirror of
     * X11Session.scopedDesktopKillCommand, same scope). Kills a
     * pattern match only when its DISPLAY names :6, and spares
     * every other display. The [x]/[l] bracket trick keeps pgrep
     * from matching this one-shot's own shell, and the literal
     * desktop names appear nowhere else in the rendered command.
     */
    private fun directKillCommand(): String {
        val d = "$"
        val pattern = "[l]xqt-session|[l]xqt-panel|[l]xqt-runner|" +
            "[l]xqt-notificationd|[l]xqt-globalkeysd|[p]cmanfm-qt|[q]terminal|" +
            "[o]penbox|[s]tartlxqt|[d]bus-daemon"
        return "for pid in ${d}(pgrep -f '$pattern' 2>/dev/null); do " +
            "disp=${d}(tr '\\000' '\\n' < /proc/${d}pid/environ 2>/dev/null | sed -n 's/^DISPLAY=//p'); " +
            "case \"${d}disp\" in 127.0.0.1:6*|:6*) kill -9 \"${d}pid\" 2>/dev/null ;; esac; " +
            "done; echo DIRECT_CLEANED"
    }

    /**
     * The :6 session bus address, read host-side from the :6
     * lxqt-session's /proc environ (fast, bounded, best-effort).
     * Null when no :6 session is running. Used by the theme
     * switch so the live wallpaper flip reaches the :6 desktop.
     * Runs on the UI thread from stageThemeSwitch, so it never
     * shells out and never blocks on the guest.
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
                if (display == DIRECT_DISPLAY && !bus.isNullOrEmpty()) return bus
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Last 30 lines of the newest :6 session log (diagnostics
     * only). Host-side file reads, never throws.
     */
    fun getDirectDesktopLog(context: Context): String {
        val app = context.applicationContext
        return try {
            val sb = StringBuilder()
            val dir = File(DebianSystem.debianRootfsDir(app), "root")
            val logs = dir.listFiles { f ->
                f.name.startsWith("lxqt6-session-") && f.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() }
            val file = logs?.firstOrNull()
            if (file == null || !file.exists()) {
                sb.append("(lxqt6-session.log not found - :6 never started)\n")
            } else {
                val lines = file.readLines()
                if (lines.isEmpty()) sb.append("(lxqt6-session.log empty)\n")
                else sb.append(lines.takeLast(30).joinToString("\n")).append("\n")
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
