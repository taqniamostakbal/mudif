package com.bolm.mudif.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View

/**
 * p56: card entrance animation (fade + slide up 24px, 200ms, the
 * same motion as the store program list in ProgramAdapter).
 * Static screens (home, profile) play it once per card on first
 * view, staggered; list adapters play it once per item id on first
 * bind (they keep their own animated-ids set, like ProgramAdapter).
 * Pure ViewPropertyAnimator/ObjectAnimator, no library.
 */
object CardEntrance {

    /** Play the entrance on [view] after [delayMs]. Safe to call on
     *  any view; it only touches alpha and translationY. */
    fun play(view: View, delayMs: Long) {
        view.alpha = 0f
        view.translationY = 24f
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 24f, 0f)
            )
            duration = 200
            startDelay = delayMs
            start()
        }
    }

    /** Stagger for a static screen: card [index] enters [index] * 25ms
     *  in, capped at 12 steps like the store list. */
    fun staggerDelay(index: Int): Long = (index.coerceAtMost(12) * 25).toLong()
}
