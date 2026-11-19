package com.bolm.mudif.ui.control

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.bolm.mudif.ui.MudifActivity
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ActivityControlCenterBinding
import com.bolm.mudif.ui.settings.AppTheme
import java.io.File
import java.util.Locale

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
 * p111: three theme dots (quiet apply, the viewer stays open) and
 * a Bluetooth row (state + the system enable dialog or the
 * Bluetooth settings; a regular app cannot toggle Bluetooth
 * itself on modern Android, so no fake toggle is shown). The
 * activity opens as a floating centered card over the viewer.
 */
class ControlCenterActivity : MudifActivity() {
    private lateinit var binding: ActivityControlCenterBinding
    private lateinit var audio: AudioManager

    // p111: BLUETOOTH_CONNECT runtime permission (API 31+). The
    // SettingsActivity notification launcher is the same pattern.
    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshBluetooth()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // p111: floating centered card over the viewer (about 92%
        // of the screen width, WRAP_CONTENT height, dimmed
        // backdrop). The desktop stays alive behind it; an outside
        // tap dismisses the panel.
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.setGravity(Gravity.CENTER)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.5f }
        setFinishOnTouchOutside(true)

        binding.controlBack.setOnClickListener { finish() }
        binding.btnWifiSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        binding.btnRefresh.setOnClickListener { refreshAll() }

        setupVolume()
        setupBrightness()
        setupThemeDots()
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

    /** p111 (D4): three theme dots. Each dot is filled with its
     *  theme's surface color; the current dot wears a thicker
     *  ring. A tap calls AppTheme.applyQuiet (save pref + stage
     *  the guest switch, no task relaunch) and recreates this
     *  activity, so the viewer underneath stays open. The current
     *  tag is re-read here on every onCreate, never cached in a
     *  field across recreation (rotation rule). */
    private fun setupThemeDots() {
        val current = AppTheme.currentTag(this)
        val dots = listOf(
            binding.dotDark to AppTheme.TAG_DARK,
            binding.dotLight to AppTheme.TAG_LIGHT,
            binding.dotGradient to AppTheme.TAG_GRADIENT
        )
        val fills = mapOf(
            AppTheme.TAG_DARK to 0xFF17112B.toInt(),
            AppTheme.TAG_LIGHT to 0xFFFFFFFF.toInt(),
            AppTheme.TAG_GRADIENT to 0xFF593BFE.toInt()
        )
        val rings = mapOf(
            AppTheme.TAG_DARK to 0xFFFFFFFF.toInt(),
            AppTheme.TAG_LIGHT to 0xFF6B6486.toInt(),
            AppTheme.TAG_GRADIENT to 0xFFFFFFFF.toInt()
        )
        for ((dot, tag) in dots) {
            dot.backgroundTintList = ColorStateList.valueOf(fills.getValue(tag))
            dot.strokeColor = ColorStateList.valueOf(rings.getValue(tag))
            dot.strokeWidth = dp(if (tag == current) 3 else 1)
            dot.setOnClickListener {
                AppTheme.applyQuiet(this, tag)
                recreate()
            }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    /** p111 (D1/D3): the Bluetooth row. State plus an action
     *  button, never a toggle: enable()/disable() always fail for
     *  apps targeting API 33+ (Mudif targets 34). Off: the button
     *  opens the system enable dialog (user consent). On: it opens
     *  the Bluetooth settings (no user-consent intent exists for
     *  turning it off). Without the runtime permission the row
     *  asks for it first and never throws a SecurityException. */
    private fun refreshBluetooth() {
        if (!hasBluetoothPermission()) {
            binding.btState.text = getString(R.string.ui_cc_unknown)
            binding.btnBtAction.visibility = View.GONE
            binding.btnBtPermission.visibility = View.VISIBLE
            binding.btnBtPermission.setOnClickListener {
                requestBluetoothPermission()
            }
            return
        }
        binding.btnBtPermission.visibility = View.GONE
        binding.btnBtAction.visibility = View.VISIBLE
        val manager =
            getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        val enabled = try {
            adapter?.isEnabled
        } catch (e: SecurityException) {
            null
        }
        if (adapter == null || enabled == null) {
            binding.btState.text = getString(R.string.ui_cc_unknown)
            binding.btnBtAction.visibility = View.GONE
            return
        }
        binding.btState.text = if (enabled) {
            getString(R.string.ui_cc_bt_on)
        } else {
            getString(R.string.ui_cc_bt_off)
        }
        binding.btnBtAction.text = if (enabled) {
            getString(R.string.settings_title)
        } else {
            getString(R.string.ui_cc_bt_enable)
        }
        binding.btnBtAction.setOnClickListener {
            if (enabled) {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } else {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(this, perm) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // API 30 and below: BLUETOOTH is an install-time
            // permission, so reaching here means the manifest
            // lacks it (the verifier forbids that). Re-read the
            // state instead of asking for something unaskable.
            refreshBluetooth()
        }
    }

    private fun refreshAll() {
        // Wi-Fi + battery are cheap reads; CPU needs a background thread.
        // p111: Bluetooth state is a cheap read too, and refreshAll
        // runs on onResume, so the row re-reads after the user
        // returns from the system settings or enable dialog.
        refreshBluetooth()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        binding.wifiState.text = if (wifi) getString(R.string.ui_cc_wifi_on) else getString(R.string.ui_cc_wifi_off)

        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        binding.batteryState.text = if (pct >= 0) {
            "$pct% " + if (charging) getString(R.string.ui_cc_charging) else ""
        } else {
            "…"
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
                // readCpuUsage returns -1 when /proc/stat is unreadable;
                // never show the sentinel as a percentage.
                binding.cpuValue.text = if (cpu >= 0) "$cpu%" else getString(R.string.ui_cc_unknown)
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

    private fun gb(bytes: Long): String = "%.1f GB".format(Locale.US, bytes / 1_073_741_824.0)

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
