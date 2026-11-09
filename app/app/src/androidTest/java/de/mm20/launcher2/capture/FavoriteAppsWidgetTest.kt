package de.mm20.launcher2.capture

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import de.mm20.launcher2.dashboard.*
import de.mm20.launcher2.ui.dashboard.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.SavedSearchableEntity
import de.mm20.launcher2.preferences.ui.GridSettings
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.search.Tag
import de.mm20.launcher2.ui.base.ProvideCompositionLocals
import de.mm20.launcher2.ui.launcher.LauncherActivity
import de.mm20.launcher2.ui.launcher.sheets.WidgetPickerSheet
import de.mm20.launcher2.ui.launcher.widgets.WidgetItem
import de.mm20.launcher2.ui.launcher.widgets.favorites.FavoriteAppsWidgetVM
import de.mm20.launcher2.ui.locals.LocalGridSettings
import de.mm20.launcher2.widgets.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FavoriteAppsWidgetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val koin get() = org.koin.core.context.GlobalContext.get()
    private val database get() = koin.get<AppDatabase>()
    private fun row(item: SavableSearchable, pin: Int, launches: Int = 0, hidden: Int = 0) = SavedSearchableEntity(
        item.key, item.domain, requireNotNull(item.getSerializer().serialize(item)), launches, pin, hidden, 1.0)
    private fun fixture(block: (List<Application>) -> Unit) {
        val apps = runBlocking { withTimeout(10000) { koin.get<AppRepository>().findMany().first { list ->
            list.size >= 4 && list.any { it.componentName.packageName == "com.android.settings" }
        } } }
        val settings = apps.first { it.componentName.packageName == "com.android.settings" }
        val selected = listOf(settings) + apps.filter { it.key != settings.key }.take(3)
        val dao = database.searchableDao()
        val backup = database.backupDao()
        val before = runBlocking { backup.exportFavorites(Int.MAX_VALUE, 0) }
        runBlocking { database.withWriteTransaction {
            backup.wipeFavorites()
            dao.upsert(row(selected[0], 10)); dao.upsert(row(selected[1], 9))
            dao.upsert(row(selected[2], 0, launches = 500))
            dao.upsert(row(selected[3], 8, hidden = 2))
            dao.upsert(row(Tag("Pinned tag fixture"), 11))
        } }
        try { block(selected) } finally { runBlocking { database.withWriteTransaction {
            backup.wipeFavorites(); backup.importFavorites(before)
        } } }
    }
    @Test fun pinnedAppsKeepTheirOrderAndReactToUnpinAndHide() = fixture { apps ->
        val model = FavoriteAppsWidgetVM()
        val initial = runBlocking { withTimeout(10000) { model.favorites.first { it.size == 2 } } }
        assertEquals(listOf(apps[0].key, apps[1].key), initial.map { it.key })
        runBlocking { database.searchableDao().upsert(row(apps[0], 0, launches = 1000)) }
        val unpinned = runBlocking { withTimeout(10000) { model.favorites.first { it.size == 1 } } }
        assertEquals(apps[1].key, unpinned.single().key)
        runBlocking { database.searchableDao().upsert(row(apps[1], 9, hidden = 2)) }
        runBlocking { withTimeout(10000) { model.favorites.first { it.isEmpty() } } }
    }
    @Test fun pickerCreatesPersistentIconOnlyWidgetThatWrapsAndLaunches() = fixture { apps ->
        val repository = koin.get<WidgetRepository>()
        val parent = UUID.randomUUID()
        val gridParent = "favorite-apps-test-$parent"
        GridProfile.entries.forEach { mode -> WidgetGridStore(InstrumentationRegistry.getInstrumentation().targetContext)
            .save(gridParent, mode, WidgetGridLayout(mode.columns).place("apps", GridCell(0,0,mode.columns,4))) }
        val chosen = mutableStateOf<AppsWidget?>(null)
        val width = mutableIntStateOf(360)
        val picker = mutableStateOf(true)
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                fun show() { home.onActivity { activity -> activity.setContent { MaterialTheme { ProvideCompositionLocals {
                    CompositionLocalProvider(LocalGridSettings provides GridSettings(showLabels = true)) {
                        de.mm20.launcher2.ui.overlays.OverlayHost(Modifier.fillMaxSize().padding(top = 48.dp)) {
                            chosen.value?.let { widget ->
                                Column(Modifier.width(width.intValue.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                    ProfiledWidgetGrid(gridParent, listOf(GridTile("apps", "Favorite apps", rows = 4) { modifier ->
                                        WidgetItem(widget, modifier, gridMode = true)
                                    }))
                                }
                            }
                            WidgetPickerSheet(picker.value, onWidgetSelected = { widget ->
                                chosen.value = widget as AppsWidget; repository.create(widget, 0, parent)
                            }, onDismiss = { picker.value = false })
                        }
                    }
                } } } } }
                show()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("widget-picker-list").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("widget-picker-list").performScrollToNode(hasText("Favorite apps"))
                compose.onNodeWithText("Favorite apps").performClick()
                compose.waitUntil(5000) { chosen.value != null }
                val widget = requireNotNull(chosen.value)
                assertTrue(widget.config.favoritesOnly)
                val restored = runBlocking { withTimeout(10000) { repository.get(parent).first { it.isNotEmpty() }.single() } }
                assertEquals(widget, restored)
                val tag = "favorite-apps-${widget.id}"
                fun icon(app: Application) = compose.onNode(hasContentDescription(app.label) and hasAnyAncestor(hasTestTag(tag)))
                compose.waitUntil(10000) { compose.onAllNodes(hasContentDescription(apps[0].label) and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(0)
                icon(apps[2]).assertDoesNotExist(); icon(apps[3]).assertDoesNotExist()
                val first = icon(apps[0]).getUnclippedBoundsInRoot()
                val second = icon(apps[1]).getUnclippedBoundsInRoot()
                assertEquals(first.top, second.top)
                compose.runOnIdle { width.intValue = 100 }
                compose.waitForIdle()
                assertTrue(icon(apps[1]).getUnclippedBoundsInRoot().top > icon(apps[0]).getUnclippedBoundsInRoot().top)
                home.recreate(); show()
                compose.waitUntil(10000) { compose.onAllNodes(hasContentDescription(apps[0].label) and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty() }
                icon(apps[0]).assertExists()
                compose.runOnIdle { width.intValue = 360 }
                compose.waitForIdle()
                icon(apps[0]).performTouchInput { longClick() }
                compose.onNodeWithTag("grid-move-apps").assertExists()
                compose.onAllNodesWithText(apps[0].label).assertCountEquals(0)
                compose.onNodeWithText("Done").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
                compose.waitForIdle()
                compose.onNodeWithTag("grid-move-apps").assertDoesNotExist()
                icon(apps[0]).performClick()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                fun resumedSettings(): Boolean = instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities").use { fd ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8) }
                }.lineSequence().any { it.contains("Resumed", ignoreCase = true) && it.contains("com.android.settings") }
                compose.waitUntil(10000) { resumedSettings() }
                runBlocking { withTimeout(10000) { database.searchableDao().getByKey(apps[0].key).first { (it?.launchCount ?: 0) > 0 } } }
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
        } finally {
            GridEditing.active = false
            chosen.value?.let { repository.delete(it) }
            DashboardStore(InstrumentationRegistry.getInstrumentation().targetContext).use { db ->
                db.writableDatabase.delete("meta", "key LIKE ?", arrayOf("grid.v1.$gridParent.%"))
            }
        }
    }
}
