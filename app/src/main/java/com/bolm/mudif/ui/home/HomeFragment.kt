package com.bolm.mudif.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentHomeBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.InstallFlow
import com.bolm.mudif.ui.MudifDialog
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
    }

    private fun openDesktop() {
        startActivity(
            Intent(requireContext(), VncActivity::class.java).apply {
                putExtra(VncActivity.EXTRA_DESKTOP, VncActivity.DESKTOP_MUDIF)
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
