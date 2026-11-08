package de.mm20.launcher2.capture

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class InboxStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun cleanup(id: String) { InboxStore(context).use {
        it.writableDatabase.delete("captures", "id=?", arrayOf(id)); it.file(id).delete()
    } }
    @Test fun offlineCaptureSurvivesReopenWithUnicodeAndStableIds() {
        val id = InboxStore(context).use { it.save("text", "text/plain", "Remember café ☕") }
        try {
            InboxStore(context).use { store ->
                val note = store.list().single { it.id == id }
                assertEquals("pending", note.state)
                assertEquals("Remember café ☕", store.file(id).readText())
                assertEquals("Remember café ☕", org.json.JSONObject(String(store.manifest(note))).getString("text"))
                store.update(id, "remote" to "stable-id", "manifest" to "manifest-id", "owner" to "account-a", "state" to "auth_required")
                store.retry()
            }
            InboxStore(context).use { store ->
                val note = store.list().single { it.id == id }
                assertEquals("pending", note.state); assertEquals("stable-id", note.remote)
                assertEquals("manifest-id", note.manifest); assertEquals("account-a", note.owner)
            }
        } finally { cleanup(id) }
    }
    @Test fun brokenImportNeverCreatesQueueEntry() {
        InboxStore(context).use { store ->
            val before = store.list().map { it.id }.toSet()
            try {
                store.save("image", "image/png", source = object : InputStream() {
                    override fun read(): Int = throw java.io.IOException("Provider failed")
                })
                fail("Import should fail")
            } catch (_: java.io.IOException) { }
            assertEquals(before, store.list().map { it.id }.toSet())
        }
    }
    @Test fun blankNotesDoNotCreateQueueEntries() {
        InboxStore(context).use { store ->
            val before = store.list().size
            try { store.save("text", "text/plain", "  "); fail("Blank note accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(before, store.list().size)
        }
    }
}
