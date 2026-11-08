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
    @Test fun homeHasAllThreeCaptureButtonsAndTextOpensTheEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(QuickCaptureActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("capture-actions").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Text note").assertExists()
                compose.onNodeWithText("Voice note").assertExists()
                compose.onNodeWithText("Add image").assertExists()
                compose.onNodeWithTag("capture-text").performScrollTo().performClick()
                val opened = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertNotNull("Home capture button did not open capture", opened)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertTrue("Text quick capture should focus the editor", opened.currentFocus is android.widget.EditText)
                    opened.finish()
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

}
