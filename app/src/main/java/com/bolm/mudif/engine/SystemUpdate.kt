package com.bolm.mudif.engine

/**
 * Real-computer #4 — in-app system update: apt update + upgrade
 * inside the Debian guest, without reinstalling the image.
 *
 * Deliberately does NOT kill orphaned apt processes first: an
 * apt-get/dpkg holding the lock might be a LIVE store install, and
 * killing it mid-operation is exactly how the P16.3 orphan was
 * born. If the lock is held, apt fails with its own message and the
 * caller shows it; opening the desktop once clears real orphans
 * (VncActivity kills them on entry), then the update works.
 * Runs on a background thread; progress arrives as live output
 * lines via [onLine] (same streaming contract as TerminalSession).
 */
object SystemUpdate {

    fun run(
        context: android.content.Context,
        onLine: ((String) -> Unit)? = null
    ): TerminalResult {
        return TerminalSession.runCommandDebian(
            context,
            "(dpkg --configure -a; DEBIAN_FRONTEND=noninteractive apt-get update && " +
                "DEBIAN_FRONTEND=noninteractive apt-get upgrade -y) 2>&1",
            idleTimeoutSec = 300,
            maxTimeoutSec = 7200,
            onLine = onLine
        )
    }
}
