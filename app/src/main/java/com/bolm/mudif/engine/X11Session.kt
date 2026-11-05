package com.bolm.mudif.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.termux.x11.CmdEntryPointService
import java.io.File

/**
 * P5.3: Native X11 via Termux:X11 (the Tiny Container approach).
 *
 * Instead of Xvfb+x11vnc+VNC (which fights SELinux), we use Termux:X11:
 * a NATIVE Android X server. The proot guest connects via DISPLAY=:0
 * through the Unix socket at filesDir/tmp/.X11-unix/X0 (bind-mounted
 * to /tmp in proot by RootfsManager).
 *
 * Flow:
 * 1. startXServer() launches CmdEntryPointService with ":0"
 * 2. Wait for the X socket to appear
 * 3. Launch GUI apps in proot with DISPLAY=:0
 * 4. Open com.termux.x11.MainActivity to display
 */
object X11Session {

    private const val TAG = "X11Session"
    private const val DISPLAY = ":0"

    @Volatile
    var xserverRunning = false
        private set

    /**
     * Start the Termux:X11 X server. Must be called from main thread
     * (startService). Returns immediately; use waitForXSocket() to block
     * until ready.
     */
    fun startXServer(context: Context) {
        val app = context.applicationContext
        val tmpDir = File(app.filesDir, "tmp").apply { mkdirs() }

        // XKB config root: use the guest's xkeyboard-config if available,
        // otherwise the host won't have it. Termux:X11 needs this for
        // keyboard mapping.
        val xkbRoot = File(RootfsManager.rootfsDir(context), "usr/share/X11/xkb")
            .takeIf { it.exists() }?.absolutePath
            ?: ""

        val envKeys = arrayOf(
            "TMPDIR",
            "XKB_CONFIG_ROOT",
            "TERMUX_X11_DEBUG"
        )
        val envVals = arrayOf(
            tmpDir.absolutePath,
            xkbRoot,
            "0"
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

    /**
     * Stop the X server.
     */
    fun stopXServer(context: Context) {
        val app = context.applicationContext
        app.startService(Intent(app, CmdEntryPointService::class.java).apply {
            action = CmdEntryPointService.ACTION_STOP
        })
        xserverRunning = false
    }

    /**
     * Wait for the X socket to appear (up to timeoutMs).
     * Returns true if the socket exists.
     */
    fun waitForXSocket(context: Context, timeoutMs: Long = 15000): Boolean {
        val socket = File(context.filesDir, "tmp/.X11-unix/X0")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (socket.exists()) {
                Log.d(TAG, "X socket ready: ${socket.absolutePath}")
                return true
            }
            Thread.sleep(200)
        }
        Log.e(TAG, "X socket not ready after ${timeoutMs}ms")
        return false
    }

    /**
     * Launch a GUI app in proot with DISPLAY=:0.
     * The app connects to the Termux:X11 server via the Unix socket.
     */
    fun launchGuiApp(context: Context, command: List<String>): Process? {
        // DISPLAY=:0, XDG_RUNTIME_DIR for Wayland compat (not needed but harmless)
        val pb = RootfsManager.prootBuilder(context, command) ?: return null
        pb.environment()["DISPLAY"] = DISPLAY
        // Ensure /tmp is bind-mounted (prootBuilder does this) so the
        // guest sees the X socket at /tmp/.X11-unix/X0
        return try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch GUI app", e)
            null
        }
    }

    /**
     * Open the Termux:X11 viewer Activity.
     */
    fun openViewer(context: Context) {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(intent)
    }
}
