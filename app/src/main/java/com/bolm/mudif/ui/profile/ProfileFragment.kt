package com.bolm.mudif.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentProfileBinding
import com.bolm.mudif.engine.EngineCheck
import com.bolm.mudif.engine.InstallFlow
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
