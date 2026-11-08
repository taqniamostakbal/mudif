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
 * P8.1 — the LXQt shell itself is Arabic too: the upstream Arabic
 * .qm files (47, 100% translated) are bundled as assets and copied
 * into the rootfs (Debian trixie ships no LXQt .qm at all).
 * P9 — the `mudif` theme: dark purple LXQt theme + purple icon
 * theme (inherits bloom-dark) + purple Openbox theme (Onyx base),
 * bundled as one zip asset and extracted into the rootfs.
 * P9FIX — the theme images did not render (P9 test PARTIAL):
 * the QSS image urls are now absolute guest paths (relative url()
 * fails when the stylesheet is applied as a string), the icon
 * theme gets an icon-theme.cache built inside the guest after
 * extraction (Qt's icon lookup needs the cache to discover the
 * theme), and lxqt.conf selects palette=Mudif explicitly.
 * P9FIX2 — the P9FIX re-test proved the fix landed but changed
 * nothing on screen, and the diagnosis (diagnosis-p9fix/
 * DIAGNOSIS.md) found the real causes. The logo: the Fancy Menu
 * plugin sets the start button icon ITSELF via setButtonIcon()
 * (lxqtfancymenu.cpp; the same logic in LXQtMainMenu for the
 * standard menu) — it reads ownIcon + icon from the [mainmenu]
 * settings and applies `#FancyMenu { qproperty-icon: url(<icon>);
 * }` as the button's own stylesheet, which overrides the theme
 * QSS. With ownIcon=false (the default; the P9FIX panel.conf
 * [mainmenu] has no ownIcon key) the plugin sets the button's
 * stylesheet to EMPTY, clearing the theme QSS's qproperty-icon
 * on that button. So the logo is set in panel.conf
 * (ownIcon=true + icon=<mudif logo>), not in the QSS. The
 * spacers: panel.conf has NO spacer plugin at all (the system
 * default has none either), so there is no #Spacer widget to
 * style — a [spacer] section is written into panel.conf (keys
 * from the lxqt-panel source: size, expandable, spaceType).
 * The icons: the lookup works (a fresh Qt process resolves the
 * mudif overrides to purple); the running processes were stale,
 * so no code change — the fresh-panel re-test is the decisive
 * check. The palette: the palette probe proved
 * QT_STYLE_OVERRIDE is NOT the cause (without it the palette
 * becomes Fusion light, not Mudif), so the override stays and
 * the palette is deferred (its cause is inside libqtlxqt's
 * palette handling).
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
    // P8FIX2: bumped again because the look content changes again
    // (SHELL=/bin/bash in session.conf so qterminal runs bash, and
    // the Mudif scheme moves to the system color-schemes dir —
    // qtermwidget never scans the user dir).
    // P8.1: bumped again because the look content changes again
    // (the upstream Arabic .qm files are copied into the rootfs,
    // so the LXQt shell itself becomes Arabic).
    // P9: bumped again because the look content changes again
    // (the `mudif` theme + icons + Openbox theme are installed,
    // and lxqt.conf switches to theme=mudif, icon_theme=mudif).
    // P9FIX: bumped again because the look content changes again
    // (the QSS image urls become absolute guest paths — relative
    // url() fails when the stylesheet is applied as a string, P9
    // test item 1 + 3 — and the icon theme gets a cache built, P9
    // test item 5; lxqt.conf also selects palette=Mudif, item 4).
    // P9FIX2: bumped again because the look content changes again
    // (panel.conf is now written by LxqtLook, merged never
    // overwritten: the [mainmenu] section gains ownIcon=true +
    // icon=<mudif logo> so the Fancy Menu plugin sets the start
    // button icon itself, and a [spacer] section is added so the
    // panel has a spacer widget to style. The palette is NOT
    // touched: the palette probe proved QT_STYLE_OVERRIDE is NOT
    // the cause of the palette not loading, and removing it would
    // make the desktop light).
    private const val MARKER_VERSION = "p9fix2"
    const val WALLPAPER_GUEST_PATH = "/usr/share/backgrounds/mudif-wallpaper.png"
    private const val WALLPAPER_ASSET = "mudif-wallpaper.png"
    private const val CAIRO_TTF_ASSET = "fonts-cairo/Cairo[slnt,wght].ttf"
    private const val CAIRO_OFL_ASSET = "fonts-cairo/OFL.txt"
    private const val CLI_ASSET = "mudif-cli/arabic-cli.sh"
    private const val COLORSCHEME_ASSET = "mudif-cli/Mudif.colorscheme"
    private const val TRANSLATIONS_ZIP_ASSET = "translations.zip"
    private const val THEME_ZIP_ASSET = "mudif-theme.zip"

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
            // P8FIX2: the scheme MUST live in the system dir
            // /usr/share/qtermwidget6/color-schemes/ (the built-in
            // COLORSCHEMES_DIR). qtermwidget's get_color_schemes_dirs()
            // (tools.cpp) scans ONLY that dir (+ macOS + API-added
            // custom dirs); it NEVER scans the user dir, so the
            // ~/.local/share copy was ignored and the terminal kept
            // its light-grey default. (Proven from the qtermwidget
            // source: tools.cpp get_color_schemes_dirs + CMakeLists
            // COLORSCHEMES_DIR = ${DATADIR}/qtermwidget6/color-schemes.)
            val schemeDir = File(rootfs, "usr/share/qtermwidget6/color-schemes")
            schemeDir.mkdirs()
            context.assets.open(COLORSCHEME_ASSET).use { input ->
                File(schemeDir, "Mudif.colorscheme").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            // P8.1 — Arabic translations: extract the translations.zip
            // asset into the rootfs. The zip's entries mirror the guest
            // tree under /usr/share/ (lxqt/translations/...,
            // pcmanfm-qt/translations/..., libfm-qt6/...,
            // qterminal/...), so each entry lands at
            // usr/share/<entry>. 47 .qm files, all upstream Arabic,
            // 100% translated (2,317/2,317 strings, probe-verified
            // load). qtermwidget_ar.qm is NOT bundled: Debian already
            // installs it, and overwriting a package-owned file would
            // be undone by the next apt upgrade. No _ar_MA.qm copies:
            // the loader asks for *_ar.qm directly under
            // LANG=ar_MA.UTF-8 + LANGUAGE=ar (probe, t4g). A single
            // zip, not 47 loose asset files: the repo push goes
            // through the GitHub web UI, where 47 nested uploads are
            // error-prone; the zip keeps the tree intact as one file.
            extractZipAsset(context, TRANSLATIONS_ZIP_ASSET, File(rootfs, "usr/share"))
            // P9 — the `mudif` theme: extract mudif-theme.zip into the
            // rootfs. Its entries are guest-relative paths, so each
            // lands at File(rootfs, entry.name): the LXQt theme to
            // usr/share/lxqt/themes/mudif/, the palette to
            // usr/share/lxqt/palettes/Mudif, the icons to
            // usr/share/icons/mudif/, the Openbox theme to
            // usr/share/themes/mudif/openbox-3/themerc, and rc.xml to
            // root/.config/openbox/rc.xml. lxqt.conf (below) switches
            // to theme=mudif + icon_theme=mudif; the Openbox theme
            // name is set inside rc.xml itself.
            // P9FIX — the QSS files inside the zip now use ABSOLUTE
            // image urls (url(/usr/share/lxqt/themes/mudif/...)):
            // relative url() fails when LXQt applies the stylesheet
            // as a string, so the start-button logo and the spacer
            // SVGs never rendered (P9 test items 1 + 3).
            extractZipAsset(context, THEME_ZIP_ASSET, rootfs)
            // P9FIX — build the icon-theme cache for `mudif`.
            // The P9 test proved all 10 mudif icon overrides fall
            // back to bloom-dark defaults, and the one verified
            // difference is that /usr/share/icons/mudif has NO
            // icon-theme.cache while bloom-dark does (40,180 B).
            // Qt's icon lookup uses the cache to discover the theme;
            // without it the mudif icons are never found. A prebuilt
            // cache shipped in the zip would be ignored: the cache
            // records the theme directory mtime, and extraction sets
            // a new mtime, so the cache is stale on arrival. Building
            // it inside the guest (after extraction) is the only
            // correct way. gtk-update-icon-cache is verified present
            // in the guest (/usr/bin/gtk-update-icon-cache, P9 test).
            // -f forces a fresh cache on re-apply (idempotent). This
            // is a guest command, not a host-side write: it runs via
            // the same one-shot proot mechanism as
            // ensureDebianDesktop, before the desktop launches, so
            // the session reads the cache at startup. If it fails,
            // the look is NOT marked applied (return false) so the
            // next launch retries — the icons are the whole point of
            // P9FIX, and a silent failure would leave them broken
            // under a "p9fix" marker.
            val cacheResult = TerminalSession.runCommandDebian(
                context,
                "gtk-update-icon-cache -f /usr/share/icons/mudif && echo ICON_CACHE_OK",
                idleTimeoutSec = 30,
                maxTimeoutSec = 120
            )
            Log.d(TAG, "icon cache build exit=${cacheResult.exitCode} out=${cacheResult.output.takeLast(200)}")
            if (!cacheResult.output.contains("ICON_CACHE_OK")) {
                Log.d(TAG, "icon cache build failed — look not applied")
                return false
            }
            // P9FIX2 — the panel's start-button logo + a spacer
            // plugin, via panel.conf (merged, never overwritten —
            // the panel rewrites panel.conf on exit, so overwriting
            // it would lose the user's plugin layout; the same
            // clobber race as pcmanfm-qt's settings.conf). The
            // base is the existing panel.conf if the panel already
            // created one, else the system default
            // /etc/xdg/lxqt/panel.conf (lxqt-panel is installed —
            // startlxqt exists — so the default must exist). The
            // write happens here, before the first desktop launch
            // (the same ordering as lxqt.conf / session.conf), so
            // no running panel can clobber it, and the panel reads
            // it at startup and preserves both additions when it
            // rewrites panel.conf on exit.
            val panelConf = File(lxqtDir, "panel.conf")
            val existingPanelConf = when {
                panelConf.exists() -> panelConf.readText()
                File(rootfs, "etc/xdg/lxqt/panel.conf").exists() ->
                    File(rootfs, "etc/xdg/lxqt/panel.conf").readText()
                else -> null
            }
            panelConf.writeText(mergePanelConf(existingPanelConf))
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

    // Extract a zip asset into the rootfs. Each entry lands at
    // File(destRoot, entry.name). Any failure throws and is caught by
    // ensureLxqtLook, which then reports false honestly. The ".."
    // guard is zip-slip defence (our zips are built by us, but the
    // check costs one line).
    private fun extractZipAsset(context: Context, assetName: String, destRoot: File) {
        java.util.zip.ZipInputStream(context.assets.open(assetName)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && ".." !in entry.name) {
                    val dest = File(destRoot, entry.name)
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    // LXQt theme: dark panel + bloom-dark (Deepin) icons.
    // P9: theme=mudif + icon_theme=mudif (the `mudif` theme and icon
    // theme installed by extractZipAsset above). Before P9 this was
    // theme=dark + icon_theme=bloom-dark.
    // P9FIX: palette=Mudif selects the `Mudif` palette explicitly
    // (usr/share/lxqt/palettes/Mudif, highlight #593BFE). The file
    // name is capitalized like upstream (theme `dark` -> palette
    // `Dark`), so it matches the theme-name lookup; the explicit
    // key covers the case where LXQt reads the palette name from
    // the config instead. Harmless if unread (an extra key).
    private const val LXQT_CONF = """[General]
__userfile__=true
icon_theme=mudif
theme=mudif
palette=Mudif
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
    // P8FIX2: SHELL=/bin/bash so qterminal runs bash (not /bin/sh)
    // and sources .bashrc (the Arabic prompt/banner/CLI).
    private const val SESSION_CONF = """[General]
__userfile__=true
window_manager=openbox

[Environment]
QT_STYLE_OVERRIDE=Adwaita-Dark
GTK_THEME=Adwaita:dark
QT_QPA_PLATFORMTHEME=lxqt
LANG=ar_MA.UTF-8
LANGUAGE=ar
SHELL=/bin/bash
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

    // P9FIX2 — merge the panel's start-button icon + a spacer
    // plugin into panel.conf, preserving every plugin section
    // already present (the panel rewrites panel.conf on exit, so
    // overwriting it would lose the user's plugin layout).
    // The logo: LXQtFancyMenu::setButtonIcon() (lxqtfancymenu.cpp;
    // the same logic in LXQtMainMenu::setButtonIcon for the
    // standard menu) reads ownIcon + icon from the [mainmenu]
    // settings and sets the button's own stylesheet to
    // `#FancyMenu { qproperty-icon: url(<icon>); }`. When ownIcon
    // is false (the default — the P9FIX panel.conf [mainmenu] has
    // no ownIcon key) the plugin sets the button's stylesheet to
    // EMPTY, which clears the theme QSS's qproperty-icon on that
    // button. That is why the logo never rendered even though the
    // QSS url() itself works (diagnosis Q4 proved url() loads the
    // PNG on a plain button). Setting ownIcon=true + icon=<mudif
    // logo> in [mainmenu] makes the plugin set the icon itself,
    // via its own stylesheet (which overrides the theme QSS). The
    // theme QSS qproperty-icon is left in place (harmless; the
    // plugin's rule wins). The icon path is the mudif logo PNG
    // already installed by the theme zip
    // (/usr/share/lxqt/themes/mudif/mainmenu.png).
    // The spacer: panel.conf has NO spacer plugin (the system
    // default /etc/xdg/lxqt/panel.conf has none either), so there
    // is no #Spacer widget for the QSS spacer rules to style
    // (diagnosis Q5). The spacer plugin exists (a core plugin,
    // built into the lxqt-panel binary) and its config keys come
    // from the lxqt-panel source (plugin-spacer/spacer.cpp
    // settingsChanged, plugin-spacer/spacerconfiguration.cpp
    // msTypes): size (default 8), expandable (default false),
    // spaceType (lined/dotted/invisible, default lined). The panel
    // loads plugins by scanning sections with a type= key (the user
    // panel.conf has no [panel1] plugins= list), so adding a
    // [spacer] section is sufficient. The panel preserves a loaded
    // plugin when it rewrites panel.conf on exit, so the section
    // persists. expandable=true makes the spacer push the
    // right-aligned plugins to the right edge (the usual layout
    // purpose); size is ignored when expandable.
    // A key already present is replaced in place (one copy only);
    // a missing key is appended at the end of its section. A
    // re-merge of an already-merged file is byte-identical.
    private fun mergePanelConf(existing: String?): String {
        val mainmenuKeys = linkedMapOf(
            "icon" to "/usr/share/lxqt/themes/mudif/mainmenu.png",
            "ownIcon" to "true"
        )
        val spacerLines = listOf(
            "[spacer]",
            "alignment=Left",
            "type=spacer",
            "size=8",
            "expandable=true",
            "spaceType=lined"
        )
        val out = StringBuilder()
        val seenMainmenuKeys = mutableSetOf<String>()
        var inMainmenu = false
        var mainmenuSeen = false
        var spacerSeen = false
        fun appendMissingMainmenuKeys() {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            for ((k, v) in mainmenuKeys) if (k !in seenMainmenuKeys) out.append("$k=$v\n")
        }
        // trimEnd on the input drops the trailing empty lines the
        // file's final newline produces, so no blank line lands
        // inside [mainmenu] when it is the last section.
        for (line in (existing?.trimEnd()?.lines() ?: emptyList())) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (inMainmenu) appendMissingMainmenuKeys()
                inMainmenu = trimmed == "[mainmenu]"
                if (inMainmenu) mainmenuSeen = true
                if (trimmed == "[spacer]") spacerSeen = true
                out.append(line).append("\n")
                continue
            }
            if (inMainmenu) {
                val key = trimmed.substringBefore("=").trim()
                if (key in mainmenuKeys) {
                    if (key !in seenMainmenuKeys) {
                        out.append("$key=${mainmenuKeys[key]}\n")
                        seenMainmenuKeys.add(key)
                    }
                    continue
                }
            }
            out.append(line).append("\n")
        }
        if (inMainmenu) appendMissingMainmenuKeys()
        if (!mainmenuSeen) {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            out.append("[mainmenu]\n")
            out.append("alignment=Left\n")
            out.append("type=fancymenu\n")
            for ((k, v) in mainmenuKeys) out.append("$k=$v\n")
        }
        if (!spacerSeen) {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            for (l in spacerLines) out.append(l).append("\n")
        }
        // Normalize the ending: exactly one trailing newline, so a
        // re-merge of an already-merged file is byte-identical.
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
