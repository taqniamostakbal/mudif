package com.bolm.mudif.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentProfileBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.EngineCheck
import com.bolm.mudif.engine.InstallFlow
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.logs.LogViewerActivity
import com.bolm.mudif.ui.settings.SettingsActivity
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
        // P18.3 — Viewer settings screen (Fusha, Mudif style).
        binding.rowViewerSettings.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.cardSystems.setOnClickListener(soon)
        binding.cardApps.setOnClickListener(soon)
        binding.rowEngine.setOnClickListener {
            Snackbar.make(binding.root, getString(R.string.engine_running), Snackbar.LENGTH_SHORT).show()
            Thread {
                val result = EngineCheck.runProotVersion(requireContext())
                activity?.runOnUiThread {
                    // P16: branded dialog.
                    MudifDialog.info(
                        requireContext(),
                        getString(R.string.engine_check),
                        result,
                        getString(R.string.ok)
                    )
                }
            }.start()
        }
        refreshCounters()
        binding.rowTerminal.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), TerminalActivity::class.java))
        }
        // P6.4 — نظام مضيف is the ONLY system in this app (the
        // user's order 2026-11-07). The Alpine desktop rows
        // (row_vnc LXQt, row_vnc_xfce XFCE) and the Alpine boot
        // test (row_boot) are GONE — nothing else launches a
        // desktop from this tab.
        // P6 — نظام مضيف: the ONE system (Debian 13.1 + LXQt). First
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
                        // P16: branded dialog.
                        MudifDialog.info(
                            requireContext(),
                            getString(R.string.system_install_title),
                            message,
                            getString(R.string.ok)
                        )
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
     * test proved it). They now read real state.
     * P6.2 FIX (P6.1 test): /usr/bin/soffice is an ABSOLUTE symlink
     * to /usr/lib/libreoffice/program/soffice, which does NOT resolve
     * on the Android host (File.exists follows the link against the
     * host's /usr/lib) — so the apps counter read 1 instead of 2
     * while LibreOffice IS installed. Check the real target path
     * inside each rootfs as well.
     * P6.4 — نظام مضيف is the ONLY system: the counters read the
     * Debian rootfs only (Alpine is gone from the app).
     */
    private fun refreshCounters() {
        val ctx = requireContext()
        val systems = if (DebianSystem.isReady(ctx)) 1 else 0
        val debianRoot = DebianSystem.debianRootfsDir(ctx)
        val hasFirefox = java.io.File(debianRoot, "usr/bin/firefox-esr").exists()
        val hasOffice = java.io.File(debianRoot, "usr/bin/soffice").exists() ||
            java.io.File(debianRoot, "usr/lib/libreoffice/program/soffice").exists()
        val apps = (if (hasFirefox) 1 else 0) + (if (hasOffice) 1 else 0)
        binding.textSystemsCount.text = systems.toString()
        binding.textAppsCount.text = apps.toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
