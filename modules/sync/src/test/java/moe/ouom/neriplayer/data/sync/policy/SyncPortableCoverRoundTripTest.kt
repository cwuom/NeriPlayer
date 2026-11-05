package moe.ouom.neriplayer.data.sync.policy

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncPortableCoverRoundTripTest {
    @get:Rule val temporary = TemporaryFolder()

    private val profile = "00000000-0000-0000-0000-000000000001"
    private val legacy = "https://neri-server.invalid/v1/$profile/cover/Y292ZXI"
    private val marker = "neri-cover=v1:" + "a".repeat(64)
    private val portable = "https://music.example/music/rest/getCoverArt.view?id=cover&size=600#$marker"
    private val customLegacy = "$legacy-custom"
    private val originalLegacy = "$legacy-original"
    private val customPortable = "https://music.example/music/rest/getCoverArt.view?id=custom&size=600#$marker"
    private val originalPortable = "https://music.example/music/rest/getCoverArt.view?id=original&size=600#$marker"

    @Test
    fun `every sync cover field projects without changing song source identity`() {
        bindProjection()
        try {
            val input = sampleData()
            assertPortable(input.sanitizeLocalCoverUrls(), input)
            val local = input.copy(
                favoritePlaylists = input.favoritePlaylists.map { it.copy(coverUrl = "file:/private/cover") },
                playbackStats = input.playbackStats.map { it.copy(coverUrl = "content://media/cover") }
            ).sanitizeLocalCoverUrls()
            assertNull(local.favoritePlaylists.single().coverUrl)
            assertNull(local.playbackStats.single().coverUrl)
        } finally {
            SyncCoverUrlProjection.bind()
        }
    }

    @Test
    fun `json and gzip protobuf round trips retain portable covers without credentials`() {
        bindProjection()
        try {
            val input = sampleData()
            for (binary in listOf(false, true)) {
                val payload = SyncDataSerializer.serialize(input, binary)
                assertPortable(SyncDataSerializer.deserialize(payload), input)
                if (!binary) {
                    val text = payload.toString(Charsets.UTF_8)
                    assertFalse(text.contains("neri-server.invalid"))
                    assertFalse(text.contains("password="))
                    assertFalse(text.contains("&t="))
                    assertFalse(text.contains("&s="))
                }
            }
        } finally {
            SyncCoverUrlProjection.bind()
        }
    }

    @Test
    fun `v4 archive restores portable covers in songs playlists history and statistics`() = runBlocking {
        bindProjection()
        try {
            val input = sampleData()
            val writer = SyncArchiveRepository(temporary.newFolder())
            writer.prepare(input).use { archive ->
                val objects = archive.objects.associate { it.path to it.content }
                val reader = SyncArchiveRepository(temporary.newFolder())
                val restored = reader.read(archive.content) { Result.success(objects.getValue(it)) }.getOrThrow()
                assertPortable(restored, input)
            }
        } finally {
            SyncCoverUrlProjection.bind()
        }
    }

    private fun bindProjection() {
        val mapping = mapOf(legacy to portable, customLegacy to customPortable, originalLegacy to originalPortable)
        SyncCoverUrlProjection.bind(project = { mapping[it] ?: it })
    }

    private fun sampleData(): SyncData {
        val song = SyncSong(
            id = -77, name = "Server song", artist = "Artist", album = "Album", albumId = -88,
            coverUrl = legacy, customCoverUrl = customLegacy, originalCoverUrl = originalLegacy,
            mediaUri = "neri-server://$profile/song/c29uZy1pZA", channelId = "subsonic",
            audioId = "v1:$profile:c29uZy1pZA", lyricSyncEdited = false,
            syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION
        )
        return SyncData(
            lastModified = 1,
            playlists = listOf(SyncPlaylist(id = 1, songs = listOf(song))),
            favoritePlaylists = listOf(SyncFavoritePlaylist(id = 2, coverUrl = legacy, songs = listOf(song))),
            recentPlays = listOf(SyncRecentPlay(songId = song.id, song = song, playedAt = 10)),
            playbackStats = listOf(SyncTrackStat(identityKey = "server-track", id = song.id, coverUrl = legacy, mediaUri = song.mediaUri)),
            playbackStatBuckets = listOf(SyncPlaybackStatBucket(identityKey = "server-track", dayStartAt = 1, id = song.id, coverUrl = legacy, mediaUri = song.mediaUri)),
            playlistUsageStats = listOf(SyncPlaylistUsageStat(playlistKey = "playlist:1", coverUrl = legacy))
        )
    }

    private fun assertPortable(actual: SyncData, input: SyncData) {
        val expectedSong = input.playlists.single().songs.single().copy(
            coverUrl = portable, customCoverUrl = customPortable, originalCoverUrl = originalPortable
        )
        assertEquals(expectedSong, actual.playlists.single().songs.single())
        assertEquals(expectedSong, actual.favoritePlaylists.single().songs.single())
        assertEquals(expectedSong, actual.recentPlays.single().song)
        assertEquals(portable, actual.favoritePlaylists.single().coverUrl)
        assertEquals(input.playbackStats.single().copy(coverUrl = portable), actual.playbackStats.single())
        assertEquals(input.playbackStatBuckets.single().copy(coverUrl = portable), actual.playbackStatBuckets.single())
        assertEquals(input.playlistUsageStats.single().copy(coverUrl = portable), actual.playlistUsageStats.single())
    }
}
