package com.bolm.mudif.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * P6.4 - the Deepin-style look for نظام مضيف (ONE system, Debian + LXQt).
 *
 * XFCE is gone by the user's order (2026-11-07); the look moves from
 * DeepinLook's xfconf XMLs + WhiteSur to LXQt configs. The recipe is
 * the one proven rendering on Alpine in the 2026-11-07 LXQt test,
 * adapted to Debian trixie:
 *  - Navy wallpaper (the same mudif-wallpaper.png asset).
 *  - Deepin icons: bloom-dark (deepin-icon-theme ships bloom /
 *    bloom-dark on Debian; no copying needed, unlike Alpine).
 *  - Dark panel: LXQt theme=dark in lxqt.conf.
 *  - Dark Qt windows: Fusion + the [Palette] group in lxqt.conf.
 *  - Dark GTK apps: Adwaita:dark via GTK_THEME.
 *  - The lxqt-qtplugin platform theme (QT_QPA_PLATFORMTHEME=lxqt)
 *    is what applies icon_theme to Qt apps at all.
 *
 * Kvantum is DELIBERATELY NOT used: there is NO kvantum or
 * kvantum-themes package in Debian trixie (verified
 * packages.debian.org 2026-11-07: "No such package") - the Alpine
 * recipe (kvantum + KvArcDark) does not transfer. Adwaita-dark is
 * the Debian-native dark Qt style (upstream FedoraQt README lists
 * Adwaita / Adwaita-dark / HighContrast).
 *
 * The look is applied ONCE per rootfs (marker .mudif_lxqt_look in
 * the guest's /root) by writing files directly into the rootfs (no
 * guest execution needed - the app owns these files), BEFORE the
 * first desktop launch. That order matters: the 2026-11-07 LXQt
 * test proved the running pcmanfm-qt rewrites settings.conf on
 * exit (clobber race) - writing before any launch means no running
 * process can clobber the files. No live apply step exists for
 * LXQt (there is no xfconf to query); the configs are read by the
 * session at startup.
 *
 * The user vetoed a separate dock ("الدوك، لا"): there is NO Plank
 * and NO floating dock - the single full-width bottom lxqt-panel IS
 * the taskbar.
 *
 * P8 - Arabic layer: LANG ar_MA.UTF-8 + LANGUAGE=ar (session env,
 * RootfsManager one-shot env and boot argv), Cairo variable font
 * bundled as an asset (fonts-cairo, no apt package exists), Noto
 * Arabic fallback from apt, purple wallpaper (the user's pick),
 * and the Arabic CLI (48 Fusha commands + Mudif qterminal scheme).
 * P8.1 - the LXQt shell itself is Arabic too: the upstream Arabic
 * .qm files (47, 100% translated) are bundled as assets and copied
 * into the rootfs (Debian trixie ships no LXQt .qm at all).
 * P9 - the `mudif` theme: dark purple LXQt theme + purple icon
 * theme (inherits bloom-dark) + purple Openbox theme (Onyx base),
 * bundled as one zip asset and extracted into the rootfs.
 * P9FIX - the theme images did not render (P9 test PARTIAL):
 * the QSS image urls were made absolute guest paths (the P9
 * diagnosis suspected relative url() fails when the stylesheet
 * is applied as a string), the icon theme gets an
 * icon-theme.cache built inside the guest after extraction
 * (Qt's icon lookup needs the cache to discover the theme),
 * and lxqt.conf selects palette=Mudif explicitly.
 * P9FIX6 - the absolute urls were WRONG: the spacer diagnosis
 * (diagnosis-spacer/DIAGNOSIS-SPACER.md) proved the LXQt theme
 * loader prepends the theme directory to every QSS url(), so
 * absolute urls are resolved to a DOUBLED path that does not
 * exist and QPixmap returns null (the session log catches it:
 * "Could not create pixmap from .../mudif//usr/share/lxqt/
 * themes/mudif/spacer-plugin/spacer-line.png"). Stock LXQt
 * themes all use RELATIVE urls (the convention). The QSS urls
 * are back to theme-relative (the spacer keeps its original
 * root-relative /spacer-plugin/... form, as stock themes do).
 * The P9FIX5 base #Spacer rule is KEPT (the log proves it
 * matches the live SpacerWidget).
 * P9FIX2 - the P9FIX re-test proved the fix landed but changed
 * nothing on screen, and the diagnosis (diagnosis-p9fix/
 * DIAGNOSIS.md) found the real causes. The logo: the Fancy Menu
 * plugin sets the start button icon ITSELF via setButtonIcon()
 * (lxqtfancymenu.cpp; the same logic in LXQtMainMenu for the
 * standard menu) - it reads ownIcon + icon from the [mainmenu]
 * settings and applies `#FancyMenu { qproperty-icon: url(<icon>);
 * }` as the button's own stylesheet, which overrides the theme
 * QSS. With ownIcon=false (the default; the P9FIX panel.conf
 * [mainmenu] has no ownIcon key) the plugin sets the button's
 * stylesheet to EMPTY, clearing the theme QSS's qproperty-icon
 * on that button. So the logo is set in panel.conf
 * (ownIcon=true + icon=<mudif logo>), not in the QSS. The
 * spacers: panel.conf has NO spacer plugin at all (the system
 * default has none either), so there is no #Spacer widget to
 * style - a [spacer] section is written into panel.conf (keys
 * from the lxqt-panel source: size, expandable, spaceType).
 * The icons: the lookup works (a fresh Qt process resolves the
 * mudif overrides to purple); the running processes were stale,
 * so no code change - the fresh-panel re-test is the decisive
 * check. P15 (2026-11-08, PALETTE-PROOF.md) SUPERSEDES the old palette
 * verdict: the cause was the missing [Palette] group in lxqt.conf
 * (lxqt-qtplugin builds the palette solely from it) AND Adwaita-Dark
 * masking it with its own palette. The fix is the [Palette] group in
 * LXQT_CONF + QT_STYLE_OVERRIDE=Fusion, proven live (all ten roles
 * exact: Window #232323, Highlight #593BFE).
 * P9FIX3: the P9FIX2 re-test proved the [spacer] section alone
 * is NOT enough - the panel loads plugins ONLY from the
 * `plugins=` list in [panel1] (LXQtPanel::loadPlugins), and the
 * user panel.conf [panel1] has NO `plugins=` key, so it falls
 * back to the system default's 12-plugin list (no spacer). The
 * merge now also writes `plugins=` (the system default's
 * 12-plugin order + `spacer`) into [panel1]. The icons: the
 * fresh-panel re-test REFUTED the stale-process explanation
 * (a fresh panel still shows stock icons), so the cause is
 * deeper and needs a diagnostic before any code change.
 * P9FIX4: the icons diagnosis proved two separate causes. (A)
 * The panel reads its icon theme from panel.conf `[General]
 * iconTheme`, and the user panel.conf has no iconTheme key, so
 * it falls back to the system default's Papirus-Dark (its
 * mapped caches are Papirus-Dark + hicolor only, no mudif).
 * The merge now writes `iconTheme=mudif` into [General].
 * (B) libfm-qt requests folders by the MIME name
 * `inode-directory`, which mudif lacks, so they fall back to
 * bloom-dark blue. The theme zip gains 6 MIME folder SVGs.
 * (C) The P9FIX3 re-test proved the spacer IS loaded (layout
 * shift) but its SVG marks never paint, because Qt stylesheet
 * `background-image` loads via QPixmap, which has no SVG
 * plugin in this build (a PNG control renders 7,283 px where
 * the SVG renders 0). The spacer SVGs become PNGs and the
 * QSS urls point at the PNGs.
 * P9FIX5: the P9FIX4 re-test proved the spacer marks still do not
 * paint, and the cause is a QSS re-polish TIMING bug, not the PNGs:
 * lxqt-panel applies the stylesheet BEFORE it sets the spacer's
 * type/orientation, and setType/setOrientation do not trigger a
 * re-polish, so #Spacer[type="lined"] and
 * #Spacer[orientation="horizontal"] never match (timing probe:
 * real order 0 px, real order + forced re-polish 84 px, control
 * 84 px). The fix puts the image + repeat-y + margins on the
 * base #Spacer rule with no condition (the spacer is always
 * lined and horizontal in our panel.conf). The terminal font
 * becomes Cairo (his order: "التيرمينال خاصو خط كايرو").
 * P9FIX6: the P9FIX5 re-test proved the base-rule fix renders
 * 84 px in every probe but the real panel still shows 0 px,
 * and the diagnosis (diagnosis-spacer/DIAGNOSIS-SPACER.md)
 * proved the real cause: the LXQt theme loader prepends the
 * theme directory to every QSS url(), so the P9FIX ABSOLUTE
 * urls are resolved to a DOUBLED path that does not exist and
 * QPixmap returns null (session log: "Could not create pixmap
 * from .../mudif//usr/share/lxqt/themes/mudif/spacer-plugin/
 * spacer-line.png"). The fix reverts ALL 17 QSS urls to
 * theme-relative paths (stock LXQt convention); the P9FIX5 base
 * #Spacer rule is KEPT.
 * P11: desktop polish (his order 2026-11-08: the picked
 * wallpapers must actually be in the system, a professional
 * panel, and a Start menu like the mockups in branding/):
 * the pomegranate + bold poster wallpapers are bundled as
 * assets and copied into /usr/share/backgrounds/ next to the
 * purple default (default stays purple); the panel becomes a
 * translucent purple gradient (bold mockup); the QuickLaunch
 * plugin gets the 4 mockup apps (file manager, terminal,
 * browser, settings); the Fancy Menu search box and category
 * rows become rounded and lighter (launcher mockups). The
 * palette is NOT touched: still deferred. The spacer is NOT
 * touched: P9FIX6-verified.
 * P14: match the reference mockups exactly (his order 2026-11-08,
 * after the P13 re-test: the desktop must show the mockup
 * wallpapers, the mockup panel, and the mockup start menu).
 * (1) Default wallpaper becomes the pomegranate poster (his pick;
 * bold stays selectable, purple stays selectable). (2) Panel
 * plugins become the reference layout: mainmenu, quicklaunch,
 * taskbar, tray, statusnotifier, mount, volume, worldclock.
 * Removed: showdesktop, desktopswitch (the 4 3 2 1 pager),
 * kbindicator (US S N C), quicklaunch2 (dead), spacer (the lone
 * line). The taskbar is expected to expand and push the clock
 * group to the far end; if the re-test shows the clock still
 * floating in the middle, P15 adds back an invisible spacer.
 * (3) Fancy Menu favorites are set to the 4 real desktop apps
 * (pcmanfm-qt, qterminal, firefox-esr, lxqt-config) via the
 * favorites array in [mainmenu]. The store and the calendar are
 * NOT desktop apps, so they cannot be favorites; no fake
 * .desktop entries are created. The palette is NOT touched:
 * still deferred. RTL is kept (correct for Arabic); the mockups
 * are LTR only because they are AI-generated.
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
    // the Mudif scheme moves to the system color-schemes dir -
    // qtermwidget never scans the user dir).
    // P8.1: bumped again because the look content changes again
    // (the upstream Arabic .qm files are copied into the rootfs,
    // so the LXQt shell itself becomes Arabic).
    // P9: bumped again because the look content changes again
    // (the `mudif` theme + icons + Openbox theme are installed,
    // and lxqt.conf switches to theme=mudif, icon_theme=mudif).
    // P9FIX: bumped again because the look content changes again
    // (the QSS image urls become absolute guest paths - relative
    // url() fails when the stylesheet is applied as a string, P9
    // test item 1 + 3 - and the icon theme gets a cache built, P9
    // test item 5; lxqt.conf also selects palette=Mudif, item 4).
    // P9FIX2: bumped again because the look content changes again
    // (panel.conf is now written by LxqtLook, merged never
    // overwritten: the [mainmenu] section gains ownIcon=true +
    // icon=<mudif logo> so the Fancy Menu plugin sets the start
    // button icon itself, and a [spacer] section is added so the
    // panel has a spacer widget to style. The palette is NOT
    // touched here (P15 later proved the cause: the missing
    // [Palette] group in lxqt.conf + Adwaita-Dark masking it).
    // P9FIX3: bumped again because the look content changes again
    // (mergePanelConf now also writes a `plugins=` key into the
    // [panel1] section of panel.conf: the panel loads plugins
    // ONLY from that list, so the [spacer] section added by P9FIX2
    // was never loaded. The list is the system default's
    // 12-plugin order with `spacer` appended, preserving the
    // current layout. The icons and the palette are NOT touched
    // here: the icons need a diagnostic first, the palette is
    // deferred).
    // P9FIX6: bumped again because the look content changes again.
    // Theme only: ALL 17 QSS image urls revert from absolute
    // guest paths to theme-relative paths (the spacer keeps its
    // original root-relative /spacer-plugin/... form, as stock
    // themes do). The spacer diagnosis proved the LXQt theme
    // loader prepends the theme directory to every QSS url(), so
    // the P9FIX absolute urls were resolved to a DOUBLED path
    // that does not exist and QPixmap returned null (session log:
    // "Could not create pixmap from .../mudif//usr/share/lxqt/
    // themes/mudif/spacer-plugin/spacer-line.png"). The P9FIX5
    // base #Spacer rule is KEPT. The palette is NOT touched:
    // deferred.
    // P11: bumped again because the look content changes again
    // (two more wallpapers are copied into
    // /usr/share/backgrounds/, the panel QSS becomes a purple
    // gradient, the [quicklaunch] section gains the 4 mockup
    // apps, and the Fancy Menu search + categories are
    // restyled). The palette is NOT touched: deferred. The
    // spacer is NOT touched: P9FIX6-verified.
    // P14: bumped again because the look content changes again
    // (default wallpaper becomes the pomegranate poster; the
    // panel plugins list becomes the reference layout without
    // showdesktop, desktopswitch, kbindicator, quicklaunch2,
    // spacer; the [mainmenu] section gains the Fancy Menu
    // favorites array). The palette is NOT touched: deferred.
    // P16: public so the version toast + diagnostic report always show
    // the CURRENT marker (never a stale hardcoded "P8" again).
    // P17: bumped again because the rootfs is now the pre-built
    // image, the look still runs once after extraction (it rebuilds
    // the mudif icon cache inside the guest), so a fresh rootfs must
    // receive it.
    // P21: WhiteSur-light icon theme + 48px panel + launcher
    // quicklaunch + 64px desktop icons, existing rootfs must
    // re-apply the look to pick them up.
    // P22: bumped again because the p21 APK bundled a STALE look
    // (2026-11-07 theme) and clobbered the p21 image's fixed look on
    // first boot, existing rootfs must re-apply the current look to
    // pick up the maximize rule, padding 4 and DesktopIconSize=64.
    // The p22 APK and the p22 image must ship from the same snapshot.
    // P23: adaptive scale (2026-11-14). The font, panel, icons,
    // terminal and Openbox sizes are computed from the device's real
    // screen (DesktopScale), so an existing rootfs must re-apply to
    // pick them up. The p23 APK and the p23 image must ship from the
    // same snapshot.
    // P24 (2026-11-15, CLI v2): the Arabic CLI 2.0 and programs.tsv
    // are staged by ensureLxqtLook, so an existing rootfs must
    // re-apply to pick them up. The image stays p23 (untouched);
    // only the look version moves, and the VNC version toast shows
    // it. This is the same re-apply mechanism as p22/p23.
    const val MARKER_VERSION = "p24"
    const val WALLPAPER_GUEST_PATH = "/usr/share/backgrounds/mudif-wallpaper.png"
    private const val WALLPAPER_ASSET = "mudif-wallpaper.png"
    private const val WALLPAPER_POMEGRANATE_ASSET = "mudif-wallpaper-pomegranate.png"
    private const val WALLPAPER_BOLD_ASSET = "mudif-wallpaper-bold.png"
    private const val CAIRO_TTF_ASSET = "fonts-cairo/Cairo[slnt,wght].ttf"
    private const val CAIRO_OFL_ASSET = "fonts-cairo/OFL.txt"
    private const val CLI_ASSET = "mudif-cli/arabic-cli.sh"
    private const val PROGRAMS_TSV_ASSET = "mudif-cli/programs.tsv"
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
        // p23: adaptive scale, computed once from this device's real
        // screen and threaded through every config writer below.
        val scale = DesktopScaleCalculator.compute(context)
        val rootfs = DebianSystem.debianRootfsDir(context)

        // Prereq: the apt set must have landed (startlxqt + the
        // bloom-dark icons). If ensureDebianDesktop failed, the look
        // must report false honestly instead of writing configs for
        // programs that are not there.
        if (!File(rootfs, "usr/bin/startlxqt").exists()) {
            Log.d(TAG, "startlxqt missing - Debian desktop set not installed")
            return false
        }
        if (!File(rootfs, "usr/share/icons/bloom-dark").exists()) {
            Log.d(TAG, "bloom-dark icons missing - deepin-icon-theme not installed")
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
            // P11: the two picked poster wallpapers are installed
            // next to the purple default so they are selectable in
            // the desktop wallpaper settings.
            // P14: the default is now the pomegranate poster (his
            // pick 2026-11-08); PCMANFM_SETTINGS_CONF Wallpaper
            // points at it. Purple and bold stay selectable.
            context.assets.open(WALLPAPER_POMEGRANATE_ASSET).use { input ->
                File(backgrounds, "mudif-wallpaper-pomegranate.png").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            context.assets.open(WALLPAPER_BOLD_ASSET).use { input ->
                File(backgrounds, "mudif-wallpaper-bold.png").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            val lxqtDir = File(rootfs, "root/.config/lxqt")
            lxqtDir.mkdirs()
            File(lxqtDir, "lxqt.conf").writeText(lxqtConf(scale))
            File(lxqtDir, "session.conf").writeText(SESSION_CONF)
            val pcmanfmDir = File(rootfs, "root/.config/pcmanfm-qt/lxqt")
            pcmanfmDir.mkdirs()
            File(pcmanfmDir, "settings.conf").writeText(pcmanfmSettingsConf(scale))
            // P8 - Arabic: Cairo font + fontconfig + Arabic CLI.
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
            // P8 - Arabic CLI: the catalog file, the qterminal colour
            // scheme, the qterminal.ini keys (merged, never overwritten),
            // and the .bashrc block (appended once, idempotent).
            val mudifConfDir = File(rootfs, "root/.config/mudif")
            mudifConfDir.mkdirs()
            context.assets.open(CLI_ASSET).use { input ->
                File(mudifConfDir, "arabic-cli.sh").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            // CLI v2: the Arabic-name to apt mapping (programs.tsv,
            // generated from store-catalog.json). The CLI resolves
            // ثبت/افتح through it; without the file the verbs fall
            // back to raw apt names / xdg-open and say so.
            context.assets.open(PROGRAMS_TSV_ASSET).use { input ->
                File(mudifConfDir, "programs.tsv").outputStream().use { out ->
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
            // P8.1 - Arabic translations: extract the translations.zip
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
            // P9 - the `mudif` theme: extract mudif-theme.zip into the
            // rootfs. Its entries are guest-relative paths, so each
            // lands at File(rootfs, entry.name): the LXQt theme to
            // usr/share/lxqt/themes/mudif/, the palette to
            // usr/share/lxqt/palettes/Mudif, the icons to
            // usr/share/icons/mudif/, the Openbox theme to
            // usr/share/themes/mudif/openbox-3/themerc, and rc.xml to
            // root/.config/openbox/rc.xml. lxqt.conf (below) switches
            // to theme=mudif + icon_theme=mudif; the Openbox theme
            // name is set inside rc.xml itself.
            // P9FIX6 - the QSS files inside the zip use THEME-
            // RELATIVE image urls (url(mainmenu.png),
            // url(/spacer-plugin/spacer-line.png), ...). P9FIX made
            // them ABSOLUTE (url(/usr/share/lxqt/themes/mudif/...)),
            // but the spacer diagnosis proved the LXQt theme loader
            // prepends the theme directory to every url(), so
            // absolute urls are resolved to a DOUBLED path that
            // does not exist and QPixmap returns null (the spacer
            // painted 0 px in P9FIX through P9FIX5). Stock LXQt
            // themes all use relative urls. The P9FIX5 base #Spacer
            // rule is kept.
            extractZipAsset(context, THEME_ZIP_ASSET, rootfs)
            // p23: adaptive scale for the window manager. The zip
            // ships the p21 base (title/menu fonts 11, OSD 9,
            // padding 4); the runtime rewrite replaces them with
            // the computed sizes so the title bars, menus and OSD
            // read big on this device. Only the two font sizes the
            // zip ships and the one padding key are touched.
            rewriteOpenboxScale(rootfs, scale)
            // P9FIX - build the icon-theme cache for `mudif`.
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
            // next launch retries - the icons are the whole point of
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
                Log.d(TAG, "icon cache build failed - look not applied")
                return false
            }
            // P9FIX2 - the panel's start-button logo + a spacer
            // plugin, via panel.conf (merged, never overwritten -
            // the panel rewrites panel.conf on exit, so overwriting
            // it would lose the user's plugin layout; the same
            // clobber race as pcmanfm-qt's settings.conf). P9FIX3
            // adds the `plugins=` key to [panel1] (the [spacer]
            // section alone is not loaded - the panel reads only
            // the `plugins=` list). P9FIX4 adds `iconTheme=mudif`
            // to [General] (the panel reads its icon theme from
            // there, not from lxqt.conf). The base is the existing
            // panel.conf if the panel already created one, else
            // the system default /etc/xdg/lxqt/panel.conf
            // (lxqt-panel is installed - startlxqt exists - so
            // the default must exist). The write happens here,
            // before the first desktop launch (the same ordering
            // as lxqt.conf / session.conf), so no running panel
            // can clobber it, and the panel reads it at startup
            // and preserves both additions when it rewrites
            // panel.conf on exit.
            val panelConf = File(lxqtDir, "panel.conf")
            val existingPanelConf = when {
                panelConf.exists() -> panelConf.readText()
                File(rootfs, "etc/xdg/lxqt/panel.conf").exists() ->
                    File(rootfs, "etc/xdg/lxqt/panel.conf").readText()
                else -> null
            }
            panelConf.writeText(mergePanelConf(existingPanelConf, scale))
            val qterminalIni = File(rootfs, "root/.config/qterminal.org/qterminal.ini")
            qterminalIni.parentFile?.mkdirs()
            val existingIni = if (qterminalIni.exists()) qterminalIni.readText() else null
            qterminalIni.writeText(mergeQterminalIni(existingIni, scale))
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
            // p22 - big macOS cursor: Xresources + the xrdb loader +
            // GTK settings (the env vars live in SESSION_CONF).
            // The desktop reads the X cursor, not the Qt/GTK one:
            // the Appearance size 38 showed only inside some apps
            // because the Xresources were never loaded for the
            // desktop. XCURSOR_SIZE=48 in the session env + the
            // xrdb autostart entry make it 48 everywhere.
            File(rootfs, "root/.Xresources").writeText(CURSOR_XRESOURCES)
            File(autostartDir, "xrdb-xresources.desktop").writeText(XRDB_AUTOSTART)
            val gtkDir = File(rootfs, "root/.config/gtk-3.0")
            gtkDir.mkdirs()
            val gtkSettings = File(gtkDir, "settings.ini")
            val existingGtk = if (gtkSettings.exists()) gtkSettings.readText() else null
            gtkSettings.writeText(mergeGtkCursorSettings(existingGtk, scale))
            // p22 - screensaver: the image ships
            // lxqt-xscreensaver-autostart (it tries to start a
            // screensaver that is NOT installed), and any lock
            // trigger then fails as "xdg-screensaver lock" with
            // the error dialog he saw. No lock is wanted (no
            // profile set), so the autostart is disabled the
            // same way powermanagement is.
            File(autostartDir, "lxqt-xscreensaver-autostart.desktop").writeText(XSCREENSAVER_HIDDEN)
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
    // P15: the palette lives HERE, in a [Palette] group (lxqt-qtplugin
    // builds it solely from lxqt.conf [Palette], proven live,
    // PALETTE-PROOF.md: Fusion + this group gives Window #232323 /
    // Highlight #593BFE on all ten roles). The `palette=Mudif` key and
    // the file /usr/share/lxqt/palettes/Mudif are DEAD (nothing reads
    // them); kept as harmless presets for lxqt-config-appearance.
    // P21: icon_theme=WhiteSur-light (baked into the p21 image;
    // the `mudif` icon theme stays installed as the last fallback
    // in WhiteSur-light's Inherits chain, for the penguin/purple
    // names only it provides). theme stays mudif.
    // P23: font=Cairo,17 (LXQt convention: lxqt-config-appearance
    // writes the shell font to lxqt.conf [General] as font=Family,pt;
    // the LXQt platform theme reads it). 17 is the Nova 9 adaptive
    // output (3.0 mm on the glass); a small phone gets 19, a tablet
    // 11. The image base below carries the Nova 9 value; a real
    // device writes its own via lxqtConf(scale). If the rig shows
    // the shell font did not change, this key is wrong and the fix
    // is to find the key the platform theme actually reads, not to
    // guess another one. RIG MUST CONFIRM.
    private const val LXQT_CONF = """[General]
__userfile__=true
icon_theme=WhiteSur-light
theme=mudif
palette=Mudif
cursor_theme=WhiteSur-cursors
cursor_size=48
font=Cairo,17

[Palette]
window_color=#232323
base_color=#282828
highlight_color=#593BFE
window_text_color=#e1e6e6
text_color=#b8b8b8
highlighted_text_color=#ebfdff
link_color=#8B7CF5
link_visited_color=#C9B8FF
tooltip_base_color=#232323
tooltip_text_color=#b8b8b8
"""

    // p23: runtime adaptive path. LXQT_CONF above is the IMAGE base
    // (Nova 9 outputs; extract-look.py reads it by regex into the
    // p23 image). On a real device ensureLxqtLook writes the same
    // text with this device's computed font, so a small phone and a
    // tablet get their own size from the same build. The require
    // fails the look loudly (caught by ensureLxqtLook, returns
    // false) if the base ever changes without this line, instead of
    // silently shipping the wrong size.
    private fun lxqtConf(scale: DesktopScale): String {
        val out = LXQT_CONF.replace("font=Cairo,17", "font=Cairo,${scale.fontPt}")
        require(out != LXQT_CONF) { "LXQT_CONF base font changed, update lxqtConf" }
        return out
    }

    // Openbox is LXQt's window manager. The Environment section is
    // what the session exports to every Qt/GTK app: Fusion is the Qt
    // widget style (P15: Adwaita-Dark was dropped because it provides
    // its own palette which MASKS the platform-theme palette, the
    // leave dialog stayed #353535 under it; Fusion respects the
    // [Palette] group in lxqt.conf, proven live PALETTE-PROOF.md.
    // Fusion is built-in, so no P6.6 "invalid style override" risk),
    // Adwaita:dark the dark GTK theme, and the lxqt platform theme is
    // what applies icon_theme at all.
    // P8: LANG ar_MA.UTF-8 + LANGUAGE=ar make the session Arabic
    // (the locale itself is generated by ensureDebianDesktop).
    // P8FIX2: SHELL=/bin/bash so qterminal runs bash (not /bin/sh)
    // and sources .bashrc (the Arabic prompt/banner/CLI).
    private const val SESSION_CONF = """[General]
__userfile__=true
window_manager=openbox

[Environment]
QT_STYLE_OVERRIDE=Fusion
GTK_THEME=Adwaita:dark
QT_QPA_PLATFORMTHEME=lxqt
LANG=ar_MA.UTF-8
LANGUAGE=ar
SHELL=/bin/bash
XCURSOR_THEME=WhiteSur-cursors
XCURSOR_SIZE=48
"""

    // pcmanfm-qt draws the desktop wallpaper. It reads Wallpaper /
    // WallpaperMode / BgColor from the [Desktop] group ONLY
    // (Settings::loadFile, beginGroup("Desktop")). Writing Wallpaper
    // under [General] is invisible to it: mode defaults to
    // WallpaperNone, updateWallpaper() returns early, and
    // paintBackground() fills the window solid BgColor (#000000) -
    // the 0% paint defect, proven live by PAINT-PROBE2-P65 (0.00% ->
    // 92.16% with the corrected config). WallpaperMode=stretch is
    // required alongside the path: without it the mode is none and
    // the desktop stays black. No BgColor key is needed (default
    // black is invisible under a covering wallpaper).
    // P21: DesktopIconSize=64 (pcmanfm-qt Settings.cpp: the
    // [Desktop] DesktopIconSize key, default 48). Bigger desktop
    // icons for the big-desktop look.
    // P23: DesktopIconSize=93 (Nova 9 adaptive output, 12.0 mm on
    // the glass); the image base carries it, a real device writes
    // its own via pcmanfmSettingsConf(scale).
    private const val PCMANFM_SETTINGS_CONF = """[Desktop]
Wallpaper=/usr/share/backgrounds/mudif-wallpaper-pomegranate.png
WallpaperMode=stretch
DesktopIconSize=93
"""

    // p23: runtime adaptive path (same pattern as lxqtConf above).
    private fun pcmanfmSettingsConf(scale: DesktopScale): String {
        val out = PCMANFM_SETTINGS_CONF.replace(
            "DesktopIconSize=93", "DesktopIconSize=${scale.iconPx}"
        )
        require(out != PCMANFM_SETTINGS_CONF) {
            "PCMANFM_SETTINGS_CONF base icon size changed, update pcmanfmSettingsConf"
        }
        return out
    }

    // P8 - fontconfig: Cairo is the preferred sans-serif and serif
    // font, and Arabic text prefers Cairo (Noto Sans Arabic is the
    // next fallback, installed from apt). Monospace is NOT aliased
    // in fontconfig (Cairo has no monospace variant), BUT the
    // terminal font is set to Cairo directly in mergeQterminalIni
    // (P9FIX5, his order: "التيرمينال خاصو خط كايرو"). QTermWidget
    // renders whatever font it is given; Cairo covers Arabic and
    // Latin. The monospace concern is only about code columns
    // lining up perfectly; for his Arabic commands Cairo renders
    // correctly. This is verified on the device (P9FIX5 re-test),
    // not assumed.
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

    // P8 - the .bashrc block that loads the Arabic CLI. Appended
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

    // P8 - merge the four qterminal keys into [General], preserving
    // every key already present (HideTabBarWithOneTab, version,
    // [MainWindow], [Shortcuts]) and any key the user changed.
    // If the file does not exist yet, a [General] section is created.
    // A key already present is replaced in place (one copy only).
    // P9FIX5 - fontFamily is "Cairo" (his order: "التيرمينال خاصو
    // خط كايرو"), not DejaVu Sans Mono. DejaVu Sans Mono has its
    // own Arabic glyphs, so the Arabic rendered in DejaVu, not
    // Cairo - that is why his screenshot showed no Cairo.
    private fun mergeQterminalIni(existing: String?, scale: DesktopScale): String {
        val keys = linkedMapOf(
            "colorScheme" to "Mudif",
            "fontFamily" to "Cairo",
            // p23: terminal font is the shell font + 2 (19 on the
            // Nova 9, 21 on a small phone, 13 on a tablet).
            "fontSize" to scale.terminalPt.toString(),
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

    // P9FIX2 - merge the panel's start-button icon + a spacer
    // plugin into panel.conf, preserving every plugin section
    // already present (the panel rewrites panel.conf on exit, so
    // overwriting it would lose the user's plugin layout).
    // The logo: LXQtFancyMenu::setButtonIcon() (lxqtfancymenu.cpp;
    // the same logic in LXQtMainMenu::setButtonIcon for the
    // standard menu) reads ownIcon + icon from the [mainmenu]
    // settings and sets the button's own stylesheet to
    // `#FancyMenu { qproperty-icon: url(<icon>); }`. When ownIcon
    // is false (the default - the P9FIX panel.conf [mainmenu] has
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
    // spaceType (lined/dotted/invisible, default lined).
    // P9FIX3 - the [spacer] section alone is NOT enough (the
    // P9FIX2 assumption "the panel loads plugins by scanning
    // sections with a type= key" was REFUTED by the P9FIX2
    // re-test: the section was present and the panel fresh, yet
    // no spacer rendered). LXQtPanel::loadPlugins()
    // (panel/lxqtpanel.cpp:1046-1088) loads plugins ONLY from
    // the `plugins=` list in [panel1]; the user panel.conf
    // [panel1] has NO `plugins=` key, so the panel falls back
    // to the system default's 12-plugin list (no spacer) and
    // the [spacer] section is never loaded. So the merge also
    // writes `plugins=` into [panel1]: the system default's
    // 12-plugin order with `spacer` appended, preserving the
    // current layout. The panel preserves a loaded
    // plugin when it rewrites panel.conf on exit, so the section
    // persists. expandable=true makes the spacer push the
    // right-aligned plugins to the right edge (the usual layout
    // purpose); size is ignored when expandable.
    // P9FIX4 - the panel reads its icon theme from panel.conf
    // [General] iconTheme, NOT from lxqt.conf icon_theme. The
    // user panel.conf [General] has no iconTheme key, so the
    // panel falls back to the system default's
    // iconTheme=Papirus-Dark (the icons diagnosis proved it from
    // the panel's mapped caches: Papirus-Dark + hicolor only).
    // So the merge also writes `iconTheme=mudif` into [General].
    // A key already present is replaced in place (one copy only);
    // a missing key is appended at the end of its section. A
    // re-merge of an already-merged file is byte-identical.
    // P14 - the reference panel layout (his order 2026-11-08).
    // The plugins= line is now REPLACED wholesale with the
    // reference list (mainmenu, quicklaunch, taskbar, tray,
    // statusnotifier, mount, volume, worldclock). The P9FIX3
    // keep-as-is/append-spacer logic is gone: it would keep the
    // removed plugins (showdesktop, desktopswitch, kbindicator,
    // quicklaunch2, spacer). The [spacer] section is no longer
    // written (a leftover one in an existing file is harmless:
    // the panel loads plugins ONLY from plugins=). The [mainmenu]
    // section also gains the Fancy Menu favorites array (see
    // mainmenuKeys). The merge still runs once per marker
    // version, so it does not fight later user edits.
    private fun mergePanelConf(existing: String?, scale: DesktopScale): String {
        // P14: Fancy Menu favorites. The menu reads them via
        // readArray("favorites") from its own [mainmenu] section:
        // favorites\N\desktopFile + favorites\size=N (the same
        // QSettings ARRAY shape as the QuickLaunch apps, proven
        // by a real user panel.conf: [fancymenu2] with
        // favorites\1\desktopFile=... + favorites\size=4,
        // type=fancymenu). The 4 real desktop apps (the same 4 as
        // QuickLaunch). The store and the calendar are NOT
        // desktop apps, so they cannot be favorites; no fake
        // .desktop entries are created.
        val mainmenuKeys = linkedMapOf(
            "icon" to "/usr/share/lxqt/themes/mudif/mainmenu.png",
            "ownIcon" to "true",
            "favorites\\1\\desktopFile" to "/usr/share/applications/pcmanfm-qt.desktop",
            "favorites\\2\\desktopFile" to "/usr/share/applications/qterminal.desktop",
            "favorites\\3\\desktopFile" to "/usr/share/applications/firefox-esr.desktop",
            "favorites\\4\\desktopFile" to "/usr/share/applications/lxqt-config.desktop",
            "favorites\\size" to "4"
        )
        // P9FIX4 - the panel reads its icon theme from panel.conf
        // [General] iconTheme, NOT from lxqt.conf icon_theme. The
        // user panel.conf [General] has no iconTheme key, so the
        // panel falls back to the system default's
        // iconTheme=Papirus-Dark (its mapped caches are
        // Papirus-Dark + hicolor only, no mudif). Writing
        // iconTheme=WhiteSur-light here makes the panel use it.
        // P21: mudif -> WhiteSur-light (baked into the p21 image).
        val generalKeys = linkedMapOf("iconTheme" to "WhiteSur-light")
        // P11: the QuickLaunch plugin shows the 4 mockup apps
        // (file manager, terminal, browser, settings). The 4
        // .desktop names are verified against the Debian Contents
        // index (store/work-debian/Contents-main.gz).
        // P13: the plugin reads apps via readArray("apps"), i.e.
        // QSettings ARRAY keys apps\N\desktop + apps\size=N (the
        // system /etc/xdg/lxqt/panel.conf uses exactly that). P11
        // (@StringList) and P12 (comma-separated list) both wrote
        // a single apps= LIST key, which readArray never reads, so
        // the plugin fell back to the system defaults. The merge
        // now writes the array keys and drops any stale apps= key.
        val quicklaunchKeys = linkedMapOf(
            "apps\\1\\desktop" to "/usr/share/applications/pcmanfm-qt.desktop",
            "apps\\2\\desktop" to "/usr/share/applications/qterminal.desktop",
            "apps\\3\\desktop" to "/usr/share/applications/firefox-esr.desktop",
            "apps\\4\\desktop" to "/usr/share/applications/lxqt-config.desktop",
            // P21: one-tap launcher (mudif-launcher.desktop,
            // Icon=system-run). Autostart stays OFF (P20 rule).
            "apps\\5\\desktop" to "/usr/share/applications/mudif-launcher.desktop",
            "apps\\size" to "5"
        )
        // P21: explicit panel height. lxqt-panel reads panelSize
        // from [panel1] (default 32 when absent).
        // P23: adaptive (8.0 mm on the glass): 62px on the Nova 9,
        // 67 on a small phone, 48 on a tablet (the clamp floor).
        val panel1Keys = linkedMapOf("panelSize" to scale.panelPx.toString())
        // P14: the reference panel layout (mockups, mirrored for
        // RTL). Removed from the P13 list: showdesktop,
        // desktopswitch (the 4 3 2 1 pager), kbindicator (US S N C),
        // quicklaunch2 (dead, renders nothing), spacer (the lone
        // line in empty space). The taskbar is expected to expand
        // and push the clock group to the far end; if the re-test
        // shows the clock still floating in the middle, P15 adds
        // back an invisible spacer. A leftover [spacer] section in
        // an existing panel.conf is harmless (the panel loads
        // plugins ONLY from this list, so it is never loaded).
        val pluginsList = "mainmenu, quicklaunch, taskbar, tray, statusnotifier, mount, volume, worldclock"
        val out = StringBuilder()
        val seenMainmenuKeys = mutableSetOf<String>()
        var inMainmenu = false
        var mainmenuSeen = false
        var inGeneral = false
        var generalSeen = false
        val seenGeneralKeys = mutableSetOf<String>()
        var inPanel1 = false
        var panel1Seen = false
        var pluginsSeen = false
        val seenPanel1Keys = mutableSetOf<String>()
        val seenQuicklaunchKeys = mutableSetOf<String>()
        var inQuicklaunch = false
        var quicklaunchSeen = false
        fun appendMissingMainmenuKeys() {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            for ((k, v) in mainmenuKeys) if (k !in seenMainmenuKeys) out.append("$k=$v\n")
        }
        fun appendMissingGeneralKeys() {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            for ((k, v) in generalKeys) if (k !in seenGeneralKeys) out.append("$k=$v\n")
        }
        fun appendMissingPanel1Plugins() {
            if (!pluginsSeen) {
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
                out.append("plugins=$pluginsList\n")
                pluginsSeen = true
            }
        }
        // P21: panelSize (and any future [panel1] key) is merged
        // the same way as the [General] keys: replaced in place
        // when present, appended when missing, one copy only.
        fun appendMissingPanel1Keys() {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            for ((k, v) in panel1Keys) if (k !in seenPanel1Keys) out.append("$k=$v\n")
        }
        fun appendMissingQuicklaunchKeys() {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            for ((k, v) in quicklaunchKeys) if (k !in seenQuicklaunchKeys) out.append("$k=$v\n")
        }
        // trimEnd on the input drops the trailing empty lines the
        // file's final newline produces, so no blank line lands
        // inside [mainmenu] when it is the last section.
        for (line in (existing?.trimEnd()?.lines() ?: emptyList())) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (inMainmenu) appendMissingMainmenuKeys()
                if (inGeneral) appendMissingGeneralKeys()
                if (inPanel1) {
                    appendMissingPanel1Plugins()
                    appendMissingPanel1Keys()
                }
                if (inQuicklaunch) appendMissingQuicklaunchKeys()
                inMainmenu = trimmed == "[mainmenu]"
                if (inMainmenu) mainmenuSeen = true
                inGeneral = trimmed == "[General]"
                if (inGeneral) generalSeen = true
                inPanel1 = trimmed == "[panel1]"
                if (inPanel1) panel1Seen = true
                inQuicklaunch = trimmed == "[quicklaunch]"
                if (inQuicklaunch) quicklaunchSeen = true
                out.append(line).append("\n")
                continue
            }
            if (inGeneral) {
                val key = trimmed.substringBefore("=").trim()
                if (key in generalKeys) {
                    if (key !in seenGeneralKeys) {
                        out.append("$key=${generalKeys[key]}\n")
                        seenGeneralKeys.add(key)
                    }
                    continue
                }
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
            if (inQuicklaunch) {
                val key = trimmed.substringBefore("=").trim()
                if (key == "apps") {
                    // P13: drop the stale single apps= LIST key
                    // (P11/P12). The plugin reads an ARRAY
                    // (apps\N\desktop + apps\size), never this key.
                    continue
                }
                if (key in quicklaunchKeys) {
                    if (key !in seenQuicklaunchKeys) {
                        out.append("$key=${quicklaunchKeys[key]}\n")
                        seenQuicklaunchKeys.add(key)
                    }
                    continue
                }
            }
            if (inPanel1) {
                val key = trimmed.substringBefore("=").trim()
                if (key == "plugins") {
                    // Duplicate plugins= key: drop it (one was
                    // already written above).
                    if (pluginsSeen) continue
                    // P14: replace the whole list with the
                    // reference layout. The old list carries the
                    // removed plugins (showdesktop, desktopswitch,
                    // kbindicator, quicklaunch2, spacer), so
                    // keeping it as-is would keep them. The merge
                    // runs once per marker version, so this does
                    // not fight later user edits.
                    out.append("plugins=$pluginsList\n")
                    pluginsSeen = true
                    continue
                }
                if (key in panel1Keys) {
                    if (key !in seenPanel1Keys) {
                        out.append("$key=${panel1Keys[key]}\n")
                        seenPanel1Keys.add(key)
                    }
                    continue
                }
            }
            out.append(line).append("\n")
        }
        if (inMainmenu) appendMissingMainmenuKeys()
        if (inGeneral) appendMissingGeneralKeys()
        if (inPanel1) {
            appendMissingPanel1Plugins()
            appendMissingPanel1Keys()
        }
        if (inQuicklaunch) appendMissingQuicklaunchKeys()
        if (!generalSeen) {
            // Defensive only: [General] is in both the system
            // default and the user panel.conf, so this should
            // never trigger. A minimal [General] with just the
            // icon theme is better than the panel falling back
            // to Papirus-Dark.
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            out.append("[General]\n")
            for ((k, v) in generalKeys) out.append("$k=$v\n")
        }
        if (!mainmenuSeen) {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            out.append("[mainmenu]\n")
            out.append("alignment=Left\n")
            out.append("type=fancymenu\n")
            for ((k, v) in mainmenuKeys) out.append("$k=$v\n")
        }
        if (!panel1Seen) {
            // Defensive only: [panel1] is in both the system
            // default and the user panel.conf, so this should
            // never trigger. A minimal [panel1] with just the
            // plugins list is better than no plugins list at all.
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            out.append("[panel1]\n")
            out.append("plugins=$pluginsList\n")
            for ((k, v) in panel1Keys) out.append("$k=$v\n")
        }
        if (!quicklaunchSeen) {
            // Defensive only: [quicklaunch] is in both the system
            // default and the user panel.conf, so this should
            // never trigger. A minimal [quicklaunch] with just
            // the apps array is better than an empty quicklaunch.
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            out.append("[quicklaunch]\n")
            out.append("alignment=Left\n")
            out.append("type=quicklaunch\n")
            for ((k, v) in quicklaunchKeys) out.append("$k=$v\n")
        }
        // Normalize the ending: exactly one trailing newline, so a
        // re-merge of an already-merged file is byte-identical.
        return out.toString().trimEnd() + "\n"
    }

    // p22 - big macOS cursor (his order: the desktop cursor is too
    // small; the Appearance size 38 shows only inside some apps
    // because the desktop reads the X cursor, not the Qt/GTK one).
    // The theme files ride in mudif-theme.zip
    // (usr/share/icons/WhiteSur-cursors/, 111 cursors + index.theme,
    // macOS black arrow, vinceliuice/WhiteSur-cursors, MIT).
    private const val CURSOR_XRESOURCES = """Xcursor.theme: WhiteSur-cursors
Xcursor.size: 48
"""

    private const val XRDB_AUTOSTART = """[Desktop Entry]
Type=Application
Name=xrdb-xresources
Exec=xrdb -merge /root/.Xresources
"""

    // p22 - screensaver: the image ships lxqt-xscreensaver-autostart
    // (it tries to start a screensaver that is NOT installed), and
    // any lock trigger then fails as "xdg-screensaver lock" with the
    // error dialog he saw. No lock is wanted (no profile set), so
    // the autostart is disabled the same way powermanagement is.
    private const val XSCREENSAVER_HIDDEN = """[Desktop Entry]
Type=Application
Name=lxqt-xscreensaver-autostart
Hidden=true
"""

    // p23: rewrite the two Openbox font sizes and the window padding
    // in the EXTRACTED files (the zip ships the p21 base: title/menu
    // fonts 11, OSD 9, padding 4). Only exact <size>11</size> and
    // <size>9</size> font blocks are touched: the <size> in the
    // moveresize block has no number on its line, so it is never
    // matched, and window.client.padding.width is left alone.
    // A missing file is silently skipped (a missing rc.xml is the
    // theme's problem, not this rewrite's). OSD is fontPt - 2 (15 on
    // the Nova 9): the OSD is a transient label, it stays one step
    // smaller than the window text.
    private fun rewriteOpenboxScale(rootfs: File, scale: DesktopScale) {
        val rc = File(rootfs, "root/.config/openbox/rc.xml")
        if (rc.exists()) {
            var text = rc.readText()
            text = text.replace("<size>11</size>", "<size>${scale.fontPt}</size>")
            text = text.replace("<size>9</size>", "<size>${scale.fontPt - 2}</size>")
            rc.writeText(text)
        }
        val themerc = File(rootfs, "usr/share/themes/mudif/openbox-3/themerc")
        if (themerc.exists()) {
            var text = themerc.readText()
            text = text.replace(
                "padding.width: 4", "padding.width: ${scale.openboxPadding}"
            )
            themerc.writeText(text)
        }
    }

    /** Merge the two cursor keys and the GTK font key into
     *  gtk-3.0/settings.ini, preserving every key already present.
     *  If the file does not exist, a [Settings] section is created.
     *  A key already present is replaced in place (one copy only).
     *  P23: gtk-font-name=Cairo <fontPt> (GTK reads it directly, so
     *  GTK apps get the adaptive font even if the LXQt shell font
     *  key turns out wrong). The cursor keys are NOT the pointer
     *  size: they are the GTK cursor theme, left at 48. */
    private fun mergeGtkCursorSettings(existing: String?, scale: DesktopScale): String {
        val out = StringBuilder()
        var inSettings = false
        var settingsSeen = false
        var nameSeen = false
        var sizeSeen = false
        var fontSeen = false
        fun flushMissing() {
            if (!nameSeen) out.append("gtk-cursor-theme-name=WhiteSur-cursors\n")
            if (!sizeSeen) out.append("gtk-cursor-theme-size=48\n")
            if (!fontSeen) out.append("gtk-font-name=Cairo ${scale.fontPt}\n")
            nameSeen = true
            sizeSeen = true
            fontSeen = true
        }
        for (line in (existing?.lines() ?: emptyList())) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (inSettings) flushMissing()
                inSettings = trimmed == "[Settings]"
                if (inSettings) settingsSeen = true
                out.append(line).append("\n")
                continue
            }
            if (inSettings && trimmed.startsWith("gtk-cursor-theme-name=")) {
                out.append("gtk-cursor-theme-name=WhiteSur-cursors\n")
                nameSeen = true
                continue
            }
            if (inSettings && trimmed.startsWith("gtk-cursor-theme-size=")) {
                out.append("gtk-cursor-theme-size=48\n")
                sizeSeen = true
                continue
            }
            if (inSettings && trimmed.startsWith("gtk-font-name=")) {
                out.append("gtk-font-name=Cairo ${scale.fontPt}\n")
                fontSeen = true
                continue
            }
            out.append(line).append("\n")
        }
        if (inSettings) flushMissing()
        if (!settingsSeen) {
            out.append("[Settings]\n")
            flushMissing()
        }
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
