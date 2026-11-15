package com.bolm.mudif

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
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

        // p48: the bottom nav is the custom BottomNavView (Lottie
        // icons). The listener receives the tab id directly; the view
        // plays its own select animation, so there is no AVD wiring
        // here anymore.
        binding.bottomNav.setOnItemSelectedListener { itemId ->
            val frag: Fragment = when (itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_store -> StoreFragment()
                R.id.nav_apps -> AppsFragment()
                R.id.nav_games -> GamesFragment()
                R.id.nav_agent -> AgentFragment()
                R.id.nav_profile -> ProfileFragment()
                else -> HomeFragment()
            }
            show(frag)
            binding.navView.setSelected(itemId)
            true
        }

        // P19 Phase 2: side menu (navigation drawer). The 5 main items
        // select the matching bottom-nav tab (its listener shows the
        // fragment); the second group opens the settings, the logs, or
        // the About dialog. p46: the drawer content is now the custom
        // SidebarView (expanded panel / collapsed rail); it emits the
        // same item ids, so this routing is unchanged.
        binding.navView.onItemSelected = { itemId ->
            when (itemId) {
                R.id.nav_home, R.id.nav_store, R.id.nav_apps,
                R.id.nav_games, R.id.nav_agent ->
                    binding.bottomNav.selectedItemId = itemId
                // Profile is NOT in the bottom nav: the custom
                // BottomNavView builds exactly the 5 main tabs.
                // Profile lives in the drawer only; show it directly
                // instead of routing through the bottom nav.
                R.id.nav_profile -> {
                    binding.navView.setSelected(R.id.nav_profile)
                    show(ProfileFragment())
                }
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
        }

        // Keep the sidebar active row in sync with the tab the app
        // actually shows (restored selection included).
        binding.navView.setSelected(binding.bottomNav.selectedItemId)

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
}
