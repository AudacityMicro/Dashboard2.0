package de.mm20.launcher2.capture

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.NutritionRecord
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.health.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Run only against synthetic test-device health data; removes the record it creates. */
class HealthSdkTest {
    @Test fun realProviderWritesCorrectsReadsAggregatesAndDeletesOneSyntheticMeal() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assertEquals(HealthConnectClient.SDK_AVAILABLE,HealthDevice.status(context))
        val grants=setOf(HealthDevice.nutritionWrite,HealthCatalog.permission("NutritionRecord"))
        for (grant in grants) instrumentation.uiAutomation.grantRuntimePermission(context.packageName,grant)
        ActivityScenario.launch(HealthConnectActivity::class.java).use {
            val device=HealthDevice(context)
            val id="dashboard-test-${UUID.randomUUID()}"
            val at=OffsetDateTime.now().minusMinutes(1)
            val entry=FoodEntry("test",1,"Synthetic test meal",at,550.0,mapOf("proteinGrams" to 30.0))
            try {
                val before=device.totals().consumed!!
                device.food(entry,id); device.food(entry,id)
                assertEquals(before+550.0,device.totals().consumed!!,0.01)
                device.food(entry.copy(revision=2,kcal=650.0),id)
                assertEquals(before+650.0,device.totals().consumed!!,0.01)
                val page=device.read(HealthRead("test-query","NutritionRecord",at.toInstant().minusSeconds(5),Instant.now(),100,null))
                val records=page.getJSONArray("records")
                val own=(0 until records.length()).map { records.getJSONObject(it) }.filter {
                    it.getJSONObject("metadata").optString("clientRecordId")==id
                }
                assertEquals(1,own.size)
                assertEquals(650.0,own.single().getJSONObject("energy").getDouble("kilocalories"),0.01)
            } finally { device.client.deleteRecords(NutritionRecord::class,emptyList(),listOf(id)) }
        }
    }
}
