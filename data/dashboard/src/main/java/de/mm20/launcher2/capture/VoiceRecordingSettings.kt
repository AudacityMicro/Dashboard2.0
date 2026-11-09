package de.mm20.launcher2.capture

import android.content.Context

enum class VoiceRecordingMode(val id: String, val title: String, val description: String) {
    Transcript("transcript", "Transcript only", "Transcribe locally and upload the text. Keep the original audio on this phone."),
    Audio("audio", "Audio only", "No transcription. Upload the original recording unchanged."),
    Both("both", "Audio and transcript", "Transcribe locally and upload both the original audio and a text transcript.");
    val transcribe get() = this != Audio
    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: Both
    }
}

object VoiceRecordingSettings {
    fun get(context: Context) = VoiceRecordingMode.fromId(context.getSharedPreferences("voice_recording", Context.MODE_PRIVATE).getString("mode", "both").orEmpty())
    fun set(context: Context, mode: VoiceRecordingMode): Boolean = context.getSharedPreferences("voice_recording", Context.MODE_PRIVATE).edit().putString("mode", mode.id).commit()
}
