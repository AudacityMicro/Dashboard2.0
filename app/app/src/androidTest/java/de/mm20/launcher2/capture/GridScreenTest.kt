package de.mm20.launcher2.capture

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.dashboard.*
import de.mm20.launcher2.preferences.ScreenOrientation
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.launcher.LauncherActivity
import de.mm20.launcher2.ui.dashboard.GridEditing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GridScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun homeHidesEmptySectionsAndRestoresLayoutsOnRealWindowChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ui = org.koin.core.context.GlobalContext.get().get<UiSettings>()
        val orientation = runBlocking { ui.orientation.first() }
        val homeWidgets = runBlocking { ui.homeScreenWidgets.first() }
        val repository = org.koin.core.context.GlobalContext.get().get<de.mm20.launcher2.widgets.WidgetRepository>()
        val sampleWidget = de.mm20.launcher2.widgets.NotesWidget(java.util.UUID.randomUUID(),
            de.mm20.launcher2.widgets.NotesWidgetConfig(storedText = "Grid widget sample"))
        val keys = listOf("board", "setupHidden") + GridProfile.entries.map { "grid.v1.home.${it.name}" }
        val before = DashboardStore(context).use { store -> keys.associateWith { store.meta(it) } }
        var resized = false
        fun screenshot(name: String) {
            if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            java.io.File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        } }
        try {
            ui.setHomeScreenWidgets(false)
            runBlocking { ui.homeScreenWidgets.first { !it } }
            repository.create(sampleWidget, 0, de.mm20.launcher2.preferences.WidgetScreenTarget.Default.id)
            DashboardStore(context).use {
                it.setMeta("setupHidden", "true")
                it.acceptBoard("""{"schemaVersion":1,"updates":[],"tasks":[]}""")
            }
            GridProfile.entries.forEach { profile ->
                val columns = profile.columns
                WidgetGridStore(context).save("home", profile, WidgetGridLayout(columns)
                    .place("updates", GridCell(0, 0, columns / 2, if (profile == GridProfile.Landscape) 3 else 4))
                    .place("tasks", GridCell(columns / 2, 0, columns / 2, if (profile == GridProfile.Landscape) 3 else 4))
                    .place("setup", GridCell(0, 20, columns, 8)).place("connection", GridCell(0, 28, columns, 2))
                    .copy(hidden = setOf("setup", "connection")))
            }
            ui.setOrientation(ScreenOrientation.Portrait)
            runBlocking { ui.orientation.first { it == ScreenOrientation.Portrait } }
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("widget-grid-Portrait").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("agent-updates").assertDoesNotExist()
                compose.onNodeWithTag("todo-list").assertDoesNotExist()
                compose.onNodeWithText("Agent updates").assertDoesNotExist()
                compose.onNodeWithText("To-do list").assertDoesNotExist()
                DashboardStore(context).use { store -> store.acceptBoard("""{"schemaVersion":1,"updates":[{"id":"sample","title":"Review today's plan","body":"Sample: review your notes and prepare your next steps."}],"tasks":[{"id":"sample-task","title":"Review captured notes","completed":false}]}""") }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-updates").fetchSemanticsNodes().isNotEmpty() }
                val updates = compose.onNodeWithTag("agent-updates").getUnclippedBoundsInRoot()
                val tasks = compose.onNodeWithTag("todo-list").getUnclippedBoundsInRoot()
                assertEquals(updates.top, tasks.top); assertTrue(updates.right <= tasks.left)
                screenshot("grid-home-portrait.png")
                compose.onNodeWithTag("search-overflow-menu").performClick()
                compose.onNodeWithTag("edit-grid-menu").performClick()
                compose.onNodeWithTag("grid-profile-Unfolded").assertIsDisplayed()
                screenshot("grid-home-editor.png")
                compose.onNodeWithText("Done").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
                ui.setOrientation(ScreenOrientation.Landscape)
                compose.waitUntil(15000) { compose.onAllNodesWithTag("widget-grid-Landscape").fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle(); screenshot("grid-home-landscape.png")
                if (InstrumentationRegistry.getArguments().getString("screenshots") == "true" && android.os.Build.MODEL.contains("sdk")) {
                    shell("wm size 1800x2200"); resized = true
                    compose.waitUntil(15000) { compose.onAllNodesWithTag("widget-grid-Unfolded").fetchSemanticsNodes().isNotEmpty() }
                    compose.waitForIdle(); screenshot("grid-home-unfolded.png")
                }
                compose.onNodeWithTag("search-overflow-menu").performClick()
                compose.onNodeWithTag("edit-grid-menu").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("grid-resize-widget-${sampleWidget.id}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("grid-resize-widget-${sampleWidget.id}").performScrollTo().performClick()
                compose.onNodeWithTag("grid-edit-w").performTextReplacement("4")
                compose.onNodeWithTag("grid-edit-h").performTextReplacement("4")
                compose.onNodeWithText("Save layout").performClick()
                val activeProfile = if (resized) GridProfile.Unfolded else GridProfile.Landscape
                compose.waitUntil(5000) { WidgetGridStore(context).load("home", activeProfile).cells["widget-${sampleWidget.id}"]?.height == 4 }
                compose.waitUntil(5000) { compose.onAllNodesWithText("Done").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Done").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
                compose.waitUntil(5000) { GridEditing.target == null }
                compose.waitForIdle()
                runBlocking { kotlinx.coroutines.withTimeout(10000) { ui.homeScreenWidgets.first { it } } }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("grid-item-widget-${sampleWidget.id}").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            GridEditing.active = false
            if (resized) shell("wm size reset")
            ui.setOrientation(orientation)
            ui.setHomeScreenWidgets(homeWidgets)
            repository.delete(sampleWidget)
            DashboardStore(context).use { store -> before.forEach { (key, value) -> store.setMeta(key, value) } }
        }
    }
}
