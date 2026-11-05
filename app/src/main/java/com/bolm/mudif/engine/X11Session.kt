package com.bolm.mudif.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.termux.x11.CmdEntryPointService
import java.io.File

/**
 * P5.3: Native X11 via Termux:X11 (EXACT Tiny Container approach).
 *
 * Tiny Container's launchXServer replicated exactly:
 * - CmdEntryPointService with args [":0"]
 * - Env: TMPDIR, XKB_CONFIG_ROOT, TERMUX_X11_DEBUG=1, TERMUX_X11_OVERRIDE_PACKAGE
 * - Wait for filesDir/tmp/.X11-unix/X0
 * - proot guest connects via DISPLAY=:0 (tmp bind-mount in prootBuilder)
 * - Open com.termux.x11.MainActivity to display
 */
object X11Session {

    private const val TAG = "X11Session"
    private const val DISPLAY = ":0"

    @Volatile
    var xserverRunning = false
        private set

    // Keep the openbox Process alive (prevents GC)
    @Volatile
    private var guiProcess: Process? = null

    fun startXServer(context: Context) {
        val app = context.applicationContext
        val tmpDir = File(app.filesDir, "tmp").apply { mkdirs() }

        // EXACT match to Tiny Container's launchXServer
        val xkbRoot = File(RootfsManager.rootfsDir(context), "usr/share/X11/xkb")
            .takeIf { it.exists() }?.absolutePath ?: ""

        val envKeys = arrayOf(
            "TMPDIR",
            "XKB_CONFIG_ROOT",
            "TERMUX_X11_DEBUG",
            "TERMUX_X11_OVERRIDE_PACKAGE"
        )
        val envVals = arrayOf(
            tmpDir.absolutePath,
            xkbRoot,
            "1",
            app.packageName
        )

        val intent = Intent(app, CmdEntryPointService::class.java).apply {
            action = CmdEntryPointService.ACTION_START
            putExtra(CmdEntryPointService.EXTRA_ARGS, arrayOf(DISPLAY))
            putExtra(CmdEntryPointService.EXTRA_ENV_KEYS, envKeys)
            putExtra(CmdEntryPointService.EXTRA_ENV_VALUES, envVals)
        }
        app.startService(intent)
        xserverRunning = true
        Log.d(TAG, "X server start requested for $DISPLAY")
    }

    fun stopXServer(context: Context) {
        val app = context.applicationContext
        app.startService(Intent(app, CmdEntryPointService::class.java).apply {
            action = CmdEntryPointService.ACTION_STOP
        })
        xserverRunning = false
    }

    /**
     * Wait for X socket file (Tiny Container approach - no connection test).
     * Tiny Container only waits for the file, does NOT test the connection.
     */
    fun waitForXSocket(context: Context, timeoutMs: Long = 15000): Boolean {
        val socket = File(context.filesDir, "tmp/.X11-unix/X0")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (socket.exists()) {
                Log.d(TAG, "X socket ready")
                // Small delay for server init (like Tiny Container's implicit delay)
                Thread.sleep(2000)
                return true
            }
            Thread.sleep(200)
        }
        Log.e(TAG, "X socket not ready after ${timeoutMs}ms")
        return false
    }

    /**
     * Launch openbox DIRECTLY as the proot process (no shell, no pipe).
     * The Process IS openbox. Kept alive via static reference.
     * Output drained by daemon thread to prevent deadlock.
     */
    fun launchGuiApp(context: Context): Boolean {
        val pb = RootfsManager.prootBuilder(context, listOf("/usr/bin/openbox"))
            ?: return false
        pb.environment()["DISPLAY"] = DISPLAY
        // Don't merge streams; drain separately
        return try {
            val proc = pb.start()
            // Keep reference to prevent GC
            guiProcess = proc
            // Drain stdout/stderr to prevent pipe deadlock
            Thread {
                try {
                    val buf = ByteArray(8192)
                    val input = proc.inputStream
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "openbox-drain" }.start()
            Log.d(TAG, "openbox launched directly, pid kept alive")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch openbox", e)
            false
        }
    }

    fun openViewer(context: Context) {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(intent)
    }

    fun getOpenboxLog(context: Context): String {
        return try {
            val file = File(RootfsManager.rootfsDir(context), "root/openbox.log")
            if (!file.exists()) return "(openbox.log not found - openbox never started)"
            val lines = file.readLines()
            if (lines.isEmpty()) return "(openbox.log empty)"
            lines.takeLast(30).joinToString("\n")
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }

    fun isOpenboxRunning(context: Context): Boolean {
        val proc = guiProcess ?: return false
        return try {
            proc.exitValue()
            false // Exited
        } catch (_: IllegalThreadStateException) {
            true // Still alive
        }
    }
}
