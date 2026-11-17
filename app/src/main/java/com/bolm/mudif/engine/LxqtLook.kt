package com.bolm.mudif.engine

import android.content.Context
import com.bolm.mudif.ui.settings.AppTheme
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
 * p86: the default wallpaper becomes the Deepin-style gradient
 * with the clay penguin watermark (desktop polish spec section 1,
 * approved 2026-11-17). Pomegranate + bold + purple stay staged
 * and selectable in the desktop wallpaper settings. Zorin's
 * mountain photo is the spec's alternative, but no clean source
 * exists in the workspace, so it is NOT bundled in p86 (owed).
 * p87: the launcher becomes the frosted light panel (desktop
 * polish spec section 2). The QSS restyle (lxqt-panel.qss in
 * mudif-theme.zip) is solid #F7F5FF + dark text #333333, NOT
 * translucent: no compositor runs in the guest and the FancyMenu
 * window sets no WA_TranslucentBackground, so an rgba white would
 * blend against the dark palette window color and render gray.
 * The category rail moves to the RIGHT in RTL via
 * categoriesAtRight=false in [mainmenu] (the code default is
 * true, and QHBoxLayout mirrors it to the visual left in RTL -
 * lxqt-panel 2.1.4 plugin-fancymenu/lxqtfancymenuwindow.cpp
 * setCategoryPosition). The menu width stays the 450px code
 * minimum (minimumSizeHint); the spec's ~25% width is not
 * achievable via QSS and forcing it narrower would clip the long
 * Arabic category labels. The gear/power buttons keep their
 * current behavior (gear -> lxqt-config, power -> lxqt-leave):
 * no Mudif settings program exists in the guest, so the spec's
 * "gear opens Mudif settings" is a new feature, not a restyle.
 * p88: windows + file manager become the Zorin white windows
 * (desktop polish spec section 3). pcmanfm-qt 2.1.0 loads NO
 * stylesheet (proven from its source: application.cpp,
 * settings.cpp and mainwindow.cpp contain no setStyleSheet
 * except the hardcoded red root warning label; the lxqt
 * platform theme plugin loads no stylesheet either), so the
 * ONLY mechanism that styles its window, sidebar, header bar
 * and status bar is the [Palette] group in lxqt.conf, which
 * the platform theme builds into the QApplication palette
 * (lxqt-qtplugin lxqtplatformtheme.cpp). The palette flips to
 * LIGHT: Window #FFFFFF, Base #FFFFFF, Text/WindowText
 * #333333, Highlight #593BFE unchanged, HighlightedText
 * #FFFFFF, Link #593BFE, LinkVisited #6A5AE0, ToolTip
 * #FFFFFF/#333333. The openbox themerc flips with it: white
 * flat title bars (active #FFFFFF, inactive #F5F5F5), dark
 * title text #333333, dark button glyphs #555555, light
 * borders #D9D5E8/#E5E5E5. The panel KEEPS its dark-purple
 * look: its 60%-alpha gradient blends against BLACK, not the
 * palette (proven by the P9 measurement: the 55% gradient
 * rendered #180d42, which is exactly 55% of (43,24,120) over
 * black; blending against the old palette #232323 would have
 * given #271D52). The render harness re-proves it for p88.
 * Honest gaps (no mechanism exists in this build): the sidebar
 * is white, not the spec's #f5f5f5 (its background is the Base
 * role, shared with the content view; the PlacesView uses no
 * alternating rows, proven from libfm-qt placesview.cpp); the
 * selected row is the purple Highlight #593BFE, not the spec's
 * dark-gray pill (the pill shape needs a stylesheet, which
 * pcmanfm-qt cannot load); true rounded window corners ~16px
 * and the drop shadow need a compositor (picom is ideas item
 * 3, NOT built). In RTL the sidebar sits on the right by Qt
 * splitter mirroring (main-win.ui: the horizontal QSplitter's
 * first child is the SidePane; QSplitter lays out in layout
 * direction order). The two QMenu::item:selected:disabled
 * rules that read palette(text) are set to an explicit
 * #D9D5E8: with the light palette the old rule would paint a
 * #333333 background behind disabled text.
 * p89: dialogs become the Zorin white dialogs (desktop polish
 * spec section 4, RTL mirror of the reference dialog in
 * image-414.png). The mechanism, proven before writing: Qt
 * dialogs (pcmanfm-qt, lxqt) already match by the p88 light
 * palette + white openbox title bars, proven by an offscreen
 * PyQt6 QMessageBox render (white card, dark text, yellow
 * warning triangle on the RIGHT in RTL, bold title, light-gray
 * buttons); no Qt change is made. GTK dialogs (Mousepad,
 * Geany, file chooser) follow the GTK theme, and GTK reads
 * $XDG_CONFIG_HOME/gtk-3.0/gtk.css at init BEFORE the theme
 * CSS, so a staged gtk.css beats Adwaita by higher
 * specificity: dialog background #FFFFFF, dialog buttons flat
 * light gray #F2F2F2 with no gradient and the suggested-action
 * neutralized to the same gray, links #593BFE, and the
 * symbolic dialog-warning icon recolored orange #F67F53
 * (measured from the reference triangle). The staged file
 * ships as a NEW entry root/.config/gtk-3.0/gtk.css in
 * mudif-theme.zip (153 -> 154 entries); the zip is extracted
 * by extractZipAsset during ensureLxqtLook, so no new Kotlin
 * staging code is needed. GTK apps are GTK3 only (no libgtk-4
 * in the guest image), so no gtk-4.0/gtk.css is shipped.
 * Honest gaps (no mechanism exists in this build): true
 * rounded ~12px outer corners and the soft drop shadow need
 * a compositor (picom is ideas item 3, NOT built); GTK dialog
 * buttons expand full-width in the action area (GTK's own
 * layout, not themeable via CSS), not a compact group on one
 * end; the Qt default button carries Fusion's faint lavender
 * tint + purple border (no mechanism for pcmanfm-qt, which
 * loads no stylesheet).
 * p90: the bottom panel (desktop polish spec section 5). The
 * headline is the launcher-button icon: after p87 the button
 * still showed the theme's default start icon (blue circle,
 * white bird/swirl), not the Mudif penguin (Anas's report,
 * 2026-11-17, screenshot of the launcher open on his phone).
 * Mechanism, proven before writing: the staged file IS already
 * the penguin (usr/share/lxqt/themes/mudif/mainmenu.png in the
 * theme zip, 294x299, viewed), and the merge writes
 * icon=<that path> + ownIcon=true into [mainmenu] - but the
 * panel REWRITES panel.conf on exit and DROPS ownIcon (proven
 * live: the p87 and p88 rig panel.conf dumps both have icon=
 * present and ownIcon ABSENT after the panel runs). With
 * ownIcon gone, LXQtFancyMenu::setButtonIcon()
 * (lxqt-panel 2.1.x, plugin-fancymenu/lxqtfancymenu.cpp: the
 * else branch sets an empty widget stylesheet) the button falls
 * back to the icon theme's start icon. The fix is an assertion
 * of the two [mainmenu] icon keys on EVERY desktop start,
 * before the marker early-return, so whatever the panel dropped
 * on exit is undone by the next start and the exact
 * fresh-staging state (penguin, proven on the rig) is restored.
 * The same build adds the rest of section 5: two lined
 * separators (spacer after quicklaunch, spacer2 before
 * worldclock; keys size/expandable/spaceType from the upstream
 * spacer source, values equal to the defaults; the line is
 * painted by the base #Spacer rule, P9FIX5), and the stacked
 * clock (worldclock showDate=true + datePosition=below; the
 * system default carries no date keys, so the clock shows time
 * only today). The panel bar needs no change: it already
 * renders the spec gradient rgba(89,59,254,60%) to
 * rgba(43,24,120,60%) (pixel-sampled #27186E on the rig).
 * NOT done in this build: the centered app group (spec section
 * 9 is still OPEN: pinned favorites vs live task buttons, no
 * decision given, so the P14 plugin order is not redesigned);
 * true frosted translucency (needs a compositor, picom is
 * ideas item 3, NOT built); a panel restart WITHOUT a desktop
 * start still shows the swirl until the next full start.
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
    // P25 (2026-11-15, p44): p43 shipped a new arabic-cli.sh but
    // kept MARKER_VERSION at p24, so the new CLI never staged on
    // an existing rootfs. Bump to p25 so the p44 configs re-apply.
    // P26 (2026-11-15, p51): p51 ships a new arabic-cli.sh (token
    // helpers, confirmation on the multi-arg ثبت, ازل, رقي, نظف),
    // so an existing rootfs must re-apply to pick it up. The image
    // stays p23; only the look version moves. Without this bump the
    // CLI change repeats the exact p43 trap.
    // P27 (2026-11-16, p59): p59 stages the lxqt-about shim (the
    // FancyMenu About button hardcodes the `lxqt-about` command;
    // the shim redirects it to Mudif's own About page) plus the
    // updated mudif-about.py (app version 0.1.0 + owner), and the
    // patched fancymenu_ar.qm inside translations.zip (the app's
    // zip overwrites the image's patched copy on every update, so
    // the label fix must live in the zip itself).
    // P28 (2026-11-16, p63): p63 adds [System] Terminal=qterminal
    // to the staged pcmanfm settings.conf (the xterm error dialog
    // in image-408: every Open Terminal tap failed because the
    // staged file never set Terminal and pcmanfm-qt fell back to
    // its hardcoded "xterm"). An existing rootfs must re-apply to
    // pick it up; without this bump the fix repeats the p43 trap.
    // P29 (2026-11-16, p68): p68 stages the download library
    // (mudif-dl.sh) so an existing phone picks it up on this
    // update; without the bump the store would keep the old
    // single-stream curl download.
    // P30 (2026-11-16, p72): p72 adds -y to the apt calls in
    // ازل (apt remove), رقي (apt full-upgrade) and نظف (apt
    // autoremove) in the staged arabic-cli.sh. The p51 rig round
    // proved ازل aborts at apt's own Y/n prompt under the agent
    // (stdin EOF); رقي and نظف carry the same gap in source.
    // An existing rootfs must re-apply to pick it up; without
    // this bump the fix repeats the p43 trap.
    // P35 (2026-11-17, p89): p89 adds the staged GTK dialog
    // stylesheet (root/.config/gtk-3.0/gtk.css, Zorin white
    // dialogs, spec section 4), so an existing rootfs must
    // re-apply to pick it up. The image stays p23; only the
    // look version moves. Without this bump the fix repeats the
    // p43 trap.
    // P38 (2026-11-17, p92): p92 fixes the rotator state divergence
    // (F2): the rotate script now verifies settings.conf before
    // writing wallpaper-index, so an existing rootfs must re-apply
    // to pick it up. The image stays p23; only the look version
    // moves. Without this bump the change repeats the p43 trap.
    // P39 (2026-11-17, p95): p95 stages a new panel.conf (both
    // spacers expandable so the taskbar centers; [General]
    // iconSize=48 so the mainmenu penguin grows), a new
    // lxqt-panel.qss (the mainmenu button: transparent background,
    // 8px side padding, ~67px wide), a new mudif-launcher icon +
    // a corrected mudif-launcher.desktop (Icon=mudif-launcher),
    // and the fixed rotator script (fd 9 closed before sleep,
    // apply retries instead of exiting). An existing rootfs must
    // re-apply to pick them up. The image stays p23; only the
    // look version moves. Without this bump the change repeats
    // the p43 trap.
    // P40 (2026-11-17, p96): p96 corrects the p95 panel fixes
    // the rig refuted: [panel1] iconSize=48 (p95 wrote it into
    // [General], which the panel never reads, so the penguin
    // stayed 22px); both spacers back to fixed (the expandable
    // centering hypothesis is falsified by the upstream layout
    // code: an expandable spacer's sizeHint is 1px, the taskbar
    // absorbs the leftover and pins its button to its start);
    // and the ~/Desktop launcher copy is re-staged with
    // Icon=mudif-launcher (p95 only rewrote the applications
    // copy, so the desktop icon stayed the gear). An existing
    // rootfs must re-apply to pick them up. The image stays p23;
    // only the look version moves. Without this bump the change
    // repeats the p43 trap.
    // P41 (2026-11-17, p98): the theme system. The look stages the
    // active theme's palette, panel QSS, gtk.css and themerc, pins
    // the theme's wallpaper and pauses the rotator. An existing
    // rootfs must re-apply to pick them up. The image stays p23;
    // only the look version moves. Without this bump the change
    // repeats the p43 trap. The marker is now "p41:<theme>": a
    // theme switch makes isApplied() false, so the next desktop
    // start re-stages the new theme instead of keeping the old one.
    const val MARKER_VERSION = "p41"
    const val WALLPAPER_GUEST_PATH = "/usr/share/backgrounds/mudif-wallpaper.png"
    private const val WALLPAPER_ASSET = "mudif-wallpaper.png"
    private const val WALLPAPER_POMEGRANATE_ASSET = "mudif-wallpaper-pomegranate.png"
    private const val WALLPAPER_BOLD_ASSET = "mudif-wallpaper-bold.png"
    private const val WALLPAPER_GRADIENT_ASSET = "mudif-wallpaper-gradient.png"
    private const val WALLPAPER_PREMIUM_ASSET = "mudif-wallpaper-premium.png"
    private const val WALLPAPER_GREEN_ASSET = "mudif-wallpaper-green.png"
    private const val CAIRO_TTF_ASSET = "fonts-cairo/Cairo[slnt,wght].ttf"
    private const val CAIRO_OFL_ASSET = "fonts-cairo/OFL.txt"
    private const val CLI_ASSET = "mudif-cli/arabic-cli.sh"
    private const val DL_ASSET = "mudif-cli/mudif-dl.sh"
    private const val PROGRAMS_TSV_ASSET = "mudif-cli/programs.tsv"
    private const val ABOUT_SHIM_ASSET = "mudif-cli/lxqt-about"
    private const val ABOUT_PY_ASSET = "mudif-cli/mudif-about.py"
    private const val ROTATE_SH_ASSET = "mudif-cli/mudif-wallpaper-rotate.sh"
    private const val COLORSCHEME_ASSET = "mudif-cli/Mudif.colorscheme"
    private const val TRANSLATIONS_ZIP_ASSET = "translations.zip"
    private const val THEME_ZIP_ASSET = "mudif-theme.zip"

    fun isApplied(context: Context): Boolean {
        val marker = File(DebianSystem.debianRootfsDir(context), "root/$MARKER")
        if (!marker.exists()) return false
        return try {
            // p98: the marker carries the theme ("p41:<theme>"), so
            // a theme switch invalidates it and the next desktop
            // start re-stages the new theme.
            marker.readText().trim() == "$MARKER_VERSION:${AppTheme.currentTag(context)}"
        } catch (e: Exception) {
            false
        }
    }

    /** Stage the guest side of a theme switch, called from
     *  AppTheme.apply before the app relaunches. Writes the theme
     *  file, the wallpaper index and the rotator pin into
     *  root/.config/mudif (best effort: a missing rootfs never
     *  crashes the switch), then flips the live wallpaper when the
     *  guest is up. The full re-skin (palette, QSS, gtk.css,
     *  themerc) happens on the next full desktop start, when
     *  isApplied() is false and ensureLxqtLook re-stages. */
    fun stageThemeSwitch(context: Context, tag: String) {
        try {
            val mudifConfDir = File(DebianSystem.debianRootfsDir(context), "root/.config/mudif")
            mudifConfDir.mkdirs()
            File(mudifConfDir, "theme").writeText("$tag\n")
            File(mudifConfDir, "wallpaper-index").writeText("${wallpaperIndexFor(tag)}\n")
            File(mudifConfDir, "wallpaper-pinned").writeText("$tag\n")
        } catch (e: Exception) {
            MudifLog.d(TAG, "theme switch guest write failed: ${e.message}")
        }
        try {
            if (GuestSession.alive && GuestSession.booted) {
                GuestSession.sendCommand("pcmanfm-qt --set-wallpaper ${wallpaperPathFor(tag)}")
            }
        } catch (e: Exception) {
            MudifLog.d(TAG, "theme switch live wallpaper failed: ${e.message}")
        }
    }

    /** Rotator state index for a theme: 0 = premium Badeen
     *  wallpaper (dark and gradient themes), 1 = green storybook
     *  (light theme). The pin file holds the theme id; while it
     *  exists the rotator no-ops (see the pin check in
     *  mudif-wallpaper-rotate.sh). */
    private fun wallpaperIndexFor(tag: String): Int =
        if (tag == AppTheme.TAG_LIGHT) 1 else 0

    /** Desktop wallpaper path for a theme. */
    private fun wallpaperPathFor(tag: String): String =
        if (tag == AppTheme.TAG_LIGHT) "/usr/share/backgrounds/mudif-wallpaper-green.png"
        else "/usr/share/backgrounds/mudif-wallpaper-premium.png"

    // p90 (polish spec section 5, headline fix): the panel DROPS
    // ownIcon from [mainmenu] when it rewrites panel.conf on exit.
    // Proven live: the p87 and p88 rig panel.conf dumps both show
    // icon=/usr/share/lxqt/themes/mudif/mainmenu.png PRESENT but
    // ownIcon=true ABSENT after the panel runs. With ownIcon gone,
    // LXQtFancyMenu::setButtonIcon() (lxqt-panel 2.1.x,
    // plugin-fancymenu/lxqtfancymenu.cpp) takes its else branch and
    // clears the button stylesheet, so the button falls back to the
    // icon theme's start icon (the blue swirl) instead of the Mudif
    // penguin. The marker-gated merge below writes ownIcon=true
    // only once per marker, so it never repairs the drop. This
    // assertion runs on EVERY desktop start, BEFORE the marker
    // early-return in ensureLxqtLook: the panel reads panel.conf
    // at startup, so whatever it dropped on exit is undone by the
    // next start, restoring the exact fresh-staging state that
    // shows the penguin (proven on the rig: the p89-verify
    // first-start screenshot shows the penguin; Anas's phone
    // screenshot after panel restarts shows the swirl). Only these
    // two keys are asserted; favorites, categoriesAtRight, plugins
    // stay once-per-marker, so user edits to them are not fought.
    // No-op when panel.conf does not exist yet (the gated staging
    // creates it) or when both keys are already correct (the file
    // is not rewritten, so no churn). Every other byte of the file
    // is preserved exactly. If a duplicate key exists, the last
    // copy is the one fixed (QSettings reads the last value).
    // Best-effort: a failure here must never block the desktop
    // start.
    private fun assertPanelMenuIcon(context: Context) {
        try {
            val panelConf = File(
                DebianSystem.debianRootfsDir(context),
                "root/.config/lxqt/panel.conf"
            )
            if (!panelConf.exists()) return
            val lines = panelConf.readText().lines().toMutableList()
            var inMainmenu = false
            var mainmenuSeen = false
            var iconLine = -1
            var ownIconLine = -1
            var mainmenuEnd = -1
            for ((i, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    if (inMainmenu) {
                        mainmenuEnd = i
                        break
                    }
                    inMainmenu = trimmed == "[mainmenu]"
                    if (inMainmenu) mainmenuSeen = true
                    continue
                }
                if (!inMainmenu) continue
                val key = trimmed.substringBefore("=").trim()
                if (key == "icon") iconLine = i
                if (key == "ownIcon") ownIconLine = i
            }
            if (!mainmenuSeen) return
            if (mainmenuEnd == -1) mainmenuEnd = lines.size
            // A file ending in a newline yields a trailing empty
            // element; insert before it so the file keeps its
            // trailing newline and no blank line lands inside
            // [mainmenu].
            if (mainmenuEnd == lines.size && lines.isNotEmpty()
                && lines.last().isEmpty()) mainmenuEnd -= 1
            var changed = false
            if (iconLine == -1) {
                lines.add(
                    mainmenuEnd,
                    "icon=/usr/share/lxqt/themes/mudif/mainmenu.png"
                )
                mainmenuEnd += 1
                changed = true
            } else if (lines[iconLine].trim() !=
                "icon=/usr/share/lxqt/themes/mudif/mainmenu.png") {
                lines[iconLine] =
                    "icon=/usr/share/lxqt/themes/mudif/mainmenu.png"
                changed = true
            }
            if (ownIconLine == -1) {
                lines.add(mainmenuEnd, "ownIcon=true")
                changed = true
            } else if (lines[ownIconLine].trim() != "ownIcon=true") {
                lines[ownIconLine] = "ownIcon=true"
                changed = true
            }
            if (!changed) return
            panelConf.writeText(lines.joinToString("\n"))
        } catch (e: Exception) {
            MudifLog.d(TAG, "p90 panel menu icon assertion failed: ${e.message}")
        }
    }

    /**
     * Apply the whole look. Host-side file writes only, best-effort,
     * idempotent (the marker gates re-runs). Call from a background
     * thread. Returns true only when the theme packages are present
     * AND every file is written.
     */
    fun ensureLxqtLook(context: Context): Boolean {
        // p90: re-assert the panel menu icon keys before anything
        // else (see assertPanelMenuIcon): the marker early-return
        // below would skip the repair on every later start.
        assertPanelMenuIcon(context)
        if (isApplied(context)) return true
        // p23: adaptive scale, computed once from this device's real
        // screen and threaded through every config writer below.
        val scale = DesktopScaleCalculator.compute(context)
        val rootfs = DebianSystem.debianRootfsDir(context)
        // p98: the active theme, threaded through the config writers
        // below (palette, settings.conf wallpaper, per-theme files).
        val theme = AppTheme.currentTag(context)

        // Prereq: the apt set must have landed (startlxqt + the
        // bloom-dark icons). If ensureDebianDesktop failed, the look
        // must report false honestly instead of writing configs for
        // programs that are not there.
        if (!File(rootfs, "usr/bin/startlxqt").exists()) {
            MudifLog.d(TAG, "startlxqt missing - Debian desktop set not installed")
            return false
        }
        if (!File(rootfs, "usr/share/icons/bloom-dark").exists()) {
            MudifLog.d(TAG, "bloom-dark icons missing - deepin-icon-theme not installed")
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
            // p86: the default is now the Deepin-style gradient
            // wallpaper (polish spec section 1, approved
            // 2026-11-17); pomegranate stays selectable.
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
            // p86: the gradient wallpaper is staged next to the
            // others so it is selectable in the desktop wallpaper
            // settings; PCMANFM_SETTINGS_CONF Wallpaper points
            // at it as the new default.
            context.assets.open(WALLPAPER_GRADIENT_ASSET).use { input ->
                File(backgrounds, "mudif-wallpaper-gradient.png").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            // p91: the two rotating wallpapers are staged next to
            // the others so they are selectable in the desktop
            // wallpaper settings; the rotate script (below)
            // alternates between these two only. The gradient
            // stays staged and selectable, and stays the staged
            // default in PCMANFM_SETTINGS_CONF.
            context.assets.open(WALLPAPER_PREMIUM_ASSET).use { input ->
                File(backgrounds, "mudif-wallpaper-premium.png").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            context.assets.open(WALLPAPER_GREEN_ASSET).use { input ->
                File(backgrounds, "mudif-wallpaper-green.png").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            val lxqtDir = File(rootfs, "root/.config/lxqt")
            lxqtDir.mkdirs()
            File(lxqtDir, "lxqt.conf").writeText(lxqtConf(scale, theme))
            File(lxqtDir, "session.conf").writeText(SESSION_CONF)
            val pcmanfmDir = File(rootfs, "root/.config/pcmanfm-qt/lxqt")
            pcmanfmDir.mkdirs()
            File(pcmanfmDir, "settings.conf").writeText(pcmanfmSettingsConf(scale, theme))
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
            // p68 - the download library (segmented aria2c + resume +
            // on-device cache + mirror-pick). Staged here (not only
            // baked in the image) so an existing phone picks it up on
            // this update. setExecutable: ProgramStore execs it via
            // `sh`, but the CLI / terminal can call it directly too.
            context.assets.open(DL_ASSET).use { input ->
                val dlFile = File(mudifConfDir, "mudif-dl.sh")
                dlFile.outputStream().use { out ->
                    input.copyTo(out)
                }
                dlFile.setExecutable(true, false)
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
            // p59 - the FancyMenu About button hardcodes the
            // `lxqt-about` command (lxqtfancymenuwindow.cpp
            // runAboutgDialog); the LXQt dialog was removed in
            // p20, so the button errored "No Executable". The shim
            // redirects it to Mudif's own About page, and the
            // updated mudif-about.py carries the app version +
            // owner. Both are staged here (not only baked in the
            // image) so an existing phone picks them up on this
            // update. setExecutable: the panel execs both files,
            // and a fresh outputStream file would be 644.
            val binDir = File(rootfs, "usr/local/bin")
            binDir.mkdirs()
            context.assets.open(ABOUT_SHIM_ASSET).use { input ->
                File(binDir, "lxqt-about").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            File(binDir, "lxqt-about").setExecutable(true, false)
            context.assets.open(ABOUT_PY_ASSET).use { input ->
                File(binDir, "mudif-about").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            File(binDir, "mudif-about").setExecutable(true, false)
            // p91 - the wallpaper rotator: alternates between the
            // two new wallpapers on every desktop start and every
            // 30 minutes while the desktop runs (state in
            // root/.config/mudif/wallpaper-index, flock single
            // instance). Staged here (not only baked in the image)
            // so an existing phone picks it up on this update.
            // setExecutable: the autostart entry execs it directly,
            // and a fresh outputStream file would be 644.
            context.assets.open(ROTATE_SH_ASSET).use { input ->
                File(binDir, "mudif-wallpaper-rotate.sh").outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            File(binDir, "mudif-wallpaper-rotate.sh").setExecutable(true, false)
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
            // p98: the zip ships the LIGHT base for the panel QSS,
            // gtk.css and themerc, so the active theme's variant
            // overwrites them here, after extraction and BEFORE
            // rewriteOpenboxScale (the themerc rewrite edits the
            // live file, so it must see the theme's variant).
            copyThemeAsset(context, rootfs, "mudif-theme/lxqt-panel-$theme.qss", "usr/share/lxqt/themes/mudif/lxqt-panel.qss")
            copyThemeAsset(context, rootfs, "mudif-theme/gtk-$theme.css", "root/.config/gtk-3.0/gtk.css")
            copyThemeAsset(context, rootfs, "mudif-theme/openbox-$theme/themerc", "usr/share/themes/mudif/openbox-3/themerc")
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
            MudifLog.d(TAG, "icon cache build exit=${cacheResult.exitCode} out=${cacheResult.output.takeLast(200)}")
            if (!cacheResult.output.contains("ICON_CACHE_OK")) {
                MudifLog.d(TAG, "icon cache build failed - look not applied")
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
            // p90 correction: the "preserves" claim above is
            // FALSIFIED for ownIcon (the p87/p88 rig panel.conf
            // dumps have icon= but no ownIcon after the panel
            // runs), which is why the button showed the theme's
            // start icon instead of the penguin. The repair is
            // assertPanelMenuIcon(), called on every desktop start
            // before the marker early-return (see its comment).
            val panelConf = File(lxqtDir, "panel.conf")
            val existingPanelConf = when {
                panelConf.exists() -> panelConf.readText()
                File(rootfs, "etc/xdg/lxqt/panel.conf").exists() ->
                    File(rootfs, "etc/xdg/lxqt/panel.conf").readText()
                else -> null
            }
            panelConf.writeText(mergePanelConf(existingPanelConf, scale))
            // p95: the corrected launcher desktop file (see
            // MUDIF_LAUNCHER_DESKTOP). Overwrites the image's
            // copy, the same pattern as lxqt.conf / panel.conf
            // above: the file is Mudif's own, not a Debian
            // package file. Written before the first desktop
            // launch, so pcmanfm-desktop reads Icon=
            // mudif-launcher from its first start.
            val launcherDesktop = File(rootfs, "usr/share/applications/mudif-launcher.desktop")
            launcherDesktop.parentFile?.mkdirs()
            launcherDesktop.writeText(MUDIF_LAUNCHER_DESKTOP)
            // p96: the DESKTOP copy (~/Desktop/mudif-launcher.desktop)
            // was never re-staged. The p23 image baked it with
            // Icon=system-run (build-image.sh copies the same file
            // to both places at image time), and p95 only rewrote
            // the /usr/share/applications copy above, so the live
            // desktop icon stayed the WhiteSur gear (p95 F5). The
            // desktop icon is this file, not the applications one.
            // Same pattern as above: full rewrite from the same
            // const (the file is Mudif's own, never user-edited).
            // writeText truncates in place, so the inode, the +x
            // mode and the metadata::trust xattr survive; the
            // post-staging re-kill in VncActivity starts a fresh
            // pcmanfm-desktop that reads the new Icon.
            val desktopLauncher = File(rootfs, "root/Desktop/mudif-launcher.desktop")
            desktopLauncher.parentFile?.mkdirs()
            desktopLauncher.writeText(MUDIF_LAUNCHER_DESKTOP)
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
            // p91 - the wallpaper rotator autostart: LXQt runs
            // root/.config/autostart/*.desktop on every desktop
            // start (same mechanism as the xrdb entry above), so
            // the rotator starts with the session and alternates
            // the wallpaper. No systemd in the proot guest, so
            // autostart (not a timer unit) is the mechanism.
            File(autostartDir, "mudif-wallpaper-rotate.desktop").writeText(WALLPAPER_ROTATE_AUTOSTART)
            // p98: the theme file, the rotator state index and the
            // rotator pin, next to the CLI files in mudifConfDir
            // (created above). The pin pauses the rotator while a
            // theme is active; the index keeps the rotator's state
            // consistent with the pinned wallpaper if the pin is
            // ever lifted. The marker carries the theme, so a
            // switch stages on the next desktop start.
            File(mudifConfDir, "theme").writeText("$theme\n")
            File(mudifConfDir, "wallpaper-index").writeText("${wallpaperIndexFor(theme)}\n")
            File(mudifConfDir, "wallpaper-pinned").writeText("$theme\n")
            File(rootfs, "root/$MARKER").writeText("$MARKER_VERSION:$theme\n")
        } catch (e: Exception) {
            MudifLog.d(TAG, "look config write failed: ${e.message}")
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
    // p88: the palette flips to LIGHT (desktop polish spec
    // section 3/7: Zorin white windows, window chrome white,
    // body text #333, secondary #888). pcmanfm-qt 2.1.0 loads no
    // stylesheet (proven from its source), so this group is the
    // only mechanism that styles its window, sidebar, header
    // bar and status bar. The panel is NOT affected: its
    // 60%-alpha gradient blends against BLACK (no compositor),
    // not the palette - the P9 measurement proved it (the 55%
    // gradient rendered #180d42, exactly 55% of (43,24,120)
    // over black; over the old palette #232323 it would have
    // been #271D52). The p88 render harness re-proves the
    // panel colour with the new palette.
    // P21: icon_theme=WhiteSur-light (baked into the p21 image;
    // the `mudif` icon theme stays installed as the last fallback
    // in WhiteSur-light's Inherits chain, for the penguin/purple
    // names only it provides). theme stays mudif.
    // P23: font=Cairo,17 (LXQt convention: lxqt-config-appearance
    // writes the shell font to lxqt.conf [General] as font=Family,pt;
    // the LXQt platform theme reads it). 17 is the Nova 9 adaptive
    // output (3.0 mm on the glass); a small phone gets 19, a tablet
    // 11. The image base below carries the Nova 9 value; a real
    // device writes its own via lxqtConf(scale, theme). If the rig shows
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
window_color=#FFFFFF
base_color=#FFFFFF
highlight_color=#593BFE
window_text_color=#333333
text_color=#333333
highlighted_text_color=#FFFFFF
link_color=#593BFE
link_visited_color=#6A5AE0
tooltip_base_color=#FFFFFF
tooltip_text_color=#333333
"""

    // p23: runtime adaptive path. LXQT_CONF above is the IMAGE base
    // (Nova 9 outputs; extract-look.py reads it by regex into the
    // p23 image). On a real device ensureLxqtLook writes the same
    // text with this device's computed font, so a small phone and a
    // tablet get their own size from the same build. The require
    // fails the look loudly (caught by ensureLxqtLook, returns
    // false) if the base ever changes without this line, instead of
    // silently shipping the wrong size.
    // p98: the [Palette] block is the active theme's (the rest of
    // LXQT_CONF is theme-independent). [Palette] is the last block
    // in the base, so replaceAfter keeps everything before it and
    // drops nothing: the staged file is byte-identical to the light
    // base except the ten palette lines.
    private fun lxqtConf(scale: DesktopScale, theme: String): String {
        val out = LXQT_CONF.replace("font=Cairo,17", "font=Cairo,${scale.fontPt}")
        require(out != LXQT_CONF) { "LXQT_CONF base font changed, update lxqtConf" }
        return out.replaceAfter("[Palette]\n", paletteBlock(theme))
    }

    /** The [Palette] block of lxqt.conf for [theme]. Key names are
     *  the exact ones LXQt reads (window_color, base_color,
     *  highlight_color, window_text_color, text_color,
     *  highlighted_text_color, link_color, link_visited_color,
     *  tooltip_base_color, tooltip_text_color). The light block is
     *  the LXQT_CONF base itself, so a light stage is a no-op
     *  except for the font size. */
    private fun paletteBlock(theme: String): String = when (theme) {
        AppTheme.TAG_DARK -> """window_color=#1E1830
base_color=#241D3D
highlight_color=#593BFE
window_text_color=#F2EFFF
text_color=#F2EFFF
highlighted_text_color=#FFFFFF
link_color=#8B7CF5
link_visited_color=#6A5AE0
tooltip_base_color=#241D3D
tooltip_text_color=#F2EFFF
"""
        AppTheme.TAG_GRADIENT -> """window_color=#2A1B5E
base_color=#352276
highlight_color=#593BFE
window_text_color=#FFFFFF
text_color=#FFFFFF
highlighted_text_color=#FFFFFF
link_color=#9B89FE
link_visited_color=#6A5AE0
tooltip_base_color=#352276
tooltip_text_color=#FFFFFF
"""
        else -> """window_color=#FFFFFF
base_color=#FFFFFF
highlight_color=#593BFE
window_text_color=#333333
text_color=#333333
highlighted_text_color=#FFFFFF
link_color=#593BFE
link_visited_color=#6A5AE0
tooltip_base_color=#FFFFFF
tooltip_text_color=#333333
"""
    }

    /** Copy one per-theme file from the app assets over the live
     *  guest path. A missing asset throws, caught by
     *  ensureLxqtLook, so the look reports false honestly instead
     *  of staging a half theme. */
    private fun copyThemeAsset(context: Context, rootfs: File, assetPath: String, guestPath: String) {
        val dest = File(rootfs, guestPath)
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
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
    // its own via pcmanfmSettingsConf(scale, theme).
    // p63: [System] Terminal=qterminal. pcmanfm-qt 2.1.0 falls back
    // to a hardcoded Terminal="xterm" when the key is missing
    // (settings.cpp:215), and the user settings.conf staged here
    // is the only file it reads once its dir exists (profileDir),
    // so the system default Terminal=x-terminal-emulator is never
    // seen. xterm is not installed; every Open Terminal tap showed
    // "Failed to execute child process 'xterm'" and piled up one
    // "خطا" entry per tap (image-408). qterminal is installed and
    // is already the panel and menu terminal.
    private const val PCMANFM_SETTINGS_CONF = """[System]
Terminal=qterminal

[Desktop]
Wallpaper=/usr/share/backgrounds/mudif-wallpaper-gradient.png
WallpaperMode=stretch
DesktopIconSize=93
"""

    // p23: runtime adaptive path (same pattern as lxqtConf above).
    // p98: the Wallpaper is the active theme's (premium Badeen for
    // dark and gradient, green storybook for light). The rotator
    // pin (wallpaper-pinned) keeps the rotator from flipping it
    // away while the theme is active.
    private fun pcmanfmSettingsConf(scale: DesktopScale, theme: String): String {
        val out = PCMANFM_SETTINGS_CONF.replace(
            "DesktopIconSize=93", "DesktopIconSize=${scale.iconPx}"
        )
        require(out != PCMANFM_SETTINGS_CONF) {
            "PCMANFM_SETTINGS_CONF base icon size changed, update pcmanfmSettingsConf"
        }
        val wallpaperOut = out.replace(
            "Wallpaper=/usr/share/backgrounds/mudif-wallpaper-gradient.png",
            "Wallpaper=${wallpaperPathFor(theme)}"
        )
        require(wallpaperOut != out) {
            "PCMANFM_SETTINGS_CONF base wallpaper changed, update pcmanfmSettingsConf"
        }
        return wallpaperOut
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
            // p87: rail on the RIGHT in RTL (desktop polish spec
            // section 2). The code default is categoriesAtRight=true
            // and QHBoxLayout mirrors it to the visual left in RTL;
            // false re-inserts the category view at layout index 0,
            // which mirrors to the visual right. The merge runs once
            // per marker bump, so an existing rootfs picks it up
            // on the p33 re-apply and the panel re-reads it at
            // startup. buttonsAtTop stays default false (Bottom).
            "categoriesAtRight" to "false",
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
        // p96 correction of the p95 note: [General] iconSize is
        // NEVER read by the panel. lxqtpanel.cpp readSettings()
        // does mSettings->beginGroup(mConfigGroup) with
        // mConfigGroup = "panel1" (lxqtpanelapplication.cpp: the
        // first panel is named panel1) and reads iconSize there,
        // default PANEL_DEFAULT_ICON_SIZE 22 (lxqtpanellimits.h).
        // updateStyleSheet() sizes the buttons from mIconSize,
        // which is that [panel1] value. p95 wrote 48 into
        // [General] and the penguin stayed 22px on the rig (p95
        // F2), which is exactly what this code predicts. The
        // stale [General] iconSize=48 line p95 wrote passes
        // through the merge untouched (a dead key, never read);
        // it is left in place because the merge has no key
        // deletion mechanism and inventing one is out of scope.
        val generalKeys = linkedMapOf(
            "iconTheme" to "WhiteSur-light"
        )
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
        // p96: iconSize=48 in [panel1] (see the generalKeys note:
        // this is the section the panel actually reads). The
        // merge replaces the rig's [panel1] iconSize=22 in place
        // and the panel preserves it on exit (saveSettings writes
        // it back into the same group). 48 grows the penguin to
        // 48px and every other panel icon with it (quicklaunch,
        // tray, taskbar); the panel height (62px on the Nova 9)
        // still fits a 48px icon.
        val panel1Keys = linkedMapOf(
            "panelSize" to scale.panelPx.toString(),
            "iconSize" to "48"
        )
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
        // p95: the "taskbar expands" expectation is FALSIFIED (p93
        // rig round: one window, one ~92px button, a 409px void
        // between the left group and the taskbar). The two spacers
        // below were made expandable to center the taskbar
        // instead (see the spacer note).
        // p96: that centering is FALSIFIED too (p95 rig round:
        // spacers ~306px vs ~10px; the upstream layout code gives
        // an expandable spacer a 1px sizeHint, so it cannot split
        // the leftover). The spacers below are fixed separators
        // again; the button position now follows the iconSize 48
        // widths (see the spacer note).
        // p90 (polish spec section 5): the two lined separators
        // return, in the plugins list and as sections below: one
        // after quicklaunch (launcher group | taskbar) and one
        // before worldclock (tray/volume group | clock). These are
        // VISIBLE separators (the line is painted by the base
        // #Spacer rule), not the invisible P15 spacer. The P14
        // plugin order is otherwise unchanged: the centered app
        // group needs the still-open section 9 decision (pinned
        // favorites vs live task buttons), so it is NOT redesigned
        // here.
        val pluginsList = "mainmenu, quicklaunch, spacer, taskbar, tray, statusnotifier, mount, volume, spacer2, worldclock"
        // p90 (polish spec section 5): the clock shows the time
        // stacked over the date (Deepin reference), not the time
        // alone. The worldclock plugin's date is off by default
        // (the system default panel.conf [worldclock] carries
        // only type=worldclock, and the rig shows time only).
        // Keys from the lxqt-panel 2.x worldclock config (a real
        // 2.2 user panel.conf shows showDate + datePosition):
        // showDate=true turns the date on, datePosition=below
        // puts it under the time. Format keys stay default
        // (locale short date). If the guest's 2.1.4 plugin
        // ignored these keys, the clock would stay time-only
        // (unknown keys are ignored, nothing breaks); the rig
        // round is the arbiter.
        val worldclockKeys = linkedMapOf(
            "showDate" to "true",
            "datePosition" to "below"
        )
        // p90 (polish spec section 5): the two lined separators.
        // Keys from the upstream spacer source
        // (plugin-spacer/spacer.cpp + spacerconfiguration.cpp,
        // in diagnosis-spacer/): size default 8, expandable
        // default false, spaceType default msTypes[0] = "lined".
        // The values below equal the defaults; they are written
        // explicitly so the separators are visible in panel.conf
        // and do not depend on a future default change.
        // p96: the p95 expandable-spacer centering is REVERTED:
        // the hypothesis is falsified by the upstream code, and
        // the p95 rig round refuted it by measurement (spacers
        // ~306px vs ~10px). The derivation, from lxqt-panel
        // 2.1.0 panel/lxqtpanellayout.cpp setGeometryHoriz:
        // expFactor = (panelWidth - nonExpWidth) / expWidth, and
        // each expandable item gets sizeHint * expFactor. An
        // expandable spacer's sizeHint is 1px (plugin-spacer/
        // spacer.cpp setSizes: setMinimumSize({1, 1}) with an
        // Expanding policy and no sizeHint override), so two
        // spacers absorb ~2 * expFactor px total, about 5px, and
        // can never center anything. The item that absorbs the
        // leftover is the taskbar: isExpandable() is hardcoded
        // true (plugin-taskbar/lxqttaskbarplugin.h) and its
        // sizeHint is the button widths, so it takes ~99% of the
        // leftover; its buttons are then pinned to its start
        // edge (liblxqt lxqtgridlayout.cpp setGeometry: a single
        // row starts at geometry.left(), itemWidth capped by the
        // cell max 220, no centering step), which is why the p95
        // rig showed a ~306px void INSIDE the taskbar, not
        // between spacers. No config key centers taskbar buttons
        // (the taskbar settings list has no alignment key); true
        // centering needs a guest code change, out of scope for
        // this app-only build. What the config CAN set is the
        // button position: buttonCenter = mainmenuW +
        // quicklaunchW + spacerW + buttonW / 2 (logical coords;
        // the panel mirrors it in RTL). At iconSize 48 the left
        // group grows (mainmenu ~67, quicklaunch ~5 x ~57) and
        // the button moves right toward center; the rig round
        // arbitrates whether it lands within 30px. The spacers
        // go back to the p90 design: fixed 8px lined separators.
        // spaceType stays "lined": the line is painted by the
        // theme QSS base #Spacer rule, NOT by the config value
        // (P9FIX5: property-conditioned rules never match), so
        // "invisible" would change nothing.
        val spacerKeys = linkedMapOf(
            "size" to "8",
            "expandable" to "false",
            "spaceType" to "lined"
        )
        val spacer2Keys = linkedMapOf(
            "size" to "8",
            "expandable" to "false",
            "spaceType" to "lined"
        )
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
        // p90: [worldclock], [spacer], [spacer2] sections.
        val seenWorldclockKeys = mutableSetOf<String>()
        var inWorldclock = false
        var worldclockSeen = false
        val seenSpacerKeys = mutableSetOf<String>()
        var inSpacer = false
        var spacerSeen = false
        val seenSpacer2Keys = mutableSetOf<String>()
        var inSpacer2 = false
        var spacer2Seen = false
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
        fun appendMissingWorldclockKeys() {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            for ((k, v) in worldclockKeys) if (k !in seenWorldclockKeys) out.append("$k=$v\n")
        }
        fun appendMissingSpacerKeys() {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            for ((k, v) in spacerKeys) if (k !in seenSpacerKeys) out.append("$k=$v\n")
        }
        fun appendMissingSpacer2Keys() {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            for ((k, v) in spacer2Keys) if (k !in seenSpacer2Keys) out.append("$k=$v\n")
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
                if (inWorldclock) appendMissingWorldclockKeys()
                if (inSpacer) appendMissingSpacerKeys()
                if (inSpacer2) appendMissingSpacer2Keys()
                inMainmenu = trimmed == "[mainmenu]"
                if (inMainmenu) mainmenuSeen = true
                inGeneral = trimmed == "[General]"
                if (inGeneral) generalSeen = true
                inPanel1 = trimmed == "[panel1]"
                if (inPanel1) panel1Seen = true
                inQuicklaunch = trimmed == "[quicklaunch]"
                if (inQuicklaunch) quicklaunchSeen = true
                inWorldclock = trimmed == "[worldclock]"
                if (inWorldclock) worldclockSeen = true
                inSpacer = trimmed == "[spacer]"
                if (inSpacer) spacerSeen = true
                inSpacer2 = trimmed == "[spacer2]"
                if (inSpacer2) spacer2Seen = true
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
                    // kbindicator, quicklaunch2), so keeping it
                    // as-is would keep them. The merge runs once
                    // per marker version, so this does not fight
                    // later user edits. p90: the list now also
                    // carries spacer + spacer2 (spec section 5).
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
            if (inWorldclock) {
                val key = trimmed.substringBefore("=").trim()
                if (key in worldclockKeys) {
                    if (key !in seenWorldclockKeys) {
                        out.append("$key=${worldclockKeys[key]}\n")
                        seenWorldclockKeys.add(key)
                    }
                    continue
                }
            }
            if (inSpacer) {
                val key = trimmed.substringBefore("=").trim()
                if (key in spacerKeys) {
                    if (key !in seenSpacerKeys) {
                        out.append("$key=${spacerKeys[key]}\n")
                        seenSpacerKeys.add(key)
                    }
                    continue
                }
            }
            if (inSpacer2) {
                val key = trimmed.substringBefore("=").trim()
                if (key in spacer2Keys) {
                    if (key !in seenSpacer2Keys) {
                        out.append("$key=${spacer2Keys[key]}\n")
                        seenSpacer2Keys.add(key)
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
        if (inWorldclock) appendMissingWorldclockKeys()
        if (inSpacer) appendMissingSpacerKeys()
        if (inSpacer2) appendMissingSpacer2Keys()
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
        if (!worldclockSeen) {
            // Defensive only: [worldclock] is in both the system
            // default and the user panel.conf, so this should
            // never trigger. A minimal [worldclock] with just
            // the date keys is better than a time-only clock.
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            out.append("[worldclock]\n")
            out.append("type=worldclock\n")
            for ((k, v) in worldclockKeys) out.append("$k=$v\n")
        }
        if (!spacerSeen) {
            // Defensive only: no [spacer] is in the system
            // default, so this triggers on a fresh merge from it.
            // A minimal [spacer] with the lined keys is better
            // than a spacer with unknown defaults.
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            out.append("[spacer]\n")
            out.append("type=spacer\n")
            for ((k, v) in spacerKeys) out.append("$k=$v\n")
        }
        if (!spacer2Seen) {
            // Same as [spacer]: the second separator.
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
            out.append("[spacer2]\n")
            out.append("type=spacer\n")
            for ((k, v) in spacer2Keys) out.append("$k=$v\n")
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

    // p91 - the wallpaper rotator autostart entry (see
    // ensureLxqtLook). The script alternates the two new
    // wallpapers; the Exec path is the staged script.
    private const val WALLPAPER_ROTATE_AUTOSTART = """[Desktop Entry]
Type=Application
Name=mudif-wallpaper-rotate
Exec=/usr/local/bin/mudif-wallpaper-rotate.sh
"""

    // p95 - the launcher's desktop file, corrected: the image
    // bakes Icon=system-run (the WhiteSur gear), and the icon
    // lookup checks WhiteSur-light before mudif, so a mudif
    // system-run override would never win. The corrected file
    // declares Icon=mudif-launcher, a name only the mudif theme
    // provides (apps/64x64 in mudif-theme.zip: the system
    // penguin mark). Staged here so an existing phone picks it
    // up on this update; the image source
    // (image-build/files/mudif-launcher/) carries the same Icon
    // so the next image build agrees. launchDesktop still chmods
    // +x and sets metadata::trust on this file after staging,
    // so the staged copy needs no +x here.
    private const val MUDIF_LAUNCHER_DESKTOP = """[Desktop Entry]
Type=Application
Name=Mudif Launcher
Name[ar]=مشغّل مضيف
Comment=Touch-friendly fullscreen app launcher (P19 Phase 4)
Exec=/usr/local/bin/mudif-launcher --open
Icon=mudif-launcher
Terminal=false
X-LXQt-Need-Tray=false
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
