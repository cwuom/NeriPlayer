package moe.ouom.neriplayer.core.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal const val STARTUP_WORK_GATE_TIMEOUT_MS = 3_000L

internal class InteractiveStartupWorkGate {
    private val interactiveContentReady = CompletableDeferred<Unit>()

    fun markInteractiveContentReady() {
        interactiveContentReady.complete(Unit)
    }

    suspend fun awaitInteractiveContentOrTimeout(
        timeoutMillis: Long = STARTUP_WORK_GATE_TIMEOUT_MS
    ): Boolean {
        if (interactiveContentReady.isCompleted) return true
        return withTimeoutOrNull(timeoutMillis.coerceAtLeast(0L)) {
            interactiveContentReady.await()
            true
        } == true
    }
}

internal object AppStartupWorkGate {
    private val gate = InteractiveStartupWorkGate()

    fun markInteractiveContentReady() {
        gate.markInteractiveContentReady()
    }

    suspend fun awaitInteractiveContentOrTimeout(): Boolean {
        return gate.awaitInteractiveContentOrTimeout()
    }
}
