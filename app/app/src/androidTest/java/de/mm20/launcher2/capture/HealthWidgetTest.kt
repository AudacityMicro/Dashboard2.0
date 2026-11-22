package de.mm20.launcher2.capture

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import de.mm20.launcher2.health.*
import de.mm20.launcher2.ui.launcher.widgets.health.HealthCardContent
import de.mm20.launcher2.ui.settings.SettingsActivity
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class HealthWidgetTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun widgetShowsActiveCreditWithoutAddingTotalBurnedToBudget() {
        ActivityScenario.launch(SettingsActivity::class.java).use { screen ->
            screen.onActivity { it.setContent { MaterialTheme {
                HealthCardContent(HealthSnapshot(LocalDate.now().toString(),1800.0,300.0,2100.0),HealthGoals(2200.0),true)
            } } }
            compose.onNodeWithText("700").assertIsDisplayed()
            compose.onNodeWithText("2100").assertIsDisplayed()
            compose.onNodeWithText("Goal 2200 kcal · active credit 300 kcal").assertIsDisplayed()
        }
    }
    @Test fun unconfiguredHealthWidgetOffersConnectionWithoutInventingCalories() {
        ActivityScenario.launch(SettingsActivity::class.java).use { screen ->
            screen.onActivity { it.setContent { MaterialTheme { HealthCardContent(HealthSnapshot(),HealthGoals(),false) } } }
            compose.onNodeWithText("Connect Health Connect").assertIsDisplayed()
            compose.onNodeWithText("2200").assertDoesNotExist()
        }
    }
}
