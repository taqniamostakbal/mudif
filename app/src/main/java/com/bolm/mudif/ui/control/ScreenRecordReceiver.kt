package com.bolm.mudif.ui.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.bolm.mudif.R
import com.bolm.mudif.engine.ScreenRecorder

/**
 * Real-computer #5 — stops a screen recording from the
 * notification action (the user is inside the viewer while
 * recording, so the stop action cannot live in an activity).
 * onReceive must return fast: the stop runs on a background
 * thread via goAsync().
 */
class ScreenRecordReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP = "com.bolm.mudif.STOP_SCREEN_RECORDING"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        val pending = goAsync()
        Thread {
            try {
                val out = ScreenRecorder.stop(context)
                ScreenRecorder.hideStopNotification(context)
                val msg = if (out != null) {
                    context.getString(R.string.record_saved)
                } else {
                    context.getString(R.string.record_not_running)
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
