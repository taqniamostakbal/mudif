package com.bolm.mudif.engine

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.offsec.nhterm.backend.TerminalSession
import java.io.File

/**
 * P5.8 REBUILD — persistent PTY container session, Tiny Container's
 * architecture, exactly.
 *
 * Tiny Container (`Global.kt`) runs the WHOLE container inside ONE real
 * PTY terminal session (`com.offsec.nhterm.backend.TerminalSession`,
 * terminal-lib) and types commands into it with `sendCommand()`. That is
 * why `&` job control, background GUI apps and shell buffering all work
 * there — and why Mudif's pipe-fed shell (P5.4 `ProotSession`) and
 * one-shot ProcessBuilder launches (P5.5) kept failing.
 *
 * This session is HEADLESS: no terminal UI is attached to it. It exists
 * only so the desktop flow has a real shell to talk to, exactly like
 * Tiny's `Global.terminalSession` before any TerminalView attaches.
 *
 * Lifecycle (mirrors Tiny `ContainerMainViewModel.setupEnvironment` +
 * `launchContainer` + `exitContainer`):
 *  1. `ensure()` creates the PTY shell (`/system/bin/sh`).
 *  2. `bootContainer()` writes a boot script (bypasses the PTY 4096-byte
 *     single-line limit, like Tiny's `boot_${code}.sh`) containing the
 *     exports + `exec proot ... /bin/sh -c '<marker>; exec /bin/sh'`,
 *     sources it, and waits for the guest marker file.
 *  3. `sendCommand()` types into the guest shell (openbox, etc.).
 *  4. `stop()` finishes the session and SIGQUITs the process group
 *     (Tiny: `Os.kill(-pid, SIGQUIT)`).
 *  5. Death is observed: `onSessionFinished` records the exit code
 *     (exit code -9 = killed by signal 9, Tiny's Signal9 case).
 *
 * The one-shot `TerminalSession.runCommand()` (terminal tab) is untouched
 * and independent; this session is dedicated to the desktop/container.
 */
object ContainerSession {

    private const val TAG = "ContainerSession"
    private const val BOOT_SCRIPT = "mudif_boot.sh"
    private const val GUEST_READY_MARKER = ".mudif_guest_ready"

    @Volatile
    private var session: TerminalSession? = null

    @Volatile
    private var resourcesSet = false

    /** True while the PTY session exists and has not finished. */
    @Volatile
    var alive = false
        private set

    /** True once the guest shell proved it is running (marker seen). */
    @Volatile
    var booted = false
        private set

    /** Exit code of the last finished session (-1 = never ran, -9 = SIGKILL). */
    @Volatile
    var lastExitCode = -1
        private set

    private val callback = object : TerminalSession.SessionChangedCallback {
        override fun onTextChanged(changedSession: TerminalSession?) {}
        override fun onColorsChanged(session: TerminalSession?) {}
        override fun onTitleChanged(changedSession: TerminalSession?) {}
        override fun onSessionFinished(finishedSession: TerminalSession?) {
            alive = false
            booted = false
            lastExitCode = try {
                finishedSession?.exitStatus ?: -1
            } catch (_: Exception) {
                -1
            }
            Log.w(TAG, "PTY session finished, exit=$lastExitCode")
        }

        override fun onClipboardText(session: TerminalSession?, text: String?) {}
        override fun onBell(session: TerminalSession?) {}
    }

    /**
     * Create the PTY shell if there is none alive. Idempotent.
     * Must be called from a background thread (constructor spawns a process).
     */
    @Synchronized
    fun ensure(context: Context): Boolean {
        if (alive && session != null) return true
        val app = context.applicationContext
        return try {
            if (!resourcesSet) {
                TerminalSession.setResources(app.resources)
                resourcesSet = true
            }
            val s = TerminalSession(
                "/system/bin/sh",
                app.dataDir.absolutePath,
                null,
                null,
                callback
            )
            s.updateSize(80, 24)
            session = s
            alive = true
            lastExitCode = -1
            Log.d(TAG, "PTY session created")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PTY session", e)
            alive = false
            session = null
            false
        }
    }

    /**
     * Boot the Alpine container INSIDE the PTY session (Tiny's
     * `launchContainer` flow). Idempotent: returns true immediately if the
     * guest shell already proved it is alive.
     *
     * The marker is written by the GUEST shell itself (`-c` wrapper), so
     * it can only appear if proot actually started — no false positive
     * from the host shell racing ahead.
     */
    fun bootContainer(context: Context, timeoutMs: Long = 20000): Boolean {
        if (booted && alive) return true
        if (!ensure(context)) return false
        val app = context.applicationContext

        val (argv, env) = RootfsManager.prootCommandLine(
            context, listOf("/bin/sh", "-c", "echo READY > /tmp/$GUEST_READY_MARKER; exec /bin/sh")
        ) ?: return false

        // Stale marker from a previous run must not fake success.
        try {
            File(app.filesDir, "tmp/$GUEST_READY_MARKER").delete()
        } catch (_: Exception) {
        }

        // Boot script: exports + exec proot. Written to a file because a
        // PTY line is limited to 4096 bytes (Tiny: boot_${code}.sh).
        val script = buildString {
            for ((k, v) in env) {
                append("export ")
                append(k)
                append("=")
                append(RootfsManager.shellQuote(v))
                append("\n")
            }
            append("exec ")
            append(argv.joinToString(" ") { RootfsManager.shellQuote(it) })
            append("\n")
        }
        try {
            File(app.filesDir, BOOT_SCRIPT).writeText(script)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write boot script", e)
            return false
        }

        if (!sendCommand("source ${File(app.filesDir, BOOT_SCRIPT).absolutePath}")) {
            return false
        }

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
                Log.e(TAG, "PTY session died while booting (exit=$lastExitCode)")
                return false
            }
            Thread.sleep(200)
        }
        Log.e(TAG, "Guest shell did not become ready in ${timeoutMs}ms")
        return false
    }

    /**
     * Type a command into the guest shell, exactly like Tiny's
     * `Global.sendCommand()`: the command plus a newline, as UTF-8 bytes.
     * Background `&` works here — this is a real terminal, not a pipe.
     */
    @Synchronized
    fun sendCommand(command: String): Boolean {
        val s = session ?: return false
        if (!alive) return false
        return try {
            val bytes = (command + "\n").toByteArray(Charsets.UTF_8)
            s.write(bytes, 0, bytes.size)
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand failed", e)
            false
        }
    }

    /**
     * Stop the container: finish the PTY session, then SIGQUIT the whole
     * process group so proot and its children die too (Tiny
     * `exitContainer`: `Os.kill(-pid, SIGQUIT)`).
     */
    @Synchronized
    fun stop() {
        val s = session
        session = null
        alive = false
        booted = false
        if (s == null) return
        try {
            s.finishIfRunning()
        } catch (_: Exception) {
        }
        try {
            val pid = s.pid
            if (pid > 0) Os.kill(-pid, OsConstants.SIGQUIT)
        } catch (_: Exception) {
        }
        Log.d(TAG, "PTY session stopped")
    }
}
