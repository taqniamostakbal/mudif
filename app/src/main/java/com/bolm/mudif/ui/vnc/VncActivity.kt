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

        // Version indicator
        Toast.makeText(this, "نسخة: 20261105-0320-X11", Toast.LENGTH_LONG).show()

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

            // Launch openbox in proot (connects via DISPLAY=:0 to native X server)
            Thread {
                X11Session.launchGuiApp(this, listOf("/bin/sh", "-c",
                    "DISPLAY=:0 openbox > /root/openbox.log 2>&1"))
            }.start()

            Thread.sleep(2000)

            runOnUiThread {
                Toast.makeText(this, "فتح سطح المكتب...", Toast.LENGTH_SHORT).show()
                X11Session.openViewer(this)
                finish()
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
