package com.bolm.mudif.ui.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.bolm.mudif.R
import com.google.android.material.button.MaterialButton

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
     *  language row). Branded Mudif dialog (dialog_language_picker):
     *  the 3 languages are real buttons, each in its own name, so they
     *  read correctly before any locale is applied. Choosing applies
     *  the locale and recreates the activity. The old
     *  AlertDialog.Builder + setItems() version is gone: it used the
     *  system list style (not the app's look) and its item list never
     *  appeared when setMessage was also set (rig: title + message
     *  only, no ListView). */
    fun showPicker(activity: AppCompatActivity, firstRun: Boolean = false) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_language_picker, null)
        val title = view.findViewById<TextView>(R.id.dialog_title)
        val message = view.findViewById<TextView>(R.id.dialog_message)
        if (firstRun) {
            title.text = activity.getString(R.string.language_first_run_title)
            message.text = activity.getString(R.string.language_first_run_body)
            message.visibility = View.VISIBLE
        } else {
            title.text = activity.getString(R.string.language_title)
            message.visibility = View.GONE
        }
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(true)
            .create()
        val current = currentTag(activity)
        val options = listOf(
            view.findViewById<MaterialButton>(R.id.dialog_option_fusha) to TAG_FUSHA,
            view.findViewById<MaterialButton>(R.id.dialog_option_darija) to TAG_DARIJA,
            view.findViewById<MaterialButton>(R.id.dialog_option_english) to TAG_ENGLISH
        )
        val labels = listOf(
            activity.getString(R.string.language_fusha),
            activity.getString(R.string.language_darija),
            activity.getString(R.string.language_english)
        )
        for ((index, pair) in options.withIndex()) {
            val (button, tag) = pair
            button.text = labels[index]
            if (tag == current) {
                // Current language: filled purple, white text (Primary look).
                button.backgroundTintList =
                    ContextCompat.getColorStateList(activity, R.color.mudif_purple)
                button.setTextColor(ContextCompat.getColor(activity, android.R.color.white))
            }
            button.setOnClickListener {
                dialog.dismiss()
                apply(activity, tag)
                activity.recreate()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(activity, R.drawable.dialog_mudif_bg)
        )
    }
}
