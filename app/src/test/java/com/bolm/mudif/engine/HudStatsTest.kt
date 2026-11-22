package com.bolm.mudif.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F4: HudStats is pure Kotlin (no Android APIs), so the FPS parser,
 * the guest status parser and the formatters are pinned down on the
 * JVM. The live FPS number itself cannot be proven on the rig (no
 * logd there); these tests prove the parsing of the exact lines the
 * fork's X server logs (InitOutput.c: "N frames in 5.0 seconds =
 * X FPS", tag LorieNative, -v time format).
 */
class HudStatsTest {

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun logcatLine(atMs: Long, text: String): String {
        return "${timeFmt.format(Date(atMs))}  12345 12345 I LorieNative: $text"
    }

    private fun fpsLine(atMs: Long, frames: Int, fps: String): String {
        return logcatLine(atMs, "$frames frames in 5.0 seconds = $fps FPS")
    }

    @Test
    fun `fresh FPS line parses`() {
        val now = 1795340000000L
        val out = fpsLine(now - 2000, 123, "24.6")
        val res = HudStats.parseFpsFromLogcat(out, now)
        assertEquals(24.6f, res.fps!!, 0.001f)
        assertTrue(res.everSeen)
    }

    @Test
    fun `stale FPS line means zero frames`() {
        val now = 1795340000000L
        val out = fpsLine(now - 30_000, 123, "24.6")
        val res = HudStats.parseFpsFromLogcat(out, now)
        assertEquals(0f, res.fps!!, 0.001f)
        assertTrue(res.everSeen)
    }

    @Test
    fun `no FPS line means never seen`() {
        val now = 1795340000000L
        val out = logcatLine(now - 1000, "something else entirely")
        val res = HudStats.parseFpsFromLogcat(out, now)
        assertNull(res.fps)
        assertTrue(!res.everSeen)
    }

    @Test
    fun `newest FPS line wins`() {
        val now = 1795340000000L
        val out = fpsLine(now - 5000, 100, "20.0") + "\n" +
            fpsLine(now - 1000, 300, "60.0")
        val res = HudStats.parseFpsFromLogcat(out, now)
        assertEquals(60f, res.fps!!, 0.001f)
    }

    @Test
    fun `line without timestamp still counts as fresh`() {
        val now = 1795340000000L
        val out = "123 frames in 5.0 seconds = 30.0 FPS"
        val res = HudStats.parseFpsFromLogcat(out, now)
        assertEquals(30f, res.fps!!, 0.001f)
    }

    @Test
    fun `guest status parses`() {
        val s = HudStats.parseGuestStatus("memTotalKb=8155464 memAvailKb=4774600 ts=1795340000")
        assertEquals(8155464L, s!!.memTotalKb)
        assertEquals(4774600L, s.memAvailKb)
        assertEquals(1795340000L, s.tsSec)
    }

    @Test
    fun `guest status rejects garbage`() {
        assertNull(HudStats.parseGuestStatus("memTotalKb=abc memAvailKb=1 ts=2"))
        assertNull(HudStats.parseGuestStatus("memTotalKb=100 memAvailKb=200 ts=2"))
        assertNull(HudStats.parseGuestStatus(""))
        assertNull(HudStats.parseGuestStatus("memTotalKb=100 ts=2"))
    }

    @Test
    fun `RAM formats used over total`() {
        assertEquals("3.2/7.8 GB", HudStats.formatRam(8155464L, 4774600L))
        assertEquals("0.0/7.8 GB", HudStats.formatRam(8155464L, 8155464L))
    }

    @Test
    fun `temperature formats`() {
        assertEquals("25°", HudStats.formatTemp(250))
        assertEquals("25.5°", HudStats.formatTemp(255))
        assertEquals("0°", HudStats.formatTemp(0))
    }

    @Test
    fun `FPS formats without trailing zero`() {
        assertEquals("60", HudStats.formatFps(60f))
        assertEquals("24.6", HudStats.formatFps(24.6f))
    }
}
