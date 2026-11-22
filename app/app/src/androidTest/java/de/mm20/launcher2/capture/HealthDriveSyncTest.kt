package de.mm20.launcher2.capture

import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.health.*
import de.mm20.launcher2.dashboard.DashboardStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class HealthDriveSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun run(incoming: String, check: (JSONObject, FakeHealth) -> Unit) = runBlocking {
        val file = java.io.File(context.noBackupFilesDir,"health/health.db"); file.delete()
        try { HealthStore(context).use { s ->
            s.flag("enabled",true); s.flag("share",true); s.flag("control",true)
            s.set("agentFile","health-agent"); s.set("phoneFile","health-phone")
            val fake=FakeHealth()
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setBody("""{"id":"health-agent","appProperties":{"captureId":"dashboard-health-agent-v1"}}"""))
                server.enqueue(MockResponse().setBody("""{"id":"health-phone","appProperties":{"captureId":"dashboard-health-phone-v1"}}"""))
                server.enqueue(MockResponse().setBody(incoming)); server.enqueue(MockResponse().setBody("{}"))
                assertFalse(HealthDriveSync.sync(context,"fake-token","test-owner",server.url("/").toString().trimEnd('/'),fake))
                repeat(3) { assertEquals("GET",server.takeRequest().method) }
                val outgoing=server.takeRequest(); assertEquals("PATCH",outgoing.method)
                assertTrue(outgoing.path!!.contains("health-phone")); check(JSONObject(outgoing.body.readUtf8()),fake)
                assertEquals(4,server.requestCount)
                DashboardStore(context).use { d ->
                    val registry=JSONObject(d.phoneState()).getJSONObject("health")
                    assertEquals("health-agent",registry.getString("agentFileId"))
                    assertEquals("health-phone",registry.getString("phoneFileId"))
                }
                // Account pin is checked before any HTTP or SDK writes.
                assertFalse(HealthDriveSync.sync(context,"fake-token","other-owner",server.url("/").toString().trimEnd('/'),fake))
                assertEquals(4,server.requestCount)
            }
        } } finally {
            file.delete()
            DashboardStore(context).use { it.setMeta("healthAgentFile",""); it.setMeta("healthPhoneFile","") }
        }
    }
    @Test fun agentInputIsNeverOverwrittenAndPhoneResultConfirmsFoodAndGoal() = run("""{"schemaVersion":1,"goal":{"revision":1,"dailyFoodKcal":2200,"creditActiveCalories":true},"foods":[{"id":"lunch","revision":1,"name":"Lunch","at":"2026-01-01T12:00:00Z","kcal":500}]}""") { result,fake ->
        assertEquals(1,fake.records.size)
        assertEquals("logged",result.getJSONArray("foodResults").getJSONObject(0).getString("status"))
        assertEquals(1800.0,result.getJSONObject("today").getDouble("remainingKcal"),0.001)
    }
    @Test fun invalidAgentFileAppliesNoCommandsAndPublishesNoHealthSnapshot() = run("""{"schemaVersion":1,"foods":"invalid"}""") { result,fake ->
        assertEquals(0,fake.calls); assertFalse(result.has("today")); assertTrue(result.getString("status").contains("Invalid"))
    }
}
