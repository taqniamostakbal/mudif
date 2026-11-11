package com.bolm.mudif.ui.store

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentStoreCategoryBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.InstallService
import com.bolm.mudif.ui.MudifDialog
import com.google.android.material.snackbar.Snackbar

/**
 * Phase 1c: dedicated category page. Tapping a category card in the
 * store opens this page (slide transition) with only that category's
 * programs, a search box scoped to the category, and the same
 * verify/install/uninstall actions as the store. Back returns to
 * the store. The install logic mirrors StoreFragment exactly (same
 * progress dialog, foreground service, verify-after-install rule).
 */
class StoreCategoryFragment : Fragment() {
    private var _binding: FragmentStoreCategoryBinding? = null
    private val binding get() = _binding!!

    private var categoryKey: String = StoreCategory.ALL.key
    private var programs: List<StoreProgram> = emptyList()
    private var guestArch: String = ""
    private var query: String = ""
    private var statusesLoaded: Boolean = false
    private val states = HashMap<String, ProgramState>()
    private val evidence = HashMap<String, String>()
    private lateinit var programAdapter: ProgramAdapter
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        categoryKey = arguments?.getString(ARG_CATEGORY) ?: StoreCategory.ALL.key
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStoreCategoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val category = StoreCategory.forKey(categoryKey) ?: StoreCategory.ALL
        binding.categoryHeader.background =
            ContextCompat.getDrawable(requireContext(), category.gradientRes)
        binding.categoryTitle.text = category.label
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        programAdapter = ProgramAdapter(
            onVerify = { p -> runVerify(p) },
            onInstall = { p -> runInstall(p) },
            onUninstall = { p -> runUninstall(p) }
        )
        binding.programsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.programsRecycler.adapter = programAdapter

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                applyFilter()
            }
        })

        loadCatalog()
    }

    /** Loads the catalog, keeps only this category, then batch-checks status. */
    private fun loadCatalog() {
        val appCtx = requireContext().applicationContext
        Thread {
            val loaded = try {
                StoreCatalog.load(appCtx).filter { it.category == categoryKey }
            } catch (e: Exception) {
                emptyList()
            }
            val arch = if (DebianSystem.isReady(appCtx)) ProgramStore.guestArch(appCtx) else ""
            uiHandler.post {
                if (!isAdded) return@post
                programs = loaded
                guestArch = arch
                val category = StoreCategory.forKey(categoryKey) ?: StoreCategory.ALL
                binding.categorySubtitle.text = getString(
                    R.string.store_section_count, category.label, loaded.size
                )
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

    /** One guest call for all programs; missing entries keep UNKNOWN. */
    private fun refreshAllStatuses() {
        val appCtx = requireContext().applicationContext
        Thread {
            val map = ProgramStore.batchStatus(appCtx, programs)
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

    /** Search (Arabic name, English name, description) within this category. */
    private fun applyFilter() {
        val q = query.trim()
        val filtered = programs.filter { p ->
            q.isEmpty() || p.name.contains(q, ignoreCase = true) ||
                p.nameEn.contains(q, ignoreCase = true) ||
                p.desc.contains(q, ignoreCase = true)
        }
        val items = ArrayList<StoreListItem>()
        for (p in filtered) items.add(StoreListItem.Program(p))
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

    private fun runInstall(program: StoreProgram) {
        if (!systemReady()) return
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
        val progress = MudifDialog.progress(requireContext(), program.name)
        progress.setStatus(getString(R.string.store_status_installing))
        progress.setIndeterminate(true)
        InstallService.start(appCtx, "تثبيت ${program.name}…")
        Thread {
            val result = ProgramStore.install(appCtx, program, guestArch) { stage, percent ->
                InstallService.updateProgress(
                    if (percent != null) "$stage $percent%" else stage, percent
                )
                uiHandler.post {
                    if (percent != null) {
                        progress.setProgress(percent * 10)
                        progress.setStatus("$stage $percent%")
                    } else {
                        progress.setIndeterminate(true)
                        progress.setStatus(stage)
                    }
                }
            }
            uiHandler.post {
                progress.setIndeterminate(true)
                progress.setStatus(getString(R.string.store_status_checking))
            }
            val (verified, ev) = if (result.exitCode == 0) {
                ProgramStore.verify(appCtx, program)
            } else {
                false to ProgramStore.lastLine(result.output)
            }
            val detail = if (result.exitCode == 0 && !verified) {
                ProgramStore.lastLine(result.output)
            } else {
                ProgramStore.lastLines(result.output)
            }
            uiHandler.post {
                progress.dismiss()
                InstallService.stop(appCtx)
                if (!isAdded) return@post
                if (result.exitCode == 0 && verified) {
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
                    programAdapter.refreshStates(states, evidence)
                    Snackbar.make(
                        binding.root,
                        getString(R.string.store_uninstall_success, program.name),
                        Snackbar.LENGTH_LONG
                    ).show()
                } else {
                    states[program.id] = ProgramState.INSTALLED
                    programAdapter.refreshStates(states, evidence)
                    MudifDialog.info(
                        requireContext(),
                        getString(R.string.store_uninstall_failed_title),
                        getString(R.string.store_uninstall_failed, program.name) +
                            "\n\n" + ProgramStore.lastLines(result.output),
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

    companion object {
        private const val ARG_CATEGORY = "category"
        fun newInstance(categoryKey: String): StoreCategoryFragment {
            return StoreCategoryFragment().apply {
                arguments = Bundle().apply { putString(ARG_CATEGORY, categoryKey) }
            }
        }
    }
}
