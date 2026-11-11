package com.bolm.mudif.ui.games

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ItemGameBinding
import com.bolm.mudif.ui.store.ProgramState
import com.bolm.mudif.ui.store.StoreProgram

/**
 * Mudif Arcade grid, wired to the real catalog: the items are the
 * "games" entries of store-catalog.json (StoreProgram), not the old
 * hardcoded ARCADE_ENTRIES list (deleted with Arcade.kt). Each card
 * shows the real store icon PNG from assets/store-icons/ (the
 * gamepad glyph is only the fallback when the PNG is missing), the
 * catalog Fusha description, the live install state, and ONE working
 * action: install when not installed, launch when installed. Busy
 * rows disable their action while an install or a launch runs.
 * States live in a map owned by the fragment; this adapter only
 * renders them, so a status refresh never rebuilds the list.
 */
class ArcadeAdapter(
    private val onInstall: (StoreProgram) -> Unit,
    private val onLaunch: (StoreProgram) -> Unit
) : RecyclerView.Adapter<ArcadeAdapter.Holder>() {

    private var items: List<StoreProgram> = emptyList()
    private var states: Map<String, ProgramState> = emptyMap()
    private var busyIds: Set<String> = emptySet()
    private val iconCache = HashMap<String, Bitmap?>()

    inner class Holder(val binding: ItemGameBinding) :
        RecyclerView.ViewHolder(binding.root)

    fun submitList(programs: List<StoreProgram>) {
        items = programs
        notifyDataSetChanged()
    }

    /** New states from the fragment: rebind visible cards, no relist. */
    fun refreshStates(states: Map<String, ProgramState>) {
        this.states = states
        notifyDataSetChanged()
    }

    fun setBusy(busy: Set<String>) {
        busyIds = busy
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemGameBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val program = items[position]
        val ctx = holder.itemView.context
        val state = states[program.id] ?: ProgramState.UNKNOWN
        val busy = busyIds.contains(program.id) ||
            state == ProgramState.CHECKING ||
            state == ProgramState.INSTALLING ||
            state == ProgramState.UNINSTALLING
        with(holder.binding) {
            cardBg.setBackgroundResource(R.drawable.cat_games)

            // Real store icon; the gamepad glyph stays only as the
            // fallback when the catalog PNG is missing.
            val icon = iconCache.getOrPut(program.icon) {
                try {
                    ctx.assets.open("store-icons/${program.icon}").use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (icon != null) {
                gameIcon.setImageBitmap(icon)
            } else {
                gameIcon.setImageResource(R.drawable.ic_gamepad)
            }

            gameName.text = program.name
            gameDesc.text = program.desc
            gameBadge.text = when (state) {
                ProgramState.INSTALLED -> ctx.getString(R.string.store_status_installed)
                ProgramState.NOT_INSTALLED -> ctx.getString(R.string.store_status_not_installed)
                ProgramState.CHECKING -> ctx.getString(R.string.store_status_checking)
                ProgramState.INSTALLING -> ctx.getString(R.string.store_status_installing)
                ProgramState.UNINSTALLING -> ctx.getString(R.string.store_status_uninstalling)
                ProgramState.UNKNOWN -> ctx.getString(R.string.store_status_unknown)
            }
            val installed = state == ProgramState.INSTALLED
            gameAction.text = when {
                busy -> ctx.getString(R.string.store_status_installing)
                installed -> ctx.getString(R.string.apps_launch)
                else -> ctx.getString(R.string.games_install_action)
            }
            gameAction.isEnabled = !busy
            val action = {
                if (installed) onLaunch(program) else onInstall(program)
            }
            gameAction.setOnClickListener { action() }
            root.setOnClickListener { if (!busy) action() }
        }
    }

    override fun getItemCount() = items.size
}
