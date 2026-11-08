package de.mm20.launcher2.capture

import org.junit.Assert.*
import org.junit.Test

class SyncPolicyTest {
    @Test fun transientFailuresStayQueued() {
        listOf(403, 408, 429, 500, 502, 503).forEach { assertEquals("retry", SyncPolicy.stateForHttp(it)) }
    }
    @Test fun expiredAuthorizationNeedsUserAction() { assertEquals("auth_required", SyncPolicy.stateForHttp(401)) }
    @Test fun malformedAndMissingRemoteFilesAreVisibleFailures() {
        listOf(400, 404, 409, 422).forEach { assertEquals("failed", SyncPolicy.stateForHttp(it)) }
    }
    @Test fun capturesCannotMoveBetweenGoogleAccounts() {
        assertTrue(SyncPolicy.sameOwner("", "first"))
        assertTrue(SyncPolicy.sameOwner("first", "first"))
        assertFalse(SyncPolicy.sameOwner("first", "second"))
    }
}
