package com.bolm.mudif.ui.terminal

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.R

/**
 * Full-screen terminal. Launched from the Profile tab (engine section).
 * Hosts TerminalFragment — kept separate from MainActivity because
 * BottomNavigationView supports a maximum of 5 items.
 */
class TerminalActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.terminal_container, TerminalFragment())
                .commit()
        }
    }
}
