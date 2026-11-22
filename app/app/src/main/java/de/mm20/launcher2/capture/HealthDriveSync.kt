package de.mm20.launcher2.capture

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import de.mm20.launcher2.dashboard.DashboardStore
import de.mm20.launcher2.health.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Separate agent/phone files keep health requests out of the dashboard's presentation data. */
internal object HealthDriveSync {
    suspend fun sync(context: Context, token: String, subject: String, api: String = "https://www.googleapis.com",
        gatewayOverride: HealthGateway? = null): Boolean {
        if (!HealthStore.exists(context)) return false
        HealthStore(context).use { store ->
            if (!store.flag("enabled") && store.meta("agentFile").isBlank()) return false
            if (!store.flag("share") && !store.flag("control") && store.meta("agentFile").isBlank()) return false
            if (!SyncPolicy.sameOwner(store.meta("owner"), subject)) { store.set("status", "Reconnect the original Drive account for health sync."); return false }
            store.set("owner", subject)
            try {
                fun id(key: String) = store.meta(key).ifEmpty { DriveHttp.newId(token, api).also { store.set(key, it) } }
                val agentId = id("agentFile"); val phoneId = id("phoneFile")
                val empty = """{"schemaVersion":1,"foods":[],"reads":[]}""".toByteArray(Charsets.UTF_8)
                DriveHttp.upload(token, agentId, "dashboard-health-agent-v1", "Dashboard-health-agent.json", "application/json", bytes = empty, api = api)
                DriveHttp.upload(token, phoneId, "dashboard-health-phone-v1", "Dashboard-health-phone.json", "application/json", bytes = empty, api = api)
                DashboardStore(context).use { it.setMeta("healthAgentFile", agentId); it.setMeta("healthPhoneFile", phoneId) }
                val result = if (!store.flag("enabled")) {
                    store.clearReads()
                    JSONObject().put("schemaVersion", 1).put("enabled", false).put("sharing", false).put("status", "Health integration is disconnected.")
                } else {
                    val incoming = DriveHttp.request("GET", "$api/drive/v3/files/$agentId?alt=media", token, maxBytes = 512 * 1024)
                    val commands = try { HealthCommands.parse(incoming) } catch (_: Exception) { null }
                    if (commands == null) JSONObject().put("schemaVersion", 1).put("status", "Invalid health request file. No health commands were applied.")
                    else {
                        val gateway = gatewayOverride ?: if (HealthDevice.status(context) == HealthConnectClient.SDK_AVAILABLE) HealthDevice(context) else UnavailableHealth
                        val granted = gateway.permissions()
                        val canRead = gatewayOverride != null || HealthVisibility.startedActivities > 0 ||
                            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
                        HealthRepository.refresh(context, gateway = gatewayOverride)
                        val processed = HealthRepository.mutex.withLock { HealthProcessor.process(store, subject, commands, gateway, canRead) }
                        val foodResults = processed.optJSONArray("foodResults") ?: JSONArray()
                        if (canRead && (0 until foodResults.length()).any { foodResults.getJSONObject(it).optBoolean("changed") })
                            HealthRepository.refresh(context, force = true, gateway = gatewayOverride)
                        if (store.flag("share") && gateway.permissions() == granted) {
                            val goals = store.goals(); val snapshot = store.snapshot()
                            processed.put("today", snapshot.json().put("remainingKcal", snapshot.remaining(goals) ?: JSONObject.NULL))
                        } else {
                            // A revocation or sharing toggle wins over an in-flight query result.
                            processed.remove("today"); processed.remove("availableTypes"); processed.remove("permissions")
                            processed.put("readResults", JSONArray()).put("sharing", false)
                            store.clearReads()
                        }
                        processed
                    }
                }.put("updatedAt", Instant.now().toString())
                var bytes = result.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > 2 * 1024 * 1024) {
                    val previous = result.optJSONArray("readResults") ?: JSONArray()
                    result.put("readResults", JSONArray((0 until previous.length()).map { index ->
                        val query = previous.getJSONObject(index)
                        JSONObject().put("id", query.optString("id")).put("type", query.optString("type"))
                            .put("status", "response_too_large").put("message", "Use fewer queries or smaller pages and fresh query IDs.")
                    }))
                    bytes = result.toString().toByteArray(Charsets.UTF_8)
                }
                require(bytes.size <= 2 * 1024 * 1024)
                DriveHttp.request("PATCH", "$api/upload/drive/v3/files/$phoneId?uploadType=media", token, "application/json") { it.write(bytes) }
                store.set("status", ""); store.set("lastSync", Instant.now().toString())
                return false
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
            } catch (failure: DriveFailure) {
                if (failure.code == 401) throw failure
                store.set("status", "Health Drive sync is waiting. Check the connected account and network.")
                return SyncPolicy.stateForHttp(failure.code) == "retry"
            } catch (_: java.io.IOException) { store.set("status", "Health sync is waiting for a connection."); return true
            } catch (_: Exception) { store.set("status", "Health sync could not finish. Check health permissions and the agent file."); return false }
        }
    }
    private object UnavailableHealth : HealthGateway {
        override suspend fun permissions() = emptySet<String>()
        override fun availableTypes() = emptySet<String>()
        override suspend fun totals(now: Instant) = HealthSnapshot(status = "Health Connect is unavailable.")
        override suspend fun food(entry: FoodEntry, clientId: String): String = error("Health Connect unavailable")
        override suspend fun read(query: HealthRead): JSONObject = error("Health Connect unavailable")
    }
}
