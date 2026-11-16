package com.bolm.mudif.ui.games

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentGamesBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.InstallService
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.apps.AppLauncher
import com.bolm.mudif.ui.store.ProgramState
import com.bolm.mudif.ui.store.ProgramStore
import com.bolm.mudif.ui.store.StoreCatalog
import com.bolm.mudif.ui.store.StoreProgram
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

/**
 * Mudif Arcade, wired to the real store (the P19 "install coming
 * soon" placeholder is gone). The grid is the "games" entries of
 * store-catalog.json, loaded through the same StoreCatalog loader
 * the Store tab uses; each card shows the real store icon and the
 * live install state from ProgramStore.batchStatus. Tapping a card
 * installs it for real (ProgramStore.install + post-verify, the
 * Store tab's exact pattern) or launches it when installed (the
 * shared AppLauncher path from the Apps tab).
 *
 * "جهّز محطة الألعاب" installs every game entry sequentially, with
 * a real progress bar (one step per program actually installed and
 * verified, never a timer), then runs the guest setup script
 * (setup-arcade.sh: ~/Games folders + desktop shortcuts for the
 * installed binaries only). The end report lists every program as
 * succeeded / failed / already installed plus the setup-script
 * result, and offers Retry for the failures only. Nothing here is
 * faked: a program counts as installed only when the exit code is
 * 0 AND a follow-up verify agrees.
 */
class GamesFragment : Fragment() {
    private var _binding: FragmentGamesBinding? = null
    private val binding get() = _binding!!

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var adapter: ArcadeAdapter
    private var games: List<StoreProgram> = emptyList()
    private var guestArch: String = ""
    private var gamesLoaded: Boolean = false
    private val states = HashMap<String, ProgramState>()
    private val busyIds = HashSet<String>()
    private var setupRunning: Boolean = false
    private var failedSetup: List<StoreProgram> = emptyList()

    /** One setup-run outcome, for the end report. */
    private data class SetupResult(
        val program: StoreProgram,
        val ok: Boolean,
        val alreadyInstalled: Boolean,
        val detail: String
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGamesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), 2)
        adapter = ArcadeAdapter(
            onInstall = { runInstall(it) },
            onLaunch = { runLaunch(it) }
        )
        binding.recycler.adapter = adapter
        binding.btnSetupStation.setOnClickListener { confirmSetup() }
        updateSetupButton()
        loadGames()
    }

    override fun onResume() {
        super.onResume()
        // e.g. installed from the Store tab -> reflect it here.
        if (gamesLoaded && _binding != null && DebianSystem.isReady(requireContext())) {
            refreshStatuses()
        }
    }

    /** Catalog games + guest arch, then a live batch status check. */
    private fun loadGames() {
        val appCtx = requireContext().applicationContext
        showLoading()
        Thread {
            val loaded = try {
                StoreCatalog.load(appCtx).filter { it.category == "games" }
            } catch (e: Exception) {
                emptyList()
            }
            val arch = if (DebianSystem.isReady(appCtx)) ProgramStore.guestArch(appCtx) else ""
            uiHandler.post {
                if (!isAdded || _binding == null) return@post
                games = loaded
                gamesLoaded = true
                guestArch = arch
                for (p in loaded) states[p.id] = ProgramState.UNKNOWN
                adapter.submitList(loaded)
                updateSetupButton()
                if (loaded.isEmpty()) {
                    showEmpty(getString(R.string.store_catalog_failed))
                } else {
                    showList()
                    if (DebianSystem.isReady(appCtx)) {
                        refreshStatuses()
                    } else {
                        Snackbar.make(
                            binding.root,
                            getString(R.string.store_system_not_ready),
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    /** One guest call for all games; missing entries keep UNKNOWN.
     *  A null map means the check itself failed: leave every state
     *  UNKNOWN (never show a failed check as "not installed"). */
    private fun refreshStatuses() {
        val appCtx = requireContext().applicationContext
        Thread {
            val map = ProgramStore.batchStatus(appCtx, games) ?: return@Thread
            uiHandler.post {
                if (!isAdded || _binding == null) return@post
                for ((id, installed) in map) {
                    states[id] = if (installed) ProgramState.INSTALLED else ProgramState.NOT_INSTALLED
                }
                adapter.refreshStates(states)
            }
        }.start()
    }

    private fun showLoading() {
        binding.emptyState.text = getString(R.string.downloading)
        binding.emptyState.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
    }

    private fun showEmpty(message: String) {
        binding.emptyState.text = message
        binding.emptyState.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
    }

    private fun showList() {
        binding.emptyState.visibility = View.GONE
        binding.recycler.visibility = View.VISIBLE
    }

    private fun setState(id: String, state: ProgramState) {
        states[id] = state
        adapter.refreshStates(states)
        updateSetupButton()
    }

    private fun setBusy(id: String, busy: Boolean) {
        if (busy) busyIds.add(id) else busyIds.remove(id)
        adapter.setBusy(busyIds.toSet())
        updateSetupButton()
    }

    /**
     * The setup button is live only when there is something to set
     * up and nothing is running: a second install while one runs
     * would fight it over the apt lock, so the button (and the
     * single-install path) stay disabled until the run ends.
     */
    private fun updateSetupButton() {
        val b = _binding?.btnSetupStation ?: return
        b.isEnabled = games.isNotEmpty() && !setupRunning && busyIds.isEmpty() &&
            DebianSystem.isReady(requireContext())
    }

    private fun systemReady(): Boolean {
        if (DebianSystem.isReady(requireContext())) return true
        Snackbar.make(
            binding.root,
            getString(R.string.store_system_not_ready),
            Snackbar.LENGTH_LONG
        ).show()
        return false
    }

    /** One game, installed for real: StoreFragment's exact pattern. */
    private fun runInstall(program: StoreProgram) {
        if (!systemReady() || setupRunning) return
        setState(program.id, ProgramState.INSTALLING)
        val appCtx = requireContext().applicationContext
        // Visible progress dialog (stage + real percentage when the
        // install reports one). p68: the dialog has a Pause button
        // now; a pause kills the guest command, the partial file
        // stays, and the next Install resumes it (or the cached copy).
        val progress = MudifDialog.progress(requireContext(), program.name)
        progress.setStatus(getString(R.string.store_status_installing))
        progress.setIndeterminate(true)
        val control = ProgramStore.DownloadControl()
        progress.setOnPauseListener { control.pause() }
        // Foreground service + WakeLock: the install keeps running
        // when the user leaves the app or the screen turns off.
        InstallService.start(appCtx, getString(R.string.ui_service_install_program, program.name))
        Thread {
            // p68: the status line composes the last stage + percent +
            // speed + ETA, whichever the library has reported so far.
            // appCtx strings here: the fragment may be detached
            // mid-download, the application context never is.
            var lastStage = appCtx.getString(R.string.store_status_installing)
            var lastPercent: Int? = null
            var lastSpeed: String? = null
            fun refreshStatus() {
                val base = if (lastPercent != null) "$lastStage $lastPercent%" else lastStage
                val line = if (lastSpeed != null) "$base · $lastSpeed" else base
                val pct = lastPercent
                uiHandler.post {
                    if (!isAdded) return@post
                    if (pct != null) progress.setProgress(pct * 10) else progress.setIndeterminate(true)
                    progress.setStatus(line)
                }
            }
            val result = ProgramStore.install(
                appCtx, program, guestArch,
                onProgress = { stage, percent ->
                    lastStage = stage
                    lastPercent = percent
                    InstallService.updateProgress(
                        if (percent != null) "$stage $percent%" else stage, percent
                    )
                    refreshStatus()
                },
                onSpeed = { mbps, etaSec ->
                    val speed = appCtx.getString(
                        R.string.store_dl_speed,
                        String.format(Locale.US, "%.1f", mbps)
                    )
                    lastSpeed = if (etaSec != null) {
                        "$speed · " + appCtx.getString(
                            R.string.store_dl_eta,
                            String.format(Locale.US, "%d", etaSec)
                        )
                    } else {
                        speed
                    }
                    refreshStatus()
                },
                control = control
            )
            // Verify step: show the checking stage while it runs.
            uiHandler.post {
                if (!isAdded) return@post
                if (!control.paused) {
                    progress.setIndeterminate(true)
                    progress.setStatus(getString(R.string.store_status_checking))
                }
            }
            val (verified, _) = if (result.exitCode == 0) {
                ProgramStore.verify(appCtx, program)
            } else {
                false to ProgramStore.lastLine(appCtx, result.output)
            }
            val detail = if (result.exitCode == 0 && !verified) {
                ProgramStore.lastLine(appCtx, result.output)
            } else {
                ProgramStore.lastLines(appCtx, result.output)
            }
            uiHandler.post {
                // Dismiss first: the fragment may be detached by now,
                // but the dialog must never be left on screen.
                progress.dismiss()
                InstallService.stop(appCtx)
                if (!isAdded || _binding == null) return@post
                if (control.paused) {
                    // p68: not a failure. The partial file stays, the
                    // next Install resumes it (or the cached copy).
                    states[program.id] = ProgramState.NOT_INSTALLED
                    adapter.refreshStates(states)
                    Snackbar.make(
                        binding.root,
                        getString(R.string.store_dl_paused_resume, lastPercent ?: 0),
                        Snackbar.LENGTH_LONG
                    ).show()
                } else if (result.exitCode == 0 && verified) {
                    states[program.id] = ProgramState.INSTALLED
                    adapter.refreshStates(states)
                    Snackbar.make(
                        binding.root,
                        getString(R.string.store_install_success, program.name),
                        Snackbar.LENGTH_LONG
                    ).show()
                } else {
                    states[program.id] = ProgramState.NOT_INSTALLED
                    adapter.refreshStates(states)
                    MudifDialog.info(
                        requireContext(),
                        getString(R.string.store_install_failed_title),
                        getString(R.string.store_install_failed, program.name) +
                            "\n\n" + detail,
                        getString(R.string.ok)
                    )
                }
                updateSetupButton()
            }
        }.start()
    }

    /** Installed game: launch it through the shared Apps-tab path. */
    private fun runLaunch(program: StoreProgram) {
        if (!systemReady() || setupRunning) return
        AppLauncher.launch(this, program) { busy -> setBusy(program.id, busy) }
    }

    /** Confirm first: this installs every game (large downloads). */
    private fun confirmSetup() {
        if (!systemReady() || setupRunning || games.isEmpty()) return
        if (busyIds.isNotEmpty()) return
        MudifDialog.info(
            requireContext(),
            getString(R.string.games_setup_title),
            getString(R.string.games_setup_confirm, games.size),
            getString(R.string.games_setup_start),
            onPositive = { runSetup(games) },
            negative = getString(R.string.cancel)
        )
    }

    /**
     * One-button station setup: install [toInstall] sequentially,
     * then run the guest setup script. The progress bar moves only
     * when a program is fully installed AND verified (one real step
     * per program); already-installed entries are skipped, not
     * re-installed, and still advance the count. The setup script
     * runs even if some installs failed, so the folders and
     * shortcuts cover whatever IS installed, its own result is
     * reported separately, never folded into the install count.
     * The per-install stage stream is intentionally not shown here:
     * the dialog status is the real count line (program + x/N), and
     * a second changing stage line would fight it.
     */
    private fun runSetup(toInstall: List<StoreProgram>) {
        if (!systemReady() || setupRunning || toInstall.isEmpty()) return
        if (busyIds.isNotEmpty()) return
        setupRunning = true
        updateSetupButton()
        val appCtx = requireContext().applicationContext
        val total = toInstall.size
        val progress = MudifDialog.progress(
            requireContext(), getString(R.string.games_setup_title)
        )
        progress.setIndeterminate(false)
        progress.setProgress(0)
        progress.setStatus(
            getString(R.string.games_setup_progress, toInstall[0].name, 1, total)
        )
        InstallService.start(appCtx, getString(R.string.games_setup_title))
        Thread {
            val results = ArrayList<SetupResult>()
            var done = 0
            for (program in toInstall) {
                if (states[program.id] == ProgramState.INSTALLED) {
                    results.add(SetupResult(program, true, true, ""))
                } else {
                    // Capture the count now: the post runs on the UI
                    // thread while this worker blocks in the install,
                    // but reading the mutated `done` inside the post
                    // would be a race if the main thread ever lagged.
                    val index = done + 1
                    val doneBefore = done
                    uiHandler.post {
                        if (!isAdded) return@post
                        progress.setProgress(doneBefore * 1000 / total)
                        val stage = getString(
                            R.string.games_setup_progress, program.name, index, total
                        )
                        progress.setStatus(stage)
                        InstallService.updateProgress(stage, doneBefore * 100 / total)
                        setState(program.id, ProgramState.INSTALLING)
                    }
                    val result = ProgramStore.install(appCtx, program, guestArch)
                    val (verified, _) = if (result.exitCode == 0) {
                        ProgramStore.verify(appCtx, program)
                    } else {
                        false to ""
                    }
                    val ok = result.exitCode == 0 && verified
                    // One line per failure in the end report, so the
                    // report stays readable; the full output is shown
                    // by the single-install retry for that program.
                    val detail = if (ok) "" else ProgramStore.lastLine(appCtx, result.output)
                    results.add(SetupResult(program, ok, false, detail))
                    uiHandler.post {
                        if (!isAdded) return@post
                        setState(
                            program.id,
                            if (ok) ProgramState.INSTALLED else ProgramState.NOT_INSTALLED
                        )
                    }
                }
                done++
                val doneNow = done
                uiHandler.post {
                    if (!isAdded) return@post
                    progress.setProgress(doneNow * 1000 / total)
                    InstallService.updateProgress(
                        getString(R.string.games_setup_title), doneNow * 100 / total
                    )
                }
            }
            uiHandler.post {
                if (!isAdded) return@post
                progress.setIndeterminate(true)
                progress.setStatus(getString(R.string.games_setup_setup_step))
                InstallService.updateProgress(
                    getString(R.string.games_setup_setup_step), null
                )
            }
            val scriptResult = ProgramStore.runSetupScript(appCtx, "setup-arcade.sh")
            val scriptOk = scriptResult.exitCode == 0
            val scriptDetail = if (scriptOk) {
                scriptResult.output.trim()
            } else {
                ProgramStore.lastLines(appCtx, scriptResult.output)
            }
            uiHandler.post {
                // Dismiss first: the fragment may be detached by now,
                // but the dialog must never be left on screen.
                progress.dismiss()
                InstallService.stop(appCtx)
                setupRunning = false
                if (!isAdded || _binding == null) return@post
                updateSetupButton()
                showSetupReport(results, scriptOk, scriptDetail)
            }
        }.start()
    }

    /** End report: one line per program + the setup-script result. */
    private fun showSetupReport(
        results: List<SetupResult>,
        scriptOk: Boolean,
        scriptDetail: String
    ) {
        val failed = results.filter { !it.ok }
        failedSetup = failed.map { it.program }
        val lines = StringBuilder()
        for (r in results) {
            when {
                !r.ok -> lines.append(
                    getString(R.string.games_setup_fail_line, r.program.name, r.detail)
                ).append("\n")
                r.alreadyInstalled -> lines.append(
                    getString(R.string.games_setup_already_line, r.program.name)
                ).append("\n")
                else -> lines.append(
                    getString(R.string.games_setup_ok_line, r.program.name)
                ).append("\n")
            }
        }
        if (scriptOk) {
            lines.append(getString(R.string.games_setup_script_ok))
        } else {
            lines.append(getString(R.string.games_setup_script_failed, scriptDetail))
        }
        val summary = getString(
            R.string.games_setup_summary,
            results.count { it.ok }, failed.size, results.size
        )
        val message = summary + "\n\n" + lines.toString().trimEnd()
        if (failed.isEmpty()) {
            MudifDialog.info(
                requireContext(),
                getString(R.string.games_setup_report_title),
                message,
                getString(R.string.ok)
            )
        } else {
            MudifDialog.info(
                requireContext(),
                getString(R.string.games_setup_report_title),
                message,
                getString(R.string.ok),
                neutral = getString(R.string.games_setup_retry_failed),
                onNeutral = { runSetup(failedSetup) }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
