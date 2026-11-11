package com.bolm.mudif.ui.settings

import android.content.Context
import android.content.SharedPreferences

import com.bolm.mudif.R

/**
 * P18.3 — Viewer settings (Termux:X11) in Fusha Arabic, Mudif style.
 *
 * Single source of truth for the viewer preferences:
 * - The viewer (fork) reads PreferenceManager.getDefaultSharedPreferences,
 *   which is exactly getSharedPreferences("<package>_preferences"). This
 *   object reads/writes that EXACT file with the EXACT keys and value
 *   formats from the fork's preferences.xml, so the settings screen and
 *   the viewer never disagree. (The app has no androidx.preference
 *   dependency, so the file is opened directly by name.)
 * - Mudif defaults are applied ONLY when a pref is absent (first launch).
 *   The user's choice always wins afterwards (openViewer used to overwrite
 *   them on every launch — that silently erased user choices).
 */
object ViewerPrefs {

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    /** Mudif defaults (only written when the key is absent). */
    fun applyMudifDefaults(context: Context, exactResolution: String) {
        val p = prefs(context)
        val e = p.edit()
        fun putStringIfAbsent(key: String, value: String) {
            if (!p.contains(key)) e.putString(key, value)
        }
        fun putBooleanIfAbsent(key: String, value: Boolean) {
            if (!p.contains(key)) e.putBoolean(key, value)
        }
        putStringIfAbsent("touchMode", "2")              // Simulated touchscreen
        putStringIfAbsent("displayResolutionMode", "exact")
        putStringIfAbsent("displayResolutionExact", exactResolution)
        putStringIfAbsent("forceOrientation", "landscape")
        putBooleanIfAbsent("adjustResolution", false)
        putBooleanIfAbsent("displayStretch", true)
        putBooleanIfAbsent("fullscreen", true)
        putBooleanIfAbsent("tapToMove", false)
        putBooleanIfAbsent("additionalKbdVisible", false)
        e.apply()
    }

    sealed class Setting {
        abstract val key: String
        abstract val titleRes: Int
        abstract val descRes: Int
    }

    data class SwitchSetting(
        override val key: String,
        override val titleRes: Int,
        override val descRes: Int,
        val default: Boolean
    ) : Setting()

    data class ListSetting(
        override val key: String,
        override val titleRes: Int,
        override val descRes: Int,
        // value -> label resource id; 0 means the label IS the value
        // (used for the resolution list, e.g. "1280x720").
        val options: List<Pair<String, Int>>,
        val default: String
    ) : Setting()

    data class SliderSetting(
        override val key: String,
        override val titleRes: Int,
        override val descRes: Int,
        val min: Int,
        val max: Int,
        val default: Int
    ) : Setting()

    data class TextSetting(
        override val key: String,
        override val titleRes: Int,
        override val descRes: Int,
        val default: String
    ) : Setting()

    data class ActionSetting(
        override val key: String,
        override val titleRes: Int,
        override val descRes: Int
    ) : Setting()

    data class Section(val titleRes: Int, val settings: List<Setting>)

    private fun list(
        key: String, titleRes: Int, descRes: Int,
        options: List<Pair<String, Int>>, default: String
    ) = ListSetting(key, titleRes, descRes, options, default)

    private fun switch(key: String, titleRes: Int, descRes: Int, default: Boolean) =
        SwitchSetting(key, titleRes, descRes, default)

    private fun slider(key: String, titleRes: Int, descRes: Int, min: Int, max: Int, default: Int) =
        SliderSetting(key, titleRes, descRes, min, max, default)

    private fun text(key: String, titleRes: Int, descRes: Int, default: String) =
        TextSetting(key, titleRes, descRes, default)

    val RESOLUTIONS = listOf(
        "320x200", "320x240", "352x288", "400x240", "640x350", "640x480",
        "800x480", "800x600", "1024x768", "1280x720", "1280x960", "1280x1024",
        "1400x1050", "1600x1024", "1600x1200", "1366x768", "1680x1050",
        "1920x1080", "1920x1200", "2048x1536", "2560x1600", "2560x2048",
        "3840x2160", "7680x4320"
    )

    private val USER_ACTIONS = listOf(
        "no action" to R.string.vp_opt_no_action,
        "toggle soft keyboard" to R.string.vp_opt_toggle_soft_keyboard,
        "toggle additional key bar" to R.string.vp_opt_toggle_additional_key_bar,
        "open preferences" to R.string.vp_opt_open_preferences,
        "release pointer and keyboard capture" to R.string.vp_opt_release_pointer_keyboard,
        "toggle fullscreen" to R.string.vp_opt_toggle_fullscreen,
        "restart activity" to R.string.vp_opt_restart_activity,
        "exit" to R.string.vp_opt_exit
    )
    private val USER_ACTIONS_VOLUME = USER_ACTIONS + listOf(
        "send volume up" to R.string.vp_opt_send_volume_up,
        "send volume down" to R.string.vp_opt_send_volume_down
    )
    private val USER_ACTIONS_MEDIA = USER_ACTIONS + listOf(
        "send media action" to R.string.vp_opt_send_media_action
    )

    val SECTIONS: List<Section> = listOf(
        Section(R.string.vp_section_display, listOf(
            list("displayResolutionMode", R.string.vp_displayresolutionmode_title,
                R.string.vp_displayresolutionmode_desc,
                listOf("native" to R.string.vp_opt_native, "scaled" to R.string.vp_opt_scaled,
                    "exact" to R.string.vp_opt_exact, "custom" to R.string.vp_opt_custom), "scaled"),
            slider("displayScale", R.string.vp_displayscale_title,
                R.string.vp_displayscale_desc, 30, 300, 200),
            list("displayResolutionExact", R.string.vp_displayresolutionexact_title,
                R.string.vp_displayresolutionexact_desc,
                RESOLUTIONS.map { it to 0 }, "1280x1024"),
            text("displayResolutionCustom", R.string.vp_displayresolutioncustom_title,
                R.string.vp_displayresolutioncustom_desc, "1280x1024"),
            list("displayFilteringMode", R.string.vp_displayfilteringmode_title,
                R.string.vp_displayfilteringmode_desc,
                listOf("bilinear" to R.string.vp_opt_bilinear, "nearest" to R.string.vp_opt_nearest),
                "nearest"),
            switch("adjustResolution", R.string.vp_adjustresolution_title,
                R.string.vp_adjustresolution_desc, false),
            switch("displayStretch", R.string.vp_displaystretch_title,
                R.string.vp_displaystretch_desc, false),
            switch("Reseed", R.string.vp_reseed_title,
                R.string.vp_reseed_desc, true),
            switch("PIP", R.string.vp_pip_title,
                R.string.vp_pip_desc, true),
            switch("fullscreen", R.string.vp_fullscreen_title,
                R.string.vp_fullscreen_desc, true),
            list("forceOrientation", R.string.vp_forceorientation_title,
                R.string.vp_forceorientation_desc,
                listOf("auto" to R.string.vp_opt_auto, "portrait" to R.string.vp_opt_portrait,
                    "landscape" to R.string.vp_opt_landscape, "reverse portrait" to R.string.vp_opt_reverse_portrait,
                    "reverse landscape" to R.string.vp_opt_reverse_landscape), "auto"),
            switch("hideCutout", R.string.vp_hidecutout_title,
                R.string.vp_hidecutout_desc, true),
            switch("keepScreenOn", R.string.vp_keepscreenon_title,
                R.string.vp_keepscreenon_desc, true)
        )),
        Section(R.string.vp_section_pointer, listOf(
            list("touchMode", R.string.vp_touchmode_title,
                R.string.vp_touchmode_desc,
                listOf("1" to R.string.vp_opt_touchpad, "2" to R.string.vp_opt_simulated_touch,
                    "3" to R.string.vp_opt_direct_touch), "1"),
            switch("scaleTouchpad", R.string.vp_scaletouchpad_title,
                R.string.vp_scaletouchpad_desc, true),
            switch("showStylusClickOverride", R.string.vp_showstylusclickoverride_title,
                R.string.vp_showstylusclickoverride_desc, false),
            switch("stylusIsMouse", R.string.vp_stylusismouse_title,
                R.string.vp_stylusismouse_desc, false),
            switch("stylusButtonContactModifierMode",
                R.string.vp_stylusbuttoncontactmodifiermode_title,
                R.string.vp_stylusbuttoncontactmodifiermode_desc, false),
            switch("showMouseHelper", R.string.vp_showmousehelper_title,
                R.string.vp_showmousehelper_desc, false),
            switch("pointerCapture", R.string.vp_pointercapture_title,
                R.string.vp_pointercapture_desc, true),
            list("transformCapturedPointer", R.string.vp_transformcapturedpointer_title,
                R.string.vp_transformcapturedpointer_desc,
                listOf("no" to R.string.vp_opt_no_transform, "c" to R.string.vp_opt_clockwise,
                    "cc" to R.string.vp_opt_counterclockwise, "ud" to R.string.vp_opt_upside_down,
                    "at" to R.string.vp_opt_transform_auto), "no"),
            slider("capturedPointerSpeedFactor", R.string.vp_capturedpointerspeedfactor_title,
                R.string.vp_capturedpointerspeedfactor_desc, 1, 300, 100),
            switch("tapToMove", R.string.vp_taptomove_title,
                R.string.vp_taptomove_desc, false),
            switch("ignoreGamepadEvents", R.string.vp_ignoregamepadevents_title,
                R.string.vp_ignoregamepadevents_desc, false)
        )),
        Section(R.string.vp_section_keyboard, listOf(
            switch("showAdditionalKbd", R.string.vp_showadditionalkbd_title,
                R.string.vp_showadditionalkbd_desc, true),
            switch("showIMEWhileExternalConnected",
                R.string.vp_showimewhileexternalconnected_title,
                R.string.vp_showimewhileexternalconnected_desc, true),
            switch("preferScancodes", R.string.vp_preferscancodes_title,
                R.string.vp_preferscancodes_desc, false),
            switch("hardwareKbdScancodesWorkaround",
                R.string.vp_hardwarekbdscancodesworkaround_title,
                R.string.vp_hardwarekbdscancodesworkaround_desc, true),
            switch("dexMetaKeyCapture", R.string.vp_dexmetakeycapture_title,
                R.string.vp_dexmetakeycapture_desc, false),
            ActionSetting("enableAccessibilityService",
                R.string.vp_enableaccessibilityservice_title,
                R.string.vp_enableaccessibilityservice_desc),
            switch("enableAccessibilityServiceAutomatically",
                R.string.vp_enableaccessibilityserviceautomatically_title,
                R.string.vp_enableaccessibilityserviceautomatically_desc, false),
            switch("pauseKeyInterceptingWithEsc",
                R.string.vp_pausekeyinterceptingwithesc_title,
                R.string.vp_pausekeyinterceptingwithesc_desc, false),
            switch("filterOutWinkey", R.string.vp_filteroutwinkey_title,
                R.string.vp_filteroutwinkey_desc, false),
            switch("enforceCharBasedInput", R.string.vp_enforcecharbasedinput_title,
                R.string.vp_enforcecharbasedinput_desc, false)
        )),
        Section(R.string.vp_section_keybar, listOf(
            switch("adjustHeightForEK", R.string.vp_adjustheightforek_title,
                R.string.vp_adjustheightforek_desc, true),
            switch("useTermuxEKBarBehaviour",
                R.string.vp_usetermuxekbarbehaviour_title,
                R.string.vp_usetermuxekbarbehaviour_desc, true),
            slider("opacityEKBar", R.string.vp_opacityekbar_title,
                R.string.vp_opacityekbar_desc, 1, 100, 100),
            text("extra_keys_config", R.string.vp_extra_keys_config_title,
                R.string.vp_extra_keys_config_desc, "")
        )),
        Section(R.string.vp_section_user_actions, listOf(
            list("swipeUpAction", R.string.vp_swipeupaction_title,
                R.string.vp_swipeupaction_desc,
                USER_ACTIONS, "no action"),
            list("swipeDownAction", R.string.vp_swipedownaction_title,
                R.string.vp_swipedownaction_desc,
                USER_ACTIONS, "toggle additional key bar"),
            list("volumeUpAction", R.string.vp_volumeupaction_title,
                R.string.vp_volumeupaction_desc,
                USER_ACTIONS_VOLUME, "no action"),
            list("volumeDownAction", R.string.vp_volumedownaction_title,
                R.string.vp_volumedownaction_desc,
                USER_ACTIONS_VOLUME, "no action"),
            list("backButtonAction", R.string.vp_backbuttonaction_title,
                R.string.vp_backbuttonaction_desc,
                USER_ACTIONS, "toggle additional key bar"),
            list("notificationTapAction", R.string.vp_notificationtapaction_title,
                R.string.vp_notificationtapaction_desc,
                USER_ACTIONS, "open preferences"),
            list("notificationButton0Action", R.string.vp_notificationbutton0action_title,
                R.string.vp_notificationbutton0action_desc,
                USER_ACTIONS, "open preferences"),
            list("notificationButton1Action", R.string.vp_notificationbutton1action_title,
                R.string.vp_notificationbutton1action_desc,
                USER_ACTIONS, "exit"),
            list("mediaKeysAction", R.string.vp_mediakeysaction_title,
                R.string.vp_mediakeysaction_desc,
                USER_ACTIONS_MEDIA, "no action")
        )),
        Section(R.string.vp_section_other, listOf(
            switch("clipboardEnable", R.string.vp_clipboardenable_title,
                R.string.vp_clipboardenable_desc, true),
            switch("storeSecondaryDisplayPreferencesSeparately",
                R.string.vp_storesecondarydisplaypreferencesseparately_title,
                R.string.vp_storesecondarydisplaypreferencesseparately_desc, false),
            ActionSetting("configureResponseToUserActions",
                R.string.vp_configureresponsetouseractions_title,
                R.string.vp_configureresponsetouseractions_desc),
            ActionSetting("requestNotificationPermission",
                R.string.vp_requestnotificationpermission_title,
                R.string.vp_requestnotificationpermission_desc)
        ))
    )
}
