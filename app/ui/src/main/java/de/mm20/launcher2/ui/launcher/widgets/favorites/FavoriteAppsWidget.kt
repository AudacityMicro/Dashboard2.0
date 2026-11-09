package de.mm20.launcher2.ui.launcher.widgets.favorites

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.data.customattrs.utils.withCustomLabels
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.searchable.PinnedLevel
import de.mm20.launcher2.services.favorites.FavoritesService
import de.mm20.launcher2.widgets.AppsWidget
import kotlinx.coroutines.flow.map
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class FavoriteAppsWidgetVM : ViewModel(), KoinComponent {
    private val service: FavoritesService by inject()
    private val attributes: CustomAttributesRepository by inject()
    // Use the launcher's own pinned ordering/visibility pipeline, excluding usage suggestions and tags.
    val favorites = service.getFavorites(includeTypes = listOf("app"),
        minPinnedLevel = PinnedLevel.AutomaticallySorted, limit = Int.MAX_VALUE)
        .withCustomLabels(attributes).map { it.filterIsInstance<Application>() }
}

@Composable
fun FavoriteAppsWidget(widget: AppsWidget) {
    val model: FavoriteAppsWidgetVM = viewModel(key = "favorite-apps-${widget.id}")
    val favorites by model.favorites.collectAsStateWithLifecycle(emptyList())
    AppWidgetIconGrid(favorites, Modifier.fillMaxSize().testTag("favorite-apps-${widget.id}"))
}
