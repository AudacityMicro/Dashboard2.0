package de.mm20.launcher2.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import de.mm20.launcher2.dashboard.*
import de.mm20.launcher2.ui.component.LauncherCard
import de.mm20.launcher2.ui.locals.LocalAppGridWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.ceil

object GridEditing {
    var target by mutableStateOf<String?>(null)
    var currentParent = "home"
    var active: Boolean
        get() = target != null
        set(value) { target = if (value) currentParent else null }
}
data class GridTile(val id: String, val label: String, val rows: Int = 4, val visible: Boolean = true,
    val onConfigure: (() -> Unit)? = null, val onRemove: (() -> Unit)? = null,
    val supportsAutoHeight: Boolean = false,
    val content: @Composable (Modifier) -> Unit)

@Composable
internal fun currentGridProfile(): GridProfile {
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
    val profile = detected
    var activeTile by remember(parent, profile) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var layout by remember(parent, profile) { mutableStateOf(WidgetGridLayout(profile.columns)) }
    var contentPixels by remember(parent, profile) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var loaded by remember(parent, profile) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var resumeVersion by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { resumeVersion++ }
    var hiddenDialog by remember { mutableStateOf(false) }
    LaunchedEffect(parent, profile, resumeVersion) {
        loaded = false
        try { layout = withContext(Dispatchers.IO) { WidgetGridStore(context).load(parent, profile) }; loaded = true; error = "" }
        catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = "Couldn't load this layout. Your saved layout is retained." }
    }
    LaunchedEffect(loaded, tiles.map { it.id to it.visible }, editing) {
        if (loaded) {
            val ids = tiles.map { it.id }.toSet()
            var next = layout.copy(cells = layout.cells.filterKeys { it in ids }, hidden = layout.hidden.intersect(ids),
                autoHeights = layout.autoHeights.filterKeys { it in ids })
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
            } catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = "Couldn't save the layout. Try again." }
            finally { busy = false }
        }
    }
    val density = LocalDensity.current
    val rowPixels = with(density) { layout.rowDp.dp.toPx() }
    val gutterPixels = with(density) { 8.dp.toPx() }
    val shown = tiles.filter { (it.visible || editing) && it.id !in layout.hidden && it.id in layout.cells }
    val shownIds = shown.map { it.id }.toSet()
    val automaticIds = tiles.filter { it.supportsAutoHeight }.map { it.id }.toSet()
    val display = layout.copy(cells = layout.cells.filterKeys { it in shownIds },
        autoHeights = layout.autoHeights.filterKeys { it in automaticIds && it in shownIds })
        .withContentHeights(contentPixels.mapValues { (_, pixels) -> ceil((pixels + gutterPixels) / rowPixels).toInt().coerceAtLeast(1) })
    fun place(id: String, cell: GridCell) {
        try {
            val automatic = layout.autoHeights[id]?.takeIf { it.enabled && id in automaticIds }
            // A vertical corner resize changes the automatic maximum; moving preserves it.
            val next = if (automatic != null && cell.height != display.cells[id]?.height)
                layout.copy(autoHeights = layout.autoHeights + (id to automatic.copy(maxRows = cell.height.coerceIn(1, 32)))) else layout
            val anchor = layout.cells[id] ?: cell
            val current = display.cells[id] ?: anchor
            val desired = cell.copy(y = anchor.y + cell.y - current.y,
                height = if (automatic != null) anchor.height else cell.height)
            save(next.place(id, desired), id)
        } catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = "No space for that size or position." }
    }
    LaunchedEffect(profile, editing) { if (!editing) activeTile = null; selected = null; hiddenDialog = false }
    BackHandler(editing) { if (!busy) { activeTile = null; onDone() } }
    Box(modifier.fillMaxWidth().testTag("grid-page-$parent")) {
    Column(Modifier.fillMaxWidth().testTag("widget-grid-${profile.name}")) {
        if (editing) {
            LauncherCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Hold a widget to select", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = onAdd, enabled = !busy) { Text("Add widget") }
                    TextButton(onClick = { hiddenDialog = true }, enabled = !busy) { Text("Widgets") }
                    TextButton(onClick = { activeTile = null; onDone() }, enabled = !busy) { Text("Done") }
                }
            }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (loaded) {
            val grid = display
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val stepX = with(density) { (maxWidth / grid.columns).toPx() }
                val stepY = with(density) { grid.rowDp.dp.toPx() }
                val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
                Layout(modifier = Modifier.fillMaxWidth(), content = {
                    shown.forEach { tile -> key(tile.id) {
                        val cell = grid.cells.getValue(tile.id)
                        GridWidgetFrame(tile.id, cell, stepX, stepY, direction, grid.columns,
                            selected = editing && activeTile == tile.id, editing = editing, enabled = !busy,
                            onSelect = { activeTile = tile.id; GridEditing.target = parent },
                            onPlace = { place(tile.id, it) }, onExactSize = { selected = tile.id }) {
                            CompositionLocalProvider(LocalAppGridWidth provides maxWidth,
                                LocalGridContentHeight provides if (tile.supportsAutoHeight && layout.autoHeights[tile.id]?.enabled == true) ({ pixels: Int ->
                                    if (contentPixels[tile.id] != pixels) contentPixels = contentPixels + (tile.id to pixels)
                                }) else null) {
                                tile.content(Modifier.fillMaxSize())
                            }
                        }
                    } }
                }) { measurables, constraints ->
                    val rows = shown.maxOfOrNull { grid.cells.getValue(it.id).let { c -> c.y + c.height } } ?: 0
                    val height = (rows * stepY).roundToInt()
                    val measured = measurables.mapIndexed { index, measurable ->
                        val cell = grid.cells.getValue(shown[index].id)
                        measurable.measure(Constraints.fixed((cell.width * stepX).roundToInt().coerceAtLeast(1),
                            (cell.height * stepY).roundToInt().coerceAtLeast(1)))
                    }
                    layout(constraints.maxWidth, height) {
                        measured.forEachIndexed { index, placeable ->
                            val cell = grid.cells.getValue(shown[index].id)
                            placeable.placeRelative((cell.x * stepX).roundToInt(), (cell.y * stepY).roundToInt())
                        }
                    }
                }
            }
        }
    }
    }
    selected?.let { id -> (display.cells[id] ?: layout.cells[id])?.let { cell ->
        val originalDisplay = remember(id, profile) { cell }
        val anchor = remember(id, profile) { layout.cells.getValue(id) }
        var x by remember(id, profile) { mutableStateOf(cell.x.toString()) }
        var y by remember(id, profile) { mutableStateOf(cell.y.toString()) }
        var w by remember(id, profile) { mutableStateOf(cell.width.toString()) }
        var h by remember(id, profile) { mutableStateOf(cell.height.toString()) }
        val supportsAuto = id in automaticIds
        var automatic by remember(id, profile) { mutableStateOf(layout.autoHeights[id]?.enabled == true) }
        var maximum by remember(id, profile) { mutableStateOf((layout.autoHeights[id]?.maxRows ?: 12).toString()) }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Position and size") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Column and row start at 0. Sizes snap to this profile’s grid.")
                tiles.firstOrNull { it.id == id }?.let { tile ->
                    Row {
                        tile.onConfigure?.let { configure -> TextButton(onClick = { selected = null; configure() }) { Text("Widget settings") } }
                        tile.onRemove?.let { remove -> TextButton(onClick = { selected = null; remove() }) { Text("Remove everywhere") } }
                    }
                }
                OutlinedTextField(x, { x = it }, label = { Text("Column") }, modifier = Modifier.testTag("grid-edit-x"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(y, { y = it }, label = { Text("Row") }, modifier = Modifier.testTag("grid-edit-y"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(w, { w = it }, label = { Text("Width in columns") }, modifier = Modifier.testTag("grid-edit-w"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(h, { h = it }, enabled = !supportsAuto || !automatic, label = { Text("Height in rows") }, modifier = Modifier.testTag("grid-edit-h"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (supportsAuto) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Automatic height", Modifier.weight(1f))
                        Switch(checked = automatic, onCheckedChange = { automatic = it }, modifier = Modifier.testTag("grid-auto-height"))
                    }
                    if (automatic) {
                        OutlinedTextField(maximum, { maximum = it }, label = { Text("Maximum height in rows") },
                            modifier = Modifier.testTag("grid-auto-max"), singleLine = true,
                            isError = maximum.toIntOrNull() !in 1..32, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Text("Grows and shrinks with content, up to 1–32 rows. Extra content scrolls. Vertical corner resizing changes this maximum.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = listOf(x, y, w, h).all { it.toIntOrNull() != null } &&
            (!supportsAuto || !automatic || maximum.toIntOrNull() in 1..32), onClick = {
            val values = listOf(x, y, w, h).map { it.toIntOrNull() }
            if (values.all { it != null }) {
                try {
                    val next = if (supportsAuto) layout.copy(autoHeights = layout.autoHeights +
                        (id to GridAutoHeight(automatic, maximum.toIntOrNull()?.coerceIn(1, 32) ?: 12))) else layout
                    val desired = GridCell(values[0]!!, anchor.y + values[1]!! - originalDisplay.y,
                        values[2]!!, if (supportsAuto && automatic) anchor.height else values[3]!!)
                    save(if (desired == anchor) next else next.place(id, desired), id)
                    selected = null
                } catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = "No space for that size or position." }
            }
        }) { Text("Save layout") } }, dismissButton = {
            Row {
                TextButton(onClick = { save(layout.copy(hidden = layout.hidden + id)); selected = null }) { Text("Hide") }
                TextButton(onClick = { selected = null }) { Text("Cancel") }
            }
        })
    } }
    if (hiddenDialog) AlertDialog(onDismissRequest = { hiddenDialog = false }, title = { Text("Widgets in ${profile.name}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Empty sections appear automatically when they have content. Hiding applies only to this profile.")
            tiles.forEach { tile ->
                if (tile.id in layout.hidden) TextButton(onClick = { save(layout.copy(hidden = layout.hidden - tile.id), tile.id); hiddenDialog = false }) { Text("Show ${tile.label}") }
                else TextButton(onClick = { selected = tile.id; hiddenDialog = false }) { Text("${tile.label} · position and size") }
            }
        }
    }, confirmButton = { TextButton(onClick = { hiddenDialog = false }) { Text("Close") } })
}
