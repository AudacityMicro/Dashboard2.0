package de.mm20.launcher2.ui.launcher.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.mm20.launcher2.ui.dashboard.GridEditing
import de.mm20.launcher2.ui.dashboard.ProfiledWidgetGrid

/** Existing widget screens now use the same two-dimensional, profile-specific grid. */
@Composable
fun WidgetColumn(modifier: Modifier = Modifier, editMode: Boolean = false,
    onEditModeChange: (Boolean) -> Unit, parentId: String) {
    val editing = editMode || GridEditing.target == "widgets-$parentId"
    val native = rememberNativeGridWidgets(parentId, editing)
    if (native.loaded) ProfiledWidgetGrid("widgets-$parentId", native.tiles, modifier, editing,
        onDone = { GridEditing.active = false; onEditModeChange(false) }, onAdd = native.add)
}
