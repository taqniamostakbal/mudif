package com.bolm.mudif.ui.apps

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.RootfsManager
import com.bolm.mudif.engine.TerminalSession
import com.bolm.mudif.engine.UsageLog
import com.bolm.mudif.engine.X11Session
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.store.ProgramStore
import com.bolm.mudif.ui.store.StoreProgram
import com.bolm.mudif.ui.vnc.VncActivity
import java.io.File

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
    private const val TAG = "AppLauncher"

    // Slice 4 (killer build, launchDirect): guest path of the
    // bundled Cairo font the Win32 FT text backend shapes with.
    private const val DIRECT_FONT_PATH =
        "/usr/share/fonts/truetype/cairo/Cairo[slnt,wght].ttf"

    // Slice 4: the process name the AOT runtime sets for a win32
    // program (prctl comm). Notepad is the only win32 program;
    // a second one needs its own comm here.
    private const val DIRECT_PROCESS_NAME = "notepad-aot"

    // Slice 4: last 20 lines of the detached launchDirect proot
    // output, for diagnostics (the DirectDesktop.lastProotOutput
    // shape). The proot's children redirect to files; this stream
    // carries only proot errors and the layer's own log lines.
    @Volatile
    var lastDirectProotOutput: String = ""

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
        // Win32 programs have no Linux binary: they launch as the
        // AOT binary itself (translated once on the build machine,
        // no translator at runtime). Any other program without a
        // binary cannot launch at all.
        val isWin32 = program.method == "win32" && program.win32 != null
        val binary = program.binary ?: if (!isWin32) return else ""
        // Slice 4 (killer build): a Windows program IS the
        // desktop. It launches directly on :6, never through
        // VncActivity and never behind the full desktop boot.
        // Linux programs keep the path below.
        if (isWin32) {
            launchDirect(fragment, program, onBusy)
            return
        }
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
            // A Windows program opens its own window through the
            // Win32 layer; there is no .desktop file to check.
            val isGui = if (isWin32) {
                true
            } else {
                val guiCheck = TerminalSession.runCommandDebian(
                    appCtx,
                    "grep -rl '^Exec=[^ ]*\\b$binary\\b' " +
                        "/usr/share/applications/*.desktop 2>/dev/null | " +
                        "while read f; do grep -q '^Terminal=true' \"\$f\" || echo \"\$f\"; done | head -1",
                    idleTimeoutSec = 15,
                    maxTimeoutSec = 30
                )
                guiCheck.exitCode == 0 &&
                    guiCheck.output.isNotBlank() &&
                    guiCheck.output.trim() != appCtx.getString(R.string.ui_no_output)
            }
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
            val launchCmd = if (isWin32) {
                ProgramStore.win32LaunchCommand(program)
            } else if (isGui) {
                "$runPrefix$binary$flags >/dev/null 2>&1 &"
            } else {
                "qterminal -e sh -c '$binary$flags; exec sh' >/dev/null 2>&1 &"
            }
            // The process name to verify: the AOT binary for win32
            // (it sets its own comm to "notepad-aot" with prctl,
            // so pgrep -x finds it), the program's process (or
            // binary) otherwise.
            val processName = if (isWin32) "notepad-aot"
                else program.process?.takeIf { it.isNotBlank() } ?: binary
            // Take 1: the desktop is :6 itself now, and the PTY
            // shell's DISPLAY is already 127.0.0.1:6, so a GUI app
            // launched without a prefix opens on the desktop. The
            // same launchCmd is handed to VncActivity when the
            // session is not live, and VncActivity fires it as-is
            // after the desktop boots.
            val live = GuestSession.alive && GuestSession.booted &&
                X11Session.isTcpDisplayOpen()
            if (live && GuestSession.sendCommand(launchCmd)) {
                // Verify the process actually started before claiming
                // success. The old code Toasted "launched" on `sent`
                // alone, which only proves bytes reached the PTY, not
                // that the program opened, failures were invisible
                // (output goes to /dev/null). Now a real check decides.
                val started = verifyStarted(appCtx, processName)
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
                    putExtra(VncActivity.EXTRA_LAUNCH_PROCESS, processName)
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
     * Slice 4 (killer build): the program IS the desktop.
     *
     * A Windows program launches ONLY itself as its own Android
     * window on the GPU: no session, no panel, no VNC, no viewer
     * activity of its own. The surface provider is the Termux:X11
     * :6 server (one Android window per X window, GPU-composited
     * by Android); the gate is only that :6 answers TCP, and the
     * server is started on demand when it is down. The full
     * desktop (the direct :6 session) is never started here: it starts only when
     * the user opens it (VncActivity.startDesktop, already on a
     * background thread), so a program launch is lazy by
     * construction.
     *
     * The program runs under a host-side detached proot (the
     * DirectDesktop.startDetached shape: setsid + proot, the
     * proot lives exactly as long as the program, which runs in
     * the foreground, never backgrounded with &). No window
     * manager runs on :6, so the Win32 layer draws its own title
     * bar (MUDIF_NO_WM=1) and its own FT text backend
     * (MUDIF_TEXT_BACKEND=ft + MUDIF_FONT_PATH). On an x86_64
     * guest the binary runs under qemu-aarch64-static (the rig);
     * on arm64 it runs directly (the phone).
     *
     * The viewer opens as soon as the proot starts, without
     * waiting for the process check: the window appears in well
     * under a second on a warm server, and waiting for the 4 s +
     * 5 s verify poll would hand the user a dead store screen
     * for 9 s. The process check still runs in the background
     * for the log; a launch that never produces a process is
     * reported with a Toast (the user is already in the viewer,
     * so no dialog is shown over it).
     *
     * Win32 only in this slice. Linux programs keep launch().
     */
    private fun launchDirect(
        fragment: Fragment,
        program: StoreProgram,
        onBusy: ((Boolean) -> Unit)? = null
    ) {
        val appCtx = fragment.requireContext().applicationContext
        val uiHandler = Handler(Looper.getMainLooper())
        uiHandler.post { onBusy?.invoke(true) }
        Thread {
            val fail = { reason: String ->
                Log.e(TAG, "launchDirect failed: $reason")
                uiHandler.post {
                    onBusy?.invoke(false)
                    Toast.makeText(
                        appCtx,
                        appCtx.getString(R.string.apps_launch_failed, program.name),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            val path = ProgramStore.win32ProgramPath(program)
            if (path.isBlank()) {
                fail("no win32 path")
                return@Thread
            }
            // Gate: only the :6 surface provider. Start it on
            // demand when it is down; the wait is the cold path.
            var serverJustStarted = false
            if (!X11Session.isTcpDisplayOpen()) {
                serverJustStarted = true
                if (!X11Session.startXServer(appCtx) ||
                    !X11Session.waitForTcpDisplay(10_000)
                ) {
                    fail(":6 server did not answer TCP")
                    return@Thread
                }
            }
            // Arch decides the runner: direct on arm64 (the
            // phone), qemu on x86_64 (the rig). The guest arch is
            // cached by ProgramStore; the qemu check is one
            // one-shot, cached for the process lifetime.
            val arch = ProgramStore.guestArch(appCtx)
            if (arch.isBlank()) {
                fail("guest arch unknown")
                return@Thread
            }
            val progArgv: List<String> = if (ProgramStore.isArm64(arch)) {
                listOf(path)
            } else if (ProgramStore.hasQemuAarch64(appCtx)) {
                listOf("qemu-aarch64-static", "-L", "/usr/aarch64-linux-gnu", path)
            } else {
                fail("x86_64 guest without qemu-aarch64-static")
                return@Thread
            }
            val guestCommand = listOf(
                "/usr/bin/env", "-i",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LD_LIBRARY_PATH=",
                "LD_PRELOAD=",
                "DISPLAY=127.0.0.1:6",
                "LANG=ar_MA.UTF-8",
                "LANGUAGE=ar",
                "HOME=/root",
                "USER=root",
                "MUDIF_NO_WM=1",
                "MUDIF_TEXT_BACKEND=ft",
                "MUDIF_FONT_PATH=$DIRECT_FONT_PATH"
                // Take 2 (GPU setup): the same graphics env as
                // the boot shell, so a :6 program launched from
                // the store renders through the same driver as
                // the desktop session will after take 1.
            ) + RootfsManager.gpuEnvFor(DebianSystem.debianRootfsDir(appCtx)) + progArgv
            val (argv, env) = RootfsManager.prootCommandLineFor(
                appCtx,
                DebianSystem.debianRootfsDir(appCtx),
                DebianSystem.isReady(appCtx),
                guestCommand
            ) ?: run {
                fail("proot command line unavailable (rootfs not ready)")
                return@Thread
            }
            // Slice 16 (killer build): cold-launch resize race.
            // The :6 server starts at its initial size (larger
            // than the viewer resolution); the viewer resizes it
            // to viewerResolution on attach. If qemu starts before
            // the resize, the slice-15 clamp reads the initial
            // size and the window lands at +100+100 (slice 15
            // section 6.3). When this launch just started the
            // server, open the viewer first and wait until :6
            // reports the target size before starting qemu.
            // Fail-open: on timeout start qemu anyway (the
            // overflow returns, the launch still works).
            var viewerOpened = false
            if (serverJustStarted) {
                val target = X11Session.viewerResolution(appCtx)
                uiHandler.post {
                    if (!fragment.isAdded) return@post
                    X11Session.openViewer(fragment.requireContext())
                    viewerOpened = true
                }
                X11Session.waitForDisplaySize(appCtx, target, 10_000)
            }
            // setsid puts the proot in its own session and process
            // group, so Android's force-stop (which kills the app's
            // process group) does not touch it (the startDetached
            // shape, same comment, same absolute-path-first rule).
            val setsid = if (File("/system/bin/setsid").exists()) "/system/bin/setsid" else "setsid"
            val pb = ProcessBuilder(listOf(setsid) + argv)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            val process = try {
                pb.start()
            } catch (e: Exception) {
                fail("proot start failed: ${e.message}")
                return@Thread
            }
            // Drain the proot output so a spewing proot can never
            // block on a full pipe. The thread ends when the proot
            // exits, i.e. when the program exits.
            Thread {
                try {
                    val lines = ArrayDeque<String>()
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            lines.addLast(line!!)
                            if (lines.size > 20) lines.removeFirst()
                            lastDirectProotOutput = lines.joinToString("\n")
                        }
                    }
                } catch (_: Exception) {
                }
            }.start()
            // The viewer opens now, without waiting for the
            // process check (see the header comment). On a cold
            // launch it was already opened before the size wait
            // above (slice 16); do not open it twice.
            uiHandler.post {
                onBusy?.invoke(false)
                if (!fragment.isAdded) return@post
                if (!viewerOpened) X11Session.openViewer(fragment.requireContext())
            }
            // Background verify, for the log only.
            val started = verifyStarted(appCtx, DIRECT_PROCESS_NAME)
            UsageLog.append(appCtx, "app_launched_direct", program.id, started)
            if (!started) {
                uiHandler.post {
                    Toast.makeText(
                        appCtx,
                        appCtx.getString(R.string.apps_launch_failed, program.name),
                        Toast.LENGTH_LONG
                    ).show()
                }
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
     * pgrep -x matches the exact process name; [process] is the
     * name to check (the binary, the program's process field, or
     * "notepad-aot" for a win32 program). A missing
     * pgrep binary or a failed check returns false (fail safe:
     * report the launch as failed rather than lie about it).
     *
     * p80: pgrep alone matches a zombie (state Z) as if it were
     * running, so a crashed program logs ok=true with no window
     * (p78 Craft: PID 26164 Z<, ok=true). Mirror the
     * X11Session.desktopAliveCommand filter: a pid only counts
     * when /proc/<pid>/stat field 3 is not Z. The check itself
     * is unchanged in p82; only the schedule is longer.
     *
     * p83: double-confirm. A process seen alive at one poll must
     * still be alive at the next poll before this returns true:
     * pavucontrol and LXImage both logged ok=true once and
     * ok=false once for the same crashing program (rig-proven
     * p82), because the process existed at the early poll moment
     * and died a second later, leaving a zombie the filter only
     * excludes when it is caught dead. A fast app still verifies
     * in ~9 s (alive at 4 s, confirmed at 9 s); a slow app
     * (Craft ~21 s) is unaffected; a program that dies between
     * polls resets the confirmation and keeps polling to the
     * deadline, so a fast crasher returns false, never a false
     * ok=true.
     */
    private fun verifyStarted(appCtx: Context, process: String): Boolean {
        if (process.isBlank()) return false
        // p92 N-b: the single poll now lives in X11Session
        // (isGuestProcessAlive): same pgrep plus zombie filter,
        // so the live path and the pending path share one check.
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        var delayMs = 4000L
        var seenAlive = false
        while (true) {
            Thread.sleep(delayMs)
            if (X11Session.isGuestProcessAlive(appCtx, process)) {
                if (seenAlive) return true
                seenAlive = true
            } else {
                seenAlive = false
            }
            if (System.currentTimeMillis() >= deadline) return false
            delayMs = VERIFY_INTERVAL_MS
        }
    }
}
