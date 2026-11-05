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

    /**
     * Launch openbox in the PERSISTENT proot session (Tiny Container architecture).
     * The session stays alive, so openbox stays alive.
     */
    fun launchGuiApp(context: Context): Boolean {
        // Ensure persistent session is running
        if (!ProotSession.start(context)) {
            Log.e(TAG, "Failed to start persistent proot session")
            return false
        }
        // Launch openbox in background (&) with DISPLAY set
        // Output to log file for diagnostics
        return ProotSession.sendCommand("DISPLAY=:0 openbox > /root/openbox.log 2>&1 &")
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
        // Use persistent session to check
        if (!ProotSession.running) return false
        // Send check command via a one-shot (doesn't affect persistent session)
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
