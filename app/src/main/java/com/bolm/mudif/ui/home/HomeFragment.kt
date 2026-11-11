package com.bolm.mudif.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentHomeBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.InstallFlow
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.store.StoreCatalog
import com.bolm.mudif.ui.vnc.VncActivity

/**
 * P16: the home is no longer an empty welcome screen. It shows the ONE
 * system (نظام مضيف) with its real status and a button that opens the
 * desktop (or installs the system first), so the app feels like a real
 * computer from the first screen.
 */
class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnStore.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host, com.bolm.mudif.ui.store.StoreFragment())
                .commit()
            activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                R.id.bottom_nav
            )?.selectedItemId = R.id.nav_store
        }
        binding.btnOpenDesktop.setOnClickListener {
            if (DebianSystem.isReady(requireContext())) {
                openDesktop()
            } else {
                InstallFlow.runDebianInstall(this) { ok, message ->
                    if (ok) {
                        refreshSystemCard()
                        openDesktop()
                    } else {
                        MudifDialog.info(
                            requireContext(),
                            getString(R.string.system_install_title),
                            message,
                            getString(R.string.ok)
                        )
                    }
                }
            }
        }
        refreshSystemCard()
        // P19 Phase 2: the two stats that need I/O (catalog size, free
        // storage) load off the main thread; the system stat is in
        // refreshSystemCard.
        Thread {
            // Honest count: only real programs (a launchable binary).
            // The 7 fonts have no binary; counting all 113 entries
            // is the "113 programs" the user rightly called dishonest.
            // This matches the Store ALL card (106).
            val count = try {
                StoreCatalog.load(requireContext().applicationContext)
                    .count { it.binary != null }
            } catch (e: Exception) {
                0
            }
            val freeGb = try {
                val stat = StatFs(requireContext().filesDir.path)
                stat.availableBytes / 1_073_741_824.0
            } catch (e: Exception) {
                -1.0
            }
            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                binding.statPrograms.text = if (count > 0) "$count" else "—"
                binding.statStorage.text = if (freeGb >= 0) "%.1f GB".format(freeGb) else "—"
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // e.g. the system was installed from the Store tab -> status moves.
        if (_binding != null) refreshSystemCard()
    }

    private fun refreshSystemCard() {
        val ready = DebianSystem.isReady(requireContext())
        binding.systemStatus.text = getString(
            if (ready) R.string.home_system_installed
            else R.string.home_system_not_installed
        )
        binding.btnOpenDesktop.text = getString(
            if (ready) R.string.home_open_desktop
            else R.string.home_install_system
        )
        // P19 Phase 2: system stat mirrors the hero status.
        // The image version is shown so a screenshot always tells
        // which image is installed (p17-v1 vs p19-v2 vs p20).
        binding.statSystem.text = if (ready) {
            val v = DebianSystem.installedImageVersion(requireContext())
            if (v != null) getString(R.string.ui_home_stat_installed) + " · $v" else getString(R.string.ui_home_stat_installed)
        } else getString(R.string.ui_home_stat_not_installed)
    }

    private fun openDesktop() {
        // P16.2: bring the existing VncActivity forward instead of
        // stacking a second one (singleTop + CLEAR_TOP). A second
        // instance would run a second startDesktop thread racing the
        // first on the PTY and the X server (the P16.1 second-launch
        // stall). onNewIntent re-runs the flow if nothing is running.
        startActivity(
            Intent(requireContext(), VncActivity::class.java).apply {
                putExtra(VncActivity.EXTRA_DESKTOP, VncActivity.DESKTOP_MUDIF)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
