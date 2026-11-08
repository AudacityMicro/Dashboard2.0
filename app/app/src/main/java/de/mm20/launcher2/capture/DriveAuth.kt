package de.mm20.launcher2.capture

import android.content.Context
import android.util.AtomicFile
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.File

/** Play Services retains refresh credentials in its managed account storage.
 * Access tokens are short-lived, held only in memory, never written to preferences/DB/logs.
 * The only persisted auth state is consent-enabled + a non-secret Google subject identifier.
 */
object DriveAuth {
    fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(
        Scope("https://www.googleapis.com/auth/drive.file"), Scope("openid"), Scope("email")
    )).build()
    @Synchronized fun state(context: Context): JSONObject = try {
        JSONObject(AtomicFile(File(context.noBackupFilesDir, "drive-state.json")).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    } catch (_: java.io.FileNotFoundException) { JSONObject() }
    @Synchronized fun set(context: Context, enabled: Boolean, subject: String = state(context).optString("subject")) {
        val file = AtomicFile(File(context.noBackupFilesDir, "drive-state.json"))
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("enabled", enabled).put("subject", subject).toString().toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun token(context: Context): String? {
        val result = try {
            Tasks.await(Identity.getAuthorizationClient(context).authorize(request()), 60, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        }
        return if (result.hasResolution()) null else result.accessToken
    }
    fun subject(token: String): String = JSONObject(DriveHttp.request(
        "GET", "https://www.googleapis.com/oauth2/v3/userinfo", token
    )).getString("sub")
}
