package com.bolm.mudif.engine

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Phase 2, system wireless display (Miracast fallback).
 *
 * A third-party app CANNOT initiate Miracast on stock Android:
 * the Wi-Fi Display APIs are hidden and need the system-level
 * CONFIGURE_WIFI_DISPLAY permission. The only thing the app can
 * do is open the system wireless-display settings and let the
 * user pick the TV there (exactly what the Play Store "Miracast
 * helper" apps do). This helper is that deep-link, nothing more.
 *
 * p36 defect 3: the old fallback opened the plain Display
 * settings when the wireless-display page did not resolve, and
 * that page contains no TV picker at all (proven on the Nova 9:
 * it opens "Display and brightness", a dead end). So there is no
 * fallback anymore: when the wireless-display page does not
 * resolve on a phone, callers must NOT offer "open system
 * settings" as a TV path. Check hasWirelessDisplaySettings()
 * at dialog time first.
 *
 * Settings.ACTION_WIFI_DISPLAY_SETTINGS is not a guaranteed
 * public API, so the intent is built from the action STRING
 * (no compile dependency on the constant) and guarded by a
 * resolve check. Returns false when nothing could open.
 */
object SystemDisplay {

    private const val TAG = "SystemDisplay"

    /** Action string for the system wireless-display settings. */
    private const val ACTION_WIFI_DISPLAY_SETTINGS =
        "android.settings.WIFI_DISPLAY_SETTINGS"

    /**
     * True when this phone has a wireless-display settings page
     * (the Miracast picker). Callers check this at dialog time:
     * when it is false, never offer the system settings as a TV
     * path, the plain Display settings page has no TV picker.
     */
    fun hasWirelessDisplaySettings(context: Context): Boolean {
        return try {
            Intent(ACTION_WIFI_DISPLAY_SETTINGS)
                .resolveActivity(context.packageManager) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens ONLY the system wireless-display settings. Never falls
     * back to the plain Display settings: that page contains no TV
     * picker, so opening it as a TV path misleads the user.
     * Returns false when the page does not exist on this phone.
     */
    fun openWirelessDisplaySettings(context: Context): Boolean {
        if (!hasWirelessDisplaySettings(context)) return false
        return try {
            val intent = Intent(ACTION_WIFI_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "openWirelessDisplaySettings failed: ${e.message}")
            false
        }
    }
}
