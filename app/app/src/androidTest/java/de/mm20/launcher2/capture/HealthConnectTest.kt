package de.mm20.launcher2.capture

import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.health.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.time.LocalDate

internal class FakeHealth : HealthGateway {
    var grants = HealthCatalog.corePermissions.toSet()
    val records = mutableMapOf<String, Pair<Long, FoodEntry>>()
    var calls = 0
    var reads = 0
    var loseAck = false
    override suspend fun permissions() = grants
    override fun availableTypes() = setOf("StepsRecord", "NutritionRecord")
    override suspend fun totals(now: Instant) = HealthSnapshot(LocalDate.now().toString(), 500.0, 100.0, 1500.0, System.currentTimeMillis(), "", grants)
    override suspend fun food(entry: FoodEntry, clientId: String): String {
        calls++
        if ((records[clientId]?.first ?: 0) < entry.revision) records[clientId] = entry.revision to entry
        if (loseAck) { loseAck = false; throw java.io.IOException("lost acknowledgment") }
        return "record-$clientId"
    }
    override suspend fun read(query: HealthRead): JSONObject {
        reads++
        return JSONObject().put("id", query.id).put("status", "complete").put("nextPageToken", "page-2")
    }
}

class HealthConnectTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun store(block: suspend (HealthStore, FakeHealth) -> Unit) = runBlocking {
        context.deleteDatabase("unused")
        val path = java.io.File(context.noBackupFilesDir, "health/health.db")
        path.delete()
        try { HealthStore(context).use { s ->
            assertFalse(s.flag("share")); assertFalse(s.flag("control")); assertNull(s.goals().foodKcal)
            s.flag("enabled", true); block(s, FakeHealth())
        } } finally { path.delete() }
    }
    private fun commands(extra: String) = HealthCommands.parse("""{"schemaVersion":1,$extra}""")
    private fun food(rev: Int = 1, kcal: Int = 550) = """"foods":[{"id":"meal-1","revision":$rev,"name":"Lunch","at":"2026-01-01T12:00:00-05:00","kcal":$kcal}]"""
    private fun query(id: String = "q-1") = """"reads":[{"id":"$id","type":"StepsRecord","start":"2026-01-01T00:00:00Z","end":"2026-01-02T00:00:00Z","pageSize":25}]"""
    private suspend fun process(s: HealthStore, f: FakeHealth, c: HealthCommands, canRead: Boolean = true) = HealthProcessor.process(s, "account-1", c, f, canRead)

    @Test fun foodAndGoalsRequireSeparateAgentControl() = store { s, f ->
        val c = commands(food()+""", "goal":{"revision":1,"dailyFoodKcal":2200,"creditActiveCalories":true}""")
        val result = process(s, f, c)
        assertEquals("agent_control_disabled", result.getJSONObject("goalResult").getString("status"))
        assertEquals(0, f.calls); assertNull(s.goals().foodKcal); assertFalse(result.has("today"))
        s.flag("control", true); process(s, f, c)
        assertEquals(1, f.records.size); assertEquals(2200.0, s.goals().foodKcal!!, 0.01)
    }
    @Test fun lostAcknowledgmentRetriesSameSdkIdAndRevisionWithoutDuplicateMeal() = store { s, f ->
        s.flag("control", true); f.loseAck = true
        assertEquals("retry", process(s,f,commands(food())).getJSONArray("foodResults").getJSONObject(0).getString("status"))
        assertEquals("logged", process(s,f,commands(food())).getJSONArray("foodResults").getJSONObject(0).getString("status"))
        process(s,f,commands(food()))
        assertEquals(2,f.calls); assertEquals(1,f.records.size)
        process(s,f,commands(food(2,600)))
        assertEquals(1,f.records.size); assertEquals(600.0,f.records.values.single().second.kcal,0.01)
    }
    @Test fun staleAndConflictingRevisionsDoNotWrite() = store { s,f ->
        s.flag("control",true); process(s,f,commands(food(2)))
        for ((c, expected) in listOf(food(1) to "stale_revision", food(2,600) to "revision_conflict")) {
            assertEquals(expected,process(s,f,commands(c)).getJSONArray("foodResults").getJSONObject(0).getString("status"))
        }
        assertEquals(1,f.calls)
    }
    @Test fun permissionDenialAndAccountMismatchNeverWrite() = store { s,f ->
        s.flag("control",true); f.grants=emptySet()
        assertEquals("nutrition_write_permission_required",process(s,f,commands(food())).getJSONArray("foodResults").getJSONObject(0).getString("status"))
        assertEquals(0,f.calls)
        try { HealthProcessor.process(s,"other-account",commands(food()),f,true); fail() } catch (_: IllegalStateException) {}
        assertEquals(0,f.calls)
    }
    @Test fun queryCacheRequiresConsentPermissionAndFreshIdsForNewPages() = store { s,f ->
        f.grants = f.grants + HealthCatalog.permission("StepsRecord")
        process(s,f,commands(query())); assertEquals(0,f.reads)
        s.flag("share",true)
        assertEquals("pending_foreground",process(s,f,commands(query()),false).getJSONArray("readResults").getJSONObject(0).getString("status"))
        repeat(2) { assertEquals("page-2", process(s,f,commands(query())).getJSONArray("readResults").getJSONObject(0).getString("nextPageToken")) }
        assertEquals(1,f.reads)
        process(s,f,commands(query("q-2"))); assertEquals(2,f.reads)
        f.grants=emptySet()
        assertEquals("read_permission_required",process(s,f,commands(query("q-2"))).getJSONArray("readResults").getJSONObject(0).getString("status"))
        s.flag("share",false); val off=process(s,f,commands(query()))
        assertFalse(off.has("today")); assertFalse(off.has("permissions")); assertNull(s.read("q-2",HealthCommands.digest(commands(query("q-2")).reads.single().json()),setOf(HealthCatalog.permission("StepsRecord"))))
    }
    @Test fun malformedWholeRequestIsRejectedBeforeAnyWrite() = store { _,f ->
        for (raw in listOf("""{"schemaVersion":1,"foods":{}}""", """{"schemaVersion":1.5}""",
            """{"schemaVersion":1,${food()},"reads":[{"id":"q","type":"StepsRecord","start":"2026-01-01T00:00:00Z","end":"2026-01-02T00:00:00Z","pageSize":1.5}]}""")) {
            try { HealthCommands.parse(raw); fail("Malformed request accepted") } catch (_: Exception) {}
        }
        assertEquals(0,f.calls)
    }
    @Test fun goalConflictKeepsUserTargetAndActiveFormula() = store { s,f ->
        s.flag("control",true); s.flag("share",true); s.snapshot(f.totals())
        process(s,f,commands(""""goal":{"revision":1,"dailyFoodKcal":2200,"creditActiveCalories":true}"""))
        val r=process(s,f,commands(""""goal":{"revision":1,"dailyFoodKcal":1900,"creditActiveCalories":false}"""))
        assertEquals("revision_conflict",r.getJSONObject("goalResult").getString("status"))
        assertEquals(1800.0,r.getJSONObject("today").getDouble("remainingKcal"),0.01)
    }
    @Test fun revokedPermissionsClearCachedWidgetData() = store { s,f ->
        HealthRepository.refresh(context,true,f); assertNotNull(s.snapshot().consumed)
        val denied = object : HealthGateway by f { override suspend fun totals(now: Instant): HealthSnapshot = throw SecurityException() }
        HealthRepository.refresh(context,true,denied)
        assertNull(s.snapshot().consumed); assertTrue(s.snapshot().status.contains("permission"))
    }
    @Test fun allSupportedReadTypesAreDeclaredAndOnlyNutritionCanBeWritten() {
        val declared = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        assertEquals(41, HealthCatalog.records.size)
        for (type in HealthCatalog.records.keys) assertTrue(type, HealthCatalog.permission(type) in declared)
        assertEquals(setOf(HealthDevice.nutritionWrite), declared.filter { it.startsWith("android.permission.health.WRITE_") }.toSet())
    }
    @Test fun healthWidgetRoundTripsItsIdentityForEveryGridLayout() {
        val id=java.util.UUID.randomUUID()
        val widget=de.mm20.launcher2.widgets.HealthWidget(id)
        val stored=widget.toDatabaseEntity()
        val restored=de.mm20.launcher2.widgets.Widget.fromDatabaseEntity(
            de.mm20.launcher2.database.entities.WidgetEntity(stored.type,stored.config,3,id))
        assertEquals(widget,restored)
    }
}
