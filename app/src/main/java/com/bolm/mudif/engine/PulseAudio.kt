package com.bolm.mudif.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-computer #1 — audio passthrough: guest apps produce sound
 * on the phone.
 *
 * How it works (no fork change, no host pulseaudio binary):
 * - The p19-v2 image ships pulseaudio. The app writes a guest
 *   config (/root/.config/pulse/default.pa) that creates a null
 *   sink (mudif_out) and streams it as raw PCM over TCP with
 *   module-simple-protocol-tcp on 127.0.0.1:4713. A null sink is
 *   just a tap point: apps play to it, the module forwards the
 *   stream. Nothing blocks: the module only listens, so a missing
 *   Android client can never hang the guest daemon (the FIFO
 *   alternative would block pulseaudio's startup on open).
 * - The Android side is a plain TCP client on the shared loopback
 *   (proot shares the network namespace — the same reason the guest
 *   reaches the X server on 127.0.0.1:6006). It reads s16le stereo
 *   44100 Hz PCM and feeds an AudioTrack (STREAM_MUSIC, so the
 *   control-center volume slider already governs it).
 * - Guest apps find the daemon through the normal path: the boot
 *   env sets XDG_RUNTIME_DIR=/run/user/0 (RootfsManager) and the
 *   daemon is started in the PTY guest shell, like the desktop.
 *
 * Honest limits: this is wiring, verified by a byte-flow probe
 * (probe() plays a tone in the guest and counts the PCM bytes that
 * reach the Android side). Nobody has HEARD it on a real phone
 * yet — that is the acceptance test, not a claim.
 */
object PulseAudio {

    private const val TAG = "PulseAudio"
    private const val PORT = 4713
    private const val SAMPLE_RATE = 44100

    private const val PREF_ENABLED = "mudifAudioEnabled"

    private val pumpRunning = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val bytesReceived = AtomicLong(0)
    /**
     * Peak |sample| seen since the last reset (s16le). Silence
     * streams as zeros; a real tone peaks near full scale. The
     * probe uses this, not the byte count: a TCP module can stream
     * silence continuously, so bytes alone cannot prove sound.
     */
    private val peakSample = AtomicInteger(0)
    @Volatile
    private var pumpThread: Thread? = null
    @Volatile
    private var audioTrack: AudioTrack? = null

    fun isEnabled(context: Context): Boolean =
        ViewerPrefsShim.isAudioEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        ViewerPrefsShim.setAudioEnabled(context, enabled)
    }

    /** True while the Android pump thread is alive. */
    fun isRunning(): Boolean = pumpRunning.get()

    /** PCM bytes received from the guest since the pump started. */
    fun receivedBytes(): Long = bytesReceived.get()

    /**
     * Writes the guest pulseaudio config into the rootfs (host-side
     * file write — the app owns the rootfs). Idempotent: the same
     * bytes every time.
     */
    fun ensureGuestConfig(context: Context) {
        val dir = java.io.File(
            DebianSystem.debianRootfsDir(context), "root/.config/pulse"
        )
        dir.mkdirs()
        val config = """
            # Mudif audio: guest apps play to the null sink; the TCP
            # module streams it as raw PCM to the Android side.
            load-module module-null-sink sink_name=mudif_out
            load-module module-simple-protocol-tcp rate=44100 format=s16le channels=2 record=false port=4713 listen=127.0.0.1 sink=mudif_out
            load-module module-native-protocol-unix
            set-default-sink mudif_out
        """.trimIndent()
        java.io.File(dir, "default.pa").writeText(config + "\n")
        Log.d(TAG, "guest pulseaudio config written")
    }

    /**
     * Starts the guest daemon (PTY, like the desktop launch) and the
     * Android pump. Returns true when the pump connected. Call from
     * a background thread: the TCP connect has a 3 s timeout.
     */
    fun start(context: Context): Boolean {
        if (pumpRunning.get()) return true
        if (!GuestSession.alive || !GuestSession.booted) {
            Log.e(TAG, "start: guest not booted")
            return false
        }
        ensureGuestConfig(context)
        // The daemon reads the config above (autospawn would too).
        GuestSession.sendCommand(
            "mkdir -p /run/user/0; XDG_RUNTIME_DIR=/run/user/0 pulseaudio --start 2>/dev/null"
        )
        // Give the daemon a moment to bind 4713 before connecting.
        Thread.sleep(1500)
        stopRequested.set(false)
        bytesReceived.set(0)
        val thread = Thread({
            runPump()
        }, "mudif-audio-pump")
        thread.isDaemon = true
        pumpThread = thread
        thread.start()
        // The pump sets pumpRunning once the TCP connect succeeds;
        // wait briefly so the caller gets a real answer.
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (pumpRunning.get()) return true
            if (!thread.isAlive) break
            Thread.sleep(200)
        }
        return pumpRunning.get()
    }

    /** Stops the pump and the guest daemon. */
    fun stop(context: Context) {
        stopRequested.set(true)
        try { pumpThread?.join(2000) } catch (_: Exception) { }
        pumpThread = null
        audioTrack?.let {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        audioTrack = null
        pumpRunning.set(false)
        GuestSession.sendCommand("pulseaudio --kill 2>/dev/null; pkill -f pulseaudio 2>/dev/null")
        Log.d(TAG, "audio stopped")
    }

    /** The pump: TCP -> AudioTrack. Runs on its own thread. */
    private fun runPump() {
        var socket: Socket? = null
        var track: AudioTrack? = null
        try {
            socket = Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 3000)
            socket.soTimeout = 500
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes((minBuf * 4).coerceAtLeast(16384))
                .build()
            audioTrack = track
            track.play()
            pumpRunning.set(true)
            Log.d(TAG, "pump connected, playing")
            val input = socket.getInputStream()
            val buf = ByteArray(8192)
            while (!stopRequested.get()) {
                val n = try {
                    input.read(buf)
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                }
                if (n < 0) break
                if (n > 0) {
                    track.write(buf, 0, n)
                    bytesReceived.addAndGet(n.toLong())
                    // Peak |sample| in this chunk (s16le): proves the
                    // stream carries sound, not streamed silence.
                    var peak = 0
                    var i = 0
                    while (i + 1 < n) {
                        val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                        val a = kotlin.math.abs(if (s >= 32768) s - 65536 else s)
                        if (a > peak) peak = a
                        i += 2
                    }
                    peakSample.accumulateAndGet(peak) { old, new -> maxOf(old, new) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pump failed: ${e.message}")
        } finally {
            pumpRunning.set(false)
            try { track?.stop() } catch (_: Exception) { }
            try { track?.release() } catch (_: Exception) { }
            if (track === audioTrack) audioTrack = null
            try { socket?.close() } catch (_: Exception) { }
            Log.d(TAG, "pump ended, bytes=${bytesReceived.get()}")
        }
    }

    /**
     * End-to-end probe: plays a 2 s 440 Hz tone in the guest
     * (ffmpeg generates it, ffplay plays it through pulseaudio)
     * and checks the PCM that reaches the Android side. The verdict
     * is the PEAK sample, not the byte count: the TCP module can
     * stream silence continuously, so bytes alone prove nothing.
     * A full-scale sine peaks near 32767; silence peaks at 0.
     * Returns a one-line evidence string. Requires audio started.
     */
    fun probe(context: Context): String {
        if (!pumpRunning.get()) return "probe: audio not running — start it first"
        val before = bytesReceived.get()
        peakSample.set(0)
        val ok = GuestSession.sendCommand(
            "ffmpeg -y -v error -f lavfi -i sine=frequency=440:duration=2 -ar 44100 -ac 2 /tmp/mudif-tone.wav && " +
                "ffplay -nodisp -autoexit -v error /tmp/mudif-tone.wav > /dev/null 2>&1 &"
        )
        if (!ok) return "probe: PTY send failed"
        Thread.sleep(4000)
        val after = bytesReceived.get()
        val peak = peakSample.get()
        return "probe: tone played, PCM bytes +${after - before}, peak sample=$peak " +
            if (peak > 1000) "(real sound flowing ✓ — now listen on the phone)"
            else "(silence — the guest daemon is not feeding the stream)"
    }
}

/**
 * The audio preference lives in the viewer's default
 * SharedPreferences file (same file ViewerPrefs uses), so it
 * survives with the other viewer settings. Kept here (not in
 * ViewerPrefs) so the audio engine owns its own key.
 */
private object ViewerPrefsShim {
    private fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(
            "${context.packageName}_preferences", Context.MODE_PRIVATE
        )

    fun isAudioEnabled(context: Context): Boolean =
        prefs(context).getBoolean("mudifAudioEnabled", true)

    fun setAudioEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("mudifAudioEnabled", enabled).apply()
    }
}
