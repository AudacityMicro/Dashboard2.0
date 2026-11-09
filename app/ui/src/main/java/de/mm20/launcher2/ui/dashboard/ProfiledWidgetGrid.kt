package de.mm20.launcher2.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.zIndex
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import de.mm20.launcher2.dashboard.*
import de.mm20.launcher2.ui.component.LauncherCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object GridEditing {
    var target by mutableStateOf<String?>(null)
    var currentParent = "home"
    var active: Boolean
        get() = target != null
        set(value) { target = if (value) currentParent else null }
}
data class GridTile(val id: String, val label: String, val rows: Int = 4, val visible: Boolean = true,
    val content: @Composable (Modifier) -> Unit)

@Composable
private fun currentGridProfile(): GridProfile {
    val config = LocalConfiguration.current
    val activity = LocalActivity.current
    val context = LocalContext.current
    var folding by remember { mutableStateOf(false) }
    LaunchedEffect(activity) {
        if (activity != null) runCatching {
            WindowInfoTracker.getOrCreate(context).windowLayoutInfo(activity).collect { info ->
                folding = info.displayFeatures.any { it is FoldingFeature }
            }
        }
    }
    return GridProfile.detect(config.screenWidthDp, config.screenHeightDp, folding)
}

@Composable
fun ProfiledWidgetGrid(parent: String, tiles: List<GridTile>, modifier: Modifier = Modifier,
    editing: Boolean = GridEditing.target == parent, onDone: () -> Unit = { GridEditing.active = false }, onAdd: () -> Unit = {},
    onPlaced: (String) -> Unit = {}) {
    val detected = currentGridProfile()
    var preview by remember { mutableStateOf<GridProfile?>(null) }
    val profile = if (editing) preview ?: detected else detected
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var layout by remember(parent, profile) { mutableStateOf(WidgetGridLayout(profile.columns)) }
    var loaded by remember(parent, profile) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var hiddenDialog by remember { mutableStateOf(false) }
    LaunchedEffect(parent, profile) {
        try { layout = withContext(Dispatchers.IO) { WidgetGridStore(context).load(parent, profile) }; loaded = true; error = "" }
        catch (_: Exception) { error = "Couldn't load this layout. Your saved layout is retained." }
    }
    LaunchedEffect(loaded, tiles.map { it.id to it.visible }, editing) {
        if (loaded) {
            val ids = tiles.map { it.id }.toSet()
            var next = layout.copy(cells = layout.cells.filterKeys { it in ids }, hidden = layout.hidden.intersect(ids))
            tiles.filter { it.visible || editing }.forEach { tile ->
                try { next = next.add(tile.id, tile.rows, if (profile == GridProfile.Portrait || tile.id == "setup") next.columns else next.columns / 2) }
                catch (_: IllegalStateException) { error = "Grid full. Make existing widgets smaller to add more." }
            }
            layout = next
        }
    }
    fun save(next: WidgetGridLayout, activated: String? = null) {
        if (busy || !loaded) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WidgetGridStore(context).save(parent, profile, next) }
                layout = next; error = ""
                activated?.let(onPlaced)
            } catch (_: Exception) { error = "Couldn't save the layout. Try again." }
            finally { busy = false }
        }
    }
    fun place(id: String, cell: GridCell) {
        try { save(layout.place(id, cell), id) } catch (_: Exception) { error = "No space for that size or position." }
    }
    LaunchedEffect(profile) { selected = null; settings = false; hiddenDialog = false }
    BackHandler(editing) { preview = null; onDone() }
    Column(modifier.fillMaxWidth().testTag("widget-grid-${profile.name}")) {
        if (editing) {
            LauncherCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GridProfile.entries.forEach { mode -> FilterChip(selected = profile == mode,
                        onClick = { if (!busy) preview = mode }, label = { Text(mode.name) }, modifier = Modifier.testTag("grid-profile-${mode.name}")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { settings = true }, enabled = loaded && !busy) { Text("Grid ${layout.columns} cols") }
                    TextButton(onClick = onAdd) { Text("Add widget") }
                    TextButton(onClick = { hiddenDialog = true }) { Text("Widgets") }
                    TextButton(onClick = { preview = null; onDone() }, enabled = !busy) { Text("Done") }
                }
                Text("Drag Move or Resize. Tap Widgets for precise rows, columns and hidden items.", style = MaterialTheme.typography.labelSmall)
            }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (loaded) {
            val shown = tiles.filter { (it.visible || editing) && it.id !in layout.hidden && it.id in layout.cells }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val stepX = with(density) { (maxWidth / layout.columns).toPx() }
                val stepY = with(density) { layout.rowDp.dp.toPx() }
                val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
                Layout(modifier = Modifier.fillMaxWidth(), content = {
                    shown.forEach { tile -> key(tile.id) {
                        val cell = layout.cells.getValue(tile.id)
                        var delta by remember { mutableStateOf(Offset.Zero) }
                        var resizing by remember { mutableStateOf(false) }
                        Column(Modifier.padding(4.dp).testTag("grid-item-${tile.id}").zIndex(if (delta != Offset.Zero) 1f else 0f)
                            .graphicsLayer { if (!resizing) { translationX = delta.x; translationY = delta.y } }.clipToBounds()) {
                            if (editing) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { selected = tile.id }, modifier = Modifier.weight(1f)
                                    .pointerInput(tile.id, cell, stepX, stepY, direction) {
                                        detectDragGestures(onDragStart = { delta = Offset.Zero; resizing = false },
                                            onDrag = { change, amount -> change.consume(); delta += amount },
                                            onDragCancel = { delta = Offset.Zero }, onDragEnd = {
                                                place(tile.id, cell.copy(x = cell.x + (direction * delta.x / stepX).roundToInt(), y = cell.y + (delta.y / stepY).roundToInt()))
                                                delta = Offset.Zero
                                            })
                                    }.testTag("grid-move-${tile.id}")) { Text("Move · ${tile.label}", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                                TextButton(onClick = { save(layout.copy(hidden = layout.hidden + tile.id)) },
                                    modifier = Modifier.testTag("grid-hide-${tile.id}"), enabled = !busy) { Text("Hide") }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) { tile.content(Modifier.fillMaxSize()) }
                            if (editing) TextButton(onClick = { selected = tile.id }, modifier = Modifier.align(Alignment.End)
                                .testTag("grid-resize-${tile.id}").pointerInput(tile.id, cell, stepX, stepY, direction) {
                                    detectDragGestures(onDragStart = { delta = Offset.Zero; resizing = true },
                                        onDrag = { change, amount -> change.consume(); delta += amount },
                                        onDragCancel = { delta = Offset.Zero; resizing = false }, onDragEnd = {
                                            place(tile.id, cell.copy(width = cell.width + (direction * delta.x / stepX).roundToInt(), height = cell.height + (delta.y / stepY).roundToInt()))
                                            delta = Offset.Zero; resizing = false
                                        })
                                }) { Text("Resize ${cell.width + if (resizing) (direction * delta.x / stepX).roundToInt() else 0} × ${cell.height + if (resizing) (delta.y / stepY).roundToInt() else 0}", style = MaterialTheme.typography.labelSmall) }
                        }
                    } }
                }) { measurables, constraints ->
                    val rows = shown.maxOfOrNull { layout.cells.getValue(it.id).let { c -> c.y + c.height } } ?: 0
                    val height = (rows * stepY).roundToInt()
                    val measured = measurables.mapIndexed { index, measurable ->
                        val cell = layout.cells.getValue(shown[index].id)
                        measurable.measure(Constraints.fixed((cell.width * stepX).roundToInt().coerceAtLeast(1),
                            (cell.height * stepY).roundToInt().coerceAtLeast(1)))
                    }
                    layout(constraints.maxWidth, height) {
                        measured.forEachIndexed { index, placeable ->
                            val cell = layout.cells.getValue(shown[index].id)
                            placeable.placeRelative((cell.x * stepX).roundToInt(), (cell.y * stepY).roundToInt())
                        }
                    }
                }
            }
        }
    }
    selected?.let { id -> layout.cells[id]?.let { cell ->
        var x by remember(id, cell) { mutableStateOf(cell.x.toString()) }
        var y by remember(id, cell) { mutableStateOf(cell.y.toString()) }
        var w by remember(id, cell) { mutableStateOf(cell.width.toString()) }
        var h by remember(id, cell) { mutableStateOf(cell.height.toString()) }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Position and size") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Column and row start at 0. Sizes snap to this profile’s grid.")
                OutlinedTextField(x, { x = it }, label = { Text("Column") }, modifier = Modifier.testTag("grid-edit-x"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(y, { y = it }, label = { Text("Row") }, modifier = Modifier.testTag("grid-edit-y"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(w, { w = it }, label = { Text("Width in columns") }, modifier = Modifier.testTag("grid-edit-w"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(h, { h = it }, label = { Text("Height in rows") }, modifier = Modifier.testTag("grid-edit-h"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }, confirmButton = { TextButton(enabled = listOf(x, y, w, h).all { it.toIntOrNull() != null }, onClick = {
            val values = listOf(x, y, w, h).map { it.toIntOrNull() }
            if (values.all { it != null }) { place(id, GridCell(values[0]!!, values[1]!!, values[2]!!, values[3]!!)); selected = null }
        }) { Text("Save layout") } }, dismissButton = {
            Row {
                TextButton(onClick = { save(layout.copy(hidden = layout.hidden + id)); selected = null }) { Text("Hide") }
                TextButton(onClick = { selected = null }) { Text("Cancel") }
            }
        })
    } }
    if (settings) {
        var columns by remember { mutableFloatStateOf(layout.columns.toFloat()) }
        var row by remember { mutableFloatStateOf(layout.rowDp.toFloat()) }
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("${profile.name} grid") }, text = {
            Column {
                Text("Columns: ${columns.roundToInt()}"); Slider(columns, { columns = it }, valueRange = 2f..12f, steps = 9)
                Text("Row height: ${row.roundToInt()}"); Slider(row, { row = it }, valueRange = 40f..120f, steps = 7)
            }
        }, confirmButton = { TextButton(onClick = {
            try { save(layout.withColumns(columns.roundToInt()).copy(rowDp = row.roundToInt())); settings = false }
            catch (_: Exception) { error = "No space for that grid. Choose more columns." }
        }) { Text("Save grid") } },
            dismissButton = { TextButton(onClick = { settings = false }) { Text("Cancel") } })
    }
    if (hiddenDialog) AlertDialog(onDismissRequest = { hiddenDialog = false }, title = { Text("Widgets in ${profile.name}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Empty sections appear automatically when they have content. Hiding applies only to this profile.")
            tiles.forEach { tile ->
                if (tile.id in layout.hidden) TextButton(onClick = { save(layout.copy(hidden = layout.hidden - tile.id)); hiddenDialog = false }) { Text("Show ${tile.label}") }
                else TextButton(onClick = { selected = tile.id; hiddenDialog = false }) { Text("${tile.label} · position and size") }
            }
        }
    }, confirmButton = { TextButton(onClick = { hiddenDialog = false }) { Text("Close") } })
}
