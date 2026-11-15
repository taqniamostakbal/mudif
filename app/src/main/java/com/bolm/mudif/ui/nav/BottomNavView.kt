package com.bolm.mudif.ui.nav

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.bolm.mudif.R

/**
 * p48: the bottom nav, rebuilt as a custom view with Lottie animated
 * icons. Anas ordered a library for the animated icons
 * ("استعن بمكتبات"), so each tab is a LottieAnimationView playing a
 * LottieFiles JSON from res/raw/ instead of a built-in AVD morph.
 *
 * Why the Material BottomNavigationView + AVDs are gone: the selected
 * agent AVD (nav_agent_select.xml) rotated its sparkle group 90
 * degrees with no pivot set, so the group pivot defaulted to (0,0)
 * and the rotation moved the icon off the 24x24 viewport. The
 * selected pill rendered empty on Anas's phone (image-392, proven by
 * pixel measurement: exactly 1 distinct color in the pill, zero icon
 * pixels). Lottie has no such pivot trap: the animation is a JSON
 * timeline, and the icon is a LottieAnimationView that always shows
 * its current frame.
 *
 * The look matches the p45 reference (image-389): a floating white
 * stadium bar, the selected tab a pale #E4DEFF pill (64x44dp) with a
 * clearly visible purple icon, the label below the pill. The pill
 * and the label are per-tab views here, not a Material indicator.
 *
 * p52: the p48 claim that the icon "can never be empty" was wrong.
 * The Home icon was invisible on Anas's phone (image-395, proven by
 * pixel measurement: zero ink pixels in the Home tab) because
 * nav_lottie_home.json carries a Trim Paths modifier whose end
 * animates 0% at frame 0 to 100% at frame 65: the idle frame draws
 * 0% of the path, so the icon is empty at rest and after every
 * deselect. Fixed in the JSON itself (the Trim Paths item removed,
 * frame 0 now shows the full house); the scale pop (frames 65-117)
 * is untouched, it starts from a fully drawn house. A nav icon must
 * be visible at frame 0: any JSON whose idle frame is empty is a
 * broken icon, not an animation choice.
 *
 * Per-tab animation (plays once on select, ends on the idle frame):
 * home pops (frames 65-117), store squashes (0-12, the badge and its
 * "1" text layer start at frame 13 and never show), apps shuffles
 * its grid (16-122 at 3x), games wobbles (0-99 at 3x), agent
 * robot pops (0-10). Deselect cancels the animation and resets
 * the icon to its idle frame. A re-tap re-notifies the listener
 * (the fragment re-shows) without replaying the animation.
 *
 * The icon view size differs per tab: the five Lottie canvases hold
 * different amounts of ink (home 73%, store 62%, apps 53%, games
 * 52%, agent robot 72% of canvas height at frame 0, measured with the
 * trim-aware renderer in test-evidence-realcomp/bottomnav-p52), so
 * a uniform view size would render uneven icons. Each size below
 * was measured from the real JSON ink bounds so every icon renders
 * at 22dp of ink height. The tint is a single color filter over
 * the whole composition (KeyPath "**"), purple when selected, dark
 * gray #424242 otherwise (p52: the old #757575 was too pale for
 * thin outline icons at this size; #424242 is 9.6:1 on white).
 */
class BottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private data class TabConfig(
        val id: Int,
        val labelRes: Int,
        val lottieRes: Int,
        val iconSizeDp: Int,
        val animMinFrame: Int,
        val animMaxFrame: Int,
        val animSpeed: Float,
    )

    private data class TabHolder(
        val config: TabConfig,
        val pill: View,
        val icon: LottieAnimationView,
        val label: TextView,
        val tintCallback: LottieValueCallback<ColorFilter>,
    )

    private val tabs = listOf(
        TabConfig(R.id.nav_home, R.string.tab_home, R.raw.nav_lottie_home, 30, 65, 117, 1f),
        TabConfig(R.id.nav_store, R.string.tab_store, R.raw.nav_lottie_store, 35, 0, 12, 1f),
        TabConfig(R.id.nav_apps, R.string.tab_apps, R.raw.nav_lottie_apps, 41, 16, 122, 3f),
        TabConfig(R.id.nav_games, R.string.tab_games, R.raw.nav_lottie_games, 42, 0, 99, 3f),
        TabConfig(R.id.nav_agent, R.string.tab_agent_short, R.raw.nav_lottie_agent, 30, 0, 10, 1f),
    )

    private val holders = mutableMapOf<Int, TabHolder>()

    private var currentId: Int = R.id.nav_home

    /** The selected tab id. Setting it to another tab animates the
     *  change and notifies the listener; setting it to the current
     *  tab is a no-op. Matches the old BottomNavigationView contract
     *  used by MainActivity, HomeFragment, and AgentFragment. */
    var selectedItemId: Int
        get() = currentId
        set(value) {
            if (value == currentId) return
            select(value, fromTap = false)
        }

    private var onItemSelectedListener: ((Int) -> Boolean)? = null

    /** Same contract as BottomNavigationView.setOnItemSelectedListener,
     *  but the listener receives the tab id directly (there is no
     *  MenuItem in this view). Return true to accept the selection. */
    fun setOnItemSelectedListener(listener: (Int) -> Boolean) {
        onItemSelectedListener = listener
    }

    init {
        orientation = HORIZONTAL
        val inflater = LayoutInflater.from(context)
        val density = resources.displayMetrics.density
        for (config in tabs) {
            val item = inflater.inflate(R.layout.bottom_nav_item, this, false)
            val pill = item.findViewById<View>(R.id.nav_item_pill)
            val icon = item.findViewById<LottieAnimationView>(R.id.nav_item_icon)
            val label = item.findViewById<TextView>(R.id.nav_item_label)
            val px = (config.iconSizeDp * density).toInt()
            icon.layoutParams = FrameLayout.LayoutParams(px, px, Gravity.CENTER)
            icon.setAnimation(config.lottieRes)
            icon.progress = 0f
            val tintCallback = LottieValueCallback<ColorFilter>(
                PorterDuffColorFilter(unselectedColor(), PorterDuff.Mode.SRC_ATOP)
            )
            icon.addValueCallback(KeyPath("**"), LottieProperty.COLOR_FILTER, tintCallback)
            label.setText(config.labelRes)
            label.setTextColor(unselectedColor())
            item.setOnClickListener { select(config.id, fromTap = true) }
            addView(item)
            holders[config.id] = TabHolder(config, pill, icon, label, tintCallback)
        }
        applySelection(R.id.nav_home, animate = false)
    }

    private fun select(id: Int, fromTap: Boolean) {
        if (!holders.containsKey(id)) return
        val changed = id != currentId
        if (changed) {
            holders[currentId]?.let { resetTab(it) }
            currentId = id
            applySelection(id, animate = true)
        }
        if (changed || fromTap) {
            onItemSelectedListener?.invoke(id)
        }
    }

    private fun applySelection(id: Int, animate: Boolean) {
        val holder = holders[id] ?: return
        holder.pill.visibility = View.VISIBLE
        holder.label.setTextColor(selectedColor())
        holder.tintCallback.setValue(
            PorterDuffColorFilter(selectedColor(), PorterDuff.Mode.SRC_ATOP))
        if (!animate) {
            holder.icon.progress = 0f
            return
        }
        holder.icon.setMinAndMaxFrame(holder.config.animMinFrame, holder.config.animMaxFrame)
        holder.icon.speed = holder.config.animSpeed
        holder.icon.playAnimation()
    }

    private fun resetTab(holder: TabHolder) {
        holder.icon.cancelAnimation()
        holder.icon.setMinAndMaxProgress(0f, 1f)
        holder.icon.progress = 0f
        holder.pill.visibility = View.GONE
        holder.label.setTextColor(unselectedColor())
        holder.tintCallback.setValue(
            PorterDuffColorFilter(unselectedColor(), PorterDuff.Mode.SRC_ATOP))
    }

    private fun selectedColor(): Int =
        ContextCompat.getColor(context, R.color.mudif_purple)

    private fun unselectedColor(): Int =
        ContextCompat.getColor(context, R.color.mudif_unselected)
}
