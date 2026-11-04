package com.bolm.mudif.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentHomeBinding

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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
