package com.bolm.mudif.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * p79 R7: the single gate for debug logging.
 *
 * Log.d / Log.i call sites use this object instead of
 * android.util.Log directly. Debug and info lines are emitted
 * only when the user enabled them in Settings (key
 * "debug_logging", default OFF): the shipped build is a debug
 * build (CI runs assembleDebug), so a BuildConfig.DEBUG gate
 * would change nothing, and the rig measured logd at 63-72%
 * CPU during heavy rounds. Warnings and errors are never gated:
 * Log.e stays the diagnostic channel and LogViewerActivity keeps
 * working (it shows whatever logcat holds).
 *
 * The flag is read from the cached SharedPreferences instance on
 * every call, so flipping the switch takes effect at once with
 * no restart and no stale cache. Before init() runs (only
 * possible in a process that never created the Application),
 * debug lines stay silent rather than guessing.
 */
object MudifLog {

    const val KEY_DEBUG_LOGGING = "debug_logging"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(
                "${context.packageName}_preferences", Context.MODE_PRIVATE
            )
        }
    }

    fun isDebugEnabled(): Boolean =
        prefs?.getBoolean(KEY_DEBUG_LOGGING, false) ?: false

    fun d(tag: String, msg: String) {
        if (isDebugEnabled()) Log.d(tag, msg)
    }

    fun d(tag: String, msg: String, t: Throwable) {
        if (isDebugEnabled()) Log.d(tag, msg, t)
    }

    fun i(tag: String, msg: String) {
        if (isDebugEnabled()) Log.i(tag, msg)
    }

    fun i(tag: String, msg: String, t: Throwable) {
        if (isDebugEnabled()) Log.i(tag, msg, t)
    }
}
