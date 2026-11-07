package com.bolm.mudif.engine

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P5.22 — shared desktop diagnostics. One place that assembles the FULL
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
     * (usually unreadable without a privileged shell — the attempt itself
     * is reported, never hidden).
     */
    /**
     * P5.23: the "Phantom Killer" hypothesis is DEAD. The real cause
     * was proven 2026-11-06 from the P5.21 APK's dex strings: the
     * isLxqtRunning check sent `kill -0 \$(cat ...)` — a literal
     * backslash before $( — a shell syntax error that killed the
     * guest shell and wedged the session. Phantom Killer was never
     * the cause. This section now states the proven finding.
     */
    fun hypothesis1(): String {
        val sb = StringBuilder()
        sb.append("Android API: ${Build.VERSION.SDK_INT}\n")
        sb.append("Android: ${Build.VERSION.RELEASE}\n")
        sb.append("السبب الحقيقي (مثبت 2026-11-06 من xfce.log + فحص الضيف):\n")
        sb.append("gdk-pixbuf ما عندوش محمل PNG أصلي (غير XPM)، و glycin كيستعمل bwrap اللي ما كيخدمش تحت proot.\n")
        sb.append("الحل P5.25: bwrap shim + Adwaita.\n")
        return sb.toString()
    }

    /**
     * P6 — نظام مضيف (Debian 13.1): the P5.25 pixbuf failure mode
     * does not exist here. Debian's gdk-pixbuf ships native PNG/JPEG
     * loaders; glycin is not in the XFCE install closure and bwrap
     * is never invoked. The desktop installs with apt
     * (--no-install-recommends) and launches host-side, never nested
     * (nested proot fails: the Android linker is missing inside the
     * guest). Proven live 2026-11-06 (diagnostic Q3/Q4).
     */
    fun hypothesisDebian(): String {
        val sb = StringBuilder()
        sb.append("Android API: ${Build.VERSION.SDK_INT}\n")
        sb.append("Android: ${Build.VERSION.RELEASE}\n")
        sb.append("نظام مضيف (مثبت 2026-11-06 من التشخيص):\n")
        sb.append("دبيان 13.1، XFCE كيقلع، الأيقونات أصلية، بلا glycin وبلا bwrap.\n")
        sb.append("التثبيت بـ apt (--no-install-recommends)، والإقلاع من جهة المضيف، ماشي متداخل.\n")
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
     * A blank XKB_CONFIG_ROOT or a stale .X6-lock would both stop the
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
            sb.append(" (SIGKILL — killed by the system)")
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
     * [desktopRunning] — null means "not checked".
     */
    fun fullSnapshot(context: Context, desktopRunning: Boolean?): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            append("=== مضيف P6 — سجل مفصل ===\n")
            append("الوقت: $time\n")
            append("نسخة: P6.3.2\n\n")
            append("--- السبب الحقيقي (تشخيص P5.22) ---\n")
            append(hypothesis1())
            append("\n--- حالة خادم X ---\n")
            append(xServerState(context))
            append("\n--- مدخلات خادم X ---\n")
            append(xServerInputs(context))
            append("\n--- عملية خادم X (ps) ---\n")
            append(xServerProcess())
            append("\n--- حالة جلسة الحاوية (PTY) ---\n")
            append(containerState())
            append("\n--- سطح المكتب (LXQt) ---\n")
            append(
                when (desktopRunning) {
                    true -> "يشتغل: نعم\n"
                    false -> "يشتغل: لا\n"
                    null -> "يشتغل: (لم يتم الفحص)\n"
                }
            )
            append(X11Session.getDesktopLog(context, "lxqt"))
            append("\n\n--- logcat (phantom/kill/X11/CmdEntry) ---\n")
            append(collectLogcat())
        }
    }
}
