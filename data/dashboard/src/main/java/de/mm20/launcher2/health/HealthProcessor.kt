package de.mm20.launcher2.health

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Health requests are data, not instructions. Only explicitly enabled nutrition/goal writes are accepted. */
object HealthProcessor {
    suspend fun process(store: HealthStore, owner: String, commands: HealthCommands, gateway: HealthGateway, canRead: Boolean): JSONObject {
        require(owner.isNotBlank())
        val pinned = store.meta("owner")
        check(pinned.isEmpty() || pinned == owner) { "Reconnect the original Drive account." }
        if (pinned.isEmpty()) store.set("owner", owner)
        val grants = gateway.permissions()
        val sharing = store.flag("share")
        val control = store.flag("control")
        if (sharing) store.retainReads(commands.reads.map { it.id }.toSet()) else store.clearReads()
        val goalAck = JSONObject()
        commands.goal?.let { command ->
            val value = JSONObject().put("dailyFoodKcal", command.goals.foodKcal ?: JSONObject.NULL)
                .put("creditActiveCalories", command.goals.creditActive)
            val digest = HealthCommands.digest(value)
            val previous = store.meta("goalRevision").toLongOrNull() ?: 0
            when {
                !control -> goalAck.put("status", "agent_control_disabled")
                command.revision < previous -> goalAck.put("status", "stale_revision")
                command.revision == previous && store.meta("goalDigest") != digest -> goalAck.put("status", "revision_conflict")
                command.revision == previous -> goalAck.put("status", "applied")
                else -> {
                    store.writableDatabase.beginTransaction()
                    try {
                        store.goals(command.goals)
                        store.set("goalRevision", command.revision.toString()); store.set("goalDigest", digest)
                        store.writableDatabase.setTransactionSuccessful()
                    } finally { store.writableDatabase.endTransaction() }
                    goalAck.put("status", "applied")
                }
            }
            goalAck.put("revision", command.revision)
        }
        val foods = JSONArray()
        for (entry in commands.foods) {
            val ack = JSONObject().put("id", entry.id).put("revision", entry.revision)
            val digest = HealthCommands.digest(entry.json())
            val old = store.food(entry.id)
            when {
                !store.flag("control") -> ack.put("status", "agent_control_disabled")
                old != null && entry.revision < old.getLong("revision") -> ack.put("status", "stale_revision")
                old != null && entry.revision == old.getLong("revision") -> {
                    if (digest != old.getString("digest")) ack.put("status", "revision_conflict")
                    else ack.put("status", "logged").put("recordId", old.getString("recordId"))
                }
                HealthDevice.nutritionWrite !in grants -> ack.put("status", "nutrition_write_permission_required")
                else -> try {
                    val account = java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
                        .take(8).joinToString("") { "%02x".format(it) }
                    val record = gateway.food(entry, "dashboard-food-$account-${entry.id}")
                    store.food(entry.id, entry.revision, digest, record)
                    ack.put("status", "logged").put("recordId", record).put("changed", true)
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
                } catch (_: SecurityException) { ack.put("status", "nutrition_write_permission_required")
                } catch (_: Exception) { ack.put("status", "retry").put("message", "Food was not confirmed. Check permissions and data values; retry with the same ID and revision.") }
            }
            foods.put(ack)
        }
        val reads = JSONArray()
        for (query in commands.reads) {
            val result = JSONObject().put("id", query.id).put("type", query.type)
            when {
                !sharing -> result.put("status", "health_sharing_disabled")
                query.type !in gateway.availableTypes() -> result.put("status", "unsupported_type")
                HealthCatalog.permission(query.type) !in grants -> result.put("status", "read_permission_required")
                !canRead -> result.put("status", "pending_foreground").put("message", "Open the launcher or grant background health reads.")
                else -> try {
                    val digest = HealthCommands.digest(query.json())
                    val cached = store.read(query.id, digest, grants)
                    val response = cached ?: gateway.read(query).also {
                        store.read(query.id, digest, HealthCatalog.permission(query.type), it)
                    }
                    if ((reads.toString().length + response.toString().length) > 1_500_000) {
                        result.put("status", "response_too_large").put("message", "Use fewer queries, a smaller pageSize or a shorter time range.")
                    } else { reads.put(response); continue }
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
                } catch (_: SecurityException) { result.put("status", "read_permission_required")
                } catch (_: IllegalArgumentException) { result.put("status", "invalid_or_large_query").put("message", "Use a fresh ID for each query/page and reduce pageSize or the time range.")
                } catch (_: Exception) { result.put("status", "retry").put("message", "Health query could not be completed.") }
            }
            reads.put(result)
        }
        val goals = store.goals()
        return JSONObject().put("schemaVersion", 1).put("updatedAt", Instant.now().toString()).put("enabled", store.flag("enabled"))
            .put("sharing", sharing).put("agentControl", control)
            .put("goal", JSONObject().put("dailyFoodKcal", goals.foodKcal ?: JSONObject.NULL).put("creditActiveCalories", goals.creditActive)
                .put("revision", store.meta("goalRevision").toLongOrNull() ?: 0))
            .put("goalResult", goalAck).put("foodResults", foods).put("readResults", reads)
            .apply {
                if (sharing) {
                    put("availableTypes", JSONArray(gateway.availableTypes().filter { HealthCatalog.permission(it) in grants }.sorted()))
                    put("permissions", JSONArray(grants.sorted()))
                    put("canReadNow", canRead)
                    val snapshot = store.snapshot()
                    put("today", snapshot.json().put("remainingKcal", snapshot.remaining(goals) ?: JSONObject.NULL))
                }
            }
    }
}
