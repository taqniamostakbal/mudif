package com.bolm.mudif.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service + WakeLock for long guest work (store installs,
 * system download/extract, system update, backup). The work itself
 * still runs on the caller's Thread; this service only keeps the
 * process alive and the CPU awake so Android cannot kill the
 * download when the user leaves the app or the screen turns off.
 * Reference-counted: concurrent jobs share one service instance.
 * The WakeLock has a 2 h timeout as a safety net (a 455 MB system
 * download on slow data); it is released when the last job ends.
 */
class InstallService : Service() {

    companion object {
        private const val CHANNEL_ID = "mudif_install"
        private const val NOTIF_ID = 4102
        private const val ACTION_START = "com.bolm.mudif.START_INSTALL"
        private const val ACTION_STOP = "com.bolm.mudif.STOP_INSTALL"
        private const val EXTRA_LABEL = "label"
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        private val lock = Any()
        private var refCount = 0
        @Volatile private var instance: InstallService? = null

        /** Begin a guarded job. Call [stop] exactly once when it ends. */
        fun start(context: Context, label: String) {
            synchronized(lock) { refCount++ }
            val intent = Intent(context, InstallService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LABEL, label)
            }
            context.startForegroundService(intent)
        }

        /** End one guarded job; the service stops when the count hits 0. */
        fun stop(context: Context) {
            val intent = Intent(context, InstallService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** Push the current stage / percent into the notification. */
        fun updateProgress(stage: String, percent: Int?) {
            instance?.updateNotification(stage, percent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mudif:Install").apply {
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "جاري العمل…"
                startForeground(NOTIF_ID, buildNotification(label, null))
            }
            ACTION_STOP -> {
                val remaining = synchronized(lock) {
                    refCount = (refCount - 1).coerceAtLeast(0)
                    refCount
                }
                if (remaining == 0) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "تثبيت وتحميل",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "إشعار أثناء تثبيت البرامج وتحميل النظام"
                }
            )
        }
    }

    private fun buildNotification(label: String, percent: Int?): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("مضيف")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
        if (percent != null) {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        }
        return builder.build()
    }

    private fun updateNotification(stage: String, percent: Int?) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(stage, percent))
    }
}
