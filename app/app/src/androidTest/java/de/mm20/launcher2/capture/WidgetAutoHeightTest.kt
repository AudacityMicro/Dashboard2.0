package de.mm20.launcher2.capture

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.dashboard.*
import de.mm20.launcher2.preferences.ScreenOrientation
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.dashboard.GridEditing
import de.mm20.launcher2.ui.launcher.LauncherActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import kotlin.math.abs

class WidgetAutoHeightTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun settingsRoundTripWithLegacyDefaultsAndIndependentProfiles() {
        val parent = "auto-storage-${UUID.randomUUID()}"
        val store = WidgetGridStore(context)
        try {
            val base = WidgetGridLayout(4).place("tasks", GridCell(0, 0, 4, 5))
            store.save(parent, GridProfile.Portrait, base)
            assertTrue(store.load(parent, GridProfile.Portrait).autoHeights.isEmpty())
            val automatic = base.copy(autoHeights = mapOf("tasks" to GridAutoHeight(true, 7)))
            store.save(parent, GridProfile.Portrait, automatic)
            store.save(parent, GridProfile.Landscape, base.copy(autoHeights = mapOf("tasks" to GridAutoHeight(false, 3))))
            assertEquals(automatic, store.load(parent, GridProfile.Portrait))
            assertEquals(GridAutoHeight(false, 3), store.load(parent, GridProfile.Landscape).autoHeights["tasks"])
            val reflowed = automatic.withColumns(2)
            store.save(parent, GridProfile.Unfolded, reflowed)
            assertEquals(reflowed, store.load(parent, GridProfile.Unfolded))
            // Original v1 entries have no automatic sizing field and remain fixed.
            DashboardStore(context).use { it.setMeta("grid.v1.$parent.Portrait",
                """{"version":1,"columns":4,"rowDp":56,"items":[{"id":"tasks","x":0,"y":0,"w":4,"h":5}]}""") }
            assertEquals(base, store.load(parent, GridProfile.Portrait))
        } finally { DashboardStore(context).use { it.writableDatabase.delete("meta", "key LIKE ?", arrayOf("grid.v1.$parent.%")) } }
    }

    @Test fun dashboardAndTasksGrowShrinkCapAndReturnToFixedHeightThroughEditor() {
        val store = WidgetGridStore(context)
        val ui = org.koin.core.context.GlobalContext.get().get<UiSettings>()
        val orientation = runBlocking { ui.orientation.first() }
        val homeWidgets = runBlocking { ui.homeScreenWidgets.first() }
        val keys = listOf("board", "setupHidden") + GridProfile.entries.map { "grid.v1.home.${it.name}" }
        val before = DashboardStore(context).use { db -> keys.associateWith { db.meta(it) } }
        val prefix = UUID.randomUUID().toString()
        fun board(large: Boolean) {
            val update = JSONObject().put("id", "$prefix-update").put("title", "Today's plan")
                .put("body", if (large) "Details for the day.\n".repeat(40) else "A short reminder.")
            val tasks = JSONArray()
            repeat(if (large) 8 else 1) { index -> tasks.put(JSONObject().put("id", "$prefix-task-$index")
                .put("title", "Task ${index + 1}").put("completed", false)) }
            DashboardStore(context).use { it.acceptBoard(JSONObject().put("schemaVersion", 1)
                .put("updates", JSONArray().put(update)).put("tasks", tasks).toString()) }
        }
        fun bounds(id: String) = compose.onNodeWithTag("grid-item-$id").getUnclippedBoundsInRoot()
        fun height(id: String) = bounds(id).let { it.bottom.value - it.top.value + 8f }
        fun waitRows(id: String, rows: Int) { compose.waitUntil(15000) {
            compose.onAllNodesWithTag("grid-item-$id").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() && abs(height(id) - rows * 56f) < 1f
        } }
        fun edit(id: String, enabled: Boolean, maximum: String? = null, fixed: String? = null) {
            compose.onNodeWithTag("grid-item-$id").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithTag("grid-resize-$id").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("grid-auto-height").performScrollTo().assertExists()
            val current = store.load("home", GridProfile.Portrait).autoHeights[id]?.enabled == true
            if (current != enabled) compose.onNodeWithTag("grid-auto-height").performSemanticsAction(SemanticsActions.OnClick) { it() }
            if (maximum != null) {
                compose.onNodeWithTag("grid-auto-max").performScrollTo().performTextReplacement("0")
                compose.onNodeWithText("Save layout").assertIsNotEnabled()
                compose.onNodeWithTag("grid-auto-max").performTextReplacement(maximum)
            }
            if (fixed != null) compose.onNodeWithTag("grid-edit-h").performScrollTo().performTextReplacement(fixed)
            compose.onNodeWithText("Save layout").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5000) { store.load("home", GridProfile.Portrait).autoHeights[id]?.enabled == enabled }
        }
        fun done() { compose.onNodeWithText("Done").performSemanticsAction(SemanticsActions.OnClick) { it() }; compose.waitForIdle() }
        try {
            ui.setOrientation(ScreenOrientation.Portrait); ui.setHomeScreenWidgets(false)
            runBlocking { ui.orientation.first { it == ScreenOrientation.Portrait }; ui.homeScreenWidgets.first { !it } }
            GridProfile.entries.forEach { profile -> store.save("home", profile, WidgetGridLayout(4)
                .place("updates", GridCell(0, 0, 4, 4)).place("tasks", GridCell(0, 4, 4, 5))
                .place("setup", GridCell(0, 40, 4, 8)).place("connection", GridCell(0, 48, 4, 2))
                .copy(hidden = setOf("setup", "connection"))) }
            DashboardStore(context).use { it.setMeta("setupHidden", "true") }; board(false)
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                waitRows("updates", 4); waitRows("tasks", 5)
                edit("updates", true, maximum = "3")
                edit("tasks", true, maximum = "4"); done()
                waitRows("updates", 2); waitRows("tasks", 2)
                // Editing a shifted widget's maximum must not bake the temporary shift into storage.
                assertEquals(0, store.load("home", GridProfile.Portrait).cells.getValue("updates").y)
                assertEquals(4, store.load("home", GridProfile.Portrait).cells.getValue("tasks").y)
                val shortTaskTop = bounds("tasks").top
                board(true)
                waitRows("updates", 3); waitRows("tasks", 4)
                assertTrue(bounds("updates").bottom <= bounds("tasks").top)
                assertTrue(bounds("tasks").top > shortTaskTop)
                compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("agent-updates")))
                    .performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 300f)) }
                waitRows("updates", 3)
                board(false)
                waitRows("updates", 2); waitRows("tasks", 2)
                assertEquals(shortTaskTop, bounds("tasks").top)
                edit("updates", false, fixed = "4"); done()
                board(true); waitRows("tasks", 4); waitRows("updates", 4)
                home.recreate(); waitRows("updates", 4); waitRows("tasks", 4)
                assertEquals(GridAutoHeight(false, 3), store.load("home", GridProfile.Portrait).autoHeights["updates"])
                assertEquals(GridAutoHeight(true, 4), store.load("home", GridProfile.Portrait).autoHeights["tasks"])
                assertTrue(store.load("home", GridProfile.Landscape).autoHeights.isEmpty())
            }
        } finally {
            GridEditing.active = false
            ui.setOrientation(orientation); ui.setHomeScreenWidgets(homeWidgets)
            DashboardStore(context).use { db -> before.forEach { (key, value) -> db.setMeta(key, value) } }
        }
    }
}
