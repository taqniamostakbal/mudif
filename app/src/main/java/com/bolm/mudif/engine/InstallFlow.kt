package com.bolm.mudif.engine

import androidx.fragment.app.Fragment
import com.bolm.mudif.R

/**
 * Shared system install flow (download -> extract -> first boot).
 * Debian-only: Alpine was the app's first guest (Nov 2026) and its
 * install flow + UI rows were removed when the app went Debian-only.
 * runAlpineInstall was deleted 2026-11-12 (dead code, no callers).
 */
object InstallFlow {

    /**
     * P6: shared Debian install flow (download -> extract -> first boot).
     * Used by the profile's نظام مضيف row: the first tap installs
     * Debian, later taps go straight to the desktop.
     */
    fun runDebianInstall(fragment: Fragment, onDone: (ok: Boolean, message: String) -> Unit) {
        val ctx = fragment.requireContext()
        // P16: branded progress dialog (purple bar, Cairo, rounded) via
        // MudifDialog — replaces the plain AlertDialog + stock ProgressBar
        // (teal bar) the user called classic / not professional.
        val progress = com.bolm.mudif.ui.MudifDialog.progress(
            ctx, ctx.getString(R.string.system_install_title)
        )
        progress.setStatus(ctx.getString(R.string.downloading))

        val ui = { block: () -> Unit -> fragment.activity?.runOnUiThread(block) }
        // Foreground service + WakeLock: a 455 MB download must survive
        // the user leaving the app or the screen turning off.
        InstallService.start(ctx, ctx.getString(R.string.ui_service_install_system))
        Thread {
            try {
                if (!DebianSystem.isReady(ctx)) {
                    ui { progress.setStatus(ctx.getString(R.string.downloading)) }
                    DebianSystem.download(ctx) { done, total ->
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            InstallService.updateProgress(
                                "${ctx.getString(R.string.downloading)} $pct%", pct
                            )
                        }
                        ui {
                            if (total > 0) {
                                progress.setProgress(((done * 1000) / total).toInt())
                                progress.setStatus("${ctx.getString(R.string.downloading)} ${(done * 100) / total}%")
                            }
                        }
                    }
                    ui {
                        progress.setStatus(ctx.getString(R.string.extracting))
                        progress.setIndeterminate(true)
                    }
                    DebianSystem.extract(ctx)
                }
                ui {
                    progress.setStatus(ctx.getString(R.string.debian_booting))
                    progress.setIndeterminate(true)
                }
                val result = DebianSystem.firstBoot(ctx)
                val ok = result.startsWith("دبيان خدام")
                ui {
                    progress.dismiss()
                    onDone(ok, result)
                }
            } catch (e: Exception) {
                ui {
                    progress.dismiss()
                    onDone(false, ctx.getString(R.string.ui_error_prefix, e.message))
                }
            } finally {
                InstallService.stop(ctx)
            }
        }.start()
    }
}
