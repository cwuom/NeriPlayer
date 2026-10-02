package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class SyncDeletionFileStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `file writes outside a preparation batch cannot change existing state or the caller editor`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val stable = SecureTokenStorage(prefs.preferences, directory)
        stable.addPlaylistSongDeletions(listOf(deletion(1)))
        val filesBefore = directory.listFiles().orEmpty().associate { it.name to it.readBytes() }
        val files = SyncDeletionStateStorage(prefs.preferences, directory)

        for (value in listOf(listOf(deletion(2)), null)) {
            val valuesBefore = prefs.values.toMap()
            val durableBefore = prefs.durableValues.toMap()
            val editor = prefs.preferences.edit().putString("unrelated_pending_value", "preserved")

            assertThrows(IllegalStateException::class.java) {
                files.write(editor, KEY_PLAYLIST_SONG_DELETIONS, value)
            }

            assertEquals(valuesBefore, prefs.values.toMap())
            assertEquals(durableBefore, prefs.durableValues.toMap())
            assertEquals(filesBefore.keys, directory.listFiles().orEmpty().map(File::getName).toSet())
            filesBefore.forEach { (name, bytes) -> assertTrue(File(directory, name).readBytes().contentEquals(bytes)) }
            assertTrue(editor.commit())
            assertEquals(valuesBefore + ("unrelated_pending_value" to "preserved"), prefs.values.toMap())
            assertEquals(prefs.values.toMap(), prefs.durableValues.toMap())
            assertEquals(listOf(deletion(1)), stable.getPlaylistSongDeletions())
            assertEquals(1L, stable.getSyncMutationVersion())
        }
    }

    @Test
    fun `file writes outside a preparation batch cannot create the storage directory`() {
        val prefs = MemorySyncPreferences()
        val directory = File(temporary.newFolder(), "generations")
        val files = SyncDeletionStateStorage(prefs.preferences, directory)
        val editor = prefs.preferences.edit()

        assertThrows(IllegalStateException::class.java) {
            files.write(editor, KEY_PLAYLIST_SONG_DELETIONS, listOf(deletion(1)))
        }

        assertFalse(directory.exists())
        assertTrue(editor.commit())
        assertTrue(prefs.values.isEmpty())
        assertTrue(prefs.durableValues.isEmpty())
    }

    @Test
    fun `inline storage retains the caller editor contract without a file preparation batch`() {
        val prefs = MemorySyncPreferences()
        val files = SyncDeletionStateStorage(prefs.preferences)
        val store = SecureTokenStorage(prefs.preferences)
        val editor = prefs.preferences.edit()

        files.write(editor, KEY_PLAYLIST_SONG_DELETIONS, listOf(deletion(1)))
        assertTrue(prefs.values.isEmpty())
        assertTrue(editor.commit())
        assertEquals(listOf(deletion(1)), store.getPlaylistSongDeletions())
        assertEquals(listOf(deletion(1)), SecureTokenStorage(prefs.restart().preferences).getPlaylistSongDeletions())

        val clear = prefs.preferences.edit()
        files.write(clear, KEY_PLAYLIST_SONG_DELETIONS, null)
        assertEquals(listOf(deletion(1)), store.getPlaylistSongDeletions())
        assertTrue(clear.commit())
        assertFalse(prefs.values.containsKey(KEY_PLAYLIST_SONG_DELETIONS))
        assertFalse(prefs.durableValues.containsKey(KEY_PLAYLIST_SONG_DELETIONS))
    }

    @Test
    fun `legacy tombstones migrate into durable files without truncation`() {
        val prefs = MemorySyncPreferences()
        prefs.values[KEY_PLAYLIST_SONG_DELETIONS] = """[{"playlistId":7,"songId":1,"album":"netease","deletedAt":10}]"""
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        assertEquals(1L, store.getPlaylistSongDeletions().single().songId)
        store.addPlaylistSongDeletions((2L..5_001L).map(::deletion))
        val reopened = SecureTokenStorage(prefs.preferences, directory)
        assertEquals(5_001, reopened.getPlaylistSongDeletions().size)
        assertEquals(1L, reopened.getSyncMutationVersion())
        assertTrue((prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String).startsWith("@file-v1:"))
        assertTrue(directory.listFiles().orEmpty().count { it.extension == "json" } >= 2)
    }

    @Test
    fun `corruption and missing generations stop synchronization`() {
        for (remove in listOf(false, true)) {
            val prefs = MemorySyncPreferences()
            val directory = temporary.newFolder()
            val store = SecureTokenStorage(prefs.preferences, directory)
            store.addPlaylistSongDeletions(listOf(deletion(1)))
            val marker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String
            val file = File(directory, marker.split(':')[1])
            if (remove) assertTrue(file.delete()) else file.writeText("[]")
            assertThrows(IllegalStateException::class.java) { store.getPlaylistSongDeletions() }
        }
    }

    @Test
    fun `malformed generation markers cannot be read or replaced`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.addPlaylistSongDeletions(listOf(deletion(1)))
        val marker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String
        val name = marker.split(':')[1]
        val hash = marker.split(':')[2]
        val invalidMarkers = listOf(
            "@file-v1:$name",
            "@file-v1:../$name:$hash",
            "@file-v1:invalid.json:$hash",
            "@file-v1:$name:${hash.dropLast(1)}",
            "@file-v1:$name:${"g".repeat(64)}",
            "$marker:extra"
        )

        invalidMarkers.forEach { invalid ->
            prefs.values[KEY_PLAYLIST_SONG_DELETIONS] = invalid
            assertThrows(IllegalStateException::class.java) { store.getPlaylistSongDeletions() }
            assertThrows(IllegalStateException::class.java) { store.setPlaylistSongDeletions(emptyList()) }
            assertEquals(invalid, prefs.values[KEY_PLAYLIST_SONG_DELETIONS])
            assertEquals(1L, store.getSyncMutationVersion())
            assertTrue(File(directory, name).isFile)
        }
    }

    @Test
    fun `new directory and intentional clear preserve the previous complete generation`() {
        val prefs = MemorySyncPreferences()
        val directory = File(temporary.newFolder(), "generations")
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.addPlaylistSongDeletions(listOf(deletion(1)))
        assertTrue(directory.isDirectory)
        val marker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String

        store.setPlaylistSongDeletions(emptyList())

        assertFalse(prefs.values.containsKey(KEY_PLAYLIST_SONG_DELETIONS))
        assertEquals(marker, prefs.values["${KEY_PLAYLIST_SONG_DELETIONS}_previous_generation"])
        assertTrue(File(directory, marker.split(':')[1]).isFile)
        assertTrue(SecureTokenStorage(prefs.preferences, directory).getPlaylistSongDeletions().isEmpty())
        assertEquals(1L, store.getSyncMutationVersion())
    }

    @Test
    fun `directory sync failure cannot publish a renamed generation`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val stable = SecureTokenStorage(prefs.preferences, directory)
        stable.addPlaylistSongDeletions(listOf(deletion(1)))
        val marker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String
        val failing = SecureTokenStorage(prefs.preferences, directory) {
            throw IOException("directory sync failed")
        }

        assertThrows(IllegalStateException::class.java) { failing.addPlaylistSongDeletions(listOf(deletion(2))) }

        assertEquals(marker, prefs.values[KEY_PLAYLIST_SONG_DELETIONS])
        assertEquals(listOf(1L), SecureTokenStorage(prefs.preferences, directory).getPlaylistSongDeletions().map { it.songId })
        assertEquals(1L, stable.getSyncMutationVersion())
        assertTrue(File(directory, marker.split(':')[1]).isFile)
    }

    @Test
    fun `repeated directory sync failures leave no new generations and preserve all previously returned state`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val stable = SecureTokenStorage(prefs.preferences, directory)
        val lyrics = SyncSong(id = 9, album = "netease", lyricSyncEdited = true, lyricSyncRevision = 100,
            matchedLyric = "edited original\n".repeat(4096), matchedTranslatedLyric = "edited translation\n".repeat(4096),
            matchedRomanizedLyric = "edited romanization\n".repeat(4096), originalLyric = "original baseline\n".repeat(4096),
            originalTranslatedLyric = "translated baseline\n".repeat(4096), originalRomanizedLyric = "romanized baseline\n".repeat(4096))
        stable.recordLyricOverride(lyrics)
        stable.addPlaylistSongDeletions(listOf(deletion(1)))
        stable.addPlaylistSongDeletions(listOf(deletion(2)))
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { stable.addPlaylistSongDeletions(listOf(deletion(3))) }
        File(directory, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.json").writeText("unknown generation")
        File(directory, "future-format.data").writeText("unknown document")
        val valuesBefore = prefs.values.toMap()
        val durableBefore = prefs.durableValues.toMap()
        val filesBefore = directory.listFiles().orEmpty().associate { it.name to it.readBytes() }
        val bytesBefore = filesBefore.values.sumOf { it.size.toLong() }
        val pendingTombstones = stable.getPlaylistSongDeletions()
        val durableTombstones = SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistSongDeletions()
        val versionBefore = stable.getSyncMutationVersion()
        var attempts = 0
        val failing = SecureTokenStorage(prefs.preferences, directory) {
            attempts++
            throw IOException("directory sync failed")
        }

        repeat(20) { index ->
            assertThrows(IllegalStateException::class.java) { failing.addPlaylistSongDeletions(listOf(deletion(4L + index))) }
            assertEquals(valuesBefore, prefs.values.toMap())
            assertEquals(durableBefore, prefs.durableValues.toMap())
        }

        assertEquals(20, attempts)
        assertEquals(bytesBefore, directory.listFiles().orEmpty().sumOf { it.length() })
        assertEquals(filesBefore.keys, directory.listFiles().orEmpty().map { it.name }.toSet())
        filesBefore.forEach { (name, bytes) -> assertTrue(File(directory, name).readBytes().contentEquals(bytes)) }
        assertEquals(pendingTombstones, stable.getPlaylistSongDeletions())
        assertEquals(durableTombstones, SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistSongDeletions())
        assertEquals(versionBefore, stable.getSyncMutationVersion())
        assertEquals(listOf(lyrics), stable.getLyricOverrides())
        assertEquals(listOf(lyrics), SecureTokenStorage(prefs.restart().preferences, directory).getLyricOverrides())
    }

    @Test
    fun `failed current generations cannot orphan successfully converted inline predecessors`() {
        val inline = """[{"playlistId":7,"songId":1,"album":"netease","deletedAt":1}]"""
        val initial = mapOf(KEY_PLAYLIST_SONG_DELETIONS to inline, KEY_SYNC_MUTATION_VERSION to 7L)
        val prefs = MemorySyncPreferences(initial)
        val directory = temporary.newFolder()
        var attempts = 0
        val failing = SecureTokenStorage(prefs.preferences, directory) {
            attempts++
            if (attempts % 2 == 0) throw IOException("current generation directory sync failed")
        }

        repeat(20) { index ->
            assertThrows(IllegalStateException::class.java) { failing.addPlaylistSongDeletions(listOf(deletion(2L + index))) }
            assertEquals(initial, prefs.values.toMap())
            assertEquals(initial, prefs.durableValues.toMap())
        }

        assertEquals(40, attempts)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertEquals(listOf(deletion(1)), failing.getPlaylistSongDeletions())
        assertEquals(7L, failing.getSyncMutationVersion())
        assertEquals(listOf(deletion(1)), SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistSongDeletions())
    }

    @Test
    fun `failed generation cleanup keeps the directory error and attempts both owned paths`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val stable = SecureTokenStorage(prefs.preferences, directory)
        stable.addPlaylistSongDeletions(listOf(deletion(1)))
        val valuesBefore = prefs.values.toMap()
        val filesBefore = directory.listFiles().orEmpty().associate { it.name to it.readBytes() }
        val syncFailure = IOException("directory sync failed")
        var failedDestination: File? = null
        var failedTemporary: File? = null
        val failing = SecureTokenStorage(prefs.preferences, directory) { path ->
            val destination = path.listFiles().orEmpty().single { it.name !in filesBefore.keys }
            val body = destination.readBytes()
            check(destination.delete())
            check(destination.mkdir())
            File(destination, "deletion-obstacle").writeBytes(body)
            val temporary = File(path, "${destination.name}.tmp")
            check(temporary.mkdir())
            File(temporary, "deletion-obstacle").writeText("synthetic")
            failedDestination = destination
            failedTemporary = temporary
            throw syncFailure
        }

        val failure = assertThrows(IllegalStateException::class.java) { failing.addPlaylistSongDeletions(listOf(deletion(2))) }

        assertSame(syncFailure, failure.cause)
        assertEquals(2, syncFailure.suppressed.size)
        assertTrue(syncFailure.suppressed.all { it is IOException })
        assertTrue(requireNotNull(failedDestination).isDirectory)
        assertTrue(requireNotNull(failedTemporary).isDirectory)
        assertEquals(valuesBefore, prefs.values.toMap())
        filesBefore.forEach { (name, bytes) -> assertTrue(File(directory, name).readBytes().contentEquals(bytes)) }
        assertEquals(listOf(deletion(1)), stable.getPlaylistSongDeletions())
        assertEquals(1L, stable.getSyncMutationVersion())
    }

    @Test
    fun `cancelled preparation keeps the cancellation and reclaims only that unpublished batch`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val unknown = File(directory, "future-format.data").apply { writeText("preserved") }
        val files = SyncDeletionStateStorage(prefs.preferences, directory)
        val cancellation = CancellationException("cancelled before marker commit")

        val failure = assertThrows(CancellationException::class.java) {
            files.commitEdit {
                files.write(this, KEY_PLAYLIST_SONG_DELETIONS, listOf(deletion(1)))
                throw cancellation
            }
        }

        assertSame(cancellation, failure)
        assertEquals(listOf(unknown), directory.listFiles().orEmpty().toList())
        assertEquals("preserved", unknown.readText())
        assertTrue(prefs.values.isEmpty())
        assertTrue(prefs.durableValues.isEmpty())
    }

    @Test
    fun `an exception from marker commit cannot remove already published generations`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val commitFailure = IOException("marker acknowledgement unavailable")
        val uncertain = mock(SharedPreferences::class.java) { call ->
            if (call.method.name == "edit") {
                val delegate = prefs.preferences.edit()
                mock(SharedPreferences.Editor::class.java) { edit ->
                    if (edit.method.name == "commit") {
                        delegate.commit()
                        throw commitFailure
                    }
                    edit.method.invoke(delegate, *edit.arguments)
                }
            } else call.method.invoke(prefs.preferences, *call.arguments)
        }
        val files = SyncDeletionStateStorage(uncertain, directory)

        val failure = assertThrows(IOException::class.java) {
            files.commitEdit { files.write(this, KEY_PLAYLIST_SONG_DELETIONS, listOf(deletion(1))) }
        }

        assertSame(commitFailure, failure)
        assertEquals(prefs.values.toMap(), prefs.durableValues.toMap())
        val marker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String
        assertTrue(File(directory, marker.split(':')[1]).isFile)
        assertEquals(listOf(deletion(1)), SecureTokenStorage(prefs.restart().preferences, directory).getPlaylistSongDeletions())
    }

    @Test
    fun `failed marker commit preserves the previous complete generation`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.addPlaylistSongDeletions(listOf(deletion(1)))
        val previous = prefs.values[KEY_PLAYLIST_SONG_DELETIONS]
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { store.addPlaylistSongDeletions(listOf(deletion(2))) }
        assertEquals(previous, prefs.durableValues[KEY_PLAYLIST_SONG_DELETIONS])
        assertEquals(listOf(2L, 1L), store.getPlaylistSongDeletions().map { it.songId })
        assertEquals(2L, store.getSyncMutationVersion())
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals(listOf(1L), restarted.getPlaylistSongDeletions().map { it.songId })
        assertEquals(1L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `unwritable file directory cannot advance mutation epoch`() {
        val prefs = MemorySyncPreferences()
        val path = temporary.newFile()
        val store = SecureTokenStorage(prefs.preferences, path)
        assertThrows(IllegalStateException::class.java) { store.addPlaylistSongDeletions(listOf(deletion(1))) }
        assertFalse(prefs.values.containsKey(KEY_PLAYLIST_SONG_DELETIONS))
        assertEquals(0L, store.getSyncMutationVersion())
    }

    @Test
    fun `successful commits keep the current and previous complete generations`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.addPlaylistSongDeletions(listOf(deletion(1)))
        val firstMarker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String
        store.addPlaylistSongDeletions(listOf(deletion(2)))
        val secondMarker = prefs.values[KEY_PLAYLIST_SONG_DELETIONS] as String
        store.addPlaylistSongDeletions(listOf(deletion(3)))
        val files = directory.listFiles().orEmpty().map(File::getName).toSet()

        assertEquals(2, files.size)
        assertFalse(firstMarker.split(':')[1] in files)
        assertTrue(secondMarker.split(':')[1] in files)
        assertEquals(listOf(3L, 2L, 1L), store.getPlaylistSongDeletions().map { it.songId })
    }

    private fun deletion(id: Long) = SyncPlaylistSongDeletion(playlistId = 7, songId = id, album = "netease", deletedAt = id)
}
