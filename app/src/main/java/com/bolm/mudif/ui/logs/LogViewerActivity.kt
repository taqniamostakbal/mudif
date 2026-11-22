package com.bolm.mudif.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.bolm.mudif.ui.MudifActivity
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ActivityLogViewerBinding
import com.bolm.mudif.engine.DesktopDiagnostics
import com.bolm.mudif.engine.DirectDesktop

/**
 * P5.8, detailed log viewer (the "very detailed log" button the user
 * ordered). One screen, everything in one place: Hypothesis-1, X server
 * state, PTY container session state, openbox state + log, filtered
 * logcat. Copyable (نسخ) and refreshable (تحديث).
 *
 * Entry points: the desktop screen (VncActivity) and the حسابي tab.
 */
class LogViewerActivity : MudifActivity() {

    private lateinit var binding: ActivityLogViewerBinding

    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = getString(R.string.log_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnCopy.setOnClickListener { copyLogs() }
        binding.btnRefresh.setOnClickListener { loadLogs() }

        loadLogs()
    }

    private fun loadLogs() {
        binding.logText.text = getString(R.string.ui_log_collecting)
        Thread {
            // P5.22: no launch-gate checks anymore (the pipe-session
            // isOpenboxRunning round trip is deleted). The snapshot
            // reports the session state directly.
            // Take 1: the desktop lives on :6, so the snapshot's
            // desktop check is the direct-session check (host-side
            // pid file plus the guest probe, fast enough for this
            // background thread). It used to pass null (not checked).
            val snapshot = DesktopDiagnostics.fullSnapshot(
                this, DirectDesktop.isDirectDesktopAlive(this)
            )
            if (destroyed) return@Thread
            runOnUiThread { binding.logText.text = snapshot }
        }.start()
    }

    private fun copyLogs() {
        val text = binding.logText.text?.toString() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("mudif-logs", text))
        Toast.makeText(this, getString(R.string.ui_log_copied), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
