package com.bolm.mudif.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream

/**
 * P5.16 REBUILD — persistent proot shell via ProcessBuilder.
 *
 * Replaces the P5.8 terminal-lib PTY session (NetHunter TerminalSession).
 * The PTY session is PROVEN broken on x86_64 Android 14 (InstaBox):
 * `ensure()` either throws or the session dies at once (PTY alive:
 * false, lastExitCode: -1, guest never boots), while the one-shot
 * ProcessBuilder proot path works (Alpine boot test: 3.24.0, "Alpine
 * خدام"). The native lib (libneoterm.so) exists for x86_64 with
 * standard deps, /system/bin/sh works, /dev/ptmx and /dev/pts exist —
 * the terminal-lib PTY creation itself fails on this platform.
 *
 * This session uses the PROVEN path: RootfsManager.prootBuilder
 * (ProcessBuilder) running a persistent `/bin/sh` inside proot.
 * Commands go to the shell's stdin; stdout/stderr are drained on
 * daemon threads so the pipe can never fill and stall the shell.
 *
 * Why this does not repeat the P5.4 pipe failure (R2: "& job control
 * unreliable, stdout block-buffers"): the desktop flow is
 * fire-and-forget. GUI apps are launched with `&` and their stdout
 * redirected to log files, and every status check goes through a
 * marker file on the shared /tmp bind-mount (isOpenboxRunning,
 * isXtermRunning), never by reading this shell's stdout. The shell
 * only has to stay alive and execute commands, which a pipe shell
 * does. Background jobs are detached from the shell's terminal
 * (stdin from /dev/null is not needed: openbox/xterm never read the
 * shell's stdin; xterm opens its own pty).
 *
 * Lifecycle:
 *  1. `ensure()` starts the persistent proot shell (once).
 *  2. `bootContainer()` waits for the guest marker file (written by
 *     the guest shell itself, so it can only appear if proot started).
 *  3. `sendCommand()` writes a command + newline to the shell stdin.
 *  4. `stop()` closes stdin and destroys the process.
 *  5. Death is observed: a watcher thread records the exit code and
 *     the reason in `lastError`, which the diagnostics show.
 */
object ContainerSession {

    private const val TAG = "ContainerSession"
    private const val GUEST_READY_MARKER = ".mudif_guest_ready"

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    /** True while the proot shell process exists and has not exited. */
    @Volatile
    var alive = false
        private set

    /** True once the guest shell proved it is running (marker seen). */
    @Volatile
    var booted = false
        private set

    /** Exit code of the last finished shell (-1 = never ran). */
    @Volatile
    var lastExitCode = -1
        private set

    /** Human-readable reason for the last failure, if any. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Start the persistent proot shell if there is none alive.
     * Idempotent. Must be called from a background thread.
     */
    @Synchronized
    fun ensure(context: Context): Boolean {
        if (alive && process != null) return true
        val pb = RootfsManager.prootBuilder(
            context,
            listOf("/bin/sh", "-c", "echo READY > /tmp/$GUEST_READY_MARKER; exec /bin/sh")
        ) ?: run {
            lastError = "prootBuilder null (engine/rootfs/loader missing)"
            Log.e(TAG, lastError!!)
            return false
        }
        return try {
            val proc = pb.start()
            process = proc
            stdinWriter = proc.outputStream.bufferedWriter(Charsets.UTF_8)
            // Drain stdout/stderr on daemon threads: a pipe that fills
            // (64 KiB) stalls the shell forever. Output is discarded;
            // status is read from marker files, never from this stream.
            Thread { drainStream(proc.inputStream) }.apply { isDaemon = true; start() }
            Thread { drainStream(proc.errorStream) }.apply { isDaemon = true; start() }
            // Watch for the process exiting on its own.
            Thread {
                val code = try {
                    proc.waitFor()
                } catch (_: Exception) {
                    -1
                }
                alive = false
                booted = false
                lastExitCode = code
                if (lastError == null) {
                    lastError = "proot shell exited, code=$code"
                }
                Log.w(TAG, "proot shell finished, exit=$code")
            }.apply { isDaemon = true; start() }
            alive = true
            lastExitCode = -1
            lastError = null
            Log.d(TAG, "proot shell started")
            true
        } catch (e: Exception) {
            lastError = "Failed to start proot shell: ${e.message}"
            Log.e(TAG, lastError!!, e)
            alive = false
            process = null
            stdinWriter = null
            false
        }
    }

    /**
     * Boot the Alpine container INSIDE the persistent shell. Idempotent:
     * returns true immediately if the guest shell already proved it is
     * alive. The marker is written by the GUEST shell itself, so it can
     * only appear if proot actually started.
     */
    fun bootContainer(context: Context, timeoutMs: Long = 20000): Boolean {
        if (booted && alive) return true
        val app = context.applicationContext
        // Stale marker from a previous run must not fake success.
        // Delete BEFORE starting, so the new shell is the only writer.
        try {
            File(app.filesDir, "tmp/$GUEST_READY_MARKER").delete()
        } catch (_: Exception) {
        }
        if (!ensure(context)) return false

        // Wait for the guest marker (host side of the /tmp bind-mount).
        val marker = File(app.filesDir, "tmp/$GUEST_READY_MARKER")
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (marker.exists()) {
                booted = true
                Log.d(TAG, "Guest shell alive (marker seen)")
                return true
            }
            if (!alive) {
                if (lastError == null) {
                    lastError = "proot shell died while booting (exit=$lastExitCode)"
                }
                Log.e(TAG, lastError!!)
                return false
            }
            Thread.sleep(200)
        }
        lastError = "Guest marker timeout after ${timeoutMs}ms (shell alive, marker never appeared)"
        Log.e(TAG, lastError!!)
        return false
    }

    /**
     * Write a command to the guest shell's stdin (command + newline).
     * Background `&` works: the shell backgrounds the job. Output is
     * discarded by the drain threads; status is checked via marker
     * files, never by reading this stream.
     */
    @Synchronized
    fun sendCommand(command: String): Boolean {
        val w = stdinWriter ?: return false
        if (!alive) return false
        return try {
            w.write(command + "\n")
            w.flush()
            true
        } catch (e: Exception) {
            lastError = "sendCommand failed: ${e.message}"
            Log.e(TAG, lastError!!, e)
            false
        }
    }

    /**
     * Stop the container: close stdin (EOF makes the shell exit) and
     * destroy the proot process.
     */
    @Synchronized
    fun stop() {
        try {
            stdinWriter?.close()
        } catch (_: Exception) {
        }
        stdinWriter = null
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {
        }
        process = null
        alive = false
        booted = false
        Log.d(TAG, "proot shell stopped")
    }

    private fun drainStream(stream: InputStream) {
        try {
            stream.bufferedReader().forEachLine { }
        } catch (_: Exception) {
            // Stream closed (process ended) — nothing to drain.
        }
    }
}
