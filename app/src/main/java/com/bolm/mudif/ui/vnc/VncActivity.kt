package com.bolm.mudif.ui.vnc

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.databinding.ActivityVncBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.DesktopDiagnostics
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.LxqtLook
import com.bolm.mudif.engine.RootfsManager
import com.bolm.mudif.engine.X11Session
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.logs.LogViewerActivity
import java.util.concurrent.atomic.AtomicBoolean

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "نظام مضيف"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnShowLogs.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
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
        startDesktop()
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
            Thread.sleep(3000)
            val running = X11Session.isDesktopRunning(this, DESKTOP_LXQT, true)
            val log = X11Session.getDesktopLog(this, DESKTOP_LXQT, DebianSystem.debianRootfsDir(this))
            Log.d("VncActivity", "P8 post-viewer launch: sent=$sent running=$running\n$log")
            if (!destroyed) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (sent && running) "سطح المكتب خدام ✓"
                        else "سطح المكتب ما خدمش — شوف اللوجات",
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
            setStatus("جاري تنظيف سطح المكتب السابق…")
            if (DebianSystem.isReady(this)) X11Session.killDebianDesktopProcesses(this)
            if (RootfsManager.isReady(this)) X11Session.killDesktopProcesses(this)

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
            // P6.4: نظام مضيف installs the Debian LXQt set in ONE
            // apt call (lxqt + openbox + qterminal + lxqt-qtplugin +
            // adwaita-qt6 + firefox-esr + deepin/papirus/adwaita
            // icons + dbus + xkb-data). No bwrap shim: Debian's
            // gdk-pixbuf has native loaders and glycin is not in
            // the closure. Kvantum is NOT in the set: there is no
            // kvantum package in Debian trixie (verified
            // packages.debian.org 2026-11-07) — the dark Qt style
            // is Adwaita-dark instead.
            // P16.5: kill orphaned apt-get/dpkg FIRST — an orphaned
            // apt-get holds the dpkg lock and blocks every install
            // (proven on the Nova 9, P16.3: PID 11794). The app never
            // killed it before; the P16.4 evidence (no install, no
            // temp files) is exactly a lock-blocked install.
            setStatus("جاري تنظيف عمليات التثبيت العالقة…")
            X11Session.killOrphanedAptProcesses(this)

            setStatus("جاري تثبيت نظام مضيف (LXQt + فايرفوكس)…")
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
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog(
                        "تثبيت نظام مضيف ما كملش.\nإلا كان التثبيت مازال خدام من المتجر، تسنى حتى يكمل وعاود فتح سطح المكتب."
                    )
                }
                return@Thread
            }

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
                    if (destroyed) return@Thread
                    runOnUiThread {
                        showFailureDialog(
                            "بيانات لوحة المفاتيح (xkb) ناقصة في نظام مضيف.\nأعد تثبيت النظام من المتجر ثم عاود فتح سطح المكتب."
                        )
                    }
                    return@Thread
                }
                setStatus("جاري تشغيل خادم X…")
                val xkbRootfs = DebianSystem.debianRootfsDir(this)
                if (!X11Session.startXServer(this, xkbRootfs)) {
                    if (destroyed) return@Thread
                    runOnUiThread {
                        showFailureDialog(
                            "بيانات لوحة المفاتيح (xkb) ناقصة في نظام مضيف.\nأعد تثبيت النظام من المتجر ثم عاود فتح سطح المكتب."
                        )
                    }
                    return@Thread
                }

                // 3. Wait ONLY for the socket file (Tiny waitForFile).
                if (!X11Session.waitForXSocket(this, 15000)) {
                    if (destroyed) return@Thread
                    runOnUiThread {
                        showFailureDialog("خادم X ما قلعش: سوكيت X6 ما تخلقش خلال 15 ثانية.")
                    }
                    return@Thread
                }

                // 3b. Wait until the server accepts TCP on
                // 127.0.0.1:6006. The socket file can exist before the
                // server is ready, and the Unix socket connect from
                // inside proot is broken on this device, so the guest
                // uses TCP — prove the listener is there first.
                setStatus("جاري انتظار منفذ TCP…")
                if (!X11Session.waitForTcpDisplay(10000)) {
                    if (destroyed) return@Thread
                    runOnUiThread {
                        showFailureDialog("خادم X ما فتحش منفذ TCP 6006 خلال 10 ثواني (السوكيت موجود ولكن TCP مسدود).")
                    }
                    return@Thread
                }
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

            // 5. Status dialog (a report, not a gate): PTY alive,
            // guest booted, X socket + TCP, install + look. The
            // desktop itself starts after the viewer opens.
            if (destroyed) return@Thread
            runOnUiThread {
                val ok = GuestSession.alive && GuestSession.booted && tcpOk
                setStatus(
                    if (ok) "جاهز — افتح العارض لبدء سطح المكتب"
                    else "سطح المكتب ما خدمش — شوف اللوجات"
                )
                // P16: branded dialog (title fixed: no stale P8).
                MudifDialog.info(
                    this,
                    "تشخيص — نظام مضيف",
                    "النظام: نظام مضيف (دبيان 13.1 + LXQt)\n" +
                        "PTY حية: ${GuestSession.alive}\n" +
                        "الضيف قلع: ${GuestSession.booted}\n" +
                        "سوكيت X6: ${X11Session.isXSocketPresent(this)}\n" +
                        "TCP 6006: $tcpOk\n" +
                        "الأيقونات: أصلية (دبيان، بلا glycin)\n" +
                        "نظام مضيف تثبت: $desktopOk\n" +
                        "الشكل ديال Deepin تثبت: $lookOk\n" +
                        "سطح المكتب: يبدأ بعد فتح العارض (الحجم كيتثبت أولا)\n\n" +
                        "--- السبب الحقيقي ---\n" +
                        DesktopDiagnostics.hypothesisDebian(),
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
