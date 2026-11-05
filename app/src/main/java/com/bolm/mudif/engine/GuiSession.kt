package com.bolm.mudif.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * P5.2: Persistent GUI session manager.
 *
 * Problem: TerminalSession runs each command in a FRESH proot that exits
 * when the command finishes, killing background processes (Xvfb, x11vnc).
 * Solution: Start ONE persistent proot that runs the entire GUI stack
 * (Xvfb + x11vnc + openbox) and stays alive via `sleep infinity`.
 *
 * The Process is held in memory; killing it stops the GUI.
 */
object GuiSession {

    private const val TAG = "GuiSession"

    @Volatile
    private var process: Process? = null

    @Volatile
    var running: Boolean = false
        private set

    /**
     * Start the GUI stack. Returns true if started (or already running).
     * Must be called from a background thread.
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (running && process != null) return true

        // Ensure XKB keymap exists (may be missing from apk install)
        ensureKeymap(context)

        val script = """
            rm -f /root/gui_ready.marker
            echo "SCRIPT_STARTED" > /root/gui_marker.log
            rm -f /tmp/.X0-lock
            Xvfb :0 -screen 0 1280x720x24 -nolock > /root/xvfb.log 2>&1 &
            # Wait for X socket (up to 15s) - FIXES race condition
            for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
                if [ -S /tmp/.X11-unix/X0 ]; then break; fi
                sleep 1
            done
            x11vnc -display :0 -forever -shared -rfbport 5900 -listen 127.0.0.1 > /root/vnc.log 2>&1 &
            sleep 3
            DISPLAY=:0 openbox > /root/openbox.log 2>&1 &
            # Only mark ready if x11vnc is actually alive
            sleep 2
            if ps | grep -q "[x]11vnc"; then
                touch /root/gui_ready.marker
                echo "MARKER_CREATED" >> /root/gui_marker.log
            else
                echo "X11VNC_NOT_RUNNING" >> /root/gui_marker.log
            fi
            while true; do sleep 3600; done
        """.trimIndent()

        val pb = RootfsManager.prootBuilder(context, listOf("/bin/sh", "-c", script))
            ?: return false

        return try {
            process = pb.start()
            // Wait for marker file (bulletproof, no stdout buffering issues)
            val marker = File(RootfsManager.rootfsDir(context), "root/gui_ready.marker")
            // Delete stale marker first
            try { marker.delete() } catch (_: Exception) {}
            val deadline = System.currentTimeMillis() + 20000
            var ready = false
            while (System.currentTimeMillis() < deadline) {
                if (marker.exists()) {
                    ready = true
                    break
                }
                // If process died, fail fast
                try {
                    process!!.exitValue()
                    Log.e(TAG, "GUI proot died during startup")
                    break
                } catch (_: IllegalThreadStateException) { /* still alive */ }
                Thread.sleep(300)
            }
            running = ready
            if (!ready) {
                Log.e(TAG, "GUI marker not found in 20s")
                stop()
            }
            ready
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GUI", e)
            false
        }
    }

    /**
     * Stop the GUI stack.
     */
    @Synchronized
    /**
     * Get diagnostic logs for troubleshooting.
     * Returns Triple(xvfb.log tail, vnc.log tail, marker log).
     */
    fun getDiagnosticLogs(context: Context): Triple<String, String, String> {
        val rootfs = RootfsManager.rootfsDir(context)
        fun readTail(name: String): String {
            return try {
                val file = File(rootfs, "root/$name")
                if (!file.exists()) return "(file not found)"
                val lines = file.readLines()
                lines.takeLast(20).joinToString("\n")
            } catch (e: Exception) {
                "(error: ${e.message})"
            }
        }
        return Triple(readTail("xvfb.log"), readTail("vnc.log"), readTail("gui_marker.log"))
    }

    fun stop() {
        running = false
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {}
        process = null
    }

    /**
     * Check if the VNC port is reachable (x11vnc is up).
     */
    fun isVncUp(): Boolean {
        return try {
            java.net.Socket("127.0.0.1", 5900).use { true }
        } catch (_: Exception) { false }
    }

    /**
     * Ensure the XKB keymap file exists (Alpine's xkeyboard-config
     * sometimes ships without keymap/).
     */
    private fun ensureKeymap(context: Context) {
        try {
            val pb = RootfsManager.prootBuilder(
                context,
                listOf("/bin/sh", "-c",
                    "mkdir -p /usr/share/X11/xkb/keymap && " +
                    "[ -f /usr/share/X11/xkb/keymap/evdev ] || cat > /usr/share/X11/xkb/keymap/evdev << 'EOF'\n" +
                    "xkb_keymap {\n" +
                    "    xkb_keycodes \"evdev+aliases(qwerty)\";\n" +
                    "    xkb_types \"complete\";\n" +
                    "    xkb_compat \"complete\";\n" +
                    "    xkb_symbols \"pc+us+inet(evdev)\";\n" +
                    "    xkb_geometry \"pc(pc105)\";\n" +
                    "};\n" +
                    "EOF\n" +
                    "echo done")
            )
            val p = pb?.start()
            p?.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }
}
