package com.bolm.mudif.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * P6.4 — the Deepin-style look for نظام مضيف (ONE system, Debian + LXQt).
 *
 * XFCE is gone by the user's order (2026-11-07); the look moves from
 * DeepinLook's xfconf XMLs + WhiteSur to LXQt configs. The recipe is
 * the one proven rendering on Alpine in the 2026-11-07 LXQt test,
 * adapted to Debian trixie:
 *  - Navy wallpaper (the same mudif-wallpaper.png asset).
 *  - Deepin icons: bloom-dark (deepin-icon-theme ships bloom /
 *    bloom-dark on Debian; no copying needed, unlike Alpine).
 *  - Dark panel: LXQt theme=dark in lxqt.conf.
 *  - Dark Qt windows: Adwaita-Dark via QT_STYLE_OVERRIDE.
 *  - Dark GTK apps: Adwaita:dark via GTK_THEME.
 *  - The lxqt-qtplugin platform theme (QT_QPA_PLATFORMTHEME=lxqt)
 *    is what applies icon_theme to Qt apps at all.
 *
 * Kvantum is DELIBERATELY NOT used: there is NO kvantum or
 * kvantum-themes package in Debian trixie (verified
 * packages.debian.org 2026-11-07: "No such package") — the Alpine
 * recipe (kvantum + KvArcDark) does not transfer. Adwaita-dark is
 * the Debian-native dark Qt style (upstream FedoraQt README lists
 * Adwaita / Adwaita-dark / HighContrast).
 *
 * The look is applied ONCE per rootfs (marker .mudif_lxqt_look in
 * the guest's /root) by writing files directly into the rootfs (no
 * guest execution needed — the app owns these files), BEFORE the
 * first desktop launch. That order matters: the 2026-11-07 LXQt
 * test proved the running pcmanfm-qt rewrites settings.conf on
 * exit (clobber race) — writing before any launch means no running
 * process can clobber the files. No live apply step exists for
 * LXQt (there is no xfconf to query); the configs are read by the
 * session at startup.
 *
 * The user vetoed a separate dock ("الدوك، لا"): there is NO Plank
 * and NO floating dock — the single full-width bottom lxqt-panel IS
 * the taskbar.
 */
object LxqtLook {
    private const val TAG = "LxqtLook"

    private const val MARKER = ".mudif_lxqt_look"
    // P6.6: the marker carries the look VERSION, and isApplied()
    // compares its content. P6.5 changed SESSION_CONF but left the
    // marker at "p6.4" and isApplied() only checked existence, so the
    // rewrite was skipped on every existing rootfs and the Adwaita-dark
    // fix never reached the guest (TEST-REPORT-P65 §6). The version
    // is bumped again for P6.7 because the look content changes again
    // (QT_STYLE_OVERRIDE canonical spelling Adwaita-Dark,
    // STYLE-PROBE-P66): bumping the version is what makes an
    // existing rootfs receive the new configs.
    private const val MARKER_VERSION = "p6.7"
    const val WALLPAPER_GUEST_PATH = "/usr/share/backgrounds/mudif-wallpaper.png"
    private const val WALLPAPER_ASSET = "mudif-wallpaper.png"

    fun isApplied(context: Context): Boolean {
        val marker = File(DebianSystem.debianRootfsDir(context), "root/$MARKER")
        if (!marker.exists()) return false
        return try {
            marker.readText().trim() == MARKER_VERSION
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Apply the whole look. Host-side file writes only, best-effort,
     * idempotent (the marker gates re-runs). Call from a background
     * thread. Returns true only when the theme packages are present
     * AND every file is written.
     */
    fun ensureLxqtLook(context: Context): Boolean {
        if (isApplied(context)) return true
        val rootfs = DebianSystem.debianRootfsDir(context)

        // Prereq: the apt set must have landed (startlxqt + the
        // bloom-dark icons). If ensureDebianDesktop failed, the look
        // must report false honestly instead of writing configs for
        // programs that are not there.
        if (!File(rootfs, "usr/bin/startlxqt").exists()) {
            Log.d(TAG, "startlxqt missing — Debian desktop set not installed")
            return false
        }
        if (!File(rootfs, "usr/share/icons/bloom-dark").exists()) {
            Log.d(TAG, "bloom-dark icons missing — deepin-icon-theme not installed")
            return false
        }

        try {
            val backgrounds = File(rootfs, "usr/share/backgrounds")
            backgrounds.mkdirs()
            context.assets.open(WALLPAPER_ASSET).use { input ->
                File(backgrounds, "mudif-wallpaper.png").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            val lxqtDir = File(rootfs, "root/.config/lxqt")
            lxqtDir.mkdirs()
            File(lxqtDir, "lxqt.conf").writeText(LXQT_CONF)
            File(lxqtDir, "session.conf").writeText(SESSION_CONF)
            val pcmanfmDir = File(rootfs, "root/.config/pcmanfm-qt/lxqt")
            pcmanfmDir.mkdirs()
            File(pcmanfmDir, "settings.conf").writeText(PCMANFM_SETTINGS_CONF)
            // P6.6: lxqt-powermanagement crashes on every login in the
            // emulator ("Unsupported extension", TEST-REPORT-P65) and is
            // dead in ps. Disable it with the standard XDG autostart
            // override: a user-level file with Hidden=true beats
            // /etc/xdg/autostart/lxqt-powermanagement.desktop
            // (confirmed filename, PAINT-PROBE-P65 §5). The system
            // file itself is NOT deleted.
            val autostartDir = File(rootfs, "root/.config/autostart")
            autostartDir.mkdirs()
            File(autostartDir, "lxqt-powermanagement.desktop").writeText(POWERMANAGEMENT_HIDDEN)
            File(rootfs, "root/$MARKER").writeText("$MARKER_VERSION\n")
        } catch (e: Exception) {
            Log.d(TAG, "look config write failed: ${e.message}")
            return false
        }
        return true
    }

    // LXQt theme: dark panel + bloom-dark (Deepin) icons.
    private const val LXQT_CONF = """[General]
__userfile__=true
icon_theme=bloom-dark
theme=dark
"""

    // Openbox is LXQt's window manager. The Environment section is
    // what the session exports to every Qt/GTK app: Adwaita-Dark is
    // the dark Qt widget style (the registered key, capital A capital
    // D; Qt6's factory lookup is case-insensitive so both spellings
    // resolve there, but Qt5's qlipper needs the adwaita-qt plugin
    // installed, STYLE-PROBE-P66), Adwaita:dark the dark GTK theme,
    // and the lxqt platform theme is what applies icon_theme at all.
    private const val SESSION_CONF = """[General]
__userfile__=true
window_manager=openbox

[Environment]
QT_STYLE_OVERRIDE=Adwaita-Dark
GTK_THEME=Adwaita:dark
QT_QPA_PLATFORMTHEME=lxqt
"""

    // pcmanfm-qt draws the desktop wallpaper. It reads Wallpaper /
    // WallpaperMode / BgColor from the [Desktop] group ONLY
    // (Settings::loadFile, beginGroup("Desktop")). Writing Wallpaper
    // under [General] is invisible to it: mode defaults to
    // WallpaperNone, updateWallpaper() returns early, and
    // paintBackground() fills the window solid BgColor (#000000) —
    // the 0% paint defect, proven live by PAINT-PROBE2-P65 (0.00% ->
    // 92.16% with the corrected config). WallpaperMode=stretch is
    // required alongside the path: without it the mode is none and
    // the desktop stays black. No BgColor key is needed (default
    // black is invisible under a covering wallpaper).
    private const val PCMANFM_SETTINGS_CONF = """[Desktop]
Wallpaper=/usr/share/backgrounds/mudif-wallpaper.png
WallpaperMode=stretch
"""

    // XDG autostart override that disables lxqt-powermanagement
    // (see ensureLxqtLook). Hidden=true in ~/.config/autostart
    // beats the /etc/xdg/autostart entry of the same name.
    private const val POWERMANAGEMENT_HIDDEN = """[Desktop Entry]
Type=Application
Name=lxqt-powermanagement
Hidden=true
"""
}
