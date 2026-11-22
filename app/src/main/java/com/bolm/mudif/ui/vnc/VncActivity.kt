package com.bolm.mudif.ui.vnc

import android.content.Context
import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import com.bolm.mudif.ui.MudifActivity
import androidx.core.content.ContextCompat
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ActivityVncBinding
import com.bolm.mudif.engine.CastManager
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.DesktopDiagnostics
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.LxqtLook
import com.bolm.mudif.engine.PulseAudio
import com.bolm.mudif.engine.RootfsManager
import com.bolm.mudif.engine.ScreenRecorder
import com.bolm.mudif.engine.SystemDisplay
import com.bolm.mudif.engine.TvStreamer
import com.bolm.mudif.engine.X11Session
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.keyboard.KeyboardSetup
import com.bolm.mudif.ui.logs.LogViewerActivity
import com.bolm.mudif.ui.settings.ViewerPrefs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.bolm.mudif.engine.DirectDesktop
import com.bolm.mudif.engine.MudifLog

/**
 * P5.22 RADICAL REBUILD, X11 desktop via Termux:X11, Tiny
 * Container's x11 branch exactly.
 *
 * Tiny (`ContainerMainViewModel`, x11 feature):
 *  1. Boot the container in the PTY session (fresh, clean slate).
 *  2. `launchXServer(args)` in parallel.
 *  3. `waitForFile(.../.X11-unix/X6)`, 15 s, exists() only.
 *  4. `Global.sendCommand(command)`, ONE backgrounded desktop
 *     command into the PTY session.
 *  5. Open `com.termux.x11.MainActivity`.
 *
 * No launch gates, no marker-file round trips, no isRunning checks,
 * no xterm. The dialog is a STATUS REPORT, not a verdict derived
 * from a broken pipe: success = PTY alive + guest booted + X socket
 * up + desktop command sent. The viewer shows the truth.
 *
 * P6.4, نظام مضيف is the ONLY system in this app (the user's
 * order 2026-11-07: "خاص يبقى في التطبيق نظامي فقط اما باقي
 * الانظمة لا"). Debian 13.1 + LXQt, Deepin-style look baked into
 * the install flow. The Alpine rows are gone from the profile tab,
 * the store lists Debian only, and this activity no longer has an
 * Alpine path at all. The intent extra is ignored: every launch
 * opens نظام مضيف.
 * P6.5, the desktop launches AFTER the viewer opens (from the
 * dialog button): the viewer resizes the server on attach, and a
 * desktop launched before attach lays out at 1280x1024 with its
 * panel off-screen. The viewer is pinned to exact 1280x720
 * (openViewer) so the size settles there before the launch.
 * Lifecycle: entering this screen ALWAYS reboots the container
 * fresh (Tiny Container's clean slate). Leaving the screen does NOT
 * kill the session or the X server (P5.11 kept): the desktop stays
 * alive until the app process dies or the next entry reboots it.
 */
class VncActivity : MudifActivity() {

    private lateinit var binding: ActivityVncBinding

    @Volatile
    private var destroyed = false

    /**
     * P18.5: a pending app launch, handed in by the Apps tab
     * (EXTRA_LAUNCH_APP = the full shell command). It fires only
     * after the desktop is confirmed running, inside
     * openViewerAndLaunchDesktop, never into a dead X server.
     */
    @Volatile
    private var pendingLaunchApp: String? = null

    /**
     * p92 N-b: the process name for the pending launch, carried in
     * the intent next to EXTRA_LAUNCH_APP so the retry can prove
     * the app actually started (same check as
     * AppLauncher.verifyStarted). Null only for intents built
     * before p92, which cannot occur from the current producer.
     */
    @Volatile
    private var pendingLaunchProcess: String? = null

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

    // p29: BACK exits the viewer. The p28 onBackPressedDispatcher callback
    // never fired on the rig (pressed 3x, viewer stayed) because the
    // Termux:X11 view consumes the BACK key at the View level before it
    // reaches the Activity's onBackPressed. dispatchKeyEvent runs before
    // the view sees the key, so intercepting KEYCODE_BACK here (ACTION_DOWN)
    // and finishing is the only reliable path. The desktop session itself
    // keeps running (see onDestroy).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingLaunchApp = intent.getStringExtra(EXTRA_LAUNCH_APP)
        pendingLaunchProcess = intent.getStringExtra(EXTRA_LAUNCH_PROCESS)

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
        // still opens the viewer and starts the desktop, the screen is
        // never a dead end. The TCP check runs off the UI thread
        // (Socket.connect throws NetworkOnMainThreadException, P5.25).
        binding.btnOpenViewer.setOnClickListener {
            // p95: the gate must require a LIVE session, not just
            // a listening X server. The p93 round (20:34) proved
            // the old TCP-only check attaches to a LIVE-but-
            // sessionless X (black desktop, orphaned window only)
            // when lxqt-session is dead. isDesktopSessionAlive
            // is one guest command (non-zombie lxqt-session AND
            // openbox); false fails closed into startDesktop(),
            // which does the full start. The check runs off the
            // UI thread already, so the one-shot probe cannot
            // block the UI.
            Thread {
                if (X11Session.isTcpDisplayOpen() && X11Session.isDesktopSessionAlive(this)) {
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

        // Real-computer #5, screen recording. The button toggles:
        // start (ffmpeg in the guest, viewer opens so you can see
        // what is being recorded) or stop (the mp4 lands in the
        // shared folder). One-shots run off the UI thread.
        binding.btnRecord.setOnClickListener {
            Thread {
                if (ScreenRecorder.isRecording(this)) {
                    val out = ScreenRecorder.stop(this)
                    ScreenRecorder.hideStopNotification(this)
                    runOnUiThread {
                        setRecordIcon(R.drawable.avd_stop_to_record)
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
                            setRecordIcon(R.drawable.avd_record_to_stop)
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

        // Phase 2, desktop on TV, three paths (research order):
        // Cast live-HLS (in-app, wireless), system display
        // (Presentation: Smart View / DeX / HDMI), system wireless
        // settings (Miracast fallback). The TV shows the live
        // desktop; it does not boot one, so Cast refuses until the
        // desktop is up. When streaming, the button stops it.
        // p36 defect 3: the chooser lives in showTvChooser(), so the
        // viewer overlay routes its TV button through the same
        // dialog (Cast first) instead of opening settings directly.
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
            showTvChooser()
        }


        // Real-computer #1, audio passthrough. The button toggles
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

        // Version indicator, reads the live marker, never a stale label.
        Toast.makeText(this, getString(R.string.ui_vnc_version_toast, LxqtLook.MARKER_VERSION), Toast.LENGTH_LONG).show()

        startDesktop()
    }

    /**
     * p36 defect 3, the ONE TV chooser: Cast first, system display
     * second. Shared by btnTv and by the viewer overlay (which
     * routes here through onNewIntent, EXTRA_SHOW_TV_CHOOSER).
     * Public so ViewerOverlay can reach it through its activity.
     */
    fun showTvChooser() {
        MudifDialog.info(
            this, getString(R.string.tv_title),
            getString(R.string.tv_choose_body),
            getString(R.string.tv_choose_cast),
            onPositive = { startCastFlow() },
            neutral = getString(R.string.tv_choose_system),
            onNeutral = {
                Thread {
                    val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                    val displays = dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                    if (displays.isEmpty()) {
                        // No system display. Resolve-check at dialog
                        // time: when the phone has no wireless-display
                        // settings page, do NOT offer "open system
                        // settings" as a TV path, the plain Display
                        // settings page has no TV picker (Nova 9).
                        val hasWirelessSettings = SystemDisplay.hasWirelessDisplaySettings(this)
                        val castAvailable = CastManager.available
                        runOnUiThread {
                            if (hasWirelessSettings) {
                                // The page exists, so the Miracast
                                // dialog may honestly offer it.
                                MudifDialog.info(
                                    this, getString(R.string.tv_title),
                                    getString(R.string.tv_miracast_body),
                                    getString(R.string.tv_miracast_open_button),
                                    onPositive = {
                                        if (SystemDisplay.openWirelessDisplaySettings(this)) {
                                            Toast.makeText(this, getString(R.string.tv_miracast_open), Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(this, getString(R.string.tv_miracast_unavailable), Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    negative = getString(R.string.cancel)
                                )
                            } else {
                                // No wireless-display page on this
                                // phone: honest dialog, no settings
                                // button at all. The real options are
                                // Cast (when Play Services exist) or
                                // the vendor path in the body text
                                // (Huawei wireless projection, Samsung
                                // Smart View / DeX). Instructions text
                                // only, no unverified deep links.
                                val castAction: (() -> Unit)? =
                                    if (castAvailable) ({ startCastFlow() }) else null
                                MudifDialog.info(
                                    this, getString(R.string.tv_title),
                                    getString(R.string.tv_no_wireless_settings_body),
                                    if (castAvailable) getString(R.string.tv_choose_cast) else getString(R.string.ok),
                                    onPositive = castAction,
                                    negative = getString(R.string.cancel)
                                )
                            }
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

    /**
     * Cast live-HLS flow, extracted verbatim from the old chooser
     * positive button so both the chooser and the no-settings
     * dialog start Cast the same way. Refuses until the desktop
     * is up; refuses without Play Services; otherwise starts
     * TvStreamer and opens the route chooser unless a Cast
     * session is already connected.
     */
    private fun startCastFlow() {
        if (!GuestSession.booted || !X11Session.isTcpDisplayOpen()) {
            Toast.makeText(this, getString(R.string.ui_vnc_desktop_not_ready), Toast.LENGTH_LONG).show()
            return
        }
        if (!CastManager.available) {
            MudifDialog.info(this, getString(R.string.tv_title), getString(R.string.tv_cast_no_play_services), getString(R.string.ok))
            return
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
        // p77: the viewer stub's "تشغيل سطح المكتب" button brings
        // this activity forward to (re)start the desktop. Handled
        // before the pending-app line below so a pending app launch
        // is never clobbered by the retry.
        if (intent.getBooleanExtra(EXTRA_START_DESKTOP, false)) {
            startDesktop()
            return
        }
        // p36 defect 3: the viewer overlay routes its TV button here
        // (EXTRA_SHOW_TV_CHOOSER) so the user gets the same chooser
        // as btnTv, Cast first, instead of a direct settings open.
        // This must NOT fall through to startDesktop() below: the
        // desktop is already running behind the viewer, and a
        // re-entry would reopen the viewer over the chooser.
        if (intent.getBooleanExtra(EXTRA_SHOW_TV_CHOOSER, false)) {
            showTvChooser()
            return
        }
        // A fresh launch request replaces any pending one.
        pendingLaunchApp = intent.getStringExtra(EXTRA_LAUNCH_APP)
        pendingLaunchProcess = intent.getStringExtra(EXTRA_LAUNCH_PROCESS)
        startDesktop()
    }

    // Record button icon morph (200ms): circle fades to square
    // when recording starts, square back to circle when it stops.
    // The onResume state restore keeps the static icon (no
    // animation for a state the user did not just change).
    private fun setRecordIcon(@DrawableRes avdRes: Int) {
        val drawable = ContextCompat.getDrawable(this, avdRes) ?: return
        binding.btnRecord.setImageDrawable(drawable)
        (drawable as? AnimatedVectorDrawable)?.start()
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
        // (HOME, viewer opens). The FAB stays, it floats over the
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
     * p118: one-time Mudif keyboard prompt, right after the viewer
     * opens (same spot as the overlay prompt above). The keyboard
     * is ready; the user enables it once in the system settings
     * and it then appears in the viewer instead of Gboard. If it
     * is already enabled there is nothing to prompt about (the
     * flag is still marked, so this never shows again). "Later"
     * dismisses it; the Settings card stays the permanent route.
     */
    private fun promptKeyboardOnce() {
        if (KeyboardSetup.isPromptShown(this)) return
        KeyboardSetup.markPromptShown(this)
        if (KeyboardSetup.isEnabled(this)) return
        MudifDialog.info(
            this,
            getString(R.string.keyboard_prompt_title),
            getString(R.string.keyboard_prompt_body),
            getString(R.string.keyboard_prompt_enable),
            onPositive = { KeyboardSetup.openInputMethodSettings(this) },
            negative = getString(R.string.keyboard_prompt_later)
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
        // p118: one-time keyboard prompt (the Mudif keyboard is
        // ready; the user enables it once in the system settings
        // and it then appears in the viewer instead of Gboard).
        promptKeyboardOnce()
        // P6.5: launch the desktop AFTER the viewer attaches and
        // resizes the server (RandR). A plain Thread (not
        // lifecycleScope): this activity is only paused by the viewer,
        // not destroyed, so the thread survives the navigation. The
        // Toast is guarded by `destroyed` in case the user pressed
        // Back before the launch finished.
        Thread {
            // p79 R2: wait for the viewer to ATTACH instead of a
            // fixed 4 s sleep. MainActivity.isConnected() is the
            // fork's own attach flag (LorieView.connected(), public
            // static, no fork change): false until the viewer socket
            // connects to the X server. Poll every 200 ms, cap 4 s,
            // so this is never slower than the old sleep. A 500 ms
            // settle after the flag first reads true lets the RandR
            // resize land (the flag flips when connect() returns,
            // the resize is issued in the same tryConnect block).
            // P6.5 still holds: the desktop launches only after the
            // viewer attached and resized, never before.
            // p80: the check itself runs on the MAIN thread
            // (isViewerConnected). The p79 direct call from this
            // worker thread crashed the app 4/4: the first touch
            // of MainActivity runs its static initializer, which
            // creates a Handler (MainActivity.java:80) on the
            // calling thread, which has no Looper.
            waitForViewerAttach()
            val sent = X11Session.launchDesktop(this, DESKTOP_LXQT)
            // Real-computer #8: isDesktopRunning already polls twice
            // (up to 20 s each with a 3 s gap), no extra sleep here.
            val running = X11Session.isDesktopRunning(this, DESKTOP_LXQT, true)
            // Real-computer #1: audio starts with the desktop when
            // enabled. Own thread: the pump connects (up to ~6 s)
            // and must not delay the Toast below.
            // p123 P8: no auto-start in a minimal session (API 31+):
            // the :6 session prevents pulseaudio, and this auto-start
            // would re-create it plus its autolaunch D-Bus pair
            // (3 phantoms). The manual audio toggle still works, and
            // API 30 (no phantom killer) auto-starts as before.
            if (sent && running && PulseAudio.isEnabled(this) && !DirectDesktop.isMinimalSession()) {
                Thread { PulseAudio.start(this) }.start()
            }
            // p79 R1: the brain servers do NOT start with the
            // desktop anymore. Nothing starts until the user asks
            // for it (Tiny/Winlator/Mobox rule): ollama + opencode
            // start on the first assistant turn only
            // (AgentServer.ensureStarted, called on send). A
            // desktop-only entry now pays no server startup at all.
            val log = X11Session.getDesktopLog(this, DESKTOP_LXQT, DebianSystem.debianRootfsDir(this))
            MudifLog.d("VncActivity", "P8 post-viewer launch: sent=$sent running=$running\n$log")
            // P18.5 (p92 N-b): fire a pending app launch (from the Apps tab)
            // only now that the desktop is confirmed running. The
            // command goes through the PTY session, like the desktop
            // launch itself, a one-shot proot could not keep the
            // app alive. The entry is held, not cleared, until the
            // launch is verified or the attempt budget is spent: the
            // single post-launch check could run before openbox was
            // up (p89), and clearing it either way dropped the app.
            val appToLaunch = pendingLaunchApp
            val processToLaunch = pendingLaunchProcess
            var appLaunched = false
            var desktopUp = sent && running
            var abandoned = false
            if (appToLaunch != null) {
                var attempt = 0
                while (attempt < 3 && !appLaunched) {
                    attempt++
                    if (attempt > 1) {
                        if (destroyed) { abandoned = true; break }
                        Thread.sleep(10_000)
                        if (destroyed) { abandoned = true; break }
                        desktopUp = X11Session.isDesktopSessionAlive(this)
                    }
                    if (!desktopUp) {
                        MudifLog.d("VncActivity", "pending app launch: attempt $attempt skipped, desktop not up")
                        continue
                    }
                    // Take 1: the desktop is :6 itself now, and the
                    // PTY shell's DISPLAY is already 127.0.0.1:6, so
                    // the pending command goes as-is, no prefix.
                    val ok = GuestSession.sendCommand(appToLaunch)
                    MudifLog.d("VncActivity", "pending app launch: attempt $attempt sent=$ok cmd=$appToLaunch")
                    if (!ok) continue
                    appLaunched = if (processToLaunch != null) {
                        verifyGuestProcess(processToLaunch)
                    } else {
                        MudifLog.d("VncActivity", "pending app launch: no process name, send-only")
                        true
                    }
                }
                if (abandoned) {
                    MudifLog.d("VncActivity", "pending app launch: abandoned, activity destroyed")
                } else if (appLaunched) {
                    MudifLog.d("VncActivity", "pending app launch: verified after $attempt attempt(s)")
                } else {
                    MudifLog.d("VncActivity", "pending app launch: FAILED after $attempt attempt(s), dropping entry")
                }
                pendingLaunchApp = null
                pendingLaunchProcess = null
            }
            if (!destroyed) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (desktopUp) {
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
     * p79 R2: wait until the viewer attached to the X server, cap
     * 4 s. Polls the fork's own attach flag every 200 ms; a 500 ms
     * settle after the first true lets the RandR resize land. The
     * cap keeps this never slower than the old fixed 4 s sleep; on
     * timeout the launch proceeds exactly as before.
     *
     * p80: the attach check runs on the MAIN thread, never on this
     * worker thread ([isViewerConnected]). The p79 direct call
     * crashed the app 4/4: the first touch of MainActivity runs
     * its static initializer, which creates a Handler
     * (MainActivity.java:80) on the calling thread, which has no
     * Looper, so the <clinit> throws and the process dies.
     */
    private fun waitForViewerAttach(timeoutMs: Long = 4000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (isViewerConnected()) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining > 500) Thread.sleep(500)
                MudifLog.d("VncActivity", "viewer attached, launching desktop")
                return
            }
            Thread.sleep(200)
        }
        MudifLog.d("VncActivity", "viewer attach not seen in ${timeoutMs}ms, launching desktop anyway")
    }

    /**
     * p92 N-b: process-level proof for a pending launch, same
     * schedule as AppLauncher.verifyStarted (first poll at 4 s,
     * then every 5 s, 60 s budget, two consecutive alive polls).
     * A single alive poll is not proof: a crashing program can
     * exist at one poll and be dead at the next (p83).
     */
    private fun verifyGuestProcess(process: String): Boolean {
        val deadline = System.currentTimeMillis() + 60_000L
        var seenAlive = false
        var delayMs = 4000L
        while (true) {
            Thread.sleep(delayMs)
            delayMs = 5000L
            if (X11Session.isGuestProcessAlive(this, process)) {
                if (seenAlive) return true
                seenAlive = true
            } else {
                seenAlive = false
            }
            if (System.currentTimeMillis() >= deadline) return false
        }
    }

    /**
     * p80: read the fork's attach flag on the MAIN thread only.
     * MainActivity's static initializer creates a Handler, so the
     * first touch of the class from a worker thread crashes the
     * app (p79, 4/4 desktop entries). The worker thread blocks on
     * a latch (2 s cap per check) while the main thread runs the
     * call; a wedged main thread degrades to "not attached" for
     * that check, never to a hang. Returns false, never throws.
     */
    private fun isViewerConnected(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                com.termux.x11.MainActivity.isConnected()
            } catch (_: Exception) {
                false
            }
        }
        var result = false
        val latch = CountDownLatch(1)
        runOnUiThread {
            result = try {
                com.termux.x11.MainActivity.isConnected()
            } catch (_: Exception) {
                false
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return result
    }

    private fun setStatus(text: String) {
        if (destroyed) return
        runOnUiThread { binding.statusText.text = text }
    }

    private fun startDesktop() {
        // P16.2: at most one run at a time (see the companion flag).
        // A second launch while a run is in progress just waits, it
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
            // Real-computer #7: fast reattach, a healthy session
            // opens the viewer directly, no reboot-on-entry.
            if (tryFastReattach()) return@Thread
            // 0. PTY smoke test (study step 0): prove the PTY session
            // can be created and delivers bytes on this device BEFORE
            // building anything on it. If this fails, stop here and
            // say so, no more builds on an unproven mechanism.
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
            // fatal, the fresh boot proceeds anyway.
            // P6.4: also clean the Alpine guest, the box may still
            // hold the live Alpine LXQt session from the 2026-11-07
            // test owning :6, and a surviving window manager blocks
            // the new desktop (P5.24).
            // Real-computer #8: the two kill one-shots are
            // independent, run them in parallel, join both.
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
            // P17: NO install anymore, the desktop set ships INSIDE
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
            // Take 1: the look may re-stage below (theme
            // switch or marker bump). Record whether it was applied
            // BEFORE, so the direct step knows a re-stage happened.
            val lookWasApplied = if (desktopOk) LxqtLook.isApplied(this) else true
            // P6.4, the Deepin-style look for LXQt (navy wallpaper,
            // bloom-dark icons, dark panel, dark Qt/GTK): written
            // into the rootfs BEFORE the first launch, once per
            // rootfs (marker); skipped when already applied.
            val lookOk = if (desktopOk) {
                setStatus(getString(R.string.ui_vnc_status_look))
                LxqtLook.ensureLxqtLook(this)
            } else {
                false
            }

            // p95: re-kill the stale desktop processes AFTER
            // staging. Step 1a runs pre-boot and can miss them:
            // the p93 round proved a pcmanfm-desktop from the
            // pre-install session survives into the first
            // post-install start and renders the desktop with
            // its cached (negative) icon lookups, so Firefox +
            // the launcher show as text only. The container is
            // booted now and the theme is staged, so a second
            // kill here leaves nothing stale for the startlxqt
            // below, which then starts a FRESH pcmanfm-desktop
            // that reads the staged theme. Best-effort: a
            // failure here must never block the desktop (same
            // as step 1a). NOT marker-gated: the kill must fire
            // on every full start, or the defect returns on the
            // next marker-less build. The fast-reattach path
            // above returns before this point, so a healthy
            // live session is never killed.
            try {
                X11Session.killDebianDesktopProcesses(this)
            } catch (e: Exception) {
                MudifLog.d("VncActivity", "p95 post-staging desktop kill failed: ${e.message}")
            }

            // Take 1: start the direct :6 desktop now. The gate
            // is desktopOk ONLY, never lookOk: the look's
            // pcmanfm-qt / mudif-settings .debs are amd64-only
            // (dpkg refuses them on arm64 with "package
            // architecture (amd64) does not match system (arm64)"),
            // so on Anas's phone ensureLxqtLook returns false on
            // every marker-bump launch. Gating the direct step on
            // lookOk would mean :6 never starts on his phone at
            // all. Theme-restart rule: restart :6 only when the
            // look actually SUCCEEDED in re-staging (lookOk and
            // !lookWasApplied) and :6 was alive, or the new theme
            // never takes effect. A FAILED re-stage must NOT
            // restart :6: on the phone the look fails on every
            // entry, so restarting on failure would kill the
            // surviving session on every desktop entry. The live
            // wallpaper flip (stageThemeSwitch, :6-targeted)
            // still applies without a restart; only the full
            // re-skin waits for the next fresh :6 start. There is
            // no viewer to kill first anymore, and no VNC
            // reconnect dialog to avoid. When the direct session
            // cannot start here (the :6 size has not settled yet,
            // the viewer has not attached), ensureDirectDesktop
            // DEFERS and launchDesktop starts it after the viewer
            // attaches, so a false here never leaves the user
            // with no desktop at all.
            val directOk = if (desktopOk) {
                val wasAlive = DirectDesktop.isDirectDesktopAlive(this)
                if (lookOk && !lookWasApplied && wasAlive) {
                    DirectDesktop.restartDirectDesktop(this)
                } else {
                    DirectDesktop.ensureDirectDesktop(this)
                }
            } else {
                false
            }
            MudifLog.d("VncActivity", "direct desktop :6 ok=$directOk (lookOk=$lookOk lookWasApplied=$lookWasApplied)")

            // P16 XKB GUARD: if the desktop install did not complete,
            // the xkb data is not there either, do NOT start the X
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
            // on the UI thread, that is why the dialog always said
            // false while the socket was proven LISTEN+ESTABLISHED.
            val tcpOk = X11Session.isTcpDisplayOpen()

            // 4c. P6.5: the desktop does NOT launch here anymore. The
            // viewer resizes the server on attach (RandR); a desktop
            // launched before attach lays out at the server's 1280x1024
            // birth size and its panel lands off-screen (PROBE-P65).
            // It launches after the viewer opens, from the dialog
            // button below, once the size has settled at 1280x720
            // (exact mode, set by openViewer). No live wallpaper step
            // exists for LXQt, the wallpaper is baked into the look
            // configs before launch (pcmanfm-qt reads settings.conf
            // at startup).

            // 5. P19: the viewer opens DIRECTLY, the diagnostics
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
            MudifLog.d("VncActivity", "diagnostics:\n$report")
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
     * Real-computer #8, the X-server startup sequence, extracted
     * unchanged from startDesktop step 3 so it can run in PARALLEL
     * with the container boot (the X server only needs the rootfs
     * for xkb-data, never the booted PTY session, the same split
     * Tiny Container uses). Returns ok/error instead of showing
     * dialogs: the caller shows the dialog after joining.
     */
    private data class XResult(val ok: Boolean, val error: String? = null)

    private fun runXStartup(): XResult {
        // 3. Start the X server (Tiny launchXServer), but ONLY
        // if it is not already up. Judge by the real state
        // (socket + TCP listener), not by the in-memory flag.
        // P6.3.3, نظام مضيف ALWAYS restarts the X server fresh,
        // EXCEPT slice 14: a healthy server owned by a live
        // session is reused (see below), a restart would kill
        // the launchDirect program running on it.
        // P6.3.6 proved the cold-start restart sequence works
        // end to end (the old server dies, the fresh server
        // survives, the TCP gate holds), that sequence, not
        // the old "fresh server" correlation, is why the
        // restart is kept for servers no live session owns
        // (slice 14 reuses an owned healthy server instead).
        // used to claim the fresh-server condition was "the one
        // proven to work", P6.3.4 disproved that correlation.)
        // The stop is best-effort (guarded by the in-memory
        // flag; if this process never started the server, the
        // stop is a no-op and the flow falls back to the
        // surviving server).
        var serverUp = X11Session.isXSocketPresent(this) && X11Session.isTcpDisplayOpen()
        if (serverUp) {
            // Slice 14 (killer build): reuse a healthy :6 server
            // instead of restarting it. A restart destroys every
            // :6 client, including a launchDirect program (slice 4:
            // opening the desktop killed the running Notepad).
            // Reuse only when a live session owns this server
            // right now: this process started it (xserverRunning,
            // set by launchDirect or an earlier start) or the
            // viewer is currently attached to it
            // (isViewerConnected). A stale orphan server from a
            // previous process (neither true) still gets the old
            // stop + restart below, so cold starts keep their
            // clean slate.
            if (X11Session.xserverRunning || isViewerConnected()) {
                MudifLog.d("VncActivity", "runXStartup: :6 healthy and owned, reusing (no restart)")
                return XResult(true)
            }
            setStatus(getString(R.string.ui_vnc_status_restart_x))
            X11Session.stopXServer(this)
            // P6.3.6: the old waitForXSocketGone veto is GONE.
            // The P6.3.5 resume run proved it: a SIGKILLed server
            // never deletes its socket file, so the wait always
            // timed out (10 s), serverUp stayed true, startXServer
            // was SKIPPED, and startxfce4 was sent to a dead
            // server. TCP closing is the true death signal, and
            // the stale files are deleted by hand, but ONLY when
            // TCP proves no live server holds the display.
            X11Session.waitForTcpDisplayClosed(10000)
            if (X11Session.isTcpDisplayOpen()) {
                // Old server survived the stop, fall back to it
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
            // p78: the socket file can appear LATE under memory
            // pressure (rig-proven: the failure dialog fired while
            // the detailed log showed socket X6 exists + tcp 6006
            // open). startXServer returns at once, so the 15 s
            // window can expire while the server is still coming
            // up. Before declaring the server dead, give the TCP
            // listener its chance: if it answers, the server is up
            // and the boot continues (step 3b re-checks it right
            // after). Only when TCP also stays closed is the
            // failure real. Falsifier: if the dialog still fires
            // AND the log at dialog time shows TCP 6006 open, the
            // mechanism is deeper than a slow start.
            if (!X11Session.waitForXSocket(this, 15000)) {
                setStatus(getString(R.string.ui_vnc_status_wait_tcp))
                if (!X11Session.waitForTcpDisplay(10000)) {
                    return XResult(false, getString(R.string.ui_vnc_status_x_nosocket))
                }
            }

            // 3b. Wait until the server accepts TCP on
            // 127.0.0.1:6006. The socket file can exist before the
            // server is ready, and the Unix socket connect from
            // inside proot is broken on this device, so the guest
            // uses TCP, prove the listener is there first.
            setStatus(getString(R.string.ui_vnc_status_wait_tcp))
            if (!X11Session.waitForTcpDisplay(10000)) {
                return XResult(false, getString(R.string.ui_vnc_status_tcp_closed))
            }
        }
        return XResult(true)
    }

    /**
     * Real-computer #7, fast reattach. When the session is already
     * alive, the guest is booted, TCP 6006 answers and a live
     * lxqt-session plus openbox are running, the desktop is up: open
     * the viewer directly and skip the whole reboot-on-entry (PTY
     * probe, kills, boot, X restart, sleeps). p34g: the check is
     * X11Session.isDesktopSessionAlive, ONE Debian one-shot, so a
     * dead desktop still falls through to the cold start in
     * ~seconds, not the ~43 s a full isDesktopRunning (2 attempts)
     * would cost. A raw pgrep here matched zombie lxqt-session pids
     * and attached the viewer to a dead desktop (p34f); the cold
     * start it now falls through to kills the stale processes first
     * (step 1a) and boots fresh.
     * Returns true when the viewer was opened.
     */
    private fun tryFastReattach(): Boolean {
        if (!GuestSession.alive || !GuestSession.booted) return false
        if (!X11Session.isTcpDisplayOpen()) return false
        // Take 1: the desktop lives on :6, so a healthy session
        // is :6 TCP up plus a live lxqt-session and openbox
        // (isDesktopSessionAlive, display-agnostic). There is no
        // :7 tree and no vncviewer to check anymore. A live
        // session with the viewer already attached reattaches
        // directly; anything else falls through to the full
        // start, which cold-starts the desktop.
        if (!X11Session.isDesktopSessionAlive(this)) return false
        MudifLog.d("VncActivity", "fast reattach: session healthy, opening viewer directly")
        // p79 R1: the brain servers are NOT started here anymore.
        // They start on the first assistant turn only
        // (AgentServer.ensureStarted, called on send).
        val appToLaunch = pendingLaunchApp
        pendingLaunchApp = null
        if (appToLaunch != null) {
            // Take 1: the PTY shell's DISPLAY is already
            // 127.0.0.1:6, so the pending command goes as-is.
            GuestSession.sendCommand(appToLaunch)
        }
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
        const val EXTRA_LAUNCH_PROCESS = "launch_process"
        /**
         * p36 defect 3: set by the viewer overlay when its TV button
         * is tapped with no presentation display. VncActivity is
         * brought forward and onNewIntent opens the TV chooser
         * (showTvChooser) instead of restarting the desktop.
         */
        const val EXTRA_SHOW_TV_CHOOSER = "show_tv_chooser"
        /**
         * p77: set by the viewer stub's "تشغيل سطح المكتب" button
         * (the fork's MainActivity). VncActivity is brought forward
         * and onNewIntent (re)starts the desktop. A fresh instance
         * needs no handling: onCreate auto-starts it.
         */
        const val EXTRA_START_DESKTOP = "start_desktop"
        /** P6, نظام مضيف: Debian 13.1 + LXQt, the ONE system. */
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
