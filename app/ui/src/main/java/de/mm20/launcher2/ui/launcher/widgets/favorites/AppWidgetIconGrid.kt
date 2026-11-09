package de.mm20.launcher2.ui.launcher.widgets.favorites

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.ui.launcher.search.common.grid.SearchResultGrid
import de.mm20.launcher2.ui.locals.LocalAppGridWidth
import de.mm20.launcher2.ui.locals.LocalGridSettings
import kotlin.math.roundToInt

/** Keep the configured app-grid density when a widget occupies only part of the canvas. */
@Composable
internal fun AppWidgetIconGrid(apps: List<Application>, modifier: Modifier = Modifier) {
    val grid = LocalGridSettings.current
    val canvasWidth = LocalAppGridWidth.current
    BoxWithConstraints(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        val configuredColumns = grid.columnCount.coerceAtLeast(1)
        val columns = if (canvasWidth.value.isFinite() && canvasWidth.value > 0 && maxWidth.value.isFinite()) {
            (configuredColumns * maxWidth.value / canvasWidth.value).roundToInt()
                .coerceIn(1, configuredColumns)
        } else configuredColumns
        // Icon size comes directly from GridItem's LocalGridSettings. Changing it must not
        // silently change the number of columns selected by the user.
        if (apps.isNotEmpty()) SearchResultGrid(apps, showLabels = false, columns = columns,
            allowLongClick = false)
    }
}
