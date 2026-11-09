package de.mm20.launcher2.capture

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.dashboard.*
import de.mm20.launcher2.ui.base.ProvideCompositionLocals
import de.mm20.launcher2.ui.dashboard.*
import de.mm20.launcher2.ui.launcher.widgets.rememberNativeGridWidgets
import de.mm20.launcher2.ui.overlays.OverlayHost
import de.mm20.launcher2.ui.settings.SettingsActivity
import de.mm20.launcher2.widgets.AppsWidget
import de.mm20.launcher2.widgets.WidgetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class WidgetDeleteTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun ActivityScenario<SettingsActivity>.show(content: @Composable () -> Unit) {
        onActivity { activity -> activity.setContent { MaterialTheme { ProvideCompositionLocals {
            CompositionLocalProvider(LocalConfiguration provides Configuration(context.resources.configuration).apply {
                screenWidthDp = 400; screenHeightDp = 900
            }) { OverlayHost(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 32.dp)) { content() }
            } }
        } } } }
    }
    private fun cleanup(parent: String) {
        DashboardStore(context).use { it.writableDatabase.delete("meta", "key LIKE ?", arrayOf("grid.v1.$parent.%")) }
        GridEditing.active = false
    }

    @Test fun selectedSectionCanBeDeletedRestoredAndPersistsOnlyInActiveLayout() {
        val parent = "delete-${UUID.randomUUID()}"
        val store = WidgetGridStore(context)
        val original = WidgetGridLayout(4).place("left", GridCell(0, 0, 2, 3)).place("right", GridCell(2, 0, 2, 3))
        val tiles = listOf("left", "right").map { id -> GridTile(id, id, rows = 3) { modifier -> Text(id, modifier) } }
        try {
            GridProfile.entries.forEach { store.save(parent, it, original) }
            ActivityScenario.launch(SettingsActivity::class.java).use { screen ->
                fun show() = screen.show { ProfiledWidgetGrid(parent, tiles, editing = true) }
                show()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("grid-item-left").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("grid-delete-left").assertDoesNotExist()
                compose.onNodeWithTag("grid-item-left").performTouchInput { longClick() }
                compose.onNodeWithTag("grid-move-left").assertExists()
                compose.onNodeWithTag("grid-resize-left").assertExists()
                compose.onNodeWithTag("grid-delete-selected-left").assertIsDisplayed()
                compose.onNodeWithTag("grid-delete-left").assertIsDisplayed()
                if (InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
                    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
                    java.io.File(context.getExternalFilesDir(null), "widget-delete.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                compose.onNodeWithTag("grid-delete-left").performClick()
                compose.waitUntil(5000) { "left" in store.load(parent, GridProfile.Portrait).hidden }
                compose.onNodeWithTag("grid-item-left").assertDoesNotExist()
                assertEquals(original.cells, store.load(parent, GridProfile.Portrait).cells)
                assertEquals(original, store.load(parent, GridProfile.Landscape))
                assertEquals(original, store.load(parent, GridProfile.Unfolded))
                compose.onNodeWithText("Widgets").performClick()
                compose.onNodeWithText("Show left").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("grid-item-left").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("grid-item-left").performTouchInput { longClick() }
                compose.onNodeWithTag("grid-delete-selected-left").performClick()
                compose.waitUntil(5000) { "left" in store.load(parent, GridProfile.Portrait).hidden }
                screen.recreate(); show()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("grid-item-right").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
                compose.onNodeWithTag("grid-item-left").assertDoesNotExist()
                compose.onNodeWithTag("grid-delete-selected-left").assertDoesNotExist()
            }
        } finally { cleanup(parent) }
    }

    @Test fun compactWidgetKeepsHandlesClearAndOffersToolbarAndAccessibleDelete() {
        val parent = "compact-delete-${UUID.randomUUID()}"
        val store = WidgetGridStore(context)
        try {
            store.save(parent, GridProfile.Portrait, WidgetGridLayout(4).place("tiny", GridCell(0, 0, 1, 1)))
            ActivityScenario.launch(SettingsActivity::class.java).use { screen ->
                screen.show { ProfiledWidgetGrid(parent, listOf(GridTile("tiny", "Tiny") { Text("Tiny", it) }), editing = true) }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("grid-item-tiny").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("grid-item-tiny").performTouchInput { longClick() }
                compose.onNodeWithTag("grid-delete-tiny").assertDoesNotExist()
                compose.onNodeWithTag("grid-delete-selected-tiny").assertIsDisplayed()
                val actions = compose.onNodeWithTag("grid-item-tiny").fetchSemanticsNode().config[SemanticsActions.CustomActions]
                compose.runOnIdle { assertTrue(actions.single { it.label == "Delete widget" }.action()) }
                compose.waitUntil(5000) { "tiny" in store.load(parent, GridProfile.Portrait).hidden }
                compose.onNodeWithTag("grid-item-tiny").assertDoesNotExist()
            }
        } finally { cleanup(parent) }
    }

    @Test fun nativeWidgetDeleteCanBeCancelledThenRemovesOnlyTheChosenInstance() {
        val repository = org.koin.core.context.GlobalContext.get().get<WidgetRepository>()
        val parent = UUID.randomUUID()
        val widget = AppsWidget.favoritesOnly(UUID.randomUUID())
        val other = AppsWidget.selectedApps(UUID.randomUUID())
        val id = "widget-${widget.id}"
        try {
            repository.create(widget, 0, parent); repository.create(other, 1, parent)
            ActivityScenario.launch(SettingsActivity::class.java).use { screen ->
                screen.show {
                    val native = rememberNativeGridWidgets(parent.toString(), editing = true)
                    ProfiledWidgetGrid(parent.toString(), native.tiles, editing = true, onAdd = native.add)
                }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("grid-item-$id").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("grid-item-$id").performTouchInput { longClick() }
                compose.onNodeWithTag("grid-delete-$id").performClick()
                compose.onNodeWithText("Remove widget from every layout?").assertIsDisplayed()
                compose.onNodeWithText("Cancel").performClick()
                assertTrue(runBlocking { repository.get(parent).first() }.any { it.id == widget.id })
                compose.onNodeWithTag("grid-delete-selected-$id").performClick()
                compose.onNodeWithText("Remove everywhere").performClick()
                compose.waitUntil(10000) { runBlocking { repository.get(parent).first() }.none { it.id == widget.id } }
                compose.onNodeWithTag("grid-item-$id").assertDoesNotExist()
                assertTrue(runBlocking { repository.get(parent).first() }.any { it.id == other.id })
                compose.onNodeWithTag("grid-item-widget-${other.id}").assertExists()
            }
        } finally {
            repository.delete(widget); repository.delete(other); cleanup(parent.toString())
        }
    }
}
