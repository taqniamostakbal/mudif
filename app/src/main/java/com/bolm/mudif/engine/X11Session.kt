package com.bolm.mudif.engine

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.termux.x11.CmdEntryPointService
import java.io.File

/**
 * P5.22 RADICAL REBUILD — Tiny Container's X11 flow, exactly.
 *
 * Tiny (`ContainerMainViewModel`, x11 feature):
 * - `launchXServer(args)`: CmdEntryPointService ACTION_START, env
 *   TMPDIR / XKB_CONFIG_ROOT / TERMUX_X11_DEBUG=1 /
 *   TERMUX_X11_OVERRIDE_PACKAGE. `xserverStarted` flag guards the stop.
 * - `waitForFile(.../.X11-unix/X6)` — 15 s, 500 ms poll, `exists()`
 *   ONLY — then `Global.sendCommand(command)` into the PTY session,
 *   then open the viewer.
 *
 * Mudif adaptations (proven, kept):
 * - Display :6 (TC), args `[":6", "+extension", "MIT-SHM",
 *   "-listen", "tcp"]` — the `-listen tcp` is Mudif's P5.10
 *   workaround: the Unix-socket connect from inside proot is broken
 *   on this x86_64 device, so the guest uses TCP 127.0.0.1:6006
 *   (DISPLAY is set to 127.0.0.1:6 in the boot env, so every guest
 *   command inherits it — no per-command DISPLAY prefix anywhere).
 * - waitForTcpDisplay is kept after the socket wait: the socket file
 *   can exist before the server accepts connections.
 *
 * DELETED in P5.22 (the pipe-session machinery that never worked):
 * launchGuiApp / launchXterm / launchLxqt, isOpenboxRunning /
 * isXtermRunning / isLxqtRunning and their *_PID_FILE / *_STATE_FILE
 * marker files, ensureXtermFonts, and xterm itself. Tiny Container
 * has ZERO xterm references: its desktops ship their own terminals
 * (xfce4-terminal, qterminal). The desktop is launched with ONE
 * backgrounded command into the PTY session — no setsid, no pid
 * file, no launch gate.
 */
object X11Session {

    private const val TAG = "X11Session"
    private const val DISPLAY = ":6"

    @Volatile
    var xserverRunning = false
        private set

    /** Result of the last waitForXSocket call (true/false/null = never waited). */
    @Volatile
    var lastWaitResult: Boolean? = null
        private set

    /**
     * Tiny `launchXServer`: start CmdEntryPointService with ACTION_START.
     * Sets the started flag FIRST (Tiny does the same) so a later stop
     * always pairs with a start.
     */
    fun startXServer(context: Context, rootfs: File = RootfsManager.rootfsDir(context)) {
        val app = context.applicationContext
        val tmpDir = File(app.filesDir, "tmp").apply { mkdirs() }

        // EXACT match to Tiny Container's launchXServer (plus Mudif's
        // proven -listen tcp flag).
        val xkbRoot = File(rootfs, "usr/share/X11/xkb")
            .takeIf { it.exists() }?.absolutePath ?: ""

        val envKeys = arrayOf(
            "TMPDIR",
            "XKB_CONFIG_ROOT",
            "TERMUX_X11_DEBUG",
            "TERMUX_X11_OVERRIDE_PACKAGE"
        )
        val envVals = arrayOf(
            tmpDir.absolutePath,
            xkbRoot,
            "1",
            app.packageName
        )

        val intent = Intent(app, CmdEntryPointService::class.java).apply {
            action = CmdEntryPointService.ACTION_START
            putExtra(CmdEntryPointService.EXTRA_ARGS, arrayOf(DISPLAY, "+extension", "MIT-SHM", "-listen", "tcp"))
            putExtra(CmdEntryPointService.EXTRA_ENV_KEYS, envKeys)
            putExtra(CmdEntryPointService.EXTRA_ENV_VALUES, envVals)
        }
        app.startService(intent)
        xserverRunning = true
        Log.d(TAG, "X server start requested for $DISPLAY (+TCP)")
    }

    /**
     * Tiny `killXServer`: ACTION_STOP only if the server was started.
     * Sending ACTION_STOP without a start would spawn a stray :xserver
     * process — that is why the guard exists.
     */
    fun stopXServer(context: Context) {
        if (!xserverRunning) return
        val app = context.applicationContext
        app.startService(Intent(app, CmdEntryPointService::class.java).apply {
            action = CmdEntryPointService.ACTION_STOP
        })
        xserverRunning = false
    }

    /**
     * Tiny `waitForFile`: poll `exists()` every 500 ms, 15 s timeout.
     * No connection test, no extra sleep — Tiny waits ONLY for the file.
     */
    fun waitForXSocket(context: Context, timeoutMs: Long = 15000): Boolean {
        val socket = File(context.filesDir, "tmp/.X11-unix/X6")
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (socket.exists()) {
                Log.d(TAG, "X socket ready")
                lastWaitResult = true
                return true
            }
            Thread.sleep(500)
        }
        Log.e(TAG, "X socket not ready after ${timeoutMs}ms")
        lastWaitResult = false
        return false
    }

    /** True if the X socket file exists right now (diagnostics). */
    fun isXSocketPresent(context: Context): Boolean =
        File(context.filesDir, "tmp/.X11-unix/X6").exists()

    /** TCP port of display :6 (6000 + display number). */
    const val TCP_PORT = 6006

    /** Single TCP probe — is anything accepting on 127.0.0.1:6006?
     * P5.24: one 800ms connect can fail under load right after the
     * desktop starts (P5.23 test: the dialog said false while
     * /proc/net/tcp proved LISTEN + ESTABLISHED). Retry 3 times so a
     * single slow connect does not report a dead socket. */
    fun isTcpDisplayOpen(): Boolean {
        repeat(3) { attempt ->
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", TCP_PORT), 800)
                    return true
                }
            } catch (_: Exception) {
                // fall through to the next attempt
            }
            if (attempt < 2) Thread.sleep(300)
        }
        return false
    }

    /**
     * Wait until the X server accepts TCP connections on
     * 127.0.0.1:6006. The Unix socket file can exist before the server
     * is ready to accept, so the socket check alone is not enough.
     * A connect+immediate-close is harmless to the server.
     */
    fun waitForTcpDisplay(timeoutMs: Long = 10000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (isTcpDisplayOpen()) {
                Log.d(TAG, "TCP display ready")
                return true
            }
            Thread.sleep(500)
        }
        Log.e(TAG, "TCP display not ready after ${timeoutMs}ms")
        return false
    }

    /** Open the Termux:X11 viewer (Tiny: OpenX11 navigation event). */
    fun openViewer(context: Context) {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(intent)
    }

    /**
     * P5.22 — THE desktop launch (Tiny Container's x11 branch): ONE
     * backgrounded command into the PTY session. DISPLAY is already
     * in the boot env (127.0.0.1:6), so no prefix is needed. No
     * setsid, no pid file, no marker file, no launch gate — the
     * desktop is a background job of the container shell, exactly as
     * in Tiny Container (`startxfce4 > /dev/null 2>&1 &`).
     * [desktop] is "lxqt" (startlxqt) or "xfce" (startxfce4).
     */
    fun launchDesktop(context: Context, desktop: String): Boolean {
        val cmd = if (desktop == "xfce") "startxfce4" else "startlxqt"
        val ok = GuestSession.sendCommand("$cmd > /root/$desktop.log 2>&1 &")
        if (ok) Log.d(TAG, "$desktop command sent through PTY session")
        else Log.e(TAG, "PTY session not available for $desktop")
        return ok
    }

    /** Last 30 lines of the desktop session log (diagnostics only). */
    fun getDesktopLog(context: Context, desktop: String, rootfs: File = RootfsManager.rootfsDir(context)): String {
        return try {
            val file = File(rootfs, "root/$desktop.log")
            if (!file.exists()) return "($desktop.log not found - $desktop never started)"
            val lines = file.readLines()
            if (lines.isEmpty()) return "($desktop.log empty)"
            lines.takeLast(30).joinToString("\n")
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }

    /**
     * P5.24 — kill the previous desktop's processes before a fresh
     * boot. Tapping a desktop row finishes the old PTY session, but
     * the desktop's processes (openbox, panel, pcmanfm, qterminal...)
     * survive as orphans (PPid 1) and keep owning the display, so the
     * new desktop's window manager refuses to start (xfwm4: "Another
     * Window Manager is already running" — P5.23 test). This one-shot
     * pkill runs inside the guest (same PID namespace) before the
     * fresh boot. The [x] bracket trick keeps pkill from matching its
     * own shell, whose cmdline contains the pattern text. pkill exits
     * 1 when nothing matched — that is fine, not a failure.
     */
    fun killDesktopProcesses(context: Context): Boolean {
        val pattern = "[o]penbox|[l]xqt-session|[l]xqt-panel|[l]xqt-runner|" +
            "[l]xqt-notificationd|[l]xqt-globalkeysd|[p]cmanfm-qt|[q]terminal|" +
            "[x]fwm4|[x]fce4-session|[x]fce4-panel|[x]fdesktop|[x]fce4-terminal|" +
            "[f]irefox|[s]tartlxqt|[s]tartxfce4|[d]bus-daemon"
        val result = TerminalSession.runCommand(
            context,
            "pkill -f '$pattern'; echo DESKTOP_CLEANED",
            idleTimeoutSec = 15,
            maxTimeoutSec = 30
        )
        Log.d(TAG, "killDesktopProcesses exit=${result.exitCode} out=${result.output}")
        return result.output.contains("DESKTOP_CLEANED")
    }

    /**
     * P6 — same desktop cleanup as killDesktopProcesses, but inside
     * the DEBIAN guest (the previous نظام مضيف session's orphans).
     * Best-effort; a failure here is not fatal — the fresh boot
     * proceeds anyway.
     */
    fun killDebianDesktopProcesses(context: Context): Boolean {
        val pattern = "[x]fwm4|[x]fce4-session|[x]fce4-panel|[x]fdesktop|[x]fce4-terminal|" +
            "[f]irefox-esr|[s]tartxfce4|[d]bus-daemon"
        val result = TerminalSession.runCommandDebian(
            context,
            "pkill -f '$pattern'; echo DESKTOP_CLEANED",
            idleTimeoutSec = 15,
            maxTimeoutSec = 30
        )
        Log.d(TAG, "killDebianDesktopProcesses exit=${result.exitCode} out=${result.output}")
        return result.output.contains("DESKTOP_CLEANED")
    }

    /**
     * P5.25 — fix GTK icon loading under proot (the XFCE killer,
     * proven by the 2026-11-06 guest diagnostic, NOT a guess):
     * Alpine 3.24's gdk-pixbuf ships ONLY libpixbufloader-xpm.so,
     * so every PNG/JPEG/SVG icon goes through the glycin loader
     * (/usr/libexec/glycin-loaders/2+/glycin-image-rs) spawned under
     * `bwrap --unshare-all`, which cannot create namespaces under
     * proot. The NULL pixbuf trips the libwnck/Gtk assertions that
     * abort xfce4-session, xfce4-panel and xfdesktop. Deleting
     * glycin would leave NO PNG loader at all, so instead this
     * replaces /usr/bin/bwrap with a shim that skips the sandbox
     * options and execs the loader directly (unsandboxed), keeps the
     * real binary as /usr/bin/bwrap.real (reversible), and installs
     * the Adwaita icon theme (the guest has only hicolor).
     * One-shot, best-effort, idempotent. Must run BEFORE the
     * desktop launch (VncActivity step 2b).
     */
    fun ensurePixbufLoaders(context: Context): Boolean {
        val command = """
            apk add --no-cache adwaita-icon-theme
            if [ -x /usr/bin/bwrap ] && [ ! -e /usr/bin/bwrap.real ]; then mv /usr/bin/bwrap /usr/bin/bwrap.real; fi
            cat > /usr/bin/bwrap <<'BWRAP_SHIM'
            #!/bin/sh
            # P5.25 bwrap shim: bwrap cannot create namespaces under proot,
            # so run the sandboxed program directly (unsandboxed) instead.
            # gdk-pixbuf calls: bwrap <opts...> <program> <args...>
            # Skip every known bwrap option (with its values); exec the rest.
            while [ $# -gt 0 ]; do
              case "$1" in
                --unshare-all|--unshare-user|--unshare-ipc|--unshare-pid|--unshare-net|--unshare-uts|--unshare-cgroup|--unshare-user-try|--unshare-cgroup-try|--die-with-parent|--clearenv|--new-session|--as-pid-1|--userns|--userns2) shift ;;
                --chdir|--tmpfs|--dev|--proc|--mqueue|--dir|--seccomp|--add-seccomp-fd|--unsetenv|--hostname|--domainname|--uid|--gid|--remount-ro|--cap-add|--cap-drop|--args|--lock-file|--info-fd|--json-status-fd|--userns-fd|--seccomp-fd) shift 2 ;;
                --setenv|--ro-bind|--bind|--dev-bind|--ro-bind-try|--bind-try|--symlink|--file|--bind-data|--ro-bind-data) shift 3 ;;
                --) shift; break ;;
                --*) shift ;;
                *) break ;;
              esac
            done
            exec "$@"
            BWRAP_SHIM
            chmod +x /usr/bin/bwrap
            gtk-update-icon-cache -f /usr/share/icons/hicolor /usr/share/icons/Adwaita 2>/dev/null
            grep -q "P5.25 bwrap shim" /usr/bin/bwrap && echo PIXBUF_OK
        """.trimIndent()
        val result = TerminalSession.runCommand(
            context,
            command,
            idleTimeoutSec = 60,
            maxTimeoutSec = 300
        )
        Log.d(TAG, "ensurePixbufLoaders exit=${result.exitCode} out=${result.output}")
        return result.output.contains("PIXBUF_OK")
    }

    /**
     * Install xkeyboard-config in the guest so the X server can find
     * its keyboard data (XKB_CONFIG_ROOT = <rootfs>/usr/share/X11/xkb).
     * One-shot command (TerminalSession, best-effort, idempotent).
     * Called BEFORE startXServer so the env var picks it up.
     */
    fun ensureXkb(context: Context): Boolean {
        val result = TerminalSession.runCommand(
            context,
            "apk add --no-cache xkeyboard-config",
            idleTimeoutSec = 60,
            maxTimeoutSec = 300
        )
        Log.d(TAG, "ensureXkb exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /**
     * Install openbox in the guest (the window manager LXQt uses by
     * default). One-shot, best-effort, idempotent. xterm is GONE in
     * P5.22 (Tiny Container has none; its pty allocation fails under
     * proot on this device even with /proc bound).
     */
    fun ensureDesktopPackages(context: Context): Boolean {
        val result = TerminalSession.runCommand(
            context,
            "apk add --no-cache openbox",
            idleTimeoutSec = 60,
            maxTimeoutSec = 300
        )
        Log.d(TAG, "ensureDesktopPackages exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /**
     * Install firefox + libreoffice in the guest (full acceptance).
     * LARGE downloads: generous timeouts; the first run downloads
     * them once, later runs are a fast no-op (already installed).
     * One-shot, best-effort, idempotent.
     */
    fun ensureDesktopApps(context: Context): Boolean {
        val result = TerminalSession.runCommand(
            context,
            "apk add --no-cache firefox libreoffice",
            idleTimeoutSec = 120,
            maxTimeoutSec = 1200
        )
        Log.d(TAG, "ensureDesktopApps exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /**
     * Install the LXQt desktop core in the guest + qterminal (LXQt's
     * own terminal, the Tiny Container way — the desktop ships its
     * terminal, the app launches none). NOTE: there is NO `lxqt`
     * meta-package in Alpine 3.24, so the core components are listed
     * explicitly (29 packages, LXQt 2.4.0). One-shot, best-effort,
     * idempotent.
     */
    fun ensureLxqt(context: Context): Boolean {
        val result = TerminalSession.runCommand(
            context,
            "apk add --no-cache lxqt-session lxqt-panel pcmanfm-qt lxqt-config lxqt-runner lxqt-notificationd lxqt-themes qterminal",
            idleTimeoutSec = 120,
            maxTimeoutSec = 900
        )
        Log.d(TAG, "ensureLxqt exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /**
     * Install the XFCE desktop in the guest (xfce4 4.20-r0, 83
     * packages) + xfce4-terminal (XFCE's own terminal). LARGE
     * download: call from a background thread, never blocking the
     * desktop. One-shot, best-effort, idempotent.
     */
    fun ensureXfce(context: Context): Boolean {
        val result = TerminalSession.runCommand(
            context,
            "apk add --no-cache xfce4 xfce4-terminal",
            idleTimeoutSec = 120,
            maxTimeoutSec = 1200
        )
        Log.d(TAG, "ensureXfce exit=${result.exitCode} out=${result.output}")
        return result.exitCode == 0
    }

    /**
     * P6.1 — is the desktop REALLY running? pgrep for the session
     * process in the target guest (same PID namespace, so a one-shot
     * sees the PTY session's processes — proven by P5.24's pkill).
     * The P6 test proved the dialog's "sent" line is NOT enough: run 3
     * reported TCP true + sent true with no desktop at all (the X
     * server was borrowed from the surviving LXQt run). This check is
     * the ground truth. Polls twice (3 s apart) so a slow start is
     * not a false negative. Falls back to ps if pgrep is absent.
     * The [x]/[l] bracket trick keeps pgrep/ps from matching the
     * one-shot's own shell, whose cmdline contains the pattern text.
     */
    fun isDesktopRunning(context: Context, desktop: String, debian: Boolean): Boolean {
        val proc = if (desktop == "xfce") "[x]fce4-session" else "[l]xqt-session"
        val command = "if pgrep -f '$proc' >/dev/null 2>&1; then echo RUNNING; " +
            "elif ps -e 2>/dev/null | grep '$proc' >/dev/null 2>&1; then echo RUNNING; fi"
        repeat(2) { attempt ->
            val result = if (debian) {
                TerminalSession.runCommandDebian(
                    context, command, idleTimeoutSec = 10, maxTimeoutSec = 20
                )
            } else {
                TerminalSession.runCommand(
                    context, command, idleTimeoutSec = 10, maxTimeoutSec = 20
                )
            }
            if (result.output.contains("RUNNING")) return true
            if (attempt == 0) Thread.sleep(3000)
        }
        return false
    }

    /**
     * P6 — install the نظام مضيف desktop set in the DEBIAN guest
     * (proven live by the 2026-11-06 diagnostic, Q4): apt update,
     * then ONE apt install with --no-install-recommends (208 pkgs):
     * xfce4 + whisker + xfce4-terminal + thunar + firefox-esr +
     * deepin-icon-theme + papirus + adwaita + dbus-x11 +
     * xkb-data (the X server's XKB data) + procps (pgrep/ps/pkill,
     * needed by isDesktopRunning and killDebianDesktopProcesses).
     * P6.2 FIX (P6.1 test, 2026-11-07): the list used
     * `xkeyboard-config`, which does NOT exist on Debian — the
     * package is named xkb-data (the upstream project name is
     * xkeyboard-config, the Debian binary package is xkb-data).
     * apt aborted the whole && chain on it, so ZERO packages
     * installed (live logcat: "E: Unable to locate package
     * xkeyboard-config"). DEBIAN_FRONTEND noninteractive so nothing
     * ever prompts. NO bwrap shim here: Debian's gdk-pixbuf has
     * native PNG/JPEG loaders and glycin is not in the closure —
     * the P5.25 failure mode does not exist. One-shot, best-effort,
     * idempotent. Call from a background thread.
     * P6.3.2: libgl1 + libglx-mesa0 — xfwm4 dies at startup without
     * them ("Couldn't open libGL.so.1", proven in /root/xfce.log,
     * P6.3.1 test) and with no window manager the panel stays alive
     * but never shows. x11-utils (xwininfo) is diagnostic tooling
     * for reading the panel window state.
     */
    fun ensureDebianDesktop(context: Context): Boolean {
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get update && apt-get install -y --no-install-recommends " +
            "xkb-data procps xfce4 xfce4-whiskermenu-plugin xfce4-terminal thunar " +
            "firefox-esr deepin-icon-theme papirus-icon-theme adwaita-icon-theme dbus-x11 " +
            "libgl1 libglx-mesa0 x11-utils " +
            "&& echo DEBIAN_DESKTOP_OK"
        val result = TerminalSession.runCommandDebian(
            context,
            command,
            idleTimeoutSec = 120,
            maxTimeoutSec = 1800
        )
        Log.d(TAG, "ensureDebianDesktop exit=${result.exitCode} out=${result.output.takeLast(500)}")
        return result.output.contains("DEBIAN_DESKTOP_OK")
    }
}
