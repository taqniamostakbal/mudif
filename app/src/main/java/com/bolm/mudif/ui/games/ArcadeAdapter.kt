package com.bolm.mudif.ui.games

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ItemGameBinding
import com.bolm.mudif.ui.CardEntrance
import com.bolm.mudif.ui.store.ProgramState
import com.bolm.mudif.ui.store.StoreIconLoader
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

    // p56: entrance plays once per game (fade + slide). The set is
    // per adapter instance, so a rebind after a refresh does not
    // replay it.
    private val animatedIds = mutableSetOf<String>()

    inner class Holder(val binding: ItemGameBinding) :
        RecyclerView.ViewHolder(binding.root)

    fun submitList(programs: List<StoreProgram>) {
        items = programs
        notifyDataSetChanged()
    }

    /**
     * New states from the fragment: rebind only the cards whose
     * state actually changed. The fragment mutates and re-passes the
     * same map instance, so snapshot it first; without the copy the
     * diff would compare the map to itself and see nothing. A full
     * notifyDataSetChanged here rebound every visible row on every
     * status refresh, which amplified the scroll ANR (defect 2).
     */
    fun refreshStates(states: Map<String, ProgramState>) {
        val oldStates = this.states
        this.states = states.toMap()
        for (index in items.indices) {
            val id = items[index].id
            if (oldStates[id] != states[id]) notifyItemChanged(index)
        }
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
            // p56: every game card is the standard purple gradient
            // (the per-category colors are gone, like the rest of
            // the app). The icon tile keeps its per-category
            // gradient: it is an icon well, not a card.
            cardBg.setBackgroundResource(R.drawable.home_stat_gradient)
            if (animatedIds.add(program.id)) {
                CardEntrance.play(root, CardEntrance.staggerDelay(position))
            }

            // Gamepad glyph first; it stays when the catalog PNG is
            // missing. The real icon loads off the main thread;
            // decoding in bind froze scrolling (defect 2). The loader
            // only applies the bitmap while this holder is still
            // bound to this program.
            gameIcon.setImageResource(R.drawable.ic_gamepad)
            StoreIconLoader.load(ctx, program.icon, ICON_TILE_DP, gameIcon) { bitmap ->
                if (bitmap != null) gameIcon.setImageBitmap(bitmap)
            }

            gameName.text = program.displayName(ctx)
            gameDesc.text = program.displayDesc(ctx)
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

    companion object {
        private const val ICON_TILE_DP = 32
    }
}
