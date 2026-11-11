package com.bolm.mudif.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentProfileBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.EngineCheck
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.InstallFlow
import com.bolm.mudif.engine.InstallService
import com.bolm.mudif.engine.RootfsManager
import com.bolm.mudif.engine.SystemBackup
import com.bolm.mudif.engine.SystemUpdate
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.control.TaskManagerActivity
import com.bolm.mudif.ui.logs.LogViewerActivity
import com.bolm.mudif.ui.settings.SettingsActivity
import com.bolm.mudif.ui.terminal.TerminalActivity
import com.bolm.mudif.ui.vnc.VncActivity
import com.google.android.material.snackbar.Snackbar
import java.io.File

class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    /**
     * Real-computer #2 — restore picker (SAF). The user picks a
     * .tar.xz backup file; runRestore copies it to a temp file and
     * verifies its CONTENT before wiping the system.
     */
    private val restorePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) runRestore(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val soon = View.OnClickListener {
            Snackbar.make(binding.root, getString(R.string.coming_soon), Snackbar.LENGTH_SHORT).show()
        }
        binding.rowLanguage.setOnClickListener(soon)
        binding.rowAbout.setOnClickListener(soon)
        // P18.3 — Viewer settings screen (Fusha, Mudif style).
        binding.rowViewerSettings.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.cardSystems.setOnClickListener(soon)
        binding.cardApps.setOnClickListener(soon)
        binding.rowEngine.setOnClickListener {
            Snackbar.make(binding.root, getString(R.string.engine_running), Snackbar.LENGTH_SHORT).show()
            Thread {
                val result = EngineCheck.runProotVersion(requireContext())
                activity?.runOnUiThread {
                    // P16: branded dialog.
                    MudifDialog.info(
                        requireContext(),
                        getString(R.string.engine_check),
                        result,
                        getString(R.string.ok)
                    )
                }
            }.start()
        }
        refreshCounters()
        binding.rowTerminal.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), TerminalActivity::class.java))
        }
        // P6.4 — نظام مضيف is the ONLY system in this app (the
        // user's order 2026-11-07). The Alpine desktop rows
        // (row_vnc LXQt, row_vnc_xfce XFCE) and the Alpine boot
        // test (row_boot) are GONE — nothing else launches a
        // desktop from this tab.
        // P6 — نظام مضيف: the ONE system (Debian 13.1 + LXQt). First
        // tap downloads + extracts Debian (35 MB, hash-verified),
        // later taps go straight to the desktop.
        binding.rowVncMudif.setOnClickListener {
            if (!DebianSystem.isReady(requireContext())) {
                InstallFlow.runDebianInstall(this) { ok, message ->
                    if (ok) {
                        startActivity(
                            android.content.Intent(requireContext(), VncActivity::class.java).apply {
                                putExtra(VncActivity.EXTRA_DESKTOP, VncActivity.DESKTOP_MUDIF)
                            }
                        )
                    } else {
                        // P16: branded dialog.
                        MudifDialog.info(
                            requireContext(),
                            getString(R.string.system_install_title),
                            message,
                            getString(R.string.ok)
                        )
                    }
                }
            } else {
                startActivity(
                    android.content.Intent(requireContext(), VncActivity::class.java).apply {
                        putExtra(VncActivity.EXTRA_DESKTOP, VncActivity.DESKTOP_MUDIF)
                    }
                )
            }
        }
        binding.rowLogs.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), LogViewerActivity::class.java))
        }

        // Real-computer #3 — shared files folder. The row shows the
        // Android path; tap copies it to the clipboard. Inside the
        // guest the same folder is /mnt/mudif-shared (bound at boot).
        binding.textSharedFolderPath.text =
            RootfsManager.sharedDir(requireContext()).absolutePath
        binding.rowSharedFolder.setOnClickListener {
            val path = RootfsManager.sharedDir(requireContext()).absolutePath
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("mudif-shared", path))
            android.widget.Toast.makeText(
                requireContext(), getString(R.string.shared_folder_copied), android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        // Real-computer #4 — in-app system update (apt update +
        // upgrade, no image reinstall). Runs off the UI thread; the
        // progress dialog shows the live apt output, and the result
        // dialog shows the real tail of it — never a fake "done".
        binding.rowSystemUpdate.setOnClickListener {
            val ctx = requireContext()
            val progress = MudifDialog.progress(ctx, getString(R.string.system_update_title))
            progress.setStatus(getString(R.string.system_update_running))
            progress.setIndeterminate(true)
            // Foreground service + WakeLock: apt upgrade runs minutes.
            InstallService.start(ctx, "تحديث النظام…")
            Thread {
                val result = SystemUpdate.run(ctx) { line ->
                    activity?.runOnUiThread { progress.setStatus(line.take(120)) }
                }
                activity?.runOnUiThread {
                    progress.dismiss()
                    InstallService.stop(ctx)
                    if (!isAdded) return@runOnUiThread
                    val tail = result.output.takeLast(1200).ifBlank { "(لا يوجد إخراج)" }
                    MudifDialog.info(
                        ctx,
                        if (result.exitCode == 0) getString(R.string.system_update_done)
                        else getString(R.string.system_update_failed_title),
                        tail,
                        getString(R.string.ok)
                    )
                }
            }.start()
        }

        // Real-computer #2 — backup / restore. Backup stops the
        // desktop first (a live system cannot be tarred safely);
        // restore goes through the file picker and is verified by
        // content, not by name.
        binding.rowBackup.setOnClickListener {
            MudifDialog.info(
                requireContext(),
                getString(R.string.backup_title),
                getString(R.string.backup_choose),
                getString(R.string.backup_create),
                onPositive = { runBackup() },
                neutral = getString(R.string.backup_restore),
                onNeutral = { restorePicker.launch(arrayOf("*/*")) },
                negative = getString(R.string.cancel)
            )
        }

        // Real-computer #10 — guest task manager (processes + kill).
        binding.rowTaskManager.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), TaskManagerActivity::class.java))
        }
    }

    /**
     * Real-computer #2 — create a backup. If the desktop is running
     * it is stopped first, after a confirm (a live rootfs cannot be
     * tarred safely — files change under the walk). The progress bar
     * is real bytes/total; the result dialog shows the real file.
     */
    private fun runBackup() {
        val ctx = requireContext()
        if (GuestSession.alive) {
            MudifDialog.info(
                ctx,
                getString(R.string.backup_title),
                getString(R.string.backup_desktop_running),
                getString(R.string.ok),
                onPositive = {
                    GuestSession.stop()
                    runBackup()
                },
                negative = getString(R.string.cancel)
            )
            return
        }
        val progress = MudifDialog.progress(ctx, getString(R.string.backup_title))
        progress.setStatus(getString(R.string.backup_running))
        // Foreground service + WakeLock: tar+xz of the whole rootfs.
        InstallService.start(ctx, "نسخ احتياطي…")
        Thread {
            try {
                val file = SystemBackup.backup(ctx) { done, total ->
                    if (total > 0) {
                        InstallService.updateProgress(
                            "نسخ احتياطي… ${(done * 100) / total}%", ((done * 100) / total).toInt()
                        )
                    }
                    activity?.runOnUiThread {
                        if (total > 0) progress.setProgress(((done * 1000) / total).toInt())
                        else progress.setIndeterminate(true)
                    }
                }
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    MudifDialog.info(
                        ctx,
                        getString(R.string.backup_title),
                        getString(R.string.backup_done, file.absolutePath),
                        getString(R.string.ok)
                    )
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    MudifDialog.info(
                        ctx,
                        getString(R.string.backup_failed_title),
                        e.message ?: "خطأ غير معروف",
                        getString(R.string.ok)
                    )
                }
            } finally {
                InstallService.stop(ctx)
            }
        }.start()
    }

    /**
     * Real-computer #2 — restore from a picked file. The confirm
     * says plainly that the current system is wiped. The desktop
     * is stopped first (the confirm covers it: the system is being
     * replaced). The file is copied to a temp file first because
     * SystemBackup.restore reads a File, and the temp file is
     * deleted afterwards either way.
     */
    private fun runRestore(uri: Uri) {
        val ctx = requireContext()
        MudifDialog.info(
            ctx,
            getString(R.string.backup_title),
            getString(R.string.backup_restore_confirm),
            getString(R.string.ok),
            onPositive = {
                val tmp = File(ctx.cacheDir, "mudif-restore.tar.xz")
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    } ?: throw RuntimeException("تعذر قراءة الملف المختار")
                } catch (e: Exception) {
                    MudifDialog.info(
                        ctx,
                        getString(R.string.backup_restore_failed_title),
                        e.message ?: "خطأ غير معروف",
                        getString(R.string.ok)
                    )
                    return@info
                }
                if (GuestSession.alive) GuestSession.stop()
                val progress = MudifDialog.progress(ctx, getString(R.string.backup_title))
                progress.setStatus(getString(R.string.backup_restore_running))
                Thread {
                    try {
                        SystemBackup.restore(ctx, tmp) { done, total ->
                            activity?.runOnUiThread {
                                if (total > 0) progress.setProgress(((done * 1000) / total).toInt())
                                else progress.setIndeterminate(true)
                            }
                        }
                        tmp.delete()
                        activity?.runOnUiThread {
                            progress.dismiss()
                            if (!isAdded) return@runOnUiThread
                            MudifDialog.info(
                                ctx,
                                getString(R.string.backup_title),
                                getString(R.string.backup_restore_done),
                                getString(R.string.ok)
                            )
                        }
                    } catch (e: Exception) {
                        tmp.delete()
                        activity?.runOnUiThread {
                            progress.dismiss()
                            if (!isAdded) return@runOnUiThread
                            MudifDialog.info(
                                ctx,
                                getString(R.string.backup_restore_failed_title),
                                e.message ?: "خطأ غير معروف",
                                getString(R.string.ok)
                            )
                        }
                    }
                }.start()
            },
            negative = getString(R.string.cancel)
        )
    }

    override fun onResume() {
        super.onResume()
        // e.g. Debian installed from the Store tab -> counters must move
        refreshCounters()
    }

    /**
     * P6.1 — the counters were hardcoded "0" in the layout while
     * Alpine + Firefox + LibreOffice were already installed (the tabs
     * test proved it). They now read real state.
     * P6.2 FIX (P6.1 test): /usr/bin/soffice is an ABSOLUTE symlink
     * to /usr/lib/libreoffice/program/soffice, which does NOT resolve
     * on the Android host (File.exists follows the link against the
     * host's /usr/lib) — so the apps counter read 1 instead of 2
     * while LibreOffice IS installed. Check the real target path
     * inside each rootfs as well.
     * P6.4 — نظام مضيف is the ONLY system: the counters read the
     * Debian rootfs only (Alpine is gone from the app).
     */
    private fun refreshCounters() {
        val ctx = requireContext()
        val systems = if (DebianSystem.isReady(ctx)) 1 else 0
        val debianRoot = DebianSystem.debianRootfsDir(ctx)
        val hasFirefox = java.io.File(debianRoot, "usr/bin/firefox-esr").exists()
        val hasOffice = java.io.File(debianRoot, "usr/bin/soffice").exists() ||
            java.io.File(debianRoot, "usr/lib/libreoffice/program/soffice").exists()
        val apps = (if (hasFirefox) 1 else 0) + (if (hasOffice) 1 else 0)
        binding.textSystemsCount.text = systems.toString()
        binding.textAppsCount.text = apps.toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
