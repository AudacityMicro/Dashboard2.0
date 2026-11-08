package de.mm20.launcher2.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveUploadTest {
    @Test fun responseLossAfterCreateIsReconciledWithoutSecondUpload() {
        MockWebServer().use { server ->
            server.start()
            val api = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            try {
                DriveHttp.upload("fake-token", "stable-id", "capture-123", "note.txt", "text/plain",
                    bytes = "offline text".toByteArray(), api = api)
                fail("Upload response should be lost")
            } catch (_: java.io.IOException) { }
            server.enqueue(MockResponse().setBody("""{"id":"stable-id","appProperties":{"captureId":"capture-123"},"trashed":false}"""))
            DriveHttp.upload("fake-token", "stable-id", "capture-123", "note.txt", "text/plain",
                bytes = "offline text".toByteArray(), api = api)
            assertEquals(3, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
            val upload = server.takeRequest()
            assertEquals("POST", upload.method)
            val body = upload.body.readUtf8()
            assertTrue(body.contains("stable-id")); assertTrue(body.contains("offline text"))
            val reconcile = server.takeRequest()
            assertEquals("GET", reconcile.method)
            assertTrue(reconcile.path!!.contains("stable-id"))
        }
    }
    @Test fun existingFileWithWrongCaptureIdCannotBeAccepted() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"id":"stable-id","appProperties":{"captureId":"other"},"trashed":false}"""))
            try {
                DriveHttp.upload("fake-token", "stable-id", "expected", "note.txt", "text/plain",
                    bytes = "local".toByteArray(), api = server.url("/").toString().trimEnd('/'))
                fail("Conflicting remote ID was accepted")
            } catch (_: IllegalStateException) { }
            assertEquals(1, server.requestCount)
        }
    }
}
