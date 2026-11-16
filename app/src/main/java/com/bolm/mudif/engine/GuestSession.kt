package com.bolm.mudif.engine

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.offsec.nhterm.backend.TerminalSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P5.22 RADICAL REBUILD, Tiny Container's mechanism, exactly.
 *
 * ONE NetHunter PTY session running the HOST shell (/system/bin/sh).
 * The container boots by writing the proot invocation into
 * boot_mudif.sh as `exec <proot ...>` and `source`-ing it in that
 * host shell. The `exec` REPLACES the host shell with proot running
 * a guest login shell, from that moment the same session IS the
 * container. Every later command (desktop launch, checks) is just
 * sendCommand: bytes written to the PTY master. The desktop is ONE
 * backgrounded command after the X socket appears.
 *
 * This DELETES the P5.16 pipe session (ContainerSession.kt). The pipe
 * had no controlling terminal, no job control and no delivery
 * guarantee: bytes could sit unread in the pipe buffer while
 * sendCommand returned true, exactly the P5.21 symptom (LXQt command
 * "sent", lxqt.log never created; round 2 all checks false while the
 * processes were alive). A PTY delivers bytes to the shell as typed
 * input, so a sent command is an executed command.
 *
 * PTY smoke test (study step 0) is built in, not assumed: probePty()
 * proves the PTY delivers to the host shell (marker file), and
 * bootContainer() proves it delivers to the guest shell (guest marker).
 * If the PTY truly cannot be created on this x86_64 device, ensure()
 * fails fast with lastError set, no silent false progress.
 *
 * Lifecycle (Tiny Container): clean slate on every entry.
 * bootContainer() finishes any old session and boots fresh. Leaving
 * a screen does NOT kill the session (P5.11 kept); entering the
 * desktop always reboots it, so reliability comes from the reboot
 * always working, never from reusing unproven state.
 */
object GuestSession {

    private const val TAG = "GuestSession"
    private const val BOOT_SCRIPT = "boot_mudif.sh"
    private const val PTY_PROBE_MARKER = ".mudif_pty_probe"
    private const val GUEST_READY_MARKER = ".mudif_guest_ready"

    /** The single PTY session. Null until ensure() creates it. */
    @Volatile
    var session: TerminalSession? = null
        private set

    /** True while the PTY session exists and has not finished. */
    @Volatile
    var alive = false
        private set

    /** True once the guest shell proved it is running (marker seen). */
    @Volatile
    var booted = false
        private set

    /** P6, which system the PTY session is booting (one system: Debian). */
    enum class GuestSystem { ALPINE, DEBIAN }

    /** The system of the current (or last) boot. The Terminal tab re-boots this one.
     * P6.4, the default is DEBIAN: نظام مضيف is the ONLY system in
     * this app, so a fresh start must never boot Alpine. */
    @Volatile
    var currentSystem = GuestSystem.DEBIAN
        private set

    /** Exit code of the last finished session (-1 = never ran / still running). */
    @Volatile
    var lastExitCode = -1
        private set

    /** Human-readable reason for the last failure, if any. */
    @Volatile
    var lastError: String? = null
        private set

    /** Result of the last probePty() call (null = never probed). */
    @Volatile
    var ptyProbeOk: Boolean? = null
        private set

    /** Called on every PTY screen update (the Terminal tab hooks this). */
    @Volatile
    var onScreenUpdate: (() -> Unit)? = null

    private var resourcesSet = false

    private val callback = object : TerminalSession.SessionChangedCallback {
        override fun onTextChanged(changedSession: TerminalSession?) {
            onScreenUpdate?.invoke()
        }

        override fun onColorsChanged(session: TerminalSession?) {
            onScreenUpdate?.invoke()
        }

        override fun onTitleChanged(changedSession: TerminalSession?) {}

        override fun onSessionFinished(finishedSession: TerminalSession?) {
            // Only the CURRENT session may clear the flags: an old
            // session finishing late (after a fresh boot replaced it)
            // must not kill the new session's alive flag.
            if (finishedSession !== session) return
            alive = false
            booted = false
            lastExitCode = finishedSession?.exitStatus ?: -1
            if (lastError == null) {
                lastError = "PTY session finished, exit=$lastExitCode"
            }
            Log.w(TAG, "PTY session finished, exit=$lastExitCode")
            onScreenUpdate?.invoke()
        }

        override fun onClipboardText(session: TerminalSession?, text: String?) {}

        override fun onBell(session: TerminalSession?) {}
    }

    /**
     * Create the PTY session if there is none alive. Idempotent.
     * Safe from ANY thread.
     *
     * P5.23 FIX (proven by the P5.22 test, logcat): the NetHunter
     * TerminalSession constructor creates a Handler, which needs a
     * Looper thread. VncActivity and TerminalFragment call ensure()
     * from a plain background Thread (no Looper), so the constructor
     * threw before any PTY was opened. Tiny Container creates its
     * session on the main thread. This function now does the same:
     * if the caller has no Looper, the construction runs on the main
     * thread and the caller waits for it (10s max). Only the
     * construction moves, sendCommand/write from worker threads
     * stays as it is (TC sends commands from coroutines too).
     */
    fun ensure(context: Context): Boolean {
        synchronized(this) {
            if (alive && session != null) return true
        }
        val app = context.applicationContext
        // Tiny Container's guard: the :xserver process never touches
        // the session or the bootstrap.
        if (Application.getProcessName() != app.packageName) {
            lastError = "GuestSession.ensure called from non-main process"
            Log.e(TAG, lastError!!)
            return false
        }
        if (Looper.myLooper() != null) {
            return createSession(app)
        }
        // No Looper on this thread: construct on the main thread.
        val latch = CountDownLatch(1)
        var ok = false
        Handler(Looper.getMainLooper()).post {
            ok = createSession(app)
            latch.countDown()
        }
        val done = try {
            latch.await(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!done) {
            lastError = "PTY session creation timed out (main thread busy)"
            Log.e(TAG, lastError!!)
            return false
        }
        return ok
    }

    /** The actual construction, always runs on a Looper thread. */
    @Synchronized
    private fun createSession(app: Context): Boolean {
        if (alive && session != null) return true
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
            lastError = null
            MudifLog.d(TAG, "PTY session created (host /system/bin/sh)")
            true
        } catch (e: Exception) {
            lastError = "PTY session creation failed: ${e.message}"
            Log.e(TAG, lastError!!, e)
            alive = false
            session = null
            false
        }
    }

    /**
     * Tiny Container's setupEnvironment, adapted to Mudif: create the
     * private dirs and export the paths the proot boot needs into the
     * HOST shell. The proot binary and loader live in Mudif's own
     * locations (EngineCheck / nativeLibraryDir), not TC's bootstrap.
     */
    fun setupEnvironment(context: Context) {
        val app = context.applicationContext
        val filesDir = app.filesDir.absolutePath
        File(app.filesDir, "tmp").apply { mkdirs() }
        File(app.filesDir, "run").apply { mkdirs() }
        File(app.filesDir, "proot_tmp").apply { mkdirs() }
        val loader = File(app.applicationInfo.nativeLibraryDir, "libtiny_loader.so")
        val binDir = EngineCheck.binDir(app).absolutePath
        sendCommand(
            "export BIN_DIR=$filesDir/bin\n" +
                "export CACHE_DIR=$filesDir\n" +
                "export PATH=\$BIN_DIR:\$PATH\n" +
                "export LD_LIBRARY_PATH=$binDir:\$LD_LIBRARY_PATH\n" +
                "export PROOT_LOADER=${loader.absolutePath}\n" +
                "export PROOT_TMP_DIR=$filesDir/proot_tmp\n" +
                "export PROOT_NO_SECCOMP=1"
        )
    }

    /**
     * Write a command to the PTY session (command + newline).
     * The PTY delivers it as typed input to the shell. Returns false
     * if there is no live session, the caller must treat that as a
     * hard failure, never as "sent".
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
            lastError = "sendCommand failed: ${e.message}"
            Log.e(TAG, lastError!!, e)
            false
        }
    }

    /**
     * STEP 0, PTY smoke test. Proves the PTY delivers bytes to the
     * HOST shell: sends an echo that writes a marker file straight
     * into filesDir/tmp, then polls for it. If this fails, the PTY
     * itself is dead on this device and nothing else can be built on
     * it. Must run BEFORE bootContainer (the session is still the
     * host shell at this point).
     */
    fun probePty(context: Context, timeoutMs: Long = 10000): Boolean {
        val app = context.applicationContext
        val marker = File(app.filesDir, "tmp/$PTY_PROBE_MARKER")
        try {
            marker.delete()
        } catch (_: Exception) {
        }
        if (!sendCommand("echo PTY_ALIVE > ${marker.absolutePath}")) {
            ptyProbeOk = false
            lastError = "PTY probe: sendCommand failed (no live session)"
            Log.e(TAG, lastError!!)
            return false
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (marker.exists()) {
                ptyProbeOk = true
                MudifLog.d(TAG, "PTY probe OK (marker seen)")
                return true
            }
            if (!alive) {
                ptyProbeOk = false
                lastError = "PTY probe: session died (exit=$lastExitCode)"
                Log.e(TAG, lastError!!)
                return false
            }
            Thread.sleep(200)
        }
        ptyProbeOk = false
        lastError = "PTY probe: marker never appeared after ${timeoutMs}ms"
        Log.e(TAG, lastError!!)
        return false
    }

    /**
     * Boot the container INSIDE the PTY session (Tiny
     * Container's launchContainer). Clean slate: finishes any old
     * session, creates a fresh host shell, exports the environment,
     * writes the boot script (`exec <proot ...>`) and sources it. The
     * `exec` replaces the host shell with proot running the guest
     * login shell, the session IS the container from then on.
     * Proof: a marker echo sent right after the source, written by
     * the GUEST shell into the shared /tmp bind. Returns true only
     * if that marker appears.
     *
     * P6: [system] selects the guest, ALPINE (the old system) or
     * DEBIAN (نظام مضيف). The fast-path only applies when the live
     * session is already the requested system; switching systems
     * always reboots fresh.
     */
    fun bootContainer(
        context: Context,
        system: GuestSystem = GuestSystem.DEBIAN,
        timeoutMs: Long = 20000
    ): Boolean {
        if (booted && alive && currentSystem == system) return true
        val app = context.applicationContext
        // Clean slate: kill the old session, start a fresh host shell.
        try {
            session?.finishIfRunning()
        } catch (_: Exception) {
        }
        session = null
        alive = false
        booted = false
        if (!ensure(context)) return false
        setupEnvironment(context)

        val script = if (system == GuestSystem.DEBIAN) {
            DebianSystem.writeBootScript(context)
        } else {
            RootfsManager.writeBootScript(context)
        } ?: run {
            lastError = "boot script: proot/rootfs/loader missing"
            Log.e(TAG, lastError!!)
            return false
        }
        // Stale marker from a previous run must not fake success.
        val marker = File(app.filesDir, "tmp/$GUEST_READY_MARKER")
        try {
            marker.delete()
        } catch (_: Exception) {
        }
        if (!sendCommand("source ${script.absolutePath}")) {
            lastError = "boot: could not source ${script.absolutePath}"
            Log.e(TAG, lastError!!)
            return false
        }
        // This echo is queued in the PTY input buffer and read by the
        // GUEST shell once proot has started it. It can only appear
        // if the guest shell is really running.
        if (!sendCommand("echo GUEST_READY > /tmp/$GUEST_READY_MARKER")) {
            lastError = "boot: could not send guest proof command"
            Log.e(TAG, lastError!!)
            return false
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (marker.exists()) {
                booted = true
                currentSystem = system
                MudifLog.d(TAG, "Guest shell alive (marker seen, system=$system)")
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
        lastError = "Guest marker timeout after ${timeoutMs}ms (session alive, marker never appeared)"
        Log.e(TAG, lastError!!)
        return false
    }

    /** Stop the session (close the PTY master; the guest dies with it). */
    @Synchronized
    fun stop() {
        try {
            session?.finishIfRunning()
        } catch (_: Exception) {
        }
        session = null
        alive = false
        booted = false
        MudifLog.d(TAG, "PTY session stopped")
    }
}
