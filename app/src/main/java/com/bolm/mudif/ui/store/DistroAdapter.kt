package com.bolm.mudif.ui.store

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.databinding.ItemDistroBinding

class DistroAdapter(
    private val items: List<Distro>,
    private val isInstalled: (Distro) -> Boolean = { false },
    private val onInstall: (Distro) -> Unit
) : RecyclerView.Adapter<DistroAdapter.Holder>() {

    inner class Holder(val binding: ItemDistroBinding) : RecyclerView.ViewHolder(binding.root)

    fun refresh() = notifyDataSetChanged()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDistroBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val d = items[position]
        with(holder.binding) {
            distroName.text = d.name
            distroDesc.text = root.context.getString(d.descRes)
            distroSize.text = d.size
            // Phase 1b: the icon is the penguin logo in the layout
            // (@drawable/logo_mudif); no letter is set here.
            distroLogo.backgroundTintList = ColorStateList.valueOf(Color.parseColor(d.color))
            val installed = isInstalled(d)
            btnInstall.text = if (installed) root.context.getString(com.bolm.mudif.R.string.ui_home_stat_installed) else root.context.getString(com.bolm.mudif.R.string.install)
            btnInstall.isEnabled = !installed
            btnInstall.setOnClickListener { onInstall(d) }
        }
    }

    override fun getItemCount() = items.size
}
