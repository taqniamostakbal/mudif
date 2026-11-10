package com.bolm.mudif

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.bolm.mudif.databinding.ActivityMainBinding
import com.bolm.mudif.ui.MudifDialog
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

        if (savedInstanceState == null) {
            show(HomeFragment())
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val frag: Fragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_store -> StoreFragment()
                R.id.nav_apps -> AppsFragment()
                R.id.nav_games -> GamesFragment()
                R.id.nav_profile -> ProfileFragment()
                else -> HomeFragment()
            }
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
                R.id.nav_games, R.id.nav_profile ->
                    binding.bottomNav.selectedItemId = item.itemId
                R.id.drawer_settings ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                R.id.drawer_control_center ->
                    startActivity(Intent(this, ControlCenterActivity::class.java))
                R.id.drawer_logs ->
                    startActivity(Intent(this, LogViewerActivity::class.java))
                R.id.drawer_about ->
                    MudifDialog.info(
                        this,
                        getString(R.string.about),
                        getString(R.string.about_value),
                        getString(R.string.ok)
                    )
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

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, fragment)
            .commit()
    }
}
