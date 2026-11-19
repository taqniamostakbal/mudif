package com.bolm.mudif.ui.settings

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bolm.mudif.MainActivity
import com.bolm.mudif.R
import com.bolm.mudif.engine.LxqtLook
import com.google.android.material.button.MaterialButton

/**
 * App + desktop theme (Glass Dark / Clean Light / Royal Gradient),
 * stored in the same prefs file as the language ("mudif_app_prefs",
 * key "app_theme") so one prefs file holds the app identity.
 * Default when absent is Glass Dark ("dark"). The tag is read at
 * cold start by MudifActivity (setTheme before super.onCreate),
 * so the saved theme is the effective theme on every start and
 * after every switch. p98: values-night/colors.xml is deleted, so
 * there is no DayNight parent left; the three themes are full
 * Material3.NoActionBar styles and Theme.Mudif is a parent alias
 * of GlassDark (the manifest keeps android:theme="@style/Theme.Mudif").
 *
 * The tag also drives the guest: LxqtLook stages the matching
 * palette, panel QSS, gtk.css and themerc, pins the matching
 * wallpaper and pauses the rotator (wallpaper-pinned). The guest
 * side applies on the next full desktop start; the wallpaper
 * applies live when the guest is up (see LxqtLook.stageThemeSwitch).
 * The marker becomes "p41:<theme>", so a switch stages.
 */
object AppTheme {

    private const val PREFS_NAME = "mudif_app_prefs"
    private const val KEY = "app_theme"

    const val TAG_DARK = "dark"
    const val TAG_LIGHT = "light"
    const val TAG_GRADIENT = "gradient"

    val TAGS = listOf(TAG_DARK, TAG_LIGHT, TAG_GRADIENT)

    /** Style for a saved tag; unknown tags fall back to Glass Dark. */
    fun resolve(context: Context): Int = when (currentTag(context)) {
        TAG_LIGHT -> R.style.Theme_Mudif_CleanLight
        TAG_GRADIENT -> R.style.Theme_Mudif_RoyalGradient
        else -> R.style.Theme_Mudif_GlassDark
    }

    /** Current tag; Glass Dark ("dark") when nothing was chosen yet. */
    fun currentTag(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY, TAG_DARK) ?: TAG_DARK
    }

    /** Save [tag], stage the guest side of the switch (theme file,
     *  wallpaper pin + index, live wallpaper when the guest is up),
     *  toast the confirmation, then relaunch the whole task on
     *  MainActivity so every back-stack screen reloads in the new
     *  theme instead of keeping the old one. The guest desktop
     *  itself re-skins on its next full start (see the note on the
     *  theme card); the wallpaper flips live when possible. */
    fun apply(context: Context, tag: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, tag).apply()
        LxqtLook.stageThemeSwitch(context, tag)
        Toast.makeText(context, context.getString(R.string.theme_applied), Toast.LENGTH_SHORT).show()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
    }

    /** p111 (D4): save [tag] and stage the guest side of the
     *  switch, WITHOUT relaunching the task. The caller (the
     *  Control Center theme dots) recreates itself, so the viewer
     *  underneath stays open. No Intent here by design: the dots
     *  must never close the viewer. */
    fun applyQuiet(context: Context, tag: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, tag).apply()
        LxqtLook.stageThemeSwitch(context, tag)
        Toast.makeText(context, context.getString(R.string.theme_applied), Toast.LENGTH_SHORT).show()
    }

    /** Theme picker dialog (Settings theme card). Branded Mudif
     *  dialog (dialog_theme_picker): the 3 themes are real buttons,
     *  each in its own Fusha name, so they read correctly in any
     *  theme. Choosing applies the theme and relaunches the whole
     *  task (see apply()). Same pattern as AppLanguage.showPicker:
     *  the current option is filled white with purple text on the
     *  purple gradient dialog card. */
    fun showPicker(activity: AppCompatActivity) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_theme_picker, null)
        val title = view.findViewById<TextView>(R.id.dialog_title)
        val message = view.findViewById<TextView>(R.id.dialog_message)
        title.text = activity.getString(R.string.theme_title)
        message.text = activity.getString(R.string.theme_desc)
        message.visibility = View.VISIBLE
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(true)
            .create()
        val current = currentTag(activity)
        val options = listOf(
            view.findViewById<MaterialButton>(R.id.dialog_option_dark) to TAG_DARK,
            view.findViewById<MaterialButton>(R.id.dialog_option_light) to TAG_LIGHT,
            view.findViewById<MaterialButton>(R.id.dialog_option_gradient) to TAG_GRADIENT
        )
        val labels = listOf(
            activity.getString(R.string.theme_dark),
            activity.getString(R.string.theme_light),
            activity.getString(R.string.theme_gradient)
        )
        for ((index, pair) in options.withIndex()) {
            val (button, tag) = pair
            button.text = labels[index]
            if (tag == current) {
                // Current theme: filled white, purple text
                // (OnPurple look). The picker is a gradient card,
                // so a filled purple button would be invisible.
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
