package de.mm20.launcher2.updates

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseClientTest {
    private val current = requireNotNull(ReleaseVersion.parse("0.1.0-preview.3"))
    private fun release(tag: String, draft: Boolean = false, download: String = "${ReleaseClient.REPOSITORY}/releases/download/$tag/${ReleaseClient.APK}") =
        JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", tag.contains("preview"))
            .put("assets", JSONArray().put(JSONObject().put("name", ReleaseClient.APK).put("state", "uploaded")
                .put("size", 1234).put("digest", "sha256:" + "a".repeat(64)).put("browser_download_url", download)))

    @Test fun publishedPreviewsAreFoundWithoutGoogleCredentials() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(JSONArray().put(release("v0.1.0-preview.4"))
                .put(release("v0.1.0-preview.10")).put(release("v0.1.0-preview.11", draft = true)).toString()))
            val result = requireNotNull(ReleaseClient.check(current, server.url("/releases").toString()))
            assertEquals("v0.1.0-preview.10", result.tag)
            assertTrue(result.version > current)
            assertEquals("a".repeat(64), result.sha256)
            assertEquals(1234L, result.size)
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        }
    }
    @Test fun foreignOrIncompleteDownloadsCannotBeOffered() {
        val missing = release("v0.1.0-preview.90").put("assets", JSONArray())
        val raw = JSONArray().put(release("v0.1.0-preview.99", download = "https://evil.example/update.apk"))
            .put(missing).put(release("v0.1.0-preview.88", download = "${ReleaseClient.REPOSITORY}/releases/download/v0.1.0-preview.3/${ReleaseClient.APK}"))
            .put(release("v0.1.0-preview.2")).toString()
        val result = requireNotNull(ReleaseClient.parse(raw, current))
        assertEquals("v0.1.0-preview.2", result.tag)
        assertFalse(result.version > current)
    }
    @Test fun stableChannelDoesNotOfferPreviews() {
        val raw = JSONArray().put(release("v0.2.0-preview.1")).put(release("v0.1.0")).toString()
        assertEquals("v0.1.0", ReleaseClient.parse(raw, requireNotNull(ReleaseVersion.parse("0.1.0")))?.tag)
    }
    @Test fun summaryComesFromTheSelectedReleaseWithLegacyAndMissingNotesFallbacks() {
        val older = release("v0.1.0-preview.4").put("body", "What's new: Older change.")
        val newer = release("v0.1.0-preview.18").put("body", "What's new: Brief **patch notes** before installation.\n\nLong details.")
        assertEquals("Brief patch notes before installation.", ReleaseClient.parse(JSONArray().put(newer).put(older).toString(), current)?.summary)
        assertEquals("Legacy changes.", ReleaseClient.parse(JSONArray().put(newer.put("body", "Legacy changes.\n\nDetails")).toString(), current)?.summary)
        assertEquals(ReleaseNotes.UNAVAILABLE, ReleaseClient.parse(JSONArray().put(newer.put("body", JSONObject.NULL)).toString(), current)?.summary)
    }
    @Test fun rateLimitAndRedirectAreFailuresRatherThanUpToDate() {
        MockWebServer().use { server ->
            server.start()
            listOf(403, 429, 302).forEach { code ->
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", "https://evil.example/"))
                try {
                    ReleaseClient.check(current, server.url("/releases").toString())
                    fail("HTTP $code should fail")
                } catch (failure: ReleaseCheckFailure) { assertEquals(code, failure.status) }
            }
            assertEquals(3, server.requestCount)
        }
    }
}
