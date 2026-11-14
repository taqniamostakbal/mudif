package com.bolm.mudif.engine

import android.content.Context
import com.bolm.mudif.R
import android.util.Log
import java.io.File

/**
 * Real-computer #5, screen recording: ffmpeg inside the guest
 * grabs the X display (x11grab over the proven TCP 127.0.0.1:6)
 * and writes an .mp4 into the SHARED folder, so the user can pull
 * it out with the Files app. No MediaProjection, no fork change.
 *
 * ffmpeg runs as a background job of the PTY guest shell (like the
 * desktop launch itself): a one-shot proot could not keep it alive,
 * because the one-shot's proot exits and --kill-on-exit reaps its
 * children. The pid file lives in the guest /tmp, which is bound to
 * the app's files/tmp, so the host can see it too.
 *
 * Video only: guest audio is a separate path (PulseAudio) and is
 * not mixed into the recording in this pass. 15 fps + ultrafast
 * keeps the CPU cost survivable under llvmpipe/proot.
 */
object ScreenRecorder {

    private const val TAG = "ScreenRecorder"
    private const val PID_FILE = "/tmp/mudif-rec.pid"
    private const val LOG_FILE = "/tmp/mudif-rec.log"

    /** The recording file of the current (or last) run, if any. */
    @Volatile
    var currentFile: File? = null
        private set

    private fun pidFile(context: Context): File =
        File(File(context.filesDir, "tmp"), "mudif-rec.pid")

    private fun outputFile(context: Context): File {
        val stamp = System.currentTimeMillis() / 1000
        return File(RootfsManager.sharedDir(context), "mudif-rec-$stamp.mp4")
    }

    /**
     * Starts recording. Returns the output file, or null when the
     * desktop is not up or ffmpeg could not be launched. Call from a
     * background thread: the start itself is one PTY send, but the
     * caller usually verifies the pid file right after.
     */
    fun start(context: Context): File? {
        if (isRecording(context)) return currentFile
        if (!GuestSession.alive || !GuestSession.booted) {
            Log.e(TAG, "start: guest not booted")
            return null
        }
        val out = outputFile(context)
        val resolution = X11Session.viewerResolution(context)
        // SIGINT (not SIGKILL) on stop: ffmpeg then writes the mp4
        // trailer, so the file plays. -video_size must match the
        // server or x11grab records a cropped/wrong-size frame.
        val cmd = "ffmpeg -y -f x11grab -video_size $resolution -framerate 15 " +
            "-i 127.0.0.1:6 -c:v libx264 -preset ultrafast -pix_fmt yuv420p " +
            "${RootfsManager.SHARED_GUEST_PATH}/${out.name} > $LOG_FILE 2>&1 & " +
            "echo \$! > $PID_FILE"
        if (!GuestSession.sendCommand(cmd)) {
            Log.e(TAG, "start: PTY send failed")
            return null
        }
        // The pid file appears once the shell backgrounds ffmpeg.
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (pidFile(context).exists()) {
                currentFile = out
                Log.d(TAG, "recording started -> ${out.name}")
                return out
            }
            Thread.sleep(200)
        }
        Log.e(TAG, "start: pid file never appeared")
        return null
    }

    /**
     * Stops recording with SIGINT (clean mp4 finalization) and waits
     * up to 10 s for ffmpeg to exit. Returns the finished file when
     * it exists and is non-empty, null otherwise.
     */
    fun stop(context: Context): File? {
        val pidF = pidFile(context)
        if (!pidF.exists()) return null
        GuestSession.sendCommand("kill -INT \$(cat $PID_FILE) 2>/dev/null")
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            if (!isPidAlive(context)) break
            Thread.sleep(500)
        }
        try { pidF.delete() } catch (_: Exception) { }
        val out = currentFile
        currentFile = null
        return if (out != null && out.exists() && out.length() > 0) out else null
    }

    /** True when a recording ffmpeg is alive right now. */
    fun isRecording(context: Context): Boolean {
        if (!pidFile(context).exists()) return false
        return isPidAlive(context)
    }

    /** Ground truth: does the recorded pid still exist in the guest? */
    private fun isPidAlive(context: Context): Boolean {
        return try {
            val r = TerminalSession.runCommandDebian(
                context,
                "kill -0 \$(cat $PID_FILE) 2>/dev/null && echo ALIVE",
                idleTimeoutSec = 10,
                maxTimeoutSec = 20
            )
            r.exitCode == 0 && r.output.contains("ALIVE")
        } catch (_: Exception) {
            false
        }
    }

    /** Last lines of ffmpeg's log (diagnostics when a recording fails). */
    fun lastLog(context: Context): String {
        return try {
            val f = File(File(context.filesDir, "tmp"), "mudif-rec.log")
            if (!f.exists()) return "(no ffmpeg log yet)"
            f.readLines().takeLast(15).joinToString("\n")
        } catch (e: Exception) {
            "(log unreadable: ${e.message})"
        }
    }

    // ---- Stop notification: the user is inside the viewer while
    // recording, so the stop action lives in a notification, not in
    // an activity the user cannot reach. POST_NOTIFICATIONS is
    // already in the manifest; without the runtime grant the
    // notification is silently dropped (no crash) and the
    // VncActivity button remains the stop path.
    private const val CHANNEL_ID = "mudif_recording"
    const val NOTIFICATION_ID = 4101

    fun showStopNotification(context: Context) {
        try {
            val app = context.applicationContext
            val nm = app.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    CHANNEL_ID, context.getString(R.string.ui_notif_record_channel),
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(channel)
            }
            val stopIntent = android.content.Intent(
                app, com.bolm.mudif.ui.control.ScreenRecordReceiver::class.java
            ).apply { action = com.bolm.mudif.ui.control.ScreenRecordReceiver.ACTION_STOP }
            val pending = android.app.PendingIntent.getBroadcast(
                app, 0, stopIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = androidx.core.app.NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(context.getString(R.string.record_running))
                .setContentText(context.getString(R.string.ui_notif_record_stop))
                .addAction(android.R.drawable.ic_media_pause, context.getString(R.string.ui_stop), pending)
                .setOngoing(true)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "stop notification failed: ${e.message}")
        }
    }

    fun hideStopNotification(context: Context) {
        try {
            val nm = context.applicationContext
                .getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) { }
    }
}
