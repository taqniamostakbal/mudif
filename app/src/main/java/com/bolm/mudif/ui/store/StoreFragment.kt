package com.bolm.mudif.ui.store

import android.os.Bundle
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
import com.bolm.mudif.engine.RootfsManager
import com.google.android.material.snackbar.Snackbar

class StoreFragment : Fragment() {
    private var _binding: FragmentStoreBinding? = null
    private val binding get() = _binding!!

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
            // P6.1: the Debian card now reflects the real Debian install
            // (it was a permanent no-op before: "install_soon" toast).
            isInstalled = { d ->
                (d.name == "ألباين" && RootfsManager.isReady(requireContext())) ||
                    (d.name == "دبيان" && DebianSystem.isReady(requireContext()))
            },
            onInstall = { d -> onInstallClicked(d) }
        )
    }

    override fun onResume() {
        super.onResume()
        // e.g. installed from the profile tab -> reflect it here
        (_binding?.recycler?.adapter as? DistroAdapter)?.refresh()
    }

    private fun onInstallClicked(d: Distro) {
        when (d.name) {
            "ألباين" -> installAlpine()
            "دبيان" -> installDebian()
            else -> Snackbar.make(
                binding.root,
                getString(R.string.install_soon),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun installAlpine() {
        if (RootfsManager.isReady(requireContext())) {
            Snackbar.make(
                binding.root,
                getString(R.string.already_installed),
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        InstallFlow.runAlpineInstall(this) { ok, message ->
            if (ok) {
                (binding.recycler.adapter as? DistroAdapter)?.refresh()
                Snackbar.make(
                    binding.root,
                    getString(R.string.install_done),
                    Snackbar.LENGTH_LONG
                ).show()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.boot_test))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
