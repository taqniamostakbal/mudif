package com.bolm.mudif.ui.store

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ItemCategoryCardBinding
import com.bolm.mudif.ui.CardEntrance

/**
 * P19 Phase 1: category gradient cards (replaces the plain filter chips).
 * p56: every card is the standard purple gradient + white icon +
 * label + program count (the per-category colors are gone, like the
 * rest of the app). Tapping a card filters the program list, same
 * as the old chips. The selected card gets a white stroke. The
 * letter tile inside a category is not a card; it keeps its
 * per-category gradient.
 */
class CategoryCardAdapter(
    private val categories: List<StoreCategory>,
    private var selectedKey: String,
    private var counts: Map<String, Int>,
    private val onSelect: (StoreCategory) -> Unit
) : RecyclerView.Adapter<CategoryCardAdapter.Holder>() {

    // p56: entrance plays once per category (fade + slide). The set
    // is per adapter instance, so a rebind after a store refresh
    // does not replay it.
    private val animatedKeys = mutableSetOf<String>()

    inner class Holder(val binding: ItemCategoryCardBinding) :
        RecyclerView.ViewHolder(binding.root)

    fun setSelected(key: String) {
        if (selectedKey == key) return
        val old = selectedKey
        selectedKey = key
        categories.indexOfFirst { it.key == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        categories.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    fun setCounts(newCounts: Map<String, Int>) {
        counts = newCounts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCategoryCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val category = categories[position]
        val selected = category.key == selectedKey
        // Honest counts: "الكل" counts only real programs (entries
        // with a launchable binary). Fonts are not programs, they
        // are counted on their own card as خطوط, never as برامج.
        val count = if (category == StoreCategory.ALL) {
            counts.entries
                .filter { it.key != StoreCategory.FONTS.key }
                .sumOf { it.value }
        } else counts[category.key] ?: 0
        val unit = if (category == StoreCategory.FONTS) holder.binding.root.context.getString(com.bolm.mudif.R.string.ui_store_unit_fonts) else holder.binding.root.context.getString(com.bolm.mudif.R.string.ui_store_unit_programs)
        with(holder.binding) {
            cardBg.setBackgroundResource(R.drawable.home_stat_gradient)
            if (animatedKeys.add(category.key)) {
                CardEntrance.play(root, CardEntrance.staggerDelay(position))
            }
            catIcon.setImageResource(category.iconRes)
            catLabel.text = root.context.getString(category.labelRes)
            catCount.text = "$count $unit"
            root.strokeWidth = if (selected) dp(3, holder) else 0
            root.strokeColor = Color.WHITE
            root.setOnClickListener { onSelect(category) }
        }
    }

    override fun getItemCount() = categories.size

    /** p70: stop the card's entrance animation when the card leaves
     *  the screen. No icon load to cancel here (vector resource).
     *  Only 9 categories exist, so the position gate is moot: all
     *  of them are on the first screen by definition. */
    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        CardEntrance.cancel(holder.binding.root)
    }

    private fun dp(v: Int, holder: Holder): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            holder.binding.root.resources.displayMetrics
        ).toInt()
}
