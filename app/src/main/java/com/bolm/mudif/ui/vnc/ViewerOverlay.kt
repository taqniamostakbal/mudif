package com.bolm.mudif.ui.vnc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.bolm.mudif.R
import com.bolm.mudif.engine.ExternalDisplay
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.HudStats
import com.bolm.mudif.engine.PulseAudio
import com.bolm.mudif.engine.ScreenRecorder
import com.bolm.mudif.engine.X11Session
import java.io.File
import com.bolm.mudif.ui.MudifDialog
import com.bolm.mudif.ui.control.ControlCenterActivity
import com.bolm.mudif.ui.remote.RemoteControlActivity

/**
 * Phase 1d: floating icon controls over the viewer. The VncActivity
 * status screen auto-opens the viewer in 2-4 s, so its text buttons
 * (record / TV / audio) are effectively unreachable. This overlay is
 * the reachable replacement: a draggable purple FAB that floats over
 * the Termux:X11 viewer, expanding to four professional icon
 * buttons (record, TV, audio, control center). No foreground service: the overlay is
 * added through the application WindowManager and removed when the
 * VncActivity is destroyed, so it lives exactly as long as the
 * desktop session. Requires SYSTEM_ALERT_WINDOW; when it is not
 * granted the overlay is simply not shown (never a crash).
 */
class ViewerOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var fabView: View? = null
    private var panelView: View? = null
    private var fabParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var shown = false
    private var expanded = false

    // F4: performance HUD state.
    private var hudView: TextView? = null
    private var hudParams: WindowManager.LayoutParams? = null
    private var hudShown = false
    private val hudHandler = Handler(Looper.getMainLooper())
    private var hudTickCount = 0
    private var lastFps: Float? = null
    private var lastFpsSeen = false
    private var logcatFailedLogged = false

    // F8 half A: external-display detection while the overlay lives.
    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null

    fun isShown(): Boolean = shown

    /** True when the app may draw over other apps. */
    fun canDraw(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /** Opens the system "Display over other apps" settings for this app. */
    fun requestPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + context.packageName)
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.ui_overlay_permission_hint), Toast.LENGTH_LONG).show()
        }
    }

    /** Show the floating FAB (and nothing else) over the viewer. */
    fun show() {
        if (shown) return
        if (!canDraw()) return
        try {
            val fab = ImageView(context).apply {
                setImageResource(R.drawable.ic_controls)
                setBackgroundResource(R.drawable.overlay_fab_bg)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setOnClickListener { togglePanel() }
            }
            // Draggable: a drag moves the FAB (the open panel follows
            // it, so the two can never overlap). A tap toggles the
            // panel; a long-press hides the overlay entirely (it
            // returns when the viewer screen is re-entered). On
            // release the FAB snaps to the nearest screen edge.
            var downX = 0f
            var downY = 0f
            var downTime = 0L
            var startX = 0
            var startY = 0
            var moved = false
            fab.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        downTime = System.currentTimeMillis()
                        startX = fabParams?.x ?: 0
                        startY = fabParams?.y ?: 0
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                        fabParams?.let { p ->
                            p.x = startX + dx
                            p.y = startY + dy
                            try { wm.updateViewLayout(fab, p) } catch (_: Exception) { }
                        }
                        repositionPanel()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved && System.currentTimeMillis() - downTime > 600) {
                            hide()
                        } else {
                            if (!moved) fab.performClick()
                            // Snap to the nearest edge; the panel follows.
                            fabParams?.let { p ->
                                val screenW = context.resources.displayMetrics.widthPixels
                                p.x = if (p.x + dp(28) < screenW / 2) dp(16)
                                    else screenW - dp(56) - dp(16)
                                try { wm.updateViewLayout(fab, p) } catch (_: Exception) { }
                            }
                            repositionPanel()
                        }
                        true
                    }
                    else -> false
                }
            }
            val params = WindowManager.LayoutParams(
                dp(56), dp(56),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(16)
                y = dp(120)
            }
            wm.addView(fab, params)
            fabView = fab
            fabParams = params
            shown = true
            // F8 half A: notice a presentation display appearing or
            // disappearing while the viewer is open (plug / unplug,
            // Smart View connect / disconnect). Best-effort: a
            // failed registration only loses the toast.
            displayListener = ExternalDisplay.registerListener(
                context.applicationContext,
                onAdded = {
                    (context as? android.app.Activity)?.runOnUiThread {
                        Toast.makeText(context, context.getString(R.string.ui_display_found), Toast.LENGTH_LONG).show()
                    }
                },
                onRemoved = {
                    (context as? android.app.Activity)?.runOnUiThread {
                        Toast.makeText(context, context.getString(R.string.ui_display_lost), Toast.LENGTH_LONG).show()
                    }
                }
            )
        } catch (e: Exception) {
            // Overlay not available (permission revoked, etc.): skip silently.
            shown = false
        }
    }

    /** Hide the FAB and the panel. */
    fun hide() {
        hideHud()
        ExternalDisplay.unregisterListener(context.applicationContext, displayListener)
        displayListener = null
        try { panelView?.let { wm.removeView(it) } } catch (_: Exception) { }
        try { fabView?.let { wm.removeView(it) } } catch (_: Exception) { }
        panelView = null
        fabView = null
        panelParams = null
        fabParams = null
        shown = false
        expanded = false
    }

    /** Collapse the panel, keeping the FAB. Called when the viewer
     *  activity pauses (HOME / viewer opens): the wide panel must
     *  not sit over the home screen blocking icons. */
    fun collapsePanel() {
        if (expanded) hidePanel()
    }

    /** Panel position next to the FAB: right of it when there is room,
     *  left of it near the right edge (so it never runs off-screen).
     *  The panel is ~344dp wide (6 x 48dp buttons + margins/padding). */
    private fun panelPosition(): Pair<Int, Int> {
        val fx = fabParams?.x ?: dp(16)
        val fy = fabParams?.y ?: dp(120)
        val screenW = context.resources.displayMetrics.widthPixels
        val panelW = dp(344)
        val x = if (fx + dp(64) + panelW > screenW) fx - panelW else fx + dp(64)
        return Pair(x, fy)
    }

    /** Move the open panel to follow the FAB (drag / edge snap). */
    private fun repositionPanel() {
        val panel = panelView ?: return
        val params = panelParams ?: return
        val (x, y) = panelPosition()
        params.x = x
        params.y = y
        try { wm.updateViewLayout(panel, params) } catch (_: Exception) { }
    }

    private fun togglePanel() {
        if (expanded) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (panelView != null) return
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.overlay_panel_bg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        panel.addView(iconButton(R.drawable.ic_record, context.getString(R.string.ui_overlay_record)) { onRecord(panel) })
        panel.addView(iconButton(R.drawable.ic_tv, context.getString(R.string.ui_overlay_tv)) { onTv() })
        panel.addView(iconButton(R.drawable.ic_audio, context.getString(R.string.ui_overlay_audio)) { onAudio(panel) })
        panel.addView(iconButton(R.drawable.ic_controls, context.getString(R.string.control_center)) { onControlCenter() })
        panel.addView(iconButton(R.drawable.ic_hud, context.getString(R.string.ui_overlay_hud)) { onHud(panel) })
        // F8 half B: web remote control, the 6th panel button.
        panel.addView(iconButton(R.drawable.ic_remote, context.getString(R.string.ui_remote_title)) { onRemote() })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (px, py) = panelPosition()
            x = px
            y = py
        }
        try {
            wm.addView(panel, params)
            panelView = panel
            panelParams = params
            expanded = true
        } catch (_: Exception) {
            expanded = false
        }
    }

    private fun hidePanel() {
        try { panelView?.let { wm.removeView(it) } } catch (_: Exception) { }
        panelView = null
        panelParams = null
        expanded = false
    }

    private fun iconButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(iconRes)
            contentDescription = desc
            setBackgroundResource(R.drawable.overlay_icon_bg)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val lp = LinearLayout.LayoutParams(dp(48), dp(48))
            lp.marginStart = dp(4)
            lp.marginEnd = dp(4)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    /** Record toggle (same rule as VncActivity): start or stop, then refresh the icon. */
    private fun onRecord(panel: LinearLayout) {
        Thread {
            if (ScreenRecorder.isRecording(context)) {
                val out = ScreenRecorder.stop(context)
                ScreenRecorder.hideStopNotification(context)
                (context as? android.app.Activity)?.runOnUiThread {
                    Toast.makeText(
                        context,
                        if (out != null) context.getString(R.string.record_saved, out.absolutePath)
                        else context.getString(R.string.record_not_running),
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshRecordIcon(panel, recording = false)
            } else {
                if (!GuestSession.booted || !X11Session.isTcpDisplayOpen()) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        Toast.makeText(context, context.getString(R.string.ui_vnc_desktop_not_ready), Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val startedFile = ScreenRecorder.start(context)
                (context as? android.app.Activity)?.runOnUiThread {
                    if (startedFile != null) {
                        ScreenRecorder.showStopNotification(context)
                        Toast.makeText(context, context.getString(R.string.record_running), Toast.LENGTH_SHORT).show()
                    } else {
                        // The viewer is on top, so a dialog would not be
                        // seen: a Toast carries the failure instead.
                        Toast.makeText(context, context.getString(R.string.record_failed), Toast.LENGTH_LONG).show()
                    }
                }
                refreshRecordIcon(panel, recording = startedFile != null)
            }
        }.start()
    }

    private fun refreshRecordIcon(panel: LinearLayout, recording: Boolean) {
        (context as? android.app.Activity)?.runOnUiThread {
            val btn = panel.getChildAt(0) as? ImageButton ?: return@runOnUiThread
            btn.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_record)
        }
    }

    /**
     * F8 half A, TV: a system-connected display (Smart View / DeX /
     * HDMI) opens the viewer on it (unchanged). With more than one
     * presentation display, a branded picker asks which one; with
     * exactly one it opens directly. With no such display, p36
     * defect 3 routes through the SAME chooser as the status
     * screen (VncActivity.showTvChooser, Cast first): this activity
     * is brought forward with EXTRA_SHOW_TV_CHOOSER and the chooser
     * opens from its onNewIntent. Never a direct settings open from
     * here anymore: on this phone that landed on "Display and
     * brightness", which has no TV picker at all.
     */
    private fun onTv() {
        Thread {
            val displays = ExternalDisplay.presentationDisplays(context)
            if (displays.isEmpty()) {
                // No system display: hand the TV choice to
                // VncActivity, same dialog as its btnTv, so the user
                // can choose Cast first. The viewer is on top, so
                // the activity is brought forward first (singleTop:
                // onNewIntent opens the chooser, no desktop restart).
                try {
                    val intent = Intent(context, VncActivity::class.java).apply {
                        putExtra(VncActivity.EXTRA_SHOW_TV_CHOOSER, true)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    // Explicit in-app intent: unreachable in practice.
                    // Stay silent rather than promise a settings page
                    // that does not exist on this phone.
                }
                return@Thread
            }
            if (!GuestSession.booted || !X11Session.isTcpDisplayOpen()) {
                (context as? android.app.Activity)?.runOnUiThread {
                    Toast.makeText(context, context.getString(R.string.ui_vnc_desktop_not_ready), Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            if (displays.size == 1) {
                openOnDisplay(displays[0])
                return@Thread
            }
            // More than one external display: ask which one. The
            // dialog MUST be shown by the resumed VncActivity, not
            // from here: a dialog attached to this stopped activity
            // (the viewer is in front) exists as a window but never
            // gains input focus — rig-proven 22 Nov, DPAD and BACK
            // both go to the viewer instead. So route there, like
            // the no-display case, and VncActivity shows the picker
            // directly (EXTRA_SHOW_DISPLAY_PICKER).
            try {
                val intent = Intent(context, VncActivity::class.java).apply {
                    putExtra(VncActivity.EXTRA_SHOW_DISPLAY_PICKER, true)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                // Explicit in-app intent: unreachable in practice.
                // Fall back to the first display, never strand the
                // user without a TV path.
                Thread { openOnDisplay(displays[0]) }.start()
            }
        }.start()
    }

    /** Open the viewer on one presentation display + report the result. */
    private fun openOnDisplay(display: android.view.Display) {
        val ok = ExternalDisplay.openViewerOn(context, display)
        (context as? android.app.Activity)?.runOnUiThread {
            Toast.makeText(
                context,
                if (ok) context.getString(R.string.tv_opened) else context.getString(R.string.tv_no_display),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Audio toggle (same rule as VncActivity). */
    private fun onAudio(panel: LinearLayout) {
        Thread {
            val nowEnabled = !PulseAudio.isEnabled(context)
            PulseAudio.setEnabled(context, nowEnabled)
            if (nowEnabled) {
                if (GuestSession.booted && X11Session.isTcpDisplayOpen()) {
                    val started = PulseAudio.start(context)
                    if (!started) {
                        (context as? android.app.Activity)?.runOnUiThread {
                            Toast.makeText(context, context.getString(R.string.audio_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    (context as? android.app.Activity)?.runOnUiThread {
                        Toast.makeText(context, context.getString(R.string.ui_vnc_audio_starts_later), Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                PulseAudio.stop(context)
            }
            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(
                    context,
                    if (nowEnabled) context.getString(R.string.audio_on) else context.getString(R.string.audio_off),
                    Toast.LENGTH_SHORT
                ).show()
                // Tint the audio icon when enabled (white = on, dim = off).
                val btn = panel.getChildAt(2) as? ImageButton
                btn?.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (nowEnabled) 0xFFFFFFFF.toInt() else 0xFF9E9E9E.toInt()
                )
            }
        }.start()
    }

    /** p111: open the Control Center over the viewer. The context
     *  is the VncActivity, so no NEW_TASK flag is needed (same as
     *  the onTv activity start). Opening pauses the viewer, so the
     *  open panel collapses but the FAB stays. */
    private fun onControlCenter() {
        try {
            context.startActivity(Intent(context, ControlCenterActivity::class.java))
        } catch (_: Exception) {
            // Explicit in-app intent: unreachable in practice.
            // Stay silent rather than promise a panel that did
            // not open.
        }
    }

    /** F8 half B: web remote control, the 6th panel button. Opens
     *  the remote screen; the feature starts only there, never
     *  from this button alone. The panel collapses so it does not
     *  sit over the remote screen covering the address row. */
    private fun onRemote() {
        try {
            collapsePanel()
            context.startActivity(Intent(context, RemoteControlActivity::class.java))
        } catch (_: Exception) {
            // Explicit in-app intent: unreachable in practice.
        }
    }

    // F4: performance HUD ----------------------------------------------

    /** Guest sampler: writes /tmp/mudif-hud/status.txt every second
     *  (guest /tmp is the Android filesDir/tmp bind mount, so the app
     *  reads it as a plain file). Started on HUD toggle-on, killed
     *  on toggle-off / overlay hide. */
    private val samplerScript: String
        get() = """
            #!/bin/sh
            mkdir -p /tmp/mudif-hud
            while true; do
              set -- $(awk '/MemTotal/{t=$2} /MemAvailable/{a=$2} END{print t, a}' /proc/meminfo)
              echo "memTotalKb=$1 memAvailKb=$2 ts=$(date +%s)" > /tmp/mudif-hud/status.txt
              sleep 1
            done
            """.trimIndent()

    private fun startGuestSampler() {
        if (!GuestSession.booted) return
        try {
            val dir = File(context.filesDir, "tmp/mudif-hud")
            dir.mkdirs()
            File(dir, "sampler.sh").writeText(samplerScript)
            // Kill a stale sampler first (an earlier session may have
            // died without killing its loop), then start a fresh one.
            GuestSession.sendCommand(
                "kill \$(cat /tmp/mudif-hud/sampler.pid 2>/dev/null) 2>/dev/null; " +
                    "nohup sh /tmp/mudif-hud/sampler.sh >/tmp/mudif-hud/sampler.log 2>&1 & " +
                    "echo \$! > /tmp/mudif-hud/sampler.pid"
            )
        } catch (_: Exception) {
            // Sampler is best-effort: RAM shows "…" without it.
        }
    }

    private fun stopGuestSampler() {
        try {
            GuestSession.sendCommand(
                "kill \$(cat /tmp/mudif-hud/sampler.pid 2>/dev/null) 2>/dev/null; " +
                    "rm -f /tmp/mudif-hud/sampler.pid"
            )
        } catch (_: Exception) {
            // Best-effort; a stale loop only rewrites a tiny file.
        }
    }

    /** F4: toggle the HUD chip. 5th panel button; tint mirrors the
     *  audio button (white = on, dim = off). */
    private fun onHud(panel: LinearLayout) {
        if (hudShown) hideHud() else showHud()
        (context as? android.app.Activity)?.runOnUiThread {
            val btn = panel.getChildAt(4) as? ImageButton
            btn?.imageTintList = android.content.res.ColorStateList.valueOf(
                if (hudShown) 0xFFFFFFFF.toInt() else 0xFF9E9E9E.toInt()
            )
        }
    }

    private fun showHud() {
        if (hudView != null) return
        val tv = TextView(context).apply {
            setBackgroundResource(R.drawable.overlay_panel_bg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = ResourcesCompat.getFont(context, R.font.cairo)
            text = hudText()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(16)
        }
        try {
            wm.addView(tv, params)
            hudView = tv
            hudParams = params
            hudShown = true
            hudTickCount = 0
            startGuestSampler()
            hudHandler.post(hudTick)
        } catch (_: Exception) {
            hudShown = false
        }
    }

    private fun hideHud() {
        hudHandler.removeCallbacks(hudTick)
        stopGuestSampler()
        try { hudView?.let { wm.removeView(it) } } catch (_: Exception) { }
        hudView = null
        hudParams = null
        hudShown = false
    }

    /** 1 s ticker: battery + temperature + guest RAM every tick,
     *  FPS (logcat, 5 s granularity) every 5th tick. */
    private val hudTick = object : Runnable {
        override fun run() {
            if (!hudShown) return
            hudTickCount++
            updateHudText()
            if (hudTickCount % 5 == 0) refreshFpsAsync()
            hudHandler.postDelayed(this, 1000L)
        }
    }

    private fun updateHudText() {
        hudView?.text = hudText()
    }

    private fun hudText(): String {
        val fpsPart = when {
            lastFps == null && !lastFpsSeen -> "FPS …"
            lastFps == null -> "FPS 0"
            else -> "FPS " + HudStats.formatFps(lastFps!!)
        }
        val ramPart = readGuestRam()
        val (pct, tempTenths) = readBattery()
        val batteryPart = if (pct >= 0) {
            context.getString(R.string.ui_hud_battery) + " " + pct + "%"
        } else {
            context.getString(R.string.ui_hud_battery) + " …"
        }
        val tempPart = if (tempTenths != Int.MIN_VALUE) {
            // NOTE (F4): the degree sign renders detached at the line end
            // (bidi); LRI and RLI isolates were both tried and made the
            // layout worse, so the plain string stays. Cosmetic only.
            context.getString(R.string.ui_hud_temp) + " " + HudStats.formatTemp(tempTenths)
        } else {
            context.getString(R.string.ui_hud_temp) + " …"
        }
        return fpsPart + " | " + ramPart + " | " + batteryPart + " | " + tempPart
    }

    /** Guest RAM from the sampler status file; "RAM …" when the
     *  sampler has not written yet or its data is older than 3 s. */
    private fun readGuestRam(): String {
        return try {
            val text = File(context.filesDir, "tmp/mudif-hud/status.txt").readText()
            val status = HudStats.parseGuestStatus(text) ?: return "RAM …"
            val ageSec = System.currentTimeMillis() / 1000 - status.tsSec
            if (ageSec < 0 || ageSec > 3) return "RAM …"
            "RAM " + HudStats.formatRam(status.memTotalKb, status.memAvailKb)
        } catch (_: Exception) {
            "RAM …"
        }
    }

    /** Battery level % + temperature tenths °C, same sticky-intent
     *  call as ControlCenterActivity. Returns (-1, MIN_VALUE) when
     *  unreadable. */
    private fun readBattery(): Pair<Int, Int> {
        return try {
            val battery = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return Pair(-1, Int.MIN_VALUE)
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            Pair(pct, temp)
        } catch (_: Exception) {
            Pair(-1, Int.MIN_VALUE)
        }
    }

    /** FPS from the fork's LorieNative logcat line (own UID logs,
     *  no permission). Runs off the main thread; a failed read
     *  (no logd on this rig) leaves the last value alone. */
    private fun refreshFpsAsync() {
        Thread {
            val out = readLorieLogcat()
            if (out.isEmpty()) return@Thread
            val res = HudStats.parseFpsFromLogcat(out, System.currentTimeMillis())
            (context as? android.app.Activity)?.runOnUiThread {
                lastFps = res.fps
                lastFpsSeen = res.everSeen
                if (hudShown) updateHudText()
            }
        }.start()
    }

    private fun readLorieLogcat(): String {
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("/system/bin/logcat", "-d", "-t", "200", "-v", "time")
            )
            if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroy()
            }
            proc.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            if (!logcatFailedLogged) {
                logcatFailedLogged = true
                Log.w("ViewerOverlay", "HUD logcat read failed: " + e.message)
            }
            ""
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
        ).toInt()
}
