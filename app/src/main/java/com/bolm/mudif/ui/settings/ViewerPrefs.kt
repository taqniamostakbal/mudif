package com.bolm.mudif.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

import com.bolm.mudif.R

/**
 * P18.3, Viewer settings (Termux:X11) in Fusha Arabic, Mudif style.
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
 *   them on every launch, that silently erased user choices).
 */
object ViewerPrefs {

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    /** Mudif defaults (only written when the key is absent).
     *  migrateExact: replace a stale stored exact resolution with
     *  the adaptive one (p23). The TV path passes false: it must
     *  never rewrite the phone's stored value. */
    fun applyMudifDefaults(
        context: Context,
        exactResolution: String,
        migrateExact: Boolean = true,
    ) {
        val p = prefs(context)
        val e = p.edit()
        fun putStringIfAbsent(key: String, value: String) {
            if (!p.contains(key)) e.putString(key, value)
        }
        fun putBooleanIfAbsent(key: String, value: Boolean) {
            if (!p.contains(key)) e.putBoolean(key, value)
        }
        // Mouse fix (2026-11-12, MOUSE-PLAN Step 1): a stored "1"
        // (Trackpad: relative pointer at half finger speed) is never
        // corrected by putStringIfAbsent, so a phone that ever got "1"
        // stays on the bad mode forever. One-time migration: "1" -> "2"
        // (Simulated). Never touch "3" (Direct, a deliberate choice).
        // Runs once (flag), never on every launch.
        if (!p.getBoolean("touchModeMigratedToSimulated", false)) {
            if (p.getString("touchMode", null) == "1") {
                e.putString("touchMode", "2")
            }
            e.putBoolean("touchModeMigratedToSimulated", true)
        }
        // Keys-bar fix (2026-11-14, Anas's order): the Termux:X11 default
        // extra-keys bar is TWO tall rows (16 keys: ESC / HOME UP END
        // PGUP / TAB CTRL ALT arrows PGDN). He showed it and ordered it
        // replaced by a professional, modern, COMPACT bar. One single row
        // with only the essentials (ESC, CTRL, ALT, TAB, 4 arrows, soft
        // keyboard toggle) makes the bar half as tall. One-time migration
        // (flag) so it applies to existing installs too, but a later user
        // edit in the settings screen (extra_keys_config) is never
        // overwritten. Format = TermuxX11ExtraKeys DEFAULT_IVALUE_EXTRA_KEYS
        // (single-quoted rows); one outer element = one row.
        if (!p.getBoolean("extraKeysMigratedToCompact", false)) {
            e.putString(
                "extra_keys_config",
                "[['ESC','CTRL','ALT','TAB','LEFT','UP','DOWN','RIGHT','KEYBOARD']]"
            )
            e.putBoolean("extraKeysMigratedToCompact", true)
        }
        // Portrait fix (2026-11-16, p65, Anas's image-410): the viewer
        // opened in portrait and the landscape desktop (1170x540) was
        // stretched onto the portrait surface, so the wallpaper text
        // was huge and clipped and the desktop menu was squeezed.
        // Root cause: the fork default for forceOrientation is "auto"
        // (preferences.xml), and the fork's settings screen persists
        // that "auto" the first time it is displayed. Mudif's
        // putStringIfAbsent("forceOrientation", "landscape") below
        // only fires when the key is ABSENT, so a stored "auto" is
        // never corrected and the viewer follows device orientation
        // (MainActivity onWindowFocusChanged: "auto" means
        // UNSPECIFIED). Same failure family as the touchMode "1" bug
        // above. One-time migration: "auto" -> "landscape". Never
        // touch "portrait" or "reverse portrait" (a deliberate user
        // choice in the settings screen). Runs once (flag), never on
        // every launch, so a later user change is never overwritten.
        if (!p.getBoolean("forceOrientationMigratedToLandscape", false)) {
            if (p.getString("forceOrientation", null) == "auto") {
                e.putString("forceOrientation", "landscape")
            }
            e.putBoolean("forceOrientationMigratedToLandscape", true)
        }
        // Adaptive scale (2026-11-14, p23): the guest resolution is
        // computed from the real screen (DesktopScale), so a stored
        // exact resolution from an old default must not survive.
        // Only the three values Mudif itself ever wrote as a default
        // are replaced (1280x1024 fork default, 1280x720 P6.5 pin,
        // 640x360 halved default): any other stored value is a
        // deliberate user choice in the settings screen and is never
        // touched. Runs once (flag), never on every launch. The TV
        // path passes migrateExact=false so it never rewrites the
        // phone's value.
        if (migrateExact && !p.getBoolean("exactMigratedToAdaptive", false)) {
            if (p.getString("displayResolutionExact", null) in
                setOf("1280x1024", "1280x720", "640x360")
            ) {
                e.putString("displayResolutionExact", exactResolution)
            }
            e.putBoolean("exactMigratedToAdaptive", true)
        }
        putStringIfAbsent("touchMode", "2")              // Simulated touchscreen
        putStringIfAbsent("displayResolutionMode", "exact")
        putStringIfAbsent("displayResolutionExact", exactResolution)
        // Upscale filter (2026-11-14, p23): the fork default is
        // "nearest" (GL_NEAREST), which makes the 2.0x upscale
        // blocky. "bilinear" maps to GL_LINEAR (LorieView.java:809),
        // a smooth upscale. Only written when absent, so a user who
        // deliberately picked nearest keeps it.
        putStringIfAbsent("displayFilteringMode", "bilinear")
        putStringIfAbsent("forceOrientation", "landscape")
        putBooleanIfAbsent("adjustResolution", false)
        putBooleanIfAbsent("displayStretch", true)
        putBooleanIfAbsent("fullscreen", true)
        putBooleanIfAbsent("tapToMove", false)
        putBooleanIfAbsent("additionalKbdVisible", false)
        e.apply()
        // Log the effective mode so the rig proves it (MOUSE-PLAN Step 1).
        Log.d("Mudif", "touchMode=" + (p.getString("touchMode", "?") ?: "?"))
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
        )),
        // p22 - agent API keys (free cloud providers). The values are
        // written to the guest's keys.env by AgentServer.writeKeysEnv
        // and sourced by agent-start.sh before opencode starts. An
        // empty key simply means that provider stays unavailable;
        // the local brain always works without any key.
        Section(R.string.vp_section_agent_keys, listOf(
            text("agent_key_groq", R.string.vp_agent_key_groq_title,
                R.string.vp_agent_key_groq_desc, ""),
            text("agent_key_hf", R.string.vp_agent_key_hf_title,
                R.string.vp_agent_key_hf_desc, ""),
            text("agent_key_llm7", R.string.vp_agent_key_llm7_title,
                R.string.vp_agent_key_llm7_desc, ""),
            text("agent_key_openrouter", R.string.vp_agent_key_openrouter_title,
                R.string.vp_agent_key_openrouter_desc, ""),
            // p31 - the three new free providers (Gemini, Cohere,
            // Mistral). Same shape as the four above.
            text("agent_key_gemini", R.string.vp_agent_key_gemini_title,
                R.string.vp_agent_key_gemini_desc, ""),
            text("agent_key_cohere", R.string.vp_agent_key_cohere_title,
                R.string.vp_agent_key_cohere_desc, ""),
            text("agent_key_mistral", R.string.vp_agent_key_mistral_title,
                R.string.vp_agent_key_mistral_desc, ""),
            // Brain fix phase 2: AMD Token Lab (free key, 10 credits, 30 days).
            text("agent_key_tokenlab", R.string.vp_agent_key_tokenlab_title,
                R.string.vp_agent_key_tokenlab_desc, ""),
            // Brain fix phase 2: search keys for the agent's own browser.
            text("agent_key_exa", R.string.vp_agent_key_exa_title,
                R.string.vp_agent_key_exa_desc, ""),
            text("agent_key_tavily", R.string.vp_agent_key_tavily_title,
                R.string.vp_agent_key_tavily_desc, "")
        ))
    )
}
