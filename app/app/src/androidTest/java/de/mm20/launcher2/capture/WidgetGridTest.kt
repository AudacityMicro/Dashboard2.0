package de.mm20.launcher2.capture

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.dashboard.*
import de.mm20.launcher2.ui.dashboard.*
import de.mm20.launcher2.ui.launcher.LauncherActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WidgetGridTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun cleanup(parent: String) { DashboardStore(context).use { it.writableDatabase.delete("meta", "key LIKE ?", arrayOf("grid.v1.$parent.%")) } }
    private val tiles get() = listOf("left", "right").map { id -> GridTile(id, id, rows = 3) { modifier ->
        Surface(modifier.testTag("test-tile-$id")) { Text(id) }
    } }
    private fun editSize(id: String, width: String, height: String) {
        compose.onNodeWithTag("grid-item-$id").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithTag("grid-resize-$id").performClick()
        compose.onNodeWithTag("grid-edit-w").performTextReplacement(width)
        compose.onNodeWithTag("grid-edit-h").performTextReplacement(height)
        compose.onNodeWithText("Save layout").performClick()
    }
    @Test fun homeSettingsExposeAllScreenGrids() {
        ActivityScenario.launch(de.mm20.launcher2.ui.settings.SettingsActivity::class.java).use {
            val homeLabel = context.getString(de.mm20.launcher2.ui.R.string.preference_screen_homescreen)
            compose.onNodeWithText(homeLabel).performScrollTo().performClick()
            compose.onNodeWithText("Widget grid").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("grid-settings-Portrait").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save grid").assertIsEnabled()
            compose.onNodeWithTag("grid-settings-Portrait").performClick()
            compose.onNodeWithText("Save grid").assertIsEnabled()
            compose.onNodeWithTag("grid-settings-Landscape").performClick()
            compose.onNodeWithTag("grid-settings-Unfolded").performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithTag("grid-settings-Portrait").assertDoesNotExist()
        }
    }
    @Test fun gridSettingsReflowPagesAtomicallyAndKeepOtherProfiles() {
        val suffix = java.util.UUID.randomUUID().toString()
        val parents = listOf("settings-a-$suffix", "settings-b-$suffix")
        val store = WidgetGridStore(context)
        val keys = listOf("grid.config.Portrait", "grid.config.Landscape", "grid.config.Unfolded")
        val before = DashboardStore(context).use { db -> keys.associateWith { db.meta(it) } }
        // Restore all existing layouts because settings intentionally apply across widget pages.
        val layouts = DashboardStore(context).use { db -> db.readableDatabase.rawQuery("SELECT key,value FROM meta WHERE key LIKE 'grid.v1.%'", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        } }
        try {
            parents.forEach { store.save(it, GridProfile.Portrait, WidgetGridLayout(4).place("a", GridCell(0,0,2,3)).place("b", GridCell(2,0,2,3))) }
            store.save(parents[0], GridProfile.Landscape, WidgetGridLayout(8).place("a", GridCell(2,1,4,3)))
            store.configure(GridProfile.Portrait, 2, 80)
            parents.forEach { parent ->
                val grid = store.load(parent, GridProfile.Portrait)
                assertEquals(2, grid.columns); assertEquals(80, grid.rowDp)
                assertFalse(grid.cells.getValue("a").intersects(grid.cells.getValue("b")))
            }
            assertEquals(WidgetGridLayout(2,80), store.load("new-$suffix", GridProfile.Portrait))
            assertEquals(8, store.load(parents[0], GridProfile.Landscape).columns)
            try { store.configure(GridProfile.Portrait, 1, 80); fail("Invalid density accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(2, store.configuration(GridProfile.Portrait).columns)
        } finally {
            parents.forEach { cleanup(it) }
            DashboardStore(context).use { db -> (before + layouts).forEach { (key,value) -> db.setMeta(key,value) } }
        }
    }
    @Test fun longPressOverAChildButtonSelectsWithoutClickingAndKeepsShortTaps() {
        val parent = "press-${java.util.UUID.randomUUID()}"
        var clicks = 0
        try {
            WidgetGridStore(context).save(parent, GridProfile.Portrait, WidgetGridLayout(4).place("button", GridCell(0,0,4,5)))
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                home.onActivity { activity -> activity.setContent { MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ProfiledWidgetGrid(parent, listOf(GridTile("button", "Button") { modifier ->
                            Box(modifier) { androidx.compose.material3.Button(onClick = { clicks++ }, modifier = Modifier.fillMaxSize().testTag("child-button")) { Text("Tap me") } }
                        }))
                    }
                } } }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("child-button").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("child-button").performClick()
                compose.runOnIdle { assertEquals(1, clicks) }
                compose.onNodeWithTag("child-button").performTouchInput { swipeUp(durationMillis = 200) }
                compose.onNodeWithTag("grid-move-button").assertDoesNotExist()
                compose.onNodeWithTag("child-button").performTouchInput { longClick() }
                compose.onNodeWithTag("grid-move-button").assertExists()
                listOf("", "-tl", "-tr", "-bl").forEach { compose.onNodeWithTag("grid-resize-button$it").assertExists() }
                compose.runOnIdle { assertEquals(1, clicks) }
                compose.onNodeWithText("Done").performScrollTo().performClick()
                compose.onNodeWithTag("grid-move-button").assertDoesNotExist()
                compose.onNodeWithTag("child-button").performClick()
                compose.runOnIdle { assertEquals(2, clicks) }
            }
        } finally { cleanup(parent); GridEditing.active = false }
    }
    @Test fun malformedLayoutWritesCannotReplaceSavedGeometry() {
        val parent = "store-${java.util.UUID.randomUUID()}"
        val store = WidgetGridStore(context)
        val valid = WidgetGridLayout(4).place("a", GridCell(0, 0, 2, 3))
        try {
            store.save(parent, GridProfile.Portrait, valid)
            try { store.save(parent, GridProfile.Portrait, valid.copy(cells = valid.cells + ("b" to GridCell(0, 0, 2, 3)))); fail("Overlapping layout accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(valid, WidgetGridStore(context).load(parent, GridProfile.Portrait))
            assertEquals(WidgetGridLayout(8), store.load(parent, GridProfile.Landscape))
        } finally { cleanup(parent) }
    }
    @Test fun androidWidgetHostReceivesWidthAndHeightFromEachProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val parent = "native-${java.util.UUID.randomUUID()}"
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val host = android.appwidget.AppWidgetHost(context, 44203)
        val widgetId = host.allocateAppWidgetId()
        var granted = false
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        } }
        try {
            val provider = android.content.ComponentName(instrumentation.context.packageName, GridTestWidgetProvider::class.java.name)
            if (!manager.bindAppWidgetIdIfAllowed(widgetId, provider)) {
                shell("appwidget grantbind --package ${context.packageName} --user 0"); granted = true
                assertTrue(manager.bindAppWidgetIdIfAllowed(widgetId, provider))
            }
            val widget = de.mm20.launcher2.widgets.AppWidget(java.util.UUID.randomUUID(), de.mm20.launcher2.widgets.AppWidgetConfig(widgetId, 300))
            val store = WidgetGridStore(context)
            store.save(parent, GridProfile.Portrait, WidgetGridLayout(4).place("native", GridCell(0, 0, 2, 3)))
            store.save(parent, GridProfile.Landscape, WidgetGridLayout(8).place("native", GridCell(0, 0, 6, 5)))
            val configuration = mutableStateOf(Configuration(context.resources.configuration).apply { screenWidthDp = 400; screenHeightDp = 900 })
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                home.onActivity { activity -> activity.setContent { MaterialTheme {
                    de.mm20.launcher2.ui.base.ProvideCompositionLocals {
                        CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                ProfiledWidgetGrid(parent, listOf(GridTile("native", "Native", rows = 3) { modifier ->
                                    Box(modifier) { de.mm20.launcher2.ui.launcher.widgets.external.AppWidget(widget,
                                        gridMode = true, onWidgetUpdate = {}, onWidgetRemove = {}) }
                                }), editing = false)
                            }
                        }
                    }
                } } }
                compose.waitUntil(10000) { manager.getAppWidgetOptions(widgetId).getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) > 0 }
                val portrait = manager.getAppWidgetOptions(widgetId)
                compose.onNodeWithTag("test-tile-left").assertDoesNotExist()
                compose.runOnIdle { configuration.value = Configuration(configuration.value).apply { screenWidthDp = 900; screenHeightDp = 400 } }
                compose.waitUntil(10000) {
                    manager.getAppWidgetOptions(widgetId).getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) > portrait.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                }
                val landscape = manager.getAppWidgetOptions(widgetId)
                assertTrue(landscape.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) > portrait.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
            }
        } finally {
            host.deleteAppWidgetId(widgetId)
            if (granted) shell("appwidget revokebind --package ${context.packageName} --user 0")
            cleanup(parent)
        }
    }
    @Test fun separateProfilesRestoreAfterRotationUnfoldingAndRecreation() {
        val parent = "test-${java.util.UUID.randomUUID()}"
        val store = WidgetGridStore(context)
        val configuration = mutableStateOf(Configuration(context.resources.configuration).apply { screenWidthDp = 400; screenHeightDp = 900 })
        val editing = mutableStateOf(true)
        try {
            store.save(parent, GridProfile.Portrait, WidgetGridLayout(4).place("left", GridCell(0, 0, 2, 3)).place("right", GridCell(2, 0, 2, 3)))
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                fun show() { home.onActivity { activity -> activity.setContent {
                    MaterialTheme { CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            ProfiledWidgetGrid(parent, tiles, editing = editing.value, onDone = { editing.value = false })
                        }
                    } }
                } } }
                show()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("test-tile-left").fetchSemanticsNodes().isNotEmpty() }
                val left = compose.onNodeWithTag("test-tile-left").getUnclippedBoundsInRoot()
                val right = compose.onNodeWithTag("test-tile-right").getUnclippedBoundsInRoot()
                assertEquals(left.top, right.top); assertTrue(left.right <= right.left)
                editSize("left", "1", "4")
                compose.waitUntil(5000) { store.load(parent, GridProfile.Portrait).cells["left"]?.width == 1 }
                compose.onNodeWithTag("grid-resize-left").performClick()
                compose.onNodeWithText("Hide").performClick()
                compose.waitUntil(5000) { "left" in store.load(parent, GridProfile.Portrait).hidden }
                compose.onNodeWithText("Widgets").performScrollTo().performClick()
                compose.onNodeWithText("Show left").performClick()
                compose.waitUntil(5000) { "left" !in store.load(parent, GridProfile.Portrait).hidden }
                compose.onNodeWithTag("grid-item-left").performTouchInput { longClick() }
                compose.onNodeWithTag("grid-resize-left").performClick()
                compose.onNodeWithText("Hide").performClick()
                compose.waitUntil(5000) { "left" in store.load(parent, GridProfile.Portrait).hidden }
                compose.runOnIdle { configuration.value = Configuration(configuration.value).apply { screenWidthDp = 900; screenHeightDp = 400 } }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("widget-grid-Landscape").fetchSemanticsNodes().isNotEmpty() }
                editSize("left", "4", "2")
                compose.waitUntil(5000) { store.load(parent, GridProfile.Landscape).cells["left"]?.width == 4 }
                compose.runOnIdle { configuration.value = Configuration(configuration.value).apply { screenWidthDp = 700; screenHeightDp = 900 } }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("widget-grid-Unfolded").fetchSemanticsNodes().isNotEmpty() }
                editSize("left", "6", "4")
                compose.waitUntil(5000) { store.load(parent, GridProfile.Unfolded).cells["left"]?.width == 6 }
                compose.onNodeWithText("Done").performScrollTo().performClick()
                compose.runOnIdle { configuration.value = Configuration(configuration.value).apply { screenWidthDp = 400; screenHeightDp = 900 } }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("widget-grid-Portrait").fetchSemanticsNodes().isNotEmpty() }
                compose.runOnIdle { configuration.value = Configuration(configuration.value).apply { screenWidthDp = 900; screenHeightDp = 400 } }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("widget-grid-Landscape").fetchSemanticsNodes().isNotEmpty() }
                compose.runOnIdle { configuration.value = Configuration(configuration.value).apply { screenWidthDp = 700; screenHeightDp = 900 } }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("widget-grid-Unfolded").fetchSemanticsNodes().isNotEmpty() }
                home.recreate(); show()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("test-tile-left").fetchSemanticsNodes().isNotEmpty() }
                assertTrue("left" in store.load(parent, GridProfile.Portrait).hidden)
                assertFalse("left" in store.load(parent, GridProfile.Landscape).hidden)
                assertEquals(1, store.load(parent, GridProfile.Portrait).cells.getValue("left").width)
                assertEquals(4, store.load(parent, GridProfile.Landscape).cells.getValue("left").width)
                assertEquals(6, store.load(parent, GridProfile.Unfolded).cells.getValue("left").width)
            }
        } finally { cleanup(parent); GridEditing.active = false }
    }
    @Test fun draggingMoveAndResizePersistsTwoDimensionalGeometry() {
        val parent = "drag-${java.util.UUID.randomUUID()}"
        val store = WidgetGridStore(context)
        try {
            store.save(parent, GridProfile.Portrait, WidgetGridLayout(4).place("left", GridCell(0, 0, 2, 3)).place("right", GridCell(2, 0, 2, 3)))
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                home.onActivity { activity -> activity.setContent { MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ProfiledWidgetGrid(parent, tiles, editing = true) }
                } } }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("grid-item-left").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("grid-item-left").performTouchInput { longClick() }
                compose.onNodeWithText("Grid 4 cols").assertDoesNotExist()
                compose.onNodeWithTag("grid-move-left").assertIsDisplayed()
                val root = compose.onNodeWithTag("widget-grid-Portrait").getUnclippedBoundsInRoot()
                val density = context.resources.displayMetrics.density
                val dx = (root.right - root.left).value * density / 4
                val dy = 56 * density
                compose.onNodeWithTag("grid-move-left").performTouchInput { down(center); moveBy(Offset(dx, dy), 400); up() }
                compose.waitUntil(5000) { store.load(parent, GridProfile.Portrait).cells["left"]?.x == 1 }
                assertEquals(1, store.load(parent, GridProfile.Portrait).cells.getValue("left").y)
                compose.onNodeWithTag("grid-resize-left").performScrollTo().performTouchInput { down(center); moveBy(Offset(dx, dy), 400); up() }
                compose.waitUntil(5000) { store.load(parent, GridProfile.Portrait).cells["left"]?.width == 3 }
                assertEquals(4, store.load(parent, GridProfile.Portrait).cells.getValue("left").height)
                compose.onNodeWithTag("grid-resize-left-tl").performScrollTo().performTouchInput {
                    down(center); moveBy(Offset(dx / 2, dy / 2), 150); moveBy(Offset(dx / 2, dy / 2), 150); up()
                }
                compose.waitUntil(5000) { store.load(parent, GridProfile.Portrait).cells["left"] == GridCell(2, 2, 2, 3) }
            }
        } finally { cleanup(parent); GridEditing.active = false }
    }
}
