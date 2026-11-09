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
        assertTrue(old.customTags); assertTrue(old.editButton)
        assertEquals(2, old.skipRows)
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
