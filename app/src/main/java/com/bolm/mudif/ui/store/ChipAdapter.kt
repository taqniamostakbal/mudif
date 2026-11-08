package com.bolm.mudif.ui.store

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.databinding.ItemCategoryChipBinding

/**
 * Horizontal category chip row (P10): الكل plus the 7 catalog
 * categories. Single selection; the selected chip is filled purple,
 * the rest are pale. Tapping a chip filters the program list.
 */
class ChipAdapter(
    private val categories: List<StoreCategory>,
    private var selectedKey: String,
    private val onSelect: (StoreCategory) -> Unit
) : RecyclerView.Adapter<ChipAdapter.Holder>() {

    inner class Holder(val binding: ItemCategoryChipBinding) :
        RecyclerView.ViewHolder(binding.root)

    fun setSelected(key: String) {
        if (selectedKey == key) return
        val old = selectedKey
        selectedKey = key
        categories.indexOfFirst { it.key == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        categories.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCategoryChipBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val category = categories[position]
        val selected = category.key == selectedKey
        with(holder.binding) {
            chipButton.text = category.label
            chipButton.isCheckable = true
            chipButton.isChecked = selected
            chipButton.setOnClickListener { onSelect(category) }
        }
    }

    override fun getItemCount() = categories.size
}
