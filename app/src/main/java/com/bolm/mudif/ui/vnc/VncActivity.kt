package com.bolm.mudif.ui.vnc

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.databinding.ActivityVncBinding
import com.bolm.mudif.engine.X11Session

/**
 * P5.3: X11 desktop via Termux:X11 (native Android X server).
 * EXACT Tiny Container approach - no VNC, no Xvfb.
 */
class VncActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVncBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "سطح مكتب لينكس"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.vncView.visibility = android.view.View.GONE

        // Version indicator - P5.7 diagnostic (H1 test)
        Toast.makeText(this, "نسخة: P5.7-Diag", Toast.LENGTH_LONG).show()

        startDesktop()
    }

    private fun startDesktop() {
        Thread {
            runOnUiThread {
                Toast.makeText(this, "بدء تشغيل سطح المكتب...", Toast.LENGTH_SHORT).show()
            }

            if (!X11Session.xserverRunning) {
                X11Session.startXServer(this)
            }

            if (!X11Session.waitForXSocket(this, 20000)) {
                runOnUiThread {
                    Toast.makeText(this, "فشل بدء سيرفر X", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@Thread
            }

            // Launch openbox DIRECTLY (no shell wrapper) - X11Session keeps Process alive
            X11Session.launchGuiApp(this)

            // Give openbox time to connect
            Thread.sleep(3000)

            // DIAGNOSTIC: Check if openbox is running and show log + H1 test
            val isRunning = X11Session.isOpenboxRunning(this)
            val log = X11Session.getOpenboxLog(this)
            val h1Result = testHypothesis1()
            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("تشخيص P5.7")
                    .setMessage("openbox يشتغل: $isRunning\n\nاللوگ:\n$log\n\n--- الفرضية 1 (Phantom Killer) ---\n$h1Result")
                    .setPositiveButton("فتح العارض", { _, _ ->
                        X11Session.openViewer(this)
                    })
                    .setNeutralButton("جمع لوگات", { _, _ ->
                        collectAndShowLogs()
                    })
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }.start()
    }

    /**
     * Hypothesis 1: Android 12+ Phantom Process Killer
     * Returns diagnostic info about phantom process status
     */
    private fun testHypothesis1(): String {
        val sb = StringBuilder()
        sb.append("Android API: ${android.os.Build.VERSION.SDK_INT}\n")
        sb.append("Android: ${android.os.Build.VERSION.RELEASE}\n")
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            sb.append("⚠ Android 12+ : Phantom Killer محتمل\n")
        } else {
            sb.append("✓ Android < 12: Phantom Killer غير موجود\n")
        }
        // Try to read phantom settings (likely fails without permission)
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("settings", "get", "global", "settings_enable_monitor_phantom_procs"))
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            sb.append("monitor_phantom_procs: $out\n")
        } catch (e: Exception) {
            sb.append("لا يمكن قراءة الإعدادات: ${e.message}\n")
        }
        return sb.toString()
    }

    /**
     * Collect logcat and X server related logs
     */
    private fun collectAndShowLogs() {
        Thread {
            val logs = StringBuilder()
            logs.append("=== Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) ===\n\n")
            try {
                // Get logcat for relevant tags
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "brief",
                    "ActivityManager:I", "DEBUG:I", "*:S"))
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                // Filter for phantom, kill, X11, xserver
                val relevant = out.lines().filter {
                    it.contains("phantom", ignoreCase = true) ||
                    it.contains("Phantom", ignoreCase = true) ||
                    it.contains("kill", ignoreCase = true) ||
                    it.contains("x11", ignoreCase = true) ||
                    it.contains("X11", ignoreCase = true) ||
                    it.contains("CmdEntry", ignoreCase = true)
                }.takeLast(50)
                logs.append("=== Logcat (relevant) ===\n")
                if (relevant.isEmpty()) {
                    logs.append("(no relevant entries)\n")
                } else {
                    relevant.forEach { logs.append("$it\n") }
                }
            } catch (e: Exception) {
                logs.append("logcat failed: ${e.message}\n")
            }
            val logText = logs.toString()
            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("اللوگات المجمعة")
                    .setMessage(logText.take(3000))
                    .setPositiveButton("حسنا", null)
                    .show()
            }
        }.start()
    }
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
