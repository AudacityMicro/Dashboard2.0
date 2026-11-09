package de.mm20.launcher2.widgets

import android.content.Context
import de.mm20.launcher2.database.entities.PartialWidgetEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class FavoritesWidgetConfig(
    val customTags: Boolean = false,
    val editButton: Boolean = true,
    val tagsMultiline: Boolean = false,
    val compactTags: Boolean = false,
    val tagList: List<String> = emptyList(),
    val skipRows: Int = 0,
    val favoritesOnly: Boolean = false,
    val selectedOnly: Boolean = false,
    val selectedAppKeys: List<String> = emptyList(),
)

data class AppsWidget(
    override val id: UUID,
    val config: FavoritesWidgetConfig = FavoritesWidgetConfig(),
) : Widget() {

    override fun toDatabaseEntity(): PartialWidgetEntity {
        return PartialWidgetEntity(
            id = id,
            type = Type,
            config = Json.encodeToString(config),
        )
    }

    override fun getLabel(context: Context): String {
        return context.getString(when {
            config.selectedOnly -> R.string.widget_name_selected_apps
            config.favoritesOnly -> R.string.widget_name_favorite_apps
            else -> R.string.widget_name_apps
        })
    }

    companion object {
        const val Type = "favorites"
        // Picker aliases retain the existing database/backup widget type.
        const val FavoritesOnlyType = "favorite_apps"
        const val SelectedAppsType = "selected_apps"
        fun favoritesOnly(id: UUID) = AppsWidget(id, FavoritesWidgetConfig(favoritesOnly = true, editButton = false))
        fun selectedApps(id: UUID) = AppsWidget(id, FavoritesWidgetConfig(selectedOnly = true, editButton = false))
    }
}
