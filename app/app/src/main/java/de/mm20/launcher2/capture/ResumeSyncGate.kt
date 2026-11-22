package de.mm20.launcher2.capture

/** Coalesce rapid Home returns without delaying explicit capture/task/refresh requests. */
internal class ResumeSyncGate(private val intervalMs: Long = 30_000L) {
    private var lastRequest: Long? = null

    @Synchronized fun allow(now: Long, resuming: Boolean): Boolean {
        val last = lastRequest
        if (resuming && last != null && now >= last && now - last < intervalMs) return false
        lastRequest = now
        return true
    }
}
