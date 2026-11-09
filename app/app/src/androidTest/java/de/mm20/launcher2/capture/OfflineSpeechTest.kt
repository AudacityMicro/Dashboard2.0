package de.mm20.launcher2.capture

import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OfflineSpeechTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun bundledEngineDecodesKnownSpeechWithoutNetworkOrMicrophone() {
        val file = File(context.cacheDir, "speech-test.wav")
        try {
            instrumentation.context.assets.open("vosk-test.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            val text = OfflineTranscriber.transcribe(context, file)
            assertTrue("Expected spoken digits, got: $text", text.contains("one zero zero zero one"))
            assertTrue(text.contains("zero one eight zero three"))
        } finally { file.delete() }
    }

    @Test fun migrationPreservesOldPayloadAndNewVoiceWaitsForTranscript() {
        val root = File(context.cacheDir, "migration-${java.util.UUID.randomUUID()}").also { it.mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = root
            override fun sendBroadcast(intent: Intent) = Unit
        }
        try {
            val database = File(root, "capture/inbox.db").also { it.parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
                db.execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY, kind TEXT NOT NULL, mime TEXT NOT NULL, created INTEGER NOT NULL, text TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', remote TEXT NOT NULL DEFAULT '', manifest TEXT NOT NULL DEFAULT '', owner TEXT NOT NULL DEFAULT '')")
                db.execSQL("INSERT INTO captures(id,kind,mime,created,text) VALUES('old','text','text/plain',1,'keep me')")
                db.version = 1
            }
            File(root, "capture/old.payload").writeText("keep me")
            InboxStore(isolated).use { store ->
                val old = store.list().single()
                assertEquals("none", old.transcription); assertTrue(old.readyForSync)
                assertEquals("keep me", store.file(old.id).readText())
                val id = store.save("voice", "audio/mp4", source = "original audio".byteInputStream(), transcribe = true)
                val voice = store.list().single { it.id == id }
                assertFalse(voice.readyForSync)
                store.update(id, "text" to "remember lunch", "transcription" to "ready")
                val ready = store.list().single { it.id == id }
                assertTrue(ready.readyForSync)
                val json = org.json.JSONObject(String(store.manifest(ready)))
                assertEquals("remember lunch", json.getString("text")); assertEquals("ready", json.getString("transcriptionStatus"))
                assertEquals("original audio", store.file(id).readText())
                store.update(id, "transcription" to "failed")
                assertTrue(store.list().single { it.id == id }.readyForSync)
                val upload = store.save("file", "application/pdf", source = "document".byteInputStream(), name = "../budget\n.pdf")
                val document = store.list().single { it.id == upload }
                assertEquals("budget.pdf", document.name)
                assertEquals("budget.pdf", org.json.JSONObject(String(store.manifest(document))).getString("originalName"))
            }
        } finally { root.deleteRecursively() }
    }
}
