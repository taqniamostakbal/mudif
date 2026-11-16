package com.bolm.mudif.ui

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.ui.settings.AppLanguage

/**
 * Base activity for every Mudif screen. p61: wraps the base
 * context with the saved app language (AppLanguage.wrapBaseContext)
 * before any resource is loaded, so the whole UI follows the
 * Language setting on every start and after every switch. The
 * wrap is the locale application; AppCompatDelegate's
 * setApplicationLocales alone never moved the visible UI (see
 * AppLanguage). No behaviour change besides the locale.
 */
open class MudifActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrapBaseContext(newBase))
    }
}
