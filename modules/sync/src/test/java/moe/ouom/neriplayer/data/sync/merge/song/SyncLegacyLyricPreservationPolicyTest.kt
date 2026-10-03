package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLegacyLyricPreservationPolicyTest {
    @Test
    fun `unknown legacy lyrics keep all original and matched variants in one durable override`() {
        val legacy = SyncSong(id = 1, album = "netease", matchedLyric = "old edit",
            matchedTranslatedLyric = "translated edit", matchedRomanizedLyric = "romanized edit",
            originalLyric = "original", originalTranslatedLyric = "translation", originalRomanizedLyric = "romanized")
        val data = SyncData(playlists = listOf(SyncPlaylist(songs = listOf(legacy))),
            favoritePlaylists = listOf(SyncFavoritePlaylist(songs = listOf(legacy))),
            recentPlays = listOf(SyncRecentPlay(song = legacy)))

        val result = SyncSongLyricMergePolicy.converge(data)

        val expected = legacy.copy(lyricSyncEdited = true, lyricSyncRevision = 1)
        assertEquals(expected, result.playlists.single().songs.single())
        assertEquals(expected, result.favoritePlaylists.single().songs.single())
        assertEquals(expected, result.recentPlays.single().song)
        assertEquals(expected, result.lyricOverrides.single())
        assertSame(result, SyncSongLyricMergePolicy.converge(result))
    }

    @Test
    fun `baseline only legacy lyrics become playable without dropping restoration originals`() {
        val legacy = SyncSong(id = 1, originalLyric = "original", originalTranslatedLyric = "translation",
            originalRomanizedLyric = "romanized")
        val result = SyncSongLyricMergePolicy.normalize(legacy)

        assertEquals("original", result.matchedLyric)
        assertEquals("translation", result.matchedTranslatedLyric)
        assertEquals("romanized", result.matchedRomanizedLyric)
        assertEquals("original", result.originalLyric)
        assertEquals("translation", result.originalTranslatedLyric)
        assertEquals("romanized", result.originalRomanizedLyric)
        assertEquals(true, result.lyricSyncEdited)
        assertEquals(1L, result.lyricSyncRevision)
        assertSame(result, SyncSongLyricMergePolicy.normalize(result))
    }

    @Test
    fun `an intentionally empty legacy matched variant does not fall back to a nonempty original`() {
        val legacy = SyncSong(id = 1, matchedLyric = "", matchedTranslatedLyric = "", matchedRomanizedLyric = "",
            originalLyric = "base", originalTranslatedLyric = "translation", originalRomanizedLyric = "romanized")
        val result = SyncSongLyricMergePolicy.normalize(legacy)

        assertEquals("", result.matchedLyric)
        assertEquals("", result.matchedTranslatedLyric)
        assertEquals("", result.matchedRomanizedLyric)
        assertEquals("base", result.originalLyric)
        assertEquals(true, result.lyricSyncEdited)
    }

    @Test
    fun `every nonnull legacy lyric field including an empty string is preserved`() {
        val variants = listOf(
            SyncSong(matchedLyric = ""), SyncSong(matchedTranslatedLyric = ""), SyncSong(matchedRomanizedLyric = ""),
            SyncSong(originalLyric = ""), SyncSong(originalTranslatedLyric = ""), SyncSong(originalRomanizedLyric = "")
        )
        variants.forEach { legacy ->
            val result = SyncSongLyricMergePolicy.normalize(legacy)
            assertEquals(true, result.lyricSyncEdited)
            assertEquals(1L, result.lyricSyncRevision)
        }
    }

    @Test
    fun `bilibili zero version lyrics are always retained even when marked as a cache`() {
        val identities = listOf(SyncSong(album = "bilibili"), SyncSong(album = "bilibili:123"),
            SyncSong(channelId = " BILIBILI ", album = "custom title"))
        identities.forEach { identity ->
            val cached = identity.copy(matchedTranslatedLyric = "bili translation", originalRomanizedLyric = "bili baseline",
                lyricSyncEdited = false)
            val result = SyncSongLyricMergePolicy.normalize(cached)
            assertEquals("bili translation", result.matchedTranslatedLyric)
            assertEquals("bili baseline", result.matchedRomanizedLyric)
            assertEquals("bili baseline", result.originalRomanizedLyric)
            assertEquals(true, result.lyricSyncEdited)
            assertEquals(1L, result.lyricSyncRevision)
        }
    }

    @Test
    fun `explicit non bilibili channels override old album hints while incomplete bilibili identities retain lyrics`() {
        val misleadingAlbum = SyncSong(id = 0, album = "Bilibili|123", channelId = " netease ",
            lyricSyncEdited = false, matchedLyric = "network cache")
        val cache = SyncSongLyricMergePolicy.normalize(misleadingAlbum)
        assertEquals(false, cache.lyricSyncEdited)
        assertNull(cache.matchedLyric)

        val incomplete = misleadingAlbum.copy(album = "custom title", channelId = " BILIBILI ", audioId = " ")
        val preserved = SyncSongLyricMergePolicy.normalize(incomplete)
        assertEquals("network cache", preserved.matchedLyric)
        assertEquals(true, preserved.lyricSyncEdited)
        assertEquals(1L, preserved.lyricSyncRevision)
        val legacyAlbumOnly = incomplete.copy(channelId = null, album = "Bilibili|456")
        assertEquals("network cache", SyncSongLyricMergePolicy.normalize(legacyAlbumOnly).matchedLyric)
    }

    @Test
    fun `a bilibili reset with stale full text remains a reset and wins over the compatibility version`() {
        val legacy = SyncSong(id = 1, album = "bilibili", matchedLyric = "old manual edit", originalLyric = "base")
        val reset = legacy.copy(lyricSyncEdited = false, lyricSyncRevision = 1)

        val result = SyncSongLyricMergePolicy.merge(legacy, listOf(legacy, reset))

        assertEquals(false, result.lyricSyncEdited)
        assertEquals(1L, result.lyricSyncRevision)
        assertNull(result.matchedLyric)
        assertNull(result.originalLyric)
        assertEquals(result, SyncSongLyricMergePolicy.merge(result, listOf(legacy, result)))
    }

    @Test
    fun `known ordinary caches stay omitted while empty unknown songs create no compatibility record`() {
        val cache = SyncSong(album = "netease", lyricSyncEdited = false, matchedLyric = "network cache", originalLyric = "base")
        val empty = SyncSong()
        val result = SyncSongLyricMergePolicy.normalize(cache)

        assertNull(result.matchedLyric)
        assertNull(result.originalLyric)
        assertFalse(result.lyricSyncEdited == true)
        assertEquals(0L, result.lyricSyncRevision)
        assertSame(empty, SyncSongLyricMergePolicy.normalize(empty))
    }

    @Test
    fun `equal edited revisions with different original variants converge in either merge order`() {
        val first = SyncSong(id = 1, lyricSyncEdited = true, lyricSyncRevision = 20, matchedLyric = "edited",
            originalLyric = "original a", originalTranslatedLyric = "translation a", originalRomanizedLyric = "romanized a")
        val second = first.copy(originalLyric = "original b", originalTranslatedLyric = "translation b", originalRomanizedLyric = "romanized b")
        val left = SyncSongLyricMergePolicy.merge(first, listOf(first, second))
        val right = SyncSongLyricMergePolicy.merge(second, listOf(second, first))

        assertEquals(second, left)
        assertEquals(left, right)
    }

    @Test
    fun `unknown numeric versions preserve full text only at the compatibility floor and cannot defeat resets`() {
        listOf(-100L, 0L, 999L, Long.MAX_VALUE).forEach { revision ->
            val legacy = SyncSong(id = 1, matchedLyric = "old text", originalLyric = "base", lyricSyncRevision = revision)
            val normalized = SyncSongLyricMergePolicy.normalize(legacy)
            assertEquals("old text", normalized.matchedLyric)
            assertEquals("base", normalized.originalLyric)
            assertEquals(1L, normalized.lyricSyncRevision)
            val reset = SyncSong(id = 1, lyricSyncEdited = false, lyricSyncRevision = 2)
            assertEquals(reset, SyncSongLyricMergePolicy.merge(legacy, listOf(legacy, reset)))
        }
    }

    @Test
    fun `empty unknown versions create no edits while negative bilibili caches retain their full text`() {
        val unknown = SyncSong(id = 1, lyricSyncRevision = 999)
        val normalized = SyncSongLyricMergePolicy.normalize(unknown)
        assertEquals(false, normalized.lyricSyncEdited)
        assertEquals(0L, normalized.lyricSyncRevision)
        assertSame(normalized, SyncSongLyricMergePolicy.normalize(normalized))
        val invalidCache = SyncSong(id = 2, album = "bilibili", lyricSyncEdited = false,
            lyricSyncRevision = -10, matchedLyric = "stale cache")
        val preserved = SyncSongLyricMergePolicy.normalize(invalidCache)
        assertEquals(true, preserved.lyricSyncEdited)
        assertEquals(1L, preserved.lyricSyncRevision)
        assertEquals("stale cache", preserved.matchedLyric)
        assertSame(preserved, SyncSongLyricMergePolicy.normalize(preserved))
        val ordinary = SyncSongLyricMergePolicy.normalize(invalidCache.copy(album = "netease"))
        assertEquals(false, ordinary.lyricSyncEdited)
        assertEquals(0L, ordinary.lyricSyncRevision)
        assertNull(ordinary.matchedLyric)
    }

    @Test
    fun `optimization removes only unknown non bilibili full text and keeps explicit edits and resets`() {
        val unknown = SyncSong(id = 1, channelId = "youtube_music", matchedLyric = "old text", originalLyric = "base",
            matchedTranslatedLyric = "translation", originalTranslatedLyric = "base translation",
            matchedRomanizedLyric = "romanized", originalRomanizedLyric = "base romanized", lyricSyncRevision = 999)
        val preserved = SyncSongLyricMergePolicy.prepareLegacy(unknown)
        assertEquals("old text", preserved.matchedLyric)
        assertEquals("base", preserved.originalLyric)
        val optimized = SyncSongLyricMergePolicy.prepareLegacy(unknown, optimize = true)
        assertEquals(false, optimized.lyricSyncEdited)
        assertEquals(0L, optimized.lyricSyncRevision)
        assertNull(optimized.matchedLyric)
        assertNull(optimized.matchedTranslatedLyric)
        assertNull(optimized.matchedRomanizedLyric)
        assertNull(optimized.originalLyric)
        assertNull(optimized.originalTranslatedLyric)
        assertNull(optimized.originalRomanizedLyric)
        assertSame(optimized, SyncSongLyricMergePolicy.prepareLegacy(optimized, optimize = true))

        val edit = unknown.copy(lyricSyncEdited = true, lyricSyncRevision = 1000)
        assertSame(edit, SyncSongLyricMergePolicy.prepareLegacy(edit, optimize = true))
        val reset = unknown.copy(lyricSyncEdited = false, lyricSyncRevision = 1001)
        val normalizedReset = SyncSongLyricMergePolicy.prepareLegacy(reset, optimize = true)
        assertEquals(false, normalizedReset.lyricSyncEdited)
        assertEquals(1001L, normalizedReset.lyricSyncRevision)
        assertNull(normalizedReset.matchedLyric)
        assertNull(normalizedReset.originalLyric)
        listOf<Boolean?>(null, false).forEach { marker ->
            val bili = unknown.copy(channelId = "bilibili", lyricSyncEdited = marker, lyricSyncRevision = 0)
            val normalized = SyncSongLyricMergePolicy.prepareLegacy(bili, optimize = true)
            assertEquals("old text", normalized.matchedLyric)
            assertEquals("base", normalized.originalLyric)
            assertEquals(true, normalized.lyricSyncEdited)
            assertEquals(1L, normalized.lyricSyncRevision)
        }
        assertEquals(1L, SyncSongLyricMergePolicy.prepareLegacy(
            unknown.copy(channelId = "bilibili"), optimize = true).lyricSyncRevision)
        val empty = SyncSong(id = 123)
        assertSame(empty, SyncSongLyricMergePolicy.prepareLegacy(empty, optimize = true))
    }

    @Test
    fun `legacy data preparation applies the selected policy to playlists favorites history and detached records`() {
        val unknown = SyncSong(id = 1, album = "netease", matchedLyric = "old edit", originalLyric = "base")
        val bili = unknown.copy(id = 2, album = "Bilibili|123", lyricSyncEdited = false)
        val known = unknown.copy(id = 3, lyricSyncEdited = true, lyricSyncRevision = 10)
        val input = SyncData(lastModified = 100, deviceId = "device", playbackStatsClearedAt = 200,
            playlists = listOf(SyncPlaylist(songs = listOf(unknown, bili))),
            favoritePlaylists = listOf(SyncFavoritePlaylist(songs = listOf(unknown))),
            recentPlays = listOf(SyncRecentPlay(song = unknown)), lyricOverrides = listOf(known))

        val preserved = SyncSongLyricMergePolicy.prepareLegacy(input)
        assertEquals(3, preserved.lyricOverrides.size)
        assertEquals("old edit", preserved.favoritePlaylists.single().songs.single().matchedLyric)
        assertEquals("base", preserved.recentPlays.single().song.originalLyric)
        assertSame(preserved, SyncSongLyricMergePolicy.prepareLegacy(preserved))

        val optimized = SyncSongLyricMergePolicy.prepareLegacy(input, optimize = true)
        assertEquals(setOf(2L, 3L), optimized.lyricOverrides.map { it.id }.toSet())
        assertNull(optimized.playlists.single().songs.first().matchedLyric)
        assertNull(optimized.favoritePlaylists.single().songs.single().originalLyric)
        assertNull(optimized.recentPlays.single().song.matchedLyric)
        assertEquals("old edit", optimized.playlists.single().songs.last().matchedLyric)
        assertEquals("base", optimized.lyricOverrides.single { it.id == 3L }.originalLyric)
        assertEquals(100L, optimized.lastModified)
        assertEquals("device", optimized.deviceId)
        assertEquals(200L, optimized.playbackStatsClearedAt)
        assertSame(optimized, SyncSongLyricMergePolicy.prepareLegacy(optimized, optimize = true))
        val empty = SyncData(lastModified = 1)
        assertSame(empty, SyncSongLyricMergePolicy.prepareLegacy(empty, optimize = true))
    }

    @Test
    fun `optimization applies to detached overrides without requiring a playlist or playback copy`() {
        val unknown = SyncSong(id = 1, album = "netease", matchedLyric = "old unknown edit", originalLyric = "base")
        val input = SyncData(lastModified = 1, lyricOverrides = listOf(unknown))
        val preserved = SyncSongLyricMergePolicy.prepareLegacy(input)
        assertEquals("old unknown edit", preserved.lyricOverrides.single().matchedLyric)
        assertEquals("base", preserved.lyricOverrides.single().originalLyric)
        val optimized = SyncSongLyricMergePolicy.prepareLegacy(input, optimize = true)
        assertTrue(optimized.lyricOverrides.isEmpty())
        assertTrue(optimized.playlists.isEmpty())
        assertSame(optimized, SyncSongLyricMergePolicy.prepareLegacy(optimized, optimize = true))
    }

    @Test
    fun `optimization reaches favorite only and history only legacy songs while retaining existing empty caches`() {
        val unknown = SyncSong(id = 1, matchedLyric = "old text")
        val favorite = SyncData(lastModified = 1, favoritePlaylists = listOf(SyncFavoritePlaylist(songs = listOf(unknown))))
        val history = SyncData(lastModified = 1, recentPlays = listOf(SyncRecentPlay(song = unknown)))
        val optimizedFavorite = SyncSongLyricMergePolicy.prepareLegacy(favorite, optimize = true)
        val optimizedHistory = SyncSongLyricMergePolicy.prepareLegacy(history, optimize = true)
        assertNull(optimizedFavorite.favoritePlaylists.single().songs.single().matchedLyric)
        assertNull(optimizedHistory.recentPlays.single().song.matchedLyric)
        assertTrue(optimizedFavorite.lyricOverrides.isEmpty())
        assertTrue(optimizedHistory.lyricOverrides.isEmpty())

        val cache = unknown.copy(lyricSyncEdited = false, matchedLyric = null)
        val stable = SyncData(lastModified = 1, playlists = listOf(SyncPlaylist(songs = listOf(cache))),
            favoritePlaylists = listOf(SyncFavoritePlaylist(songs = listOf(cache))), recentPlays = listOf(SyncRecentPlay(song = cache)))
        assertSame(stable, SyncSongLyricMergePolicy.prepareLegacy(stable, optimize = true))
    }
}
