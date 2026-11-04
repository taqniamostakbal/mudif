package com.bolm.mudif.engine

import android.content.Context
import java.util.concurrent.TimeUnit

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

    fun runCommand(context: Context, command: String, timeoutSec: Long = 30): TerminalResult {
        return try {
            val pb = RootfsManager.prootBuilder(context, listOf("/bin/sh", "-c", command))
                ?: return TerminalResult("النظام غير جاهز: ثبت Alpine من المتجر أولا", -1, false)

            val proc = pb.start()

            // Drain stdout on a daemon thread: avoids pipe deadlock when the
            // command produces more than the 64KB pipe buffer.
            val output = StringBuilder()
            val drain = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) { output.appendLine(line) }
                    }
                } catch (_: Exception) {
                    // Stream closed (timeout kill) — partial output is kept.
                }
            }.apply { isDaemon = true; start() }

            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            drain.join(2000)

            if (!finished) {
                proc.destroyForcibly()
                val partial = synchronized(output) { output.toString().trim() }
                val msg = buildString {
                    if (partial.isNotEmpty()) appendLine(partial)
                    append("انتهت المهلة (${timeoutSec}s): تم إيقاف الأمر")
                }
                return TerminalResult(msg, -1, true)
            }

            val out = synchronized(output) { output.toString().trim() }
            TerminalResult(
                output = out.ifEmpty { "(لا مخرجات)" },
                exitCode = proc.exitValue(),
                timedOut = false
            )
        } catch (e: Exception) {
            TerminalResult("خطأ: ${e.message}", -1, false)
        }
    }
}
