package com.bolm.mudif.ui.terminal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentTerminalBinding
import com.bolm.mudif.engine.RootfsManager
import com.bolm.mudif.engine.TerminalSession

/**
 * Terminal tab: type a command, it runs inside the Alpine rootfs via proot,
 * output appears in the dark terminal view. Commands run on a background
 * thread; one command at a time (send is disabled while running).
 */
class TerminalFragment : Fragment() {
    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!
    private var running = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (RootfsManager.isReady(requireContext())) {
            appendOutput("طرفية مضيف — Alpine جاهزة.\nاكتب أمرا ثم اضغط تنفيذ.\n")
        } else {
            appendOutput("⚠ النظام غير مثبت.\nثبت Alpine من المتجر أولا ثم عد هنا.\n")
        }

        binding.btnSend.setOnClickListener { sendCommand() }
        binding.btnClear.setOnClickListener { binding.terminalOutput.text = "" }
        binding.inputCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else false
        }
    }

    private fun sendCommand() {
        if (running) return
        val cmd = binding.inputCommand.text.toString().trim()
        if (cmd.isEmpty()) return

        binding.inputCommand.text?.clear()
        appendOutput("~$ $cmd")

        running = true
        binding.btnSend.isEnabled = false

        Thread {
            val result = TerminalSession.runCommand(requireContext(), cmd)
            activity?.runOnUiThread {
                running = false
                if (_binding == null) return@runOnUiThread
                binding.btnSend.isEnabled = true
                appendOutput(result.output)
                if (result.timedOut) {
                    appendOutput("[انتهت المهلة]")
                } else if (result.exitCode != 0) {
                    appendOutput("[خرج بالكود ${result.exitCode}]")
                }
            }
        }.start()
    }

    private fun appendOutput(text: String) {
        binding.terminalOutput.append(text + "\n")
        binding.terminalScroll.post {
            binding.terminalScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
