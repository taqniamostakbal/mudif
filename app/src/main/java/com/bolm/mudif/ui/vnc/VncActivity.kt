package com.bolm.mudif.ui.vnc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.databinding.ActivityVncBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.DesktopDiagnostics
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.LxqtLook
import com.bolm.mudif.engine.PulseAudio
import com.bolm.mudif.engine.RootfsManager
import com.bolm.mudif.engine.ScreenRecorder
import com.bolm.mudif.engine.TerminalSession
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingLaunchApp = intent.getStringExtra(EXTRA_LAUNCH_APP)

        supportActionBar?.title = "نظام مضيف"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

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
                    "لا توجد معلومات تشخيص بعد — افتح سطح المكتب أولا",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                MudifDialog.info(
                    this,
                    "تشخيص — نظام مضيف",
                    report,
                    "إغلاق"
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
                            "سطح المكتب مازال ما وجدش — تسنى حتى يكمل",
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
                        binding.btnRecord.text = getString(R.string.record_start)
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
                            Toast.makeText(this, "سطح المكتب مازال ما وجدش — تسنى حتى يكمل", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    val startedFile = ScreenRecorder.start(this)
                    runOnUiThread {
                        if (startedFile != null) {
                            binding.btnRecord.text = getString(R.string.record_stop)
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

        // Real-computer #9 — desktop on TV. The TV shows the live
        // desktop; it does not boot one, so the button refuses until
        // the desktop is up. The TV gets its own viewer prefs.
        binding.btnTv.setOnClickListener {
            Thread {
                val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                val displays = dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                if (displays.isEmpty()) {
                    runOnUiThread {
                        MudifDialog.info(this, getString(R.string.tv_title), getString(R.string.tv_no_display), getString(R.string.ok))
                    }
                    return@Thread
                }
                if (!GuestSession.booted || !X11Session.isTcpDisplayOpen()) {
                    runOnUiThread {
                        Toast.makeText(this, "سطح المكتب مازال ما وجدش — تسنى حتى يكمل", Toast.LENGTH_LONG).show()
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
                            Toast.makeText(this, "الصوت غادي يبدا ملي يفتح سطح المكتب", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    PulseAudio.stop(this)
                }
                runOnUiThread {
                    binding.btnAudio.text =
                        if (nowEnabled) getString(R.string.audio_on) else getString(R.string.audio_off)
                }
            }.start()
        }

        // Version indicator — reads the live marker, never a stale label.
        Toast.makeText(this, "نسخة: ${LxqtLook.MARKER_VERSION}", Toast.LENGTH_LONG).show()

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
        binding.btnAudio.text =
            if (PulseAudio.isEnabled(this)) getString(R.string.audio_on)
            else getString(R.string.audio_off)
        Thread {
            val recording = ScreenRecorder.isRecording(this)
            runOnUiThread {
                binding.btnRecord.text =
                    if (recording) getString(R.string.record_stop)
                    else getString(R.string.record_start)
            }
        }.start()
    }

    /**
     * P16.2: open the viewer, then launch the desktop after the viewer
     * attaches and resizes the server (P6.5). Shared by the diagnostics
     * dialog's positive button and the status screen's viewer button.
     */
    private fun openViewerAndLaunchDesktop() {
        X11Session.openViewer(this)
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
                            if (appLaunched) "تم تشغيل التطبيق ✓"
                            else "سطح المكتب خدام ✓"
                        } else "سطح المكتب ما خدمش — شوف اللوجات",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
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
                Toast.makeText(this, "سطح المكتب كيبدا دابا، تسنى شوية", Toast.LENGTH_SHORT).show()
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
            setStatus("جاري فحص جلسة PTY…")
            if (!GuestSession.ensure(this)) {
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog(
                        "جلسة PTY ما تخلقاتش على هاد الجهاز.\n${GuestSession.lastError ?: ""}"
                    )
                }
                return@Thread
            }
            if (!GuestSession.probePty(this)) {
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog(
                        "جلسة PTY ما كتوصلش الأوامر.\n${GuestSession.lastError ?: ""}"
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
            setStatus("جاري تنظيف سطح المكتب السابق…")
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
            setStatus("جاري تشغيل نظام مضيف…")
            if (!GuestSession.bootContainer(this, GuestSession.GuestSystem.DEBIAN)) {
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog(
                        "تعذّر تشغيل نظام مضيف.\n" + (GuestSession.lastError ?: "")
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
            setStatus("جاري تنظيف عمليات التثبيت العالقة…")
            X11Session.killOrphanedAptProcesses(this)

            setStatus("جاري التحقق من نظام مضيف…")
            val desktopOk = X11Session.ensureDebianDesktop(this)
            // P6.4 — the Deepin-style look for LXQt (navy wallpaper,
            // bloom-dark icons, dark panel, dark Qt/GTK): written
            // into the rootfs BEFORE the first launch, once per
            // rootfs (marker); skipped when already applied.
            val lookOk = if (desktopOk) {
                setStatus("جاري تثبيت الشكل ديال Deepin…")
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
                        "نظام مضيف غير مكتمل.\nالصورة ما فيهاش سطح المكتب — عاود تثبيت النظام من جديد."
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
                    showFailureDialog(xr?.error ?: "خادم X ما قلعش.")
                }
                return@Thread
            }

            // 4. TCP gate (P6.3.6): NEVER send the desktop command to
            // a dead server. Re-prove TCP here, on EVERY path, before
            // the dialog goes out.
            if (!X11Session.isTcpDisplayOpen()) {
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog("خادم X ما خدامش: منفذ TCP 6006 مسدود. سطح المكتب ما تبعتش.")
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
            val report = "النظام: نظام مضيف (دبيان 13.1 + LXQt)\n" +
                "PTY حية: ${GuestSession.alive}\n" +
                "الضيف قلع: ${GuestSession.booted}\n" +
                "سوكيت X6: ${X11Session.isXSocketPresent(this@VncActivity)}\n" +
                "TCP 6006: $tcpOk\n" +
                "الأيقونات: أصلية (دبيان، بلا glycin)\n" +
                "نظام مضيف تثبت: $desktopOk\n" +
                "الشكل ديال Deepin تثبت: $lookOk\n" +
                "سطح المكتب: يبدأ بعد فتح العارض (الحجم كيتثبت أولا)\n\n" +
                "--- السبب الحقيقي ---\n" +
                DesktopDiagnostics.hypothesisDebian()
            Log.d("VncActivity", "diagnostics:\n$report")
            lastDiagnostics = report
            runOnUiThread {
                val ok = GuestSession.alive && GuestSession.booted && tcpOk
                setStatus(
                    if (ok) "جاهز — افتح العارض لبدء سطح المكتب"
                    else "سطح المكتب ما خدمش — شوف اللوجات"
                )
                binding.btnShowDiagnostics.visibility = View.VISIBLE
                if (ok) {
                    openViewerAndLaunchDesktop()
                } else {
                    // P16: branded dialog (title fixed: no stale P8).
                    MudifDialog.info(
                        this,
                        "تشخيص — نظام مضيف",
                        report,
                        "فتح العارض وبدء سطح المكتب",
                        onPositive = {
                            // P16.2: shared with the status screen button.
                            openViewerAndLaunchDesktop()
                        },
                        neutral = "اللوجات المفصلة",
                        onNeutral = {
                            startActivity(Intent(this, LogViewerActivity::class.java))
                        },
                        negative = "إلغاء"
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
                        showFailureDialog("خطأ غير متوقع أثناء بدء سطح المكتب:\n${e.message}")
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
            setStatus("جاري إعادة تشغيل خادم X من جديد…")
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
            setStatus("جاري فحص بيانات لوحة المفاتيح…")
            if (!X11Session.ensureXkbData(this)) {
                return XResult(false, "بيانات لوحة المفاتيح (xkb) ناقصة في نظام مضيف.\nأعد تثبيت النظام من المتجر ثم عاود فتح سطح المكتب.")
            }
            setStatus("جاري تشغيل خادم X…")
            val xkbRootfs = DebianSystem.debianRootfsDir(this)
            if (!X11Session.startXServer(this, xkbRootfs)) {
                return XResult(false, "بيانات لوحة المفاتيح (xkb) ناقصة في نظام مضيف.\nأعد تثبيت النظام من المتجر ثم عاود فتح سطح المكتب.")
            }

            // 3. Wait ONLY for the socket file (Tiny waitForFile).
            if (!X11Session.waitForXSocket(this, 15000)) {
                return XResult(false, "خادم X ما قلعش: سوكيت X6 ما تخلقش خلال 15 ثانية.")
            }

            // 3b. Wait until the server accepts TCP on
            // 127.0.0.1:6006. The socket file can exist before the
            // server is ready, and the Unix socket connect from
            // inside proot is broken on this device, so the guest
            // uses TCP — prove the listener is there first.
            setStatus("جاري انتظار منفذ TCP…")
            if (!X11Session.waitForTcpDisplay(10000)) {
                return XResult(false, "خادم X ما فتحش منفذ TCP 6006 خلال 10 ثواني (السوكيت موجود ولكن TCP مسدود).")
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
        val appToLaunch = pendingLaunchApp
        pendingLaunchApp = null
        if (appToLaunch != null) GuestSession.sendCommand(appToLaunch)
        runOnUiThread {
            setStatus("جاهز — افتح العارض لبدء سطح المكتب")
            X11Session.openViewer(this)
        }
        return true
    }

    /** Shown when the container or the X server fails to start. */
    private fun showFailureDialog(reason: String) {
        if (destroyed) return
        // P16: branded dialog.
        MudifDialog.info(
            this,
            "فشل بدء سطح المكتب",
            "$reason\n\nالسبب الحقيقي كاين في اللوجات " +
                "(logcat ديال أندرويد + حالة الخادم).",
            "عرض اللوجات",
            onPositive = {
                startActivity(Intent(this, LogViewerActivity::class.java))
            },
            neutral = "إعادة المحاولة",
            onNeutral = {
                startDesktop()
            },
            negative = "إغلاق",
            onNegative = {
                finish()
            }
        )
    }

    override fun onDestroy() {
        destroyed = true
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
