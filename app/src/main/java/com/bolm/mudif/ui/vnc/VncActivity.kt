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

        // Version indicator - P5.4 professional rebuild
        Toast.makeText(this, "نسخة: P5.4-ProotSession", Toast.LENGTH_LONG).show()

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

            // DIAGNOSTIC: Check if openbox is running and show log
            val isRunning = X11Session.isOpenboxRunning(this)
            val log = X11Session.getOpenboxLog(this)
            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("تشخيص openbox")
                    .setMessage("يشتغل: $isRunning\n\nاللوگ:\n$log")
                    .setPositiveButton("فتح العارض", { _, _ ->
                        X11Session.openViewer(this)
                    })
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
