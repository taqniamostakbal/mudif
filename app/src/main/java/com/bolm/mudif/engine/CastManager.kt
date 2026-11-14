package com.bolm.mudif.engine

import android.content.Context
import com.bolm.mudif.R
import android.util.Log
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Phase 2, Google Cast sender (live HLS, Default Media Receiver).
 *
 * The Cast SDK has NO screen-mirroring API for third-party apps:
 * Remote Display (CastRemoteDisplayLocalService) was deprecated
 * in Sender 21.4.0-era release 20.1.0 (Nov 2021) and
 * isRemoteDisplaySdkSupported() now returns false. So the phone
 * casts its desktop as a LIVE MEDIA STREAM: TvStreamer encodes
 * the desktop to H.264 and serves an HLS playlist on the LAN,
 * and this object loads that URL with STREAM_TYPE_LIVE on the
 * Default Media Receiver (no custom receiver, no invented Cast
 * application ID, a production custom receiver would need a
 * registered application ID from the Cast developer console).
 *
 * Flow: init() once (main thread, guarded, no Play Services
 * means no Cast, never a crash) registers a session listener.
 * setPendingHlsUrl(url) + loadNow(url) are called when the user
 * picks "بث لاسلكي": if a Cast session is already connected the
 * media loads at once; otherwise the listener loads it when the
 * user picks a TV in the route chooser. Latency is seconds
 * (HLS), so this is for viewing/presenting, not fast interaction.
 *
 * No new manifest permission is needed (INTERNET and
 * ACCESS_NETWORK_STATE are already declared). The OptionsProvider
 * below is registered in AndroidManifest.xml via the
 * OPTIONS_PROVIDER_CLASS_NAME meta-data.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object CastManager {

    private const val TAG = "CastManager"

    /** True after init() when Play Services + Cast are present. */
    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var castContext: CastContext? = null

    /** Application context kept from init() for error strings on
     *  the session-listener path (no Activity is in scope there). */
    private var appContext: Context? = null

    /** HLS URL waiting for a Cast session to connect. */
    @Volatile
    private var pendingHlsUrl: String? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            loadPending(session)
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            loadPending(session)
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    /** Call once from the main thread (e.g. VncActivity.onCreate). */
    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            val cc = CastContext.getSharedInstance(context.applicationContext)
            castContext = cc
            cc.sessionManager.addSessionManagerListener(
                sessionListener, CastSession::class.java
            )
            available = true
            Log.d(TAG, "Cast available")
        } catch (e: Exception) {
            available = false
            lastError = context.getString(R.string.ui_cast_unavailable, e.message)
            Log.w(TAG, "Cast init failed: ${e.message}")
        }
    }

    fun setPendingHlsUrl(url: String) {
        pendingHlsUrl = url
    }

    /**
     * Loads [hlsUrl] on the current Cast session, if one is already
     * connected. Returns true when the load was sent, false when
     * there is no session yet (the listener will load the pending
     * URL when the user picks a TV).
     */
    fun loadNow(hlsUrl: String): Boolean {
        val session = currentSession() ?: return false
        return loadOnSession(session, hlsUrl)
    }

    /** Stops casting: ends the current session. Safe when idle. */
    fun stopCast() {
        try {
            pendingHlsUrl = null
            castContext?.sessionManager?.endCurrentSession(true)
        } catch (e: Exception) {
            Log.w(TAG, "stopCast failed: ${e.message}")
        }
    }

    private fun currentSession(): CastSession? {
        return try {
            castContext?.sessionManager?.currentCastSession
        } catch (_: Exception) {
            null
        }
    }

    private fun loadPending(session: CastSession) {
        val url = pendingHlsUrl ?: return
        pendingHlsUrl = null
        loadOnSession(session, url)
    }

    private fun loadOnSession(session: CastSession, hlsUrl: String): Boolean {
        return try {
            val client = session.remoteMediaClient ?: return false
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
                putString(MediaMetadata.KEY_TITLE, appContext?.getString(R.string.ui_cast_title) ?: "Mudif")
            }
            val media = MediaInfo.Builder(hlsUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType("application/x-mpegURL")
                .setMetadata(metadata)
                .build()
            client.load(media)
            Log.d(TAG, "Cast load sent: $hlsUrl")
            true
        } catch (e: Exception) {
            lastError = appContext?.getString(R.string.ui_cast_start_failed, e.message)
                ?: "Cast failed: ${e.message}"
            Log.w(TAG, "Cast load failed: ${e.message}")
            false
        }
    }
}
