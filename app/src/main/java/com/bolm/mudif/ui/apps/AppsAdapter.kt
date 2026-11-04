package com.bolm.mudif.ui.apps

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.databinding.ItemAppBinding

class AppsAdapter(
    private val items: List<AppInfo>,
    private val onInstall: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppsAdapter.Holder>() {

    inner class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val a = items[position]
        with(holder.binding) {
            appName.text = a.name
            appDesc.text = a.desc
            appCategory.text = a.category
            appLetter.text = a.name.first().toString()
            appLetter.backgroundTintList = ColorStateList.valueOf(Color.parseColor(a.color))
            btnInstall.setOnClickListener { onInstall(a) }
        }
    }

    override fun getItemCount() = items.size
}
