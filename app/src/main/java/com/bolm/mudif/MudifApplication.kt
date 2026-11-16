package com.bolm.mudif

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.bolm.mudif.engine.AppDeathDetector

/**
 * p74: the app had no Application class and no response to memory
 * pressure at all. The spontaneous deaths (11 across the p44-p53
 * rig rounds, no crash line, clean restart) are a SIGKILL from
 * outside the process, most likely Android's lowmemorykiller
 * under device-wide memory pressure; an app that never sheds
 * when the system warns stays a prime kill candidate.
 *
 * This class does two things on top of the default Application:
 * it starts the death detector (previous-process check) once per
 * process start, and it forwards every memory-pressure signal
 * to the detector, which sheds the rebuildable caches and
 * records the trim level. Nothing else changes: no service
 * starts here, no guest work, no UI.
 */
class MudifApplication : Application(), ComponentCallbacks2 {

    override fun onCreate() {
        super.onCreate()
        AppDeathDetector.recordStart(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppDeathDetector.onMemoryPressure(this, level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppDeathDetector.onMemoryPressure(this, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
