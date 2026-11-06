package com.frop.montash

import com.frop.montash.data.ConnectionPolling
import com.frop.montash.data.ConnectionStateStabilizer
import com.frop.montash.data.ServerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionPollingTest {
    @Test fun checksImmediatelyAndEveryFiveSecondsWithoutOverlap() = runBlocking {
        var now = 0L
        val calls = mutableListOf<Long>()
        try {
            ConnectionPolling({ now }, { now += it }).run {
                calls += now
                if (calls.size == 4) throw CancellationException("test complete")
            }
        } catch (_: CancellationException) { }
        assertEquals(listOf(0L, 5_000L, 10_000L, 15_000L), calls)
    }

    @Test fun slowCheckSkipsOverlappingPolls() = runBlocking {
        var now = 0L
        val calls = mutableListOf<Long>()
        try {
            ConnectionPolling({ now }, { now += it }).run {
                calls += now
                now += 7_000L
                if (calls.size == 3) throw CancellationException("test complete")
            }
        } catch (_: CancellationException) { }
        assertEquals(listOf(0L, 12_000L, 24_000L), calls)
    }

    @Test fun transientServerFailuresKeepLastHealthyConnection() {
        val state = ConnectionStateStabilizer(unavailableThreshold = 3)

        assertEquals(ServerConnection.GLOBAL, state.update(ServerConnection.GLOBAL))
        assertEquals(ServerConnection.GLOBAL, state.update(ServerConnection.SERVER_UNAVAILABLE))
        assertEquals(ServerConnection.GLOBAL, state.update(ServerConnection.SERVER_UNAVAILABLE))
        assertEquals(ServerConnection.SERVER_UNAVAILABLE, state.update(ServerConnection.SERVER_UNAVAILABLE))
        assertEquals(ServerConnection.GLOBAL, state.update(ServerConnection.GLOBAL))
    }

    @Test fun initialFailureWaitsForConfirmationButOfflineIsImmediate() {
        val state = ConnectionStateStabilizer(unavailableThreshold = 3)

        assertEquals(ServerConnection.CHECKING, state.update(ServerConnection.SERVER_UNAVAILABLE))
        assertEquals(ServerConnection.CHECKING, state.update(ServerConnection.SERVER_UNAVAILABLE))
        assertEquals(ServerConnection.SERVER_UNAVAILABLE, state.update(ServerConnection.SERVER_UNAVAILABLE))
        assertEquals(ServerConnection.OFFLINE, state.update(ServerConnection.OFFLINE))
    }
}
