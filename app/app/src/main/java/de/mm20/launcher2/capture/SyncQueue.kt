package de.mm20.launcher2.capture

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object SyncQueue {
    fun install(context: Context) {
        runCatching { context.getSystemService(ShortcutManager::class.java)?.addDynamicShortcuts(listOf(
            ShortcutInfo.Builder(context, "capture_inbox").setShortLabel("Capture inbox")
                .setIcon(Icon.createWithResource(context, android.R.drawable.ic_input_add))
                .setIntent(Intent(context, CaptureActivity::class.java).setAction(Intent.ACTION_VIEW)).build()
        )) }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("capture-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecoveryWorker>(15, TimeUnit.MINUTES).build())
        enqueue(context)
    }
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("capture-sync", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
class RecoveryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result { SyncQueue.enqueue(applicationContext); return Result.success() }
}
class DriveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val auth = DriveAuth.state(context)
        if (!auth.optBoolean("enabled")) return@withContext Result.success()
        InboxStore(context).use { store ->
            val queued = store.list().filter { it.state == "pending" }
            if (queued.isEmpty()) return@withContext Result.success()
            var activeId: String? = null
            try {
                val token = DriveAuth.token(context)
                if (token == null) {
                    queued.forEach { store.update(it.id, "state" to "auth_required") }
                    return@withContext Result.success()
                }
                val subject = DriveAuth.subject(token)
                if (subject != auth.optString("subject")) {
                    queued.forEach { store.update(it.id, "state" to "auth_required") }
                    return@withContext Result.success()
                }
                for (original in queued) {
                    activeId = original.id
                    if (isStopped || !DriveAuth.state(context).optBoolean("enabled")) return@withContext Result.success()
                    if (!SyncPolicy.sameOwner(original.owner, subject)) {
                        store.update(original.id, "state" to "auth_required"); continue
                    }
                    // Pin account before allocating/uploading anything to avoid cross-account retries.
                    store.update(original.id, "owner" to subject)
                    if (!store.file(original.id).isFile) {
                        store.update(original.id, "state" to "failed"); continue
                    }
                    val remote = original.remote.ifEmpty { DriveHttp.newId(token).also { store.update(original.id, "remote" to it) } }
                    val manifest = original.manifest.ifEmpty { DriveHttp.newId(token).also { store.update(original.id, "manifest" to it) } }
                    val capture = original.copy(remote = remote, manifest = manifest, owner = subject)
                    val extension = when (capture.kind) { "text" -> "txt"; "voice" -> "audio"; else -> "image" }
                    DriveHttp.upload(token, remote, capture.id, "Dashboard-${capture.id}.$extension", capture.mime, store.file(capture.id))
                    DriveHttp.upload(token, manifest, capture.id, "Dashboard-${capture.id}.json", "application/json", bytes = store.manifest(capture))
                    store.update(capture.id, "state" to "synced")
                }
                Result.success()
            } catch (e: DriveFailure) {
                val state = SyncPolicy.stateForHttp(e.code)
                if (state == "retry") Result.retry() else {
                    if (state == "auth_required") {
                        store.list().filter { it.state == "pending" }.forEach { store.update(it.id, "state" to state) }
                        Result.success()
                    } else {
                        activeId?.let { store.update(it, "state" to "failed") }
                        if (activeId != null) Result.retry() else Result.failure()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: java.io.IOException) { Result.retry()
            } catch (_: java.util.concurrent.TimeoutException) { Result.retry()
            } catch (e: com.google.android.gms.common.api.ApiException) {
                if (e.statusCode in listOf(7, 8, 14, 15, 17)) Result.retry() else {
                    store.list().filter { it.state == "pending" }.forEach { store.update(it.id, "state" to "auth_required") }
                    Result.success()
                }
            } catch (_: Exception) {
                // Keep payloads and show a repairable error. Never log content or credentials.
                activeId?.let { store.update(it, "state" to "failed") }
                if (activeId != null) Result.retry() else Result.failure()
            }
        }
    }
}
