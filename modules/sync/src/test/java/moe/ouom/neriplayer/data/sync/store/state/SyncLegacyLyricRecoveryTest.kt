package moe.ouom.neriplayer.data.sync.store.state

import java.io.File
import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.engine.TestSyncMergeHost
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncLegacyLyricRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val legacy = SyncSong(id = 1, album = "netease", matchedLyric = "unknown old lyrics",
        matchedTranslatedLyric = "old translation", matchedRomanizedLyric = "old romanized",
        matchedLyricSource = "CLOUD_MUSIC", matchedSongId = "42", originalLyric = "old baseline")
    private val key = "1|netease|"

    @Test
    fun `baseline only legacy lyrics remain durable candidates after restart`() {
        val preferences = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val baseline = legacy.copy(matchedLyric = null, matchedTranslatedLyric = null, matchedRomanizedLyric = null)
        SecureTokenStorage(preferences.preferences, directory).retainLegacyLyricCandidates(listOf(baseline))
        val restarted = SecureTokenStorage(preferences.restart().preferences, directory)
        assertEquals(listOf(baseline), restarted.getLegacyLyricCandidates())
        assertEquals(0L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `auxiliary and baseline only legacy lyrics survive restart without retaining confirmed states`() {
        val preferences = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val storage = SecureTokenStorage(preferences.preferences, directory)
        val translated = SyncSong(id = 2, album = "netease", matchedTranslatedLyric = "")
        val romanized = SyncSong(id = 3, album = "netease", matchedRomanizedLyric = "romanized")
        val baseline = SyncSong(id = 4, album = "netease", originalLyric = "baseline only")
        storage.retainLegacyLyricCandidates(listOf(translated, romanized,
            baseline,
            legacy.copy(lyricSyncEdited = false), legacy.copy(lyricSyncEdited = true, lyricSyncRevision = 9)))
        val restarted = SecureTokenStorage(preferences.restart().preferences, directory)
        assertEquals(listOf(translated, romanized, baseline), restarted.getLegacyLyricCandidates())
        assertTrue(restarted.getLyricOverrides().isEmpty())
        assertEquals(0L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `remote unknown lyrics survive normalization restart and removal of their last history copy`() {
        val preferences = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val storage = SecureTokenStorage(preferences.preferences, directory)
        val original = SyncData(recentPlays = listOf(SyncRecentPlay(song = legacy, playedAt = 10)))
        val decoded = decoder(storage).decode(SyncDataSerializer.serialize(original, false)) { IOException("empty") }.getOrThrow()
        assertEquals(legacy.matchedLyric, decoded.recentPlays.single().song.matchedLyric)
        assertEquals(legacy.originalLyric, decoded.lyricOverrides.single().originalLyric)
        val deletion = SyncData(recentPlayDeletions = listOf(SyncRecentPlayDeletion(songId = 1, album = "netease", deletedAt = 20)))
        val merged = SyncDataMerger(TestSyncMergeHost()).merge(deletion, decoded, 0).mergedData
        assertTrue(merged.recentPlays.isEmpty())
        assertEquals(SyncSongLyricMergePolicy.prepareLegacy(legacy), merged.lyricOverrides.single())
        val restarted = SecureTokenStorage(preferences.restart().preferences, directory)
        assertEquals(SyncSongLyricMergePolicy.prepareLegacy(legacy), restarted.getLyricOverridesForIdentityKeys(setOf(key)).single())
        assertTrue(restarted.getLyricOverrides().isEmpty())
        assertEquals(0L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `every distinct old candidate remains local and repeated imports keep the generation`() {
        val preferences = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val storage = SecureTokenStorage(preferences.preferences, directory)
        val candidates = SyncData(playlists = listOf(SyncPlaylist(songs = listOf(legacy, legacy.copy(matchedLyric = "other old lyrics")))))
        storage.retainLegacyLyrics(candidates)
        val marker = preferences.values[KEY_LEGACY_LYRIC_RECOVERY]
        storage.retainLegacyLyrics(candidates)
        assertEquals(marker, preferences.values[KEY_LEGACY_LYRIC_RECOVERY])
        val file = File(directory, (marker as String).split(':')[1])
        assertTrue(file.readText().contains("unknown old lyrics"))
        assertTrue(file.readText().contains("other old lyrics"))
        assertEquals(listOf(legacy, legacy.copy(matchedLyric = "other old lyrics")), storage.getLegacyLyricCandidatesForIdentityKey(key))
        storage.recordLyricOverride(legacy.copy(matchedLyric = "confirmed edit", lyricSyncEdited = true, lyricSyncRevision = 20))
        val reset = legacy.copy(matchedLyric = null, matchedTranslatedLyric = null, matchedRomanizedLyric = null,
            lyricSyncEdited = false, lyricSyncRevision = 21)
        storage.recordLyricOverride(reset)
        assertEquals(false, storage.getLyricOverridesForIdentityKeys(setOf(key)).single().lyricSyncEdited)
        assertTrue(file.exists())
        assertTrue(file.readText().contains("unknown old lyrics"))
        assertEquals(2, SecureTokenStorage(preferences.restart().preferences, directory).getLegacyLyricCandidatesForIdentityKey(key).size)
        assertEquals(2, storage.getLegacyLyricCandidates().size)
        assertTrue(storage.getLegacyLyricCandidatesForIdentityKey("other|netease|").isEmpty())
    }

    @Test
    fun `failed durable confirmation or recovery file sync stops decoding before sanitization`() {
        val preferences = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val storage = SecureTokenStorage(preferences.preferences, directory)
        val bytes = SyncDataSerializer.serialize(SyncData(lyricOverrides = listOf(legacy)), false)
        preferences.failNextCommit = true
        assertTrue(decoder(storage).decode(bytes) { IOException("empty") }.isFailure)
        assertTrue(SecureTokenStorage(preferences.restart().preferences, directory).getLyricOverridesForIdentityKeys(setOf(key)).isEmpty())
        val failingFiles = SecureTokenStorage(preferences.preferences, directory) { throw IOException("directory sync failed") }
        assertTrue(decoder(failingFiles).decode(bytes) { IOException("empty") }.isFailure)
        assertTrue(SecureTokenStorage(preferences.restart().preferences, directory).getLyricOverridesForIdentityKeys(setOf(key)).isEmpty())
        decoder(storage).decode(bytes) { IOException("empty") }.getOrThrow()
        assertEquals(SyncSongLyricMergePolicy.prepareLegacy(legacy), SecureTokenStorage(preferences.restart().preferences, directory).getLyricOverridesForIdentityKeys(setOf(key)).single())
    }

    @Test
    fun `unknown old override registry is retained before a confirmed edit replaces it`() {
        val preferences = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val files = SyncDeletionStateStorage(preferences.preferences, directory)
        assertTrue(files.commitEdit { files.write(this, KEY_LYRIC_OVERRIDES, listOf(legacy)) })
        val storage = SecureTokenStorage(preferences.preferences, directory)
        assertEquals(SyncSongLyricMergePolicy.prepareLegacy(legacy), storage.getLyricOverrides().single())
        storage.recordLyricOverride(legacy.copy(matchedLyric = "confirmed", lyricSyncEdited = true, lyricSyncRevision = 20))
        val marker = preferences.values[KEY_LEGACY_LYRIC_RECOVERY] as String
        val recovery = File(directory, marker.split(':')[1])
        assertTrue(recovery.exists())
        assertTrue(recovery.readText().contains("unknown old lyrics"))
        recovery.appendText(" ")
        assertThrows(IllegalStateException::class.java) { storage.getLyricOverridesForIdentityKeys(emptySet()) }
        assertThrows(IllegalStateException::class.java) { storage.getLegacyLyricCandidatesForIdentityKey("other|netease|") }
    }

    private fun decoder(storage: SecureTokenStorage) = SyncRemoteSnapshotDecoder(
        SyncSongLyricMergePolicy::converge, { it }, { it }, storage::retainLegacyLyrics
    )
}
