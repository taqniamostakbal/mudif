package com.bolm.mudif.ui.apps

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.bolm.mudif.MainActivity
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentAppsBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.TerminalSession
import com.bolm.mudif.engine.X11Session
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.store.ProgramStore
import com.bolm.mudif.ui.store.StoreCatalog
import com.bolm.mudif.ui.store.StoreProgram
import com.bolm.mudif.ui.vnc.VncActivity
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
     * Launch the program's binary in the guest desktop session.
     * GUI or CLI is decided for real: a .desktop file whose Exec
     * runs this binary means a window of its own; otherwise the
     * binary opens inside qterminal (LXQt's terminal, in the image).
     * The command goes through the live PTY session
     * (GuestSession.sendCommand — the proven launchDesktop
     * mechanism): a one-shot proot cannot keep a GUI process alive
     * after it exits. When no desktop session is live, the launch
     * is handed to VncActivity, which boots the desktop and fires
     * the pending app once it is confirmed running. Either way the
     * user ends up in the viewer, looking at the running app.
     */
    private fun runLaunch(program: StoreProgram) {
        val binary = program.binary ?: return
        setBusy(program.id, true)
        val appCtx = requireContext().applicationContext
        Thread {
            val guiCheck = TerminalSession.runCommandDebian(
                appCtx,
                "grep -rl '^Exec=[^ ]*\\b$binary\\b' " +
                    "/usr/share/applications/*.desktop 2>/dev/null | head -1",
                idleTimeoutSec = 15,
                maxTimeoutSec = 30
            )
            val isGui = guiCheck.exitCode == 0 &&
                guiCheck.output.isNotBlank() &&
                guiCheck.output.trim() != "(لا مخرجات)"
            // Extra launch flags (e.g. Electron apps). VSCodium
            // crashes on launch (code 5) without --no-sandbox
            // --disable-gpu --disable-dev-shm-usage in proot.
            val flags = program.flags?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
            val launchCmd = if (isGui) {
                "$binary$flags >/dev/null 2>&1 &"
            } else {
                "qterminal -e sh -c '$binary$flags; exec sh' >/dev/null 2>&1 &"
            }
            val live = GuestSession.alive && GuestSession.booted &&
                X11Session.isTcpDisplayOpen()
            if (live && GuestSession.sendCommand(launchCmd)) {
                // Verify the process actually started before claiming
                // success. The old code Toasted "launched" on `sent`
                // alone, which only proves bytes reached the PTY, not
                // that the program opened — failures were invisible
                // (output goes to /dev/null). Now a real check decides.
                val started = verifyStarted(appCtx, program)
                uiHandler.post {
                    if (!isAdded) return@post
                    setBusy(program.id, false)
                    if (started) {
                        X11Session.openViewer(requireContext())
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.apps_launched, program.name),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        MudifDialog.info(
                            requireContext(),
                            program.name,
                            getString(R.string.apps_launch_failed, program.name),
                            getString(R.string.ok)
                        )
                    }
                }
                return@Thread
            }
            uiHandler.post {
                if (!isAdded) return@post
                setBusy(program.id, false)
                val intent = Intent(requireContext(), VncActivity::class.java).apply {
                    putExtra(VncActivity.EXTRA_DESKTOP, VncActivity.DESKTOP_MUDIF)
                    putExtra(VncActivity.EXTRA_LAUNCH_APP, launchCmd)
                    addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.apps_launching, program.name),
                    Toast.LENGTH_SHORT
                ).show()
                startActivity(intent)
            }
        }.start()
    }

    /**
     * True if the program's process is running in the guest.
     * Checks twice (after 4s, then after 6s more) so slow apps
     * (LibreOffice, Electron) get time to start. pgrep -x matches
     * the exact process name; [StoreProgram.process] is the name
     * to check (defaults to the binary). A missing pgrep binary
     * or a failed check returns false (fail safe: report the
     * launch as failed rather than lie about it).
     */
    private fun verifyStarted(appCtx: Context, program: StoreProgram): Boolean {
        val process = program.process?.takeIf { it.isNotBlank() }
            ?: program.binary ?: return false
        repeat(2) { attempt ->
            Thread.sleep(if (attempt == 0) 4000 else 6000)
            val check = TerminalSession.runCommandDebian(
                appCtx,
                "pgrep -x \"$process\" >/dev/null 2>&1",
                idleTimeoutSec = 10,
                maxTimeoutSec = 15
            )
            if (check.exitCode == 0) return true
        }
        return false
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
}
