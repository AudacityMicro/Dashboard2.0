package de.mm20.launcher2.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.kilocalories
import androidx.health.connect.client.units.grams
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

interface HealthGateway {
    suspend fun permissions(): Set<String>
    fun availableTypes(): Set<String>
    suspend fun totals(now: Instant = Instant.now()): HealthSnapshot
    suspend fun food(entry: FoodEntry, clientId: String): String
    suspend fun read(query: HealthRead): JSONObject
}

class HealthDevice(context: Context) : HealthGateway {
    val client = HealthConnectClient.getOrCreate(context.applicationContext)
    override suspend fun permissions() = client.permissionController.getGrantedPermissions()
    override fun availableTypes() = HealthCatalog.available(client).keys
    override suspend fun totals(now: Instant): HealthSnapshot {
        val granted = permissions()
        val metrics = buildSet {
            if (HealthCatalog.permission("NutritionRecord") in granted) add(NutritionRecord.ENERGY_TOTAL)
            if (HealthCatalog.permission("ActiveCaloriesBurnedRecord") in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (HealthCatalog.permission("TotalCaloriesBurnedRecord") in granted) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        }
        val zone = ZoneId.systemDefault()
        val date = now.atZone(zone).toLocalDate()
        if (metrics.isEmpty()) return HealthSnapshot(date.toString(), status = "Grant calorie read permissions in Health Connect.", permissions = granted)
        val result = client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(date.atStartOfDay(zone).toInstant(), now)))
        val consumed = if (HealthCatalog.permission("NutritionRecord") in granted) result[NutritionRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0 else null
        return HealthSnapshot(date.toString(), consumed, result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories, System.currentTimeMillis(), "", granted)
    }
    override suspend fun food(entry: FoodEntry, clientId: String): String {
        val start = entry.at.toInstant()
        val record = NutritionRecord(startTime = start, endTime = start.plusSeconds(1), startZoneOffset = entry.at.offset,
            endZoneOffset = entry.at.offset, metadata = Metadata.manualEntry(clientRecordId = clientId, clientRecordVersion = entry.revision),
            name = entry.name + if (entry.estimated) " (estimated)" else "", energy = entry.kcal.kilocalories,
            protein = entry.nutrients["proteinGrams"]?.grams, totalCarbohydrate = entry.nutrients["carbsGrams"]?.grams,
            totalFat = entry.nutrients["fatGrams"]?.grams, dietaryFiber = entry.nutrients["fiberGrams"]?.grams,
            sugar = entry.nutrients["sugarGrams"]?.grams, sodium = entry.nutrients["sodiumGrams"]?.grams)
        return requireNotNull(client.insertRecords(listOf(record)).recordIdsList.firstOrNull()) { "Food write was not acknowledged." }
    }
    @Suppress("UNCHECKED_CAST")
    override suspend fun read(query: HealthRead): JSONObject {
        val type = requireNotNull(HealthCatalog.available(client)[query.type]) { "This health data type isn't supported on this phone." }
        val page = client.readRecords(ReadRecordsRequest(type as KClass<Record>, TimeRangeFilter.between(query.start, query.end),
            pageSize = query.pageSize, pageToken = query.pageToken))
        val result = JSONObject().put("id", query.id).put("type", query.type).put("status", "complete")
            .put("readAt", Instant.now().toString()).put("start", query.start.toString()).put("end", query.end.toString())
            .put("records", JSONArray(page.records.map { HealthRecordJson.value(it) }))
            .put("nextPageToken", page.pageToken ?: JSONObject.NULL)
        require(result.toString().toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Response too large; request a smaller page or time window." }
        return result
    }
    fun supports(feature: Int) = client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    companion object {
        fun status(context: Context) = HealthConnectClient.getSdkStatus(context)
        val nutritionWrite = HealthPermission.getWritePermission(NutritionRecord::class)
    }
}

/** Typed SDK getter values and their named units; no HTML, code or object methods are executed from Drive. */
object HealthRecordJson {
    fun value(value: Any?, depth: Int = 0): Any {
        require(depth <= 12) { "Health record nesting is too deep." }
        return when (value) {
            null -> JSONObject.NULL
            is String, is Boolean, is Number -> value
            is java.time.temporal.TemporalAccessor, is java.time.Duration -> value.toString()
            is Iterable<*> -> JSONArray(value.map { value(it, depth + 1) })
            is Enum<*> -> value.name
            else -> {
                require(value.javaClass.name.startsWith("androidx.health.connect.client.")) { "Unsupported health value type." }
                JSONObject().apply {
                    put("sdkType", value.javaClass.simpleName)
                    value.javaClass.methods.filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                        it.name.startsWith("get") && it.name.length > 3 && it.name != "getClass" &&
                        it.name != "getExerciseRouteResult" && '$' !in it.name }
                        .sortedBy { it.name }.forEach { method ->
                            put(method.name.removePrefix("get").replaceFirstChar { it.lowercase() }, value(method.invoke(value), depth + 1))
                        }
                }
            }
        }
    }
}

/** Updated by activity lifecycle callbacks; health reads never block a foreground frame. */
object HealthVisibility { @Volatile var startedActivities = 0 }

object HealthRepository {
    val mutex = Mutex()
    suspend fun refresh(context: Context, force: Boolean = false, gateway: HealthGateway? = null) = mutex.withLock {
        if (!HealthStore.exists(context)) return@withLock
        HealthStore(context).use { store ->
            if (!store.flag("enabled")) return@withLock
            try {
                val device = gateway ?: if (HealthDevice.status(context) == HealthConnectClient.SDK_AVAILABLE) HealthDevice(context) else null
                if (device == null) { store.snapshot(HealthSnapshot(status = "Health Connect is unavailable or needs an update.")); return@withLock }
                val permissions = device.permissions()
                val fingerprint = permissions.sorted().joinToString(",")
                val changed = fingerprint != store.meta("permissions")
                if (changed) { store.clearReads(); store.set("permissions", fingerprint) }
                val canRead = HealthVisibility.startedActivities > 0 || HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in permissions
                if (!canRead && gateway == null) {
                    if (changed) store.snapshot(HealthSnapshot(status = "Open the launcher to refresh health data.", permissions = permissions))
                    return@withLock
                }
                val previous = store.snapshot()
                if (!force && !changed && previous.date == java.time.LocalDate.now().toString() && System.currentTimeMillis() - previous.updated < 30_000) return@withLock
                store.snapshot(device.totals()); store.set("status", "")
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
            } catch (_: SecurityException) { store.clearReads(); store.snapshot(HealthSnapshot(status = "Health permission changed. Reconnect in Settings."))
            } catch (_: Exception) { store.set("status", "Couldn't refresh health data. Your last snapshot may be out of date.") }
        }
    }
}
