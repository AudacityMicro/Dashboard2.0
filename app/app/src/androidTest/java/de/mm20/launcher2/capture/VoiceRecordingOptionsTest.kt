package de.mm20.launcher2.capture

import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class VoiceRecordingOptionsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(test: (InboxStore) -> Unit) {
        val root = File(context.cacheDir, "voice-options-${UUID.randomUUID()}").also { it.mkdirs() }
        val local = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = root
            override fun sendBroadcast(intent: Intent) = Unit
        }
        try { InboxStore(local).use(test) } finally { root.deleteRecursively() }
    }
    private val original = byteArrayOf(0, 1, 2, 10, 13, 127, -1, -2)

    @Test fun audioOnlySkipsTranscriptionAndSuppressesAnyTranscriptInManifest() = isolated { store ->
        val id = store.saveVoice("audio/mp4", original.inputStream(), VoiceRecordingMode.Audio)
        val capture = store.list().single()
        assertEquals("none", capture.transcription); assertTrue(capture.readyForSync)
        assertArrayEquals(original, store.file(id).readBytes())
        val manifest = JSONObject(String(store.manifest(capture.copy(text = "must not upload"))))
        assertEquals("", manifest.getString("text"))
        assertEquals("", manifest.getString("transcriptFileId"))
    }

    @Test fun transcriptOnlyNeverBecomesUploadableWithoutSuccessfulTextAndRetryIsDurable() = isolated { store ->
        val id = store.saveVoice("audio/mp4", original.inputStream(), VoiceRecordingMode.Transcript)
        assertFalse(store.list().single().readyForSync)
        for (failure in listOf("failed", "no_speech")) {
            store.update(id, "transcription" to failure, "state" to "failed")
            assertFalse(store.list().single().readyForSync)
            store.retry()
            assertEquals("pending", store.list().single().transcription)
            assertFalse(store.list().single().readyForSync)
        }
        store.update(id, "text" to "Lunch at noon", "transcription" to "ready")
        assertTrue(store.list().single().readyForSync)
        assertArrayEquals(original, store.file(id).readBytes())
    }

    @Test fun queuedModeDoesNotChangeWhenPreferenceChanges() = isolated { store ->
        val before = VoiceRecordingSettings.get(context)
        try {
            assertTrue(VoiceRecordingSettings.set(context, VoiceRecordingMode.Audio))
            store.saveVoice("audio/mp4", original.inputStream(), VoiceRecordingSettings.get(context))
            assertTrue(VoiceRecordingSettings.set(context, VoiceRecordingMode.Transcript))
            assertEquals("audio", store.list().single().voiceMode)
            assertEquals("none", store.list().single().transcription)
        } finally { VoiceRecordingSettings.set(context, before) }
    }

    private fun uploads(mode: VoiceRecordingMode) = isolated { store ->
        val id = store.saveVoice("audio/mp4", original.inputStream(), mode)
        store.update(id, "remote" to "payload", "manifest" to "manifest", "transcript_remote" to "transcript")
        if (mode.transcribe) store.update(id, "text" to "Lunch at noon", "transcription" to "ready")
        val capture = store.list().single()
        MockWebServer().use { server ->
            server.start()
            val count = if (mode == VoiceRecordingMode.Both) 3 else 2
            repeat(count) {
                server.enqueue(MockResponse().setResponseCode(404))
                server.enqueue(MockResponse().setBody("{}"))
            }
            val api = server.url("/").toString().trimEnd('/')
            CaptureDriveUpload.upload(store, capture, "fake-token", api)
            val bodies = (0 until count).map {
                assertEquals("GET", server.takeRequest().method)
                val post = server.takeRequest(); assertEquals("POST", post.method)
                post.body.readByteArray()
            }
            val first = bodies.first()
            if (mode == VoiceRecordingMode.Transcript) {
                assertTrue(String(first).contains("Lunch at noon"))
                assertTrue(String(first).contains("text/plain"))
                assertFalse(String(first).contains("audio/mp4"))
            } else {
                assertTrue("Original audio bytes must be unchanged", first.asList().windowed(original.size).any { it == original.asList() })
                assertTrue(String(first).contains("audio/mp4"))
            }
            val manifestBody = String(bodies.last())
            if (mode == VoiceRecordingMode.Audio) assertFalse(manifestBody.contains("Lunch at noon"))
            if (mode == VoiceRecordingMode.Both) assertTrue(String(bodies[1]).contains("Lunch at noon"))
            assertEquals("synced", store.list().single().state)
            assertArrayEquals(original, store.file(id).readBytes())
            // Reconcile a response-loss retry using the persisted IDs: no duplicate POSTs.
            store.update(id, "state" to "pending")
            val ids = if (mode == VoiceRecordingMode.Both) listOf("payload", "transcript", "manifest") else listOf("payload", "manifest")
            ids.forEach { remote -> server.enqueue(MockResponse().setBody("""{"id":"$remote","appProperties":{"captureId":"$id"},"trashed":false}""")) }
            CaptureDriveUpload.upload(store, store.list().single(), "fake-token", api)
            repeat(count) { assertEquals("GET", server.takeRequest().method) }
            assertEquals(count * 3, server.requestCount)
        }
    }
    @Test fun audioOnlyUploadsOriginalAndManifestWithoutText() = uploads(VoiceRecordingMode.Audio)
    @Test fun transcriptOnlyUploadsTextAndManifestWithoutAudio() = uploads(VoiceRecordingMode.Transcript)
    @Test fun bothUploadsOriginalAndSeparateTranscriptWithRetrySafeIds() = uploads(VoiceRecordingMode.Both)

    @Test fun versionTwoMigrationKeepsOriginalAudioAndPriorBehavior() {
        val root = File(context.cacheDir, "voice-migration-${UUID.randomUUID()}").also { it.mkdirs() }
        val local = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = root
            override fun sendBroadcast(intent: Intent) = Unit
        }
        try {
            val file = File(root, "capture/inbox.db").also { it.parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE captures(id TEXT PRIMARY KEY,kind TEXT NOT NULL,mime TEXT NOT NULL,created INTEGER NOT NULL,text TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'pending',remote TEXT NOT NULL DEFAULT '',manifest TEXT NOT NULL DEFAULT '',owner TEXT NOT NULL DEFAULT '',transcription TEXT NOT NULL DEFAULT 'none',name TEXT NOT NULL DEFAULT '')")
                db.execSQL("INSERT INTO captures(id,kind,mime,created,text,transcription) VALUES('old','voice','audio/mp4',1,'remember','ready')")
                db.version = 2
            }
            File(root, "capture/old.payload").writeBytes(original)
            InboxStore(local).use { store ->
                val capture = store.list().single()
                assertEquals("both", capture.voiceMode); assertEquals("", capture.transcriptRemote)
                assertTrue(capture.readyForSync); assertArrayEquals(original, store.file("old").readBytes())
            }
        } finally { root.deleteRecursively() }
    }
}
