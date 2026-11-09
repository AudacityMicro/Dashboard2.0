package de.mm20.launcher2.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.dashboard.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun WidgetGridSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(GridProfile.Portrait) }
    var columns by remember { mutableFloatStateOf(4f) }
    var rows by remember { mutableFloatStateOf(56f) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(profile) {
        ready = false; error = ""
        try {
            val value = withContext(Dispatchers.IO) { WidgetGridStore(context).configuration(profile) }
            columns = value.columns.toFloat(); rows = value.rowDp.toFloat(); ready = true
        } catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = "Couldn't load grid settings." }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Widget grid") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GridProfile.entries.forEach { mode ->
                FilterChip(selected = profile == mode, enabled = !busy,
                    onClick = { if (profile != mode) { ready = false; profile = mode } }, label = { Text(mode.name) },
                    modifier = Modifier.testTag("grid-settings-${mode.name}"))
            }
            Text("Applies to Home and all widget pages in this screen layout. Other layouts keep their settings.")
            Text("Columns: ${columns.roundToInt()}")
            Slider(columns, { columns = it }, enabled = ready && !busy, valueRange = 2f..12f, steps = 9,
                modifier = Modifier.testTag("grid-settings-columns"))
            Text("Row height: ${rows.roundToInt()} dp")
            Slider(rows, { rows = it }, enabled = ready && !busy, valueRange = 40f..120f, steps = 7,
                modifier = Modifier.testTag("grid-settings-rows"))
            Text("Widgets snap to this grid. Crowded widgets move down to fit.", style = MaterialTheme.typography.bodySmall)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = ready && !busy, onClick = {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WidgetGridStore(context).configure(profile, columns.roundToInt(), rows.roundToInt()) }
                onDismiss()
            } catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = "Couldn't apply this grid. Try more columns or smaller widgets." }
            finally { busy = false }
        }
    }) { Text("Save grid") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}
