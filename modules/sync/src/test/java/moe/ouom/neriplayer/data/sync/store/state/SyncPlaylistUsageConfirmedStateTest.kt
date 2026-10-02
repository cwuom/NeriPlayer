package moe.ouom.neriplayer.data.sync.store.state

import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletion
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletionPolicy
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncPlaylistUsageConfirmedStateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `malformed durable barriers are rejected without replacing their original payload`() {
        val malformed = listOf(
            "invalid document" to "broken",
            "nonstandard JSON" to "[{playlistKey:'local:7',deletedAt:20,deletionTokens:[]}]",
            "trailing document" to "[] []",
            "wrong root type" to "{}",
            "null record" to "[null]",
            "missing fields" to "[{}]",
            "missing time" to "[{\"playlistKey\":\"local:7\",\"deletionTokens\":${tokenJson()}}]",
            "missing tokens" to "[{\"playlistKey\":\"local:7\",\"deletedAt\":20}]",
            "null key" to barrierJson(key = "null"),
            "numeric key" to barrierJson(key = "7"),
            "blank key" to barrierJson(key = "\" \""),
            "null time" to barrierJson(time = "null"),
            "quoted time" to barrierJson(time = "\"20\""),
            "fractional time" to barrierJson(time = "1.5"),
            "overflowed time" to barrierJson(time = "9223372036854775808"),
            "null tokens" to barrierJson(tokens = "null"),
            "object tokens" to barrierJson(tokens = "{}"),
            "empty tokens" to barrierJson(tokens = "[]"),
            "null token" to barrierJson(tokens = "[null]"),
            "missing token fields" to barrierJson(tokens = "[{}]"),
            "missing device" to barrierJson(tokens = "[{\"counter\":1}]"),
            "missing counter" to barrierJson(tokens = "[{\"deviceId\":\"a\"}]"),
            "null device" to barrierJson(tokens = tokenJson(device = "null")),
            "numeric device" to barrierJson(tokens = tokenJson(device = "1")),
            "blank device" to barrierJson(tokens = tokenJson(device = "\" \"")),
            "object counter" to barrierJson(tokens = tokenJson(counter = "{}")),
            "quoted counter" to barrierJson(tokens = tokenJson(counter = "\"1\"")),
            "zero counter" to barrierJson(tokens = tokenJson(counter = "0")),
            "negative counter" to barrierJson(tokens = tokenJson(counter = "-1")),
            "fractional counter" to barrierJson(tokens = tokenJson(counter = "1.5")),
            "overflowed counter" to barrierJson(tokens = tokenJson(counter = "9223372036854775808"))
        )
        for ((reason, raw) in malformed) {
            val original = mapOf(KEY_PLAYLIST_USAGE_DELETION_BARRIERS to raw)
            val prefs = MemorySyncPreferences(original)
            val directory = temporary.newFolder()
            val storage = SecureTokenStorage(prefs.preferences, directory)
            assertThrows(reason, IllegalStateException::class.java) { storage.getPlaylistUsageDeletionBarriersConfirmed() }
            assertEquals(reason, original, prefs.values.toMap())
            assertEquals(reason, original, prefs.durableValues.toMap())
            assertEquals(reason, 0L, storage.getSyncMutationVersion())
            assertTrue(reason, directory.listFiles().orEmpty().isEmpty())
            assertThrows(reason, IllegalStateException::class.java) {
                SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistUsageDeletionBarriersConfirmed()
            }
        }
    }

    @Test
    fun `invalid or exhausted causal allocation cannot partially commit a deletion`() {
        assertAllocationRejected(-1L, IllegalStateException::class.java)
        assertAllocationRejected(Long.MAX_VALUE, ArithmeticException::class.java)
    }

    private fun assertAllocationRejected(counter: Long, failure: Class<out Throwable>) {
        val original = mapOf(KEY_DEVICE_ID to "phone", KEY_SYNC_CAUSAL_COUNTER to counter)
        val prefs = MemorySyncPreferences(original)
        val directory = temporary.newFolder()
        val storage = SecureTokenStorage(prefs.preferences, directory)
        assertThrows(failure) { storage.addPlaylistUsageDeletion("local:7", 20) }
        assertEquals(original, prefs.values.toMap())
        assertEquals(original, prefs.durableValues.toMap())
        assertTrue(prefs.commits.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun barrierJson(key: String = "\"local:7\"", time: String = "20", tokens: String = tokenJson()): String =
        "[{\"playlistKey\":$key,\"deletedAt\":$time,\"deletionTokens\":$tokens}]"

    private fun tokenJson(device: String = "\"usage-delete:a\"", counter: String = "1"): String =
        "[{\"deviceId\":$device,\"counter\":$counter}]"

    @Test
    fun `false barrier commit cannot be confirmed by a new same process store and checked retry is durable`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val storage = SecureTokenStorage(prefs.preferences, directory)
        val deletion = SyncPlaylistUsageDeletion("local:7", listOf(SyncCausalToken("usage-delete:a", 1)), 10)
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { storage.mergePlaylistUsageDeletionBarriers(listOf(deletion)) }
        assertTrue(SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistUsageDeletionBarriersConfirmed().isEmpty())
        for (current in listOf(storage, SecureTokenStorage(prefs.preferences, directory))) {
            prefs.failNextCommit = true
            assertThrows(IllegalStateException::class.java) { current.getPlaylistUsageDeletionBarriersConfirmed() }
            assertTrue(SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistUsageDeletionBarriersConfirmed().isEmpty())
        }
        assertEquals(listOf(deletion), storage.getPlaylistUsageDeletionBarriersConfirmed())
        assertEquals(listOf(deletion), SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistUsageDeletionBarriersConfirmed())
    }

    @Test
    fun `clearing old marker never discards barriers and guarded partial apply only unions tokens`() {
        val prefs = MemorySyncPreferences(mapOf(KEY_PLAYLIST_USAGE_DELETIONS to "{\"local:7\":20}"))
        val directory = temporary.newFolder()
        val storage = SecureTokenStorage(prefs.preferences, directory)
        val legacy = SyncPlaylistUsageDeletionPolicy.fromLegacy(mapOf("local:7" to 20L))
        assertEquals(legacy, storage.getPlaylistUsageDeletionBarriersConfirmed())
        storage.removePlaylistUsageDeletion("local:7")
        assertTrue(storage.getPlaylistUsageDeletionsConfirmed().isEmpty())
        val remote = SyncPlaylistUsageDeletion("local:7", listOf(SyncCausalToken("usage-delete:b", 1)), 1)
        val expected = storage.getSyncMutationVersion()
        assertTrue(!storage.mergePlaylistUsageDeletionBarriersIfMutationVersion(expected - 1, listOf(remote)))
        assertEquals(legacy, storage.getPlaylistUsageDeletionBarriersConfirmed())
        assertTrue(storage.mergePlaylistUsageDeletionBarriersIfMutationVersion(expected, listOf(remote)))
        val merged = SyncPlaylistUsageDeletionPolicy.merge(legacy + remote)
        assertEquals(merged, storage.getPlaylistUsageDeletionBarriersConfirmed())
        assertTrue(storage.mergePlaylistUsageDeletionBarriersIfMutationVersion(expected, emptyList()))
        assertEquals(merged, SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistUsageDeletionBarriersConfirmed())
    }

    @Test
    fun `each delete has a new event even at the same clock and shares the causal allocation range`() {
        val prefs = MemorySyncPreferences(mapOf(KEY_DEVICE_ID to "phone"))
        val storage = SecureTokenStorage(prefs.preferences, temporary.newFolder())
        storage.addPlaylistUsageDeletion("local:7", 20)
        storage.addPlaylistUsageDeletion("local:7", 20)
        val events = storage.getPlaylistUsageDeletionBarriersConfirmed().single().deletionTokens.filter { it.deviceId == "usage-delete:phone" }
        assertEquals(listOf(1L, 2L), events.map { it.counter })
        assertEquals(3L, storage.nextSyncCausalTokens(1).single().counter)
    }

    @Test
    fun `confirmed usage addition rejects ghost RAM until a checked empty commit persists it`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val storage = SecureTokenStorage(prefs.preferences, directory)
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { storage.addPlaylistUsageDeletion("local:7", 20) }
        val pending = mapOf("local:7" to 20L)
        assertEquals(pending, storage.getPlaylistUsageDeletions())
        assertTrue(SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistUsageDeletions().isEmpty())
        val pendingVersion = storage.getSyncMutationVersion()

        repeat(2) {
            prefs.failNextCommit = true
            val current = if (it == 0) storage else SecureTokenStorage(prefs.preferences, directory)
            assertThrows(IllegalStateException::class.java) { current.getPlaylistUsageDeletionsConfirmed() }
            assertEquals(pending, current.getPlaylistUsageDeletions())
            assertTrue(SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistUsageDeletions().isEmpty())
        }
        assertEquals(pending, storage.getPlaylistUsageDeletionsConfirmed())
        val reopened = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals(pending, reopened.getPlaylistUsageDeletionsConfirmed())
        assertEquals(pendingVersion, reopened.getSyncMutationVersion())
    }

    @Test
    fun `confirmed usage removal rejects ghost RAM and preserves old durable generation until retry`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val storage = SecureTokenStorage(prefs.preferences, directory)
        val original = mapOf("local:7" to 20L)
        storage.addPlaylistUsageDeletion("local:7", 20)
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { storage.removePlaylistUsageDeletion("local:7") }
        assertTrue(storage.getPlaylistUsageDeletions().isEmpty())
        val pendingVersion = storage.getSyncMutationVersion()

        repeat(2) {
            prefs.failNextCommit = true
            val current = if (it == 0) storage else SecureTokenStorage(prefs.preferences, directory)
            assertThrows(IllegalStateException::class.java) { current.getPlaylistUsageDeletionsConfirmed() }
            assertEquals(original, SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistUsageDeletions())
        }
        assertTrue(storage.getPlaylistUsageDeletionsConfirmed().isEmpty())
        val reopened = SecureTokenStorage(prefs.restart().preferences, directory)
        assertTrue(reopened.getPlaylistUsageDeletionsConfirmed().isEmpty())
        assertEquals(pendingVersion, reopened.getSyncMutationVersion())
    }
}
