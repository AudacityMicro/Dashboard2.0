package de.mm20.launcher2.ui.dashboard

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.core.content.FileProvider
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import de.mm20.launcher2.capture.InboxStore
import de.mm20.launcher2.capture.VoiceRecordingMode
import de.mm20.launcher2.capture.VoiceRecordingSettings
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

enum class InlineCaptureMode { Text, Voice, Image, File }

/** The microphone never outlives the foreground launcher. Only committed files enter sync. */
class InlineCaptureViewModel(application: Application, private val state: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var mode by mutableStateOf(state.get<String>("externalMode")?.let { runCatching { InlineCaptureMode.valueOf(it) }.getOrNull() }
        ?: if (state.get<Boolean>("textOpen") == true) InlineCaptureMode.Text else null); private set
    var draft by mutableStateOf(state.get<String>("textDraft").orEmpty()); private set
    var title by mutableStateOf(state.get<String>("textTitle").orEmpty()); private set
    val hasTextDraft: Boolean get() = title.isNotBlank() || draft.isNotBlank()
    var busy by mutableStateOf(false); private set
    var saved by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var recording by mutableStateOf(false); private set
    var holding by mutableStateOf(false); private set
    var holdFromDock by mutableStateOf(false); private set
    var holdPermissionRequested by mutableStateOf(false); private set
    var startedAt by mutableLongStateOf(0); private set
    var pendingVoice by mutableStateOf(false); private set
    var launchExternal by mutableStateOf(false); private set
    var transcript by mutableStateOf(""); private set
    var transcription by mutableStateOf("none"); private set
    var voiceMode by mutableStateOf(VoiceRecordingSettings.get(app)); private set
    private var savedId: String? = null
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null

    fun open(value: InlineCaptureMode) {
        if (busy || recording || pendingVoice) return
        voiceMode = VoiceRecordingSettings.get(app)
        mode = value; state["textOpen"] = value == InlineCaptureMode.Text
        state["externalMode"] = if (value == InlineCaptureMode.Image || value == InlineCaptureMode.File) value.name else null
        launchExternal = value == InlineCaptureMode.Image || value == InlineCaptureMode.File
        saved = false; error = ""; savedId = null; transcript = ""; transcription = "none"
    }
    fun edit(value: String) {
        if (!busy && value.length + title.length + (if (title.isNotBlank()) 2 else 0) <= 65536) {
            draft = value; state["textDraft"] = value; saved = false
        }
    }
    fun editTitle(value: String) {
        val singleLine = value.replace('\n', ' ').replace('\r', ' ')
        if (!busy && singleLine.length <= 200 && singleLine.length + draft.length + (if (singleLine.isNotBlank()) 2 else 0) <= 65536) {
            title = singleLine; state["textTitle"] = singleLine; saved = false
        }
    }
    fun permissionDenied() { error = "Microphone permission is needed to record. You can enable it in Android settings." }
    fun holdPermissionLaunched() { holdPermissionRequested = false }
    fun beginHold(fromDock: Boolean = false) {
        if (busy || pendingVoice || recording) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            open(InlineCaptureMode.Voice); holdPermissionRequested = true; return
        }
        open(InlineCaptureMode.Voice)
        holdFromDock = fromDock
        holding = true
        startVoice()
        if (!recording) holding = false
    }
    fun endHold() {
        if (!holding) return
        stopAndSave(); holding = false
    }
    // TalkBack/switch access cannot sustain a pointer hold: explicit activation toggles recording.
    fun accessibleVoiceAction() {
        if (recording) { stopAndSave(); holding = false }
        else {
            open(InlineCaptureMode.Voice)
            if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
            else holdPermissionRequested = true
        }
    }
    fun close() { if (!busy && !recording && !pendingVoice) { mode = null; state["textOpen"] = false; state["externalMode"] = null } }
    fun externalLaunched() { launchExternal = false }
    fun externalFailed() { launchExternal = false; error = "Couldn't open the camera or file picker. Try again." }
    fun requestExternal() { launchExternal = true }
    fun cameraUri(): Uri {
        val directory = File(app.cacheDir, "capture-camera").also { it.mkdirs() }
        // Clean abandoned camera staging files after a day, never committed inbox payloads.
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86400000 }?.forEach { it.delete() }
        val file = File.createTempFile("photo-", ".jpg", directory)
        state["cameraPath"] = file.path
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }
    fun cameraResult(success: Boolean) {
        val path = state.get<String>("cameraPath") ?: return
        state["cameraPath"] = null
        val file = File(path)
        if (!success) { file.delete(); return }
        commit({
            try { InboxStore(app).use { it.save("image", "image/jpeg", source = file.inputStream(), name = "photo.jpg") } }
            finally { file.delete() }
        }) { }
    }
    suspend fun refreshTranscript() {
        val id = savedId ?: return
        val capture = withContext(Dispatchers.IO) { InboxStore(app).use { store -> store.list().find { it.id == id } } } ?: return
        transcript = capture.text; transcription = capture.transcription
    }
    fun discard() {
        if (busy) return
        recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null; recording = false
        voiceFile?.delete(); voiceFile = null; pendingVoice = false
        if (mode == InlineCaptureMode.Text) { draft = ""; state["textDraft"] = ""; title = ""; state["textTitle"] = "" }
        mode = null; state["textOpen"] = false; error = ""; saved = false
    }
    fun saveText() {
        if (mode != InlineCaptureMode.Text || busy || !hasTextDraft) return
        // Keep captures and Drive manifests plain text; titles need no schema migration.
        val text = listOf(title.trim(), draft).filter { it.isNotBlank() }.joinToString("\n\n")
        commit({ InboxStore(app).use { it.save("text", "text/plain", text) } }) {
            draft = ""; state["textDraft"] = ""; title = ""; state["textTitle"] = ""
            close()
            android.widget.Toast.makeText(app, "Note saved", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    @Suppress("DEPRECATION")
    fun startVoice() {
        if (mode != InlineCaptureMode.Voice || busy || recording || pendingVoice) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { permissionDenied(); return }
        voiceMode = VoiceRecordingSettings.get(app)
        val file = File(app.noBackupFilesDir, "recording-${UUID.randomUUID()}.m4a")
        try {
            val active = MediaRecorder(); recorder = active; voiceFile = file
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioSamplingRate(16000); active.setAudioChannels(1); active.setAudioEncodingBitRate(64000)
            active.setOutputFile(file.path)
            active.setMaxFileSize(25L * 1024 * 1024); active.setMaxDuration(600000)
            active.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stopAndSave()
            }
            active.setOnErrorListener { _, _, _ -> stopAndSave() }
            active.prepare(); active.start()
            startedAt = SystemClock.elapsedRealtime(); recording = true; error = ""; saved = false
        } catch (_: Exception) {
            recorder?.release(); recorder = null; voiceFile = null; file.delete()
            error = "Couldn't start the microphone. Check permission and try again."
        }
    }
    fun stopAndSave(closeWhenSaved: Boolean = false) {
        val active = recorder ?: return
        recorder = null; recording = false
        try { active.stop(); pendingVoice = true }
        catch (_: Exception) {
            voiceFile?.delete(); voiceFile = null; pendingVoice = false
            error = "That recording was too short to save. Try recording for a little longer."
        } finally { active.release() }
        if (pendingVoice) saveVoice(closeWhenSaved)
    }
    fun saveVoice(closeWhenSaved: Boolean = false) {
        val file = voiceFile ?: return
        if (busy) return
        commit({
            savedId = InboxStore(app).use { it.saveVoice("audio/mp4", file.inputStream(), voiceMode) }
            file.delete()
        }) {
            voiceFile = null; pendingVoice = false
            transcription = if (voiceMode.transcribe) "pending" else "none"
            if (closeWhenSaved) { mode = null; state["textOpen"] = false }
        }
    }
    fun importFile(uri: Uri) {
        if (busy) return
        saved = false
        commit({
            require(uri.scheme == "content")
            val mime = app.contentResolver.getType(uri)?.takeIf { it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) } ?: "application/octet-stream"
            val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0).orEmpty() else ""
            }.orEmpty()
            val stream = requireNotNull(app.contentResolver.openInputStream(uri))
            InboxStore(app).use { it.save(if (mime.startsWith("image/")) "image" else "file", mime, source = stream, name = name) }
        }) { }
    }
    private fun commit(work: () -> Unit, done: () -> Unit) {
        busy = true; error = ""
        io.execute {
            val success = try { work(); true } catch (_: Exception) { false }
            main.post {
                busy = false
                if (success) { done(); saved = true }
                else error = if (pendingVoice) "Couldn't save. Your recording is retained; tap Retry save."
                    else if (mode == InlineCaptureMode.Text) "Couldn't save this note. Your draft is still here. Check available storage and try again."
                    else "Couldn't save. Files must be nonempty and under 25 MiB. Check available storage and try again."
            }
        }
    }
    fun backgrounded() { if (holding) endHold() else if (recording) stopAndSave() }
    override fun onCleared() {
        // This executor drains a pending durable write even after the UI owner is destroyed.
        backgrounded(); io.shutdown(); super.onCleared()
    }
}
