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

    /** Width the frame is downscaled to (keeps it ~100-250 KB). */
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
        val resolution = X11Session.viewerResolution(appCtx)
        val cmd = "ffmpeg -y -f x11grab -video_size $resolution " +
            "-i 127.0.0.1:6 -frames:v 1 -vf scale=$SHOT_WIDTH:-1 " +
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
        Log.d(TAG, "capture: ${shot.length()} bytes at $resolution")
        return CaptureResult.Ok(shot, shot.length())
    }
}
