package de.mm20.launcher2.capture

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Offline durable queue; interrupted work remains pending and is recovered on startup/periodically. */
class TranscriptionWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        InboxStore(applicationContext).use { store ->
            for (capture in store.list().filter { it.transcription == "pending" }) {
                if (isStopped) return Result.retry()
                try {
                    val text = OfflineTranscriber.transcribe(applicationContext, store.file(capture.id)) { isStopped }
                    if (isStopped) return Result.retry()
                    store.update(capture.id, "text" to text, "transcription" to if (text.isBlank()) "no_speech" else "ready")
                } catch (_: java.util.concurrent.CancellationException) { return Result.retry()
                } catch (_: Exception) {
                    // The audio is still available, including when the format/language is unsupported.
                    store.update(capture.id, "transcription" to "failed")
                } catch (_: LinkageError) {
                    store.update(capture.id, "transcription" to "failed")
                }
            }
        }
        SyncQueue.enqueueDrive(applicationContext)
        return Result.success()
    }
}
