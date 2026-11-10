package com.bolm.mudif.ui.control

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.bolm.mudif.databinding.ActivityControlCenterBinding
import java.io.File

/**
 * P19 Phase 3: control center + system status (P18.5 #2 + #3).
 * Every value is real, read from the device at open time:
 * - Wi-Fi: connected or not (ConnectivityManager); the button opens the
 *   system Wi-Fi settings (a regular app cannot toggle Wi-Fi itself on
 *   modern Android, so no fake toggle is shown).
 * - Volume: a real slider for the music stream (AudioManager).
 * - Brightness: a real slider once the WRITE_SETTINGS permission is
 *   granted; otherwise a button opens the permission screen.
 * - Battery: level + charging state (BatteryManager).
 * - CPU: two /proc/stat samples 500 ms apart. RAM: ActivityManager.
 *   Storage: StatFs. All refreshed by the "تحديث" button.
 */
class ControlCenterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityControlCenterBinding
    private lateinit var audio: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        binding.controlBack.setOnClickListener { finish() }
        binding.btnWifiSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        binding.btnRefresh.setOnClickListener { refreshAll() }

        setupVolume()
        setupBrightness()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted the brightness permission meanwhile.
        setupBrightness()
        refreshAll()
    }

    private fun setupVolume() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSlider.max = max
        binding.volumeSlider.progress =
            audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun setupBrightness() {
        if (!Settings.System.canWrite(this)) {
            binding.brightnessSlider.isEnabled = false
            binding.btnBrightnessPermission.visibility = View.VISIBLE
            binding.btnBrightnessPermission.setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        binding.btnBrightnessPermission.visibility = View.GONE
        binding.brightnessSlider.isEnabled = true
        binding.brightnessSlider.max = 255
        binding.brightnessSlider.progress = currentBrightness()
        binding.brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = v.coerceAtLeast(10)
                Settings.System.putInt(
                    contentResolver, Settings.System.SCREEN_BRIGHTNESS, value
                )
                window.attributes = window.attributes.apply {
                    screenBrightness = value / 255f
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun currentBrightness(): Int = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    } catch (e: Exception) {
        128
    }

    private fun refreshAll() {
        // Wi-Fi + battery are cheap reads; CPU needs a background thread.
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        binding.wifiState.text = if (wifi) "متصل ✓" else "غير متصل"

        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        binding.batteryState.text = if (pct >= 0) {
            "$pct% " + if (charging) "(يشحن)" else ""
        } else {
            "—"
        }

        Thread {
            val cpu = readCpuUsage()
            val mem = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            mem.getMemoryInfo(info)
            val ramUsed = info.totalMem - info.availMem
            val stat = StatFs(filesDir.path)
            val storageTotal = stat.totalBytes
            val storageFree = stat.availableBytes
            runOnUiThread {
                binding.cpuBar.progress = cpu.coerceIn(0, 100)
                binding.cpuValue.text = "$cpu%"
                val ramPct = if (info.totalMem > 0)
                    (ramUsed * 100 / info.totalMem).toInt() else 0
                binding.ramBar.progress = ramPct
                binding.ramValue.text = "${gb(ramUsed)} / ${gb(info.totalMem)}"
                val storagePct = if (storageTotal > 0)
                    ((storageTotal - storageFree) * 100 / storageTotal).toInt() else 0
                binding.storageBar.progress = storagePct
                binding.storageValue.text =
                    "${gb(storageTotal - storageFree)} / ${gb(storageTotal)}"
            }
        }.start()
    }

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1_073_741_824.0)

    /** Two /proc/stat samples 500 ms apart; -1 when unreadable. */
    private fun readCpuUsage(): Int {
        fun sample(): Pair<Long, Long>? {
            return try {
                val parts = File("/proc/stat").bufferedReader().use { it.readLine() }
                    .trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
                val idle = parts.getOrNull(3) ?: 0L
                val iowait = parts.getOrNull(4) ?: 0L
                Pair(parts.sum(), idle + iowait)
            } catch (e: Exception) {
                null
            }
        }
        val first = sample() ?: return -1
        Thread.sleep(500)
        val second = sample() ?: return -1
        val totalDelta = second.first - first.first
        val idleDelta = second.second - first.second
        if (totalDelta <= 0) return -1
        return ((totalDelta - idleDelta) * 100 / totalDelta).toInt().coerceIn(0, 100)
    }
}
