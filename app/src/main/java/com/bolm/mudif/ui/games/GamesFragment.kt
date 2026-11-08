package com.bolm.mudif.ui.games

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bolm.mudif.databinding.FragmentGamesBinding

/**
 * P16: games are NOT now (the user's order: "الألعاب ليس الآن").
 * The tab is a clean "قريباً" (coming soon) screen — no fake
 * installable list. The real gaming container (Box64+Wine) comes later.
 */
class GamesFragment : Fragment() {
    private var _binding: FragmentGamesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGamesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
