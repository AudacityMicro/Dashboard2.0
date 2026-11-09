package de.mm20.launcher2.ui.dashboard

import android.Manifest
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
    val lifecycle = LocalLifecycleOwner.current
    val keyboard = LocalSoftwareKeyboardController.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A permission dialog ends the original hold. Require a fresh press after granting.
        if (!granted) model.permissionDenied()
    }
    LaunchedEffect(model.holdPermissionRequested) {
        if (model.holdPermissionRequested) {
            model.holdPermissionLaunched()
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importFile(uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), model::cameraResult)
    LaunchedEffect(model.launchExternal) {
        if (model.launchExternal) {
            model.externalLaunched()
            try {
                if (model.mode == InlineCaptureMode.Image) camera.launch(model.cameraUri())
                else filePicker.launch(arrayOf("*/*"))
            } catch (_: Exception) { model.externalFailed() }
        }
    }
    LaunchedEffect(model.saved, model.transcription) {
        if (model.saved && model.transcription == "pending") {
            while (model.transcription == "pending") { model.refreshTranscript(); delay(1000) }
        }
    }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.backgrounded() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.backgrounded() }
    }
    fun dismiss() {
        if (model.busy) return
        when {
            model.recording -> model.stopAndSave(closeWhenSaved = true)
            model.pendingVoice || (model.mode == InlineCaptureMode.Text && model.draft.isNotBlank()) -> confirmDiscard = true
            else -> { keyboard?.hide(); model.close() }
        }
    }
    BottomSheet(state = model.mode, expanded = { it != null && !(model.holding && model.holdFromDock) }, onDismissRequest = { dismiss() }) { mode ->
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .padding(24.dp).navigationBarsPadding().testTag("inline-capture-sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when (mode) {
                InlineCaptureMode.Text -> "Text note"
                InlineCaptureMode.Voice -> "Voice note"
                InlineCaptureMode.Image -> "Photo"
                else -> "Upload file"
            }, style = MaterialTheme.typography.headlineSmall)
            Text(if (model.saved) "Saved on this phone. Drive will sync when connected."
                else "Saved on this phone first. No internet needed.", style = MaterialTheme.typography.bodyMedium)
            if (model.saved) {
                Text("Saved offline", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("inline-capture-saved"))
                if (mode == InlineCaptureMode.Voice) {
                    Text("Automatic transcript · English", style = MaterialTheme.typography.titleMedium)
                    Text(when (model.transcription) {
                        "ready" -> model.transcript
                        "no_speech" -> "No speech detected. Your audio is saved."
                        "failed" -> "Couldn't transcribe this recording. Your audio is saved."
                        else -> "Transcribing on this phone… You can close this; it continues offline."
                    }, modifier = Modifier.testTag("inline-voice-transcript"))
                }
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
                        Text(if (model.recording) "Release to save. Also saves when you leave Home."
                            else "Hold to record; release to save. Up to 10 minutes. English transcription runs automatically on this phone after saving.")
                        if (model.pendingVoice) Button(onClick = { model.saveVoice() }, enabled = !model.busy,
                            modifier = Modifier.fillMaxWidth().testTag("inline-voice-retry")) { Text("Retry save") }
                        else HoldToRecordButton(model, Modifier.fillMaxWidth().testTag("inline-voice-record"))

                    }
                    InlineCaptureMode.Image, InlineCaptureMode.File -> Button(onClick = model::requestExternal, enabled = !model.busy,
                        modifier = Modifier.fillMaxWidth()) { Text(if (mode == InlineCaptureMode.Image) "Open camera" else "Choose image or file") }
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
