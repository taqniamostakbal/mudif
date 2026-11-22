package com.bolm.mudif.ui.nav

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.bolm.mudif.R

/**
 * p46: the sidebar navigation drawer, rebuilt like reference
 * image-390.png. A slide-in drawer with two states that morph into
 * each other: an expanded panel (logo header, icon + label rows, the
 * active row a purple pill) and a collapsed rail (icons only, the
 * active icon in a purple circle). One consistent purple #593BFE,
 * Fusha Arabic labels, the existing Mudif vector icons. No user row,
 * no logout row: there is no account in the app. Rows emit the same
 * ids as drawer_menu.xml and values/ids.xml, so MainActivity routes
 * them exactly like the old NavigationView did.
 *
 * p49: the pill and the circle stay INSIDE the panel and rail
 * bounds (the reference bulges past the panel edge by design, but
 * on the phone it reads as broken). No child draws outside its
 * parent, so nothing here needs clipChildren false.
 */
class SidebarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Emits the tapped row id (R.id.nav_home, R.id.drawer_settings, ...). */
    var onItemSelected: ((Int) -> Unit)? = null

    private val expandedWidth = resources.getDimensionPixelSize(R.dimen.sidebar_width_expanded)
    private val collapsedWidth = resources.getDimensionPixelSize(R.dimen.sidebar_width_collapsed)
    private val rowHeight = resources.getDimensionPixelSize(R.dimen.sidebar_row_height)
    private val circleSize = resources.getDimensionPixelSize(R.dimen.sidebar_circle_size)
    private val panelCorner = resources.getDimension(R.dimen.sidebar_panel_corner)

    private var collapsed = false
    private var selectedId: Int = R.id.nav_home
    private var widthAnimator: ValueAnimator? = null
    private var bgSwappedForFraction = -1f

    private val onSurface: Int = resolveAttrColor(
        com.google.android.material.R.attr.colorOnSurface
    )
    // p98: the unselected color is the theme attr
    // mudifSidebarUnselected (#A99FD0 dark, #424242 light,
    // #CBC0F5 gradient). The old static R.color.mudif_unselected
    // could not vary per theme.
    private val unselected: Int = resolveAttrColor(R.attr.mudifSidebarUnselected)
    private val cairo = ResourcesCompat.getFont(context, R.font.cairo)
    private val lifta = ResourcesCompat.getFont(context, R.font.lifta)

    private data class RowViews(
        val id: Int,
        val root: FrameLayout,
        val bg: View,
        val content: LinearLayout,
        val icon: ImageView,
        val label: TextView
    )

    private val rows = mutableListOf<RowViews>()
    private lateinit var headerExpanded: View
    private lateinit var headerCollapsed: View
    private lateinit var collapseButton: ImageButton
    private lateinit var expandButton: ImageButton
    private lateinit var sectionTitle: TextView

    private val mainRows = listOf(
        Row(R.id.nav_home, R.drawable.ic_home, R.string.tab_home),
        Row(R.id.nav_store, R.drawable.ic_store, R.string.tab_store),
        Row(R.id.nav_apps, R.drawable.ic_apps, R.string.tab_apps),
        Row(R.id.nav_games, R.drawable.ic_games, R.string.tab_games),
        Row(R.id.nav_agent, R.drawable.ic_agent_robot, R.string.tab_agent),
        Row(R.id.nav_profile, R.drawable.ic_person, R.string.tab_profile)
    )

    // p53: EVERY row takes the active state when tapped, tools
    // included. The old comment said tools "never take the active
    // state": MainActivity never called setSelected for them, so on
    // Anas's phone no settings row and no tool row ever showed the
    // purple pill. The pill marks the last tapped row.
    private val toolRows = listOf(
        Row(R.id.drawer_language, R.drawable.ic_menu, R.string.drawer_language),
        Row(R.id.drawer_settings, R.drawable.ic_settings, R.string.viewer_settings_title),
        Row(R.id.drawer_control_center, R.drawable.ic_cat_system, R.string.control_center),
        Row(R.id.drawer_logs, R.drawable.ic_logs, R.string.log_title),
        Row(R.id.drawer_about, R.drawable.ic_info, R.string.about)
    )

    private data class Row(
        val id: Int,
        @DrawableRes val icon: Int,
        @StringRes val label: Int
    )

    init {
        orientation = VERTICAL
        applyPanelBackground()
        buildHeader()
        buildMenu()
        refreshSelection()
        applyStateInstant()
    }

    /** Marks [itemId] active in both states. Any row id matches,
     *  tools included (p53). */
    fun setSelected(itemId: Int) {
        selectedId = itemId
        refreshSelection()
    }

    /** Collapses to the rail or expands to the panel. */
    fun setCollapsed(collapse: Boolean, animate: Boolean) {
        if (collapse == collapsed && widthAnimator == null) return
        collapsed = collapse
        widthAnimator?.cancel()
        widthAnimator = null
        if (!animate) {
            applyStateInstant()
            return
        }
        val fromWidth = (layoutParams?.width ?: expandedWidth)
            .coerceIn(collapsedWidth, expandedWidth)
        val toWidth = if (collapsed) collapsedWidth else expandedWidth
        if (fromWidth == toWidth) {
            applyStateInstant()
            return
        }
        // Labels stay laid out while they fade, then hide at the end
        // of a collapse; gravity flips at the end too, so the icon
        // does not jump mid-morph.
        if (!collapsed) {
            setLabelsVisible(true)
            setContentGravity(Gravity.START)
        }
        headerExpanded.isVisible = true
        headerCollapsed.isVisible = true
        bgSwappedForFraction = -1f
        val animator = ValueAnimator.ofInt(fromWidth, toWidth).apply {
            duration = 250
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val w = anim.animatedValue as Int
                val lp = layoutParams ?: return@addUpdateListener
                lp.width = w
                requestLayout()
                val fraction = (w - collapsedWidth).toFloat() /
                    (expandedWidth - collapsedWidth).toFloat()
                headerExpanded.alpha = fraction
                headerCollapsed.alpha = 1f - fraction
                setLabelsAlpha(fraction)
                sectionTitle.alpha = fraction
                // Midpoint swap: the active circle becomes the pill
                // (or the reverse) halfway through the morph.
                val side = if (fraction >= 0.5f) 1f else 0f
                if (side != bgSwappedForFraction) {
                    bgSwappedForFraction = side
                    applyBgParams(fraction >= 0.5f)
                }
            }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                widthAnimator = null
                applyStateInstant()
            }
        })
        widthAnimator = animator
        animator.start()
    }

    fun isCollapsed(): Boolean = collapsed

    private fun applyStateInstant() {
        layoutParams?.let {
            it.width = if (collapsed) collapsedWidth else expandedWidth
        }
        requestLayout()
        headerExpanded.isVisible = !collapsed
        headerCollapsed.isVisible = collapsed
        headerExpanded.alpha = if (collapsed) 0f else 1f
        headerCollapsed.alpha = if (collapsed) 1f else 0f
        setLabelsVisible(!collapsed)
        setLabelsAlpha(if (collapsed) 0f else 1f)
        sectionTitle.isVisible = !collapsed
        sectionTitle.alpha = if (collapsed) 0f else 1f
        setContentGravity(if (collapsed) Gravity.CENTER else Gravity.START)
        applyBgParams(!collapsed)
    }

    private fun applyBgParams(expanded: Boolean) {
        for (row in rows) {
            val active = row.id == selectedId
            if (!active) {
                row.bg.isVisible = false
                continue
            }
            row.bg.isVisible = true
            if (expanded) {
                // p49: full row width, no overflow. The pill ends
                // flush with the panel edge, inside the panel.
                row.bg.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, rowHeight
                )
                row.bg.setBackgroundResource(R.drawable.sidebar_active_pill)
            } else {
                // p49: centered in the rail, 8dp inset each side
                // ((72-56)/2), fully inside the rail.
                row.bg.layoutParams = FrameLayout.LayoutParams(
                    circleSize, circleSize, Gravity.CENTER
                )
                row.bg.setBackgroundResource(R.drawable.circle_bg)
            }
        }
    }

    private fun refreshSelection() {
        for (row in rows) {
            val active = row.id == selectedId
            row.icon.imageTintList = ColorStateList.valueOf(
                if (active) Color.WHITE else unselected
            )
            row.label.setTextColor(if (active) Color.WHITE else onSurface)
        }
        applyBgParams(!collapsed)
    }

    private fun setLabelsVisible(visible: Boolean) {
        for (row in rows) row.label.isVisible = visible
    }

    private fun setLabelsAlpha(alpha: Float) {
        for (row in rows) row.label.alpha = alpha
    }

    private fun setContentGravity(gravity: Int) {
        for (row in rows) {
            row.content.gravity = gravity or Gravity.CENTER_VERTICAL
        }
    }

    private fun applyPanelBackground() {
        val surface = resolveAttrColor(com.google.android.material.R.attr.colorSurface)
        // The reference panel rounds all four corners; the drawer is
        // anchored to the screen edge on the outer side, so only the
        // inner edge corners round (left in RTL, right in LTR).
        val rtl = resources.configuration.layoutDirection == LAYOUT_DIRECTION_RTL
        val r = panelCorner
        val radii = if (rtl) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(surface)
            cornerRadii = radii
        }
    }

    private fun buildHeader() {
        val header = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.sidebar_header_height)
            )
        }
        headerExpanded = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPaddingRelative(dp(16), 0, dp(8), 0)
        }.also { row ->
            val logo = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setImageResource(R.drawable.logo_mudif)
                contentDescription = context.getString(R.string.app_name)
            }
            val titles = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(12) }
            }.also { col ->
                col.addView(TextView(context).apply {
                    text = context.getString(R.string.app_name)
                    textSize = 20f
                    typeface = lifta
                    setTextColor(onSurface)
                })
                col.addView(TextView(context).apply {
                    text = context.getString(
                        R.string.about_version_chip,
                        com.bolm.mudif.engine.MudifUpdater.currentVersion(context).first
                    )
                    textSize = 12f
                    typeface = cairo
                    setTextColor(ColorUtils.setAlphaComponent(onSurface, 153))
                })
            }
            collapseButton = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setImageResource(R.drawable.ic_back)
                imageTintList = ColorStateList.valueOf(unselected)
                background = resolveAttrDrawable(
                    android.R.attr.selectableItemBackgroundBorderless
                )
                contentDescription = context.getString(R.string.sidebar_collapse)
                setOnClickListener { setCollapsed(true, animate = true) }
            }
            row.addView(logo)
            row.addView(titles)
            row.addView(collapseButton)
        }
        headerCollapsed = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }.also { col ->
            col.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setImageResource(R.drawable.logo_mudif)
                contentDescription = context.getString(R.string.app_name)
            })
            expandButton = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    topMargin = dp(8)
                }
                setImageResource(R.drawable.ic_menu)
                imageTintList = ColorStateList.valueOf(unselected)
                background = resolveAttrDrawable(
                    android.R.attr.selectableItemBackgroundBorderless
                )
                contentDescription = context.getString(R.string.sidebar_expand)
                setOnClickListener { setCollapsed(false, animate = true) }
            }
            col.addView(expandButton)
        }
        header.addView(headerExpanded)
        header.addView(headerCollapsed)
        addView(header)
    }

    private fun buildMenu() {
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f
            )
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for (item in mainRows) menu.addView(buildRow(item).root)
        sectionTitle = TextView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(12), 0, dp(4))
                marginStart = dp(20)
                marginEnd = dp(16)
            }
            text = context.getString(R.string.ui_drawer_settings_tools)
            textSize = 14f
            typeface = cairo
            setTextColor(ColorUtils.setAlphaComponent(onSurface, 153))
        }
        menu.addView(sectionTitle)
        for (item in toolRows) menu.addView(buildRow(item).root)
        scroll.addView(menu)
        addView(scroll)
    }

    private fun buildRow(item: Row): RowViews {
        val root = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, rowHeight
            )
            isClickable = true
            isFocusable = true
            foreground = resolveSelectableBackground()
            // p53: the pill follows the tap itself, for every row.
            // MainActivity only calls setSelected for destinations
            // (and profile); the tool rows open activities and
            // dialogs without touching the selection, which is why
            // they never showed the pill. Marking here, before the
            // emit, makes the tapped row active no matter what the
            // routing does next.
            setOnClickListener {
                setSelected(item.id)
                onItemSelected?.invoke(item.id)
            }
        }
        val bg = View(context).apply {
            visibility = GONE
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPaddingRelative(dp(20), 0, dp(16), 0)
        }
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            setImageResource(item.icon)
        }
        val label = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(16) }
            setText(item.label)
            textSize = 16f
            typeface = cairo
            setTextColor(onSurface)
            maxLines = 1
        }
        content.addView(icon)
        content.addView(label)
        root.addView(bg)
        root.addView(content)
        val views = RowViews(item.id, root, bg, content, icon, label)
        rows.add(views)
        return views
    }

    private fun resolveSelectableBackground(): android.graphics.drawable.Drawable? =
        resolveAttrDrawable(android.R.attr.selectableItemBackground)

    private fun resolveAttrDrawable(@AttrRes attr: Int): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) {
            ContextCompat.getDrawable(context, tv.resourceId)
        } else {
            null
        }
    }

    private fun resolveAttrColor(@AttrRes attr: Int): Int {
        val tv = TypedValue()
        require(context.theme.resolveAttribute(attr, tv, true))
        return tv.data
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
