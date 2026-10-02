package moe.ouom.neriplayer.data.sync.runtime

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLocalDataApplierTest {
    @Test
    fun `all sections are applied with the same mutation ticket`() = runTest {
        val host = MemoryHost()
        assertTrue(SyncLocalDataApplier(host).apply(SyncData(deviceId = "snapshot"), true, 42L))
        assertEquals(listOf("deletions", "playlists", "favorites", "history", "statistics", "skip"), host.sections)
        assertTrue(host.historyChanged)
    }

    @Test
    fun `rejected epoch stops subsequent local writes`() = runTest {
        val order = listOf("deletions", "playlists", "favorites", "history", "statistics", "skip")
        for (rejected in order - "statistics") {
            val host = MemoryHost(rejected)
            assertFalse(SyncLocalDataApplier(host).apply(SyncData(deviceId = "snapshot"), false, 42L))
            assertEquals(order.take(order.indexOf(rejected) + 1), host.sections)
            assertFalse(host.historyChanged)
        }
    }

    @Test
    fun `failed permanent state commit prevents containers and any later failure stops subsequent writes`() = runTest {
        val order = listOf("deletions", "playlists", "favorites", "history", "statistics", "skip")
        for (failed in order) {
            val host = MemoryHost(failed = failed)
            val failure = runCatching {
                SyncLocalDataApplier(host).apply(SyncData(deviceId = "snapshot"), false, 42L)
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(order.take(order.indexOf(failed) + 1), host.sections)
        }
    }

    private class MemoryHost(private val rejected: String? = null, private val failed: String? = null) : SyncLocalApplyHost {
        val sections = mutableListOf<String>()
        var historyChanged = false
        private fun apply(section: String, data: SyncData, ticket: Long): Boolean {
            assertEquals("snapshot", data.deviceId)
            assertEquals(42L, ticket)
            record(section)
            return section != rejected
        }
        private fun record(section: String) {
            sections += section
            if (section == failed) throw IllegalStateException("Failed local section: $section")
        }
        override suspend fun applyPlaylists(data: SyncData, expectedMutationVersion: Long) = apply("playlists", data, expectedMutationVersion)
        override fun applyDeletions(data: SyncData, expectedMutationVersion: Long) = apply("deletions", data, expectedMutationVersion)
        override suspend fun applyFavorites(data: SyncData, expectedMutationVersion: Long) = apply("favorites", data, expectedMutationVersion)
        override suspend fun applyHistory(data: SyncData, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
            historyChanged = remoteChanged
            return apply("history", data, expectedMutationVersion)
        }
        override suspend fun applyStatistics(data: SyncData) { record("statistics") }
        override suspend fun applyVideoSkipRules(data: SyncData, expectedMutationVersion: Long) = apply("skip", data, expectedMutationVersion)
    }
}
