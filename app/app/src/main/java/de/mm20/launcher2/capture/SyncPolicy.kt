package de.mm20.launcher2.capture

internal object SyncPolicy {
    fun stateForHttp(code: Int): String = when {
        code == 401 -> "auth_required"
        code == 408 || code == 429 || code == 403 || code >= 500 -> "retry"
        else -> "failed"
    }
    fun sameOwner(bound: String, current: String) = bound.isEmpty() || bound == current
}
