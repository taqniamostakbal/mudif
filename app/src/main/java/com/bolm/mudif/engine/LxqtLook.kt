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
 *
 * P8 — Arabic layer: LANG ar_MA.UTF-8 + LANGUAGE=ar (session env,
 * RootfsManager one-shot env and boot argv), Cairo variable font
 * bundled as an asset (fonts-cairo, no apt package exists), Noto
 * Arabic fallback from apt, purple wallpaper (the user's pick),
 * and the Arabic CLI (48 Fusha commands + Mudif qterminal scheme).
 * The LXQt shell (panel, pcmanfm-qt menus) stays English: no Arabic
 * .qm ships in Debian trixie (upstream .qm follow-up is P8.1).
 * Lifta is NOT bundled (EULA forbids App Product embedding); it is
 * pixels in the wallpaper only.
 */
object LxqtLook {
    private const val TAG = "LxqtLook"

    private const val MARKER = ".mudif_lxqt_look"
    // P6.6: the marker carries the look VERSION, and isApplied()
    // compares its content. P6.5 changed SESSION_CONF but left the
    // marker at "p6.4" and isApplied() only checked existence, so the
    // rewrite was skipped on every existing rootfs and the Adwaita-dark
    // fix never reached the guest (TEST-REPORT-P65 §6). The version
    // is bumped again for P8 because the look content changes again
    // (Arabic: LANG ar_MA.UTF-8, Cairo font, purple wallpaper,
    // Arabic CLI): bumping the version is what makes an
    // existing rootfs receive the new configs.
    private const val MARKER_VERSION = "p8"
    const val WALLPAPER_GUEST_PATH = "/usr/share/backgrounds/mudif-wallpaper.png"
    private const val WALLPAPER_ASSET = "mudif-wallpaper.png"
    private const val CAIRO_TTF_ASSET = "fonts-cairo/Cairo[slnt,wght].ttf"
    private const val CAIRO_OFL_ASSET = "fonts-cairo/OFL.txt"
    private const val CLI_ASSET = "mudif-cli/arabic-cli.sh"
    private const val COLORSCHEME_ASSET = "mudif-cli/Mudif.colorscheme"

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
            // P8 — Arabic: Cairo font + fontconfig + Arabic CLI.
            // Cairo cannot come from apt (no fonts-cairo in trixie,
            // P8-READINESS §1.1): bundle the variable TTF as an app
            // asset and copy it into the rootfs. Lifta is NOT bundled
            // (EULA forbids App Product embedding); it lives only as
            // pixels inside the wallpaper PNG.
            val cairoDir = File(rootfs, "usr/share/fonts/truetype/cairo")
            cairoDir.mkdirs()
            context.assets.open(CAIRO_TTF_ASSET).use { input ->
                File(cairoDir, "Cairo[slnt,wght].ttf").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            context.assets.open(CAIRO_OFL_ASSET).use { input ->
                File(cairoDir, "OFL.txt").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            val fontsConfDir = File(rootfs, "etc/fonts")
            fontsConfDir.mkdirs()
            File(fontsConfDir, "local.conf").writeText(LOCAL_CONF)
            // P8 — Arabic CLI: the catalog file, the qterminal colour
            // scheme, the qterminal.ini keys (merged, never overwritten),
            // and the .bashrc block (appended once, idempotent).
            val mudifConfDir = File(rootfs, "root/.config/mudif")
            mudifConfDir.mkdirs()
            context.assets.open(CLI_ASSET).use { input ->
                File(mudifConfDir, "arabic-cli.sh").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            val schemeDir = File(rootfs, "root/.local/share/qtermwidget6/color-schemes")
            schemeDir.mkdirs()
            context.assets.open(COLORSCHEME_ASSET).use { input ->
                File(schemeDir, "Mudif.colorscheme").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            val qterminalIni = File(rootfs, "root/.config/qterminal.org/qterminal.ini")
            qterminalIni.parentFile?.mkdirs()
            val existingIni = if (qterminalIni.exists()) qterminalIni.readText() else null
            qterminalIni.writeText(mergeQterminalIni(existingIni))
            val bashrc = File(rootfs, "root/.bashrc")
            val bashrcText = if (bashrc.exists()) bashrc.readText() else ""
            if (!bashrcText.contains("# mudif-arabic-cli")) {
                bashrc.appendText(BASHRC_BLOCK)
            }
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
    // P8: LANG ar_MA.UTF-8 + LANGUAGE=ar make the session Arabic
    // (the locale itself is generated by ensureDebianDesktop).
    private const val SESSION_CONF = """[General]
__userfile__=true
window_manager=openbox

[Environment]
QT_STYLE_OVERRIDE=Adwaita-Dark
GTK_THEME=Adwaita:dark
QT_QPA_PLATFORMTHEME=lxqt
LANG=ar_MA.UTF-8
LANGUAGE=ar
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

    // P8 — fontconfig: Cairo is the preferred sans-serif and serif
    // font, and Arabic text prefers Cairo (Noto Sans Arabic is the
    // next fallback, installed from apt). Monospace is NOT aliased:
    // Cairo has no monospace, and the terminal keeps DejaVu Sans Mono
    // (its Arabic glyphs fall back to Cairo through fontconfig).
    private const val LOCAL_CONF = """<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <alias>
    <family>sans-serif</family>
    <prefer><family>Cairo</family></prefer>
  </alias>
  <alias>
    <family>serif</family>
    <prefer><family>Cairo</family></prefer>
  </alias>
  <match target="pattern">
    <test name="lang" compare="contains">
      <string>ar</string>
    </test>
    <edit name="family" mode="prepend">
      <string>Cairo</string>
    </edit>
  </match>
</fontconfig>
"""

    // P8 — the .bashrc block that loads the Arabic CLI. Appended
    // once (the "# mudif-arabic-cli" marker guards re-runs). The PS1
    // shows "نظام مضيف" in purple, the current directory in cyan,
    // then the ❯ prompt mark (ARABIC-CLI-DESIGN §3.3).
    private const val BASHRC_BLOCK = """
# mudif-arabic-cli
if [ -f /root/.config/mudif/arabic-cli.sh ]; then
  . /root/.config/mudif/arabic-cli.sh
fi
PS1='\[\e[35m\]نظام مضيف\[\e[0m\] \[\e[36m\]\w\[\e[0m\] ❯ '
"""

    // P8 — merge the four qterminal keys into [General], preserving
    // every key already present (HideTabBarWithOneTab, version,
    // [MainWindow], [Shortcuts]) and any key the user changed.
    // If the file does not exist yet, a [General] section is created.
    // A key already present is replaced in place (one copy only).
    private fun mergeQterminalIni(existing: String?): String {
        val keys = linkedMapOf(
            "colorScheme" to "Mudif",
            "fontFamily" to "DejaVu Sans Mono",
            "fontSize" to "13",
            "enabledBidiSupport" to "true"
        )
        val out = StringBuilder()
        val seen = mutableSetOf<String>()
        var inGeneral = false
        var generalSeen = false
        fun appendMissingKeys() {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            for ((k, v) in keys) if (k !in seen) out.append("$k=$v\n")
        }
        // trimEnd on the input drops the trailing empty lines the
        // file's final newline produces, so no blank line lands
        // inside [General] when it is the last section.
        for (line in (existing?.trimEnd()?.lines() ?: emptyList())) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (inGeneral) appendMissingKeys()
                inGeneral = trimmed == "[General]"
                if (inGeneral) generalSeen = true
                out.append(line).append("\n")
                continue
            }
            if (inGeneral) {
                val key = trimmed.substringBefore("=").trim()
                if (key in keys) {
                    if (key !in seen) {
                        out.append("$key=${keys[key]}\n")
                        seen.add(key)
                    }
                    continue
                }
            }
            out.append(line).append("\n")
        }
        if (inGeneral) appendMissingKeys()
        if (!generalSeen) {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            out.append("[General]\n")
            for ((k, v) in keys) out.append("$k=$v\n")
        }
        // Normalize the ending: exactly one trailing newline, so a
        // re-merge of an already-merged file is byte-identical
        // (without this, each merge would add one more blank line).
        return out.toString().trimEnd() + "\n"
    }

    // XDG autostart override that disables lxqt-powermanagement
    // (see ensureLxqtLook). Hidden=true in ~/.config/autostart
    // beats the /etc/xdg/autostart entry of the same name.
    private const val POWERMANAGEMENT_HIDDEN = """[Desktop Entry]
Type=Application
Name=lxqt-powermanagement
Hidden=true
"""
}
