package com.bolm.mudif.ui.vnc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ActivityVncBinding
import com.bolm.mudif.engine.AgentServer
import com.bolm.mudif.engine.CastManager
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.DesktopDiagnostics
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.LxqtLook
import com.bolm.mudif.engine.PulseAudio
import com.bolm.mudif.engine.RootfsManager
import com.bolm.mudif.engine.ScreenRecorder
import com.bolm.mudif.engine.SystemDisplay
import com.bolm.mudif.engine.TerminalSession
import com.bolm.mudif.engine.TvStreamer
import com.bolm.mudif.engine.X11Session
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.logs.LogViewerActivity
import com.bolm.mudif.ui.settings.ViewerPrefs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * P5.22 RADICAL REBUILD — X11 desktop via Termux:X11, Tiny
 * Container's x11 branch exactly.
 *
 * Tiny (`ContainerMainViewModel`, x11 feature):
 *  1. Boot the container in the PTY session (fresh, clean slate).
 *  2. `launchXServer(args)` in parallel.
 *  3. `waitForFile(.../.X11-unix/X6)` — 15 s, exists() only.
 *  4. `Global.sendCommand(command)` — ONE backgrounded desktop
 *     command into the PTY session.
 *  5. Open `com.termux.x11.MainActivity`.
 *
 * No launch gates, no marker-file round trips, no isRunning checks,
 * no xterm. The dialog is a STATUS REPORT, not a verdict derived
 * from a broken pipe: success = PTY alive + guest booted + X socket
 * up + desktop command sent. The viewer shows the truth.
 *
 * P6.4 — نظام مضيف is the ONLY system in this app (the user's
 * order 2026-11-07: "خاص يبقى في التطبيق نظامي فقط اما باقي
 * الانظمة لا"). Debian 13.1 + LXQt, Deepin-style look baked into
 * the install flow. The Alpine rows are gone from the profile tab,
 * the store lists Debian only, and this activity no longer has an
 * Alpine path at all. The intent extra is ignored: every launch
 * opens نظام مضيف.
 * P6.5 — the desktop launches AFTER the viewer opens (from the
 * dialog button): the viewer resizes the server on attach, and a
 * desktop launched before attach lays out at 1280x1024 with its
 * panel off-screen. The viewer is pinned to exact 1280x720
 * (openViewer) so the size settles there before the launch.
 * Lifecycle: entering this screen ALWAYS reboots the container
 * fresh (Tiny Container's clean slate). Leaving the screen does NOT
 * kill the session or the X server (P5.11 kept): the desktop stays
 * alive until the app process dies or the next entry reboots it.
 */
class VncActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVncBinding

    @Volatile
    private var destroyed = false

    /**
     * P18.5: a pending app launch, handed in by the Apps tab
     * (EXTRA_LAUNCH_APP = the full shell command). It fires only
     * after the desktop is confirmed running, inside
     * openViewerAndLaunchDesktop — never into a dead X server.
     */
    @Volatile
    private var pendingLaunchApp: String? = null

    /**
     * P19: the last diagnostics report (built in startDesktop step 5).
     * The viewer now opens directly, so the report is shown on demand
     * from the status screen's "التشخيص" button instead of blocking
     * the normal path. Volatile: written on the start thread, read on
     * the UI thread.
     */
    @Volatile
    private var lastDiagnostics: String? = null

    /**
     * Phase 1d: floating icon controls over the viewer (record / TV /
     * audio). Shown when the viewer opens; hidden when this activity
     * is destroyed. The permission prompt is shown at most once per
     * activity instance, so it never spams.
     */
    private var viewerOverlay: ViewerOverlay? = null
    private var overlayPermissionPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingLaunchApp = intent.getStringExtra(EXTRA_LAUNCH_APP)

        supportActionBar?.title = getString(R.string.mudif_system)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // p28: BACK exits the viewer (returns to the app). Without
        // this the VNC view swallows BACK and the user is trapped
        // in the desktop with no way out except the Home gesture.
        // The desktop session itself keeps running (see onDestroy).
        onBackPressedDispatcher.addCallback(this) { finish() }

        // Phase 2: Cast init (main thread, guarded: no Play
        // Services on some devices means no Cast, never a crash).
        try { CastManager.init(this) } catch (_: Exception) { }

        binding.btnShowLogs.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        // P19: diagnostics on demand (the viewer opens directly now,
        // so the report lives here instead of in a blocking dialog).
        binding.btnShowDiagnostics.setOnClickListener {
            val report = lastDiagnostics
            if (report == null) {
                Toast.makeText(
                    this,
                    getString(R.string.ui_vnc_no_diagnostics),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                MudifDialog.info(
                    this,
                    getString(R.string.ui_vnc_diag_title),
                    report,
                    getString(R.string.ui_vnc_close)
                )
            }
        }

        // P16.2: the status screen is the escape hatch. If the diagnostics
        // dialog never appears (or the user dismisses it), this button
        // still opens the viewer and starts the desktop — the screen is
        // never a dead end. The TCP check runs off the UI thread
        // (Socket.connect throws NetworkOnMainThreadException, P5.25).
        binding.btnOpenViewer.setOnClickListener {
            Thread {
                if (X11Session.isTcpDisplayOpen()) {
                    runOnUiThread { openViewerAndLaunchDesktop() }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.ui_vnc_desktop_not_ready),
                            Toast.LENGTH_SHORT
                        ).show()
                        startDesktop()
                    }
                }
            }.start()
        }

        // Real-computer #5 — screen recording. The button toggles:
        // start (ffmpeg in the guest, viewer opens so you can see
        // what is being recorded) or stop (the mp4 lands in the
        // shared folder). One-shots run off the UI thread.
        binding.btnRecord.setOnClickListener {
            Thread {
                if (ScreenRecorder.isRecording(this)) {
                    val out = ScreenRecorder.stop(this)
                    ScreenRecorder.hideStopNotification(this)
                    runOnUiThread {
                        binding.btnRecord.setImageResource(R.drawable.ic_record)
                        Toast.makeText(
                            this,
                            if (out != null) getString(R.string.record_saved, out.absolutePath)
                            else getString(R.string.record_not_running),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    if (!GuestSession.booted || !X11Session.isTcpDisplayOpen()) {
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.ui_vnc_desktop_not_ready), Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    val startedFile = ScreenRecorder.start(this)
                    runOnUiThread {
                        if (startedFile != null) {
                            binding.btnRecord.setImageResource(R.drawable.ic_stop)
                            ScreenRecorder.showStopNotification(this)
                            Toast.makeText(this, getString(R.string.record_running), Toast.LENGTH_SHORT).show()
                            X11Session.openViewer(this)
                        } else {
                            MudifDialog.info(
                                this,
                                getString(R.string.record_title),
                                getString(R.string.record_failed) + "\n\n" + ScreenRecorder.lastLog(this),
                                getString(R.string.ok)
                            )
                        }
                    }
                }
            }.start()
        }

        // Phase 2 — desktop on TV, three paths (research order):
        // Cast live-HLS (in-app, wireless), system display
        // (Presentation: Smart View / DeX / HDMI), system wireless
        // settings (Miracast fallback). The TV shows the live
        // desktop; it does not boot one, so Cast refuses until the
        // desktop is up. When streaming, the button stops it.
        binding.btnTv.setOnClickListener {
            if (TvStreamer.state == TvStreamer.TvState.STREAMING) {
                MudifDialog.info(
                    this, getString(R.string.tv_title),
                    getString(R.string.tv_cast_running),
                    getString(R.string.tv_cast_stop),
                    onPositive = {
                        Thread {
                            TvStreamer.stop(this)
                            CastManager.stopCast()
                        }.start()
                    },
                    negative = getString(R.string.cancel)
                )
                return@setOnClickListener
            }
            MudifDialog.info(
                this, getString(R.string.tv_title),
                getString(R.string.tv_choose_body),
                getString(R.string.tv_choose_cast),
                onPositive = {
                    if (!GuestSession.booted || !X11Session.isTcpDisplayOpen()) {
                        Toast.makeText(this, getString(R.string.ui_vnc_desktop_not_ready), Toast.LENGTH_LONG).show()
                        return@info
                    }
                    if (!CastManager.available) {
                        MudifDialog.info(this, getString(R.string.tv_title), getString(R.string.tv_cast_no_play_services), getString(R.string.ok))
                        return@info
                    }
                    Toast.makeText(this, getString(R.string.tv_cast_starting), Toast.LENGTH_SHORT).show()
                    Thread {
                        val started = TvStreamer.start(this)
                        runOnUiThread {
                            if (!started) {
                                MudifDialog.info(
                                    this, getString(R.string.tv_title),
                                    getString(R.string.tv_cast_failed) + "\n\n" + (TvStreamer.lastError ?: "") + "\n\n" + TvStreamer.lastLog(this),
                                    getString(R.string.ok)
                                )
                                return@runOnUiThread
                            }
                            val url = TvStreamer.streamUrl()
                            if (url == null) {
                                Toast.makeText(this, getString(R.string.tv_cast_failed), Toast.LENGTH_LONG).show()
                                return@runOnUiThread
                            }
                            CastManager.setPendingHlsUrl(url)
                            // If a Cast session is already connected,
                            // load now; otherwise the session listener
                            // loads when the user picks a TV below.
                            if (!CastManager.loadNow(url)) {
                                MediaRouteChooserDialogFragment()
                                    .show(supportFragmentManager, "mudif_cast_chooser")
                            } else {
                                Toast.makeText(this, getString(R.string.tv_cast_started), Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                },
                neutral = getString(R.string.tv_choose_system),
                onNeutral = {
                    Thread {
                        val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                        val displays = dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                        if (displays.isEmpty()) {
                            // No system display: label the Miracast
                            // fallback as a SYSTEM flow before opening
                            // the system settings (the app cannot
                            // connect to a Miracast TV itself).
                            runOnUiThread {
                                MudifDialog.info(
                                    this, getString(R.string.tv_title),
                                    getString(R.string.tv_miracast_body),
                                    getString(R.string.tv_miracast_open_button),
                                    onPositive = {
                                        SystemDisplay.openWirelessDisplaySettings(this)
                                    },
                                    negative = getString(R.string.cancel)
                                )
                            }
                            return@Thread
                        }
                        if (!GuestSession.booted || !X11Session.isTcpDisplayOpen()) {
                            runOnUiThread {
                                Toast.makeText(this, getString(R.string.ui_vnc_desktop_not_ready), Toast.LENGTH_LONG).show()
                            }
                            return@Thread
                        }
                        ViewerPrefs.prefs(this).edit()
                            .putBoolean("storeSecondaryDisplayPreferencesSeparately", true).apply()
                        val ok = X11Session.openViewerOnDisplay(this, displays[0])
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                if (ok) getString(R.string.tv_opened) else getString(R.string.tv_no_display),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }.start()
                },
                negative = getString(R.string.cancel)
            )
        }

        // Real-computer #1 — audio passthrough. The button toggles
        // the "audio enabled" preference; the pump starts with the
        // desktop when enabled, and immediately on tap when the
        // desktop is already up.
        binding.btnAudio.setOnClickListener {
            Thread {
                val nowEnabled = !PulseAudio.isEnabled(this)
                PulseAudio.setEnabled(this, nowEnabled)
                if (nowEnabled) {
                    if (GuestSession.booted && X11Session.isTcpDisplayOpen()) {
                        val started = PulseAudio.start(this)
                        if (!started) {
                            runOnUiThread {
                                Toast.makeText(this, getString(R.string.audio_failed), Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.ui_vnc_audio_starts_later), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    PulseAudio.stop(this)
                }
                runOnUiThread {
                    binding.btnAudio.imageTintList =
                        android.content.res.ColorStateList.valueOf(
                            if (nowEnabled) 0xFFFFFFFF.toInt() else 0xFF9E9E9E.toInt()
                        )
                    binding.btnAudio.contentDescription =
                        if (nowEnabled) getString(R.string.audio_on) else getString(R.string.audio_off)
                }
            }.start()
        }

        // Version indicator — reads the live marker, never a stale label.
        Toast.makeText(this, getString(R.string.ui_vnc_version_toast, LxqtLook.MARKER_VERSION), Toast.LENGTH_LONG).show()

        startDesktop()
    }

    /**
     * P16.2: singleTop relaunch. A second tap on "افتح سطح المكتب" (or a
     * relaunch while this screen is already in the stack) brings THIS
     * instance forward instead of stacking a second VncActivity whose
     * background thread would race the first on the PTY and the X
     * server (the P16.1 second-launch stall). Re-run the flow only if
     * no run is already in progress.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A fresh launch request replaces any pending one.
        pendingLaunchApp = intent.getStringExtra(EXTRA_LAUNCH_APP)
        startDesktop()
    }

    /**
     * Real-computer #1/#5: refresh the toggle buttons when the
     * screen comes back (the user may have left the viewer running).
     * Audio reads the preference (cheap, no one-shot); recording
     * checks the live ffmpeg process (one-shot, off the UI thread).
     */
    override fun onResume() {
        super.onResume()
        binding.btnAudio.imageTintList =
            android.content.res.ColorStateList.valueOf(
                if (PulseAudio.isEnabled(this)) 0xFFFFFFFF.toInt() else 0xFF9E9E9E.toInt()
            )
        Thread {
            val recording = ScreenRecorder.isRecording(this)
            runOnUiThread {
                binding.btnRecord.setImageResource(
                    if (recording) R.drawable.ic_stop else R.drawable.ic_record
                )
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        // p22: collapse the floating panel when leaving this screen
        // (HOME, viewer opens). The FAB stays — it floats over the
        // viewer and is the reachable control there. The wide panel
        // must not sit over the home screen blocking icons. onDestroy
        // still removes everything.
        viewerOverlay?.collapsePanel()
    }

    /**
     * Phase 1d: show the floating icon controls over the viewer.
     * If the overlay permission is missing, ask once (open the system
     * settings); if the user declines, the desktop still works, just
     * without the floating controls.
     */
    private fun showViewerOverlay() {
        if (viewerOverlay == null) viewerOverlay = ViewerOverlay(this)
        val overlay = viewerOverlay ?: return
        if (overlay.canDraw()) {
            overlay.show()
            return
        }
        if (overlayPermissionPrompted) return
        overlayPermissionPrompted = true
        MudifDialog.info(
            this,
            getString(R.string.ui_vnc_overlay_title),
            getString(R.string.ui_vnc_overlay_body),
            getString(R.string.ui_vnc_overlay_enable),
            onPositive = { overlay.requestPermission() },
            negative = getString(R.string.ui_vnc_overlay_later)
        )
    }

    /**
     * P16.2: open the viewer, then launch the desktop after the viewer
     * attaches and resizes the server (P6.5). Shared by the diagnostics
     * dialog's positive button and the status screen's viewer button.
     */
    private fun openViewerAndLaunchDesktop() {
        X11Session.openViewer(this)
        // Phase 1d: floating icon controls over the viewer.
        showViewerOverlay()
        // P6.5: launch the desktop AFTER the viewer attaches and
        // resizes the server (RandR). A plain Thread (not
        // lifecycleScope): this activity is only paused by the viewer,
        // not destroyed, so the thread survives the navigation. The
        // Toast is guarded by `destroyed` in case the user pressed
        // Back before the launch finished.
        Thread {
            Thread.sleep(4000)
            val sent = X11Session.launchDesktop(this, DESKTOP_LXQT)
            // Real-computer #8: the 3 s sleep here was redundant —
            // isDesktopRunning already polls twice (up to 20 s each
            // with a 3 s gap). The 4 s sleep above stays: the viewer
            // must attach and resize the server BEFORE the desktop
            // launches (P6.5), and there is no event for that.
            val running = X11Session.isDesktopRunning(this, DESKTOP_LXQT, true)
            // Real-computer #1: audio starts with the desktop when
            // enabled. Own thread: the pump connects (up to ~6 s)
            // and must not delay the Toast below.
            if (sent && running && PulseAudio.isEnabled(this)) {
                Thread { PulseAudio.start(this) }.start()
            }
            // p21: the brain servers start with the desktop. Before,
            // nothing started them on boot: the only callers of
            // AgentServer.ensureStarted were the agent tab (on send)
            // and the supervisor service (never started). Own thread:
            // ensureStarted polls up to 30 s and must not delay the
            // Toast below.
            if (sent && running) {
                startAgentServersInBackground()
            }
            val log = X11Session.getDesktopLog(this, DESKTOP_LXQT, DebianSystem.debianRootfsDir(this))
            Log.d("VncActivity", "P8 post-viewer launch: sent=$sent running=$running\n$log")
            // P18.5: fire a pending app launch (from the Apps tab)
            // only now that the desktop is confirmed running. The
            // command goes through the PTY session, like the desktop
            // launch itself — a one-shot proot could not keep the
            // app alive. Cleared whether it fires or not, so a
            // failed desktop never launches an app into a dead X.
            val appToLaunch = pendingLaunchApp
            pendingLaunchApp = null
            val appLaunched = if (appToLaunch != null && sent && running) {
                val ok = GuestSession.sendCommand(appToLaunch)
                Log.d("VncActivity", "pending app launch: sent=$ok cmd=$appToLaunch")
                ok
            } else {
                false
            }
            if (!destroyed) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (sent && running) {
                            if (appLaunched) getString(R.string.ui_vnc_app_launched)
                            else getString(R.string.ui_vnc_desktop_running)
                        } else getString(R.string.ui_vnc_desktop_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /**
     * p21: start the agent brain (opencode 4096, ollama 11434) on a
     * background thread. ensureStarted stages the start script and
     * runs it on the persistent GuestSession PTY, so the servers live
     * as long as the desktop session does (a one-shot probe proot
     * would reap them on exit, which is what the p21 rig test saw
     * for the manually started servers). Idempotent: a re-entry
     * costs nothing when the servers are already up.
     */
    private fun startAgentServersInBackground() {
        Thread {
            val ensured = AgentServer.ensureStarted(applicationContext)
            Log.d(
                "VncActivity",
                "agent servers on desktop boot: up=${ensured.up} reason=${ensured.reason}"
            )
        }.apply { isDaemon = true }.start()
    }

    private fun setStatus(text: String) {
        if (destroyed) return
        runOnUiThread { binding.statusText.text = text }
    }

    private fun startDesktop() {
        // P16.2: at most one run at a time (see the companion flag).
        // A second launch while a run is in progress just waits — it
        // must NOT spawn a second thread racing the first on the PTY
        // and the X server.
        if (!startInProgress.compareAndSet(false, true)) {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.ui_vnc_desktop_starting), Toast.LENGTH_SHORT).show()
            }
            return
        }
        Thread {
            try {
            // Real-computer #7: fast reattach — a healthy session
            // opens the viewer directly, no reboot-on-entry.
            if (tryFastReattach()) return@Thread
            // 0. PTY smoke test (study step 0): prove the PTY session
            // can be created and delivers bytes on this device BEFORE
            // building anything on it. If this fails, stop here and
            // say so — no more builds on an unproven mechanism.
            setStatus(getString(R.string.ui_vnc_status_pty_check))
            if (!GuestSession.ensure(this)) {
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog(
                        getString(R.string.ui_vnc_status_pty_missing, GuestSession.lastError ?: "")
                    )
                }
                return@Thread
            }
            if (!GuestSession.probePty(this)) {
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog(
                        getString(R.string.ui_vnc_status_pty_noconn, GuestSession.lastError ?: "")
                    )
                }
                return@Thread
            }

            // 1a. Kill the previous desktop's processes FIRST
            // (P5.24): the old PTY session's death leaves its desktop
            // processes as orphans owning the display, and the new
            // desktop's window manager then refuses to start. Only
            // when the guest is installed; a failure here is not
            // fatal — the fresh boot proceeds anyway.
            // P6.4: also clean the Alpine guest — the box may still
            // hold the live Alpine LXQt session from the 2026-11-07
            // test owning :6, and a surviving window manager blocks
            // the new desktop (P5.24).
            // Real-computer #8: the two kill one-shots are
            // independent — run them in parallel, join both.
            setStatus(getString(R.string.ui_vnc_status_clean_desktop))
            val killThreads = listOf(
                Thread { if (DebianSystem.isReady(this)) X11Session.killDebianDesktopProcesses(this) },
                Thread { if (RootfsManager.isReady(this)) X11Session.killDesktopProcesses(this) }
            )
            killThreads.forEach { it.start() }
            killThreads.forEach { it.join() }

            // Real-computer #8: the X server does not depend on the
            // guest boot (one-shots only need the rootfs), so it
            // starts in parallel with the container boot below.
            // The result is joined before the TCP gate (step 4).
            val xResult = AtomicReference<XResult?>(null)
            val xThread = Thread { xResult.set(runXStartup()) }
            xThread.start()

            // 1. Boot the container in the PTY session (Tiny:
            // clean slate, fresh boot on every entry).
            // P6: نظام مضيف boots DEBIAN in the PTY.
            setStatus(getString(R.string.ui_vnc_status_boot_mudif))
            if (!GuestSession.bootContainer(this, GuestSession.GuestSystem.DEBIAN)) {
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog(
                        getString(R.string.ui_vnc_status_boot_failed, GuestSession.lastError ?: "")
                    )
                }
                return@Thread
            }

            // 2. One-shot installers FIRST (proven path).
            // P17: NO install anymore — the desktop set ships INSIDE
            // the pre-built image (LXQt + openbox + qterminal +
            // firefox-esr + deepin/papirus/adwaita icons + dbus +
            // xkb-data, all pre-installed). ensureDebianDesktop only
            // checks the /root/.mudif_desktop_ok marker the image
            // bakes in. The orphan-kill stays: it clears any apt-get
            // orphaned by a killed P16 install on the upgrade path.
            setStatus(getString(R.string.ui_vnc_status_clean_install))
            X11Session.killOrphanedAptProcesses(this)

            setStatus(getString(R.string.ui_vnc_status_verify_system))
            val desktopOk = X11Session.ensureDebianDesktop(this)
            // P6.4 — the Deepin-style look for LXQt (navy wallpaper,
            // bloom-dark icons, dark panel, dark Qt/GTK): written
            // into the rootfs BEFORE the first launch, once per
            // rootfs (marker); skipped when already applied.
            val lookOk = if (desktopOk) {
                setStatus(getString(R.string.ui_vnc_status_look))
                LxqtLook.ensureLxqtLook(this)
            } else {
                false
            }

            // P16 XKB GUARD: if the desktop install did not complete,
            // the xkb data is not there either — do NOT start the X
            // server (it would die instantly with an empty
            // XKB_CONFIG_ROOT). Tell the user plainly instead.
            if (!desktopOk) {
                // Real-computer #8: the parallel X thread must be
                // joined before leaving, or it would keep starting
                // a server for a broken system.
                xThread.join()
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog(
                        getString(R.string.ui_vnc_status_incomplete)
                    )
                }
                return@Thread
            }

            // Real-computer #8: join the parallel X startup; a
            // failure there is reported exactly like the old
            // serial failure was.
            xThread.join()
            val xr = xResult.get()
            if (xr == null || !xr.ok) {
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog(xr?.error ?: getString(R.string.ui_vnc_status_x_failed))
                }
                return@Thread
            }

            // 4. TCP gate (P6.3.6): NEVER send the desktop command to
            // a dead server. Re-prove TCP here, on EVERY path, before
            // the dialog goes out.
            if (!X11Session.isTcpDisplayOpen()) {
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog(getString(R.string.ui_vnc_status_tcp_blocked))
                }
                return@Thread
            }

            // 4b. TCP value for the dialog, computed HERE on the
            // background thread (P5.25): isTcpDisplayOpen() does a
            // Socket.connect, which throws NetworkOnMainThreadException
            // on the UI thread — that is why the dialog always said
            // false while the socket was proven LISTEN+ESTABLISHED.
            val tcpOk = X11Session.isTcpDisplayOpen()

            // 4c. P6.5: the desktop does NOT launch here anymore. The
            // viewer resizes the server on attach (RandR); a desktop
            // launched before attach lays out at the server's 1280x1024
            // birth size and its panel lands off-screen (PROBE-P65).
            // It launches after the viewer opens, from the dialog
            // button below, once the size has settled at 1280x720
            // (exact mode, set by openViewer). No live wallpaper step
            // exists for LXQt — the wallpaper is baked into the look
            // configs before launch (pcmanfm-qt reads settings.conf
            // at startup).

            // 5. P19: the viewer opens DIRECTLY — the diagnostics
            // dialog no longer blocks the normal path. The dialog was
            // a report, not a gate (the desktop only launches after
            // the viewer opens, P6.5), so opening the viewer here is
            // the same action the dialog's positive button took.
            // The report is still built (same content, including
            // DesktopDiagnostics.hypothesisDebian()), logged, and kept
            // for the status screen's "التشخيص" button. Only when the
            // desktop is NOT ready does the dialog still appear, so
            // the user sees why it failed.
            if (destroyed) return@Thread
            val report = getString(
                R.string.ui_vnc_diag_report,
                GuestSession.alive, GuestSession.booted,
                X11Session.isXSocketPresent(this@VncActivity), tcpOk,
                desktopOk, lookOk,
                DesktopDiagnostics.hypothesisDebian(this@VncActivity)
            )
            Log.d("VncActivity", "diagnostics:\n$report")
            lastDiagnostics = report
            runOnUiThread {
                val ok = GuestSession.alive && GuestSession.booted && tcpOk
                setStatus(
                    if (ok) getString(R.string.ui_vnc_status_ready)
                    else getString(R.string.ui_vnc_desktop_failed)
                )
                binding.btnShowDiagnostics.visibility = View.VISIBLE
                if (ok) {
                    openViewerAndLaunchDesktop()
                } else {
                    // P16: branded dialog (title fixed: no stale P8).
                    MudifDialog.info(
                        this,
                        getString(R.string.ui_vnc_diag_title),
                        report,
                        getString(R.string.ui_vnc_diag_open_viewer),
                        onPositive = {
                            // P16.2: shared with the status screen button.
                            openViewerAndLaunchDesktop()
                        },
                        neutral = getString(R.string.ui_vnc_diag_view_logs),
                        onNeutral = {
                            startActivity(Intent(this, LogViewerActivity::class.java))
                        },
                        negative = getString(R.string.cancel)
                    )
                }
            }
            } catch (e: Exception) {
                // P16.2: a silent thread death used to leave the user
                // on a stuck status screen with no dialog and no error
                // (the P16.1 second-launch stall). Any failure now
                // surfaces as a dialog with a retry button instead.
                Log.e("VncActivity", "startDesktop failed", e)
                if (!destroyed) {
                    runOnUiThread {
                        showFailureDialog(getString(R.string.ui_vnc_unexpected_error, e.message))
                    }
                }
            } finally {
                startInProgress.set(false)
            }
        }.start()
    }

    /**
     * Real-computer #8 — the X-server startup sequence, extracted
     * unchanged from startDesktop step 3 so it can run in PARALLEL
     * with the container boot (the X server only needs the rootfs
     * for xkb-data, never the booted PTY session — the same split
     * Tiny Container uses). Returns ok/error instead of showing
     * dialogs: the caller shows the dialog after joining.
     */
    private data class XResult(val ok: Boolean, val error: String? = null)

    private fun runXStartup(): XResult {
        // 3. Start the X server (Tiny launchXServer) — but ONLY
        // if it is not already up. Judge by the real state
        // (socket + TCP listener), not by the in-memory flag.
        // P6.3.3 — نظام مضيف ALWAYS restarts the X server fresh.
        // P6.3.6 proved the cold-start restart sequence works
        // end to end (the old server dies, the fresh server
        // survives, the TCP gate holds) — that sequence, not
        // the old "fresh server" correlation, is why the
        // restart is kept on every entry. (The P6.3.3 comment
        // used to claim the fresh-server condition was "the one
        // proven to work" — P6.3.4 disproved that correlation.)
        // The stop is best-effort (guarded by the in-memory
        // flag; if this process never started the server, the
        // stop is a no-op and the flow falls back to the
        // surviving server).
        var serverUp = X11Session.isXSocketPresent(this) && X11Session.isTcpDisplayOpen()
        if (serverUp) {
            setStatus(getString(R.string.ui_vnc_status_restart_x))
            X11Session.stopXServer(this)
            // P6.3.6: the old waitForXSocketGone veto is GONE.
            // The P6.3.5 resume run proved it: a SIGKILLed server
            // never deletes its socket file, so the wait always
            // timed out (10 s), serverUp stayed true, startXServer
            // was SKIPPED, and startxfce4 was sent to a dead
            // server. TCP closing is the true death signal, and
            // the stale files are deleted by hand — but ONLY when
            // TCP proves no live server holds the display.
            X11Session.waitForTcpDisplayClosed(10000)
            if (X11Session.isTcpDisplayOpen()) {
                // Old server survived the stop — fall back to it
                // (never start a second server on :6).
            } else {
                X11Session.deleteStaleXSocketFiles(this)
                serverUp = false
            }
        }
        if (!serverUp) {
            // P16 XKB GUARD: make sure the keyboard data is there
            // (reinstall xkb-data once if it is missing), then start
            // the server only if the guard allows it.
            setStatus(getString(R.string.ui_vnc_status_check_xkb))
            if (!X11Session.ensureXkbData(this)) {
                return XResult(false, getString(R.string.ui_vnc_status_xkb_missing))
            }
            setStatus(getString(R.string.ui_vnc_status_start_x))
            val xkbRootfs = DebianSystem.debianRootfsDir(this)
            if (!X11Session.startXServer(this, xkbRootfs)) {
                return XResult(false, getString(R.string.ui_vnc_status_xkb_missing))
            }

            // 3. Wait ONLY for the socket file (Tiny waitForFile).
            if (!X11Session.waitForXSocket(this, 15000)) {
                return XResult(false, getString(R.string.ui_vnc_status_x_nosocket))
            }

            // 3b. Wait until the server accepts TCP on
            // 127.0.0.1:6006. The socket file can exist before the
            // server is ready, and the Unix socket connect from
            // inside proot is broken on this device, so the guest
            // uses TCP — prove the listener is there first.
            setStatus(getString(R.string.ui_vnc_status_wait_tcp))
            if (!X11Session.waitForTcpDisplay(10000)) {
                return XResult(false, getString(R.string.ui_vnc_status_tcp_closed))
            }
        }
        return XResult(true)
    }

    /**
     * Real-computer #7 — fast reattach. When the session is already
     * alive, the guest is booted, TCP 6006 answers and lxqt-session
     * is running, the desktop is up: open the viewer directly and
     * skip the whole reboot-on-entry (PTY probe, kills, boot, X
     * restart, sleeps). The check is ONE pgrep one-shot, so a dead
     * desktop falls through to the cold start in ~seconds, not
     * the ~43 s a full isDesktopRunning (2 attempts) would cost.
     * Returns true when the viewer was opened.
     */
    private fun tryFastReattach(): Boolean {
        if (!GuestSession.alive || !GuestSession.booted) return false
        if (!X11Session.isTcpDisplayOpen()) return false
        val r = TerminalSession.runCommandDebian(
            this, "pgrep -f '[l]xqt-session' >/dev/null 2>&1 && echo RUNNING", 10, 20
        )
        if (!r.output.contains("RUNNING")) return false
        Log.d("VncActivity", "fast reattach: session healthy, opening viewer directly")
        // p21: the session survived, but the brain servers may not
        // have; ensureStarted is a no-op when they are already up.
        startAgentServersInBackground()
        val appToLaunch = pendingLaunchApp
        pendingLaunchApp = null
        if (appToLaunch != null) GuestSession.sendCommand(appToLaunch)
        runOnUiThread {
            setStatus(getString(R.string.ui_vnc_status_ready))
            X11Session.openViewer(this)
            // Phase 1d: floating icon controls over the viewer.
            showViewerOverlay()
        }
        return true
    }

    /** Shown when the container or the X server fails to start. */
    private fun showFailureDialog(reason: String) {
        if (destroyed) return
        // P16: branded dialog.
        MudifDialog.info(
            this,
            getString(R.string.ui_vnc_fail_title),
            getString(R.string.ui_vnc_fail_logs_hint, reason),
            getString(R.string.ui_vnc_view_logs),
            onPositive = {
                startActivity(Intent(this, LogViewerActivity::class.java))
            },
            neutral = getString(R.string.ui_vnc_fail_retry),
            onNeutral = {
                startDesktop()
            },
            negative = getString(R.string.ui_vnc_close),
            onNegative = {
                finish()
            }
        )
    }

    override fun onDestroy() {
        destroyed = true
        // Phase 1d: remove the floating controls with the session.
        viewerOverlay?.hide()
        viewerOverlay = null
        // P5.11: do NOT stop the X server or the container session here.
        // Leaving this screen (back button / switching to the terminal
        // tab) used to kill the server under the user's feet. The
        // desktop now persists until the next entry reboots it fresh
        // (Tiny Container's clean slate on entry, not on exit).
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_DESKTOP = "desktop"
        const val DESKTOP_LXQT = "lxqt"
        /** P18.5: full shell command for one app to launch after the
         *  desktop is confirmed running (handed in by the Apps tab). */
        const val EXTRA_LAUNCH_APP = "launch_app"
        /** P6 — نظام مضيف: Debian 13.1 + LXQt, the ONE system. */
        const val DESKTOP_MUDIF = "mudif"

        /**
         * P16.2: at most ONE startDesktop run at a time, across ALL
         * VncActivity instances. A second launch while a run is in
         * progress used to spawn a second thread that raced the first
         * on the PTY session and the X server (the P16.1 second-launch
         * stall: the restart stalled after the stale-socket cleanup
         * and no dialog ever appeared). The flag is reset in a
         * finally block, so a failed run never blocks the next one.
         */
        private val startInProgress = AtomicBoolean(false)
    }
}
