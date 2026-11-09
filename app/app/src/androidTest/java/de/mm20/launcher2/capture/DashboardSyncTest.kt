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
                store.setMeta("instructionsFile", "instructions-file")
                store.acceptBoard(feed)
                store.complete(store.snapshot().tasks.single(), true)
                store.addTask("Buy groceries")
                MockWebServer().use { server ->
                    server.start()
                    server.enqueue(MockResponse().setBody("""{"id":"agent-file","appProperties":{"captureId":"dashboard-agent-v1"}}"""))
                    server.enqueue(MockResponse().setBody(incoming))
                    server.enqueue(MockResponse().setBody("""{"id":"phone-file","appProperties":{"captureId":"dashboard-phone-v1"}}"""))
                    server.enqueue(MockResponse().setBody("{}"))
                    server.enqueue(MockResponse().setBody("""{"id":"instructions-file","appProperties":{"captureId":"dashboard-instructions-v1"}}"""))
                    assertFalse(DashboardSync.sync(context, "fake-token", "test-account", server.url("/").toString().trimEnd('/'), name))
                    check(store, server)
                }
            }
        } finally { listOf(name, "$name-journal", "$name-wal", "$name-shm").forEach { File(context.noBackupFilesDir, it).delete() } }
    }
    @Test fun agentFileIsReadOnlyAndPhoneEditsAreExported() = runSync(feed) { store, server ->
        assertEquals(5, server.requestCount)
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
        val guide = server.takeRequest()
        assertEquals("GET", guide.method)
        assertTrue(guide.path!!.contains("instructions-file"))
        assertEquals("instructions-file", store.snapshot().instructionsFileId)
    }
    @Test fun malformedAgentFileRetainsCachedBoardWhileStillExportingPhoneTasks() = runSync("{invalid}") { store, server ->
        assertEquals("Take an umbrella", store.snapshot().updates.single().body)
        assertTrue(store.snapshot().status.contains("invalid"))
        repeat(3) { server.takeRequest() }
        assertEquals("PATCH", server.takeRequest().method)
    }

    @Test fun instructionsCreationRetriesWithSameIdAndNeverOverwritesGuide() {
        val name = "guide-test-${java.util.UUID.randomUUID()}.db"
        try {
            DashboardStore(context, name).use { store ->
                store.setMeta("agentFile", "agent-file"); store.setMeta("phoneFile", "phone-file")
                MockWebServer().use { server ->
                    server.start()
                    val api = server.url("/").toString().trimEnd('/')
                    fun boardResponses() {
                        server.enqueue(MockResponse().setBody("""{"appProperties":{"captureId":"dashboard-agent-v1"}}"""))
                        server.enqueue(MockResponse().setBody(feed))
                        server.enqueue(MockResponse().setBody("""{"appProperties":{"captureId":"dashboard-phone-v1"}}"""))
                        server.enqueue(MockResponse().setBody("{}"))
                    }
                    boardResponses()
                    server.enqueue(MockResponse().setBody("""{"ids":["stable-guide"]}"""))
                    server.enqueue(MockResponse().setResponseCode(404))
                    server.enqueue(MockResponse().setResponseCode(503))
                    assertTrue(DashboardSync.sync(context, "fake", "owner", api, name))
                    assertEquals("stable-guide", store.meta("instructionsFile"))
                    assertEquals("", store.snapshot().instructionsFileId)
                    repeat(6) { server.takeRequest() }
                    val create = server.takeRequest()
                    assertEquals("POST", create.method)
                    val content = create.body.readUtf8()
                    assertTrue(content.contains(AgentSetup.INSTRUCTIONS_NAME))
                    assertTrue(content.contains("BEFORE") || content.contains("before every Dashboard"))
                    assertTrue(content.contains("stable-guide"))

                    // A lost response may have created a guide the user has already edited.
                    boardResponses()
                    server.enqueue(MockResponse().setBody("""{"appProperties":{"captureId":"dashboard-instructions-v1"}}"""))
                    assertFalse(DashboardSync.sync(context, "fake", "owner", api, name))
                    repeat(4) { server.takeRequest() }
                    val reconcile = server.takeRequest()
                    assertEquals("GET", reconcile.method)
                    assertTrue(reconcile.path!!.contains("stable-guide"))
                    assertEquals(12, server.requestCount)
                    val snapshot = store.snapshot()
                    assertEquals("stable-guide", snapshot.instructionsFileId)
                    val prompt = AgentSetup.prompt(snapshot)
                    assertTrue(prompt.contains("file ID stable-guide"))
                    assertTrue(prompt.contains("file ID agent-file"))
                    assertTrue(prompt.contains("file ID phone-file"))
                    assertTrue(prompt.contains("BEFORE EACH Dashboard"))
                }
            }
        } finally { listOf(name, "$name-journal", "$name-wal", "$name-shm").forEach { File(context.noBackupFilesDir, it).delete() } }
    }
}
