package de.mm20.launcher2.ui.launcher.widgets

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.ui.base.LocalAppWidgetHost
import de.mm20.launcher2.ui.dashboard.GridTile
import de.mm20.launcher2.ui.launcher.sheets.WidgetPickerSheet
import de.mm20.launcher2.widgets.AppWidget
import de.mm20.launcher2.widgets.AppsWidget
import de.mm20.launcher2.widgets.Widget
import kotlin.math.ceil

data class NativeGridWidgets(val tiles: List<GridTile>, val add: () -> Unit, val loaded: Boolean)

@Composable
fun rememberNativeGridWidgets(parentId: String, editing: Boolean, onWidgetAdded: () -> Unit = {}): NativeGridWidgets {
    val model: WidgetsVM = viewModel(key = "widgets-column-$parentId", factory = WidgetsVM.Factory(parentId))
    val widgets by model.widgets.collectAsState()
    val context = LocalContext.current
    val host = LocalAppWidgetHost.current
    var picker by remember { mutableStateOf(false) }
    var configuring by remember { mutableStateOf<Widget?>(null) }
    configuring?.let { widget ->
        de.mm20.launcher2.ui.launcher.sheets.ConfigureWidgetSheet(expanded = true, widget = widget,
            gridManaged = true, onWidgetUpdated = model::updateWidget, onDismiss = { configuring = null })
    }
    var removing by remember { mutableStateOf<Widget?>(null) }
    WidgetPickerSheet(expanded = picker, onDismiss = { picker = false }, onWidgetSelected = {
        model.addWidget(it); picker = false; onWidgetAdded()
    })
    removing?.let { widget -> AlertDialog(onDismissRequest = { removing = null },
        title = { Text("Remove widget from every layout?") },
        text = { Text("Use Hide in the grid editor to hide it only in the current screen layout.") },
        confirmButton = { TextButton(onClick = {
            model.removeWidget(widget)
            if (widget is AppWidget) host.deleteAppWidgetId(widget.config.widgetId)
            removing = null
        }) { Text("Remove everywhere") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
    return NativeGridWidgets(widgets.orEmpty().map { widget ->
        val iconsOnly = widget is AppsWidget && widget.config.favoritesOnly
        val rows = when {
            widget is AppWidget -> ceil(widget.config.height / 56.0).toInt().coerceIn(2, 32)
            iconsOnly -> 3
            else -> 5
        }
        GridTile("widget-${widget.id}", widget.getLabel(context), rows = rows,
            onConfigure = if (iconsOnly) null else ({ configuring = widget }), onRemove = { removing = widget }) { modifier ->
            WidgetItem(widget = widget, modifier = modifier, editMode = false, gridMode = true,
                onWidgetAdd = { added, _ -> model.addWidget(added) }, onWidgetUpdate = model::updateWidget,
                onWidgetRemove = { removing = widget })
        }
    }, add = { picker = true }, loaded = widgets != null)
}
