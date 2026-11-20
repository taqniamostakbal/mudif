package com.bolm.mudif.engine

import android.content.Context
import com.bolm.mudif.R
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P5.22, shared desktop diagnostics. One place that assembles the FULL
 * detailed log the user ordered: Hypothesis-1, X server state, PTY
 * container session state, desktop state + log, and filtered logcat.
 *
 * Used by both the VncActivity dialog and the LogViewerActivity, so the
 * two never drift apart.
 */
object DesktopDiagnostics {

    /**
     * Hypothesis 1: Android 12+ Phantom Process Killer.
     * Reports the API level and tries to read the phantom-monitor setting
     * (usually unreadable without a privileged shell, the attempt itself
     * is reported, never hidden).
     */
    /**
     * P5.23: the "Phantom Killer" hypothesis is DEAD. The real cause
     * was proven 2026-11-06 from the P5.21 APK's dex strings: the
     * isLxqtRunning check sent `kill -0 \$(cat ...)`, a literal
     * backslash before $(, a shell syntax error that killed the
     * guest shell and wedged the session. Phantom Killer was never
     * the cause. This section now states the proven finding.
     */
    fun hypothesis1(context: Context): String {
        val sb = StringBuilder()
        sb.append("Android API: ${Build.VERSION.SDK_INT}\n")
        sb.append("Android: ${Build.VERSION.RELEASE}\n")
        sb.append(context.getString(R.string.ui_diag_cause_title) + "\n")
        sb.append(context.getString(R.string.ui_diag_cause_pixbuf) + "\n")
        sb.append(context.getString(R.string.ui_diag_cause_fix) + "\n")
        return sb.toString()
    }

    /**
     * P6, نظام مضيف (Debian 13.1): the P5.25 pixbuf failure mode
     * does not exist here. Debian's gdk-pixbuf ships native PNG/JPEG
     * loaders; glycin is not in the LXQt install closure and bwrap
     * is never invoked. The desktop installs with apt
     * (--no-install-recommends) and launches host-side, never nested
     * (nested proot fails: the Android linker is missing inside the
     * guest). Proven live 2026-11-06 (diagnostic Q3/Q4).
     * P6.4, the desktop is LXQt now (XFCE stopped by the user's
     * order 2026-11-07), Deepin-style: navy wallpaper, bloom-dark
     * icons, dark panel, Adwaita-dark Qt style. Kvantum is NOT
     * installed: no kvantum package exists in Debian trixie.
     * P6.5, the 2026-11-07 freeze is proven: proot's --shm-helper
     * spins forever on a missing Termux tmp path (STRACE-P65); the
     * fix is removing --sysvipc, and the desktop now launches after
     * the viewer attaches at the settled 1280x720.
     */
    fun hypothesisDebian(context: Context): String {
        val sb = StringBuilder()
        sb.append("Android API: ${Build.VERSION.SDK_INT}\n")
        sb.append("Android: ${Build.VERSION.RELEASE}\n")
        sb.append(context.getString(R.string.ui_diag_system_note) + "\n")
        sb.append(context.getString(R.string.ui_diag_system_line1) + "\n")
        sb.append(context.getString(R.string.ui_diag_system_line2) + "\n")
        sb.append(context.getString(R.string.ui_diag_p67) + "\n")
        sb.append(context.getString(R.string.ui_diag_p8) + "\n")
        return sb.toString()
    }

    /**
     * P5.9: the old filter (`ActivityManager:I DEBUG:I *:S`) SILENCED every
     * tag except ActivityManager/DEBUG, the X server's own log
     * (Termux:X11 / CmdEntryPointService / Xlorie tags) never reached the
     * output, which is why P5.8 showed "(no relevant entries)" while the
     * server silently failed. Now: last 800 lines, ALL tags, then filter.
     */
    fun collectLogcat(maxLines: Int = 100): String {
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "800", "-v", "brief")
            )
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val relevant = out.lines().filter {
                it.contains("phantom", ignoreCase = true) ||
                    it.contains("kill", ignoreCase = true) ||
                    it.contains("x11", ignoreCase = true) ||
                    it.contains("CmdEntry", ignoreCase = true) ||
                    it.contains("xserver", ignoreCase = true) ||
                    it.contains("signal", ignoreCase = true) ||
                    it.contains("xlorie", ignoreCase = true) ||
                    it.contains("lorie", ignoreCase = true) ||
                    it.contains("termux", ignoreCase = true) ||
                    it.contains("FATAL", ignoreCase = true) ||
                    it.contains("crash", ignoreCase = true)
            }.takeLast(maxLines)
            if (relevant.isEmpty()) "(no relevant entries)\n"
            else relevant.joinToString("\n") + "\n"
        } catch (e: Exception) {
            "logcat failed: ${e.message}\n"
        }
    }

    /**
     * P5.9: is the X server process alive on the Android side?
     * `ps` shows the app's own processes (the X server is a child of
     * this app's process, forked by CmdEntryPointService), so it should
     * be visible here if it exists at all.
     */
    fun xServerProcess(): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("ps", "-A"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val hits = out.lines().filter {
                it.contains("xserver", ignoreCase = true) ||
                    it.contains("xlorie", ignoreCase = true) ||
                    it.contains("lorie", ignoreCase = true) ||
                    it.contains("CmdEntry", ignoreCase = true)
            }
            if (hits.isEmpty()) "(no X server process found in ps output)\n"
            else hits.joinToString("\n") + "\n"
        } catch (e: Exception) {
            "ps failed: ${e.message}\n"
        }
    }

    /**
     * P16.5, are apt-get/dpkg processes alive on the host side?
     * An orphaned apt-get holds the dpkg lock and blocks every
     * install (proven on the Nova 9, P16.3: PID 11794). The old
     * xServerProcess() filter hides them; this one shows them.
     */
    fun aptProcesses(): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("ps", "-A"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val hits = out.lines().filter {
                it.contains("apt-get", ignoreCase = true) ||
                    it.contains("dpkg", ignoreCase = true)
            }
            if (hits.isEmpty()) "(no apt-get/dpkg process)\n"
            else hits.joinToString("\n") + "\n"
        } catch (e: Exception) {
            "ps failed: ${e.message}\n"
        }
    }

    /**
     * P16.5, the last desktop-install attempt, as recorded by
     * X11Session.ensureDebianDesktop. This is the evidence the P16.4
     * diagnostics lacked: WHY the install failed (exit code, timeout,
     * last output) and whether the dpkg lock files exist.
     */
    fun installState(context: Context): String {
        val sb = StringBuilder()
        val r = X11Session.lastInstallResult
        if (r == null) {
            sb.append("no install attempted yet in this process\n")
        } else {
            sb.append("exitCode: ${r.exitCode}\n")
            sb.append("timedOut: ${r.timedOut}\n")
            sb.append("output (last 500):\n")
            sb.append(r.output.takeLast(500))
            sb.append("\n")
        }
        try {
            val rootfs = DebianSystem.debianRootfsDir(context)
            val lockFrontend = File(rootfs, "var/lib/dpkg/lock-frontend")
            val lock = File(rootfs, "var/lib/dpkg/lock")
            sb.append("lock-frontend exists: ${lockFrontend.exists()}\n")
            sb.append("lock exists: ${lock.exists()}\n")
        } catch (e: Exception) {
            sb.append("lock check failed: ${e.message}\n")
        }
        return sb.toString()
    }

    /**
     * P5.9: what exactly was handed to the X server at launch?
     * A blank XKB_CONFIG_ROOT or a stale .X6-lock would both stop the
     * server before it creates its socket.
     */
    fun xServerInputs(context: Context): String {
        val sb = StringBuilder()
        try {
            val tmpDir = File(context.filesDir, "tmp")
            sb.append("TMPDIR: ${tmpDir.absolutePath}\n")
            // P16: the X server runs on the DEBIAN rootfs, checking the
            // old Alpine rootfs here reported "xkb dir exists: true"
            // while the Debian one had no xkb dir (the Nova 9 miss).
            val xkb = File(DebianSystem.debianRootfsDir(context), "usr/share/X11/xkb")
            sb.append("xkb dir exists: ${xkb.exists()}\n")
            if (!xkb.exists()) {
                sb.append("XKB_CONFIG_ROOT was blank at launch\n")
            }
            val lock = File(tmpDir, ".X6-lock")
            sb.append("stale .X6-lock: ${lock.exists()}\n")
            sb.append("package: ${context.applicationContext.packageName}\n")
            sb.append("waitForXSocket result: ${X11Session.lastWaitResult}\n")
        } catch (e: Exception) {
            sb.append("inputs check failed: ${e.message}\n")
        }
        return sb.toString()
    }

    /** X server state: started flag + socket file + /tmp listing. */
    fun xServerState(context: Context): String {
        val sb = StringBuilder()
        sb.append("xserverStarted: ${X11Session.xserverRunning}\n")
        sb.append("socket X6 exists: ${X11Session.isXSocketPresent(context)}\n")
        sb.append("tcp 6006 open: ${X11Session.isTcpDisplayOpen()}\n")
        try {
            val unixDir = File(context.filesDir, "tmp/.X11-unix")
            sb.append(".X11-unix: ")
            if (unixDir.exists()) {
                sb.append(unixDir.list()?.joinToString(", ") ?: "(empty)")
                sb.append("\n")
            } else {
                sb.append("(directory missing)\n")
            }
            val tmpDir = File(context.filesDir, "tmp")
            sb.append("filesDir/tmp: ")
            sb.append(tmpDir.list()?.joinToString(", ") ?: "(empty)")
            sb.append("\n")
        } catch (e: Exception) {
            sb.append("listing failed: ${e.message}\n")
        }
        return sb.toString()
    }

    /** Container session state (P5.22: single PTY session, Tiny Container). */
    fun containerState(): String {
        val sb = StringBuilder()
        sb.append("PTY session alive: ${GuestSession.alive}\n")
        sb.append("guest booted: ${GuestSession.booted}\n")
        sb.append("PTY probe: ${GuestSession.ptyProbeOk}\n")
        sb.append("lastExitCode: ${GuestSession.lastExitCode}")
        if (GuestSession.lastExitCode == -9) {
            sb.append(" (SIGKILL, killed by the system)")
        }
        sb.append("\n")
        val err = GuestSession.lastError
        if (err != null) {
            sb.append("lastError: $err\n")
        }
        return sb.toString()
    }

    /**
     * Full snapshot: everything in one text, in a fixed order, with a
     * timestamp. This is what the log viewer shows and what "نسخ" copies.
     * The desktop running-check is done by the CALLER and passed in as
     * [desktopRunning], null means "not checked".
     */
    fun fullSnapshot(context: Context, desktopRunning: Boolean?): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            append(context.getString(R.string.ui_diag_snapshot_title, LxqtLook.MARKER_VERSION) + "\n")
            append(context.getString(R.string.ui_diag_time, time) + "\n")
            append(context.getString(R.string.ui_diag_version, LxqtLook.MARKER_VERSION) + "\n\n")
            append(context.getString(R.string.ui_diag_cause_header) + "\n")
            append(hypothesis1(context))
            append("\n" + context.getString(R.string.ui_diag_x_state) + "\n")
            append(xServerState(context))
            append("\n" + context.getString(R.string.ui_diag_x_inputs) + "\n")
            append(xServerInputs(context))
            append("\n" + context.getString(R.string.ui_diag_x_ps) + "\n")
            append(xServerProcess())
            append("\n" + context.getString(R.string.ui_diag_pty_state) + "\n")
            append(containerState())
            append("\n" + context.getString(R.string.ui_diag_desktop) + "\n")
            append(
                when (desktopRunning) {
                    true -> context.getString(R.string.ui_diag_running_yes) + "\n"
                    false -> context.getString(R.string.ui_diag_running_no) + "\n"
                    null -> context.getString(R.string.ui_diag_running_unknown) + "\n"
                }
            )
            append(X11Session.getDesktopLog(context, "lxqt"))
            // p121 (Option B): the desktop lives on :7 now, so its
            // session log is the nested one. Appended after the :6
            // log, which stays for the legacy fallback path.
            append("\n\n--- nested desktop :7 ---\n")
            append(NestedDesktop.getNestedDesktopLog(context))
            append("\n\n" + context.getString(R.string.ui_diag_apt_state) + "\n")
            append(installState(context))
            append("\n" + context.getString(R.string.ui_diag_apt_ps) + "\n")
            append(aptProcesses())
            append("\n--- logcat (phantom/kill/X11/CmdEntry) ---\n")
            append(collectLogcat())
        }
    }
}
