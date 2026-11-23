package com.bolm.mudif.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import com.bolm.mudif.R
import com.bolm.mudif.ui.settings.ViewerPrefs

/**
 * F8 half A: system presentation displays (wired USB-C / HDMI on
 * DP-capable phones, wireless Smart View / Miracast / Huawei Easy
 * Projection). Wired and wireless both arrive as
 * DISPLAY_CATEGORY_PRESENTATION displays; the code path is the
 * same: the viewer opens on one via
 * X11Session.openViewerOnDisplay. The desktop itself (:6) never
 * moves — only the viewer activity changes display — so unplug
 * cannot kill the desktop.
 */
object ExternalDisplay {

    /** All connected presentation displays (external only, never the phone screen). */
    fun presentationDisplays(context: Context): List<Display> {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Human label for the picker: the device name (when the system
     *  reports one) + the size, Latin digits. */
    fun label(context: Context, display: Display): String {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val size = "${metrics.widthPixels}×${metrics.heightPixels}"
        val name = display.name?.takeIf { it.isNotBlank() }
        return if (name != null) "$name · $size"
        else context.getString(R.string.ui_display_external, size)
    }

    /**
     * Open the viewer on [display]: the same guards as the TV
     * button (desktop booted + TCP display open), the secondary
     * prefs flag (so the TV gets its own viewer settings), then
     * X11Session.openViewerOnDisplay. Call from a background
     * thread; returns false when the desktop is not ready or the
     * launch fails.
     */
    fun openViewerOn(context: Context, display: Display): Boolean {
        if (!GuestSession.booted || !X11Session.isTcpDisplayOpen()) return false
        ViewerPrefs.prefs(context).edit()
            .putBoolean("storeSecondaryDisplayPreferencesSeparately", true).apply()
        return X11Session.openViewerOnDisplay(context, display)
    }

    /**
     * Display detection while the viewer is open: fires when a
     * presentation display appears or disappears (plug / unplug,
     * Smart View connect / disconnect). Callbacks arrive on the
     * main thread. Returns the listener to unregister, or null
     * when registration failed (detection is best-effort).
     */
    fun registerListener(
        context: Context,
        onAdded: (Display) -> Unit,
        onRemoved: (Int) -> Unit
    ): DisplayManager.DisplayListener? {
        return try {
            val dm = context.applicationContext
                .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    try {
                        dm.getDisplay(displayId)?.let { onAdded(it) }
                    } catch (_: Exception) {
                    }
                }

                override fun onDisplayRemoved(displayId: Int) {
                    onRemoved(displayId)
                }

                override fun onDisplayChanged(displayId: Int) {}
            }
            dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            listener
        } catch (_: Exception) {
            null
        }
    }

    /** Unregister a listener from registerListener; no-op when null. */
    fun unregisterListener(context: Context, listener: DisplayManager.DisplayListener?) {
        if (listener == null) return
        try {
            val dm = context.applicationContext
                .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.unregisterDisplayListener(listener)
        } catch (_: Exception) {
        }
    }
}
