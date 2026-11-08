package de.mm20.launcher2.capture

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.mm20.launcher2.dashboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DashboardStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val incoming = """{"schemaVersion":1,"updates":[{"id":"brief","title":"Today","body":"Bring a jacket"}],"tasks":[{"id":"agent-1","title":"Prepare agenda","due":"Tomorrow","completed":false}]}"""
    private fun withStore(block: (String) -> Unit) {
        val name = "dashboard-test-${java.util.UUID.randomUUID()}.db"
        try { block(name) } finally {
            listOf(name, "$name-journal", "$name-wal", "$name-shm").forEach { File(context.noBackupFilesDir, it).delete() }
        }
    }
    @Test fun phoneCompletionAndAddedTaskSurviveIncomingRefreshAndReopen() = withStore { name ->
        var localId = ""
        DashboardStore(context, name).use { store ->
            store.acceptBoard(incoming)
            store.complete(store.snapshot().tasks.single(), true)
            localId = store.addTask("  Pick up groceries  ")
            store.acceptBoard(incoming.replace("Bring a jacket", "Rain expected"))
        }
        DashboardStore(context, name).use { store ->
            val board = store.snapshot()
            assertEquals("Rain expected", board.updates.single().body)
            assertTrue(board.tasks.single { it.id == "agent-1" }.completed)
            assertEquals("Pick up groceries", board.tasks.single { it.id == localId }.title)
            val exported = org.json.JSONObject(store.phoneState())
            assertEquals(2, exported.getJSONArray("tasks").length())
            assertEquals(2, exported.getLong("revision"))
        }
    }
    @Test fun malformedOrDuplicateBoardDoesNotReplaceLastValidCopy() = withStore { name ->
        DashboardStore(context, name).use { store ->
            store.acceptBoard(incoming)
            for (bad in listOf("{}", incoming.replace("\"schemaVersion\":1", "\"schemaVersion\":999"),
                """{"schemaVersion":1,"updates":[],"tasks":[{"id":"same","title":"A"},{"id":"same","title":"B"}]}""")) {
                try { store.acceptBoard(bad); fail("Invalid board accepted") } catch (_: Exception) { }
                assertEquals("Bring a jacket", store.snapshot().updates.single().body)
            }
        }
    }
    @Test fun uncheckIsAlsoADurablePhoneOverride() = withStore { name ->
        DashboardStore(context, name).use { store ->
            store.acceptBoard(incoming.replace("false", "true"))
            store.complete(store.snapshot().tasks.single(), false)
            store.acceptBoard(incoming.replace("false", "true"))
            assertFalse(store.snapshot().tasks.single().completed)
        }
    }
}
