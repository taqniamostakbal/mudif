package com.bolm.mudif.engine

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P5.8 — shared desktop diagnostics. One place that assembles the FULL
 * detailed log the user ordered: Hypothesis-1, X server state, container
 * session state, openbox state + log, and filtered logcat.
 *
 * Used by both the VncActivity dialog and the LogViewerActivity, so the
 * two never drift apart.
 */
object DesktopDiagnostics {

    /**
     * Hypothesis 1: Android 12+ Phantom Process Killer.
     * Reports the API level and tries to read the phantom-monitor setting
     * (usually unreadable without a privileged shell — the attempt itself
     * is reported, never hidden).
     */
    fun hypothesis1(): String {
        val sb = StringBuilder()
        sb.append("Android API: ${Build.VERSION.SDK_INT}\n")
        sb.append("Android: ${Build.VERSION.RELEASE}\n")
        if (Build.VERSION.SDK_INT >= 31) {
            sb.append("Android 12+ : Phantom Killer محتمل\n")
        } else {
            sb.append("Android < 12: Phantom Killer غير موجود\n")
        }
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf(
                    "settings", "get", "global",
                    "settings_enable_monitor_phantom_procs"
                )
            )
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            sb.append("monitor_phantom_procs: $out\n")
        } catch (e: Exception) {
            sb.append("لا يمكن قراءة الإعدادات: ${e.message}\n")
        }
        return sb.toString()
    }

    /**
     * P5.9: the old filter (`ActivityManager:I DEBUG:I *:S`) SILENCED every
     * tag except ActivityManager/DEBUG — the X server's own log
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
     * P5.9: what exactly was handed to the X server at launch?
     * A blank XKB_CONFIG_ROOT or a stale .X0-lock would both stop the
     * server before it creates its socket.
     */
    fun xServerInputs(context: Context): String {
        val sb = StringBuilder()
        try {
            val tmpDir = File(context.filesDir, "tmp")
            sb.append("TMPDIR: ${tmpDir.absolutePath}\n")
            val xkb = File(RootfsManager.rootfsDir(context), "usr/share/X11/xkb")
            sb.append("xkb dir exists: ${xkb.exists()}\n")
            if (!xkb.exists()) {
                sb.append("XKB_CONFIG_ROOT was blank at launch\n")
            }
            val lock = File(tmpDir, ".X0-lock")
            sb.append("stale .X0-lock: ${lock.exists()}\n")
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
        sb.append("socket X0 exists: ${X11Session.isXSocketPresent(context)}\n")
        sb.append("tcp 6000 open: ${X11Session.isTcpDisplayOpen()}\n")
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

    /** Container session state (P5.16: persistent proot shell, no PTY). */
    fun containerState(): String {
        val sb = StringBuilder()
        sb.append("session alive: ${ContainerSession.alive}\n")
        sb.append("guest booted: ${ContainerSession.booted}\n")
        sb.append("lastExitCode: ${ContainerSession.lastExitCode}")
        if (ContainerSession.lastExitCode == -9) {
            sb.append(" (SIGKILL — killed by the system)")
        }
        sb.append("\n")
        val err = ContainerSession.lastError
        if (err != null) {
            sb.append("lastError: $err\n")
        }
        return sb.toString()
    }

    /**
     * Full snapshot: everything in one text, in a fixed order, with a
     * timestamp. This is what the log viewer shows and what "نسخ" copies.
     * The openbox running-check is done by the CALLER (it needs a few
     * seconds and the PTY) and passed in as [openboxRunning] — null means
     * "not checked".
     */
    fun fullSnapshot(context: Context, openboxRunning: Boolean?): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            append("=== مضيف P5.21 — سجل مفصل ===\n")
            append("الوقت: $time\n")
            append("نسخة: P5.21\n\n")
            append("--- الفرضية 1 (Phantom Killer) ---\n")
            append(hypothesis1())
            append("\n--- حالة خادم X ---\n")
            append(xServerState(context))
            append("\n--- مدخلات خادم X ---\n")
            append(xServerInputs(context))
            append("\n--- عملية خادم X (ps) ---\n")
            append(xServerProcess())
            append("\n--- حالة جلسة الحاوية (proot) ---\n")
            append(containerState())
            append("\n--- openbox ---\n")
            append(
                when (openboxRunning) {
                    true -> "يشتغل: نعم\n"
                    false -> "يشتغل: لا\n"
                    null -> "يشتغل: (لم يتم الفحص)\n"
                }
            )
            append(X11Session.getOpenboxLog(context))
            append("\n\n--- logcat (phantom/kill/X11/CmdEntry) ---\n")
            append(collectLogcat())
        }
    }
}
