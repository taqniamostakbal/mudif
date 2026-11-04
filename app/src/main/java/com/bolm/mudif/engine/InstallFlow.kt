package com.bolm.mudif.engine

import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.bolm.mudif.R

/**
 * P3.3: shared Alpine install flow (download -> extract -> first boot).
 * Used by the Store's Alpine card and the profile boot-test row,
 * so both go through the exact same verified path.
 */
object InstallFlow {

    fun runAlpineInstall(fragment: Fragment, onDone: (ok: Boolean, message: String) -> Unit) {
        val ctx = fragment.requireContext()
        val density = ctx.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val status = TextView(ctx).apply {
            text = ctx.getString(R.string.downloading)
            setPadding(0, 0, 0, pad / 2)
        }
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
            progress = 0
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(bar)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.boot_test))
            .setView(layout)
            .setCancelable(false)
            .create()
        dialog.show()

        val ui = { block: () -> Unit -> fragment.activity?.runOnUiThread(block) }
        Thread {
            try {
                if (!RootfsManager.isReady(ctx)) {
                    ui { status.text = ctx.getString(R.string.downloading) }
                    RootfsManager.download(ctx) { done, total ->
                        ui {
                            if (total > 0) {
                                bar.progress = ((done * 1000) / total).toInt().coerceIn(0, 1000)
                                status.text = "${ctx.getString(R.string.downloading)} ${(done * 100) / total}%"
                            }
                        }
                    }
                    ui {
                        status.text = ctx.getString(R.string.extracting)
                        bar.isIndeterminate = true
                    }
                    RootfsManager.extract(ctx)
                }
                ui {
                    status.text = ctx.getString(R.string.booting)
                    bar.isIndeterminate = true
                }
                val result = RootfsManager.firstBoot(ctx)
                val ok = result.startsWith("Alpine خدام")
                ui {
                    dialog.dismiss()
                    onDone(ok, result)
                }
            } catch (e: Exception) {
                ui {
                    dialog.dismiss()
                    onDone(false, "خطأ: ${e.message}")
                }
            }
        }.start()
    }
}
