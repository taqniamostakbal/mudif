package com.bolm.mudif.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Vision increment 1 ("see and describe", local only): one still
 * frame of the guest Debian desktop, taken with the guest ffmpeg
 * that is already in the image (x11grab on 127.0.0.1:6, the same
 * source TvStreamer uses for the TV stream).
 *
 * The file lands in guest /tmp, which is bind-mounted over the
 * app's files/tmp (same pattern as the bridge token file), so the
 * app reads the JPEG bytes straight from its own filesDir. No new
 * IPC, no new dependency.
 *
 * Gate: [X11Session.isTcpDisplayOpen] first. Desktop down means
 * vision is honestly unavailable ([Failed.reason] =
 * "desktop_down"), never a fake or stale frame: the previous shot
 * is deleted before every capture, so a failed ffmpeg cannot leave
 * an old picture behind to be read as new.
 */
object VisionCapture {
    private const val TAG = "VisionCapture"

    /** Guest path of the shot (bind-mounted to the app's files/tmp). */
    const val SHOT_GUEST_PATH = "/tmp/mudif-shot.jpg"

    /** The guest display the frame comes from. This is the ONLY
     *  capture source for desktop vision: the Android display is
     *  never a fallback, here or anywhere on the vision path. */
    const val SOURCE_DISPLAY = "127.0.0.1:6"
    const val SOURCE_LABEL = "guest_desktop_x11grab"

    /** Width the frame is downscaled to when the desktop is wider
     *  (keeps it ~100-250 KB). Desktops at or below this width are
     *  captured at native size. */
    const val SHOT_WIDTH = 960

    sealed class CaptureResult {
        data class Ok(val file: File, val bytes: Long) : CaptureResult()
        data class Failed(val reason: String) : CaptureResult()
    }

    const val REASON_DESKTOP_DOWN = "desktop_down"
    const val REASON_CAPTURE_FAILED = "capture_failed"

    fun shotFile(context: Context): File =
        File(File(context.filesDir, "tmp"), "mudif-shot.jpg")

    /** One frame, or [Failed] with the real reason. Never called on
     *  the UI thread (the ffmpeg run takes seconds). */
    fun capture(context: Context): CaptureResult {
        if (!X11Session.isTcpDisplayOpen()) {
            Log.w(TAG, "capture: desktop display is down")
            return CaptureResult.Failed(REASON_DESKTOP_DOWN)
        }
        val appCtx = context.applicationContext
        val shot = shotFile(appCtx)
        // Delete any previous shot first: a failed ffmpeg must not
        // leave a stale picture to be read as a fresh one.
        try {
            shot.parentFile?.mkdirs()
            if (shot.exists()) shot.delete()
        } catch (e: Exception) {
            Log.w(TAG, "capture: could not clear old shot", e)
        }
        // The requested viewer resolution can differ from the size
        // the server actually settled at (rig: requested 960x540,
        // server 848x480), and x11grab rejects a -video_size larger
        // than the screen. Query the real size first; a failed query
        // fails the capture, never a guessed size.
        val actual = queryDisplaySize(appCtx) ?: run {
            Log.w(TAG, "capture: could not read the real display size")
            return CaptureResult.Failed(REASON_CAPTURE_FAILED)
        }
        val size = "${actual.first}x${actual.second}"
        val scaleFilter =
            if (actual.first > SHOT_WIDTH) " -vf scale=$SHOT_WIDTH:-1" else ""
        val cmd = "ffmpeg -y -f x11grab -video_size $size " +
            "-i $SOURCE_DISPLAY -frames:v 1$scaleFilter " +
            SHOT_GUEST_PATH
        val result = try {
            TerminalSession.runCommandDebian(appCtx, cmd, 30, 60)
        } catch (e: Exception) {
            Log.w(TAG, "capture: ffmpeg run failed", e)
            return CaptureResult.Failed(REASON_CAPTURE_FAILED)
        }
        if (result.exitCode != 0 || !shot.exists() || shot.length() == 0L) {
            Log.w(TAG, "capture: ffmpeg exit=${result.exitCode} " +
                "shot exists=${shot.exists()} size=${shot.length()}")
            return CaptureResult.Failed(REASON_CAPTURE_FAILED)
        }
        Log.i(TAG, "capture: source=$SOURCE_LABEL display=$SOURCE_DISPLAY size=$size file=$SHOT_GUEST_PATH bytes=${shot.length()}")
        return CaptureResult.Ok(shot, shot.length())
    }

    /** Real server size from xdpyinfo ("dimensions: 848x480 pixels"),
     *  or null when it cannot be read. xdpyinfo is in the image's
     *  x11-utils, same package as the xwininfo the plan already
     *  relies on. */
    private fun queryDisplaySize(context: Context): Pair<Int, Int>? {
        val result = try {
            TerminalSession.runCommandDebian(
                context, "xdpyinfo -display $SOURCE_DISPLAY", 10, 30
            )
        } catch (e: Exception) {
            Log.w(TAG, "capture: xdpyinfo run failed", e)
            return null
        }
        if (result.exitCode != 0) {
            Log.w(TAG, "capture: xdpyinfo exit=${result.exitCode}")
            return null
        }
        val match = Regex("""dimensions:\s*(\d+)x(\d+)\s*pixels""")
            .find(result.output)
        if (match == null) {
            Log.w(TAG, "capture: xdpyinfo gave no dimensions line")
            return null
        }
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "capture: xdpyinfo gave bad size ${width}x$height")
            return null
        }
        return width to height
    }
}
