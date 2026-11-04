package com.bolm.mudif

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStore.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_store
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                else -> {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.coming_soon),
                        Snackbar.LENGTH_SHORT
                    ).show()
                    false
                }
            }
        }
    }
}
