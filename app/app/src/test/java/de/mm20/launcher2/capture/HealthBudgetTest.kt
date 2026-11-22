package de.mm20.launcher2.capture

import de.mm20.launcher2.health.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HealthBudgetTest {
    private val today = LocalDate.now().toString()
    @Test fun creditsOnlyActiveCaloriesNotTotalBurned() {
        val snapshot = HealthSnapshot(today, consumed = 1800.0, active = 300.0, burned = 2100.0)
        assertEquals(700.0, snapshot.remaining(HealthGoals(2200.0))!!, 0.001)
        assertEquals(400.0, snapshot.remaining(HealthGoals(2200.0, false))!!, 0.001)
    }
    @Test fun unsetGoalMissingFoodAndPreviousDayNeverInventBudget() {
        assertNull(HealthSnapshot(today, 500.0).remaining(HealthGoals()))
        assertNull(HealthSnapshot(today).remaining(HealthGoals(2000.0)))
        assertNull(HealthSnapshot(LocalDate.now().minusDays(1).toString(), 500.0).remaining(HealthGoals(2000.0)))
    }
    @Test fun missingActivityAddsNoCreditAndOverBudgetCanBeNegative() {
        assertEquals(-500.0, HealthSnapshot(today, 2500.0, null, 9000.0).remaining(HealthGoals(2000.0))!!, 0.001)
    }
}
