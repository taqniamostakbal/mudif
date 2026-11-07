package com.bolm.mudif.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * P6.3 — the Deepin look for نظام مضيف (ONE system, Debian + XFCE).
 *
 * The user approved the Deepin look and vetoed a separate dock
 * ("الدوك، لا"): there is NO Plank and NO floating dock — a single
 * full-width bottom xfce4-panel IS the taskbar (the Deepin 23 way).
 *
 * The look is four things, all applied once per rootfs (marker
 * .mudif_deepin_look in the guest's /root):
 *  1. Look packages: git + gtk2-engines-murrine + sassc + optipng +
 *     libxml2-utils + libglib2.0-dev-bin (WhiteSur build deps),
 *     breeze-gtk-theme (GTK theme, Manus spec), libreoffice (Writer /
 *     Calc / Impress launchers on the panel).
 *  2. WhiteSur-Dark WM theme from upstream source, pinned commit
 *     d5782652d412137e26fb8ff55b55a5572e4c6995 (2026-09-11, the repo
 *     HEAD at build time; no versioned releases exist). Deps are
 *     pre-installed by the app's own apt (the installer would call
 *     `sudo`, which does not exist in the guest), inkscape is NOT
 *     needed (it appears nowhere in install.sh / lib-install.sh).
 *  3. xfconf XML written directly into the rootfs (no daemon needed):
 *     single bottom panel (48 px, full width, dark navy #011948),
 *     Whisker launcher + app launchers + tasklist + systray + clock,
 *     Breeze GTK + deepin icons, WhiteSur-Dark WM, compositor OFF,
 *     blue wallpaper (both candidate monitor paths).
 *  4. Wallpaper set live via xfconf-query after the session starts
 *     (the real monitor name is discovered from the live properties;
 *     the D-Bus address is read from the xfce4-session process env).
 *
 * Panel colour is dark navy #011948 from the wallpaper palette, NOT
 * the Manus spec's green #0F2B1C (that green is Manus's brand; our
 * system is blue). Manus Control is dropped (it does not exist).
 * Icons are deepin-icon-theme (installed), not ePapirus (Manus's
 * choice) — this is a Deepin-style system.
 */
object DeepinLook {
    private const val TAG = "DeepinLook"

    /** WhiteSur-gtk-theme commit pinned at build time (repo HEAD 2026-09-11). */
    const val WHITESUR_COMMIT = "d5782652d412137e26fb8ff55b55a5572e4c6995"

    private const val MARKER = ".mudif_deepin_look"
    const val WALLPAPER_GUEST_PATH = "/usr/share/backgrounds/mudif-wallpaper.png"
    private const val WALLPAPER_ASSET = "mudif-wallpaper.png"

    fun isApplied(context: Context): Boolean =
        File(DebianSystem.debianRootfsDir(context), "root/$MARKER").exists()

    /**
     * Apply the whole look. One-shot per step, best-effort, idempotent
     * (apt install of an installed package is a no-op; the marker
     * gates re-runs). Call from a background thread. Returns true only
     * when packages + WhiteSur + config are all in place.
     */
    fun ensureDeepinLook(context: Context): Boolean {
        if (isApplied(context)) return true
        val rootfs = DebianSystem.debianRootfsDir(context)

        // 1. Look packages (one apt transaction, --no-install-recommends).
        // `sudo` MUST be in this list: WhiteSur's install.sh sources
        // libs/lib-core.sh, which runs `set -Eeo pipefail` and then the
        // unguarded `SUDO_BIN="$(which sudo)"` — with no sudo in the
        // guest, `which` exits 1, errexit fires, and install.sh dies
        // during sourcing (proven by the P6.3 test). Installing sudo
        // first also covers install.sh's own auto-dep-installer, which
        // calls `sudo apt install`.
        val pkgs = TerminalSession.runCommandDebian(
            context,
            "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get update && apt-get install -y --no-install-recommends " +
                "sudo git gtk2-engines-murrine sassc optipng libxml2-utils " +
                "libglib2.0-dev-bin breeze-gtk-theme libreoffice " +
                "&& echo LOOK_PKGS_OK",
            idleTimeoutSec = 120,
            maxTimeoutSec = 1800
        )
        Log.d(TAG, "look pkgs exit=${pkgs.exitCode} out=${pkgs.output.takeLast(2000)}")
        if (!pkgs.output.contains("LOOK_PKGS_OK")) return false

        // 2. WhiteSur-Dark from upstream source, pinned commit. Deps are
        // already installed (step 1), so install.sh never calls sudo.
        // --silent-mode: skips start_animation, whose unguarded
        // `setterm -cursor off` is the last remaining unguarded
        // command in the pre-install path (everything else — clean,
        // customize, install_app, install_theme_deps, install_beggy
        // with the default background — is plain cp/sed/rm on files
        // the P6.3.1 test proved present) and dies under
        // `set -Eeo pipefail` in a non-tty one-shot. full_sudo() is
        // harmless (it only checks that /root is writable).
        // bash -x + a persistent trace file: the P6.3.1 test proved
        // the app's Log.d never reaches this box's logcat and
        // WhiteSur's own error log is deleted by its EXIT/ERR trap,
        // so the exact failing line is otherwise unrecoverable.
        // The trace lives at /root/whitesur.log in the guest.
        val ws = TerminalSession.runCommandDebian(
            context,
            "rm -rf /root/WhiteSur-gtk-theme && mkdir -p /root/WhiteSur-gtk-theme && " +
                "cd /root/WhiteSur-gtk-theme && git init -q && " +
                "git remote add origin https://github.com/vinceliuice/WhiteSur-gtk-theme.git && " +
                "git fetch -q --depth 1 origin $WHITESUR_COMMIT && " +
                "git checkout -q FETCH_HEAD && " +
                "bash -x ./install.sh -c dark --silent-mode > /root/whitesur.log 2>&1; " +
                "echo \"whitesur rc=$?\"; tail -30 /root/whitesur.log; " +
                "test -d /usr/share/themes/WhiteSur-Dark && echo WHITESUR_OK",
            idleTimeoutSec = 120,
            maxTimeoutSec = 1800
        )
        Log.d(TAG, "whitesur exit=${ws.exitCode} out=${ws.output.takeLast(2000)}")
        if (!ws.output.contains("WHITESUR_OK")) return false

        // 3. Config + wallpaper, written directly into the rootfs
        // (no guest execution needed — the app owns these files).
        try {
            val backgrounds = File(rootfs, "usr/share/backgrounds")
            backgrounds.mkdirs()
            context.assets.open(WALLPAPER_ASSET).use { input ->
                File(backgrounds, "mudif-wallpaper.png").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            val xfconfDir = File(
                rootfs, "root/.config/xfce4/xfconf/xfce-perchannel-xml"
            )
            xfconfDir.mkdirs()
            File(xfconfDir, "xfce4-panel.xml").writeText(PANEL_XML)
            File(xfconfDir, "xsettings.xml").writeText(XSETTINGS_XML)
            File(xfconfDir, "xfwm4.xml").writeText(XFWM4_XML)
            File(xfconfDir, "xfce4-desktop.xml").writeText(DESKTOP_XML)
            File(rootfs, "root/$MARKER").writeText("p6.3.2\n")
        } catch (e: Exception) {
            Log.d(TAG, "look config write failed: ${e.message}")
            return false
        }
        return true
    }

    /**
     * Set the wallpaper on the REAL monitor, live, after the session
     * starts. Discovers the monitor name from the live xfce4-desktop
     * properties and reads the session D-Bus address from the
     * xfce4-session process env (a fresh one-shot has no bus address
     * of its own). Retries while xfconfd comes up. Call from a
     * background thread.
     */
    fun applyWallpaperLive(context: Context): Boolean {
        val script = "PID=\$(pgrep -f '[x]fce4-session' | head -1); " +
            "if [ -n \"\$PID\" ]; then " +
            "DBUS_SESSION_BUS_ADDRESS=\$(tr '\\0' '\\n' < /proc/\$PID/environ | grep '^DBUS_SESSION_BUS_ADDRESS=' | cut -d= -f2-); " +
            "export DBUS_SESSION_BUS_ADDRESS; fi; " +
            "MON=\$(xfconf-query -c xfce4-desktop -l | grep -m1 'backdrop/screen0' | sed 's|/backdrop/screen0/||;s|/workspace0.*||'); " +
            "if [ -n \"\$MON\" ]; then " +
            "xfconf-query -c xfce4-desktop -p \"/backdrop/screen0/\$MON/workspace0/last-image\" -s $WALLPAPER_GUEST_PATH; " +
            "xfconf-query -c xfce4-desktop -p \"/backdrop/screen0/\$MON/workspace0/image-style\" -t int -s 5 && echo WALLPAPER_OK; fi"
        repeat(3) { attempt ->
            val result = TerminalSession.runCommandDebian(
                context, script, idleTimeoutSec = 10, maxTimeoutSec = 60
            )
            Log.d(TAG, "wallpaper live attempt $attempt: ${result.output.takeLast(200)}")
            if (result.output.contains("WALLPAPER_OK")) return true
            if (attempt < 2) Thread.sleep(3000)
        }
        return false
    }

    // Single full-width bottom panel (48 px, dark navy #011948).
    // NO separate dock, NO top panel. Order follows the Manus spec
    // (Whisker, Firefox, Thunar, Writer, Calc, Impress, Settings,
    // Terminal — Manus Control dropped, it does not exist), then an
    // expanding separator, tasklist, systray, clock.
    private const val PANEL_XML = """<?xml version="1.1" encoding="UTF-8"?>
<channel name="xfce4-panel" version="1.0">
  <property name="configver" type="int" value="2"/>
  <property name="panels" type="array">
    <value type="int" value="1"/>
    <property name="dark-mode" type="bool" value="true"/>
    <property name="panel-1" type="empty">
      <property name="position" type="string" value="p=10;x=0;y=0"/>
      <property name="length" type="uint" value="100"/>
      <property name="position-locked" type="bool" value="true"/>
      <property name="size" type="uint" value="48"/>
      <property name="background-style" type="uint" value="1"/>
      <property name="background-color" type="array">
        <value type="double" value="0.0039"/>
        <value type="double" value="0.0980"/>
        <value type="double" value="0.2824"/>
        <value type="double" value="1.0000"/>
      </property>
      <property name="plugin-ids" type="array">
        <value type="int" value="1"/>
        <value type="int" value="2"/>
        <value type="int" value="3"/>
        <value type="int" value="4"/>
        <value type="int" value="5"/>
        <value type="int" value="6"/>
        <value type="int" value="7"/>
        <value type="int" value="8"/>
        <value type="int" value="9"/>
        <value type="int" value="10"/>
        <value type="int" value="11"/>
        <value type="int" value="12"/>
      </property>
    </property>
  </property>
  <property name="plugins" type="empty">
    <property name="plugin-1" type="string" value="whiskermenu"/>
    <property name="plugin-2" type="string" value="launcher">
      <property name="items" type="array">
        <value type="string" value="firefox-esr.desktop"/>
      </property>
    </property>
    <property name="plugin-3" type="string" value="launcher">
      <property name="items" type="array">
        <value type="string" value="thunar.desktop"/>
      </property>
    </property>
    <property name="plugin-4" type="string" value="launcher">
      <property name="items" type="array">
        <value type="string" value="libreoffice-writer.desktop"/>
      </property>
    </property>
    <property name="plugin-5" type="string" value="launcher">
      <property name="items" type="array">
        <value type="string" value="libreoffice-calc.desktop"/>
      </property>
    </property>
    <property name="plugin-6" type="string" value="launcher">
      <property name="items" type="array">
        <value type="string" value="libreoffice-impress.desktop"/>
      </property>
    </property>
    <property name="plugin-7" type="string" value="launcher">
      <property name="items" type="array">
        <value type="string" value="xfce4-settings-manager.desktop"/>
      </property>
    </property>
    <property name="plugin-8" type="string" value="launcher">
      <property name="items" type="array">
        <value type="string" value="xfce4-terminal.desktop"/>
      </property>
    </property>
    <property name="plugin-9" type="string" value="separator">
      <property name="expand" type="bool" value="true"/>
      <property name="style" type="uint" value="0"/>
    </property>
    <property name="plugin-10" type="string" value="tasklist">
      <property name="grouping" type="uint" value="1"/>
    </property>
    <property name="plugin-11" type="string" value="systray">
      <property name="square-icons" type="bool" value="true"/>
    </property>
    <property name="plugin-12" type="string" value="clock"/>
  </property>
</channel>
"""

    // Breeze GTK (Manus spec) + deepin icons (this is a Deepin-style system).
    private const val XSETTINGS_XML = """<?xml version="1.1" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Gtk" type="empty">
    <property name="ThemeName" type="string" value="Breeze"/>
    <property name="IconThemeName" type="string" value="deepin"/>
    <property name="FontName" type="string" value="Sans 10"/>
    <property name="MonospaceFontName" type="string" value="Monospace 10"/>
  </property>
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="Breeze"/>
    <property name="IconThemeName" type="string" value="deepin"/>
  </property>
  <property name="Xft" type="empty">
    <property name="Antialias" type="int" value="1"/>
    <property name="Hinting" type="int" value="1"/>
    <property name="HintStyle" type="string" value="hintslight"/>
    <property name="RGBA" type="string" value="rgb"/>
  </property>
</channel>
"""

    // WhiteSur-Dark window decorations, compositor OFF (Deepin 23 replica spec).
    private const val XFWM4_XML = """<?xml version="1.1" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="theme" type="string" value="WhiteSur-Dark"/>
    <property name="title_font" type="string" value="Sans Bold 9"/>
    <property name="use_compositing" type="bool" value="false"/>
    <property name="workspace_count" type="int" value="1"/>
  </property>
</channel>
"""

    // Blue wallpaper on both candidate monitor paths (the real monitor
    // name is discovered live by applyWallpaperLive; the XML is the
    // durable fallback for the first paint).
    private const val DESKTOP_XML = """<?xml version="1.1" encoding="UTF-8"?>
<channel name="xfce4-desktop" version="1.0">
  <property name="backdrop" type="empty">
    <property name="screen0" type="empty">
      <property name="monitor0" type="empty">
        <property name="workspace0" type="empty">
          <property name="last-image" type="string" value="/usr/share/backgrounds/mudif-wallpaper.png"/>
          <property name="image-style" type="int" value="5"/>
          <property name="color-style" type="int" value="0"/>
          <property name="color1" type="array">
            <value type="double" value="0.0000"/>
            <value type="double" value="0.0705"/>
            <value type="double" value="0.2196"/>
            <value type="double" value="1.0000"/>
          </property>
        </property>
      </property>
      <property name="default" type="empty">
        <property name="workspace0" type="empty">
          <property name="last-image" type="string" value="/usr/share/backgrounds/mudif-wallpaper.png"/>
          <property name="image-style" type="int" value="5"/>
          <property name="color-style" type="int" value="0"/>
          <property name="color1" type="array">
            <value type="double" value="0.0000"/>
            <value type="double" value="0.0705"/>
            <value type="double" value="0.2196"/>
            <value type="double" value="1.0000"/>
          </property>
        </property>
      </property>
    </property>
  </property>
</channel>
"""
}
