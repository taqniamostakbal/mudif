package com.bolm.mudif

import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.bolm.mudif.databinding.ActivityMainBinding
import com.bolm.mudif.engine.AgentSupervisorService
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.settings.AppLanguage
import com.bolm.mudif.ui.agent.AgentFragment
import com.bolm.mudif.ui.apps.AppsFragment
import com.bolm.mudif.ui.control.ControlCenterActivity
import com.bolm.mudif.ui.games.GamesFragment
import com.bolm.mudif.ui.home.HomeFragment
import com.bolm.mudif.ui.logs.LogViewerActivity
import com.bolm.mudif.ui.placeholder.PlaceholderFragment
import com.bolm.mudif.ui.profile.ProfileFragment
import com.bolm.mudif.ui.settings.SettingsActivity
import com.bolm.mudif.ui.store.StoreFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    // Bottom-nav icon morphs: the previously selected tab, so its
    // icon can play the deselect AVD when another tab is tapped.
    // Starts on nav_home, which is the tab shown on launch.
    private var previousNavItemId: Int = R.id.nav_home

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // p22 fix: start the agent servers in the background on app
        // launch, so opencode (4096) is up without opening the agent
        // tab. ensureStarted is a no-op when the engine is not ready.
        AgentSupervisorService.start(this)

        // First run only: ask once which language the app speaks.
        // Safe here: it runs after setContentView, shows a plain
        // modal dialog, and never fires again once a choice is saved
        // (apply() persists the tag before recreate()).
        if (!AppLanguage.isChosen(this)) {
            AppLanguage.showPicker(this, firstRun = true)
        }

        if (savedInstanceState == null) {
            show(HomeFragment())
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val frag: Fragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_store -> StoreFragment()
                R.id.nav_apps -> AppsFragment()
                R.id.nav_games -> GamesFragment()
                R.id.nav_agent -> AgentFragment()
                R.id.nav_profile -> ProfileFragment()
                else -> HomeFragment()
            }
            animateNavSelection(previousNavItemId, item.itemId)
            previousNavItemId = item.itemId
            show(frag)
            true
        }

        // P19 Phase 2: side menu (navigation drawer). The 5 main items
        // select the matching bottom-nav tab (its listener shows the
        // fragment); the second group opens the settings, the logs, or
        // the About dialog.
        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home, R.id.nav_store, R.id.nav_apps,
                R.id.nav_games, R.id.nav_agent ->
                    binding.bottomNav.selectedItemId = item.itemId
                // Profile is NOT in the bottom nav: BottomNavigationView
                // throws at inflate time with more than 5 items
                // (IllegalArgumentException), which crashed the app on
                // launch. Profile lives in the drawer only; show it
                // directly instead of routing through the bottom nav.
                R.id.nav_profile ->
                    show(ProfileFragment())
                R.id.drawer_settings ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                R.id.drawer_control_center ->
                    startActivity(Intent(this, ControlCenterActivity::class.java))
                R.id.drawer_logs ->
                    startActivity(Intent(this, LogViewerActivity::class.java))
                R.id.drawer_language ->
                    AppLanguage.showPicker(this)
                R.id.drawer_about ->
                    MudifDialog.about(this)
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Back closes an open drawer instead of leaving the app.
        // (OnBackPressedCallback is in the base activity artifact, so
        // this needs no activity-ktx dependency.)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    /** Called from the menu button in every fragment header
     *  (android:onClick="openDrawer"). */
    fun openDrawer(view: View) {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    /** P18.5: let a fragment switch tabs (the Apps empty state
     *  links to the Store). Setting selectedItemId runs the same
     *  listener as a tap, so the fragment shows. */
    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    /** p33: let a fragment hide the bottom nav (the agent tab hides
     *  it while the keyboard is open, the nav is activity chrome
     *  the fragment never owned, and on Anas's phone it sat between
     *  the input bar and the keyboard, eating ~110px of the message
     *  list). Restored when the keyboard closes. */
    fun setBottomNavVisible(visible: Boolean) {
        binding.bottomNav.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fragment_fade_enter, R.anim.fragment_fade_exit
            )
            .replace(R.id.nav_host, fragment)
            .commit()
    }

    // Bottom-nav icon morphs (200ms, values/motion.xml). The newly
    // selected tab plays its select AVD, the previous tab its
    // deselect AVD. The pill slide is the Material 3 built-in
    // indicator animation, not a custom animator. A re-tap on the
    // selected tab plays nothing. The MenuItem icon is pinned to the
    // AVD instance so later nav updates keep it; the drawable shown
    // is set directly on the item ImageView in playNavAvd.
    private fun animateNavSelection(previousId: Int, selectedId: Int) {
        if (previousId == selectedId) return
        navAvdFor(previousId, deselect = true)?.let { playNavAvd(previousId, it, selected = false) }
        navAvdFor(selectedId, deselect = false)?.let { playNavAvd(selectedId, it, selected = true) }
    }

    private fun playNavAvd(itemId: Int, @DrawableRes avdRes: Int, selected: Boolean) {
        val drawable = ContextCompat.getDrawable(this, avdRes) as? AnimatedVectorDrawable ?: return
        // Pin this AVD instance as the MenuItem icon first: every later
        // NavigationBarMenuView update calls itemView.initialize(), which
        // calls setIcon(item.icon) and early-returns when the icon is the
        // instance already stored, so it will not overwrite the ImageView
        // drawable set below.
        binding.bottomNav.menu.findItem(itemId)?.icon = drawable
        // Material 1.12.0 NavigationBarItemView.setIcon never shows the
        // instance given: it shows ConstantState.newDrawable() wrapped in
        // DrawableCompat.wrap().mutate(). Starting the instance we hold
        // animates nothing on screen (the p34f root cause), and starting
        // whatever copy the ImageView happens to hold is a gamble on its
        // runtime type, with the old fallback silently starting the
        // undisplayed instance when the cast failed. Post past the
        // framework checked-state update for this tap, then set THIS
        // instance directly on the displayed ImageView and start it, so
        // the started instance and the displayed instance are the same
        // object by construction. This is the same pattern the send and
        // record buttons already use (setImageDrawable then start).
        // The direct set bypasses Material icon tint, so tint the AVD
        // with the resolved selected/unselected color here.
        binding.bottomNav.post {
            val imageView = navIconImageView(itemId) ?: return@post
            val tintList = binding.bottomNav.itemIconTintList
            val state = if (selected) intArrayOf(android.R.attr.state_checked) else intArrayOf()
            val fallback = ContextCompat.getColor(
                this@MainActivity,
                if (selected) R.color.mudif_purple else R.color.mudif_unselected
            )
            val color = tintList?.getColorForState(state, fallback) ?: fallback
            drawable.mutate()
            DrawableCompat.setTint(drawable, color)
            imageView.setImageDrawable(drawable)
            drawable.start()
        }
    }

    // The ImageView the bottom nav actually displays for itemId: the
    // item view (id == itemId, set by NavigationBarItemView.initialize)
    // holds one ImageView with the icon.
    private fun navIconImageView(itemId: Int): ImageView? {
        val menuView = binding.bottomNav.getChildAt(0) as? ViewGroup ?: return null
        for (i in 0 until menuView.childCount) {
            val itemView = menuView.getChildAt(i)
            if (itemView.id == itemId) {
                return itemView.findViewById<ImageView>(
                    com.google.android.material.R.id.navigation_bar_item_icon_view
                )
            }
        }
        return null
    }

    @DrawableRes
    private fun navAvdFor(itemId: Int, deselect: Boolean): Int? = when (itemId) {
        R.id.nav_home -> if (deselect) R.drawable.nav_home_deselect else R.drawable.nav_home_select
        R.id.nav_store -> if (deselect) R.drawable.nav_store_deselect else R.drawable.nav_store_select
        R.id.nav_apps -> if (deselect) R.drawable.nav_apps_deselect else R.drawable.nav_apps_select
        R.id.nav_games -> if (deselect) R.drawable.nav_games_deselect else R.drawable.nav_games_select
        R.id.nav_agent -> if (deselect) R.drawable.nav_agent_deselect else R.drawable.nav_agent_select
        else -> null
    }
}
