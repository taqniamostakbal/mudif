package com.bolm.mudif.ui

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.engine.AppDeathDetector
import com.bolm.mudif.ui.settings.AppLanguage

/**
 * Base activity for every Mudif screen. p61: wraps the base
 * context with the saved app language (AppLanguage.wrapBaseContext)
 * before any resource is loaded, so the whole UI follows the
 * Language setting on every start and after every switch. The
 * wrap is the locale application; AppCompatDelegate's
 * setApplicationLocales alone never moved the visible UI (see
 * AppLanguage). No behaviour change besides the locale.
 *
 * p74: resume/pause heartbeats for the death detector, so the
 * last state file always knows which screen was in front and
 * whether the app was in the foreground when it died. Cheap:
 * one throttled small write on a background thread.
 */
open class MudifActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrapBaseContext(newBase))
    }

    override fun onResume() {
        super.onResume()
        AppDeathDetector.heartbeat(this, tab = javaClass.simpleName, action = "resume", foreground = true)
    }

    override fun onPause() {
        AppDeathDetector.heartbeat(this, tab = javaClass.simpleName, action = "pause", foreground = false)
        super.onPause()
    }
}
