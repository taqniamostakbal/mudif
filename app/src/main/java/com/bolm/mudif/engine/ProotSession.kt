package com.bolm.mudif.engine

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/**
 * P5.4 PROFESSIONAL REBUILD: Persistent proot shell session.
 *
 * Tiny Container's architecture: a SINGLE persistent shell in proot,
 * commands sent via sendCommand(), background processes stay alive.
 *
 * This replaces the broken one-shot prootBuilder().start() approach.
 * - One proot process, lives for the app's lifetime
 * - Commands sent via stdin, output drained via daemon thread
 * - GUI apps (openbox) launched with & stay alive in the session
 */
object ProotSession {

    private const val TAG = "ProotSession"

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    var running = false
        private set

    /**
     * Start the persistent proot shell. Returns true on success.
     * Idempotent: if already running, returns true immediately.
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (running && process != null) {
            try {
                process!!.exitValue()
                // Process died, fall through to restart
                Log.w(TAG, "Proot session died, restarting")
                stop()
            } catch (_: IllegalThreadStateException) {
                // Still alive
                return true
            }
        }

        val pb = RootfsManager.prootBuilder(context, listOf("/bin/sh"))
            ?: return false

        // Interactive shell: no -c, stdin stays open for commands
        // Don't merge streams here; we drain stdout via thread
        pb.redirectErrorStream(true)

        return try {
            val proc = pb.start()
            process = proc
            writer = proc.outputStream.bufferedWriter()

            // Drain stdout to prevent pipe deadlock (daemon thread)
            Thread {
                try {
                    val reader: BufferedReader = proc.inputStream.bufferedReader()
                    val buf = CharArray(8192)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        // Discard output (or log if needed)
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "ProotSession-drain" }.start()

            // Verify shell is alive
            Thread.sleep(500)
            try {
                proc.exitValue()
                Log.e(TAG, "Proot shell exited immediately")
                stop()
                false
            } catch (_: IllegalThreadStateException) {
                running = true
                Log.d(TAG, "Proot persistent session started")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proot session", e)
            stop()
            false
        }
    }

    /**
     * Send a command to the persistent shell.
     * The command runs in the same proot environment.
     * Use & for background processes that should stay alive.
     */
    @Synchronized
    fun sendCommand(command: String): Boolean {
        val w = writer ?: return false
        if (!running) return false
        return try {
            w.write(command)
            w.newLine()
            w.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command", e)
            false
        }
    }

    /**
     * Send a command and wait for a marker file to appear (sync execution).
     * The command should touch the marker when done.
     */
    fun runAndWaitMarker(context: Context, command: String, markerName: String, timeoutMs: Long = 15000): Boolean {
        val marker = File(RootfsManager.rootfsDir(context), "root/$markerName")
        try { marker.delete() } catch (_: Exception) {}
        if (!sendCommand(command)) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (marker.exists()) return true
            try {
                process?.exitValue()
                Log.e(TAG, "Proot died while waiting for marker")
                return false
            } catch (_: IllegalThreadStateException) {}
            Thread.sleep(200)
        }
        return false
    }

    @Synchronized
    fun stop() {
        running = false
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        try { process?.destroy() } catch (_: Exception) {}
        process = null
    }
}
