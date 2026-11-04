package com.bolm.mudif.engine

import android.content.Context

/**
 * Result of a single terminal command.
 */
data class TerminalResult(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean
)

/**
 * Runs one shell command inside the Alpine rootfs via proot.
 * Each call is stateless (fresh proot process); safe to call from a
 * background thread. Never call on the UI thread.
 */
object TerminalSession {

    /**
     * Smart timeout: a command is killed only if it is TRULY stuck —
     * no output for [idleTimeoutSec] — or if it exceeds [maxTimeoutSec]
     * total. A command actively producing output (e.g. apk installing
     * 108 packages) is never killed for being slow.
     */
    fun runCommand(
        context: Context,
        command: String,
        idleTimeoutSec: Long = 60,
        maxTimeoutSec: Long = 600
    ): TerminalResult {
        return try {
            val pb = RootfsManager.prootBuilder(context, listOf("/bin/sh", "-c", command))
                ?: return TerminalResult("النظام غير جاهز: ثبت Alpine من المتجر أولا", -1, false)

            val proc = pb.start()

            // Drain stdout on a daemon thread: avoids pipe deadlock when the
            // command produces more than the 64KB pipe buffer. Also tracks
            // the last time the command produced output (liveness signal).
            val output = StringBuilder()
            val lastOutputAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val drain = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) { output.appendLine(line) }
                        lastOutputAt.set(System.currentTimeMillis())
                    }
                } catch (_: Exception) {
                    // Stream closed (timeout kill) — partial output is kept.
                }
            }.apply { isDaemon = true; start() }

            val startAt = System.currentTimeMillis()
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
                    timeoutReason = "توقف عن إخراج أي نتيجة منذ ${idleSec}s"
                    break
                }
                if (totalSec >= maxTimeoutSec) {
                    timedOut = true
                    timeoutReason = "تجاوز الحد الأقصى (${maxTimeoutSec}s)"
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
                    append("تم إيقاف الأمر: $timeoutReason")
                }
                return TerminalResult(msg, -1, true)
            }

            TerminalResult(
                output = partial.ifEmpty { "(لا مخرجات)" },
                exitCode = proc.exitValue(),
                timedOut = false
            )
        } catch (e: Exception) {
            TerminalResult("خطأ: ${e.message}", -1, false)
        }
    }
}
