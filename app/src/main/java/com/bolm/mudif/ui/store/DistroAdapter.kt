package com.bolm.mudif.ui.store

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.databinding.ItemDistroBinding

class DistroAdapter(
    private val items: List<Distro>,
    private val onInstall: (Distro) -> Unit
) : RecyclerView.Adapter<DistroAdapter.Holder>() {

    inner class Holder(val binding: ItemDistroBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDistroBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val d = items[position]
        with(holder.binding) {
            distroName.text = d.name
            distroDesc.text = d.desc
            distroSize.text = d.size
            distroLetter.text = d.name.first().toString()
            distroLetter.backgroundTintList = ColorStateList.valueOf(Color.parseColor(d.color))
            btnInstall.setOnClickListener { onInstall(d) }
        }
    }

    override fun getItemCount() = items.size
}
