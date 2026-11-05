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

    // Keep the GUI app process alive (prevents GC from killing it)
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

    fun waitForXSocket(context: Context, timeoutMs: Long = 15000): Boolean {
        val socket = File(context.filesDir, "tmp/.X11-unix/X0")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (socket.exists()) {
                Log.d(TAG, "X socket ready")
                return true
            }
            Thread.sleep(200)
        }
        Log.e(TAG, "X socket not ready after ${timeoutMs}ms")
        return false
    }

    fun launchGuiApp(context: Context): Process? {
        // Run openbox DIRECTLY (no sh -c wrapper) to avoid shell lifecycle issues.
        // DISPLAY is set in the environment, output goes to log file via redirect.
        val pb = RootfsManager.prootBuilder(context, listOf("/usr/bin/openbox"))
            ?: return null
        pb.environment()["DISPLAY"] = DISPLAY
        // Redirect output to log file (replaces shell redirection)
        val logFile = File(RootfsManager.rootfsDir(context), "root/openbox.log")
        try {
            logFile.parentFile?.mkdirs()
        } catch (_: Exception) {}
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile))
        // Don't merge stderr (we're redirecting separately)
        return try {
            val proc = pb.start()
            // Keep reference to prevent GC from killing the process
            guiProcess = proc
            proc
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch GUI app", e)
            null
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
        return try {
            val pb = RootfsManager.prootBuilder(context, listOf("/bin/sh", "-c", "ps | grep -q '[o]penbox' && echo YES || echo NO"))
                ?: return false
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.contains("YES")
        } catch (_: Exception) { false }
    }
}
