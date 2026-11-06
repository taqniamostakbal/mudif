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

        // Version indicator — P5.9 diagnostics (verifiable by evidence).
        Toast.makeText(this, "نسخة: P5.9-Diag", Toast.LENGTH_LONG).show()

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

            // 2. Start the X server (Tiny launchXServer).
            setStatus("جاري تشغيل خادم X…")
            if (!X11Session.xserverRunning) {
                X11Session.startXServer(this)
            }

            // 3. Wait ONLY for the socket file (Tiny waitForFile).
            if (!X11Session.waitForXSocket(this, 15000)) {
                if (destroyed) return@Thread
                runOnUiThread {
                    Toast.makeText(this, "فشل بدء سيرفر X", Toast.LENGTH_LONG).show()
                    showFailureDialog("خادم X ما قلعش: سوكيت X0 ما تخلقش خلال 15 ثانية.")
                }
                return@Thread
            }

            // 4. Send the GUI command through the PTY session (Tiny).
            setStatus("جاري تشغيل openbox…")
            X11Session.launchGuiApp(this)
            Thread.sleep(2000)
            val isRunning = X11Session.isOpenboxRunning(this)
            val log = X11Session.getOpenboxLog(this)

            // 5. Diagnostic dialog (H1 result + openbox state + log).
            if (destroyed) return@Thread
            runOnUiThread {
                setStatus(if (isRunning) "سطح المكتب خدام ✓" else "openbox ما خدامش — شوف اللوجات")
                AlertDialog.Builder(this)
                    .setTitle("تشخيص P5.9")
                    .setMessage(
                        "openbox يشتغل: $isRunning\n\nاللوگ:\n$log\n\n" +
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
        // Tiny's onCleared/exitContainer: stop the X server + container
        // session only when this screen is really going away (not when the
        // X11 viewer just moved to the front — then we are only stopped).
        if (isFinishing) {
            try {
                X11Session.stopXServer(this)
            } catch (_: Exception) {
            }
            try {
                ContainerSession.stop()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
