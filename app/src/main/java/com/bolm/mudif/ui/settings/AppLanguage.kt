package com.bolm.mudif.ui.settings

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.bolm.mudif.MainActivity
import com.bolm.mudif.R
import com.bolm.mudif.ui.agent.AgentFragment
import com.google.android.material.button.MaterialButton

/**
 * App-level language (Fusha / Darija / English), stored in its own
 * prefs file ("mudif_app_prefs", key "app_language") so it never
 * collides with the viewer prefs (ViewerPrefs reads
 * "<package>_preferences"). Default when absent is Fusha ("ar").
 * AppCompatDelegate stores the locale itself (per-app locales), so
 * no manifest localeConfig is needed; apply() triggers the locale
 * change, links the reply language (unless the user picked it by
 * hand in the chat), and relaunches the whole task on MainActivity,
 * so back-stack screens cannot keep the old language.
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

    /** Save [tag], apply it as the app locale, sync the reply
     *  language (AgentFragment skips the sync when the user picked
     *  the reply language by hand), then relaunch the whole task
     *  on MainActivity so every back-stack screen reloads in the
     *  new language instead of keeping the old one. */
    fun apply(context: Context, tag: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, tag).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        AgentFragment.applyAppLanguageReply(context, tag)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
    }

    /** Language picker dialog (drawer item, first-run dialog, Profile
     *  language row). Branded Mudif dialog (dialog_language_picker):
     *  the 3 languages are real buttons, each in its own name, so they
     *  read correctly before any locale is applied. Choosing applies
     *  the locale and relaunches the whole task (see apply()). The old
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
                // Current language: filled white, purple text
                // (OnPurple look). p56: the picker is a gradient
                // card now, so the old filled purple was invisible.
                button.backgroundTintList =
                    ContextCompat.getColorStateList(activity, android.R.color.white)
                button.setTextColor(ContextCompat.getColor(activity, R.color.mudif_purple))
            }
            button.setOnClickListener {
                dialog.dismiss()
                apply(activity, tag)
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(activity, R.drawable.dialog_mudif_bg)
        )
        com.bolm.mudif.ui.MudifDialog.animateWindow(dialog)
    }
}
