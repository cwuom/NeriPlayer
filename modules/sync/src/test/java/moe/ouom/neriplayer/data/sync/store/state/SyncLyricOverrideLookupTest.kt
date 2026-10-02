package moe.ouom.neriplayer.data.sync.store.state

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncLyricOverrideLookupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `failed edit cannot be acknowledged by original new same process or empty filtered lookup`() {
        assertPendingOverrideRequiresCheckedConfirmation(reset = false)
    }

    @Test
    fun `failed reset cannot be acknowledged by original new same process or empty filtered lookup`() {
        assertPendingOverrideRequiresCheckedConfirmation(reset = true)
    }

    private fun assertPendingOverrideRequiresCheckedConfirmation(reset: Boolean) {
        val fixture = Fixture()
        val original = edit(1)
        fixture.write(listOf(original))
        val durableMarker = fixture.prefs.durableValues[KEY_LYRIC_OVERRIDES]
        val pending = original.copy(
            matchedLyric = if (reset) null else "pending user edit",
            matchedTranslatedLyric = if (reset) null else "pending translation",
            matchedRomanizedLyric = if (reset) null else "pending romanized",
            lyricSyncEdited = !reset,
            lyricSyncRevision = 21
        )
        fixture.prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { fixture.store.recordLyricOverride(pending) }
        val pendingMarker = fixture.prefs.values[KEY_LYRIC_OVERRIDES]
        assertNotEquals(durableMarker, pendingMarker)
        assertEquals(durableMarker, fixture.prefs.durableValues[KEY_LYRIC_OVERRIDES])
        val observer = SecureTokenStorage(fixture.prefs.preferences, fixture.directory)
        for (storage in listOf(fixture.store, observer)) {
            for (identityKeys in listOf(setOf("1|netease|"), emptySet())) {
                fixture.prefs.failNextCommit = true
                assertThrows(IllegalStateException::class.java) {
                    storage.getLyricOverridesForIdentityKeys(identityKeys)
                }
                assertEquals(durableMarker, fixture.prefs.durableValues[KEY_LYRIC_OVERRIDES])
                val restarted = SecureTokenStorage(fixture.prefs.restart().preferences, fixture.directory)
                val durable = restarted.getLyricOverridesForIdentityKeys(setOf("1|netease|")).single()
                assertEquals(20L, durable.lyricSyncRevision)
                assertEquals(true, durable.lyricSyncEdited)
                assertEquals(original.matchedLyric, durable.matchedLyric)
            }
        }
        fixture.prefs.failNextCommit = false
        val confirmed = observer.getLyricOverridesForIdentityKeys(setOf("1|netease|")).single()
        assertEquals(21L, confirmed.lyricSyncRevision)
        assertEquals(!reset, confirmed.lyricSyncEdited)
        assertEquals(pending.matchedLyric, confirmed.matchedLyric)
        assertEquals(pending.matchedTranslatedLyric, confirmed.matchedTranslatedLyric)
        assertEquals(pending.matchedRomanizedLyric, confirmed.matchedRomanizedLyric)
        assertEquals(pendingMarker, fixture.prefs.durableValues[KEY_LYRIC_OVERRIDES])
        assertTrue(observer.getLyricOverridesForIdentityKeys(emptySet()).isEmpty())
        val reopened = SecureTokenStorage(fixture.prefs.restart().preferences, fixture.directory)
        assertEquals(confirmed, reopened.getLyricOverridesForIdentityKeys(setOf("1|netease|")).single())
    }

    @Test
    fun `filtered generation retains only target identities and resolves duplicate resets and legacy edits`() {
        val fixture = Fixture()
        val records = (1L..1_501L).map { edit(it) }.toMutableList()
        records += edit(7).copy(album = "Netease", lyricSyncEdited = false, lyricSyncRevision = 20)
        records[998] = edit(999).copy(lyricSyncEdited = null, lyricSyncRevision = 0,
            originalLyric = "network baseline", matchedLyricSource = "CLOUD_MUSIC")
        fixture.write(records)
        var scanned = 0

        val result = fixture.store.getLyricOverridesForIdentityKeys(setOf("7|netease|", "999|netease|")) { scanned++ }

        assertEquals(2, result.size)
        val reset = result.single { it.id == 7L }
        assertEquals(false, reset.lyricSyncEdited)
        assertNull(reset.matchedLyric)
        assertNull(reset.matchedTranslatedLyric)
        assertNull(reset.matchedRomanizedLyric)
        val edited = result.single { it.id == 999L }
        assertEquals(true, edited.lyricSyncEdited)
        assertEquals(1L, edited.lyricSyncRevision)
        assertEquals("network baseline", edited.originalLyric)
        assertEquals("edit 999", edited.matchedLyric)
        assertEquals("translation", edited.matchedTranslatedLyric)
        assertEquals("romanized", edited.matchedRomanizedLyric)
        assertTrue(scanned >= records.size)
    }

    @Test
    fun `invalid unselected records and trailing documents reject the entire filtered result`() {
        for (tail in listOf("null", "{\"id\":2,\"album\":null}", "{\"id\":2,\"lyricSyncRevision\":\"bad\"}")) {
            val fixture = Fixture()
            fixture.write(listOf(edit(1)))
            fixture.replaceDocument(fixture.file().readText().dropLast(1) + ",$tail]")
            assertThrows(IllegalStateException::class.java) {
                fixture.store.getLyricOverridesForIdentityKeys(setOf("1|netease|"))
            }
        }
        val fixture = Fixture()
        fixture.write(listOf(edit(1)))
        fixture.replaceDocument(fixture.file().readText() + " []")
        assertThrows(IllegalStateException::class.java) {
            fixture.store.getLyricOverridesForIdentityKeys(setOf("1|netease|"))
        }
    }

    @Test
    fun `unselected checksum corruption and missing generations cannot acknowledge an empty lookup`() {
        for (missing in listOf(false, true)) {
            val fixture = Fixture()
            fixture.write(listOf(edit(1), edit(2)))
            if (missing) assertTrue(fixture.file().delete()) else fixture.file().appendText(" ")
            assertThrows(IllegalStateException::class.java) {
                fixture.store.getLyricOverridesForIdentityKeys(emptySet())
            }
        }
    }

    @Test
    fun `generation rotation during unlocked scanning retries the latest complete generation`() {
        val fixture = Fixture()
        fixture.write(listOf(edit(1), edit(2)))
        val original = fixture.file()
        var rotated = false
        val result = fixture.store.getLyricOverridesForIdentityKeys(setOf("1|netease|")) {
            assertFalse(Thread.holdsLock(syncMutationLock))
            if (!rotated) {
                rotated = true
                fixture.write(listOf(edit(1).copy(matchedLyric = "new", lyricSyncRevision = 21)))
                fixture.write(listOf(edit(1).copy(matchedLyric = "latest", lyricSyncRevision = 22)))
                assertFalse(original.exists())
            }
        }
        assertEquals("latest", result.single().matchedLyric)
        assertEquals(22L, result.single().lyricSyncRevision)
    }

    @Test
    fun `cancellation propagates and never returns already matched records`() {
        val fixture = Fixture()
        fixture.write(listOf(edit(1), edit(2)))
        val cancellation = CancellationException("lookup cancelled")
        var checked = 0
        val result = runCatching {
            fixture.store.getLyricOverridesForIdentityKeys(setOf("1|netease|")) {
                if (++checked == 3) throw cancellation
            }
        }
        assertSame(cancellation, result.exceptionOrNull())
    }

    @Test
    fun `inline legacy and missing registry use the same filtered normalization`() {
        val fixture = Fixture()
        assertTrue(fixture.store.getLyricOverridesForIdentityKeys(setOf("1|netease|")).isEmpty())
        fixture.prefs.values[KEY_LYRIC_OVERRIDES] = """[{"id":1,"album":"Netease","matchedLyric":"manual","matchedLyricSource":"CLOUD_MUSIC"},{"id":2,"album":"netease","matchedLyric":"cached","lyricSyncEdited":false}]"""
        val selected = fixture.store.getLyricOverridesForIdentityKeys(setOf("1|netease|", "2|netease|"))
        assertEquals(1L, selected.single().id)
        assertEquals(true, selected.single().lyricSyncEdited)
        assertEquals(1L, selected.single().lyricSyncRevision)
        assertEquals("manual", selected.single().matchedLyric)
    }

    private inner class Fixture {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        private val files = SyncDeletionStateStorage(prefs.preferences, directory)
        val store = SecureTokenStorage(prefs.preferences, directory)

        fun write(records: List<SyncSong>) {
            assertTrue(files.commitEdit { files.write(this, KEY_LYRIC_OVERRIDES, records) })
        }

        fun file(): File = File(directory, (prefs.values[KEY_LYRIC_OVERRIDES] as String).split(':')[1])

        fun replaceDocument(text: String) {
            val file = file()
            file.writeText(text)
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            prefs.values[KEY_LYRIC_OVERRIDES] = "@file-v1:${file.name}:$hash"
        }
    }

    private fun edit(id: Long) = SyncSong(id = id, album = "netease", matchedLyric = "edit $id",
        matchedTranslatedLyric = "translation", matchedRomanizedLyric = "romanized",
        lyricSyncEdited = true, lyricSyncRevision = 20)
}
