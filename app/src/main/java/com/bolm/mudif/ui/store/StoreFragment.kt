package com.bolm.mudif.ui.store

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentStoreBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.InstallFlow
import com.google.android.material.snackbar.Snackbar

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
    private lateinit var chipAdapter: ChipAdapter
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
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = DistroAdapter(
            DISTROS,
            // P6.4: the store lists Debian only (نظام مضيف is the
            // ONE system). The Alpine card and the install_soon
            // placeholders are gone.
            isInstalled = { d ->
                d.name == "دبيان" && DebianSystem.isReady(requireContext())
            },
            onInstall = { d -> onInstallClicked(d) }
        )

        chipAdapter = ChipAdapter(StoreCategory.entries, selectedCategory) { category ->
            selectedCategory = category.key
            chipAdapter.setSelected(category.key)
            applyFilter()
        }
        binding.chipRecycler.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.HORIZONTAL, false
        )
        binding.chipRecycler.adapter = chipAdapter

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

    override fun onResume() {
        super.onResume()
        // e.g. installed from the profile tab -> reflect it here
        (_binding?.recycler?.adapter as? DistroAdapter)?.refresh()
        if (statusesLoaded && DebianSystem.isReady(requireContext())) {
            refreshAllStatuses()
        }
    }

    private fun onInstallClicked(d: Distro) {
        when (d.name) {
            "دبيان" -> installDebian()
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
                (binding.recycler.adapter as? DistroAdapter)?.refresh()
                Snackbar.make(
                    binding.root,
                    getString(R.string.debian_install_done),
                    Snackbar.LENGTH_LONG
                ).show()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.system_install_title))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
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
            val arch = if (DebianSystem.isReady(appCtx)) ProgramStore.guestArch(appCtx) else ""
            uiHandler.post {
                if (!isAdded) return@post
                programs = loaded
                guestArch = arch
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

    /** Search (Arabic name, English name, description) plus category filter. */
    private fun applyFilter() {
        val q = query.trim()
        val filtered = programs.filter { p ->
            (selectedCategory == StoreCategory.ALL.key || p.category == selectedCategory) &&
                (q.isEmpty() || p.name.contains(q, ignoreCase = true) ||
                    p.nameEn.contains(q, ignoreCase = true) ||
                    p.desc.contains(q, ignoreCase = true))
        }
        val items = ArrayList<StoreListItem>()
        if (q.isEmpty()) {
            val groups = filtered.groupBy { it.category }
            for (category in StoreCategory.entries) {
                if (category == StoreCategory.ALL) continue
                val list = groups[category.key] ?: continue
                items.add(StoreListItem.Header(category.key, list.size))
                for (p in list) items.add(StoreListItem.Program(p))
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
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.store_no_arm64_title))
                    .setMessage(getString(R.string.store_no_arm64))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
                return
            }
        }
        setState(program.id, ProgramState.INSTALLING)
        val appCtx = requireContext().applicationContext
        Thread {
            val result = ProgramStore.install(appCtx, program, guestArch)
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
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.store_install_failed_title))
                        .setMessage(
                            getString(R.string.store_install_failed, program.name) +
                                "\n\n" + detail
                        )
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
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
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.store_uninstall_failed_title))
                        .setMessage(
                            getString(R.string.store_uninstall_failed, program.name) +
                                "\n\n" + ProgramStore.lastLines(result.output)
                        )
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                }
            }
        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
