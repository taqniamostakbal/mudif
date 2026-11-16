package com.bolm.mudif.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import com.bolm.mudif.R

/**
 * p56: card entrance animation (fade + slide up 24px, 200ms, the
 * same motion as the store program list in ProgramAdapter).
 * Static screens (home, profile) play it once per card on first
 * view, staggered; list adapters play it once per item id on first
 * bind (they keep their own animated-ids set, like ProgramAdapter).
 * Pure ViewPropertyAnimator/ObjectAnimator, no library.
 *
 * p70: play returns the running AnimatorSet and tags it on the
 * view (R.id.card_entrance_animator), so an adapter's
 * onViewRecycled can cancel it with [cancel]. Before this, a
 * fling left dozens of sets running on recycled views, each still
 * animating the alpha and translationY of a row that now shows a
 * different program.
 */
object CardEntrance {

    /** Play the entrance on [view] after [delayMs]. Returns the
     *  running AnimatorSet, tagged on the view for [cancel].
     *  Safe to call on any view; it only touches alpha and
     *  translationY. */
    fun play(view: View, delayMs: Long): AnimatorSet {
        // Never two sets on one view: drop any set left over from a
        // previous bind before starting the new one.
        (view.getTag(R.id.card_entrance_animator) as? AnimatorSet)?.cancel()
        view.alpha = 0f
        view.translationY = 24f
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 24f, 0f)
            )
            duration = 200
            startDelay = delayMs
        }
        view.setTag(R.id.card_entrance_animator, set)
        set.start()
        return set
    }

    /** Cancel the entrance set running on [view], if any, and
     *  restore the view to its settled state (fully visible, no
     *  offset). A cancelled set leaves the view at whatever alpha
     *  it had reached, so the restore matters: without it a
     *  recycled row could stay half-transparent. */
    fun cancel(view: View) {
        val set = view.getTag(R.id.card_entrance_animator) as? AnimatorSet
        if (set != null) {
            set.cancel()
            view.setTag(R.id.card_entrance_animator, null)
        }
        view.alpha = 1f
        view.translationY = 0f
    }

    /** Stagger for a static screen: card [index] enters [index] * 25ms
     *  in, capped at 12 steps like the store list. */
    fun staggerDelay(index: Int): Long = (index.coerceAtMost(12) * 25).toLong()
}
