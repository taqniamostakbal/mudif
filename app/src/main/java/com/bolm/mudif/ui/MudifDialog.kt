package com.bolm.mudif.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.bolm.mudif.R
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
