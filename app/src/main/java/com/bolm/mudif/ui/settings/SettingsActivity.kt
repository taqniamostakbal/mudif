package com.bolm.mudif.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bolm.mudif.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.roundToInt

/**
 * P18.3 — Viewer settings screen (Termux:X11), Fusha Arabic, Mudif style.
 * Every change is saved immediately to the same prefs file the viewer reads
 * (com.bolm.mudif_preferences); the viewer's OnSharedPreferenceChangeListener
 * applies it live, no restart needed.
 */
class SettingsActivity : AppCompatActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) "تم منح إذن الإشعارات" else "لم يتم منح إذن الإشعارات",
                Toast.LENGTH_SHORT
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.settings_back).setOnClickListener { finish() }

        // Apply Mudif defaults on first open (only for absent keys).
        // Same aspect math as X11Session.openViewer (long/short sides,
        // height 720, width rounded to even) so both entry points agree.
        val metrics = resources.displayMetrics
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
        var width = (720.0 * longSide / shortSide).roundToInt()
        if (width % 2 != 0) width += 1
        ViewerPrefs.applyMudifDefaults(this, "${width}x720")

        buildSections()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    private fun buildSections() {
        val container = findViewById<LinearLayout>(R.id.settings_container)
        container.removeAllViews()
        for (section in ViewerPrefs.SECTIONS) {
            val title = TextView(this).apply {
                text = section.title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#1A1A1A"))
                setPadding(dp(4), dp(18), dp(4), dp(8))
            }
            container.addView(title)

            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(2).toFloat()
                setCardBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val cardBody = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(6), dp(18), dp(6))
            }
            section.settings.forEachIndexed { index, setting ->
                cardBody.addView(buildRow(setting))
                if (index < section.settings.lastIndex) {
                    cardBody.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                        ).apply { setMargins(0, dp(4), 0, dp(4)) }
                        setBackgroundColor(Color.parseColor("#EEEEEE"))
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
            setTextColor(Color.parseColor("#777777"))
            setPadding(dp(4), dp(18), dp(4), dp(24))
        }
        container.addView(footer)
    }

    private fun rowTexts(setting: ViewerPrefs.Setting): Pair<TextView, TextView> {
        val title = TextView(this).apply {
            text = setting.title
            textSize = 16f
            setTextColor(Color.parseColor("#1A1A1A"))
        }
        val desc = TextView(this).apply {
            text = setting.desc
            textSize = 13f
            setTextColor(Color.parseColor("#777777"))
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
                val sw = SwitchMaterial(this).apply {
                    isChecked = prefs.getBoolean(setting.key, setting.default)
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean(setting.key, checked).apply()
                    }
                }
                top.addView(sw)
            }
            is ViewerPrefs.ListSetting -> {
                val labels = setting.options.map { it.second }
                val values = setting.options.map { it.first }
                val current = prefs.getString(setting.key, setting.default) ?: setting.default
                val spinner = Spinner(this).apply {
                    val spinnerAdapter = ArrayAdapter(
                        this@SettingsActivity,
                        android.R.layout.simple_spinner_item, labels
                    ).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    adapter = spinnerAdapter
                    setSelection((values.indexOf(current)).coerceAtLeast(0))
                    // Ignore the initial selection callback.
                    var first = true
                    onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?, view: View?,
                            position: Int, id: Long
                        ) {
                            if (first) { first = false; return }
                            prefs.edit().putString(setting.key, values[position]).apply()
                        }
                        override fun onNothingSelected(
                            parent: android.widget.AdapterView<*>?
                        ) {}
                    }
                }
                top.addView(spinner)
            }
            is ViewerPrefs.SliderSetting -> {
                // Slider goes below the texts (full width).
                val valueText = TextView(this).apply {
                    textSize = 14f
                    setTextColor(Color.parseColor("#593BFE"))
                    setTypeface(typeface, Typeface.BOLD)
                }
                val seek = SeekBar(this).apply {
                    max = setting.max - setting.min
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
                return row
            }
            is ViewerPrefs.TextSetting -> {
                val edit = EditText(this).apply {
                    setText(prefs.getString(setting.key, setting.default) ?: setting.default)
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnEditorActionListener { v, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            prefs.edit().putString(setting.key, v.text.toString()).apply()
                            Toast.makeText(
                                this@SettingsActivity, "تم الحفظ", Toast.LENGTH_SHORT
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
                    setTextColor(Color.parseColor("#593BFE"))
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
                        Toast.makeText(this, "إذن الإشعارات ممنوح مسبقا", Toast.LENGTH_SHORT).show()
                    } else {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    Toast.makeText(this, "لا حاجة لهذا الإذن في هذا الإصدار", Toast.LENGTH_SHORT).show()
                }
            }
            "configureResponseToUserActions" -> {
                // Scroll to the User actions section (it is section index 4).
                val scroll = findViewById<ScrollView>(R.id.settings_scroll)
                val container = findViewById<LinearLayout>(R.id.settings_container)
                // Title(0) + card(1) per section; section 4 starts at child 8.
                val target = container.getChildAt(8)
                target?.let { scroll.post { scroll.smoothScrollTo(0, it.top) } }
            }
        }
    }
}
