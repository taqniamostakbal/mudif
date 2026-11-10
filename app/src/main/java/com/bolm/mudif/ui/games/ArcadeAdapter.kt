package com.bolm.mudif.ui.games

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.databinding.ItemGameBinding

/**
 * P19 Phase 3: the Arcade grid adapter. One gradient card per entry;
 * tapping an entry calls onSelect (the fragment shows the honest
 * "coming soon" dialog — the installs land with P18.4).
 */
class ArcadeAdapter(
    private val entries: List<ArcadeEntry>,
    private val onSelect: (ArcadeEntry) -> Unit
) : RecyclerView.Adapter<ArcadeAdapter.Holder>() {

    inner class Holder(val binding: ItemGameBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemGameBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        with(holder.binding) {
            cardBg.setBackgroundResource(entry.gradientRes)
            gameName.text = entry.name
            gameDesc.text = entry.desc
            root.setOnClickListener { onSelect(entry) }
        }
    }

    override fun getItemCount() = entries.size
}
