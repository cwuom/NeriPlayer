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
    fun `first reservation creates a durable identity and range in one commit`() {
        val prefs = MemorySyncPreferences()
        val token = SecureTokenStorage(prefs.preferences).nextSyncCausalTokens(1).single()
        assertTrue(token.deviceId.isNotBlank())
        assertEquals(1L, token.counter)
        assertEquals(listOf(setOf("device_id", "sync_causal_counter")), prefs.commits)
        val reopened = SecureTokenStorage(prefs.restart().preferences).nextSyncCausalTokens(1).single()
        assertEquals(token.deviceId, reopened.deviceId)
        assertEquals(2L, reopened.counter)
    }

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
    fun `device changes retain the high watermark so an actor cannot reuse counters`() {
        val prefs = MemorySyncPreferences(mapOf("device_id" to "old", "sync_causal_counter" to 9L))
        val store = SecureTokenStorage(prefs.preferences)
        store.saveDeviceId("old")
        assertEquals(9L, prefs.values["sync_causal_counter"])
        store.saveDeviceId("new")
        assertEquals(10L, store.nextSyncCausalTokens(1).single().counter)
        assertEquals(setOf("device_id"), prefs.commits[1])
        assertEquals(setOf("device_id", "sync_causal_counter"), prefs.commits.last())
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
    fun `volatile device after failed commit cannot be returned until durable confirmation`() {
        val prefs = MemorySyncPreferences()
        val first = SecureTokenStorage(prefs.preferences)
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { first.saveDeviceId("new-device") }
        assertEquals("new-device", prefs.values["device_id"])
        assertEquals(null, prefs.restart().values["device_id"])

        prefs.failNextCommit = true
        val second = SecureTokenStorage(prefs.preferences)
        assertThrows(IllegalStateException::class.java) { second.getOrCreateDeviceId() }
        val confirmed = second.getOrCreateDeviceId()
        assertEquals("new-device", confirmed)
        assertEquals(confirmed, SecureTokenStorage(prefs.restart().preferences).getOrCreateDeviceId())
    }

    @Test
    fun `causal reservation commits device identity and range together after a failed identity save`() {
        val prefs = MemorySyncPreferences()
        val store = SecureTokenStorage(prefs.preferences)
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { store.saveDeviceId("new-device") }
        val tokens = store.nextSyncCausalTokens(2)
        assertEquals(setOf("device_id", "sync_causal_counter"), prefs.commits.last())
        val reopened = SecureTokenStorage(prefs.restart().preferences).nextSyncCausalTokens(1).single()
        assertEquals(listOf(1L, 2L), tokens.map { it.counter })
        assertEquals(3L, reopened.counter)
        assertTrue((tokens + reopened).all { it.deviceId == "new-device" })
    }

    @Test
    fun `failed identity change and revert cannot reuse an existing actor counter`() {
        val prefs = MemorySyncPreferences(mapOf("device_id" to "old", "sync_causal_counter" to 9L))
        val store = SecureTokenStorage(prefs.preferences)
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { store.saveDeviceId("new") }
        store.saveDeviceId("old")
        val token = store.nextSyncCausalTokens(1).single()
        assertEquals("old", token.deviceId)
        assertEquals(10L, token.counter)
        assertEquals(11L, SecureTokenStorage(prefs.restart().preferences).nextSyncCausalTokens(1).single().counter)
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
