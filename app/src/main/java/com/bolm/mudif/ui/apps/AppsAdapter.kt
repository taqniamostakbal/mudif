package com.bolm.mudif.ui.apps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ItemAppBinding
import com.bolm.mudif.ui.store.StoreCategory
import com.bolm.mudif.ui.store.StoreProgram

/**
 * Installed-apps list (P18.5 rebuild): the items are real StorePrograms
 * the guest reports as installed — no hardcoded list anymore. Each card
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
    private val iconCache = HashMap<String, Bitmap?>()

    inner class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    fun submitList(programs: List<StoreProgram>) {
        items = programs
        notifyDataSetChanged()
    }

    fun setBusy(busy: Set<String>) {
        busyIds = busy
        notifyDataSetChanged()
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
            appName.text = p.name
            appDesc.text = p.desc
            appCategory.text = StoreCategory.labelFor(p.category)

            val icon = iconCache.getOrPut(p.icon) {
                try {
                    ctx.assets.open("store-icons/${p.icon}").use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (icon != null) {
                appIcon.setImageBitmap(icon)
                appIcon.visibility = View.VISIBLE
                appLetter.visibility = View.GONE
            } else {
                // Same P19 fallback as the store: first letter on the
                // program's category gradient (squircle).
                appLetter.text = p.name.firstOrNull()?.toString() ?: "؟"
                val cat = StoreCategory.forKey(p.category)
                appLetter.background = ContextCompat.getDrawable(
                    ctx, cat?.gradientRes ?: R.drawable.cat_all
                )
                appLetter.visibility = View.VISIBLE
                appIcon.visibility = View.GONE
            }

            btnLaunch.isEnabled = !busy
            btnUninstall.isEnabled = !busy
            btnLaunch.setOnClickListener { onLaunch(p) }
            btnUninstall.setOnClickListener { onUninstall(p) }
        }
    }

    override fun getItemCount() = items.size
}
