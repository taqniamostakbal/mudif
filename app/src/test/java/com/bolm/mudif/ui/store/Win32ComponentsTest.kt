package com.bolm.mudif.ui.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F6: Win32Components is pure Kotlin (no Android APIs), so the
 * registry, the install gate input, and the card labels are pinned
 * down on the JVM. The gate itself lives in ProgramStore.install;
 * these tests prove the exact lists it decides on.
 */
class Win32ComponentsTest {

    @Test
    fun `msvcrt and vcruntime140 are available`() {
        assertTrue(Win32Components.forId("msvcrt")!!.available)
        assertTrue(Win32Components.forId("vcruntime140")!!.available)
    }

    @Test
    fun `msvcp140 concrt140 and directx are unavailable`() {
        assertTrue(!Win32Components.forId("msvcp140")!!.available)
        assertTrue(!Win32Components.forId("concrt140")!!.available)
        assertTrue(!Win32Components.forId("directx")!!.available)
    }

    @Test
    fun `unknown id is not registered`() {
        assertNull(Win32Components.forId("no-such-component"))
    }

    @Test
    fun `no declared components means nothing missing`() {
        assertTrue(Win32Components.missingFor(null).isEmpty())
        assertTrue(Win32Components.missingFor(emptyList()).isEmpty())
    }

    @Test
    fun `available components only means nothing missing`() {
        assertTrue(
            Win32Components.missingFor(listOf("msvcrt", "vcruntime140")).isEmpty()
        )
    }

    @Test
    fun `one unavailable component is reported by id`() {
        val missing = Win32Components.missingFor(listOf("msvcrt", "msvcp140"))
        assertEquals(listOf("msvcp140"), missing.map { it.id })
    }

    @Test
    fun `directx is reported missing`() {
        val missing = Win32Components.missingFor(listOf("directx"))
        assertEquals(listOf("directx"), missing.map { it.id })
    }

    @Test
    fun `unknown id counts as missing`() {
        val missing = Win32Components.missingFor(listOf("no-such-component"))
        assertEquals(listOf("no-such-component"), missing.map { it.id })
    }

    @Test
    fun `labels join the declared components`() {
        val labels = Win32Components.labelsFor(listOf("msvcrt", "vcruntime140"))
        assertTrue(labels.contains("msvcrt.dll"))
        assertTrue(labels.contains("vcruntime140.dll"))
    }

    @Test
    fun `labels are empty with no declared components`() {
        assertEquals("", Win32Components.labelsFor(null))
        assertEquals("", Win32Components.labelsFor(emptyList()))
    }
}
