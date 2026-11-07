package com.bolm.mudif.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentProfileBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.EngineCheck
import com.bolm.mudif.engine.InstallFlow
import com.bolm.mudif.engine.RootfsManager
import com.bolm.mudif.ui.logs.LogViewerActivity
import com.bolm.mudif.ui.terminal.TerminalActivity
import com.bolm.mudif.ui.vnc.VncActivity
import com.google.android.material.snackbar.Snackbar

class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val soon = View.OnClickListener {
            Snackbar.make(binding.root, getString(R.string.coming_soon), Snackbar.LENGTH_SHORT).show()
        }
        binding.rowLanguage.setOnClickListener(soon)
        binding.rowAbout.setOnClickListener(soon)
        binding.cardSystems.setOnClickListener(soon)
        binding.cardApps.setOnClickListener(soon)
        binding.rowEngine.setOnClickListener {
            Snackbar.make(binding.root, getString(R.string.engine_running), Snackbar.LENGTH_SHORT).show()
            Thread {
                val result = EngineCheck.runProotVersion(requireContext())
                activity?.runOnUiThread {
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.engine_check))
                        .setMessage(result)
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                }
            }.start()
        }
        binding.rowBoot.setOnClickListener { runBootTest() }
        refreshCounters()
        binding.rowTerminal.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), TerminalActivity::class.java))
        }
        binding.rowVnc.setOnClickListener {
            startActivity(
                android.content.Intent(requireContext(), VncActivity::class.java).apply {
                    putExtra(VncActivity.EXTRA_DESKTOP, VncActivity.DESKTOP_LXQT)
                }
            )
        }
        binding.rowVncXfce.setOnClickListener {
            startActivity(
                android.content.Intent(requireContext(), VncActivity::class.java).apply {
                    putExtra(VncActivity.EXTRA_DESKTOP, VncActivity.DESKTOP_XFCE)
                }
            )
        }
        // P6 — نظام مضيف: the ONE system (Debian 13.1 + XFCE). First
        // tap downloads + extracts Debian (35 MB, hash-verified),
        // later taps go straight to the desktop.
        binding.rowVncMudif.setOnClickListener {
            if (!DebianSystem.isReady(requireContext())) {
                InstallFlow.runDebianInstall(this) { ok, message ->
                    if (ok) {
                        startActivity(
                            android.content.Intent(requireContext(), VncActivity::class.java).apply {
                                putExtra(VncActivity.EXTRA_DESKTOP, VncActivity.DESKTOP_MUDIF)
                            }
                        )
                    } else {
                        AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.system_install_title))
                            .setMessage(message)
                            .setPositiveButton(getString(R.string.ok), null)
                            .show()
                    }
                }
            } else {
                startActivity(
                    android.content.Intent(requireContext(), VncActivity::class.java).apply {
                        putExtra(VncActivity.EXTRA_DESKTOP, VncActivity.DESKTOP_MUDIF)
                    }
                )
            }
        }
        binding.rowLogs.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), LogViewerActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // e.g. Debian installed from the Store tab -> counters must move
        refreshCounters()
    }

    /**
     * P6.1 — the counters were hardcoded "0" in the layout while
     * Alpine + Firefox + LibreOffice were already installed (the tabs
     * test proved it). They now read real state: systems = installed
     * rootfses (Alpine + Debian), apps = desktop apps actually
     * present on disk (firefox/firefox-esr + soffice, checked by
     * plain File.exists — no guest execution needed).
     */
    private fun refreshCounters() {
        val ctx = requireContext()
        val systems = (if (RootfsManager.isReady(ctx)) 1 else 0) +
            (if (DebianSystem.isReady(ctx)) 1 else 0)
        val alpineRoot = RootfsManager.rootfsDir(ctx)
        val debianRoot = DebianSystem.debianRootfsDir(ctx)
        val hasFirefox = java.io.File(alpineRoot, "usr/bin/firefox").exists() ||
            java.io.File(debianRoot, "usr/bin/firefox-esr").exists()
        val hasOffice = java.io.File(alpineRoot, "usr/bin/soffice").exists() ||
            java.io.File(debianRoot, "usr/bin/soffice").exists()
        val apps = (if (hasFirefox) 1 else 0) + (if (hasOffice) 1 else 0)
        binding.textSystemsCount.text = systems.toString()
        binding.textAppsCount.text = apps.toString()
    }

    private fun runBootTest() {
        InstallFlow.runAlpineInstall(this) { _, message ->
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.boot_test))
                .setMessage(message)
                .setPositiveButton(getString(R.string.ok), null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
