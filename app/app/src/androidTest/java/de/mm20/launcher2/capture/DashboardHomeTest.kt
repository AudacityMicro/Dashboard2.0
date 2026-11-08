package de.mm20.launcher2.capture

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import de.mm20.launcher2.dashboard.DashboardStore
import de.mm20.launcher2.ui.launcher.LauncherActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DashboardHomeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun mainHomeContainsAgentUpdatesAndSavesAndChecksOffATask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = "Home task " + java.util.UUID.randomUUID()
        var id: String? = null
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-updates").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("agent-updates").assertExists()
                compose.onNodeWithText("Add task").performScrollTo().performClick()
                compose.onNodeWithTag("new-task-title").performTextInput(title)
                compose.onNodeWithText("Save task").performClick()
                compose.waitUntil(5000) {
                    id = DashboardStore(context).use { store -> store.snapshot().tasks.find { task -> task.title == title }?.id }
                    id != null
                }
                compose.onNodeWithTag("task-check-${requireNotNull(id)}").performScrollTo().performClick()
                compose.waitUntil(5000) { DashboardStore(context).use { store -> store.snapshot().tasks.find { task -> task.id == id }?.completed == true } }
                DashboardStore(context).use { store -> assertTrue(store.snapshot().tasks.single { task -> task.id == id }.completed) }
            }
        } finally {
            id?.let { saved -> DashboardStore(context).use { store -> store.writableDatabase.delete("local_tasks", "id=?", arrayOf(saved)) } }
        }
    }
}
