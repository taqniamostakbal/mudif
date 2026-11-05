package com.bolm.mudif.ui.vnc

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.databinding.ActivityVncBinding
import com.bolm.mudif.engine.GuiSession
import com.bolm.mudif.engine.vnc.RfbClient

/**
 * P5.2: VNC viewer for the Linux desktop.
 * Uses GuiSession (persistent proot) + RfbClient.
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
            runOnUiThread {
                Toast.makeText(this, "بدء تشغيل سطح المكتب...", Toast.LENGTH_SHORT).show()
            }
            // Auto-start GUI stack if not running
            if (!GuiSession.running) {
                val started = GuiSession.start(this)
                if (!started) {
                    val logs = GuiSession.getDiagnosticLogs(this)
                    runOnUiThread {
                        val msg = "فشل بدء سطح المكتب\n\nxvfb.log:\n${logs.first}\n\nvnc.log:\n${logs.second}\n\nmarker log:\n${logs.third}"
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("تشخيص")
                            .setMessage(msg)
                            .setPositiveButton("حسنا", null)
                            .show()
                    }
                    return@Thread
                }
                // Wait for VNC port
                var tries = 0
                while (tries < 20 && !GuiSession.isVncUp()) {
                    Thread.sleep(500)
                    tries++
                }
                if (!GuiSession.isVncUp()) {
                    // Show diagnostic logs instead of generic message
                    val logs = GuiSession.getDiagnosticLogs(this)
                    runOnUiThread {
                        Toast.makeText(this, "VNC لا يستجيب", Toast.LENGTH_LONG).show()
                        // Show logs in a dialog
                        val msg = "xvfb.log:\n${logs.first}\n\nvnc.log:\n${logs.second}\n\nmarker:\n${logs.third}"
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("تشخيص")
                            .setMessage(msg)
                            .setPositiveButton("حسنا", null)
                            .show()
                        finish()
                    }
                    return@Thread
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
                    Toast.makeText(this, "فشل الاتصال", Toast.LENGTH_LONG).show()
                    finish()
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
