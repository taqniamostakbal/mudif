package com.bolm.mudif.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.bolm.mudif.R
import com.bolm.mudif.engine.DebianSystem
import com.google.android.material.button.MaterialButton

/**
 * P16: the ONE branded dialog for the whole app. Every dialog in Mudif
 * (install progress, install/verify failures, engine check, desktop
 * diagnostics, failure reports) goes through here, so none of them can
 * fall back to the plain Android default (white box, teal bar) that the
 * user called "classic, not professional".
 *
 * Style: rounded white card (dialog_mudif_bg), Cairo font, purple
 * (#593BFE) positive button + purple progress bar, dark title.
 * The window background is set after show() so the corners are visible.
 */
object MudifDialog {

    /** One info dialog: title + message + 1-3 buttons. Null buttons are hidden. */
    fun info(
        ctx: Context,
        title: String,
        message: String,
        positive: String,
        onPositive: (() -> Unit)? = null,
        neutral: String? = null,
        onNeutral: (() -> Unit)? = null,
        negative: String? = null,
        onNegative: (() -> Unit)? = null,
        cancelable: Boolean = true
    ): AlertDialog {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_mudif_info, null)
        view.findViewById<TextView>(R.id.dialog_title).text = title
        view.findViewById<TextView>(R.id.dialog_message).text = message
        val dialog = AlertDialog.Builder(ctx)
            .setView(view)
            .setCancelable(cancelable)
            .create()
        val btnPositive = view.findViewById<MaterialButton>(R.id.dialog_btn_positive)
        btnPositive.text = positive
        btnPositive.setOnClickListener { dialog.dismiss(); onPositive?.invoke() }
        val btnNeutral = view.findViewById<MaterialButton>(R.id.dialog_btn_neutral)
        if (neutral != null) {
            btnNeutral.visibility = View.VISIBLE
            btnNeutral.text = neutral
            btnNeutral.setOnClickListener { dialog.dismiss(); onNeutral?.invoke() }
        }
        val btnNegative = view.findViewById<MaterialButton>(R.id.dialog_btn_negative)
        if (negative != null) {
            btnNegative.visibility = View.VISIBLE
            btnNegative.text = negative
            btnNegative.setOnClickListener { dialog.dismiss(); onNegative?.invoke() }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(ctx, R.drawable.dialog_mudif_bg)
        )
        return dialog
    }

    /** Branded single-choice dialog: title + one full-width button
     *  per option (Secondary outlined purple; the current one filled
     *  purple). Replaces Spinner dropdowns, whose system popup is
     *  unstyled and can be clipped by the screen edge in RTL.
     *  Choosing dismisses the dialog and calls [onSelect]. */
    fun select(
        ctx: Context,
        title: String,
        options: List<String>,
        current: Int,
        message: String? = null,
        onSelect: (Int) -> Unit
    ): AlertDialog {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_mudif_select, null)
        view.findViewById<TextView>(R.id.dialog_title).text = title
        val messageView = view.findViewById<TextView>(R.id.dialog_message)
        if (message != null) {
            messageView.text = message
            messageView.visibility = View.VISIBLE
        }
        val dialog = AlertDialog.Builder(ctx)
            .setView(view)
            .setCancelable(true)
            .create()
        val container = view.findViewById<LinearLayout>(R.id.dialog_options)
        for ((index, label) in options.withIndex()) {
            val button = LayoutInflater.from(ctx).inflate(
                R.layout.item_select_option, container, false
            ) as MaterialButton
            button.text = label
            button.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) topMargin = dp(ctx, 8) }
            if (index == current) {
                // Current choice: filled purple, white text (Primary look).
                button.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, R.color.mudif_purple)
                button.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            }
            button.setOnClickListener { dialog.dismiss(); onSelect(index) }
            container.addView(button)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(ctx, R.drawable.dialog_mudif_bg)
        )
        return dialog
    }

    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
        ).toInt()

    /** Branded About dialog (Phase 1a): the same identity as the
     *  desktop mudif-about — the user's avatar, the channel credit,
     *  the app + image versions, and the GitHub/YouTube links.
     *  This replaces the version-only dialog. */
    fun about(ctx: Context): AlertDialog {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_mudif_about, null)
        val imageVersion = DebianSystem.installedImageVersion(ctx)
        val appVersion = appVersion(ctx)
        val versionText = if (imageVersion != null) {
            ctx.getString(R.string.ui_about_version, appVersion, imageVersion)
        } else {
            ctx.getString(R.string.ui_about_version_app, appVersion)
        }
        view.findViewById<TextView>(R.id.about_version).text = versionText
        val dialog = AlertDialog.Builder(ctx)
            .setView(view)
            .setCancelable(true)
            .create()
        view.findViewById<MaterialButton>(R.id.about_btn_github).setOnClickListener {
            openLink(ctx, "https://github.com/taqniamostakbal/mudif")
        }
        view.findViewById<MaterialButton>(R.id.about_btn_youtube).setOnClickListener {
            openLink(ctx, "https://www.youtube.com/@taqniaMostakbal")
        }
        view.findViewById<MaterialButton>(R.id.about_btn_ok).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(ctx, R.drawable.dialog_mudif_bg)
        )
        return dialog
    }

    /**
     * App version from the package itself. BuildConfig is NOT
     * generated (AGP 8 defaults: buildFeatures has viewBinding
     * only), so the version comes from PackageManager, never from
     * a BuildConfig reference.
     */
    private fun appVersion(ctx: Context): String {
        return try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.1.0"
        } catch (_: Exception) {
            "0.1.0"
        }
    }

    private fun openLink(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // No browser available: do nothing (the dialog stays open).
        }
    }

    /** Progress dialog handle: status text + bar (0..1000) or indeterminate. */
    class Progress(private val dialog: AlertDialog, view: View) {
        private val status: TextView = view.findViewById(R.id.dialog_status)
        private val bar: ProgressBar = view.findViewById(R.id.dialog_bar)

        fun setStatus(text: String) {
            status.text = text
        }

        fun setProgress(value: Int) {
            bar.isIndeterminate = false
            bar.progress = value.coerceIn(0, 1000)
        }

        fun setIndeterminate(indeterminate: Boolean) {
            bar.isIndeterminate = indeterminate
        }

        fun dismiss() {
            dialog.dismiss()
        }
    }

    /** Branded progress dialog (non-cancelable): title + status + purple bar. */
    fun progress(ctx: Context, title: String): Progress {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_mudif_progress, null)
        view.findViewById<TextView>(R.id.dialog_title).text = title
        val dialog = AlertDialog.Builder(ctx)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(ctx, R.drawable.dialog_mudif_bg)
        )
        return Progress(dialog, view)
    }
}
