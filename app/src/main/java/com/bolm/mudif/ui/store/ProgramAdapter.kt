package com.bolm.mudif.ui.store

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ItemProgramBinding
import com.bolm.mudif.databinding.ItemSectionHeaderBinding
import com.bolm.mudif.databinding.ItemUnifiedResultBinding
import com.bolm.mudif.ui.CardEntrance

/** One row in the program list: a category header, a program card,
 *  or one unified-search result (take 3). */
sealed class StoreListItem {
    data class Header(val category: String, val count: Int) : StoreListItem()
    data class Program(val program: StoreProgram) : StoreListItem()
    data class Unified(val result: UnifiedSearch.UnifiedResult) : StoreListItem()
}

/**
 * Program catalog list (P10): section headers plus program cards.
 * Each card shows the real icon, the Fusha description, a live
 * status line, and the three actions (verify / install / uninstall).
 * States live in maps owned by the fragment; this adapter only renders
 * them, so a status refresh never rebuilds the list.
 */
class ProgramAdapter(
    private val onVerify: (StoreProgram) -> Unit,
    private val onInstall: (StoreProgram) -> Unit,
    private val onUninstall: (StoreProgram) -> Unit,
    /** Take 3: tap on a unified-search row. Default no-op keeps the
     *  category fragment compiling unchanged (it has no search). */
    private val onUnified: (UnifiedSearch.UnifiedResult) -> Unit = {}
) : ListAdapter<StoreListItem, RecyclerView.ViewHolder>(DIFF) {

    private var states: Map<String, ProgramState> = emptyMap()
    private var evidence: Map<String, String> = emptyMap()
    private val animatedIds = HashSet<String>()

    /**
     * New states from the fragment: rebind only the cards whose
     * state or evidence actually changed. The fragment mutates and
     * re-passes the same map instance, so snapshot both maps first;
     * without the copy the diff would compare the map to itself and
     * see nothing. A full notifyDataSetChanged here rebound every
     * visible row on every status refresh, which amplified the
     * scroll ANR (defect 2).
     *
     * p73: the change is delivered as a payload, so the row takes
     * the status-only path ([ProgramHolder.bindState]) instead of
     * a full rebind: no icon reload, no drawable inflation, no
     * text reset, no new click listeners. A batch status refresh
     * that changes 200 rows costs 200 status updates, not 200
     * full rebinds.
     */
    fun refreshStates(states: Map<String, ProgramState>, evidence: Map<String, String>) {
        val oldStates = this.states
        val oldEvidence = this.evidence
        this.states = states.toMap()
        this.evidence = evidence.toMap()
        val changed = HashSet<String>()
        for ((id, state) in states) {
            if (oldStates[id] != state) changed.add(id)
        }
        for (id in oldStates.keys) {
            if (!states.containsKey(id)) changed.add(id)
        }
        for ((id, ev) in evidence) {
            if (oldEvidence[id] != ev) changed.add(id)
        }
        for (id in oldEvidence.keys) {
            if (!evidence.containsKey(id)) changed.add(id)
        }
        if (changed.isEmpty()) return
        for (position in currentList.indices) {
            val item = currentList[position]
            if (item is StoreListItem.Program && changed.contains(item.program.id)) {
                notifyItemChanged(position, PAYLOAD_STATE)
            }
        }
    }

    /** Submit a filtered list and replay the entrance animation once. */
    fun submitListAnimated(items: List<StoreListItem>) {
        animatedIds.clear()
        submitList(items)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is StoreListItem.Header -> TYPE_HEADER
        is StoreListItem.Program -> TYPE_PROGRAM
        is StoreListItem.Unified -> TYPE_UNIFIED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
            TYPE_UNIFIED -> UnifiedHolder(ItemUnifiedResultBinding.inflate(inflater, parent, false))
            else -> ProgramHolder(ItemProgramBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf<Any>())
    }

    /** p73: with a state payload the row takes the status-only
     *  path; without one it takes the full bind. Headers have no
     *  state and always take the full bind. */
    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        when (val item = getItem(position)) {
            is StoreListItem.Header -> (holder as HeaderHolder).bind(item)
            is StoreListItem.Unified -> (holder as UnifiedHolder).bind(item.result)
            is StoreListItem.Program -> {
                val programHolder = holder as ProgramHolder
                if (payloads.contains(PAYLOAD_STATE)) programHolder.bindState(item.program)
                else programHolder.bind(item.program, position)
            }
        }
    }

    /** p70: stop the row's entrance animation and its pending icon
     *  load when the row leaves the screen. Without this, a fling
     *  left dozens of AnimatorSets mutating recycled views and
     *  queued icon decodes for off-screen rows. */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        (holder as? ProgramHolder)?.recycle()
    }

    inner class HeaderHolder(private val binding: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StoreListItem.Header) {
            // Take 3: the unified-search section header is not a
            // catalog category, so it carries its own label.
            if (item.category == "unified") {
                binding.headerTitle.text = binding.root.context.getString(
                    R.string.store_section_count,
                    binding.root.context.getString(R.string.unified_search_title),
                    item.count
                )
                return
            }
            binding.headerTitle.text = binding.root.context.getString(
                R.string.store_section_count,
                StoreCategory.labelFor(binding.root.context, item.category),
                item.count
            )
        }
    }

    /** Take 3: one unified-search row. A tap opens it (Android app,
     *  Linux program, or settings shortcut). */
    inner class UnifiedHolder(private val binding: ItemUnifiedResultBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(result: UnifiedSearch.UnifiedResult) {
            binding.unifiedLabel.text = result.label
            binding.unifiedSubtitle.text = result.subtitle.ifBlank { result.source }
            binding.unifiedSubtitle.visibility =
                if (result.subtitle.isBlank()) View.GONE else View.VISIBLE
            binding.unifiedSource.text = result.source
            binding.root.setOnClickListener { onUnified(result) }
        }
    }

    inner class ProgramHolder(private val binding: ItemProgramBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** p70: cancel this row's entrance animation and pending
         *  icon load when the row is recycled. Called from the
         *  adapter's onViewRecycled, so a fling cannot leave
         *  animators and decodes running for off-screen rows. */
        fun recycle() {
            CardEntrance.cancel(binding.root)
            StoreIconLoader.cancel(binding.programIcon)
        }

        fun bind(program: StoreProgram, position: Int) {
            val ctx = binding.root.context
            binding.programName.text = program.displayName(ctx)
            binding.programNameEn.text = program.nameEn
            binding.programNameEn.visibility =
                if (program.nameEn == binding.programName.text.toString()) View.GONE else View.VISIBLE
            binding.programDesc.text = program.displayDesc(ctx)

            // Letter tile first (P19 Phase 1: the letter sits on the
            // program's category gradient, not a plain purple circle).
            // It stays until the real icon arrives, and it is the final
            // rendering when the icon file is missing. The icon loads
            // off the main thread; decoding in bind froze scrolling
            // (defect 2). The loader only applies the bitmap while
            // this holder is still bound to this program.
            binding.programLetter.text = program.name.firstOrNull()?.toString() ?: "؟"
            val cat = StoreCategory.forKey(program.category)
            binding.programLetter.background = DrawableCache.get(
                ctx, cat?.gradientRes ?: R.drawable.cat_all
            )
            binding.programLetter.visibility = View.VISIBLE
            binding.programIcon.visibility = View.GONE
            StoreIconLoader.load(ctx, program.icon, ICON_TILE_DP, binding.programIcon) { bitmap ->
                if (bitmap != null) {
                    binding.programIcon.setImageBitmap(bitmap)
                    binding.programIcon.visibility = View.VISIBLE
                    binding.programLetter.visibility = View.GONE
                }
            }

            val state = states[program.id] ?: ProgramState.UNKNOWN
            bindState(program, state)
            // Tested badge: rig-verified install only. The catalog
            // value names the arch + date (e.g. "x86_64 · 2026-11-10");
            // a null/blank value hides the badge entirely.
            val tested = program.tested
            if (!tested.isNullOrBlank()) {
                binding.programTestedBadge.text =
                    ctx.getString(R.string.store_tested_badge, tested)
                binding.programTestedBadge.visibility = View.VISIBLE
            } else {
                binding.programTestedBadge.visibility = View.GONE
            }

            binding.btnVerify.setOnClickListener { onVerify(program) }
            binding.btnInstall.setOnClickListener { onInstall(program) }
            binding.btnUninstall.setOnClickListener { onUninstall(program) }

            // p70: the entrance animation runs only for the first
            // screenful. Rows bound during a fling never animate:
            // before this, every first bind started an AnimatorSet
            // that was never cancelled on recycle, so a fling left
            // dozens of sets mutating recycled views.
            if (position < 12 && animatedIds.add(program.id)) {
                CardEntrance.play(binding.root, CardEntrance.staggerDelay(position))
            }
        }

        /** p73: status-only rebind. Updates nothing but the live
         *  state views: status text, evidence, progress spinner,
         *  button enabled states. No icon load, no drawable
         *  inflation, no text reset, no new click listeners, no
         *  animation. The full [bind] delegates its state section
         *  here so the two paths can never drift apart. */
        fun bindState(program: StoreProgram) {
            bindState(program, states[program.id] ?: ProgramState.UNKNOWN)
        }

        private fun bindState(program: StoreProgram, state: ProgramState) {
            val ctx = binding.root.context
            val busy = state == ProgramState.CHECKING ||
                state == ProgramState.INSTALLING ||
                state == ProgramState.UNINSTALLING
            binding.programProgress.visibility = if (busy) View.VISIBLE else View.GONE
            binding.programStatus.text = when (state) {
                ProgramState.INSTALLED -> ctx.getString(R.string.store_status_installed)
                ProgramState.NOT_INSTALLED -> ctx.getString(R.string.store_status_not_installed)
                ProgramState.CHECKING -> ctx.getString(R.string.store_status_checking)
                ProgramState.INSTALLING -> ctx.getString(R.string.store_status_installing)
                ProgramState.UNINSTALLING -> ctx.getString(R.string.store_status_uninstalling)
                ProgramState.UNKNOWN -> ctx.getString(R.string.store_status_unknown)
            }
            val ev = evidence[program.id]
            if (state == ProgramState.INSTALLED && !ev.isNullOrBlank()) {
                binding.programEvidence.text = ev
                binding.programEvidence.visibility = View.VISIBLE
            } else {
                binding.programEvidence.visibility = View.GONE
            }

            binding.btnVerify.isEnabled = !busy
            binding.btnInstall.isEnabled = !busy && state != ProgramState.INSTALLED
            binding.btnUninstall.isEnabled = !busy && state == ProgramState.INSTALLED
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PROGRAM = 1
        private const val TYPE_UNIFIED = 2
        private const val ICON_TILE_DP = 56
        /** p73: payload marking a status-only rebind (see
         *  [ProgramHolder.bindState]). */
        private const val PAYLOAD_STATE = "state"

        private val DIFF = object : DiffUtil.ItemCallback<StoreListItem>() {
            override fun areItemsTheSame(old: StoreListItem, new: StoreListItem): Boolean {
                return when {
                    old is StoreListItem.Header && new is StoreListItem.Header ->
                        old.category == new.category
                    old is StoreListItem.Program && new is StoreListItem.Program ->
                        old.program.id == new.program.id
                    old is StoreListItem.Unified && new is StoreListItem.Unified ->
                        old.result.kind == new.result.kind && old.result.id == new.result.id
                    else -> false
                }
            }

            override fun areContentsTheSame(old: StoreListItem, new: StoreListItem): Boolean =
                old == new
        }
    }
}
