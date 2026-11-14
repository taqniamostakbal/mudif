package com.bolm.mudif.ui.apps

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.TerminalSession
import com.bolm.mudif.engine.X11Session
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.store.StoreProgram
import com.bolm.mudif.ui.vnc.VncActivity

/**
 * The ONE guest-app launch path (moved verbatim from
 * AppsFragment.runLaunch, P18.5 + PR #83 pgrep verify, so the Games
 * tab launches installed games exactly the way the Apps tab does ,
 * no second, drifting copy of the launch logic).
 *
 * GUI or CLI is decided for real: a .desktop file whose Exec runs
 * this binary means a window of its own; otherwise the binary opens
 * inside qterminal (LXQt's terminal, in the image). The command goes
 * through the live PTY session (GuestSession.sendCommand, the proven
 * launchDesktop mechanism): a one-shot proot cannot keep a GUI
 * process alive after it exits. When no desktop session is live, the
 * launch is handed to VncActivity, which boots the desktop and fires
 * the pending app once it is confirmed running. Either way the user
 * ends up in the viewer, looking at the running app.
 *
 * [onBusy] reports the busy window to the caller (the caller owns its
 * own busy set); it is always invoked on the UI thread.
 */
object AppLauncher {

    fun launch(
        fragment: Fragment,
        program: StoreProgram,
        onBusy: ((Boolean) -> Unit)? = null
    ) {
        val binary = program.binary ?: return
        val appCtx = fragment.requireContext().applicationContext
        val uiHandler = Handler(Looper.getMainLooper())
        uiHandler.post { onBusy?.invoke(true) }
        Thread {
            // A .desktop file whose Exec runs this binary means a
            // window of its own, EXCEPT when it declares
            // Terminal=true: that is a terminal app (htop), and
            // launching it raw opens no window at all (Batch 3).
            // The while loop drops Terminal=true files from the
            // match list, so htop falls through to qterminal.
            val guiCheck = TerminalSession.runCommandDebian(
                appCtx,
                "grep -rl '^Exec=[^ ]*\\b$binary\\b' " +
                    "/usr/share/applications/*.desktop 2>/dev/null | " +
                    "while read f; do grep -q '^Terminal=true' \"\$f\" || echo \"\$f\"; done | head -1",
                idleTimeoutSec = 15,
                maxTimeoutSec = 30
            )
            val isGui = guiCheck.exitCode == 0 &&
                guiCheck.output.isNotBlank() &&
                guiCheck.output.trim() != appCtx.getString(R.string.ui_no_output)
            // Extra launch flags (e.g. Electron apps). VSCodium
            // crashes on launch (code 5) without --no-sandbox
            // --disable-gpu --disable-dev-shm-usage in proot.
            val flags = program.flags?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
            // Programs that refuse to run as root (VLC) run as
            // another user via runuser. runuser keeps the caller's
            // environment (DISPLAY included), so the GUI still
            // reaches the viewer; Batch 3 proved it initializes
            // VLC's GUI.
            val runPrefix = program.runAs?.takeIf { it.isNotBlank() }
                ?.let { "runuser -u $it -- " } ?: ""
            val launchCmd = if (isGui) {
                "$runPrefix$binary$flags >/dev/null 2>&1 &"
            } else {
                "qterminal -e sh -c '$binary$flags; exec sh' >/dev/null 2>&1 &"
            }
            val live = GuestSession.alive && GuestSession.booted &&
                X11Session.isTcpDisplayOpen()
            if (live && GuestSession.sendCommand(launchCmd)) {
                // Verify the process actually started before claiming
                // success. The old code Toasted "launched" on `sent`
                // alone, which only proves bytes reached the PTY, not
                // that the program opened, failures were invisible
                // (output goes to /dev/null). Now a real check decides.
                val started = verifyStarted(appCtx, program)
                uiHandler.post {
                    if (!fragment.isAdded) return@post
                    onBusy?.invoke(false)
                    if (started) {
                        X11Session.openViewer(fragment.requireContext())
                        Toast.makeText(
                            fragment.requireContext(),
                            fragment.getString(R.string.apps_launched, program.name),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        MudifDialog.info(
                            fragment.requireContext(),
                            program.name,
                            fragment.getString(R.string.apps_launch_failed, program.name),
                            fragment.getString(R.string.ok)
                        )
                    }
                }
                return@Thread
            }
            uiHandler.post {
                if (!fragment.isAdded) return@post
                onBusy?.invoke(false)
                val intent = Intent(fragment.requireContext(), VncActivity::class.java).apply {
                    putExtra(VncActivity.EXTRA_DESKTOP, VncActivity.DESKTOP_MUDIF)
                    putExtra(VncActivity.EXTRA_LAUNCH_APP, launchCmd)
                    addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                Toast.makeText(
                    fragment.requireContext(),
                    fragment.getString(R.string.apps_launching, program.name),
                    Toast.LENGTH_SHORT
                ).show()
                fragment.startActivity(intent)
            }
        }.start()
    }

    /**
     * True if the program's process is running in the guest.
     * Checks twice (after 4s, then after 6s more) so slow apps
     * (LibreOffice, Electron) get time to start. pgrep -x matches
     * the exact process name; [StoreProgram.process] is the name
     * to check (defaults to the binary). A missing pgrep binary
     * or a failed check returns false (fail safe: report the
     * launch as failed rather than lie about it).
     */
    private fun verifyStarted(appCtx: Context, program: StoreProgram): Boolean {
        val process = program.process?.takeIf { it.isNotBlank() }
            ?: program.binary ?: return false
        repeat(2) { attempt ->
            Thread.sleep(if (attempt == 0) 4000 else 6000)
            val check = TerminalSession.runCommandDebian(
                appCtx,
                "pgrep -x \"$process\" >/dev/null 2>&1",
                idleTimeoutSec = 10,
                maxTimeoutSec = 15
            )
            if (check.exitCode == 0) return true
        }
        return false
    }
}
