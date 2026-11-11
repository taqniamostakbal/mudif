package com.bolm.mudif.ui.settings

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bolm.mudif.R

/**
 * App-level language (Fusha / Darija / English), stored in its own
 * prefs file ("mudif_app_prefs", key "app_language") so it never
 * collides with the viewer prefs (ViewerPrefs reads
 * "<package>_preferences"). Default when absent is Fusha ("ar").
 * AppCompatDelegate stores the locale itself (per-app locales), so
 * no manifest localeConfig is needed; apply() triggers the locale
 * change and the caller recreates the activity.
 */
object AppLanguage {

    private const val PREFS_NAME = "mudif_app_prefs"
    private const val KEY = "app_language"

    const val TAG_FUSHA = "ar"
    const val TAG_DARIJA = "ar-MA"
    const val TAG_ENGLISH = "en"

    val TAGS = listOf(TAG_FUSHA, TAG_DARIJA, TAG_ENGLISH)

    /** Current tag; Fusha ("ar") when nothing was chosen yet. */
    fun currentTag(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY, TAG_FUSHA) ?: TAG_FUSHA
    }

    /** True once the user has picked a language (first-run picker shows only when false). */
    fun isChosen(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY)
    }

    /** Save [tag] and apply it as the app locale. Caller recreates the activity. */
    fun apply(context: Context, tag: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, tag).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** Language picker dialog (drawer item, first-run dialog, Profile
     *  language row). The labels are each language's own name, so
     *  they read correctly before any locale is applied. Choosing
     *  applies the locale and recreates the activity. */
    fun showPicker(activity: AppCompatActivity, firstRun: Boolean = false) {
        val labels = arrayOf(
            activity.getString(R.string.language_fusha),
            activity.getString(R.string.language_darija),
            activity.getString(R.string.language_english)
        )
        val builder = AlertDialog.Builder(activity)
            .setItems(labels) { _, which ->
                apply(activity, TAGS[which])
                activity.recreate()
            }
        if (firstRun) {
            builder.setTitle(activity.getString(R.string.language_first_run_title))
            // setMessage on a setItems dialog shows above the list.
            builder.setMessage(activity.getString(R.string.language_first_run_body))
        } else {
            builder.setTitle(activity.getString(R.string.language_title))
        }
        builder.show()
    }
}
