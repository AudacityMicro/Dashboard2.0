package de.mm20.launcher2.capture

import de.mm20.launcher2.database.entities.WidgetEntity
import de.mm20.launcher2.widgets.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FavoriteAppsWidgetConfigTest {
    @Test fun existingAppsWidgetsRetainTheirBehavior() {
        val old = Json.decodeFromString<FavoritesWidgetConfig>("""{"customTags":true,"tagList":["Work"],"skipRows":2}""")
        assertFalse(old.favoritesOnly)
        assertFalse(old.selectedOnly)
        assertTrue(old.selectedAppKeys.isEmpty())
        assertTrue(old.customTags); assertTrue(old.editButton)
        assertEquals(2, old.skipRows)
    }
    @Test fun selectedAppsRoundTripSeparatelyWithoutChangingOtherWidgets() {
        val first = AppsWidget.selectedApps(UUID.randomUUID()).let {
            it.copy(config = it.config.copy(selectedAppKeys = listOf("app:work:one", "app:personal:one")))
        }
        val second = AppsWidget.selectedApps(UUID.randomUUID())
        val entity = first.toDatabaseEntity()
        val restored = Widget.fromDatabaseEntity(WidgetEntity(entity.type, entity.config, 2, first.id)) as AppsWidget
        assertEquals(first, restored)
        assertTrue(restored.config.selectedOnly)
        assertFalse(restored.config.favoritesOnly)
        assertFalse(restored.config.editButton)
        assertTrue(second.config.selectedAppKeys.isEmpty())
        assertFalse(AppsWidget.favoritesOnly(UUID.randomUUID()).config.selectedOnly)
    }
    @Test fun favoriteAppsVariantSurvivesDatabaseAndBackupSerialization() {
        val widget = AppsWidget.favoritesOnly(UUID.randomUUID())
        val entity = widget.toDatabaseEntity()
        val restored = Widget.fromDatabaseEntity(WidgetEntity(entity.type, entity.config, 2, widget.id)) as AppsWidget
        assertEquals(widget, restored)
        assertTrue(restored.config.favoritesOnly)
        assertFalse(restored.config.editButton)
        assertEquals(AppsWidget.Type, entity.type)
    }
}
