package com.bolm.mudif.engine

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
 * Settings.ACTION_WIFI_DISPLAY_SETTINGS is not a guaranteed
 * public API, so the intent is built from the action STRING
 * (no compile dependency on the constant) and guarded by a
 * resolve check; when it does not resolve, the plain display
 * settings open instead. Returns false when nothing could open.
 */
object SystemDisplay {

    private const val TAG = "SystemDisplay"

    /** Action string for the system wireless-display settings. */
    private const val ACTION_WIFI_DISPLAY_SETTINGS =
        "android.settings.WIFI_DISPLAY_SETTINGS"

    fun openWirelessDisplaySettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(ACTION_WIFI_DISPLAY_SETTINGS),
            Intent(Settings.ACTION_DISPLAY_SETTINGS)
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) == null) continue
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "openWirelessDisplaySettings failed for ${intent.action}: ${e.message}")
            }
        }
        return false
    }
}
