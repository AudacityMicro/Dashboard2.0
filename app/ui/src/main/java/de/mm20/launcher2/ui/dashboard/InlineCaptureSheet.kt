package de.mm20.launcher2.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.mm20.launcher2.ui.component.BottomSheet
import kotlinx.coroutines.delay

@Composable
fun InlineCaptureSheet(model: InlineCaptureViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val keyboard = LocalSoftwareKeyboardController.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (permissionPending && model.mode == InlineCaptureMode.Voice && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (granted) model.startVoice() else model.permissionDenied()
        }
        permissionPending = false
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.importImage(uri)
    }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.backgrounded() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.backgrounded() }
    }
    fun dismiss() {
        if (model.busy) return
        permissionPending = false
        when {
            model.recording -> model.stopAndSave(closeWhenSaved = true)
            model.pendingVoice || (model.mode == InlineCaptureMode.Text && model.draft.isNotBlank()) -> confirmDiscard = true
            else -> { keyboard?.hide(); model.close() }
        }
    }
    BottomSheet(state = model.mode, expanded = { it != null }, onDismissRequest = { dismiss() }) { mode ->
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .padding(24.dp).navigationBarsPadding().testTag("inline-capture-sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when (mode) {
                InlineCaptureMode.Text -> "Text note"
                InlineCaptureMode.Voice -> "Voice note"
                else -> "Add image"
            }, style = MaterialTheme.typography.headlineSmall)
            Text(if (model.saved) "Saved on this phone. Drive will sync when connected."
                else "Saved on this phone first. No internet needed.", style = MaterialTheme.typography.bodyMedium)
            if (model.saved) {
                Text("Saved offline", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("inline-capture-saved"))
                Button(onClick = { keyboard?.hide(); model.close() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            } else {
                when (mode) {
                    InlineCaptureMode.Text -> {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { delay(250); focus.requestFocus(); keyboard?.show() }
                        OutlinedTextField(value = model.draft, onValueChange = model::edit,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("inline-note-editor"),
                            enabled = !model.busy, label = { Text("Your note") }, placeholder = { Text("Drop a thought here…") },
                            minLines = 4, maxLines = 8, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            keyboardActions = KeyboardActions.Default)
                        Button(onClick = { keyboard?.hide(); model.saveText() }, enabled = model.draft.isNotBlank() && !model.busy,
                            modifier = Modifier.fillMaxWidth().testTag("inline-note-save")) { Text("Save note") }
                    }
                    InlineCaptureMode.Voice -> {
                        var elapsed by remember { mutableLongStateOf(0) }
                        LaunchedEffect(model.recording, model.startedAt) {
                            if (model.recording) while (true) {
                                elapsed = (SystemClock.elapsedRealtime() - model.startedAt) / 1000; delay(250)
                            }
                        }
                        Text("%02d:%02d".format(elapsed / 60, elapsed % 60), style = MaterialTheme.typography.displayMedium,
                            modifier = Modifier.testTag("inline-voice-timer"))
                        Text(if (model.recording) "Recording. Saves when you leave Home."
                            else "Tap to record. Up to 10 minutes or 25 MiB.")
                        Button(onClick = {
                            if (model.recording) model.stopAndSave()
                            else if (model.pendingVoice) model.saveVoice()
                            else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.startVoice()
                            else { permissionPending = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
                        }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("inline-voice-record")) {
                            Text(if (model.recording) "Stop and save" else if (model.pendingVoice) "Retry save" else "Start recording")
                        }
                    }
                    InlineCaptureMode.Image -> Button(onClick = { imagePicker.launch("image/*") }, enabled = !model.busy,
                        modifier = Modifier.fillMaxWidth()) { Text("Choose image") }
                    else -> Unit
                }
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (model.error.isNotBlank()) Text(model.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("inline-capture-error"))
                TextButton(onClick = { dismiss() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (model.recording) "Save and close" else "Close")
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard unsaved note?") },
        text = { Text("Your unsaved content will be removed. Saved notes stay in your inbox.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; keyboard?.hide(); model.discard() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } })
}
