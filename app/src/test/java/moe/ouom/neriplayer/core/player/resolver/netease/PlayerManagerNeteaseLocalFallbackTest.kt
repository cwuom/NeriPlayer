package moe.ouom.neriplayer.core.player.resolver.netease

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerNeteaseLocalFallbackTest {

    @Test
    fun select_prefersExactMatchedSongIdOverFuzzyMetadata() {
        val song = neteaseSong(id = 123L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val exact = localSong(
            path = "/music/exact.flac",
            name = "晴天(备份)",
            artist = "周杰伦",
            durationMs = 270_000L,
            matchedSongId = "123",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )
        val fuzzy = localSong(
            path = "/music/fuzzy.flac",
            name = "晴天",
            artist = "周杰伦",
            durationMs = 269_000L,
            matchedSongId = "456",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )

        val candidates = selectNeteaseLocalFallbackCandidates(song, listOf(fuzzy, exact))

        assertEquals(listOf(exact, fuzzy), candidates)
    }

    @Test
    fun select_rejectsExactMatchedSongIdWhenDurationIsTooFarOff() {
        val song = neteaseSong(id = 123L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val wrongExact = localSong(
            path = "/music/wrong-exact.flac",
            name = "晴天",
            artist = "周杰伦",
            durationMs = 300_000L,
            matchedSongId = "123",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )
        val fuzzy = localSong(
            path = "/music/fuzzy-duration.flac",
            name = "晴天",
            artist = "周杰伦",
            durationMs = 269_000L,
            matchedSongId = "456",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )

        assertEquals(
            listOf(fuzzy),
            selectNeteaseLocalFallbackCandidates(song, listOf(wrongExact, fuzzy))
        )
    }

    @Test
    fun select_acceptsMetadataOnlyLocalSongAsRestrictedFallback() {
        val song = neteaseSong(id = 123L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val unmatched = localSong(
            path = "/music/unmatched.flac",
            name = "晴天",
            artist = "周杰伦",
            durationMs = 269_000L,
            matchedSongId = null,
            matchedSource = null
        )

        val candidates = selectNeteaseLocalFallbackCandidates(song, listOf(unmatched))

        assertEquals(listOf(unmatched), candidates)
    }

    @Test
    fun select_ignoresNonLocalSongsEvenWhenMatched() {
        val song = neteaseSong(id = 123L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val remote = neteaseSong(
            id = 999L,
            name = "晴天",
            artist = "周杰伦",
            durationMs = 269_000L
        ).copy(matchedSongId = "123", matchedLyricSource = MusicPlatform.CLOUD_MUSIC)

        val candidates = selectNeteaseLocalFallbackCandidates(song, listOf(remote))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun select_qqMatchedIdIsNotAnExactMatchButCanMatchByMetadata() {
        val song = neteaseSong(id = 123L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val qqMatchedDifferentMetadata = localSong(
            path = "/music/qq-other.flac",
            name = "别的歌",
            artist = "别人",
            durationMs = 269_000L,
            matchedSongId = "123",
            matchedSource = MusicPlatform.QQ_MUSIC
        )
        val qqMatchedSameMetadata = localSong(
            path = "/music/qq-same.flac",
            name = "晴天",
            artist = "周杰伦",
            durationMs = 268_000L,
            matchedSongId = "888",
            matchedSource = MusicPlatform.QQ_MUSIC
        )

        val candidates = selectNeteaseLocalFallbackCandidates(
            song,
            listOf(qqMatchedDifferentMetadata, qqMatchedSameMetadata)
        )

        assertEquals(listOf(qqMatchedSameMetadata), candidates)
    }

    @Test
    fun select_sortsExactMatchesByDurationCloseness() {
        val song = neteaseSong(id = 123L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val farDuration = localSong(
            path = "/music/far.mp3",
            name = "晴天",
            artist = "周杰伦",
            durationMs = 275_000L,
            matchedSongId = "123",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )
        val closeDuration = localSong(
            path = "/music/close.flac",
            name = "晴天",
            artist = "周杰伦",
            durationMs = 270_000L,
            matchedSongId = "123",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )

        val candidates = selectNeteaseLocalFallbackCandidates(song, listOf(farDuration, closeDuration))

        assertEquals(listOf(closeDuration, farDuration), candidates)
    }

    @Test
    fun select_deduplicatesSameLocalFileAcrossPlaylists() {
        val song = neteaseSong(id = 123L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val entry = localSong(
            path = "/music/exact.flac",
            name = "晴天",
            artist = "周杰伦",
            durationMs = 269_000L,
            matchedSongId = "123",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )

        val candidates = selectNeteaseLocalFallbackCandidates(song, listOf(entry, entry.copy()))

        assertEquals(1, candidates.size)
    }

    @Test
    fun metadataMatch_usesCustomOverrideFromManualMatch() {
        val song = neteaseSong(id = 123L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val candidate = localSong(
            path = "/music/tagged.flac",
            name = "track01",
            artist = "unknown",
            durationMs = 268_000L,
            matchedSongId = "888",
            matchedSource = MusicPlatform.CLOUD_MUSIC,
            customName = "晴天",
            customArtist = "周杰伦"
        )

        assertTrue(matchesNeteaseLocalFallbackMetadata(song, candidate))
    }

    @Test
    fun metadataMatch_normalizesCaseWhitespaceAndPunctuation() {
        val song = neteaseSong(
            id = 123L,
            name = "Love Story (Live)",
            artist = "Taylor Swift",
            durationMs = 235_000L
        )
        val candidate = localSong(
            path = "/music/live.flac",
            name = "love story live",
            artist = "taylor-swift",
            durationMs = 235_000L,
            matchedSongId = "888",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )

        assertTrue(matchesNeteaseLocalFallbackMetadata(song, candidate))
    }

    @Test
    fun metadataMatch_prefersOriginalMetadataOfRenamedNeteaseSong() {
        val song = neteaseSong(
            id = 123L,
            name = "我改过的名字",
            artist = "我改过的歌手",
            durationMs = 269_000L
        ).copy(originalName = "晴天", originalArtist = "周杰伦")
        val candidate = localSong(
            path = "/music/original.flac",
            name = "晴天",
            artist = "周杰伦",
            durationMs = 269_000L,
            matchedSongId = "888",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )

        assertTrue(matchesNeteaseLocalFallbackMetadata(song, candidate))
    }

    @Test
    fun metadataMatch_rejectsDurationOutsideTolerance() {
        val song = neteaseSong(id = 123L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val withinTolerance = localSong(
            path = "/music/within.flac",
            name = "晴天",
            artist = "周杰伦",
            durationMs = 269_000L + NETEASE_LOCAL_FALLBACK_DURATION_TOLERANCE_MS,
            matchedSongId = "888",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )
        val outsideTolerance = withinTolerance.copy(
            durationMs = 269_000L + NETEASE_LOCAL_FALLBACK_DURATION_TOLERANCE_MS + 1_000L
        )

        assertTrue(matchesNeteaseLocalFallbackMetadata(song, withinTolerance))
        assertFalse(matchesNeteaseLocalFallbackMetadata(song, outsideTolerance))
    }

    @Test
    fun metadataMatch_rejectsUnknownDurations() {
        val song = neteaseSong(id = 123L, name = "晴天", artist = "周杰伦", durationMs = 0L)
        val candidate = localSong(
            path = "/music/nodur.flac",
            name = "晴天",
            artist = "周杰伦",
            durationMs = 269_000L,
            matchedSongId = "888",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )

        assertFalse(matchesNeteaseLocalFallbackMetadata(song, candidate))
        assertFalse(
            matchesNeteaseLocalFallbackMetadata(
                song.copy(durationMs = 269_000L),
                candidate.copy(durationMs = 0L)
            )
        )
    }

    @Test
    fun metadataMatch_rejectsDifferentArtist() {
        val song = neteaseSong(id = 123L, name = "晴天", artist = "周杰伦", durationMs = 269_000L)
        val candidate = localSong(
            path = "/music/cover-version.flac",
            name = "晴天",
            artist = "翻唱歌手",
            durationMs = 269_000L,
            matchedSongId = "888",
            matchedSource = MusicPlatform.CLOUD_MUSIC
        )

        assertFalse(matchesNeteaseLocalFallbackMetadata(song, candidate))
    }

    private fun neteaseSong(
        id: Long,
        name: String,
        artist: String,
        durationMs: Long
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = "叶惠美",
            albumId = 18896L,
            durationMs = durationMs,
            coverUrl = "https://p1.music.126.net/cover.jpg"
        )
    }

    private fun localSong(
        path: String,
        name: String,
        artist: String,
        durationMs: Long,
        matchedSongId: String?,
        matchedSource: MusicPlatform?,
        customName: String? = null,
        customArtist: String? = null
    ): SongItem {
        return SongItem(
            id = path.hashCode().toLong(),
            name = name,
            artist = artist,
            album = "__local_files__",
            albumId = 0L,
            durationMs = durationMs,
            coverUrl = null,
            matchedLyricSource = matchedSource,
            matchedSongId = matchedSongId,
            customName = customName,
            customArtist = customArtist,
            localFileName = path.substringAfterLast('/'),
            localFilePath = path,
            channelId = "local"
        )
    }
}
