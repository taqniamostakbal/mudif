package com.bolm.mudif.ui.vnc

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.databinding.ActivityVncBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.DesktopDiagnostics
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.RootfsManager
import com.bolm.mudif.engine.X11Session
import com.bolm.mudif.ui.logs.LogViewerActivity

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
 * Desktop choice: intent extra EXTRA_DESKTOP ("lxqt" default,
 * "xfce"). One desktop per session — never both together.
 * Lifecycle: entering this screen ALWAYS reboots the container
 * fresh (Tiny Container's clean slate). Leaving the screen does NOT
 * kill the session or the X server (P5.11 kept): the desktop stays
 * alive until the app process dies or the next entry reboots it.
 */
class VncActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVncBinding

    @Volatile
    private var destroyed = false

    private var desktop: String = DESKTOP_LXQT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        desktop = intent.getStringExtra(EXTRA_DESKTOP)
            ?.takeIf { it == DESKTOP_XFCE || it == DESKTOP_MUDIF } ?: DESKTOP_LXQT

        supportActionBar?.title = when (desktop) {
            DESKTOP_MUDIF -> "نظام مضيف"
            DESKTOP_XFCE -> "سطح مكتب XFCE"
            else -> "سطح مكتب LXQt"
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnShowLogs.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        // Version indicator — P6 (verifiable by evidence).
        Toast.makeText(this, "نسخة: P6", Toast.LENGTH_LONG).show()

        startDesktop()
    }

    private fun setStatus(text: String) {
        if (destroyed) return
        runOnUiThread { binding.statusText.text = text }
    }

    private fun startDesktop() {
        Thread {
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

            // P6: نظام مضيف is the ONE system (Debian + XFCE).
            val isMudif = desktop == DESKTOP_MUDIF

            // 1a. Kill the previous desktop's processes FIRST
            // (P5.24): the old PTY session's death leaves its desktop
            // processes as orphans owning the display, and the new
            // desktop's window manager then refuses to start. Only
            // when the guest is installed; a failure here is not
            // fatal — the fresh boot proceeds anyway.
            // P6: the orphans live in whichever guest owned the last
            // desktop — clean both guests (best-effort, fast no-ops).
            setStatus("جاري تنظيف سطح المكتب السابق…")
            if (isMudif) {
                if (DebianSystem.isReady(this)) X11Session.killDebianDesktopProcesses(this)
                if (RootfsManager.isReady(this)) X11Session.killDesktopProcesses(this)
            } else if (RootfsManager.isReady(this)) {
                X11Session.killDesktopProcesses(this)
            }

            // 1. Boot the container in the PTY session (Tiny:
            // clean slate, fresh boot on every entry).
            // P6: نظام مضيف boots DEBIAN in the PTY; the old rows
            // still boot Alpine.
            setStatus(if (isMudif) "جاري تشغيل نظام مضيف…" else "جاري تشغيل الحاوية…")
            val system = if (isMudif) GuestSession.GuestSystem.DEBIAN else GuestSession.GuestSystem.ALPINE
            if (!GuestSession.bootContainer(this, system)) {
                if (destroyed) return@Thread
                runOnUiThread {
                    showFailureDialog(
                        (if (isMudif) "تعذّر تشغيل نظام مضيف.\n" else "تعذّر تشغيل حاوية Alpine.\n") +
                            (GuestSession.lastError ?: "")
                    )
                }
                return@Thread
            }

            // 2. One-shot installers FIRST (proven path).
            // P6: نظام مضيف installs the Debian desktop set in ONE
            // apt call (xfce4 + whisker + terminal + thunar +
            // firefox-esr + deepin/papirus/adwaita icons + dbus +
            // xkeyboard-config). No bwrap shim: Debian's gdk-pixbuf
            // has native loaders and glycin is not in the closure.
            val desktopName = if (isMudif) "نظام مضيف" else if (desktop == DESKTOP_XFCE) "XFCE" else "LXQt"
            val xkbOk: Boolean
            val packagesOk: Boolean
            val desktopOk: Boolean
            val pixbufOk: Boolean
            if (isMudif) {
                setStatus("جاري تثبيت نظام مضيف (XFCE + فايرفوكس)…")
                desktopOk = X11Session.ensureDebianDesktop(this)
                xkbOk = desktopOk
                packagesOk = desktopOk
                pixbufOk = true
            } else {
                setStatus("جاري تثبيت بيانات لوحة المفاتيح…")
                xkbOk = X11Session.ensureXkb(this)
                setStatus("جاري تثبيت حزم سطح المكتب…")
                packagesOk = X11Session.ensureDesktopPackages(this)
                setStatus("جاري تثبيت $desktopName…")
                desktopOk = if (desktop == DESKTOP_XFCE) {
                    X11Session.ensureXfce(this)
                } else {
                    X11Session.ensureLxqt(this)
                }

                // 2b. Fix GTK icon loading (P5.25): the guest's gdk-pixbuf
                // has no native PNG loader — every PNG goes through the
                // glycin loader under bwrap, which cannot create namespaces
                // under proot, so xfce4-session/panel/desktop abort. The
                // installer replaces bwrap with a shim that runs the loader
                // directly, and installs the Adwaita icon theme. Must run
                // BEFORE the desktop launch (step 4).
                setStatus("جاري إصلاح تحميل الأيقونات…")
                pixbufOk = X11Session.ensurePixbufLoaders(this)
            }

            // 3. Start the X server (Tiny launchXServer) — but ONLY
            // if it is not already up. Judge by the real state
            // (socket + TCP listener), not by the in-memory flag.
            val serverUp = X11Session.isXSocketPresent(this) && X11Session.isTcpDisplayOpen()
            if (!serverUp) {
                setStatus("جاري تشغيل خادم X…")
                val xkbRootfs = if (isMudif) DebianSystem.debianRootfsDir(this) else RootfsManager.rootfsDir(this)
                X11Session.startXServer(this, xkbRootfs)

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

            // 4. ONE desktop command into the PTY session (Tiny):
            // `startlxqt > /root/lxqt.log 2>&1 &` — no setsid, no pid
            // file, no gate. A short settle so the log has content
            // before the dialog reads it.
            setStatus("جاري تشغيل $desktopName…")
            val sent = X11Session.launchDesktop(this, if (isMudif) DESKTOP_XFCE else desktop)
            Thread.sleep(3000)
            val desktopLog = if (isMudif) {
                X11Session.getDesktopLog(this, DESKTOP_XFCE, DebianSystem.debianRootfsDir(this))
            } else {
                X11Session.getDesktopLog(this, desktop)
            }

            // 4b. TCP value for the dialog, computed HERE on the
            // background thread (P5.25): isTcpDisplayOpen() does a
            // Socket.connect, which throws NetworkOnMainThreadException
            // on the UI thread — that is why the dialog always said
            // false while the socket was proven LISTEN+ESTABLISHED.
            val tcpOk = X11Session.isTcpDisplayOpen()

            // 5. Status dialog (a report, not a gate): PTY alive,
            // guest booted, X socket + TCP, command sent, log tail.
            if (destroyed) return@Thread
            runOnUiThread {
                val ok = sent && GuestSession.alive && GuestSession.booted
                setStatus(
                    if (ok) "سطح المكتب خدام ✓"
                    else "سطح المكتب ما خدمش — شوف اللوجات"
                )
                AlertDialog.Builder(this)
                    .setTitle(if (isMudif) "تشخيص P6 — نظام مضيف" else "تشخيص P5.25")
                    .setMessage(
                        if (isMudif) {
                            "النظام: نظام مضيف (دبيان 13.1 + XFCE)\n" +
                                "PTY حية: ${GuestSession.alive}\n" +
                                "الضيف قلع: ${GuestSession.booted}\n" +
                                "سوكيت X6: ${X11Session.isXSocketPresent(this)}\n" +
                                "TCP 6006: $tcpOk\n" +
                                "أمر سطح المكتب تبعت: $sent\n" +
                                "الأيقونات: أصلية (دبيان، بلا glycin)\n" +
                                "نظام مضيف تثبت: $desktopOk\n\n" +
                                "لوگ XFCE:\n$desktopLog\n\n" +
                                "--- السبب الحقيقي ---\n" +
                                DesktopDiagnostics.hypothesisDebian()
                        } else {
                            "سطح المكتب: $desktopName\n" +
                                "PTY حية: ${GuestSession.alive}\n" +
                                "الضيف قلع: ${GuestSession.booted}\n" +
                                "سوكيت X6: ${X11Session.isXSocketPresent(this)}\n" +
                                "TCP 6006: $tcpOk\n" +
                                "أمر سطح المكتب تبعت: $sent\n" +
                                "الأيقونات تصلحات: $pixbufOk\n" +
                                "الخطوط تثبتات: $xkbOk\n" +
                                "حزم سطح المكتب تثبتات: $packagesOk\n" +
                                "$desktopName تثبت: $desktopOk\n" +
                                "XFCE + فايرفوكس + ليبر أوفيس: كيتثبتو فالخلفية…\n\n" +
                                "لوگ $desktopName:\n$desktopLog\n\n" +
                                "--- السبب الحقيقي ---\n" +
                                DesktopDiagnostics.hypothesis1()
                        }
                    )
                    .setPositiveButton("فتح العارض") { _, _ ->
                        X11Session.openViewer(this)
                    }
                    .setNeutralButton("اللوجات المفصلة") { _, _ ->
                        startActivity(Intent(this, LogViewerActivity::class.java))
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }

            // 6. Install the OTHER desktop + firefox + libreoffice in
            // the BACKGROUND, after the desktop is already up. These
            // are LARGE downloads; a slow download must never block
            // the desktop. Best-effort, idempotent.
            // P6: نظام مضيف skips this — firefox-esr is already in
            // the Debian set; the program set comes in the next slice.
            if (!isMudif) Thread {
                val otherOk = if (desktop == DESKTOP_XFCE) {
                    X11Session.ensureLxqt(this)
                } else {
                    X11Session.ensureXfce(this)
                }
                Log.d("VncActivity", "ensureOtherDesktop (background) ok=$otherOk")
                val ok = X11Session.ensureDesktopApps(this)
                Log.d("VncActivity", "ensureDesktopApps (background) ok=$ok")
                runOnUiThread {
                    if (!destroyed) {
                        setStatus(
                            if (otherOk && ok) "سطح المكتب التاني + فايرفوكس + ليبر أوفيس تثبتو ✓"
                            else "بعض التثبيتات فالخلفية ما كملاتش — جرب مرة أخرى"
                        )
                    }
                }
            }.start()
        }.start()
    }

    /** Shown when the container or the X server fails to start. */
    private fun showFailureDialog(reason: String) {
        if (destroyed) return
        AlertDialog.Builder(this)
            .setTitle("فشل بدء سطح المكتب")
            .setMessage(
                "$reason\n\nالسبب الحقيقي كاين في اللوجات " +
                    "(logcat ديال أندرويد + حالة الخادم)."
            )
            .setPositiveButton("عرض اللوجات") { _, _ ->
                startActivity(Intent(this, LogViewerActivity::class.java))
            }
            .setNeutralButton("إعادة المحاولة") { _, _ ->
                startDesktop()
            }
            .setNegativeButton("إغلاق") { _, _ -> finish() }
            .show()
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
        const val DESKTOP_XFCE = "xfce"
        /** P6 — نظام مضيف: Debian 13.1 + XFCE, the ONE system. */
        const val DESKTOP_MUDIF = "mudif"
    }
}
