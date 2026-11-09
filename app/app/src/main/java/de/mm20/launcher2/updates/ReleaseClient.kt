package de.mm20.launcher2.updates

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

internal data class DashboardRelease(val tag: String, val version: ReleaseVersion, val download: String,
    val sha256: String = "", val size: Long = 0, val summary: String = ReleaseNotes.UNAVAILABLE)
internal class ReleaseCheckFailure(val status: Int) : java.io.IOException()

internal object ReleaseClient {
    const val REPOSITORY = "https://github.com/AudacityMicro/Dashboard2.0"
    const val ENDPOINT = "https://api.github.com/repos/AudacityMicro/Dashboard2.0/releases?per_page=30"
    const val APK = "Dashboard-2.0-preview.apk"

    fun parse(raw: String, current: ReleaseVersion): DashboardRelease? {
        val releases = JSONArray(raw)
        return (0 until releases.length()).mapNotNull { index ->
            val release = releases.optJSONObject(index) ?: return@mapNotNull null
            if (release.optBoolean("draft", true)) return@mapNotNull null
            val tag = release.optString("tag_name")
            val version = ReleaseVersion.parse(tag) ?: return@mapNotNull null
            // Stable installations stay on stable; previews can graduate to stable.
            if (current.preview == null && (version.preview != null || release.optBoolean("prerelease"))) return@mapNotNull null
            val expected = "$REPOSITORY/releases/download/$tag/$APK"
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            val found = (0 until assets.length()).mapNotNull { assetIndex ->
                val asset = assets.optJSONObject(assetIndex)
                asset?.takeIf { asset.optString("name") == APK && asset.optString("state") == "uploaded" &&
                    asset.optLong("size") > 0 && asset.optString("browser_download_url") == expected
                }
            }.firstOrNull()
            if (found != null) DashboardRelease(tag, version, expected,
                found.optString("digest").removePrefix("sha256:"), found.optLong("size"),
                ReleaseNotes.summary(release.opt("body") as? String ?: "")) else null
        }.maxByOrNull { it.version }
    }

    fun check(current: ReleaseVersion, endpoint: String = ENDPOINT): DashboardRelease? {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Dashboard2.0-update-check")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val status = connection.responseCode
            if (status != 200) throw ReleaseCheckFailure(status)
            val raw = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(out.size() + count <= 2 * 1024 * 1024) { "Release response too large" }
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
            return parse(raw, current)
        } finally { connection.disconnect() }
    }
}
