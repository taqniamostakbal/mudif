package com.bolm.mudif.ui.store

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentStoreBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.InstallFlow
import com.bolm.mudif.engine.InstallService
import com.bolm.mudif.ui.MudifDialog
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

/**
 * Store screen (P10): the Debian system card on top, then the program
 * catalog: search box, category chips, and program cards with real
 * icons, a live status line, and three honest actions per program:
 * verify, install, uninstall. Every guest call runs on a background
 * thread; success is only claimed when the exit code is 0 AND a
 * follow-up verify agrees.
 */
class StoreFragment : Fragment() {
    private var _binding: FragmentStoreBinding? = null
    private val binding get() = _binding!!

    private var programs: List<StoreProgram> = emptyList()
    private var guestArch: String = ""
    private var selectedCategory: String = StoreCategory.ALL.key
    private var query: String = ""
    private var statusesLoaded: Boolean = false
    private val states = HashMap<String, ProgramState>()
    private val evidence = HashMap<String, String>()
    private lateinit var programAdapter: ProgramAdapter
    private lateinit var categoryAdapter: CategoryCardAdapter
    private lateinit var distroAdapter: DistroAdapter
    private lateinit var concatAdapter: ConcatAdapter
    // p30: whether the distro card + category grid are currently in
    // the concat list. They hide while a search query is typed (the
    // filtered programs fill the screen) and come back when the query
    // is cleared. Tracked so add/remove never runs twice in a row.
    private var sectionsInConcat = true
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // p30: ONE scrolling list for the whole screen (ConcatAdapter).
        // The distro card, the category grid and the program cards are
        // sections of a single RecyclerView, so there is no fixed top
        // section that can eat the list's height (p28 + p29 proved the
        // fixed top measured 976px and left the list 0px). Grid span 2:
        // category cards span 1 (2-column grid), distro + section
        // headers + program cards span 2 (full width).
        distroAdapter = DistroAdapter(
            DISTROS,
            // P6.4: the store lists Debian only (نظام مضيف is the
            // ONE system). The Alpine card and the install_soon
            // placeholders are gone.
            isInstalled = { d ->
                d.name == "نظام مضيف" && DebianSystem.isReady(requireContext())
            },
            onInstall = { d -> onInstallClicked(d) }
        )

        // P19 Phase 1: category gradient cards (2-column grid).
        // Phase 1c: tapping a specific category opens its dedicated
        // page (slide transition); "الكل" keeps the in-place filter
        // (it IS the store's full view, so there is nowhere to go).
        categoryAdapter = CategoryCardAdapter(
            StoreCategory.entries, selectedCategory, emptyMap()
        ) { category ->
            if (category == StoreCategory.ALL) {
                selectedCategory = category.key
                categoryAdapter.setSelected(category.key)
                applyFilter()
            } else {
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(
                        R.anim.slide_in_right, R.anim.slide_out_left,
                        R.anim.slide_in_left, R.anim.slide_out_right
                    )
                    .replace(
                        R.id.nav_host,
                        StoreCategoryFragment.newInstance(category.key)
                    )
                    .addToBackStack(null)
                    .commit()
                // p21: run the navigation now, not on the next frame,
                // so a tap's result is visible immediately. commitNow
                // cannot be used here: it forbids addToBackStack.
                parentFragmentManager.executePendingTransactions()
            }
        }
        programAdapter = ProgramAdapter(
            onVerify = { p -> runVerify(p) },
            onInstall = { p -> runInstall(p) },
            onUninstall = { p -> runUninstall(p) }
        )
        concatAdapter = ConcatAdapter(distroAdapter, categoryAdapter, programAdapter)
        binding.programsRecycler.layoutManager = GridLayoutManager(requireContext(), 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    // getWrappedAdapterAndPosition returns an
                    // android.util.Pair, which has no component1/2,
                    // so destructuring does not compile; use .first.
                    val adapter = concatAdapter.getWrappedAdapterAndPosition(position).first
                    return if (adapter === categoryAdapter) 1 else 2
                }
            }
        }
        binding.programsRecycler.adapter = concatAdapter

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                applyFilter()
            }
        })
        // Extract-mode safety: some keyboards buffer text in the IME's
        // fullscreen extract view and only commit it on the action key,
        // so afterTextChanged may never fire for the typed text. Read
        // the EditText's current text on the action and filter from
        // that, so a search typed in extract mode still filters.
        binding.searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                query = binding.searchBox.text?.toString() ?: ""
                applyFilter()
                true
            } else {
                false
            }
        }

        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        // e.g. installed from the profile tab -> reflect it here
        if (::distroAdapter.isInitialized) distroAdapter.refresh()
        if (statusesLoaded && DebianSystem.isReady(requireContext())) {
            refreshAllStatuses()
        }
    }

    private fun onInstallClicked(d: Distro) {
        when (d.name) {
            "نظام مضيف" -> installDebian()
            else -> Snackbar.make(
                binding.root,
                getString(R.string.install_soon),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun installDebian() {
        if (DebianSystem.isReady(requireContext())) {
            Snackbar.make(
                binding.root,
                getString(R.string.debian_already_installed),
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        InstallFlow.runDebianInstall(this) { ok, message ->
            if (ok) {
                if (::distroAdapter.isInitialized) distroAdapter.refresh()
                Snackbar.make(
                    binding.root,
                    getString(R.string.debian_install_done),
                    Snackbar.LENGTH_LONG
                ).show()
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
    }

    /** Loads the catalog, detects the guest arch, then batch-checks status. */
    private fun loadCatalog() {
        val appCtx = requireContext().applicationContext
        Thread {
            val loaded = try {
                StoreCatalog.load(appCtx)
            } catch (e: Exception) {
                emptyList()
            }
            // p28: the guest calls MUST NOT kill this thread. Before,
            // guestArch() ran unguarded: if it threw (guest busy or
            // unreachable) the thread died before the UI post, so the
            // category counts stayed at the initial empty map (all 0)
            // and the program list never populated. Now the UI post
            // always runs and the counts always come from the catalog.
            val arch = try {
                if (DebianSystem.isReady(appCtx)) ProgramStore.guestArch(appCtx) else ""
            } catch (e: Exception) {
                ""
            }
            uiHandler.post {
                if (!isAdded) return@post
                programs = loaded
                guestArch = arch
                // P19 Phase 1: category counts for the gradient cards.
                // Windows programs are counted on the Windows card
                // only: the Linux category counts (and "الكل") count
                // Linux programs only, so adding a Windows program
                // moves no Linux count.
                val counts = HashMap<String, Int>()
                for ((cat, list) in loaded.filter { it.method != "win32" }.groupBy { it.category }) {
                    counts[cat] = list.size
                }
                counts[StoreCategory.WINDOWS.key] = loaded.count { it.method == "win32" }
                categoryAdapter.setCounts(counts)
                if (loaded.isEmpty()) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.store_catalog_failed),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
                for (p in loaded) states[p.id] = ProgramState.UNKNOWN
                applyFilter()
                if (DebianSystem.isReady(appCtx)) {
                    refreshAllStatuses()
                } else {
                    statusesLoaded = true
                    Snackbar.make(
                        binding.root,
                        getString(R.string.store_system_not_ready),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /** One guest call for all programs; missing entries keep UNKNOWN.
     *  A null map means the check itself failed: leave every state
     *  UNKNOWN (never show a failed check as "not installed"). */
    private fun refreshAllStatuses() {
        val appCtx = requireContext().applicationContext
        Thread {
            val map = ProgramStore.batchStatus(appCtx, programs) ?: return@Thread
            uiHandler.post {
                if (!isAdded) return@post
                for ((id, installed) in map) {
                    states[id] = if (installed) ProgramState.INSTALLED else ProgramState.NOT_INSTALLED
                }
                statusesLoaded = true
                programAdapter.refreshStates(states, evidence)
            }
        }.start()
    }

    /** Adds or removes the distro card + category grid sections of
     *  the concat list. No-op when the state already matches, so a
     *  keystroke never re-adds an adapter that is already there
     *  (ConcatAdapter throws on a duplicate add). */
    private fun setSectionsVisible(visible: Boolean) {
        if (visible == sectionsInConcat) return
        sectionsInConcat = visible
        if (visible) {
            concatAdapter.addAdapter(0, distroAdapter)
            concatAdapter.addAdapter(1, categoryAdapter)
        } else {
            concatAdapter.removeAdapter(categoryAdapter)
            concatAdapter.removeAdapter(distroAdapter)
        }
    }

    /** Search (Arabic name, English name, description) plus category filter. */
    private fun applyFilter() {
        val q = query.trim()
        // p21: while a query is typed, the distro card + category
        // cards leave the list so the filtered programs are what the
        // user sees. Before, the cards stayed visible with stale
        // counts and the screen looked unfiltered even though the
        // list below had already filtered. p30: the sections are
        // adapters in the concat list, so they are added/removed
        // instead of hidden (a hidden adapter would still hold its
        // place in the scrolling list).
        setSectionsVisible(q.isEmpty())
        val filtered = programs.filter { p ->
            val inList = if (selectedCategory == StoreCategory.WINDOWS.key) {
                p.method == "win32"
            } else {
                // Every Linux list shows Linux programs only; Windows
                // programs live in the Windows list alone.
                p.method != "win32" &&
                    (selectedCategory == StoreCategory.ALL.key || p.category == selectedCategory)
            }
            inList && (q.isEmpty() || p.name.contains(q, ignoreCase = true) ||
                p.nameEn.contains(q, ignoreCase = true) ||
                p.desc.contains(q, ignoreCase = true))
        }
        val items = ArrayList<StoreListItem>()
        if (q.isEmpty()) {
            if (selectedCategory == StoreCategory.WINDOWS.key) {
                if (filtered.isNotEmpty()) {
                    items.add(StoreListItem.Header(StoreCategory.WINDOWS.key, filtered.size))
                    for (p in filtered) items.add(StoreListItem.Program(p))
                }
            } else {
                val groups = filtered.groupBy { it.category }
                for (category in StoreCategory.entries) {
                    if (category == StoreCategory.ALL || category == StoreCategory.WINDOWS) continue
                    val list = groups[category.key] ?: continue
                    items.add(StoreListItem.Header(category.key, list.size))
                    for (p in list) items.add(StoreListItem.Program(p))
                }
            }
        } else {
            for (p in filtered) items.add(StoreListItem.Program(p))
        }
        val empty = items.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.programsRecycler.visibility = if (empty) View.GONE else View.VISIBLE
        programAdapter.submitListAnimated(items)
    }

    private fun setState(id: String, state: ProgramState) {
        states[id] = state
        programAdapter.refreshStates(states, evidence)
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

    private fun runVerify(program: StoreProgram) {
        if (!systemReady()) return
        setState(program.id, ProgramState.CHECKING)
        val appCtx = requireContext().applicationContext
        Thread {
            val (installed, ev) = ProgramStore.verify(appCtx, program)
            uiHandler.post {
                if (!isAdded) return@post
                states[program.id] =
                    if (installed) ProgramState.INSTALLED else ProgramState.NOT_INSTALLED
                evidence[program.id] = ev
                programAdapter.refreshStates(states, evidence)
                Snackbar.make(
                    binding.root,
                    getString(
                        if (installed) R.string.store_verified_installed
                        else R.string.store_verified_not_installed,
                        program.name
                    ),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    /** Win32 tap: runs the real guest preflight, then reports the
     *  findings honestly in a dialog. The AOT binary ships with
     *  the store now, so Install proceeds to the normal install
     *  when the user confirms; Cancel leaves the card
     *  NOT_INSTALLED. Launch runs only on arm64 and is unproven
     *  on a real device, and the dialog says so. */
    private fun runWin32Info(program: StoreProgram, onConfirm: () -> Unit) {
        if (!systemReady()) return
        setState(program.id, ProgramState.CHECKING)
        val appCtx = requireContext().applicationContext
        Thread {
            val preflight = ProgramStore.win32Preflight(appCtx, program)
            val message = ProgramStore.win32InfoMessage(appCtx, preflight)
            uiHandler.post {
                if (!isAdded) return@post
                MudifDialog.info(
                    requireContext(),
                    program.name,
                    message,
                    getString(R.string.install),
                    onPositive = { onConfirm() },
                    negative = getString(R.string.cancel),
                    onNegative = {
                        setState(program.id, ProgramState.NOT_INSTALLED)
                    }
                )
            }
        }.start()
    }

    private fun runInstall(program: StoreProgram) {
        if (!systemReady()) return
        if (program.method == "win32") {
            runWin32Info(program) { runInstallConfirmed(program) }
            return
        }
        runInstallConfirmed(program)
    }

    private fun runInstallConfirmed(program: StoreProgram) {
        if (program.method == "deb") {
            if (guestArch.isEmpty()) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.store_arch_unknown),
                    Snackbar.LENGTH_LONG
                ).show()
                return
            }
            if (ProgramStore.isArm64(guestArch) && program.debArm64 == null) {
                // P16: branded dialog.
                MudifDialog.info(
                    requireContext(),
                    getString(R.string.store_no_arm64_title),
                    getString(R.string.store_no_arm64),
                    getString(R.string.ok)
                )
                return
            }
        }
        setState(program.id, ProgramState.INSTALLING)
        val appCtx = requireContext().applicationContext
        // P19: visible progress dialog (stage + real percentage when
        // the install reports one). The card already shows a spinner;
        // the dialog is what tells the user HOW FAR the install has
        // got. p68: the dialog has a Pause button now; a pause kills
        // the guest command, the partial file stays, and the next
        // Install resumes it (or the cached copy).
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
                if (!control.paused) {
                    progress.setIndeterminate(true)
                    progress.setStatus(getString(R.string.store_status_checking))
                }
            }
            val (verified, ev) = if (result.exitCode == 0) {
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
                if (!isAdded) return@post
                if (control.paused) {
                    // p68: not a failure. The partial file stays, the
                    // next Install resumes it (or the cached copy).
                    states[program.id] = ProgramState.NOT_INSTALLED
                    evidence.remove(program.id)
                    programAdapter.refreshStates(states, evidence)
                    Snackbar.make(
                        binding.root,
                        getString(R.string.store_dl_paused_resume, lastPercent ?: 0),
                        Snackbar.LENGTH_LONG
                    ).show()
                } else if (result.exitCode == 0 && verified) {
                    states[program.id] = ProgramState.INSTALLED
                    evidence[program.id] = ev
                    programAdapter.refreshStates(states, evidence)
                    Snackbar.make(
                        binding.root,
                        getString(R.string.store_install_success, program.name),
                        Snackbar.LENGTH_LONG
                    ).show()
                } else {
                    states[program.id] = ProgramState.NOT_INSTALLED
                    evidence.remove(program.id)
                    programAdapter.refreshStates(states, evidence)
                    // P16: branded dialog.
                    MudifDialog.info(
                        requireContext(),
                        getString(R.string.store_install_failed_title),
                        getString(R.string.store_install_failed, program.name) +
                            "\n\n" + detail,
                        getString(R.string.ok)
                    )
                }
            }
        }.start()
    }

    private fun runUninstall(program: StoreProgram) {
        if (!systemReady()) return
        setState(program.id, ProgramState.UNINSTALLING)
        val appCtx = requireContext().applicationContext
        Thread {
            val result = ProgramStore.uninstall(appCtx, program)
            val (stillInstalled, _) = if (result.exitCode == 0) {
                ProgramStore.verify(appCtx, program)
            } else {
                true to ""
            }
            uiHandler.post {
                if (!isAdded) return@post
                if (result.exitCode == 0 && !stillInstalled) {
                    states[program.id] = ProgramState.NOT_INSTALLED
                    evidence.remove(program.id)
                    programAdapter.refreshStates(states, evidence)
                    Snackbar.make(
                        binding.root,
                        getString(R.string.store_uninstall_success, program.name),
                        Snackbar.LENGTH_LONG
                    ).show()
                } else {
                    states[program.id] = ProgramState.INSTALLED
                    programAdapter.refreshStates(states, evidence)
                    // P16: branded dialog.
                    MudifDialog.info(
                        requireContext(),
                        getString(R.string.store_uninstall_failed_title),
                        getString(R.string.store_uninstall_failed, program.name) +
                            "\n\n" + ProgramStore.lastLines(appCtx, result.output),
                        getString(R.string.ok)
                    )
                }
            }
        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
