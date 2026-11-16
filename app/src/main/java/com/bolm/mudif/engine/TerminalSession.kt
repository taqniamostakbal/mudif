package com.bolm.mudif.engine

import android.content.Context
import com.bolm.mudif.R

/**
 * Result of a single terminal command.
 */
data class TerminalResult(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val cancelled: Boolean = false
)

/**
 * Runs one shell command inside the Alpine rootfs via proot.
 * Each call is stateless (fresh proot process); safe to call from a
 * background thread. Never call on the UI thread.
 */
object TerminalSession {

    /**
     * Smart timeout: a command is killed only if it is TRULY stuck ,
     * no output for [idleTimeoutSec], or if it exceeds [maxTimeoutSec]
     * total. A command actively producing output (e.g. apk installing
     * 108 packages) is never killed for being slow.
     * [onLine] (optional) receives every output line as it arrives,
     * on the drain thread, callers use it for live progress (e.g.
     * the store install dialog). Null keeps the old behaviour.
     */
    fun runCommand(
        context: Context,
        command: String,
        idleTimeoutSec: Long = 60,
        maxTimeoutSec: Long = 600,
        onLine: ((String) -> Unit)? = null
    ): TerminalResult {
        val pb = RootfsManager.prootBuilder(context, listOf("/bin/sh", "-c", command))
        return runWithBuilder(
            context, pb, context.getString(R.string.ui_term_not_ready_alpine),
            idleTimeoutSec, maxTimeoutSec, onLine
        )
    }

    /** P6: one-shot command inside the DEBIAN rootfs (apt installers). */
    fun runCommandDebian(
        context: Context,
        command: String,
        idleTimeoutSec: Long = 60,
        maxTimeoutSec: Long = 600,
        onLine: ((String) -> Unit)? = null
    ): TerminalResult {
        val pb = DebianSystem.prootBuilder(context, listOf("/bin/sh", "-c", command))
        return runWithBuilder(
            context, pb, context.getString(R.string.ui_term_not_ready_mudif),
            idleTimeoutSec, maxTimeoutSec, onLine
        )
    }

    /** P68: start a Debian command and return a cancelable handle
     * (null = rootfs not ready). The caller owns [GuestCommand.await];
     * [GuestCommand.cancel] kills the guest process (store Pause). */
    fun startCommandDebian(
        context: Context,
        command: String,
        idleTimeoutSec: Long = 60,
        maxTimeoutSec: Long = 600,
        onLine: ((String) -> Unit)? = null
    ): GuestCommand? {
        val pb = DebianSystem.prootBuilder(context, listOf("/bin/sh", "-c", command))
            ?: return null
        return try {
            startWithBuilder(context, pb, idleTimeoutSec, maxTimeoutSec, onLine)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A started guest command. [await] blocks until the process
     * exits, is cancelled, or times out, and returns the same result
     * [runCommandDebian] would, with [TerminalResult.cancelled] set
     * when [cancel] killed it. Same smart-timeout contract as the
     * one-shot runners: a command is killed only if it is TRULY
     * stuck (no output for [idleTimeoutSec] or past
     * [maxTimeoutSec] total).
     */
    class GuestCommand internal constructor(
        private val proc: Process,
        private val drain: Thread,
        private val output: StringBuilder,
        private val lastOutputAt: java.util.concurrent.atomic.AtomicLong,
        private val startAt: Long,
        private val context: Context,
        private val idleTimeoutSec: Long,
        private val maxTimeoutSec: Long
    ) {
        @Volatile
        var cancelled = false
            private set

        /** Kill the guest process now; [await] returns with cancelled=true. */
        fun cancel() {
            cancelled = true
            proc.destroyForcibly()
        }

        fun await(): TerminalResult {
            var timedOut = false
            var timeoutReason = ""
            // Poll: alive + (idle too long || total too long) => kill.
            while (true) {
                val exited = try {
                    proc.exitValue()
                    true
                } catch (_: IllegalThreadStateException) { false }
                if (exited) break

                val now = System.currentTimeMillis()
                val idleSec = (now - lastOutputAt.get()) / 1000
                val totalSec = (now - startAt) / 1000
                if (idleSec >= idleTimeoutSec) {
                    timedOut = true
                    timeoutReason = context.getString(R.string.ui_term_timeout_idle, idleSec)
                    break
                }
                if (totalSec >= maxTimeoutSec) {
                    timedOut = true
                    timeoutReason = context.getString(R.string.ui_term_timeout_max, maxTimeoutSec)
                    break
                }
                Thread.sleep(2000)
            }

            if (timedOut) {
                proc.destroyForcibly()
            }
            drain.join(2000)

            val partial = synchronized(output) { output.toString().trim() }
            if (timedOut) {
                val msg = buildString {
                    if (partial.isNotEmpty()) appendLine(partial)
                    append(context.getString(R.string.ui_term_stopped, timeoutReason))
                }
                return TerminalResult(msg, -1, true, cancelled)
            }

            return TerminalResult(
                // Machine-compared sentinel (AppsFragment compares
                // against it); the resource value is byte-identical
                // in every locale, so resolving it here is safe.
                output = partial.ifEmpty { context.getString(R.string.ui_no_output) },
                exitCode = try { proc.exitValue() } catch (_: IllegalThreadStateException) { -1 },
                timedOut = false,
                cancelled = cancelled
            )
        }
    }

    /**
     * Shared starter: [pb] already built for the target rootfs
     * (Alpine or Debian). Starts the process and the drain thread
     * (avoids pipe deadlock past the 64KB pipe buffer; tracks the
     * last time the command produced output as the liveness signal).
     */
    private fun startWithBuilder(
        context: Context,
        pb: ProcessBuilder,
        idleTimeoutSec: Long,
        maxTimeoutSec: Long,
        onLine: ((String) -> Unit)? = null
    ): GuestCommand {
        val proc = pb.start()
        val output = StringBuilder()
        val lastOutputAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val drain = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) { output.appendLine(line) }
                    lastOutputAt.set(System.currentTimeMillis())
                    // Live progress: outside the lock, so a slow
                    // consumer never blocks the drain.
                    onLine?.invoke(line)
                }
            } catch (_: Exception) {
                // Stream closed (timeout kill), partial output is kept.
            }
        }.apply { isDaemon = true; start() }
        return GuestCommand(
            proc, drain, output, lastOutputAt, System.currentTimeMillis(),
            context, idleTimeoutSec, maxTimeoutSec
        )
    }

    /**
     * Shared one-shot runner: [pb] already built for the target
     * rootfs (Alpine or Debian). Same smart-timeout contract as
     * before, a command is killed only if it is TRULY stuck.
     */
    private fun runWithBuilder(
        context: Context,
        pb: ProcessBuilder?,
        notReadyMessage: String,
        idleTimeoutSec: Long,
        maxTimeoutSec: Long,
        onLine: ((String) -> Unit)? = null
    ): TerminalResult {
        return try {
            val builder = pb ?: return TerminalResult(notReadyMessage, -1, false)
            startWithBuilder(context, builder, idleTimeoutSec, maxTimeoutSec, onLine).await()
        } catch (e: Exception) {
            TerminalResult(context.getString(R.string.ui_error_prefix, e.message), -1, false)
        }
    }
}
