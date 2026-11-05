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

    fun launchGuiApp(context: Context, command: List<String>): Process? {
        val pb = RootfsManager.prootBuilder(context, command) ?: return null
        pb.environment()["DISPLAY"] = DISPLAY
        return try {
            val proc = pb.start()
            // Keep reference to prevent GC from killing the process
            guiProcess = proc
            // Drain output to prevent pipe buffer deadlock
            Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
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
}
