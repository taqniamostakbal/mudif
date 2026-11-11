package com.bolm.mudif.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * مستقبل مضيف على التلفاز: يكتشف الهاتف عبر NSD ويعرض سطح مكتبه
 * (فيديو فقط، بلا صوت) عبر ExoPlayer، إما HLS ‏(/stream.m3u8)‏
 * أو البث التقدمي منخفض التأخير ‏(/live.ts)‏.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val NSD_SERVICE_TYPE = "_mudiftv._tcp."
        private const val NSD_SERVICE_NAME = "Mudif-TV"
        private const val DEFAULT_PORT = 8089
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
    }

    private lateinit var playerView: PlayerView
    private lateinit var statusText: TextView
    private lateinit var discoveredText: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var buttonHls: Button
    private lateinit var buttonLowLatency: Button
    private lateinit var buttonStop: Button
    private lateinit var buttonRediscover: Button

    private var player: ExoPlayer? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    private var discovering = false

    private var discoveredHost: String? = null
    private var discoveredPort: Int = DEFAULT_PORT
    private var connected = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val discoveryTimeoutRunnable = Runnable {
        if (discoveredHost == null && !connected) {
            setStatus(getString(R.string.status_no_phone_found))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player_view)
        statusText = findViewById(R.id.status_text)
        discoveredText = findViewById(R.id.discovered_text)
        hostInput = findViewById(R.id.host_input)
        portInput = findViewById(R.id.port_input)
        buttonHls = findViewById(R.id.button_hls)
        buttonLowLatency = findViewById(R.id.button_low_latency)
        buttonStop = findViewById(R.id.button_stop)
        buttonRediscover = findViewById(R.id.button_rediscover)

        portInput.setText(DEFAULT_PORT.toString())

        buttonHls.setOnClickListener { connect(StreamMode.HLS) }
        buttonLowLatency.setOnClickListener { connect(StreamMode.LOW_LATENCY) }
        buttonStop.setOnClickListener { stopPlayback() }
        buttonRediscover.setOnClickListener { startDiscovery() }

        // D-pad: focus starts on the first connect button.
        buttonHls.requestFocus()

        setStatus(getString(R.string.status_searching))
        startDiscovery()
    }

    override fun onStop() {
        super.onStop()
        stopDiscovery()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        releasePlayer()
    }

    // ------------------------------------------------------------------
    // Discovery (NSD)
    // ------------------------------------------------------------------

    private fun startDiscovery() {
        stopDiscovery()
        discoveredHost = null
        discoveredPort = DEFAULT_PORT
        discoveredText.text = getString(R.string.discovered_none)
        setStatus(getString(R.string.status_searching))

        val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = manager

        resolveListener = object : NsdManager.ResolveListener {
            @Suppress("DEPRECATION")
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                // host is deprecated on API 34 but is the only accessor
                // that works on every API level down to minSdk 21.
                val hostAddress = serviceInfo.host?.hostAddress
                if (hostAddress != null) {
                    discoveredHost = hostAddress
                    discoveredPort = serviceInfo.port
                    runOnUiThread {
                        discoveredText.text = getString(
                            R.string.discovered_found,
                            hostAddress,
                            serviceInfo.port,
                        )
                        setStatus(getString(R.string.status_phone_found))
                    }
                }
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread {
                    setStatus(getString(R.string.status_resolve_failed))
                }
            }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discovering = true
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discovering = false
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == NSD_SERVICE_NAME) {
                    nsdManager?.resolveService(serviceInfo, resolveListener)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == NSD_SERVICE_NAME) {
                    discoveredHost = null
                    runOnUiThread {
                        discoveredText.text = getString(R.string.discovered_none)
                        if (!connected) {
                            setStatus(getString(R.string.status_no_phone_found))
                        }
                    }
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false
                runOnUiThread {
                    setStatus(getString(R.string.status_discovery_failed))
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false
            }
        }

        manager.discoverServices(
            NSD_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener,
        )
        mainHandler.removeCallbacks(discoveryTimeoutRunnable)
        mainHandler.postDelayed(discoveryTimeoutRunnable, DISCOVERY_TIMEOUT_MS)
    }

    private fun stopDiscovery() {
        mainHandler.removeCallbacks(discoveryTimeoutRunnable)
        val listener = discoveryListener
        if (discovering && listener != null) {
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                // Discovery was already stopped; nothing to do.
            }
        }
        discovering = false
        discoveryListener = null
        resolveListener = null
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    private enum class StreamMode { HLS, LOW_LATENCY }

    private fun connect(mode: StreamMode) {
        val manualHost = hostInput.text.toString().trim()
        val manualPort = portInput.text.toString().trim().toIntOrNull()

        // Manual entry wins when the user typed an address; otherwise
        // use the phone found by NSD discovery.
        val host: String?
        val port: Int
        val source: String
        if (manualHost.isNotEmpty()) {
            host = manualHost
            port = manualPort ?: DEFAULT_PORT
            source = getString(R.string.source_manual)
        } else if (discoveredHost != null) {
            host = discoveredHost
            port = discoveredPort
            source = getString(R.string.source_discovered)
        } else {
            setStatus(getString(R.string.status_no_phone_found))
            return
        }

        val path = when (mode) {
            StreamMode.HLS -> "stream.m3u8"
            StreamMode.LOW_LATENCY -> "live.ts"
        }
        val url = "http://$host:$port/$path"
        val mimeType = when (mode) {
            StreamMode.HLS -> null // HLS is auto-detected from the .m3u8 URL.
            StreamMode.LOW_LATENCY -> MimeTypes.VIDEO_MP2T
        }

        setStatus(getString(R.string.status_connecting, source, host, port))
        play(url, mimeType)
    }

    private fun play(url: String, mimeType: String?) {
        releasePlayer()

        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }

        val newPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this)),
            )
            .build()
        player = newPlayer
        playerView.player = newPlayer

        newPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    connected = true
                    setStatus(getString(R.string.status_playing))
                } else if (playbackState == Player.STATE_BUFFERING) {
                    setStatus(getString(R.string.status_buffering))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                connected = false
                setStatus(getString(R.string.status_playback_error))
            }
        })

        newPlayer.setMediaItem(mediaItemBuilder.build())
        newPlayer.prepare()
        newPlayer.playWhenReady = true
    }

    private fun stopPlayback() {
        releasePlayer()
        setStatus(getString(R.string.status_stopped))
    }

    private fun releasePlayer() {
        connected = false
        playerView.player = null
        player?.release()
        player = null
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }
}
