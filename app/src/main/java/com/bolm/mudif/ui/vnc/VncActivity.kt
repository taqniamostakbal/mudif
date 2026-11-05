package com.bolm.mudif.ui.vnc

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.databinding.ActivityVncBinding
import com.bolm.mudif.engine.X11Session

/**
 * P5.3: X11 desktop via Termux:X11 (native Android X server).
 *
 * Replaces the VNC approach (Xvfb+x11vnc). Flow:
 * 1. Start Termux:X11 X server (native)
 * 2. Wait for X socket
 * 3. Launch openbox in proot with DISPLAY=:0
 * 4. Open Termux:X11 viewer
 */
class VncActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVncBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "سطح مكتب لينكس"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Hide the VNC view (not used in X11 mode)
        binding.vncView.visibility = android.view.View.GONE

        startDesktop()
    }

    private fun startDesktop() {
        Thread {
            runOnUiThread {
                Toast.makeText(this, "بدء تشغيل سطح المكتب...", Toast.LENGTH_SHORT).show()
            }

            // 1. Start X server (if not already running)
            if (!X11Session.xserverRunning) {
                X11Session.startXServer(this)
            }

            // 2. Wait for X socket
            if (!X11Session.waitForXSocket(this, 20000)) {
                runOnUiThread {
                    Toast.makeText(this, "فشل بدء سيرفر X", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@Thread
            }

            // 3. Launch openbox in proot (connects via DISPLAY=:0)
            // Use a persistent process so openbox stays alive
            Thread {
                X11Session.launchGuiApp(this, listOf("/bin/sh", "-c",
                    "DISPLAY=:0 openbox > /root/openbox.log 2>&1"))
            }.start()

            Thread.sleep(2000)

            // 4. Open the viewer
            runOnUiThread {
                Toast.makeText(this, "فتح سطح المكتب...", Toast.LENGTH_SHORT).show()
                X11Session.openViewer(this)
                finish() // Close this activity, viewer takes over
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
