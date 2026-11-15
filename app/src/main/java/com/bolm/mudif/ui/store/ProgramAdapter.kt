package com.bolm.mudif.ui.store

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ItemProgramBinding
import com.bolm.mudif.databinding.ItemSectionHeaderBinding

/** One row in the program list: a category header or a program card. */
sealed class StoreListItem {
    data class Header(val category: String, val count: Int) : StoreListItem()
    data class Program(val program: StoreProgram) : StoreListItem()
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
    private val onUninstall: (StoreProgram) -> Unit
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
                notifyItemChanged(position)
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            ProgramHolder(ItemProgramBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is StoreListItem.Header -> (holder as HeaderHolder).bind(item)
            is StoreListItem.Program -> (holder as ProgramHolder).bind(item.program, position)
        }
    }

    inner class HeaderHolder(private val binding: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StoreListItem.Header) {
            binding.headerTitle.text = binding.root.context.getString(
                R.string.store_section_count,
                StoreCategory.labelFor(binding.root.context, item.category),
                item.count
            )
        }
    }

    inner class ProgramHolder(private val binding: ItemProgramBinding) :
        RecyclerView.ViewHolder(binding.root) {

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
            binding.programLetter.background = ContextCompat.getDrawable(
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
            val busy = state == ProgramState.CHECKING ||
                state == ProgramState.INSTALLING ||
                state == ProgramState.UNINSTALLING
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
            binding.btnVerify.setOnClickListener { onVerify(program) }
            binding.btnInstall.setOnClickListener { onInstall(program) }
            binding.btnUninstall.setOnClickListener { onUninstall(program) }

            if (animatedIds.add(program.id)) {
                val delay = (position.coerceAtMost(12) * 25).toLong()
                val fade = ObjectAnimator.ofFloat(binding.root, View.ALPHA, 0f, 1f)
                val slide = ObjectAnimator.ofFloat(
                    binding.root, View.TRANSLATION_Y, 24f, 0f
                )
                AnimatorSet().apply {
                    playTogether(fade, slide)
                    duration = 200
                    startDelay = delay
                    start()
                }
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PROGRAM = 1
        private const val ICON_TILE_DP = 56

        private val DIFF = object : DiffUtil.ItemCallback<StoreListItem>() {
            override fun areItemsTheSame(old: StoreListItem, new: StoreListItem): Boolean {
                return when {
                    old is StoreListItem.Header && new is StoreListItem.Header ->
                        old.category == new.category
                    old is StoreListItem.Program && new is StoreListItem.Program ->
                        old.program.id == new.program.id
                    else -> false
                }
            }

            override fun areContentsTheSame(old: StoreListItem, new: StoreListItem): Boolean =
                old == new
        }
    }
}
