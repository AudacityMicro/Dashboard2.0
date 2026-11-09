package de.mm20.launcher2.capture

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
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
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.ui.base.ProvideCompositionLocals
import de.mm20.launcher2.ui.launcher.LauncherActivity
import de.mm20.launcher2.ui.launcher.sheets.WidgetPickerSheet
import de.mm20.launcher2.ui.launcher.widgets.WidgetItem
import de.mm20.launcher2.ui.launcher.widgets.favorites.SelectedAppsWidgetVM
import de.mm20.launcher2.widgets.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class SelectedAppsWidgetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val koin get() = org.koin.core.context.GlobalContext.get()
    private val database get() = koin.get<AppDatabase>()
    private fun installed() = runBlocking { withTimeout(10000) {
        koin.get<AppRepository>().findMany().first { it.size >= 4 }
    } }

    @Test fun chooserTracksVisibilityWithoutRequiringFavorites() {
        val apps = installed().take(2)
        val backup = database.backupDao()
        val before = runBlocking { backup.exportFavorites(Int.MAX_VALUE, 0) }
        try {
            runBlocking { backup.wipeFavorites() }
            val model = SelectedAppsWidgetVM()
            runBlocking { withTimeout(10000) { model.apps.first { list -> apps.all { app -> list.any { it.key == app.key } } } } }
            fun hide(hidden: Int) = runBlocking { database.searchableDao().upsert(SavedSearchableEntity(
                apps[0].key, "app", requireNotNull(apps[0].getSerializer().serialize(apps[0])), 0, 0, hidden, 1.0)) }
            hide(2)
            runBlocking { withTimeout(10000) { model.apps.first { list -> list.isNotEmpty() && list.none { it.key == apps[0].key } } } }
            hide(0)
            runBlocking { withTimeout(10000) { model.apps.first { list -> list.any { it.key == apps[0].key } } } }
        } finally { runBlocking { database.withWriteTransaction {
            backup.wipeFavorites(); backup.importFavorites(before)
        } } }
    }

    @Test fun pickerSavesPerWidgetSelectionAndCancelAndRecreationKeepIt() {
        val apps = installed().take(2)
        val backup = database.backupDao()
        val before = runBlocking { backup.exportFavorites(Int.MAX_VALUE, 0) }
        runBlocking { backup.wipeFavorites() }
        val repository = koin.get<WidgetRepository>()
        val parent = UUID.randomUUID()
        val chosen = mutableStateOf<AppsWidget?>(null)
        val other = AppsWidget.selectedApps(UUID.randomUUID())
        repository.create(other, 1, parent)
        val picker = mutableStateOf(true)
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                fun show() { home.onActivity { activity -> activity.setContent {
                    MaterialTheme { ProvideCompositionLocals {
                        de.mm20.launcher2.ui.overlays.OverlayHost(Modifier.fillMaxSize().padding(top = 48.dp)) {
                            chosen.value?.let { widget ->
                                Box(Modifier.width(360.dp).height(300.dp)) {
                                    WidgetItem(widget, Modifier.fillMaxSize(), gridMode = true,
                                        onWidgetUpdate = { updated ->
                                            chosen.value = updated as AppsWidget; repository.update(updated)
                                        })
                                }
                            }
                            WidgetPickerSheet(picker.value, onWidgetSelected = { widget ->
                                chosen.value = widget as AppsWidget; repository.create(widget, 0, parent)
                            }, onDismiss = { picker.value = false })
                        }
                    } }
                } } }
                show()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("widget-picker-list").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("widget-picker-list").performScrollToNode(hasText("Selected apps"))
                compose.onNodeWithText("Selected apps").performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.waitUntil(5000) { chosen.value != null }
                val widget = requireNotNull(chosen.value)
                val tag = "selected-apps-${widget.id}"
                fun choose() = compose.onNodeWithTag("selected-apps-choose-${widget.id}").performClick()
                fun toggle(app: Application) {
                    compose.onNodeWithTag("selected-apps-choices").performScrollToNode(hasTestTag("selected-app-choice-${app.key}"))
                    compose.onNodeWithTag("selected-app-choice-${app.key}").performClick()
                }
                fun save() = compose.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick) { it() }
                fun icon(app: Application) = compose.onNode(hasContentDescription(app.label) and hasAnyAncestor(hasTestTag(tag)))
                fun stored() = runBlocking { withTimeout(10000) { repository.get(parent).first { widgets ->
                    (widgets.firstOrNull { it.id == widget.id } as? AppsWidget)?.config?.selectedAppKeys == chosen.value!!.config.selectedAppKeys
                } } }
                choose(); toggle(apps[0]); save()
                icon(apps[0]).assertExists(); icon(apps[1]).assertDoesNotExist()
                val saved = stored()
                assertEquals(listOf(apps[0].key), (saved.first { it.id == widget.id } as AppsWidget).config.selectedAppKeys)
                assertTrue((saved.first { it.id == other.id } as AppsWidget).config.selectedAppKeys.isEmpty())
                compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(0)
                choose(); toggle(apps[1])
                home.recreate(); show()
                compose.onNodeWithText("Choose apps").assertExists()
                compose.onNodeWithTag("selected-apps-choices").performScrollToNode(hasTestTag("selected-app-choice-${apps[1].key}"))
                compose.onNodeWithTag("selected-app-choice-${apps[1].key}").assertIsOn()
                compose.onNodeWithText("Cancel").performClick()
                icon(apps[1]).assertDoesNotExist()
                choose(); toggle(apps[1]); save()
                assertEquals(listOf(apps[0].key, apps[1].key), (stored().first { it.id == widget.id } as AppsWidget).config.selectedAppKeys)
                // Reload from the real widget database rather than retaining an in-memory fixture.
                compose.runOnIdle { chosen.value = stored().first { it.id == widget.id } as AppsWidget }
                home.recreate(); show()
                icon(apps[0]).assertExists(); icon(apps[1]).assertExists()
                choose(); toggle(apps[0]); toggle(apps[1]); save()
                stored()
                icon(apps[0]).assertDoesNotExist(); icon(apps[1]).assertDoesNotExist()
                compose.onNodeWithTag("selected-apps-choose-${widget.id}").assertExists()
                // Selecting apps never pins them or changes the launcher's favorites database.
                assertTrue(runBlocking { backup.exportFavorites(Int.MAX_VALUE, 0) }.isEmpty())
            }
        } finally {
            chosen.value?.let(repository::delete); repository.delete(other)
            runBlocking { database.withWriteTransaction { backup.wipeFavorites(); backup.importFavorites(before) } }
        }
    }
}
