package de.mm20.launcher2.capture

/** Retry-safe IDs are committed before any upload. Voice policy is immutable per capture. */
internal object CaptureDriveUpload {
    fun upload(store: InboxStore, original: Capture, token: String, api: String = "https://www.googleapis.com") {
        check(original.readyForSync)
        fun id(existing: String, column: String) = existing.ifEmpty {
            DriveHttp.newId(token, api).also { store.update(original.id, column to it) }
        }
        val remote = id(original.remote, "remote")
        val manifest = id(original.manifest, "manifest")
        val voice = original.kind == "voice"
        val transcriptOnly = voice && original.voiceMode == "transcript"
        val withTranscript = voice && original.voiceMode == "both" && original.transcription == "ready" && original.text.isNotBlank()
        val transcriptRemote = if (withTranscript) id(original.transcriptRemote, "transcript_remote") else ""
        val capture = original.copy(remote = remote, manifest = manifest, transcriptRemote = transcriptRemote)
        val extension = when (capture.kind) { "text" -> "txt"; "voice" -> if (capture.mime == "audio/mp4") "m4a" else "audio"; "image" -> "image"; else -> "file" }
        val filename = if (capture.name.isNotBlank()) "Dashboard-${capture.id}-${capture.name}" else "Dashboard-${capture.id}.$extension"
        if (transcriptOnly) {
            DriveHttp.upload(token, remote, capture.id, "Dashboard-${capture.id}.txt", "text/plain", bytes = capture.text.toByteArray(Charsets.UTF_8), api = api)
        } else {
            DriveHttp.upload(token, remote, capture.id, filename, capture.mime, store.file(capture.id), api = api)
        }
        if (withTranscript) DriveHttp.upload(token, transcriptRemote, capture.id, "Dashboard-${capture.id}-transcript.txt", "text/plain", bytes = capture.text.toByteArray(Charsets.UTF_8), api = api)
        DriveHttp.upload(token, manifest, capture.id, "Dashboard-${capture.id}.json", "application/json", bytes = store.manifest(capture), api = api)
        store.update(capture.id, "state" to "synced")
    }
}
