package com.bolm.mudif.ui.games

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.bolm.mudif.R
import com.bolm.mudif.databinding.FragmentGamesBinding
import com.bolm.mudif.ui.store.Distro
import com.bolm.mudif.ui.store.DistroAdapter
import com.google.android.material.snackbar.Snackbar

// Catalog is static for now; the real gaming container (Box64+Wine) comes later.
val GAMES = listOf(
    Distro("ستارديو فالي", "ابنِ مزرعتك وعش حياة هادئة", "1GB", "#4CAF50"),
    Distro("هولو نايت", "مغامرة ساحرة في مملكة غامضة", "2GB", "#37474F"),
    Distro("تومب رايدر 2013", "مغامرات لارا كروفت الأسطورية", "15GB", "#8D6E63"),
    Distro("جي تي إيه 5", "عالم مفتوح بلا حدود", "70GB", "#0288D1"),
    Distro("ماينكرافت", "ابنِ أي شيء تتخيله", "1GB", "#7CB342"),
    Distro("بورتال 2", "ألغاز ذكية ومرحة", "8GB", "#FF9800")
)

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
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = DistroAdapter(GAMES) {
            Snackbar.make(
                binding.root,
                getString(R.string.install_soon),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
