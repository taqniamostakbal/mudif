package com.bolm.mudif.ui.games

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentGamesBinding
import com.bolm.mudif.ui.MudifDialog

/**
 * P19 Phase 3: Mudif Arcade — the curated ARM64 games + emulators
 * grid (replaces the P16 "coming soon" screen, now that the user
 * ordered games). Tapping an entry shows the honest "install coming
 * soon" dialog; the real one-click installs land with P18.4.
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recycler.adapter = ArcadeAdapter(ARCADE_ENTRIES) { entry ->
            MudifDialog.info(
                requireContext(),
                entry.name,
                getString(R.string.install_soon),
                getString(R.string.ok)
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
