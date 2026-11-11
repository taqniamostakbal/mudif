package com.bolm.mudif.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import com.bolm.mudif.R
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.bolm.mudif.ui.agent.MudifAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 6 — foreground supervisor for the whole-app agent.
 *
 * Owns the two loopback servers the agent needs: [MudifBridge]
 * (127.0.0.1:18080, the Android-layer control plane) and the
 * [AgentServer] brain in the Debian guest (127.0.0.1:4096). On start
 * (background thread) it starts the bridge, then
 * AgentServer.ensureStarted, which boots the guest when needed.
 * On destroy it stops the bridge and the brain server.
 *
 * Same shape as [InstallService]: foreground service, type
 * dataSync, persistent notification on channel "mudif_agent",
 * reference-counted start/stop, and a PARTIAL_WAKE_LOCK with a 2 h
 * safety timeout. The WakeLock is held while the supervised agent
 * session runs — that session IS the task here (the bridge and the
 * brain must stay alive for the agent to work) — and released in
 * onDestroy. Nothing here holds a WakeLock for a single chat
 * message or a single tool call.
 *
 * The service restarts itself after a system kill (START_STICKY):
 * a null intent on restart is treated as a start. It does NOT
 * start by itself on boot or on app launch — something must call
 * [start] (the agent tab is the intended caller; wiring that call
 * is a later step, not part of this file).
 *
 * Static helpers for the bridge's /state endpoint:
 * [isAccessibilityEnabled] reads Settings.Secure
 * ENABLED_ACCESSIBILITY_SERVICES for this app's
 * MudifAccessibilityService component; [isBatteryUnrestricted]
 * reads PowerManager.isIgnoringBatteryOptimizations. Both report
 * the real system state; neither changes it.
 */
class AgentSupervisorService : Service() {

    companion object {
        private const val TAG = "AgentSupervisor"
        private const val CHANNEL_ID = "mudif_agent"
        private const val NOTIF_ID = 4103
        private const val ACTION_START = "com.bolm.mudif.START_AGENT"
        private const val ACTION_STOP = "com.bolm.mudif.STOP_AGENT"
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        private val lock = Any()
        private var refCount = 0
        @Volatile private var instance: AgentSupervisorService? = null

        /** True while the supervisor service is alive. */
        @Volatile
        var running = false
            private set

        /** Begin (or share) a supervised agent session. Call [stop] exactly once when it ends. */
        fun start(context: Context) {
            synchronized(lock) { refCount++ }
            val intent = Intent(context, AgentSupervisorService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        /** End one supervised session; the service stops when the count hits 0. */
        fun stop(context: Context) {
            val intent = Intent(context, AgentSupervisorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** True when the user enabled Mudif Assistant in Android Settings > Accessibility. */
        fun isAccessibilityEnabled(context: Context): Boolean {
            return try {
                val component = ComponentName(context, MudifAccessibilityService::class.java)
                    .flattenToString()
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabled.split(":").any { it == component }
            } catch (e: Exception) {
                Log.w(TAG, "isAccessibilityEnabled failed", e)
                false
            }
        }

        /** True when the user set the app battery to Unrestricted. */
        fun isBatteryUnrestricted(context: Context): Boolean {
            return try {
                val pm = context.getSystemService(POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Exception) {
                Log.w(TAG, "isBatteryUnrestricted failed", e)
                false
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val startupStarted = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mudif:Agent").apply {
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                startForeground(NOTIF_ID, buildNotification())
                startServersOnce()
            }
            ACTION_STOP -> {
                val remaining = synchronized(lock) {
                    refCount = (refCount - 1).coerceAtLeast(0)
                    refCount
                }
                if (remaining == 0) stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        startupStarted.set(false)
        try {
            MudifBridge.stop()
        } catch (_: Exception) {
        }
        // AgentServer.stop runs a guest one-shot (up to 30 s); never
        // block onDestroy on it.
        val appCtx = applicationContext
        Thread {
            try {
                AgentServer.stop(appCtx)
            } catch (e: Exception) {
                Log.w(TAG, "stop brain server failed", e)
            }
        }.apply { isDaemon = true }.start()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    /** Starts bridge + brain exactly once per service lifetime, on a background thread. */
    private fun startServersOnce() {
        if (!startupStarted.compareAndSet(false, true)) return
        val appCtx = applicationContext
        Thread {
            try {
                if (!MudifBridge.start(appCtx)) {
                    Log.e(TAG, "bridge failed to start")
                }
                val ensured = AgentServer.ensureStarted(appCtx)
                if (!ensured.up) {
                    Log.e(TAG, "brain server not up: ${ensured.reason}")
                } else {
                    Log.d(TAG, "bridge + brain server up")
                }
            } catch (e: Exception) {
                Log.e(TAG, "supervisor startup failed", e)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ui_notif_agent_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.ui_notif_agent_desc)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.ui_agent_running))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }
}
