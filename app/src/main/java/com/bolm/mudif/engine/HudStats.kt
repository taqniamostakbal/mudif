package com.bolm.mudif.engine

import java.util.Calendar
import java.util.Locale

/**
 * F4, performance HUD: the pure, JVM-testable pieces of the overlay
 * chip (FPS + guest RAM + battery + temperature).
 *
 * Where each number comes from [verified]:
 * - FPS: the fork's own rendered-frame counter. The Lorie X server
 *   counts every eglSwapBuffers in its renderer thread
 *   (renderer.c:755, `state->renderedFrames++`) and logs
 *   "%d frames in 5.0 seconds = %.1f FPS" with tag LorieNative
 *   every 5 s, only when frames > 0 (InitOutput.c:448-454). No fork
 *   patch, no JitPack rebuild: the counter already ships in the
 *   p126 AAR (stock Lorie code). The app reads its OWN logcat
 *   (same UID, no permission needed) and parses that line.
 *   Granularity is 5 s by construction; silence longer than 6 s
 *   means the desktop rendered 0 frames (static desktop).
 * - Guest RAM: the guest /proc/meminfo, read by a small sampler
 *   loop the app starts in the guest (writes
 *   /tmp/mudif-hud/status.txt, which is the Android
 *   filesDir/tmp bind mount, so the app reads it as a plain file).
 *   Never the Android app process memory: the guest number is the
 *   one that matches `free -m` in the guest terminal.
 * - Battery % and temperature: the sticky ACTION_BATTERY_CHANGED
 *   intent, same call as ControlCenterActivity (level/scale,
 *   EXTRA_TEMPERATURE in tenths of a degree Celsius).
 *
 * Falsified alternatives (measured on the rig 22 Nov, see
 * EVIDENCE-F4-HUD.md): guest xwd sampling (~120 ms per GetImage,
 * cap ~5/s at ~100% of a core); ffmpeg x11grab (stuck at 0.008x
 * realtime); XDamage via python-xlib (not installed in the
 * pre-built guest image); reading the fork's shared memfd (the fd
 * is passed privately between the fork's processes, no handle).
 * Rig limit, stated plainly: the rig has no logd and no working
 * logcat, so the live FPS number cannot be proven on the rig;
 * the parser is unit-tested here and the HUD shows "…" (no data)
 * there instead of a fake number.
 */
object HudStats {

    /** A parsed FPS line stays fresh for this long; older silence = 0 frames. */
    const val FPS_FRESH_MS = 6000L

    /** LorieNative line: "123 frames in 5.0 seconds = 24.6 FPS". */
    private val FPS_REGEX =
        Regex("""(\d+) frames in 5\.0 seconds = ([0-9.]+) FPS""")

    /** logcat -v time stamp at the start of a line: "11-22 13:57:49.295". */
    private val TIME_REGEX =
        Regex("""^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})""")

    data class FpsResult(val fps: Float?, val everSeen: Boolean)

    /**
     * Newest LorieNative FPS line in [logcatText], judged against
     * [nowMs]. fps is null when no line was ever seen ("…" in the
     * chip), 0f when a line was seen but is older than
     * [FPS_FRESH_MS] (static desktop, the X server logs nothing).
     */
    fun parseFpsFromLogcat(logcatText: String, nowMs: Long): FpsResult {
        var bestFps: Float? = null
        var bestTimeMs = Long.MIN_VALUE
        var seen = false
        for (line in logcatText.lines()) {
            val m = FPS_REGEX.find(line) ?: continue
            seen = true
            val t = parseLogcatTimeMs(line, nowMs) ?: nowMs
            if (t > bestTimeMs) {
                bestTimeMs = t
                bestFps = m.groupValues[2].toFloatOrNull()
            }
        }
        if (!seen || bestFps == null) return FpsResult(null, seen)
        return if (nowMs - bestTimeMs <= FPS_FRESH_MS) FpsResult(bestFps, true)
        else FpsResult(0f, true)
    }

    /** "MM-DD HH:MM:SS.mmm" at the start of a logcat -v time line. */
    fun parseLogcatTimeMs(line: String, nowMs: Long): Long? {
        val m = TIME_REGEX.find(line) ?: return null
        val (month, day, hour, minute, second, ms) = m.destructured
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
        cal.set(Calendar.MONTH, month.toInt() - 1)
        cal.set(Calendar.DAY_OF_MONTH, day.toInt())
        cal.set(Calendar.HOUR_OF_DAY, hour.toInt())
        cal.set(Calendar.MINUTE, minute.toInt())
        cal.set(Calendar.SECOND, second.toInt())
        cal.set(Calendar.MILLISECOND, ms.toInt())
        var t = cal.timeInMillis
        // 31 Dec -> 01 Jan rollover: a line dated next year is last year.
        if (t - nowMs > 60_000L) {
            cal.add(Calendar.YEAR, -1)
            t = cal.timeInMillis
        }
        return t
    }

    data class GuestStatus(val memTotalKb: Long, val memAvailKb: Long, val tsSec: Long)

    private val MEM_TOTAL_REGEX = Regex("""memTotalKb=(\d+)""")
    private val MEM_AVAIL_REGEX = Regex("""memAvailKb=(\d+)""")
    private val TS_REGEX = Regex("""ts=(\d+)""")

    /** Parse the guest sampler status file; null when malformed. */
    fun parseGuestStatus(text: String): GuestStatus? {
        val total = MEM_TOTAL_REGEX.find(text)?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        val avail = MEM_AVAIL_REGEX.find(text)?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        val ts = TS_REGEX.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (total <= 0 || avail < 0 || avail > total) return null
        return GuestStatus(total, avail, ts)
    }

    /** "2.1/7.8 GB" (used/total, one decimal, Latin digits). */
    fun formatRam(totalKb: Long, availKb: Long): String {
        val usedGb = (totalKb - availKb) / 1048576.0
        val totalGb = totalKb / 1048576.0
        return String.format(Locale.US, "%.1f/%.1f GB", usedGb, totalGb)
    }

    /** Battery temperature in tenths of a degree Celsius: "25°" / "25.5°". */
    fun formatTemp(tenths: Int): String {
        return if (tenths % 10 == 0) "${tenths / 10}°"
        else String.format(Locale.US, "%.1f°", tenths / 10.0)
    }

    /** FPS display: whole numbers without ".0" ("60", "24.6"). */
    fun formatFps(fps: Float): String {
        return if (fps % 1f == 0f) fps.toInt().toString()
        else String.format(Locale.US, "%.1f", fps)
    }
}
