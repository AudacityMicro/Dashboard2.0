package de.mm20.launcher2.capture

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.dashboard.*
import de.mm20.launcher2.database.AppDatabase
import de.mm20.launcher2.database.entities.SavedSearchableEntity
import de.mm20.launcher2.preferences.ui.UiSettings
import de.mm20.launcher2.ui.base.ProvideCompositionLocals
import de.mm20.launcher2.ui.dashboard.*
import de.mm20.launcher2.ui.launcher.LauncherActivity
import de.mm20.launcher2.ui.launcher.widgets.WidgetItem
import de.mm20.launcher2.widgets.AppsWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AppWidgetGridSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun bothWidgetsFollowLiveColumnAndIconSettingsAndRetainDensityWhenResized() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = org.koin.core.context.GlobalContext.get()
        val settings = koin.get<UiSettings>()
        val original = runBlocking { settings.gridSettings.first() }
        val database = koin.get<AppDatabase>()
        val backup = database.backupDao()
        val before = runBlocking { backup.exportFavorites(Int.MAX_VALUE, 0) }
        val apps = runBlocking { withTimeout(10000) {
            koin.get<AppRepository>().findMany().first { it.distinctBy { app -> app.label }.size >= 6 }
                .distinctBy { it.label }.take(6)
        } }
        val favorite = AppsWidget.favoritesOnly(UUID.randomUUID())
        val selected = AppsWidget.selectedApps(UUID.randomUUID()).let {
            it.copy(config = it.config.copy(selectedAppKeys = apps.map { app -> app.key }))
        }
        val tags = listOf("favorite-apps-${favorite.id}", "selected-apps-${selected.id}")
        val parent = "app-settings-test-${UUID.randomUUID()}"
        val store = WidgetGridStore(context)
        fun layout(half: Boolean = false) { GridProfile.entries.forEach { profile ->
            store.save(parent, profile, WidgetGridLayout(6)
                .place("favorite", GridCell(0, 0, if (half) 3 else 6, 4))
                .place("selected", GridCell(0, 4, if (half) 3 else 6, 4)))
        } }
        fun change(columns: Int, size: Int) {
            settings.setGridColumnCount(columns); settings.setGridIconSize(size)
            runBlocking { withTimeout(10000) { settings.gridSettings.first { it.columnCount == columns && it.iconSize == size } } }
        }
        fun icon(tag: String, index: Int, unmerged: Boolean = false) = compose.onNode(
            hasContentDescription(apps[index].label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = unmerged)
        fun expectColumns(columns: Int) {
            tags.forEach { tag ->
                compose.waitUntil(10000) {
                    val nodes = compose.onAllNodes(hasContentDescription(apps[5].label) and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes()
                    nodes.size == 1 &&
                        (icon(tag, 5).getUnclippedBoundsInRoot().top == icon(tag, 0).getUnclippedBoundsInRoot().top) == (columns == 6)
                }
            }
        }
        try {
            runBlocking { database.withWriteTransaction {
                backup.wipeFavorites()
                apps.forEachIndexed { index, app -> database.searchableDao().upsert(SavedSearchableEntity(
                    app.key, "app", requireNotNull(app.getSerializer().serialize(app)), 0, 100 - index, 0, 1.0)) }
            } }
            change(3, 32); layout()
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                fun show() { home.onActivity { activity -> activity.setContent {
                    MaterialTheme { ProvideCompositionLocals {
                        de.mm20.launcher2.ui.overlays.OverlayHost(Modifier.fillMaxSize().padding(top = 48.dp)) {
                            Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                ProfiledWidgetGrid(parent, listOf(
                                    GridTile("favorite", "Favorite apps", rows = 4) { WidgetItem(favorite, it, gridMode = true) },
                                    GridTile("selected", "Selected apps", rows = 4) { WidgetItem(selected, it, gridMode = true) }))
                            }
                        }
                    } }
                } } }
                show(); expectColumns(3)
                val small = tags.map { icon(it, 0, unmerged = true).getUnclippedBoundsInRoot().let { it.right - it.left } }
                small.forEach { assertEquals(40f, it.value, 1f) } // 32dp icon and its normal 4dp padding.
                change(6, 32); expectColumns(6)
                change(6, 48)
                tags.forEach { tag -> compose.waitUntil(10000) {
                    kotlin.math.abs(icon(tag, 0, unmerged = true).getUnclippedBoundsInRoot().let { it.right - it.left }.value - 56f) < 1f
                } }
                expectColumns(6) // The old icon-size guess silently reduced this to five columns.
                change(3, 64); expectColumns(3)
                tags.forEach { tag -> compose.waitUntil(10000) {
                    kotlin.math.abs(icon(tag, 0, unmerged = true).getUnclippedBoundsInRoot().let { it.right - it.left }.value - 72f) < 1f
                } }
                change(6, 32); expectColumns(6)
                layout(half = true); home.recreate(); show(); expectColumns(3)
                tags.forEach { tag ->
                    assertEquals(40f, icon(tag, 0, unmerged = true).getUnclippedBoundsInRoot().let { it.right - it.left }.value, 1f)
                    assertEquals(icon(tag, 0).getUnclippedBoundsInRoot().top, icon(tag, 2).getUnclippedBoundsInRoot().top)
                    assertTrue(icon(tag, 3).getUnclippedBoundsInRoot().top > icon(tag, 0).getUnclippedBoundsInRoot().top)
                }
            }
        } finally {
            change(original.columnCount, original.iconSize)
            runBlocking { database.withWriteTransaction { backup.wipeFavorites(); backup.importFavorites(before) } }
            DashboardStore(context).use { it.writableDatabase.delete("meta", "key LIKE ?", arrayOf("grid.v1.$parent.%")) }
        }
    }
}
