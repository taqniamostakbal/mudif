package com.bolm.mudif.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentProfileBinding
import com.bolm.mudif.engine.EngineCheck
import com.bolm.mudif.engine.RootfsManager
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
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val status = TextView(ctx).apply {
            text = getString(R.string.downloading)
            setPadding(0, 0, 0, pad / 2)
        }
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
            progress = 0
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(bar)
        }
        val progressDialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.boot_test))
            .setView(layout)
            .setCancelable(false)
            .create()
        progressDialog.show()

        val ui = { block: () -> Unit -> activity?.runOnUiThread(block) }
        Thread {
            try {
                if (!RootfsManager.isReady(ctx)) {
                    ui { status.text = getString(R.string.downloading) }
                    RootfsManager.download(ctx) { done, total ->
                        ui {
                            if (total > 0) {
                                bar.progress = ((done * 1000) / total).toInt().coerceIn(0, 1000)
                                status.text = "${getString(R.string.downloading)} ${(done * 100) / total}%"
                            }
                        }
                    }
                    ui {
                        status.text = getString(R.string.extracting)
                        bar.isIndeterminate = true
                    }
                    RootfsManager.extract(ctx)
                }
                ui {
                    status.text = getString(R.string.booting)
                    bar.isIndeterminate = true
                }
                val result = RootfsManager.firstBoot(ctx)
                ui {
                    progressDialog.dismiss()
                    AlertDialog.Builder(ctx)
                        .setTitle(getString(R.string.boot_test))
                        .setMessage(result)
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                }
            } catch (e: Exception) {
                ui {
                    progressDialog.dismiss()
                    AlertDialog.Builder(ctx)
                        .setTitle(getString(R.string.boot_test))
                        .setMessage("خطأ: ${e.message}")
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                }
            }
        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
