package moe.ouom.neriplayer.data.sync.store.state

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDeviceStateStoreTest {
    @Test
    fun `existing device and durable token counter survive reconstruction`() {
        val prefs = MemorySyncPreferences(mapOf("device_id" to "old-device", "sync_causal_counter" to 41L))
        val first = SecureTokenStorage(prefs.preferences).nextSyncCausalTokens(2)
        val next = SecureTokenStorage(prefs.preferences).nextSyncCausalTokens(1)
        assertEquals(listOf(42L, 43L, 44L), (first + next).map { it.counter })
        assertTrue((first + next).all { it.deviceId == "old-device" })
        assertEquals(44L, prefs.values["sync_causal_counter"])
    }

    @Test
    fun `changing device resets counter while saving same device preserves it`() {
        val prefs = MemorySyncPreferences(mapOf("device_id" to "old", "sync_causal_counter" to 9L))
        val store = SecureTokenStorage(prefs.preferences)
        store.saveDeviceId("old")
        assertEquals(9L, prefs.values["sync_causal_counter"])
        store.saveDeviceId("new")
        assertEquals(1L, store.nextSyncCausalTokens(1).single().counter)
        assertEquals(setOf("device_id", "sync_causal_counter"), prefs.commits[1])
    }

    @Test
    fun `failed durable reservation never returns tokens`() {
        val prefs = MemorySyncPreferences(mapOf("device_id" to "existing"))
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) {
            SecureTokenStorage(prefs.preferences).nextSyncCausalTokens(2)
        }
    }

    @Test
    fun `invalid and overflowing counters cannot issue tokens`() {
        for (counter in listOf(-1L, Long.MAX_VALUE)) {
            val prefs = MemorySyncPreferences(mapOf("device_id" to "existing", "sync_causal_counter" to counter))
            assertThrows(RuntimeException::class.java) {
                SecureTokenStorage(prefs.preferences).nextSyncCausalTokens(1)
            }
            assertTrue(prefs.commits.isEmpty())
        }
    }

    @Test
    fun `empty token request does not create device and negative request fails`() {
        val prefs = MemorySyncPreferences()
        val store = SecureTokenStorage(prefs.preferences)
        assertTrue(store.nextSyncCausalTokens(0).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.nextSyncCausalTokens(-1) }
        assertThrows(IllegalArgumentException::class.java) { store.saveDeviceId(" ") }
        assertTrue(prefs.commits.isEmpty())
    }

    @Test
    fun `failed device save is reported`() {
        val prefs = MemorySyncPreferences()
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) {
            SecureTokenStorage(prefs.preferences).saveDeviceId("new")
        }
    }

    @Test
    fun `concurrent instances share one generated device identity`() {
        val prefs = MemorySyncPreferences().apply { delayMissingDeviceRead = true }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map {
                executor.submit<String> {
                    start.await()
                    SecureTokenStorage(prefs.preferences).getOrCreateDeviceId()
                }
            }
            start.countDown()
            val identities = results.map { it.get(5, TimeUnit.SECONDS) }.toSet()
            assertEquals(1, identities.size)
            assertNotNull(prefs.values["device_id"])
            assertEquals(identities.single(), prefs.values["device_id"])
        } finally {
            executor.shutdownNow()
        }
    }
}
