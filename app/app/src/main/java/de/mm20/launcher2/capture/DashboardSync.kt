package de.mm20.launcher2.capture

import android.content.Context
import de.mm20.launcher2.dashboard.DashboardStore
import de.mm20.launcher2.dashboard.AgentSetup

/** The agent owns the incoming board; only this device writes the phone-state file. */
internal object DashboardSync {
    fun sync(context: Context, token: String, subject: String, api: String = "https://www.googleapis.com", databaseName: String = "dashboard.db"): Boolean {
        DashboardStore(context, databaseName).use { store ->
            if (!SyncPolicy.sameOwner(store.meta("owner"), subject)) {
                store.setMeta("status", "Reconnect the original Google account to update the board.")
                return false
            }
            store.setMeta("owner", subject)
            try {
                fun remoteId(key: String): String = store.meta(key).ifEmpty {
                    DriveHttp.newId(token, api).also { store.setMeta(key, it) }
                }
                val boardId = remoteId("agentFile")
                val phoneId = remoteId("phoneFile")
                DriveHttp.upload(token, boardId, "dashboard-agent-v1", "Dashboard-agent.json", "application/json",
                    bytes = DashboardStore.EMPTY_BOARD.toByteArray(Charsets.UTF_8), api = api)
                var invalidBoard = false
                try {
                    val incoming = DriveHttp.request("GET", "$api/drive/v3/files/$boardId?alt=media", token,
                        maxBytes = 512 * 1024)
                    store.acceptBoard(incoming)
                } catch (_: IllegalArgumentException) { invalidBoard = true
                } catch (_: org.json.JSONException) { invalidBoard = true }
                val phone = store.phoneState().toByteArray(Charsets.UTF_8)
                DriveHttp.upload(token, phoneId, "dashboard-phone-v1", "Dashboard-phone-tasks.json", "application/json", bytes = phone, api = api)
                DriveHttp.request("PATCH", "$api/upload/drive/v3/files/$phoneId?uploadType=media", token,
                    "application/json") { it.write(phone) }
                // Create once using a durable ID. Reconciliation validates identity without
                // overwriting guidance the user has subsequently edited in Drive.
                val instructionsId = remoteId("instructionsFile")
                DriveHttp.upload(token, instructionsId, "dashboard-instructions-v1", AgentSetup.INSTRUCTIONS_NAME,
                    "text/markdown", bytes = AgentSetup.instructions.toByteArray(Charsets.UTF_8), api = api)
                store.setMeta("instructionsReady", "true")
                store.setMeta("lastSync", System.currentTimeMillis().toString())
                store.setMeta("status", if (invalidBoard) "Agent file is invalid; showing your saved board. Phone task changes synced."
                    else "Drive connected. Task changes are saved on this phone first.")
                return false
            } catch (e: DriveFailure) {
                store.setMeta("status", if (e.code == 401) "Reconnect Google Drive in Capture inbox." else
                    "Drive board unavailable (${e.code}). Showing your saved board.")
                return SyncPolicy.stateForHttp(e.code) == "retry"
            } catch (_: java.io.IOException) {
                store.setMeta("status", "Waiting for network. Showing your saved board.")
                return true
            } catch (_: IllegalArgumentException) {
                store.setMeta("status", "Agent file has an invalid format. Showing your last valid board.")
                return false
            } catch (_: org.json.JSONException) {
                store.setMeta("status", "Agent file has an invalid format. Showing your last valid board.")
                return false
            } catch (_: IllegalStateException) {
                store.setMeta("status", "Drive file conflict. Showing your saved board; check the connected files.")
                return false
            }
        }
    }
}

class DashboardSyncReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) { SyncQueue.enqueue(context) }
}
