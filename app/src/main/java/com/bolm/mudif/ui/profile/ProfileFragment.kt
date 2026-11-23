package com.bolm.mudif.ui.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentProfileBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.DirectDesktop
import com.bolm.mudif.engine.EngineCheck
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.HomeSnapshots
import com.bolm.mudif.engine.InstallFlow
import com.bolm.mudif.engine.InstallService
import com.bolm.mudif.engine.RootfsManager
import com.bolm.mudif.engine.SystemBackup
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.bolm.mudif.engine.MudifUpdater
import com.bolm.mudif.engine.SystemUpdate
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.control.DeviceReportActivity
import com.bolm.mudif.ui.control.SharedFilesActivity
import com.bolm.mudif.ui.control.TaskManagerActivity
import com.bolm.mudif.ui.logs.LogViewerActivity
import com.bolm.mudif.ui.settings.AppLanguage
import com.bolm.mudif.ui.settings.SettingsActivity
import com.bolm.mudif.ui.terminal.TerminalActivity
import com.bolm.mudif.ui.vnc.VncActivity
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    /**
     * Real-computer #2, restore picker (SAF). The user picks a
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
        // The language row opens the same picker as the drawer
        // and the first-run dialog (it used to say "coming soon").
        binding.rowLanguage.setOnClickListener {
            (requireActivity() as? AppCompatActivity)?.let { AppLanguage.showPicker(it) }
        }
        // p61: the row subtitle shows the language actually
        // chosen, in the current UI language. It used to be a
        // static layout string (language_value) that always read
        // "Arabic" under an English UI, which is exactly the
        // mismatch Anas reported.
        binding.textLanguageValue.text = getString(
            when (AppLanguage.currentTag(requireContext())) {
                AppLanguage.TAG_DARIJA -> R.string.language_darija
                AppLanguage.TAG_ENGLISH -> R.string.language_english
                else -> R.string.language_fusha
            }
        )
        // p56: the About row opens the owner card (MudifDialog.about).
        // It used to show "coming soon"; the dialog exists since
        // Phase 1a and the sidebar already opens it.
        binding.rowAbout.setOnClickListener {
            MudifDialog.about(requireContext())
        }
        // P18.3, Viewer settings screen (Fusha, Mudif style).
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
        // Take 4: the version badge and the About row show the real
        // installed version, not the hardcoded string the layout
        // used to carry.
        val appVersion = getString(
            R.string.about_version_chip,
            MudifUpdater.currentVersion(requireContext()).first
        )
        binding.textAboutBadge.text = appVersion
        binding.textAboutValue.text = appVersion
        binding.rowTerminal.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), TerminalActivity::class.java))
        }
        // P6.4, نظام مضيف is the ONLY system in this app (the
        // user's order 2026-11-07). The Alpine desktop rows
        // (row_vnc LXQt, row_vnc_xfce XFCE) and the Alpine boot
        // test (row_boot) are GONE, nothing else launches a
        // desktop from this tab.
        // P6, نظام مضيف: the ONE system (Debian 13.1 + LXQt). First
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

        // Real-computer #3, shared files folder. The row shows the
        // Android path; tap opens the shared-files screen (F3):
        // send a file into the folder, browse it, open or share.
        // Inside the guest the same folder is /mnt/mudif-shared
        // (bound at boot). Copying the path lives in that screen.
        binding.textSharedFolderPath.text =
            RootfsManager.sharedDir(requireContext()).absolutePath
        binding.rowSharedFolder.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), SharedFilesActivity::class.java))
        }

        // Real-computer #4, in-app system update (apt update +
        // upgrade, no image reinstall). Runs off the UI thread; the
        // progress dialog shows the live apt output, and the result
        // dialog shows the real tail of it, never a fake "done".
        binding.rowSystemUpdate.setOnClickListener {
            val ctx = requireContext()
            val progress = MudifDialog.progress(ctx, getString(R.string.system_update_title))
            progress.setStatus(getString(R.string.system_update_running))
            progress.setIndeterminate(true)
            // Foreground service + WakeLock: apt upgrade runs minutes.
            InstallService.start(ctx, getString(R.string.ui_service_update))
            Thread {
                val result = SystemUpdate.run(ctx) { line ->
                    activity?.runOnUiThread { progress.setStatus(line.take(120)) }
                }
                activity?.runOnUiThread {
                    progress.dismiss()
                    InstallService.stop(ctx)
                    if (!isAdded) return@runOnUiThread
                    val tail = result.output.takeLast(1200).ifBlank { getString(R.string.ui_profile_no_output) }
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

        // Real-computer #2, backup / restore. Backup stops the
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

        // Take 3, home snapshots: create a snapshot of the Linux
        // home, or list the existing ones to restore / delete.
        binding.rowSnapshot.setOnClickListener {
            showSnapshotMenu()
        }

        // Take 4, in-app app update: check a release feed, download
        // with progress, verify the signer fingerprint, then install.
        binding.rowAppUpdate.setOnClickListener {
            showAppUpdateMenu()
        }

        // Real-computer #10, guest task manager (processes + kill).
        binding.rowTaskManager.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), TaskManagerActivity::class.java))
        }

        // F7, device report: the phone's real hardware facts.
        binding.rowDeviceReport.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), DeviceReportActivity::class.java))
        }

        // p56: card entrance on first view (fade + slide, staggered).
        if (savedInstanceState == null) {
            listOf(
                binding.cardIdentity, binding.cardSystems, binding.cardApps,
                binding.rowLanguage, binding.rowViewerSettings, binding.rowEngine,
                binding.rowTerminal, binding.rowVncMudif, binding.rowLogs,
                binding.rowSharedFolder, binding.rowSystemUpdate, binding.rowBackup,
                binding.rowSnapshot, binding.rowAppUpdate, binding.rowTaskManager,
                binding.rowDeviceReport, binding.rowAbout
            ).forEachIndexed { index, card ->
                com.bolm.mudif.ui.CardEntrance.play(card, com.bolm.mudif.ui.CardEntrance.staggerDelay(index))
            }
        }
    }

    /**
     * Take 1, what "desktop running" means now, and how to stop
     * it safely. The direct :6 session is a detached setsid
     * proot: GuestSession.stop() closes the PTY only and never
     * touches it, so stopping the PTY alone would let a backup
     * or restore run under a live desktop (files changing under
     * the walk, or a home replaced under a running session).
     * "Desktop running" is therefore DirectDesktop
     * .isDirectDesktopAliveHost (the lxqt-session pid, host-side,
     * fast enough for the UI thread) OR GuestSession.alive, and
     * stopping it means killing the direct session FIRST (its
     * guest one-shot needs no live PTY: it is a fresh proot) and
     * then stopping the PTY. Runs on a background thread because
     * the kill does a guest one-shot; [then] runs on the UI
     * thread afterwards. The take-3 exception stands in a new
     * form: the guard dialog is reachable now (the pid check
     * needs no viewer), but only when the user reaches Profile
     * while the session is alive; leaving the viewer does not
     * kill the detached session anymore, so the dialog can
     * actually appear. Whether it appeared on the rig is proven
     * in the take-1 evidence, not assumed here.
     */
    private fun stopDesktopAndGuest(ctx: Context, then: () -> Unit) {
        Thread {
            try {
                DirectDesktop.killDirectSession(ctx)
            } catch (_: Exception) {
            }
            try {
                GuestSession.stop()
            } catch (_: Exception) {
            }
            activity?.runOnUiThread { then() }
        }.start()
    }

    /**
     * Real-computer #2, create a backup. If the desktop is running
     * it is stopped first, after a confirm (a live rootfs cannot be
     * tarred safely, files change under the walk). The progress bar
     * is real bytes/total; the result dialog shows the real file.
     */
    private fun runBackup() {
        val ctx = requireContext()
        if (GuestSession.alive || DirectDesktop.isDirectDesktopAliveHost(ctx)) {
            MudifDialog.info(
                ctx,
                getString(R.string.backup_title),
                getString(R.string.backup_desktop_running),
                getString(R.string.ok),
                onPositive = {
                    stopDesktopAndGuest(ctx) { runBackup() }
                },
                negative = getString(R.string.cancel)
            )
            return
        }
        val progress = MudifDialog.progress(ctx, getString(R.string.backup_title))
        progress.setStatus(getString(R.string.backup_running))
        // Foreground service + WakeLock: tar+xz of the whole rootfs.
        InstallService.start(ctx, getString(R.string.ui_service_backup))
        Thread {
            try {
                val file = SystemBackup.backup(ctx) { done, total ->
                    if (total > 0) {
                        InstallService.updateProgress(
                            getString(R.string.ui_profile_backup_progress, ((done * 100) / total).toInt()), ((done * 100) / total).toInt()
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
                        e.message ?: getString(R.string.ui_profile_unknown_error),
                        getString(R.string.ok)
                    )
                }
            } finally {
                InstallService.stop(ctx)
            }
        }.start()
    }

    /**
     * Real-computer #2, restore from a picked file. The confirm
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
                    } ?: throw RuntimeException(getString(R.string.ui_profile_read_file_failed))
                } catch (e: Exception) {
                    MudifDialog.info(
                        ctx,
                        getString(R.string.backup_restore_failed_title),
                        e.message ?: getString(R.string.ui_profile_unknown_error),
                        getString(R.string.ok)
                    )
                    return@info
                }
                // Take 1: the confirm covers stopping the desktop,
                // and stopping it means the direct :6 session too
                // (GuestSession.stop() alone never touches it).
                stopDesktopAndGuest(ctx) {
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
                                e.message ?: getString(R.string.ui_profile_unknown_error),
                                getString(R.string.ok)
                            )
                        }
                    }
                }.start()
                }
            },
            negative = getString(R.string.cancel)
        )
    }

    /**
     * Take 3, home snapshots. Menu: create a snapshot, or list
     * the existing ones (restore / delete). Creating runs on a
     * background thread; the result dialog shows the real file
     * name and size, never a guess.
     */
    private fun showSnapshotMenu() {
        val ctx = requireContext()
        MudifDialog.info(
            ctx,
            getString(R.string.snapshot_title),
            getString(R.string.snapshot_choose),
            getString(R.string.snapshot_create),
            onPositive = { runCreateSnapshot() },
            neutral = getString(R.string.snapshot_list),
            onNeutral = { showSnapshotList() },
            negative = getString(R.string.cancel)
        )
    }

    private fun snapshotDisplay(info: HomeSnapshots.SnapshotInfo): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(info.createdMs))
        val mb = String.format(Locale.US, "%.1f MB", info.sizeBytes / (1024.0 * 1024.0))
        return "${info.name} ($mb, $date)"
    }

    private fun runCreateSnapshot() {
        val ctx = requireContext()
        val progress = MudifDialog.progress(ctx, getString(R.string.snapshot_title))
        progress.setStatus(getString(R.string.snapshot_creating))
        progress.setIndeterminate(true)
        InstallService.start(ctx, getString(R.string.snapshot_creating))
        Thread {
            try {
                val info = HomeSnapshots.create(ctx)
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    MudifDialog.info(
                        ctx,
                        getString(R.string.snapshot_title),
                        getString(
                            R.string.snapshot_done, info.name,
                            String.format(Locale.US, "%.1f MB", info.sizeBytes / (1024.0 * 1024.0))
                        ),
                        getString(R.string.ok)
                    )
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    MudifDialog.info(
                        ctx,
                        getString(R.string.snapshot_failed_title),
                        e.message ?: getString(R.string.ui_profile_unknown_error),
                        getString(R.string.ok)
                    )
                }
            } finally {
                InstallService.stop(ctx)
            }
        }.start()
    }

    private fun showSnapshotList() {
        val ctx = requireContext()
        Thread {
            val list = try {
                HomeSnapshots.list(ctx)
            } catch (_: Exception) {
                emptyList()
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (list.isEmpty()) {
                    MudifDialog.info(
                        ctx,
                        getString(R.string.snapshot_title),
                        getString(R.string.snapshot_none),
                        getString(R.string.ok)
                    )
                    return@runOnUiThread
                }
                MudifDialog.select(
                    ctx,
                    getString(R.string.snapshot_title),
                    list.map { snapshotDisplay(it) },
                    -1,
                    null
                ) { index ->
                    val info = list.getOrNull(index) ?: return@select
                    showSnapshotActions(info)
                }
            }
        }.start()
    }

    /** One snapshot: restore (the message IS the confirm: it
     *  says the current home is kept aside and put back on
     *  failure) or delete. */
    private fun showSnapshotActions(info: HomeSnapshots.SnapshotInfo) {
        val ctx = requireContext()
        val message = snapshotDisplay(info) +
            "\n\n" + getString(R.string.snapshot_restore_confirm)
        MudifDialog.info(
            ctx,
            getString(R.string.snapshot_title),
            message,
            getString(R.string.snapshot_restore),
            onPositive = { runRestoreSnapshot(info) },
            neutral = getString(R.string.snapshot_delete),
            onNeutral = { runDeleteSnapshot(info) },
            negative = getString(R.string.cancel)
        )
    }

    /** Restores one snapshot. A live desktop is stopped first,
     *  after a confirm; the restore itself runs on a background
     *  thread and reports the missing packages, if any. */
    private fun runRestoreSnapshot(info: HomeSnapshots.SnapshotInfo) {
        val ctx = requireContext()
        // Take 1: "desktop running" is the direct :6 session pid
        // or the PTY session (see stopDesktopAndGuest). The old
        // guard keyed off the PTY only, which never touches the
        // detached session; without the pid check a restore could
        // run under a live desktop, and without killing the direct
        // session first HomeSnapshots.restore would refuse (its
        // own guard now checks the same pid).
        if (GuestSession.alive || DirectDesktop.isDirectDesktopAliveHost(ctx)) {
            MudifDialog.info(
                ctx,
                getString(R.string.snapshot_title),
                getString(R.string.snapshot_desktop_running),
                getString(R.string.ok),
                onPositive = {
                    stopDesktopAndGuest(ctx) { runRestoreSnapshotConfirmed(info) }
                },
                negative = getString(R.string.cancel)
            )
            return
        }
        runRestoreSnapshotConfirmed(info)
    }

    private fun runRestoreSnapshotConfirmed(info: HomeSnapshots.SnapshotInfo) {
        val ctx = requireContext()
        val progress = MudifDialog.progress(ctx, getString(R.string.snapshot_title))
        progress.setStatus(getString(R.string.snapshot_restoring))
        progress.setIndeterminate(true)
        Thread {
            try {
                val packages = HomeSnapshots.restore(ctx, info.name)
                val missing = HomeSnapshots.missingPackages(ctx, packages)
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    val message = getString(R.string.snapshot_restore_done) +
                        if (missing.isEmpty()) "" else "\n\n" + getString(
                            R.string.snapshot_missing_packages,
                            missing.take(10).joinToString(", ")
                        )
                    MudifDialog.info(
                        ctx,
                        getString(R.string.snapshot_title),
                        message,
                        getString(R.string.ok)
                    )
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    MudifDialog.info(
                        ctx,
                        getString(R.string.snapshot_restore_failed_title),
                        e.message ?: getString(R.string.ui_profile_unknown_error),
                        getString(R.string.ok)
                    )
                }
            }
        }.start()
    }

    private fun runDeleteSnapshot(info: HomeSnapshots.SnapshotInfo) {
        val ctx = requireContext()
        Thread {
            HomeSnapshots.delete(ctx, info.name)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Snackbar.make(binding.root, getString(R.string.snapshot_deleted), Snackbar.LENGTH_SHORT).show()
            }
        }.start()
    }

    /**
     * Take 4, in-app app update. Menu: the current version, the
     * signer fingerprint prefix and the configured feed, with
     * "check" and "change source". No production feed exists yet
     * (release keystore decision pending), so with no source set
     * the check reports that plainly instead of guessing a feed.
     */
    private fun showAppUpdateMenu() {
        val ctx = requireContext()
        val (name, code) = MudifUpdater.currentVersion(ctx)
        val feed = MudifUpdater.feedUrl(ctx)
        val message = getString(R.string.app_update_current, name, code) + "\n" +
            getString(R.string.app_update_fingerprint, MudifUpdater.installedFingerprintPrefix(ctx)) + "\n" +
            if (feed.isBlank()) getString(R.string.app_update_no_source)
            else getString(R.string.app_update_source, feed)
        MudifDialog.info(
            ctx,
            getString(R.string.app_update_title),
            message,
            getString(R.string.app_update_check),
            onPositive = { runAppUpdateCheck() },
            neutral = getString(R.string.app_update_change_source),
            onNeutral = { showFeedUrlEditor() },
            negative = getString(R.string.cancel)
        )
    }

    /** Feed URL editor: one plain URL field on the branded dialog
     *  background. The URL is stored as-is; the check validates it. */
    private fun showFeedUrlEditor() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(MudifUpdater.feedUrl(ctx))
            hint = getString(R.string.app_update_source_hint)
            setSingleLine(true)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xFFBFBFFF.toInt())
        }
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, ctx.resources.displayMetrics
        ).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.app_update_source_title))
            .setView(container)
            .setPositiveButton(getString(R.string.app_update_save)) { d, _ ->
                MudifUpdater.saveFeedUrl(ctx, input.text.toString())
                d.dismiss()
                Snackbar.make(binding.root, getString(R.string.app_update_source_saved), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(ctx, R.drawable.dialog_mudif_bg)
        )
        MudifDialog.animateWindow(dialog)
    }

    /** Checks the configured feed on a background thread. A newer
     *  release offers download+install; anything else is reported
     *  with the real reason, never a fake "up to date". */
    private fun runAppUpdateCheck() {
        val ctx = requireContext()
        val progress = MudifDialog.progress(ctx, getString(R.string.app_update_title))
        progress.setStatus(getString(R.string.app_update_checking))
        progress.setIndeterminate(true)
        Thread {
            try {
                val result = MudifUpdater.check(ctx, MudifUpdater.feedUrl(ctx))
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    when (result) {
                        is MudifUpdater.CheckResult.UpToDate -> MudifDialog.info(
                            ctx,
                            getString(R.string.app_update_title),
                            getString(R.string.app_update_up_to_date),
                            getString(R.string.ok)
                        )
                        is MudifUpdater.CheckResult.Available -> {
                            val release = result.release
                            val size = if (release.apkSize > 0) String.format(
                                Locale.US, "%.1f MB", release.apkSize / (1024.0 * 1024.0)
                            ) else "?"
                            MudifDialog.info(
                                ctx,
                                getString(R.string.app_update_title),
                                getString(
                                    R.string.app_update_available,
                                    release.versionName, release.versionCode, size, release.notes
                                ),
                                getString(R.string.app_update_download_install),
                                onPositive = { runAppUpdateDownload(release) },
                                negative = getString(R.string.cancel)
                            )
                        }
                    }
                }
            } catch (e: MudifUpdater.UpdateException) {
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    MudifDialog.info(
                        ctx,
                        getString(R.string.app_update_title),
                        appUpdateErrorMessage(e.code),
                        getString(R.string.ok)
                    )
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    MudifDialog.info(
                        ctx,
                        getString(R.string.app_update_title),
                        getString(R.string.app_update_network),
                        getString(R.string.ok)
                    )
                }
            }
        }.start()
    }

    /** Downloads with a real progress bar, verifies, then opens the
     *  system installer. A failed verification never reaches the
     *  installer: the error dialog is the end of that path. */
    private fun runAppUpdateDownload(release: MudifUpdater.ReleaseInfo) {
        val ctx = requireContext()
        val progress = MudifDialog.progress(ctx, getString(R.string.app_update_title))
        progress.setStatus(getString(R.string.app_update_downloading, 0))
        Thread {
            try {
                val file = MudifUpdater.download(ctx, release) { done, total ->
                    val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                    activity?.runOnUiThread {
                        progress.setProgress(pct * 10)
                        progress.setStatus(getString(R.string.app_update_downloading, pct))
                    }
                }
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    if (!MudifUpdater.canInstall(ctx)) {
                        MudifDialog.info(
                            ctx,
                            getString(R.string.app_update_title),
                            getString(R.string.app_update_no_permission),
                            getString(R.string.app_update_open_settings),
                            onPositive = { MudifUpdater.openInstallPermissionSettings(ctx) },
                            negative = getString(R.string.cancel)
                        )
                    } else {
                        try {
                            MudifUpdater.install(ctx, file)
                        } catch (e: Exception) {
                            MudifDialog.info(
                                ctx,
                                getString(R.string.app_update_title),
                                e.message ?: getString(R.string.ui_profile_unknown_error),
                                getString(R.string.ok)
                            )
                        }
                    }
                }
            } catch (e: MudifUpdater.UpdateException) {
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    MudifDialog.info(
                        ctx,
                        getString(R.string.app_update_title),
                        appUpdateErrorMessage(e.code),
                        getString(R.string.ok)
                    )
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    progress.dismiss()
                    if (!isAdded) return@runOnUiThread
                    MudifDialog.info(
                        ctx,
                        getString(R.string.app_update_title),
                        getString(R.string.app_update_network),
                        getString(R.string.ok)
                    )
                }
            }
        }.start()
    }

    private fun appUpdateErrorMessage(code: String): String {
        return getString(
            when (code) {
                "no_source" -> R.string.app_update_no_source
                "signature_mismatch" -> R.string.app_update_signature_mismatch
                "size_mismatch" -> R.string.app_update_size_mismatch
                "hash_mismatch" -> R.string.app_update_hash_mismatch
                "invalid" -> R.string.app_update_invalid
                "network" -> R.string.app_update_network
                else -> R.string.ui_profile_unknown_error
            }
        )
    }

    override fun onResume() {
        super.onResume()
        // e.g. Debian installed from the Store tab -> counters must move
        refreshCounters()
    }

    /**
     * P6.1, the counters were hardcoded "0" in the layout while
     * Alpine + Firefox + LibreOffice were already installed (the tabs
     * test proved it). They now read real state.
     * P6.2 FIX (P6.1 test): /usr/bin/soffice is an ABSOLUTE symlink
     * to /usr/lib/libreoffice/program/soffice, which does NOT resolve
     * on the Android host (File.exists follows the link against the
     * host's /usr/lib), so the apps counter read 1 instead of 2
     * while LibreOffice IS installed. Check the real target path
     * inside each rootfs as well.
     * P6.4, نظام مضيف is the ONLY system: the counters read the
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
