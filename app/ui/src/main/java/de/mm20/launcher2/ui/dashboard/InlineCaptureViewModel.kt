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
import de.mm20.launcher2.capture.InboxStore
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

enum class InlineCaptureMode { Text, Voice, Image }

/** The microphone never outlives the foreground launcher. Only committed files enter sync. */
class InlineCaptureViewModel(application: Application, private val state: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var mode by mutableStateOf(if (state.get<Boolean>("textOpen") == true) InlineCaptureMode.Text else null); private set
    var draft by mutableStateOf(state.get<String>("textDraft").orEmpty()); private set
    var busy by mutableStateOf(false); private set
    var saved by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var recording by mutableStateOf(false); private set
    var startedAt by mutableLongStateOf(0); private set
    var pendingVoice by mutableStateOf(false); private set
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null

    fun open(value: InlineCaptureMode) {
        if (busy || recording || pendingVoice) return
        mode = value; state["textOpen"] = value == InlineCaptureMode.Text
        saved = false; error = ""
    }
    fun edit(value: String) { if (!busy && value.length <= 65536) { draft = value; state["textDraft"] = value; saved = false } }
    fun permissionDenied() { error = "Microphone permission is needed to record. You can enable it in Android settings." }
    fun close() { if (!busy && !recording && !pendingVoice) { mode = null; state["textOpen"] = false } }
    fun discard() {
        if (busy) return
        recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null; recording = false
        voiceFile?.delete(); voiceFile = null; pendingVoice = false
        if (mode == InlineCaptureMode.Text) { draft = ""; state["textDraft"] = "" }
        mode = null; state["textOpen"] = false; error = ""; saved = false
    }
    fun saveText() {
        if (busy || draft.isBlank()) return
        val text = draft
        commit({ InboxStore(app).use { it.save("text", "text/plain", text) } }) {
            draft = ""; state["textDraft"] = ""
        }
    }
    @Suppress("DEPRECATION")
    fun startVoice() {
        if (mode != InlineCaptureMode.Voice || busy || recording || pendingVoice) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { permissionDenied(); return }
        val file = File(app.noBackupFilesDir, "recording-${UUID.randomUUID()}.m4a")
        try {
            val active = MediaRecorder(); recorder = active; voiceFile = file
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
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
            InboxStore(app).use { it.save("voice", "audio/mp4", source = file.inputStream()) }
            file.delete()
        }) {
            voiceFile = null; pendingVoice = false
            if (closeWhenSaved) { mode = null; state["textOpen"] = false }
        }
    }
    fun importImage(uri: Uri) {
        if (busy) return
        mode = InlineCaptureMode.Image; saved = false
        commit({
            require(uri.scheme == "content")
            val mime = app.contentResolver.getType(uri).orEmpty()
            require(mime.matches(Regex("image/[A-Za-z0-9.+-]+")))
            val stream = requireNotNull(app.contentResolver.openInputStream(uri))
            InboxStore(app).use { it.save("image", mime, source = stream) }
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
                    else "Couldn't save. Check available storage and try again."
            }
        }
    }
    fun backgrounded() { if (recording) stopAndSave() }
    override fun onCleared() {
        // This executor drains a pending durable write even after the UI owner is destroyed.
        backgrounded(); io.shutdown(); super.onCleared()
    }
}
