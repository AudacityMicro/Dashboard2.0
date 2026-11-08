package de.mm20.launcher2.capture

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.dashboard.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class DashboardSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val feed = """{"schemaVersion":1,"updates":[{"id":"brief","title":"Agent brief","body":"Take an umbrella"}],"tasks":[{"id":"agent-1","title":"Review agenda","completed":false}]}"""
    private fun runSync(incoming: String, check: (DashboardStore, MockWebServer) -> Unit) {
        val name = "sync-test-${java.util.UUID.randomUUID()}.db"
        try {
            DashboardStore(context, name).use { store ->
                store.setMeta("agentFile", "agent-file"); store.setMeta("phoneFile", "phone-file")
                store.acceptBoard(feed)
                store.complete(store.snapshot().tasks.single(), true)
                store.addTask("Buy groceries")
                MockWebServer().use { server ->
                    server.start()
                    server.enqueue(MockResponse().setBody("""{"id":"agent-file","appProperties":{"captureId":"dashboard-agent-v1"}}"""))
                    server.enqueue(MockResponse().setBody(incoming))
                    server.enqueue(MockResponse().setBody("""{"id":"phone-file","appProperties":{"captureId":"dashboard-phone-v1"}}"""))
                    server.enqueue(MockResponse().setBody("{}"))
                    assertFalse(DashboardSync.sync(context, "fake-token", "test-account", server.url("/").toString().trimEnd('/'), name))
                    check(store, server)
                }
            }
        } finally { listOf(name, "$name-journal", "$name-wal", "$name-shm").forEach { File(context.noBackupFilesDir, it).delete() } }
    }
    @Test fun agentFileIsReadOnlyAndPhoneEditsAreExported() = runSync(feed) { store, server ->
        assertEquals(4, server.requestCount)
        repeat(3) { assertEquals("GET", server.takeRequest().method) }
        val outgoing = server.takeRequest()
        assertEquals("PATCH", outgoing.method)
        assertTrue(outgoing.path!!.contains("phone-file"))
        val tasks = JSONObject(outgoing.body.readUtf8()).getJSONArray("tasks")
        assertEquals(2, tasks.length())
        val remote = (0 until tasks.length()).map { tasks.getJSONObject(it) }.single { it.getString("id") == "agent-1" }
        assertTrue(remote.getBoolean("completed"))
        assertEquals("Take an umbrella", store.snapshot().updates.single().body)
        assertTrue(store.snapshot().lastSync > 0)
    }
    @Test fun malformedAgentFileRetainsCachedBoardWhileStillExportingPhoneTasks() = runSync("{invalid}") { store, server ->
        assertEquals("Take an umbrella", store.snapshot().updates.single().body)
        assertTrue(store.snapshot().status.contains("invalid"))
        repeat(3) { server.takeRequest() }
        assertEquals("PATCH", server.takeRequest().method)
    }
}
