package com.bolm.mudif.ui.store

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * p73: one cached ConstantState per gradient drawable. The store
 * adapters used to call ContextCompat.getDrawable on EVERY bind,
 * and each call re-parses the drawable XML and builds a new
 * GradientDrawable. On a 225-row category page that is 225 XML
 * inflations per layout pass, for drawables that never change.
 * A ConstantState is shared and immutable; newDrawable() hands
 * out a fresh instance per view with no XML parse. The cache is
 * keyed by resource id and never evicted: there are only about
 * ten gradient drawables in the app. A drawable with no constant
 * state falls back to a plain getDrawable every call (old
 * behavior), so this can never break a drawable type.
 */
object DrawableCache {
    private val states = HashMap<Int, Drawable.ConstantState>()

    fun get(context: Context, @DrawableRes res: Int): Drawable {
        val cached = synchronized(states) { states[res] }
        if (cached != null) return cached.newDrawable(context.resources)
        val drawable = ContextCompat.getDrawable(context, res)
            ?: throw IllegalArgumentException("missing drawable $res")
        drawable.constantState?.let { state ->
            synchronized(states) { states[res] = state }
        }
        return drawable
    }
}
