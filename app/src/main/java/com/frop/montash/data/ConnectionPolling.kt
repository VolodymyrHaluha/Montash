package com.frop.montash.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal class ConnectionPolling(
    private val clock: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val intervalMs: Long = 5_000L
) {
    suspend fun run(check: suspend () -> Unit) {
        var nextCheck = clock()
        while (currentCoroutineContext().isActive) {
            check()
            nextCheck += intervalMs
            val now = clock()
            if (nextCheck <= now) nextCheck = now + intervalMs
            pause((nextCheck - now).coerceAtLeast(1L))
        }
    }
}
