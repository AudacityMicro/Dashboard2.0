package de.mm20.launcher2.capture

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal class DriveFailure(val code: Int) : java.io.IOException("Drive request failed ($code)")
internal object DriveHttp {
    fun request(method: String, url: String, token: String,
        contentType: String? = null, write: ((java.io.OutputStream) -> Unit)? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 30000; connection.readTimeout = 60000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (write != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setChunkedStreamingMode(65536)
                connection.outputStream.use(write)
            }
            val code = connection.responseCode
            if (code !in 200..299) throw DriveFailure(code)
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { connection.disconnect() }
    }
    fun newId(token: String): String = JSONObject(request("GET",
        "https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files", token))
        .getJSONArray("ids").getString(0)

    /** A generated ID is committed locally before upload. Repeating create cannot duplicate a file. */
    fun upload(token: String, id: String, captureId: String, name: String, mime: String,
        file: File? = null, bytes: ByteArray? = null, api: String = "https://www.googleapis.com") {
        try {
            val existing = JSONObject(request("GET",
                "$api/drive/v3/files/$id?fields=id,appProperties,trashed", token))
            check(existing.getJSONObject("appProperties").getString("captureId") == captureId &&
                !existing.optBoolean("trashed")) { "Remote file conflict" }
            return
        } catch (e: DriveFailure) { if (e.code != 404) throw e }
        val boundary = UUID.randomUUID().toString()
        val metadata = JSONObject().put("id", id).put("name", name)
            .put("appProperties", JSONObject().put("captureId", captureId).put("source", "Dashboard2.0"))
        try {
            request("POST", "$api/upload/drive/v3/files?uploadType=multipart", token,
                "multipart/related; boundary=$boundary") { out ->
                out.write(("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                    "--$boundary\r\nContent-Type: $mime\r\n\r\n").toByteArray())
                if (file != null) file.inputStream().use { it.copyTo(out) } else out.write(requireNotNull(bytes))
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
        } catch (e: DriveFailure) {
            // Concurrent/replayed create: validate on the next attempt, never silently accept conflict.
            if (e.code == 409) throw java.io.IOException("Upload needs reconciliation") else throw e
        }
    }
}
