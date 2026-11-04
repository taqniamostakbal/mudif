package com.bolm.mudif.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentProfileBinding
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
