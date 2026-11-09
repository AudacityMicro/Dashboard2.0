package de.mm20.launcher2.ui.settings.voice

import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import de.mm20.launcher2.capture.VoiceRecordingMode
import de.mm20.launcher2.capture.VoiceRecordingSettings
import de.mm20.launcher2.ui.component.preferences.Preference
import de.mm20.launcher2.ui.component.preferences.PreferenceCategory
import de.mm20.launcher2.ui.component.preferences.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data object VoiceRecordingSettingsRoute : NavKey

@Composable
fun VoiceRecordingSettingsScreen() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(VoiceRecordingSettings.get(context)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PreferenceScreen(title = "Voice recordings") {
        item {
            PreferenceCategory {
                for (mode in VoiceRecordingMode.entries) Preference(
                    title = { Text(mode.title) }, summary = { Text(mode.description) },
                    controls = { RadioButton(selected = selected == mode, onClick = null) },
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val success = withContext(Dispatchers.IO) { VoiceRecordingSettings.set(context, mode) }
                            if (success) selected = mode
                            error = !success; saving = false
                        }
                    })
            }
        }
        item { PreferenceCategory {
            Text("Applies to new recordings and shared audio notes. Queued recordings keep their original choice. Transcription runs on this phone in English, without an internet connection. If transcript-only conversion fails, audio stays on this phone; retry in Capture inbox.")
            if (error) Text("Couldn't save this setting. Try again.")
        } }
    }
}
