package de.mm20.launcher2.ui.dashboard

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.ui.component.LauncherCard

@Composable
fun HoldToRecordButton(model: InlineCaptureViewModel, modifier: Modifier = Modifier, fromDock: Boolean = false) {
    val height = animateDpAsState(if (model.recording) 112.dp else 48.dp, label = "recording height")
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = if (model.recording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.heightIn(min = height.value)
            .semantics {
                role = Role.Button
                stateDescription = if (model.recording) "Recording" else "Not recording"
                onClick(if (model.recording) "Stop and save recording" else "Start voice recording") {
                    model.accessibleVoiceAction(); true
                }
            }
            .pointerInput(model, fromDock) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    down.consume()
                    model.beginHold(fromDock)
                    try {
                        // Keep the original pointer, regardless of bounds or touch slop.
                        // Consume moves before parent scrolling can claim this recording gesture.
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                            if (pointer == null || !pointer.pressed) break
                        }
                    } finally {
                        // A real system cancellation still closes the microphone and saves once.
                        model.endHold()
                    }
                }
            }) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(if (model.recording) "Release" else if (fromDock) "Voice" else "Hold to record",
                style = if (model.recording) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun CaptureDock(model: InlineCaptureViewModel, modifier: Modifier = Modifier) {
    val voiceWeight = animateFloatAsState(if (model.recording) 2.5f else 1f, label = "recording width")
    LauncherCard(modifier.testTag("capture-actions")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (model.recording) Text("Recording… release to save",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("capture-hold-recording"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { model.open(InlineCaptureMode.Text) }, modifier = Modifier.weight(1f).testTag("capture-text"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Text("Text") }
                HoldToRecordButton(model, Modifier.weight(voiceWeight.value).testTag("capture-voice"), fromDock = true)
                listOf("image" to "Photo", "file" to "Upload").forEach { (mode, label) ->
                    FilledTonalButton(onClick = { model.open(if (mode == "image") InlineCaptureMode.Image else InlineCaptureMode.File) },
                        modifier = Modifier.weight(1f).testTag("capture-$mode"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Text(label) }
                }
            }
        }
    }
}
