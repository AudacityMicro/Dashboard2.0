@file:OptIn(androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi::class)
package de.mm20.launcher2.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import kotlin.reflect.KClass

object HealthCatalog {
    val records: Map<String, KClass<out Record>> = listOf(
        ActiveCaloriesBurnedRecord::class,
        BasalBodyTemperatureRecord::class,
        BasalMetabolicRateRecord::class,
        BloodGlucoseRecord::class,
        BloodPressureRecord::class,
        BodyFatRecord::class,
        BodyTemperatureRecord::class,
        BodyWaterMassRecord::class,
        BoneMassRecord::class,
        CervicalMucusRecord::class,
        CyclingPedalingCadenceRecord::class,
        DistanceRecord::class,
        ElevationGainedRecord::class,
        ExerciseSessionRecord::class,
        FloorsClimbedRecord::class,
        HeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        HeightRecord::class,
        HydrationRecord::class,
        IntermenstrualBleedingRecord::class,
        LeanBodyMassRecord::class,
        MenstruationFlowRecord::class,
        MenstruationPeriodRecord::class,
        MindfulnessSessionRecord::class,
        NutritionRecord::class,
        OvulationTestRecord::class,
        OxygenSaturationRecord::class,
        PlannedExerciseSessionRecord::class,
        PowerRecord::class,
        RespiratoryRateRecord::class,
        RestingHeartRateRecord::class,
        SexualActivityRecord::class,
        SleepSessionRecord::class,
        SpeedRecord::class,
        SkinTemperatureRecord::class,
        StepsCadenceRecord::class,
        StepsRecord::class,
        TotalCaloriesBurnedRecord::class,
        Vo2MaxRecord::class,
        WeightRecord::class,
        WheelchairPushesRecord::class
    ).associateBy { requireNotNull(it.simpleName) }
    fun permission(type: String): String = HealthPermission.getReadPermission(requireNotNull(records[type]))
    fun available(client: HealthConnectClient): Map<String, KClass<out Record>> = records.filterKeys { type ->
        val feature = when (type) {
            "SkinTemperatureRecord" -> HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE
            "PlannedExerciseSessionRecord" -> HealthConnectFeatures.FEATURE_PLANNED_EXERCISE
            "MindfulnessSessionRecord" -> HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION
            else -> null
        }
        feature == null || client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }
    val corePermissions = setOf(permission("NutritionRecord"), permission("ActiveCaloriesBurnedRecord"),
        permission("TotalCaloriesBurnedRecord"), HealthPermission.getWritePermission(NutritionRecord::class))
}
