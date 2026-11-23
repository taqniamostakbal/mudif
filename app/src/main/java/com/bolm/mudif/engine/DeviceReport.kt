package com.bolm.mudif.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display
import com.bolm.mudif.R
import java.util.Locale

/**
 * F7, device report: real hardware facts about the phone, read
 * from the Android APIs only. Read-only, no new permissions.
 * Every row is a real read; a read that fails reports the failure
 * instead of a fake value (see the bar in DESIGN.md).
 *
 * Battery health trap (named in the design): Android exposes NO
 * battery-health percentage, only the coarse BATTERY_HEALTH_*
 * enum plus temperature and voltage. This collector shows the
 * enum, the temperature and the voltage, and never a percentage.
 */
object DeviceReport {

    data class Row(val labelRes: Int, val value: String)

    fun collect(context: Context): List<Row> {
        val rows = ArrayList<Row>()
        rows += Row(R.string.device_report_model, model())
        rows += Row(R.string.device_report_soc, soc(context))
        rows += Row(
            R.string.device_report_android,
            "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        )
        rows += Row(R.string.device_report_ram, ram(context))
        rows += Row(R.string.device_report_storage, storage(context))
        rows += Row(R.string.device_report_display, display(context))
        rows += Row(R.string.device_report_gpu, gpuRenderer(context))
        rows += Row(R.string.device_report_vulkan, vulkan(context))
        rows += batteryRows(context)
        return rows
    }

    private fun model(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /**
     * SoC: Build.SOC_MODEL on API 31+, the hardware board name
     * otherwise. Both fields read "unknown" when the system
     * reports nothing (the emulator's ro.soc.model is empty), so
     * "unknown" is treated as missing and falls through; a device
     * that reports nothing at all gets the honest not-reported
     * string instead of a fake name.
     */
    private fun soc(context: Context): String {
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            null
        }
        if (!socModel.isNullOrBlank() && socModel != "unknown") return socModel
        val hardware = Build.HARDWARE
        if (!hardware.isNullOrBlank() && hardware != "unknown") return hardware
        return context.getString(R.string.device_report_not_reported)
    }

    private fun ram(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val used = info.totalMem - info.availMem
        return context.getString(
            R.string.device_report_ram_value, gb(used), gb(info.totalMem)
        )
    }

    private fun storage(context: Context): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        return context.getString(
            R.string.device_report_storage_value,
            gb(stat.availableBytes), gb(stat.totalBytes)
        )
    }

    /**
     * Display: the PHYSICAL size (Display.getRealSize), not the
     * app window size — resources.displayMetrics reports the
     * window (720x1184 here: the status + nav bars take 96 px),
     * while wm size / the panel report the physical 720x1280.
     * Density comes from the metrics (matches wm density).
     */
    private fun display(context: Context): String {
        val metrics = context.resources.displayMetrics
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val realSize = android.graphics.Point()
        display?.getRealSize(realSize)
        val w = if (realSize.x > 0) realSize.x else metrics.widthPixels
        val h = if (realSize.y > 0) realSize.y else metrics.heightPixels
        val refresh = display?.refreshRate ?: 0f
        val refreshText = if (refresh > 0f) {
            if (refresh == refresh.toInt().toFloat()) "${refresh.toInt()} Hz"
            else String.format(Locale.US, "%.1f Hz", refresh)
        } else {
            "?"
        }
        return "$w × $h · ${metrics.densityDpi} dpi · $refreshText"
    }

    /**
     * GPU renderer: a tiny offscreen EGL pbuffer context, created
     * on demand and released immediately. The GL_RENDERER string
     * is the GPU's own name (Adreno on a real phone, SwiftShader
     * on the emulator). Any step failing returns the honest
     * failure string instead of a guessed renderer.
     */
    private fun gpuRenderer(context: Context): String {
        var eglDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext = EGL14.EGL_NO_CONTEXT
        var eglSurface = EGL14.EGL_NO_SURFACE
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                return context.getString(R.string.device_report_gpu_failed)
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                return context.getString(R.string.device_report_gpu_failed)
            }
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(
                    eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0
                ) || numConfigs[0] == 0
            ) {
                return context.getString(R.string.device_report_gpu_failed)
            }
            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(
                eglDisplay, configs[0], surfaceAttribs, 0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                return context.getString(R.string.device_report_gpu_failed)
            }
            val ctxAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(
                eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                return context.getString(R.string.device_report_gpu_failed)
            }
            if (!EGL14.eglMakeCurrent(
                    eglDisplay, eglSurface, eglSurface, eglContext
                )
            ) {
                return context.getString(R.string.device_report_gpu_failed)
            }
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            return if (renderer.isNullOrBlank()) {
                context.getString(R.string.device_report_gpu_failed)
            } else {
                renderer
            }
        } catch (_: Exception) {
            return context.getString(R.string.device_report_gpu_failed)
        } finally {
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                    }
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Vulkan: the system feature list carries the version
     * (major << 22 | minor << 12 | patch). No feature entry
     * means the device does not support Vulkan at all.
     */
    private fun vulkan(context: Context): String {
        val features = context.packageManager.systemAvailableFeatures
        val vulkan = features.firstOrNull {
            it.name == android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
        } ?: return context.getString(R.string.device_report_vulkan_unsupported)
        val v = vulkan.version
        val major = v shr 22
        val minor = (v shr 12) and 0x3ff
        val patch = v and 0xfff
        return "$major.$minor.$patch"
    }

    /**
     * Battery: the same sticky intent ControlCenter uses. Level
     * + charging state, the real health enum, temperature
     * (tenths of a degree) and voltage (mV). No health
     * percentage exists on Android; none is shown.
     */
    private fun batteryRows(context: Context): List<Row> {
        val battery = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val rows = ArrayList<Row>()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING ->
                context.getString(R.string.device_report_battery_charging)
            BatteryManager.BATTERY_STATUS_DISCHARGING ->
                context.getString(R.string.device_report_battery_discharging)
            BatteryManager.BATTERY_STATUS_FULL ->
                context.getString(R.string.device_report_battery_full)
            BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                context.getString(R.string.device_report_battery_not_charging)
            else -> context.getString(R.string.device_report_battery_unknown_status)
        }
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        rows += Row(
            R.string.device_report_battery,
            if (pct >= 0) "$pct% · $statusText" else statusText
        )
        val health = battery?.getIntExtra(
            BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN
        ) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val healthText = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD ->
                context.getString(R.string.device_report_health_good)
            BatteryManager.BATTERY_HEALTH_OVERHEAT ->
                context.getString(R.string.device_report_health_overheat)
            BatteryManager.BATTERY_HEALTH_DEAD ->
                context.getString(R.string.device_report_health_dead)
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ->
                context.getString(R.string.device_report_health_over_voltage)
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE ->
                context.getString(R.string.device_report_health_failure)
            BatteryManager.BATTERY_HEALTH_COLD ->
                context.getString(R.string.device_report_health_cold)
            else -> context.getString(R.string.device_report_health_unknown)
        }
        rows += Row(R.string.device_report_battery_health, healthText)
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        rows += Row(
            R.string.device_report_battery_temp,
            if (temp != Int.MIN_VALUE) {
                String.format(Locale.US, "%.1f °C", temp / 10.0)
            } else {
                context.getString(R.string.device_report_not_reported)
            }
        )
        val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        rows += Row(
            R.string.device_report_battery_voltage,
            if (voltage != Int.MIN_VALUE) {
                // Locale.US explicitly: getString() formats %d in
                // the UI locale, which renders Eastern Arabic
                // digits (٠ mV) while every other row uses Latin
                // digits (the identity rule).
                String.format(Locale.US, "%d mV", voltage)
            } else {
                context.getString(R.string.device_report_not_reported)
            }
        )
        return rows
    }

    private fun gb(bytes: Long): String =
        "%.1f GB".format(Locale.US, bytes / 1_073_741_824.0)
}
