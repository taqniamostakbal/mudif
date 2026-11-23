package com.bolm.mudif.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 half B: the remote session password is the one pure piece
 * of the service (the rest needs the guest + the network, so it
 * is proven on the rig, not here). The password must be exactly
 * 8 chars from [A-Za-z0-9] (the VNC DES limit: x11vnc truncates
 * anything longer, and the login screen must reject anything
 * else). Runs with testDebugUnitTest.
 */
class RemoteControlTest {

    @Test
    fun `generated password is 8 chars from A-Za-z0-9`() {
        repeat(100) {
            val pw = RemoteControlService.generatePassword()
            assertEquals(8, pw.length)
            assertTrue(RemoteControlService.isValidPassword(pw))
        }
    }

    @Test
    fun `generated passwords differ`() {
        val seen = (1..50).map { RemoteControlService.generatePassword() }.toSet()
        assertTrue(seen.size > 45)
    }

    @Test
    fun `validator rejects the wrong shapes`() {
        assertFalse(RemoteControlService.isValidPassword(null))
        assertFalse(RemoteControlService.isValidPassword(""))
        assertFalse(RemoteControlService.isValidPassword("abc123"))
        assertFalse(RemoteControlService.isValidPassword("abc123456"))
        assertFalse(RemoteControlService.isValidPassword("ab c1234"))
        assertFalse(RemoteControlService.isValidPassword("ab-c1234"))
        assertFalse(RemoteControlService.isValidPassword("abcd!fgh"))
        assertTrue(RemoteControlService.isValidPassword("abcdefgh"))
        assertTrue(RemoteControlService.isValidPassword("Ab3dEf9k"))
    }

    // F3 side task: the F8 R6 self-heal branch. It cannot be
    // reached through the UI (the activity calls start() only
    // when not RUNNING), so it is proven here through the seam
    // healStaleRunning(). state/password/lanIp have private
    // setters, so the tests preset them by reflection.
    @After
    fun resetServiceState() {
        setServiceField("state", RemoteControlService.State.STOPPED)
        setServiceField("password", null)
        setServiceField("lanIp", null)
    }

    private fun setServiceField(name: String, value: Any?) {
        val field = RemoteControlService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(RemoteControlService, value)
    }

    @Test
    fun `healStaleRunning resets a stale RUNNING session`() {
        setServiceField("state", RemoteControlService.State.RUNNING)
        setServiceField("password", "Ab3dEf9k")
        setServiceField("lanIp", "192.168.1.5")
        assertTrue(RemoteControlService.healStaleRunning(false))
        assertEquals(RemoteControlService.State.STOPPED, RemoteControlService.state)
        assertNull(RemoteControlService.password)
        assertNull(RemoteControlService.lanIp)
    }

    @Test
    fun `healStaleRunning leaves a live RUNNING session alone`() {
        setServiceField("state", RemoteControlService.State.RUNNING)
        setServiceField("password", "Ab3dEf9k")
        setServiceField("lanIp", "192.168.1.5")
        assertFalse(RemoteControlService.healStaleRunning(true))
        assertEquals(RemoteControlService.State.RUNNING, RemoteControlService.state)
        assertEquals("Ab3dEf9k", RemoteControlService.password)
        assertEquals("192.168.1.5", RemoteControlService.lanIp)
    }

    @Test
    fun `healStaleRunning does nothing when stopped`() {
        setServiceField("state", RemoteControlService.State.STOPPED)
        assertFalse(RemoteControlService.healStaleRunning(false))
        assertEquals(RemoteControlService.State.STOPPED, RemoteControlService.state)
    }

    // F3 side task: the F8 R4 guard is NOT unit-testable on this
    // VM, and the attempt proved why. X11Session.isTcpDisplayOpen()
    // is a real TCP connect to 127.0.0.1:6006, but on this VM's
    // JVM every loopback connect "succeeds": a standalone Java
    // probe connected to 6006, 5999, 6007, 18080 and even port 1,
    // while Python connect_ex to the same ports returns 111
    // (refused) in the same shell. So the probe's false branch
    // can never be observed here, and a test that can only pass
    // is not a test. The guard itself works on-device (the rig
    // runs prove it detects :6 up and down); only the JVM unit
    // test is impossible in this sandbox. By code inspection,
    // start() does not reference GuestSession.booted anywhere
    // except the comment explaining why it must not gate on it.
}
