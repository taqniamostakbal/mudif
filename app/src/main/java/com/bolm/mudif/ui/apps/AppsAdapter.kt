package com.bolm.mudif.ui.apps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ItemAppBinding
import com.bolm.mudif.ui.CardEntrance
import com.bolm.mudif.ui.store.DrawableCache
import com.bolm.mudif.ui.store.StoreCategory
import com.bolm.mudif.ui.store.StoreIconLoader
import com.bolm.mudif.ui.store.StoreProgram

/**
 * Installed-apps list (P18.5 rebuild): the items are real StorePrograms
 * the guest reports as installed, no hardcoded list anymore. Each card
 * shows the store icon (or the P19 letter tile on the category
 * gradient when the icon is missing) and two working actions:
 * Launch and Uninstall. The P19 look is kept (squircle tile, pill
 * buttons). Busy rows disable their buttons while a launch or an
 * uninstall runs.
 */
class AppsAdapter(
    private val onLaunch: (StoreProgram) -> Unit,
    private val onUninstall: (StoreProgram) -> Unit
) : RecyclerView.Adapter<AppsAdapter.Holder>() {

    private var items: List<StoreProgram> = emptyList()
    private var busyIds: Set<String> = emptySet()

    // p56: entrance plays once per app (fade + slide). The set is
    // per adapter instance, so a rebind after a refresh does not
    // replay it.
    private val animatedIds = mutableSetOf<String>()

    inner class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    fun submitList(programs: List<StoreProgram>) {
        items = programs
        notifyDataSetChanged()
    }

    /** New busy set: rebind only the rows whose busy flag flipped. */
    fun setBusy(busy: Set<String>) {
        val oldBusy = busyIds
        busyIds = busy.toSet()
        for (index in items.indices) {
            val id = items[index].id
            if (oldBusy.contains(id) != busyIds.contains(id)) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val p = items[position]
        val ctx = holder.binding.root.context
        val busy = busyIds.contains(p.id)
        with(holder.binding) {
            if (position < 12 && animatedIds.add(p.id)) {
                CardEntrance.play(root, CardEntrance.staggerDelay(position))
            }
            appName.text = p.name
            appDesc.text = p.desc
            appCategory.text = StoreCategory.labelFor(ctx, p.category)

            // Letter tile first (same P19 fallback as the store:
            // first letter on the program's category gradient). It
            // stays until the real icon arrives, and it is the final
            // rendering when the icon file is missing. The icon loads
            // off the main thread; decoding in bind froze scrolling
            // (defect 2). The loader only applies the bitmap while
            // this holder is still bound to this program.
            appLetter.text = p.name.firstOrNull()?.toString() ?: "؟"
            val cat = StoreCategory.forKey(p.category)
            appLetter.background = DrawableCache.get(
                ctx, cat?.gradientRes ?: R.drawable.cat_all
            )
            appLetter.visibility = View.VISIBLE
            appIcon.visibility = View.GONE
            StoreIconLoader.load(ctx, p.icon, ICON_TILE_DP, appIcon) { bitmap ->
                if (bitmap != null) {
                    appIcon.setImageBitmap(bitmap)
                    appIcon.visibility = View.VISIBLE
                    appLetter.visibility = View.GONE
                }
            }

            btnLaunch.isEnabled = !busy
            btnUninstall.isEnabled = !busy
            btnLaunch.setOnClickListener { onLaunch(p) }
            btnUninstall.setOnClickListener { onUninstall(p) }
        }
    }

    override fun getItemCount() = items.size

    /** p70: stop the row's entrance animation and its pending icon
     *  load when the row leaves the screen. */
    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        CardEntrance.cancel(holder.binding.root)
        StoreIconLoader.cancel(holder.binding.appIcon)
    }

    companion object {
        private const val ICON_TILE_DP = 56
    }
}
