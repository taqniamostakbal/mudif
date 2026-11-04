package com.bolm.mudif

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.bolm.mudif.databinding.ActivityMainBinding
import com.bolm.mudif.ui.apps.AppsFragment
import com.bolm.mudif.ui.games.GamesFragment
import com.bolm.mudif.ui.home.HomeFragment
import com.bolm.mudif.ui.placeholder.PlaceholderFragment
import com.bolm.mudif.ui.profile.ProfileFragment
import com.bolm.mudif.ui.store.StoreFragment
import com.bolm.mudif.ui.terminal.TerminalFragment

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
                R.id.nav_terminal -> TerminalFragment()
                R.id.nav_profile -> ProfileFragment()
                else -> HomeFragment()
            }
            show(frag)
            true
        }
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, fragment)
            .commit()
    }
}
