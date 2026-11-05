package com.bolm.mudif.ui.vnc

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.databinding.ActivityVncBinding
import com.bolm.mudif.engine.GuiSession
import com.bolm.mudif.engine.vnc.RfbClient

/**
 * P5.2: VNC viewer for the Linux desktop.
 * Connects to x11vnc on localhost:5900.
 */
class VncActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVncBinding
    private var rfb: RfbClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "سطح مكتب لينكس"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        connect()
    }

    private fun connect() {
        Thread {
            // Auto-start GUI stack if not running
            runOnUiThread {
                Toast.makeText(this, "بدء تشغيل سطح المكتب...", Toast.LENGTH_SHORT).show()
            }
            if (!GuiSession.running) {
                val started = GuiSession.start(this)
                if (!started) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "فشل بدء سطح المكتب",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@Thread
                }
                // Wait for VNC to be reachable
                var tries = 0
                while (tries < 20 && !GuiSession.isVncUp()) {
                    Thread.sleep(500)
                    tries++
                }
            }

            val client = RfbClient("127.0.0.1", 5900)
            client.onError = { msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
            client.onFramebufferUpdate = { bmp ->
                runOnUiThread {
                    binding.vncView.updateFramebuffer(bmp)
                }
            }

            if (client.connect()) {
                rfb = client
                runOnUiThread {
                    binding.vncView.rfb = client
                    Toast.makeText(
                        this,
                        "متصل: ${client.desktopName} (${client.width}x${client.height})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                client.start()
            } else {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "فشل الاتصال. تأكد أن Xvfb و x11vnc يعملان.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        rfb?.stop()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
