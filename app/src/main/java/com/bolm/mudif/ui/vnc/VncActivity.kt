package com.bolm.mudif.ui.vnc

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.databinding.ActivityVncBinding
import com.bolm.mudif.engine.ContainerSession
import com.bolm.mudif.engine.DesktopDiagnostics
import com.bolm.mudif.engine.X11Session
import com.bolm.mudif.ui.logs.LogViewerActivity

/**
 * P5.8 REBUILD — X11 desktop via Termux:X11, Tiny Container's flow exactly.
 *
 * Tiny (`ContainerMainViewModel`, x11 feature):
 *  1. Boot the container in the persistent PTY session.
 *  2. `launchXServer(args)` (CmdEntryPointService ACTION_START).
 *  3. `waitForFile(.../.X11-unix/X0)` — 15 s, exists() only.
 *  4. `Global.sendCommand(command)` — the GUI command into the PTY.
 *  5. Open `com.termux.x11.MainActivity`.
 *
 * No direct-Process openbox anymore (P5.5), no pipe shell anymore (P5.4),
 * no LocalSocket test (P5.6 — Tiny does not do it).
 *
 * The activity also shows a live status line and a "عرض اللوجات" button
 * (the detailed-log button the user ordered), and on X failure it opens
 * the log viewer instead of dying silently.
 */
class VncActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVncBinding

    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "سطح مكتب لينكس"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnShowLogs.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        // Version indicator — P5.15 (verifiable by evidence).
        Toast.makeText(this, "نسخة: P5.15", Toast.LENGTH_LONG).show()

        startDesktop()
    }

    private fun setStatus(text: String) {
        if (destroyed) return
        runOnUiThread { binding.statusText.text = text }
    }

    private fun startDesktop() {
        Thread {
            // 1. Boot the container in the persistent PTY session (Tiny).
            setStatus("جاري تشغيل الحاوية…")
            if (!ContainerSession.bootContainer(this)) {
                if (destroyed) return@Thread
                runOnUiThread {
                    Toast.makeText(this, "فشل تشغيل الحاوية", Toast.LENGTH_LONG).show()
                    showFailureDialog("تعذّر تشغيل حاوية Alpine داخل جلسة PTY.")
                }
                return@Thread
            }

            // 2. Start the X server (Tiny launchXServer) — but ONLY if it
            // is not already up. P5.11: the server now SURVIVES leaving
            // this screen (onDestroy no longer stops it), so re-entering
            // must reuse the running server instead of starting a second
            // one. Judge by the real state (socket + TCP listener), not
            // by the in-memory flag, which can go stale if the server
            // died on its own.
            val serverUp = X11Session.isXSocketPresent(this) && X11Session.isTcpDisplayOpen()
            if (!serverUp) {
                setStatus("جاري تشغيل خادم X…")
                X11Session.startXServer(this)

                // 3. Wait ONLY for the socket file (Tiny waitForFile).
                if (!X11Session.waitForXSocket(this, 15000)) {
                    if (destroyed) return@Thread
                    runOnUiThread {
                        Toast.makeText(this, "فشل بدء سيرفر X", Toast.LENGTH_LONG).show()
                        showFailureDialog("خادم X ما قلعش: سوكيت X0 ما تخلقش خلال 15 ثانية.")
                    }
                    return@Thread
                }

                // 3b. P5.10: wait until the server accepts TCP on 127.0.0.1:6000.
                // The socket file can exist before the server is ready, and the
                // Unix socket connect from inside proot is broken on this phone,
                // so the guest uses TCP — prove the listener is there first.
                setStatus("جاري انتظار منفذ TCP…")
                if (!X11Session.waitForTcpDisplay(10000)) {
                    if (destroyed) return@Thread
                    runOnUiThread {
                        Toast.makeText(this, "فشل بدء سيرفر X", Toast.LENGTH_LONG).show()
                        showFailureDialog("خادم X ما فتحش منفذ TCP 6000 خلال 10 ثواني (السوكيت موجود ولكن TCP مسدود).")
                    }
                    return@Thread
                }
            }

            // 4. Send the GUI command through the PTY session (Tiny) —
            // only if openbox is not already running. Re-entering this
            // screen must not start a second window manager on the same
            // display.
            setStatus("جاري تشغيل openbox…")
            if (!X11Session.isOpenboxRunning(this)) {
                X11Session.launchGuiApp(this)
                Thread.sleep(2000)
            }
            val isRunning = X11Session.isOpenboxRunning(this)
            val log = X11Session.getOpenboxLog(this)

            // 4b. P5.13: launch xterm through the SAME persistent PTY
            // session (not the Terminal tab — its one-shot proot kills
            // any backgrounded GUI app the moment the command returns).
            // Only if xterm is not already running.
            // P5.14: install fontconfig + DejaVu first (best-effort),
            // because the P5.13 xterm.log proved the core font is
            // missing and the pty open needs a fresh session.
            setStatus("جاري تثبيت خطوط xterm…")
            var fontsOk = true
            if (!X11Session.isXtermRunning(this)) {
                fontsOk = X11Session.ensureXtermFonts(this)
            }
            setStatus("جاري تشغيل xterm…")
            if (!X11Session.isXtermRunning(this)) {
                X11Session.launchXterm(this)
                Thread.sleep(2000)
            }
            val xtermRunning = X11Session.isXtermRunning(this)
            val xtermLog = X11Session.getXtermLog(this)

            // 5. Diagnostic dialog (H1 result + openbox state + log).
            if (destroyed) return@Thread
            runOnUiThread {
                setStatus(if (isRunning) "سطح المكتب خدام ✓" else "openbox ما خدامش — شوف اللوجات")
                AlertDialog.Builder(this)
                    .setTitle("تشخيص P5.15")
                    .setMessage(
                        "openbox يشتغل: $isRunning\n" +
                            "xterm يشتغل: $xtermRunning\n" +
                            "الخطوط تثبتات: $fontsOk\n\n" +
                            "لوگ xterm:\n$xtermLog\n\n" +
                            "اللوگ:\n$log\n\n" +
                            "--- الفرضية 1 (Phantom Killer) ---\n" +
                            DesktopDiagnostics.hypothesis1()
                    )
                    .setPositiveButton("فتح العارض") { _, _ ->
                        X11Session.openViewer(this)
                    }
                    .setNeutralButton("اللوجات المفصلة") { _, _ ->
                        startActivity(Intent(this, LogViewerActivity::class.java))
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }.start()
    }

    /** Shown when the container or the X server fails to start. */
    private fun showFailureDialog(reason: String) {
        if (destroyed) return
        AlertDialog.Builder(this)
            .setTitle("فشل بدء سطح المكتب")
            .setMessage(
                "$reason\n\nالسبب الحقيقي كاين في اللوجات " +
                    "(logcat ديال أندرويد + حالة الخادم)."
            )
            .setPositiveButton("عرض اللوجات") { _, _ ->
                startActivity(Intent(this, LogViewerActivity::class.java))
            }
            .setNeutralButton("إعادة المحاولة") { _, _ ->
                startDesktop()
            }
            .setNegativeButton("إغلاق") { _, _ -> finish() }
            .show()
    }

    override fun onDestroy() {
        destroyed = true
        // P5.11: do NOT stop the X server or the container session here.
        // Leaving this screen (back button / switching to the terminal
        // tab) used to kill the server under the user's feet: at 01:21
        // openbox was running, the user left the screen, the server was
        // stopped, and xterm at 01:32 had no server to connect to
        // ("Can't open display"). The desktop now persists like Tiny
        // Container's: it lives until the app process dies.
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
