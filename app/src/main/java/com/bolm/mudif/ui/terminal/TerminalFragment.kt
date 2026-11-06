package com.bolm.mudif.ui.terminal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bolm.mudif.databinding.FragmentTerminalBinding
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.RootfsManager
import com.google.android.material.snackbar.Snackbar
import com.offsec.nhterm.frontend.session.terminal.BasicViewClient

/**
 * P5.22 RADICAL REBUILD — real terminal (Tiny Container's
 * TerminalFragment shape): a NetHunter TerminalView attached to the
 * SAME GuestSession PTY session that owns the container. After the
 * boot `exec`, typing here goes straight into the guest login shell.
 *
 * This DELETES the old one-shot form (EditText + send button +
 * TerminalSession.runCommand per command). That form is what broke
 * in P5.21 (input focus died after a few commands, and every command
 * was a fresh proot that killed backgrounded GUI apps the moment it
 * returned). A real terminal has none of those failure modes.
 */
class TerminalFragment : Fragment() {
    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // P6: the terminal boots the ACTIVE system (the last one the
        // user opened — Debian after نظام مضيف, Alpine otherwise).
        val system = GuestSession.currentSystem
        val ready = if (system == GuestSession.GuestSystem.DEBIAN) {
            DebianSystem.isReady(requireContext())
        } else {
            RootfsManager.isReady(requireContext())
        }
        if (!ready) {
            Snackbar.make(binding.root, "النظام غير مثبت — ثبّت نظام مضيف من حسابي أولا", Snackbar.LENGTH_LONG).show()
            binding.terminalView.visibility = View.INVISIBLE
            return
        }
        // The session may not exist yet (terminal opened before the
        // desktop): boot the container in the background, then attach.
        val appContext = requireContext().applicationContext
        Thread {
            val ok = GuestSession.bootContainer(appContext, system)
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (!ok || GuestSession.session == null) {
                    Snackbar.make(
                        binding.root,
                        "تعذّر تشغيل الحاوية: ${GuestSession.lastError ?: ""}",
                        Snackbar.LENGTH_LONG
                    ).show()
                    binding.terminalView.visibility = View.INVISIBLE
                    return@runOnUiThread
                }
                attachSession()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // Re-attach if the session was replaced (fresh boot) while
        // this fragment was paused.
        if (_binding != null && GuestSession.session != null) {
            attachSession()
        }
    }

    private fun attachSession() {
        val session = GuestSession.session ?: return
        binding.terminalView.setTextSize(36)
        binding.terminalView.attachSession(session)
        // P5.24 FIX: the view's key handler (TerminalView.onKeyDown)
        // calls terminalViewClient — without a client, ANY keypress
        // NPE-crashes the whole app (P5.23 test, logcat). Tiny
        // Container always sets BasicViewClient. Skipped as
        // "optional" in P5.22; the test proved it is required.
        binding.terminalView.setTerminalViewClient(BasicViewClient(binding.terminalView))
        binding.terminalView.setBackgroundColor(0xFF141428.toInt())
        binding.terminalView.onScreenUpdated()
        GuestSession.onScreenUpdate = {
            activity?.runOnUiThread {
                if (_binding != null) binding.terminalView.onScreenUpdated()
            }
        }
        binding.terminalView.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        GuestSession.onScreenUpdate = null
        _binding = null
    }
}
