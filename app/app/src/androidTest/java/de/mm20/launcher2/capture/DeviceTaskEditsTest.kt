package de.mm20.launcher2.capture

import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.dashboard.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DeviceTaskEditsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun store(test: (DashboardStore) -> Unit) {
        val name = "device-edits-${UUID.randomUUID()}.db"
        try { DashboardStore(context, name).use(test) }
        finally { listOf(name, "$name-journal", "$name-wal", "$name-shm").forEach { File(context.noBackupFilesDir, it).delete() } }
    }
    private fun board(id: String, completed: Boolean, ack: JSONObject? = null) = JSONObject().put("schemaVersion", 1).put("updates", JSONArray())
        .put("tasks", JSONArray().put(JSONObject().put("id", id).put("title", "Shared task").put("completed", completed)))
        .apply { if (ack != null) put("acknowledgedTaskEdits", JSONArray().put(ack)) }.toString()
    private fun ack(state: JSONObject, revision: Long) = JSONObject().put("deviceId", state.getString("deviceId"))
        .put("taskId", state.getJSONArray("taskEdits").getJSONObject(0).getString("id")).put("revision", revision)

    @Test fun exactAckAllowsWindowsDecisionWithoutUndoingNewerPhoneEdits() = store { s ->
        s.acceptBoard(board("shared", false)); s.complete(s.snapshot().tasks.single(), true)
        val first = JSONObject(s.phoneState()); val old = first.getJSONArray("taskEdits").getJSONObject(0).getLong("revision")
        s.complete(s.snapshot().tasks.single(), false)
        s.acceptBoard(board("shared", true, ack(first, old)))
        assertFalse(s.snapshot().tasks.single().completed)
        val current = JSONObject(s.phoneState()); val revision = current.getJSONArray("taskEdits").getJSONObject(0).getLong("revision")
        s.acceptBoard(board("shared", true, ack(current, revision)))
        assertTrue(s.snapshot().tasks.single().completed)
        assertEquals(0, JSONObject(s.phoneState()).getJSONArray("taskEdits").length())
    }
    @Test fun acknowledgedLocalTaskIsPromotedWithoutDuplicates() = store { s ->
        val id = s.addTask("Local task"); val state = JSONObject(s.phoneState())
        val revision = state.getJSONArray("taskEdits").getJSONObject(0).getLong("revision")
        s.acceptBoard(board(id, true, ack(state, revision)))
        assertEquals(1, s.snapshot().tasks.size); assertFalse(s.snapshot().tasks.single().local); assertTrue(s.snapshot().tasks.single().completed)
    }
    @Test fun foreignDeviceOrMissingRemoteTaskDoesNotClearPendingEdit() = store { s ->
        val id = s.addTask("Keep"); val state = JSONObject(s.phoneState()); val revision = state.getJSONArray("taskEdits").getJSONObject(0).getLong("revision")
        s.acceptBoard(board(id, true, ack(state, revision).put("deviceId", "someone-else")))
        assertFalse(s.snapshot().tasks.single().completed)
        s.acceptBoard(board("other", false, ack(state, revision)))
        assertTrue(s.snapshot().tasks.any { it.id == id && it.local })
    }
    @Test fun malformedReceiptRejectsEntireBoardBeforeClearingState() = store { s ->
        s.acceptBoard(board("shared", false)); s.complete(s.snapshot().tasks.single(), true)
        val state = JSONObject(s.phoneState()); val receipt = ack(state, 1).put("revision", 1.5)
        try { s.acceptBoard(board("shared", false, receipt)); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(s.snapshot().tasks.single().completed)
    }
    @Test fun legacyCompletionIsExportedOnceWithoutInventingAnEditTime() = store { s ->
        s.acceptBoard(board("shared", false))
        s.writableDatabase.execSQL("INSERT INTO completions(id,completed) VALUES('shared',1)")
        val first = JSONObject(s.phoneState()); val second = JSONObject(s.phoneState())
        assertEquals(first.getLong("revision"), second.getLong("revision"))
        val edit = first.getJSONArray("taskEdits").getJSONObject(0)
        assertTrue(edit.getBoolean("legacy")); assertTrue(edit.isNull("changedAt"))
    }
}
