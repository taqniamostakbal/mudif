package com.bolm.mudif.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.bolm.mudif.ui.MudifActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.bolm.mudif.R
import com.bolm.mudif.engine.X11Session
import com.bolm.mudif.ui.MudifDialog
import com.termux.x11.utils.KeyInterceptor
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * P18.3, Viewer settings screen (Termux:X11), Fusha Arabic, Mudif style.
 * Every change is saved immediately to the same prefs file the viewer reads
 * (com.bolm.mudif_preferences); the viewer's OnSharedPreferenceChangeListener
 * applies it live, no restart needed.
 */
class SettingsActivity : MudifActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) getString(R.string.ui_settings_notif_granted)
                else getString(R.string.ui_settings_notif_denied),
                Toast.LENGTH_SHORT
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.settings_back).setOnClickListener { finish() }

        // Apply Mudif defaults on first open (only for absent keys).
        // The first-launch exact resolution comes from
        // X11Session.viewerResolution (height 540, aspect-matched) so
        // this entry point agrees with openViewer instead of
        // computing its own (contradictory) 720-based resolution.
        ViewerPrefs.applyMudifDefaults(this, X11Session.viewerResolution(this))

        buildSections()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    /** Resolve a Material theme color (adapts to dark mode). The
     *  programmatic rows used hardcoded #1A1A1A / #777777 / white,
     *  which ignore the theme and dark mode; theme colors keep the
     *  whole screen in the app's style. */
    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun buildSections() {
        val container = findViewById<LinearLayout>(R.id.settings_container)
        container.removeAllViews()
        buildLanguageCard(container)
        for (section in ViewerPrefs.SECTIONS) {
            val title = TextView(this).apply {
                text = getString(section.titleRes)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dp(4), dp(18), dp(4), dp(8))
            }
            container.addView(title)

            // p56: the section card is a purple gradient card. The
            // MaterialCardView itself is transparent; the gradient
            // lives on the body layout (14dp, the Compact radius),
            // the same pattern as the home stat cards (p54).
            // Ripple comes from the theme's selectableItemBackground.
            val ripple = TypedValue().let { out ->
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                ContextCompat.getDrawable(this, out.resourceId)
            }
            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(2).toFloat()
                setCardBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = true
                foreground = ripple
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(
                    this@SettingsActivity, R.animator.card_press
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val cardBody = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(
                    this@SettingsActivity, R.drawable.card_gradient_compact
                )
                setPadding(dp(18), dp(6), dp(18), dp(6))
            }
            section.settings.forEachIndexed { index, setting ->
                cardBody.addView(buildRow(setting))
                if (index < section.settings.lastIndex) {
                    cardBody.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                        ).apply { setMargins(0, dp(4), 0, dp(4)) }
                        setBackgroundColor(
                            ContextCompat.getColor(
                                this@SettingsActivity, R.color.card_divider
                            )
                        )
                    })
                }
            }
            card.addView(cardBody)
            container.addView(card)
        }

        // Mouse explainer footer (the tap mapping the user asked about).
        val footer = TextView(this).apply {
            text = getString(R.string.viewer_settings_mouse_hint)
            textSize = 13f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(4), dp(18), dp(4), dp(24))
        }
        container.addView(footer)
    }

    /** App-level language card, above the viewer sections. Same
     *  spinner pattern as ListSetting (initial callback ignored);
     *  picking a language applies it and recreates the activity. */
    private fun buildLanguageCard(container: LinearLayout) {
        val title = TextView(this).apply {
            text = getString(R.string.language_title)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(4), dp(18), dp(4), dp(8))
        }
        container.addView(title)
        // p56: the language card is a purple gradient card too
        // (transparent MaterialCardView, gradient on the body).
        val langRipple = TypedValue().let { out ->
            theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            ContextCompat.getDrawable(this, out.resourceId)
        }
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            foreground = langRipple
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(
                this@SettingsActivity, R.animator.card_press
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val cardBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(
                this@SettingsActivity, R.drawable.card_gradient_compact
            )
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val desc = TextView(this).apply {
            text = getString(R.string.language_desc)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.card_text_secondary))
        }
        cardBody.addView(desc)
        val labels = listOf(
            getString(R.string.language_fusha),
            getString(R.string.language_darija),
            getString(R.string.language_english)
        )
        val tags = AppLanguage.TAGS
        // Branded picker: a full-width button showing the current
        // language opens AppLanguage's branded dialog. The old
        // Spinner popup was unstyled and could be clipped by the
        // screen edge in RTL (the user's screenshot).
        val currentLabel = labels[tags.indexOf(AppLanguage.currentTag(this)).coerceAtLeast(0)]
        val button = LayoutInflater.from(this).inflate(
            R.layout.item_select_option, cardBody, false
        ) as MaterialButton
        button.text = "$currentLabel ▾"
        button.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        button.setOnClickListener { AppLanguage.showPicker(this) }
        cardBody.addView(button)
        card.addView(cardBody)
        container.addView(card)
    }

    private fun rowTexts(setting: ViewerPrefs.Setting): Pair<TextView, TextView> {
        val title = TextView(this).apply {
            text = getString(setting.titleRes)
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, android.R.color.white))
        }
        val desc = TextView(this).apply {
            text = getString(setting.descRes)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.card_text_secondary))
        }
        return title to desc
    }

    private fun buildRow(setting: ViewerPrefs.Setting): View {
        val prefs = ViewerPrefs.prefs(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val (title, desc) = rowTexts(setting)
        texts.addView(title)
        texts.addView(desc)
        top.addView(texts)

        when (setting) {
            is ViewerPrefs.SwitchSetting -> {
                // p64: MaterialSwitch (M3) replaces the obsolete
                // SwitchMaterial (its own javadoc says so). It
                // animates the thumb slide, grows the thumb 16 to
                // 24dp, and cross-fades the track color with the
                // thumb position (blendARGB), so the state change is
                // a motion, not a snap. ON track is Mudif purple
                // (#593BFE) with a white thumb; OFF is a frosted
                // neutral, so the two states are unmistakable on
                // the purple card. The old style used one white
                // thumb + one translucent track for both states,
                // so ON and OFF looked identical (Anas's report).
                val sw = MaterialSwitch(this).apply {
                    isChecked = prefs.getBoolean(setting.key, setting.default)
                    thumbTintList = ContextCompat.getColorStateList(
                        this@SettingsActivity, R.color.switch_thumb_mudif
                    )
                    trackTintList = ContextCompat.getColorStateList(
                        this@SettingsActivity, R.color.switch_track_mudif
                    )
                    trackDecorationTintList = ContextCompat.getColorStateList(
                        this@SettingsActivity, R.color.switch_track_decoration_mudif
                    )
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean(setting.key, checked).apply()
                        // p67: this switch used to write the pref and stop
                        // there, so the viewer then demanded an ADB grant
                        // to auto-start its accessibility service (the
                        // card Anas reported). The service is manual now:
                        // on ON, open Android Settings > Accessibility
                        // and tell the user what to enable there, unless
                        // the service is already running. A normal user
                        // action, no ADB, no Developer options. On OFF,
                        // only the pref changes.
                        if (setting.key == "enableAccessibilityServiceAutomatically" && checked
                            && !KeyInterceptor.isLaunched()
                        ) {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            Toast.makeText(
                                this@SettingsActivity,
                                getString(R.string.vp_accessibility_manual_toast),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                top.addView(sw)
            }
            is ViewerPrefs.ListSetting -> {
                // Label resource 0 means the label IS the value
                // (the resolution list, e.g. "1280x720").
                val labels = setting.options.map {
                    if (it.second == 0) it.first else getString(it.second)
                }
                val values = setting.options.map { it.first }
                val current = prefs.getString(setting.key, setting.default) ?: setting.default
                // Branded picker (MudifDialog.select): a full-width
                // button showing the current value opens the branded
                // dialog. The old Spinner popup was unstyled and could
                // be clipped by the screen edge in RTL. The button goes
                // BELOW the texts, full width (P18.5 vertical pattern).
                val currentLabel = labels[(values.indexOf(current)).coerceAtLeast(0)]
                val button = LayoutInflater.from(this).inflate(
                    R.layout.item_select_option, row, false
                ) as MaterialButton
                button.text = "$currentLabel ▾"
                button.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                button.setOnClickListener {
                    MudifDialog.select(
                        this,
                        getString(setting.titleRes),
                        labels,
                        (values.indexOf(current)).coerceAtLeast(0)
                    ) { index ->
                        prefs.edit().putString(setting.key, values[index]).apply()
                        button.text = "${labels[index]} ▾"
                    }
                }
                row.addView(top)
                row.addView(button)
                return row
            }
            is ViewerPrefs.SliderSetting -> {
                // Slider goes below the texts (full width).
                val valueText = TextView(this).apply {
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    setTypeface(typeface, Typeface.BOLD)
                }
                val seek = SeekBar(this).apply {
                    max = setting.max - setting.min
                    // p56: white thumb + track on the purple card
                    // (the brand purple thumb would vanish on it).
                    progressTintList = ColorStateList.valueOf(Color.WHITE)
                    thumbTintList = ColorStateList.valueOf(Color.WHITE)
                    val current = prefs.getInt(setting.key, setting.default)
                        .coerceIn(setting.min, setting.max)
                    progress = current - setting.min
                    valueText.text = current.toString()
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar?, progress: Int, fromUser: Boolean
                        ) {
                            valueText.text = (setting.min + progress).toString()
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {
                            val v = setting.min + (seekBar?.progress ?: 0)
                            prefs.edit().putInt(setting.key, v).apply()
                        }
                    })
                }
                val sliderRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                }
                sliderRow.addView(seek, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                sliderRow.addView(valueText)
                row.addView(top)
                row.addView(sliderRow)
                // The scale is ignored when the resolution mode is
                // exact/custom (the viewer pins the size instead).
                if (setting.key == "displayScale") {
                    row.addView(TextView(this).apply {
                        text = getString(R.string.viewer_scale_ignored_note)
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.card_text_secondary))
                        setPadding(0, dp(6), 0, 0)
                    })
                }
                return row
            }
            is ViewerPrefs.TextSetting -> {
                val edit = EditText(this).apply {
                    setText(prefs.getString(setting.key, setting.default) ?: setting.default)
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    // Mudif input style (same as Widget.Mudif.SearchBox):
                    // Cairo, theme text colors, rounded surface background.
                    typeface = ResourcesCompat.getFont(context, R.font.cairo)
                    setTextColor(this@SettingsActivity.themeColor(com.google.android.material.R.attr.colorOnSurface))
                    setHintTextColor(this@SettingsActivity.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setBackgroundResource(R.drawable.search_box_bg)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setOnEditorActionListener { v, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            prefs.edit().putString(setting.key, v.text.toString()).apply()
                            Toast.makeText(
                                this@SettingsActivity, getString(R.string.ui_settings_saved), Toast.LENGTH_SHORT
                            ).show()
                            clearFocus()
                            true
                        } else false
                    }
                    setOnFocusChangeListener { v, hasFocus ->
                        if (!hasFocus) {
                            prefs.edit().putString(setting.key, (v as EditText).text.toString()).apply()
                        }
                    }
                }
                row.addView(top)
                row.addView(edit)
                return row
            }
            is ViewerPrefs.ActionSetting -> {
                val chevron = TextView(this).apply {
                    text = "‹"
                    textSize = 24f
                    setTextColor(ContextCompat.getColor(context, android.R.color.white))
                }
                top.addView(chevron)
                top.setOnClickListener { runAction(setting.key) }
            }
        }
        row.addView(top)
        return row
    }

    private fun runAction(key: String) {
        when (key) {
            "enableAccessibilityService" -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            "requestNotificationPermission" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        Toast.makeText(this, getString(R.string.ui_settings_notif_already), Toast.LENGTH_SHORT).show()
                    } else {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    Toast.makeText(this, getString(R.string.ui_settings_notif_not_needed), Toast.LENGTH_SHORT).show()
                }
            }
            "configureResponseToUserActions" -> {
                // Scroll to the User actions section (it is section index 4).
                // Title(0) + card(1) per section, plus the language
                // title + card (2 children) prepended above them;
                // section 4 starts at child 10.
                val scroll = findViewById<ScrollView>(R.id.settings_scroll)
                val container = findViewById<LinearLayout>(R.id.settings_container)
                // Language title(0) + card(1), then Title+card per
                // section; section 4 starts at child 10.
                val target = container.getChildAt(10)
                target?.let { scroll.post { scroll.smoothScrollTo(0, it.top) } }
            }
        }
    }
}
