package de.mm20.launcher2.capture

import org.junit.Assert.*
import org.junit.Test

class ResumeSyncGateTest {
    @Test fun rapidHomeReturnsAreCoalescedAndLaterReturnsRefresh() {
        val gate = ResumeSyncGate()
        assertTrue(gate.allow(100, resuming = true))
        repeat(20) { assertFalse(gate.allow(101L + it, resuming = true)) }
        assertFalse(gate.allow(30_099, resuming = true))
        assertTrue(gate.allow(30_100, resuming = true))
    }
    @Test fun capturesTaskEditsAndManualRefreshAlwaysBypassTheResumeLimit() {
        val gate = ResumeSyncGate()
        assertTrue(gate.allow(100, resuming = true))
        repeat(10) { assertTrue(gate.allow(101L + it, resuming = false)) }
        assertFalse(gate.allow(111, resuming = true))
        assertTrue(gate.allow(30_110, resuming = true))
    }
    @Test fun aResetMonotonicClockDoesNotBlockFutureRequests() {
        val gate = ResumeSyncGate()
        assertTrue(gate.allow(900_000, resuming = true))
        assertTrue(gate.allow(10, resuming = true))
        assertFalse(gate.allow(11, resuming = true))
    }
}
