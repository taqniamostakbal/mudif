package com.bolm.mudif.ui.apps

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.bolm.mudif.MainActivity
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentAppsBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.store.ProgramStore
import com.bolm.mudif.ui.store.StoreCatalog
import com.bolm.mudif.ui.store.StoreProgram
import com.google.android.material.snackbar.Snackbar

/**
 * Installed-apps tab (P18.5 rebuild). The old hardcoded 8-app list
 * and its dead placeholder button are gone: the list is built
 * live from the guest — catalog programs (with a binary) that
 * ProgramStore.batchStatus reports as installed, exactly like the
 * store derives its states. Each card launches or uninstalls its
 * program for real. Empty state links to the Store.
 */
class AppsFragment : Fragment() {
    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var adapter: AppsAdapter
    private var installed: List<StoreProgram> = emptyList()
    private val busyIds = HashSet<String>()
    private var loaded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), 2)
        adapter = AppsAdapter(
            onLaunch = { runLaunch(it) },
            onUninstall = { runUninstall(it) }
        )
        binding.recycler.adapter = adapter
        binding.btnGoStore.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.nav_store)
        }
        showLoading()
        loadInstalled()
    }

    override fun onResume() {
        super.onResume()
        // e.g. installed/uninstalled from the Store tab -> reflect it.
        if (loaded) loadInstalled()
    }

    /** Catalog + live guest status, same source as the store. */
    private fun loadInstalled() {
        val appCtx = requireContext().applicationContext
        Thread {
            if (!DebianSystem.isReady(appCtx)) {
                uiHandler.post {
                    if (!isAdded) return@post
                    loaded = true
                    installed = emptyList()
                    adapter.submitList(emptyList())
                    showEmpty(
                        getString(R.string.apps_empty_title),
                        getString(R.string.apps_system_not_ready),
                        showButton = true
                    )
                }
                return@Thread
            }
            val all = try {
                StoreCatalog.load(appCtx)
            } catch (e: Exception) {
                emptyList()
            }
            // The user wants fonts listed in the Apps list (2026-11-10:
            // he objected to the P18.6 category exclusion of نوتو).
            // Reverted to the binary test only, as in P18.5-tip.
            val launchable = all.filter { it.binary != null }
            val map = ProgramStore.batchStatus(appCtx, launchable)
            val list = launchable.filter { map[it.id] == true }
            uiHandler.post {
                if (!isAdded) return@post
                loaded = true
                installed = list
                adapter.submitList(list)
                if (list.isEmpty()) {
                    showEmpty(
                        getString(R.string.apps_empty_title),
                        getString(R.string.apps_empty_text),
                        showButton = true
                    )
                } else {
                    showList()
                }
            }
        }.start()
    }

    private fun showList() {
        binding.emptyState.visibility = View.GONE
        binding.recycler.visibility = View.VISIBLE
    }

    private fun showEmpty(title: String, message: String, showButton: Boolean) {
        binding.emptyTitle.text = title
        binding.emptyMessage.text = message
        binding.emptyMessage.visibility = View.VISIBLE
        binding.btnGoStore.visibility = if (showButton) View.VISIBLE else View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
    }

    private fun showLoading() {
        binding.emptyTitle.text = getString(R.string.downloading)
        binding.emptyMessage.visibility = View.GONE
        binding.btnGoStore.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
    }

    private fun setBusy(id: String, busy: Boolean) {
        if (busy) busyIds.add(id) else busyIds.remove(id)
        adapter.setBusy(busyIds.toSet())
    }

    /**
     * Launch via the shared path (AppLauncher — the same code the
     * Games tab uses). The old inline copy of this logic is gone;
     * see AppLauncher for the GUI/CLI decision, the PTY launch, and
     * the pgrep verify.
     */
    private fun runLaunch(program: StoreProgram) {
        AppLauncher.launch(this, program) { busy -> setBusy(program.id, busy) }
    }

    /** Same uninstall path as the store: exit 0 AND post-verify. */
    private fun runUninstall(program: StoreProgram) {
        if (!DebianSystem.isReady(requireContext())) {
            Snackbar.make(
                binding.root,
                getString(R.string.store_system_not_ready),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }
        setBusy(program.id, true)
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
                setBusy(program.id, false)
                if (result.exitCode == 0 && !stillInstalled) {
                    installed = installed.filter { it.id != program.id }
                    adapter.submitList(installed)
                    if (installed.isEmpty()) {
                        showEmpty(
                            getString(R.string.apps_empty_title),
                            getString(R.string.apps_empty_text),
                            showButton = true
                        )
                    }
                    Snackbar.make(
                        binding.root,
                        getString(R.string.store_uninstall_success, program.name),
                        Snackbar.LENGTH_LONG
                    ).show()
                } else {
                    MudifDialog.info(
                        requireContext(),
                        getString(R.string.store_uninstall_failed_title),
                        getString(R.string.store_uninstall_failed, program.name) +
                            "\n\n" + ProgramStore.lastLines(requireContext(), result.output),
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
