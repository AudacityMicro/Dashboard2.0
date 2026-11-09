package de.mm20.launcher2.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.mm20.launcher2.dashboard.GridCell
import de.mm20.launcher2.ui.R
import kotlin.math.roundToInt

/** Observe long presses before child controls; short taps and scrolling remain with the content. */
@Composable
internal fun GridWidgetFrame(id: String, cell: GridCell, stepX: Float, stepY: Float, direction: Int, columns: Int,
    selected: Boolean, editing: Boolean, enabled: Boolean, onSelect: () -> Unit,
    onPlace: (GridCell) -> Unit, onExactSize: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val select by rememberUpdatedState(onSelect)
    val place by rememberUpdatedState(onPlace)
    val haptic = LocalHapticFeedback.current
    var delta by remember(cell, selected) { mutableStateOf(Offset.Zero) }
    var moving by remember { mutableStateOf(false) }
    var corner by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    val density = LocalDensity.current
    val gutter = with(density) { 8.dp.toPx() }
    val preview = corner?.let { cell.resized((direction * delta.x / stepX).roundToInt(), (delta.y / stepY).roundToInt(), it.first, it.second, columns) } ?: cell
    @Composable fun drag(modifier: Modifier, left: Boolean? = null, top: Boolean? = null): Modifier {
        var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        return modifier.onGloballyPositioned { coordinates = it }
            .semantics { role = Role.Button; onClick("Position and size") { if (enabled) { onExactSize(); true } else false } }
            .pointerInput(cell, stepX, stepY, direction, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val origin = coordinates?.localToRoot(down.position) ?: down.position
                    down.consume()
                    var dragged = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            val distance = (coordinates?.localToRoot(pointer.position) ?: pointer.position) - origin
                            if (distance.getDistance() > viewConfiguration.touchSlop) dragged = true
                            if (dragged) { moving = left == null; corner = left?.let { it to (top == true) }; delta = distance }
                            pointer.consume()
                            if (!pointer.pressed) {
                                if (dragged) {
                                    val dx = (direction * delta.x / stepX).roundToInt()
                                    val dy = (delta.y / stepY).roundToInt()
                                    place(if (left == null) cell.copy(x = cell.x + dx, y = cell.y + dy)
                                        else cell.resized(dx, dy, left, top == true, columns))
                                } else onExactSize()
                                break
                            }
                        }
                    } finally { delta = Offset.Zero; moving = false; corner = null }
                }
            }
    }
    Box(Modifier.padding(4.dp).testTag("grid-item-$id").zIndex(if (selected) 1f else 0f)
        .graphicsLayer {
            transformOrigin = TransformOrigin(if (direction == 1) 0f else 1f, 0f)
            translationX = if (moving) delta.x else direction * (preview.x - cell.x) * stepX
            translationY = if (moving) delta.y else (preview.y - cell.y) * stepY
            scaleX = (preview.width * stepX - gutter).coerceAtLeast(1f) / (cell.width * stepX - gutter).coerceAtLeast(1f)
            scaleY = (preview.height * stepY - gutter).coerceAtLeast(1f) / (cell.height * stepY - gutter).coerceAtLeast(1f)
        }
        .semantics {
            onLongClick("Arrange widget") { if (enabled) { select(); true } else false }
            if (selected && enabled) customActions = listOf(CustomAccessibilityAction("Delete widget") { onDelete(); true })
        }
        .pointerInput(id, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer == null || !pointer.pressed || event.changes.size > 1 ||
                            (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (cancelled == null) {
                    select(); haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    do { val event = awaitPointerEvent(PointerEventPass.Initial); event.changes.forEach { it.consume() } }
                    while (event.changes.any { it.pressed })
                }
            }
        }) {
        Box(Modifier.fillMaxSize().clipToBounds()) { content() }
        if (editing) Box(Modifier.matchParentSize().clickable(enabled = enabled) { select() })
        if (selected) {
            Box(Modifier.matchParentSize().border(2.dp, MaterialTheme.colorScheme.primary))
            Box(drag(Modifier.align(Alignment.TopCenter).size(48.dp, 40.dp).testTag("grid-move-$id")
                .semantics { contentDescription = "Move widget" }), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.size(32.dp, 10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
            listOf(Triple("tl", Alignment.TopStart, true to true), Triple("tr", Alignment.TopEnd, false to true),
                Triple("bl", Alignment.BottomStart, true to false), Triple("br", Alignment.BottomEnd, false to false)).forEach { (corner, alignment, sides) ->
                Box(drag(Modifier.align(alignment).size(40.dp).testTag(if (corner == "br") "grid-resize-$id" else "grid-resize-$id-$corner")
                    .semantics { contentDescription = "Resize widget $corner" }, sides.first, sides.second), contentAlignment = alignment) {
                    Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape))
                }
            }
            // Keep the trash target separate from the move/resize handles. Compact widgets
            // still have the same action in the arrangement toolbar and accessibility menu.
            if (cell.width * stepX >= with(density) { 128.dp.toPx() } &&
                cell.height * stepY >= with(density) { 128.dp.toPx() }) {
                FilledIconButton(onClick = onDelete, enabled = enabled,
                    modifier = Modifier.align(Alignment.Center).size(48.dp).testTag("grid-delete-$id"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                    Icon(painterResource(R.drawable.delete_24px), contentDescription = "Delete widget")
                }
            }
        }
    }
}
