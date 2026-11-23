package com.bolm.mudif.ui.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Toast
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ActivityRemoteControlBinding
import com.bolm.mudif.engine.RemoteControlService
import com.bolm.mudif.ui.MudifActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * F8 half B: web remote control, the in-app screen (p119 design
 * §3, do not redesign): status, address (large, Latin digits),
 * QR card (address only, never the password), password (copy +
 * show/hide), connected count, disconnect-all, start/stop.
 * The service owns the guest processes; this screen only starts
 * / stops / polls it on a 2 s ticker (the F4 HUD pattern).
 * The address refreshes itself on a Wi-Fi change through the
 * service's network callback; the password stays the same within
 * the session. Never auto-starts: the feature starts only when
 * the user opens this screen and taps start.
 */
class RemoteControlActivity : MudifActivity() {

    private lateinit var binding: ActivityRemoteControlBinding
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var passwordShown = false
    private var lastAddress: String? = null
    private var lastClients = -1
    private var fetchingClients = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            refreshUi()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.remoteBack.setOnClickListener { finish() }
        binding.remotePassword.transformationMethod = PasswordTransformationMethod.getInstance()
        binding.remoteToggle.setOnClickListener { onToggle() }
        binding.remoteCopyAddress.setOnClickListener { copyText(RemoteControlService.address()) }
        binding.remoteCopyPassword.setOnClickListener { copyText(RemoteControlService.password) }
        binding.remoteShowPassword.setOnClickListener { togglePasswordShown() }
        binding.remoteDisconnectAll.setOnClickListener { onDisconnectAll() }
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        ticking = true
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ticking = false
        handler.removeCallbacks(ticker)
    }

    private fun onToggle() {
        val running = RemoteControlService.state == RemoteControlService.State.RUNNING
        binding.remoteToggle.isEnabled = false
        Thread {
            if (running) RemoteControlService.stop(this) else RemoteControlService.start(this)
            runOnUiThread {
                binding.remoteToggle.isEnabled = true
                refreshUi()
            }
        }.start()
    }

    private fun onDisconnectAll() {
        Thread {
            val ok = RemoteControlService.disconnectAll(this)
            runOnUiThread {
                if (ok) Toast.makeText(this, getString(R.string.ui_remote_disconnected_all), Toast.LENGTH_SHORT).show()
                refreshUi()
            }
        }.start()
    }

    private fun togglePasswordShown() {
        passwordShown = !passwordShown
        binding.remotePassword.transformationMethod =
            if (passwordShown) null else PasswordTransformationMethod.getInstance()
        binding.remoteShowPassword.text =
            getString(if (passwordShown) R.string.ui_remote_hide else R.string.ui_remote_show)
    }

    private fun copyText(value: String?) {
        if (value.isNullOrEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mudif-remote", value))
        Toast.makeText(this, getString(R.string.ui_remote_copied), Toast.LENGTH_SHORT).show()
    }

    /** In-memory state on the UI thread; the client count arrives from a background fetch. */
    private fun refreshUi() {
        val state = RemoteControlService.state
        val address = RemoteControlService.address()
        val password = RemoteControlService.password
        val running = state == RemoteControlService.State.RUNNING
        binding.remoteStatus.text = getString(
            when (state) {
                RemoteControlService.State.STOPPED -> R.string.ui_remote_status_stopped
                RemoteControlService.State.STARTING -> R.string.ui_remote_status_starting
                RemoteControlService.State.RUNNING -> R.string.ui_remote_status_running
                RemoteControlService.State.ERROR -> R.string.ui_remote_status_error
            }
        )
        val error = RemoteControlService.lastError
        binding.remoteError.visibility = if (error != null && state == RemoteControlService.State.ERROR) View.VISIBLE else View.GONE
        binding.remoteError.text = error ?: ""
        binding.remoteAddress.text = address ?: ""
        binding.remotePassword.text = password ?: ""
        binding.remoteToggle.text = getString(if (running) R.string.ui_remote_stop else R.string.ui_remote_start)
        binding.remoteDisconnectAll.isEnabled = running
        binding.remoteCopyAddress.isEnabled = running
        binding.remoteCopyPassword.isEnabled = running
        binding.remoteShowPassword.isEnabled = running
        if (address != lastAddress) {
            lastAddress = address
            binding.remoteQr.setImageBitmap(address?.let { qrBitmap(it, 480) })
        }
        binding.remoteQrCard.visibility = if (address != null) View.VISIBLE else View.GONE
        if (running && !fetchingClients) {
            fetchingClients = true
            Thread {
                val count = try { RemoteControlService.clientCount(this) } catch (_: Exception) { 0 }
                runOnUiThread {
                    fetchingClients = false
                    if (count != lastClients) {
                        lastClients = count
                        binding.remoteClients.text = getString(R.string.ui_remote_clients, count)
                    }
                }
            }.start()
        } else if (!running) {
            lastClients = -1
            binding.remoteClients.text = getString(R.string.ui_remote_clients, 0)
        }
    }

    /**
     * QR, black-on-white with a 4-module quiet zone (never
     * recolored: scannability outranks style). Encodes the
     * address only, never the password.
     */
    private fun qrBitmap(text: String, sizePx: Int): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 4)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }
}
