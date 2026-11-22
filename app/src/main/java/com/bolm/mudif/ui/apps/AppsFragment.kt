package com.bolm.mudif.ui.apps

import android.content.res.Configuration
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
 * live from the guest, catalog programs (with a binary) that
 * ProgramStore.batchStatus reports as installed, exactly like the
 * store derives its states. Each card launches or uninstalls its
 * program for real. Empty state links to the Store.
 */
class AppsFragment : Fragment() {
    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var adapter: AppsAdapter
    private lateinit var gridManager: GridLayoutManager
    private var installed: List<StoreProgram> = emptyList()
    private val busyIds = HashSet<String>()
    private var loaded = false
    // p70: the last check failed (guest unreachable). The button
    // then retries the check instead of opening the store.
    private var checkFailed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        gridManager = GridLayoutManager(requireContext(), spanForOrientation())
        binding.recycler.layoutManager = gridManager
        applyOrientation()
        adapter = AppsAdapter(
            onLaunch = { runLaunch(it) },
            onUninstall = { runUninstall(it) }
        )
        binding.recycler.adapter = adapter
        binding.btnGoStore.setOnClickListener {
            if (checkFailed) {
                checkFailed = false
                showLoading()
                loadInstalled()
            } else {
                (activity as? MainActivity)?.selectTab(R.id.nav_store)
            }
        }
        showLoading()
        loadInstalled()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // MainActivity declares configChanges, so a rotation does not
        // recreate this fragment: re-apply the orientation layout here.
        if (::gridManager.isInitialized) applyOrientation(newConfig.orientation)
    }

    /** Landscape grid: 4 columns, so 4 cards share one row and
     *  المفكرة (installed index 6) sits in row 2 instead of row 4.
     *  Portrait keeps the 2-column grid. */
    private fun spanForOrientation(
        orientation: Int = resources.configuration.orientation
    ): Int = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 2

    /** Landscape header: the 287 px portrait header plus the ~204 px
     *  bottom nav left the list a 229 px strip in a 720 px frame, less
     *  than one 445 px card row, so the list looked unscrollable. In
     *  landscape the header compacts (8dp padding, 20sp title, no
     *  subtitle), which frees about 170 px for the list. Portrait
     *  restores the original header exactly. */
    private fun applyOrientation(
        orientation: Int = resources.configuration.orientation
    ) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        if (::gridManager.isInitialized) gridManager.spanCount = spanForOrientation(orientation)
        val density = resources.displayMetrics.density
        val pad = ((if (landscape) 8 else 24) * density).toInt()
        binding.headerBar.setPadding(pad, pad, pad, pad)
        binding.headerTitle.textSize = if (landscape) 20f else 28f
        binding.headerSubtitle.visibility = if (landscape) View.GONE else View.VISIBLE
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
            // Slice 4 (killer build): a Windows program has no Linux
            // binary, but it IS launchable (its AOT binary runs
            // through AppLauncher.launchDirect), so it belongs in
            // this list too. Fonts stay excluded exactly as before.
            val launchable = all.filter { it.binary != null || it.method == "win32" }
            val map = ProgramStore.batchStatus(appCtx, launchable)
            if (map == null) {
                // p70: the check itself failed. Never show this as
                // "nothing installed": that silent lie is the defect.
                uiHandler.post {
                    if (!isAdded) return@post
                    loaded = true
                    checkFailed = true
                    installed = emptyList()
                    adapter.submitList(emptyList())
                    showCheckFailed()
                }
                return@Thread
            }
            val list = launchable.filter { map[it.id] == true }
            uiHandler.post {
                if (!isAdded) return@post
                loaded = true
                checkFailed = false
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
        binding.btnGoStore.text = getString(R.string.apps_go_to_store)
        binding.btnGoStore.visibility = if (showButton) View.VISIBLE else View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
    }

    /** The guest check itself failed: never show this as "nothing
     *  installed" (that silent lie is the p70 defect). The button
     *  retries the check instead of opening the store. */
    private fun showCheckFailed() {
        binding.emptyTitle.text = getString(R.string.apps_check_failed_title)
        binding.emptyMessage.text = getString(R.string.apps_check_failed_text)
        binding.emptyMessage.visibility = View.VISIBLE
        binding.btnGoStore.text = getString(R.string.ui_vnc_fail_retry)
        binding.btnGoStore.visibility = View.VISIBLE
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
     * Launch via the shared path (AppLauncher, the same code the
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
