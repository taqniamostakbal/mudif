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
import com.bolm.mudif.engine.UsageLog
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
    // p82: verify window. The 4 s + 6 s window (10 s) is too short
    // for slow apps: Craft's cold start takes ~21 s to a process
    // and ~65 s to a window on the rig (p81-proven), so a real
    // launch logged ok=false and the user got a WRONG failure
    // dialog for a program that was starting.
    private const val VERIFY_TIMEOUT_MS = 60_000L
    private const val VERIFY_INTERVAL_MS = 5_000L

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
                // Slice 1 (agent-as-OS): the launch is logged only
                // once it is verified running; ok is the real
                // verifyStarted result, never assumed.
                UsageLog.append(appCtx, "app_launched", program.id, started)
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
     * Polls every 5 s up to 60 s from launch, so slow apps get
     * time to start: the first check stays at 4 s (fast apps
     * still verify fast), then 9 s, 14 s, ... A process appearing
     * at ~21 s (Craft cold start, p81-proven) is caught, while a
     * truly missing binary still returns false at the deadline.
     * pgrep -x matches the exact process name; [StoreProgram.process]
     * is the name to check (defaults to the binary). A missing
     * pgrep binary or a failed check returns false (fail safe:
     * report the launch as failed rather than lie about it).
     *
     * p80: pgrep alone matches a zombie (state Z) as if it were
     * running, so a crashed program logs ok=true with no window
     * (p78 Craft: PID 26164 Z<, ok=true). Mirror the
     * X11Session.desktopAliveCommand filter: a pid only counts
     * when /proc/<pid>/stat field 3 is not Z. The check itself
     * is unchanged in p82; only the schedule is longer.
     */
    private fun verifyStarted(appCtx: Context, program: StoreProgram): Boolean {
        val process = program.process?.takeIf { it.isNotBlank() }
            ?: program.binary ?: return false
        val d = "$"
        val checkCmd = "for pid in ${d}(pgrep -x \"$process\" 2>/dev/null); do " +
            "state=${d}(awk '{print ${d}3}' /proc/${d}pid/stat 2>/dev/null); " +
            "if [ -n \"${d}state\" ] && [ \"${d}state\" != \"Z\" ]; then exit 0; fi; done; exit 1"
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        var delayMs = 4000L
        while (true) {
            Thread.sleep(delayMs)
            val check = TerminalSession.runCommandDebian(
                appCtx,
                checkCmd,
                idleTimeoutSec = 10,
                maxTimeoutSec = 15
            )
            if (check.exitCode == 0) return true
            if (System.currentTimeMillis() >= deadline) return false
            delayMs = VERIFY_INTERVAL_MS
        }
    }
}
