package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearEstimate
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageCacheEstimateStateTest {
    @Test
    fun `new slider value hides previous estimate before the next request starts`() = runTest {
        val state = StorageCacheEstimateState()
        val first = request(0)
        state.update(first) { PlayerCacheClearEstimate(100, 30) }
        assertEquals(100L, state.estimateFor(first)?.audioBytes)
        assertNull(state.estimateFor(request(10)))
        assertTrue(state.isPending(request(10)))
        state.update(request(10)) { PlayerCacheClearEstimate(60, 10) }
        assertEquals(60L, state.estimateFor(request(10))?.audioBytes)
        assertFalse(state.isPending(request(10)))
    }

    @Test
    fun `late response cannot replace the current slider estimate`() = runTest {
        val state = StorageCacheEstimateState()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            state.update(request(0)) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                PlayerCacheClearEstimate(100, 30)
            }
        }
        entered.await()
        state.update(request(1000)) { PlayerCacheClearEstimate(0, 0) }
        release.complete(Unit)
        first.await()
        assertEquals(PlayerCacheClearEstimate(0, 0), state.estimateFor(request(1000)))
        assertNull(state.estimateFor(request(0)))
    }

    @Test
    fun `failed estimate completes as unknown and reset invalidates it`() = runTest {
        val state = StorageCacheEstimateState()
        state.update(request(10)) { error("cache unavailable") }
        assertNull(state.estimateFor(request(10)))
        assertFalse(state.isPending(request(10)))
        state.reset()
        assertTrue(state.isPending(request(10)))
    }

    @Test
    fun `unknown media estimate never falls back to total usage or a zero sum`() {
        val storage = StorageUsageSummary.Empty
        assertNull(estimatedCacheBytes(StorageCacheKind.Audio, storage, null))
        assertEquals(12L, estimatedCacheBytes(StorageCacheKind.Image, storage, PlayerCacheClearEstimate(null, 12)))
        assertEquals(0L, estimatedCacheBytes(StorageCacheKind.Other, storage, null))
        assertNull(listOf(10L, null, 20L).sumKnownCacheBytes())
        assertEquals(30L, listOf(10L, 20L).sumKnownCacheBytes())
        assertEquals(0L, emptyList<Long?>().sumKnownCacheBytes())
    }

    private fun request(count: Int) = StorageCacheEstimateRequest(count, StorageUsageSummary.Empty)
}
