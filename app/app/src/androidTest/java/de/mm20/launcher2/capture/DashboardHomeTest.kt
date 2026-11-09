package de.mm20.launcher2.capture

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import de.mm20.launcher2.dashboard.DashboardStore
import de.mm20.launcher2.dashboard.AgentSetup
import de.mm20.launcher2.ui.launcher.LauncherActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DashboardHomeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun hiddenSetupSurvivesRecreationAndCanBeRestoredFromInbox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val old = DashboardStore(context).use { it.meta("setupHidden") }
        try {
            DashboardStore(context).use { it.setMeta("setupHidden", "false") }
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-setup").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("hide-dashboard-setup").performScrollTo().performClick()
                compose.waitUntil(5000) {
                    DashboardStore(context).use { it.snapshot().setupHidden } &&
                        compose.onAllNodesWithTag("agent-setup").fetchSemanticsNodes().isEmpty()
                }
                home.recreate()
                compose.waitForIdle()
                compose.onNodeWithTag("agent-setup").assertDoesNotExist()
                compose.onNodeWithText("Copy agent file IDs").assertDoesNotExist()
                compose.onNodeWithTag("capture-actions").assertExists()
                compose.onNodeWithTag("todo-list").assertExists()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val monitor = instrumentation.addMonitor(CaptureActivity::class.java.name, null, false)
                try {
                    compose.onNodeWithText("Capture inbox").performScrollTo().performClick()
                    val inbox = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000))
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Copy agent setup prompt"))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isEnabled()))
                    val show = androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Show setup on home screen"))
                    show.check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isNotChecked()))
                        .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click())
                    compose.waitUntil(5000) { DashboardStore(context).use { !it.snapshot().setupHidden } }
                    instrumentation.runOnMainSync { inbox.finish() }
                } finally { instrumentation.removeMonitor(monitor) }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-setup").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            }
        } finally { DashboardStore(context).use { it.setMeta("setupHidden", old) } }
    }
    @Test fun updateScreenOpensFromHomeWithoutStartingANetworkCheck() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(de.mm20.launcher2.updates.UpdateActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("check-dashboard-updates").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("check-dashboard-updates").performScrollTo().performClick()
                val opened = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertNotNull(opened)
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Tap Check now to contact GitHub."))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                instrumentation.runOnMainSync { opened.finish() }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun setupPromptIsAvailableOnHomeBeforeDriveConnection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(LauncherActivity::class.java).use {
            compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-setup").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("copy-agent-setup").performScrollTo().performClick()
            compose.onNodeWithTag("agent-setup-copied").assertExists()
            compose.runOnIdle {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                val prompt = requireNotNull(clip).getItemAt(0).text.toString()
                assertTrue(prompt.contains(AgentSetup.INSTRUCTIONS_NAME))
                assertTrue(prompt.contains("BEFORE EACH Dashboard"))
                assertTrue(prompt.contains("Never request my Google password"))
            }
        }
    }
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
