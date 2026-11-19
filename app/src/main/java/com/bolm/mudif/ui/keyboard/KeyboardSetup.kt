package com.bolm.mudif.ui.keyboard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * p118: the honest route to the Mudif keyboard. Android never
 * lets an app force an IME silently (that would be a security
 * hole), so the app offers the switch and the user taps once:
 *  - enable: Settings > Language & input > the Mudif keyboard
 *    (one toggle in the system list).
 *  - pick: the system input-method picker (the keyboard icon in
 *    the navigation bar while typing), which makes it the current
 *    keyboard until the user picks another.
 * After that it stays the default, inside the viewer and
 * everywhere else on the phone. The VncActivity prompt (one
 * time, after the viewer opens) is the only nudge; the Settings
 * card is the permanent route.
 */
object KeyboardSetup {

    private const val PREFS_NAME = "mudif_app_prefs"
    private const val KEY_PROMPT_SHOWN = "keyboard_prompt_shown"

    /** Has the one-time viewer prompt been shown already? */
    fun isPromptShown(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PROMPT_SHOWN, false)
    }

    /** Mark the one-time viewer prompt as shown (even when the
     *  keyboard is already enabled: then there is nothing to
     *  prompt about). */
    fun markPromptShown(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PROMPT_SHOWN, true).apply()
    }

    /** The service id as the system knows it. */
    fun serviceId(context: Context): String =
        context.packageName + "/.ui.keyboard.MudifKeyboardService"

    /** Is the Mudif keyboard enabled in the system IME list? */
    fun isEnabled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        return imm.enabledInputMethodList.any { it.id == serviceId(context) }
    }

    /** Is the Mudif keyboard the CURRENT input method? */
    fun isCurrent(context: Context): Boolean {
        val current = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        )
        return current == serviceId(context)
    }

    /** Open the system input-method settings (the enable toggle
     *  lives there). */
    fun openInputMethodSettings(context: Context) {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Open the system input-method picker (choose the keyboard
     *  to type with right now). */
    fun showInputMethodPicker(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        imm.showInputMethodPicker()
    }
}
