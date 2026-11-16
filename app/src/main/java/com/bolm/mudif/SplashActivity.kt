package com.bolm.mudif

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.bolm.mudif.ui.MudifActivity

/**
 * Launcher splash: purple brand screen (logo + name + slogan) shown
 * briefly while the app starts. No artificial delay beyond a short
 * display (900 ms); MainActivity does nothing heavy at startup, so
 * the splash never blocks startup work.
 */
class SplashActivity : MudifActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val goMain = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        handler.postDelayed(goMain, 900)
    }

    override fun onDestroy() {
        // Do not start MainActivity after the splash is gone
        // (e.g. the user pressed Back within the 900 ms).
        handler.removeCallbacks(goMain)
        super.onDestroy()
    }
}
