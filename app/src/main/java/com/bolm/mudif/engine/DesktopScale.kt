package com.bolm.mudif.engine

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Adaptive desktop scale: one rule set, every Android device.
 *
 * At runtime the app reads the device's real screen size and density
 * and computes the guest resolution and every size the desktop needs
 * from that. Same build, any phone or tablet. No vendor-specific
 * assumption anywhere: only width, height and physical dpi.
 *
 * The one hard rule: raising the guest resolution makes everything
 * SMALLER on the screen, so the resolution is capped and the size
 * comes from bigger fonts, panel and icons, not from more pixels.
 *
 * Physical targets (measured on the phone glass, not in guest px):
 *   text em height   3.0 mm  (readable at arm's length)
 *   bottom panel     8.0 mm  (a thumb can hit it)
 *   desktop icon    12.0 mm  (a thumb can hit it)
 *
 * The guest renders a point as 1/72 inch at a 96 DPI assumption, so
 * a font of F pt is F * 96/72 guest px tall, and one guest px is
 * `scale` physical px wide. Solving for F from the 3.0 mm target:
 *   F = 3.0 * dpi / (25.4 * 96/72 * scale) = dpi * 0.0886 / scale.
 * Panel and icon guest px follow the same way (mm / (scale * 25.4 /
 * dpi)): panel 8.0 mm -> 0.315 * dpi / scale, icon 12.0 mm ->
 * 0.472 * dpi / scale. Every value is clamped so a very dense or
 * very sparse screen can never produce unreadable text or a panel
 * that eats the desktop.
 *
 * The pointer arrow is NOT part of this system: it is a real X
 * cursor at the theme's size and Anas said the pointer is fine.
 */
data class DesktopScale(
    val guestWidth: Int,
    val guestHeight: Int,
    /** Viewer upscale: phone short side / guest height. */
    val scale: Float,
    /** LXQt + Openbox + GTK font, points. */
    val fontPt: Int,
    /** LXQt bottom panel height, guest px. */
    val panelPx: Int,
    /** PCManFM desktop icon size, guest px. */
    val iconPx: Int,
    /** QTerminal font, points (shell font + 2). */
    val terminalPt: Int,
    /** Openbox window padding (themerc padding.width), guest px. */
    val openboxPadding: Int,
)

object DesktopScale {
    const val MIN_GUEST_HEIGHT = 480
    const val MAX_GUEST_HEIGHT = 720
    const val MIN_FONT_PT = 11
    const val MAX_FONT_PT = 20
    const val MIN_PANEL_PX = 48
    const val MAX_PANEL_PX = 80
    const val MIN_ICON_PX = 64
    const val MAX_ICON_PX = 128
    const val MIN_TERMINAL_PT = 13
    const val MAX_TERMINAL_PT = 22
    const val MIN_OPENBOX_PADDING = 4
    const val MAX_OPENBOX_PADDING = 8

    /**
     * Pure computation, no Android state: unit-testable and checkable
     * by hand. dpi is the PHYSICAL density (xdpi/ydpi average), not
     * the bucketed densityDpi, because the targets are physical mm.
     */
    fun compute(widthPx: Int, heightPx: Int, dpi: Float): DesktopScale {
        val longSide = maxOf(widthPx, heightPx)
        val shortSide = minOf(widthPx, heightPx)
        val guestH = roundEven(shortSide / 2.0)
            .coerceIn(MIN_GUEST_HEIGHT, MAX_GUEST_HEIGHT)
        val guestW = roundEven(guestH * longSide.toDouble() / shortSide)
        val scale = shortSide.toFloat() / guestH
        val fontPt = (0.0886f * dpi / scale).roundToInt()
            .coerceIn(MIN_FONT_PT, MAX_FONT_PT)
        val panelPx = (0.315f * dpi / scale).roundToInt()
            .coerceIn(MIN_PANEL_PX, MAX_PANEL_PX)
        val iconPx = (0.472f * dpi / scale).roundToInt()
            .coerceIn(MIN_ICON_PX, MAX_ICON_PX)
        val terminalPt = (fontPt + 2).coerceIn(MIN_TERMINAL_PT, MAX_TERMINAL_PT)
        val openboxPadding = (fontPt * 0.35f).roundToInt()
            .coerceIn(MIN_OPENBOX_PADDING, MAX_OPENBOX_PADDING)
        return DesktopScale(
            guestWidth = guestW,
            guestHeight = guestH,
            scale = scale,
            fontPt = fontPt,
            panelPx = panelPx,
            iconPx = iconPx,
            terminalPt = terminalPt,
            openboxPadding = openboxPadding,
        )
    }

    /** Reads the real display metrics of this device (same source as
     *  X11Session.viewerResolution, so the scale and the resolution
     *  never disagree about the screen size). Orientation proof: the
     *  math uses long/short side, so it works in landscape and
     *  portrait alike (the viewer forces landscape). */
    fun compute(context: Context): DesktopScale {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        val dpi = if (m.xdpi > 0f && m.ydpi > 0f) {
            (m.xdpi + m.ydpi) / 2f
        } else {
            m.densityDpi.toFloat()
        }
        return compute(m.widthPixels, m.heightPixels, dpi)
    }

    private fun roundEven(value: Double): Int =
        (value / 2.0).roundToInt() * 2
}
