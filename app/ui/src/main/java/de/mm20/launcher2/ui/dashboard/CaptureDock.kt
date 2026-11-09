package de.mm20.launcher2.ui.dashboard

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.ui.component.LauncherCard

@Composable
fun HoldToRecordButton(model: InlineCaptureViewModel, modifier: Modifier = Modifier, fromDock: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = if (model.recording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.heightIn(min = 48.dp)
            .semantics {
                role = Role.Button
                onClick(if (model.recording) "Stop and save recording" else "Start voice recording") {
                    model.accessibleVoiceAction(); true
                }
            }
            .pointerInput(model, fromDock) {
                detectTapGestures(onPress = {
                    model.beginHold(fromDock)
                    try { tryAwaitRelease() } finally { model.endHold() }
                })
            }) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(if (model.recording) "Release" else if (fromDock) "Voice" else "Hold to record",
                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun CaptureDock(model: InlineCaptureViewModel, modifier: Modifier = Modifier, onAddTask: () -> Unit) {
    LauncherCard(modifier.testTag("capture-actions")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (model.recording) "Recording… release to save" else "Hold Voice to record",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag(if (model.recording) "capture-hold-recording" else "capture-hold-hint"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { model.open(InlineCaptureMode.Text) }, modifier = Modifier.weight(1f).testTag("capture-text"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Text("Text") }
                HoldToRecordButton(model, Modifier.weight(1f).testTag("capture-voice"), fromDock = true)
                listOf("image" to "Photo", "file" to "Upload").forEach { (mode, label) ->
                    FilledTonalButton(onClick = { model.open(if (mode == "image") InlineCaptureMode.Image else InlineCaptureMode.File) },
                        modifier = Modifier.weight(1f).testTag("capture-$mode"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Text(label) }
                }
            }
            TextButton(onClick = onAddTask, modifier = Modifier.align(Alignment.End).testTag("add-task-dock"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("Add task") }
        }
    }
}
