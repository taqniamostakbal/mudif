package com.bolm.mudif.ui.vnc

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bolm.mudif.R
import com.bolm.mudif.engine.GuestSession
import com.bolm.mudif.engine.PulseAudio
import com.bolm.mudif.engine.ScreenRecorder
import com.bolm.mudif.engine.X11Session
import com.bolm.mudif.ui.settings.ViewerPrefs

/**
 * Phase 1d: floating icon controls over the viewer. The VncActivity
 * status screen auto-opens the viewer in 2-4 s, so its text buttons
 * (record / TV / audio) are effectively unreachable. This overlay is
 * the reachable replacement: a draggable purple FAB that floats over
 * the Termux:X11 viewer, expanding to three professional icon
 * buttons (record, TV, audio). No foreground service: the overlay is
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
        } catch (e: Exception) {
            // Overlay not available (permission revoked, etc.): skip silently.
            shown = false
        }
    }

    /** Hide the FAB and the panel. */
    fun hide() {
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
     *  The panel is ~192dp wide (3 x 48dp buttons + margins/padding). */
    private fun panelPosition(): Pair<Int, Int> {
        val fx = fabParams?.x ?: dp(16)
        val fy = fabParams?.y ?: dp(120)
        val screenW = context.resources.displayMetrics.widthPixels
        val panelW = dp(192)
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
     * Phase 2, TV: a system-connected display (Smart View / DeX /
     * HDMI) opens the viewer on it (unchanged). With no such display,
     * p36 defect 3 routes through the SAME chooser as the status
     * screen (VncActivity.showTvChooser, Cast first): this activity
     * is brought forward with EXTRA_SHOW_TV_CHOOSER and the chooser
     * opens from its onNewIntent. Never a direct settings open from
     * here anymore: on this phone that landed on "Display and
     * brightness", which has no TV picker at all.
     */
    private fun onTv() {
        Thread {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val displays = dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
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
            ViewerPrefs.prefs(context).edit()
                .putBoolean("storeSecondaryDisplayPreferencesSeparately", true).apply()
            val ok = X11Session.openViewerOnDisplay(context, displays[0])
            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(
                    context,
                    if (ok) context.getString(R.string.tv_opened) else context.getString(R.string.tv_no_display),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
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

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
        ).toInt()
}
