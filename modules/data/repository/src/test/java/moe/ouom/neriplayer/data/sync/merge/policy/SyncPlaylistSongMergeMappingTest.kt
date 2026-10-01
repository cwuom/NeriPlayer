package moe.ouom.neriplayer.data.sync.merge.policy

import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import moe.ouom.neriplayer.data.sync.merge.song.SyncPlaylistSongMergePolicy
import moe.ouom.neriplayer.data.sync.mapping.toSongItem
import org.junit.Test

class SyncPlaylistSongMergeMappingTest {
    @Test
    fun `snapshot deduplication does not restore stale custom display metadata`() {
        val current = syncSong(
            id = 1L,
            name = "New title",
            artist = "New artist",
            membershipTokens = listOf(token("current", 1L))
        ).copy(coverUrl = "https://example.com/new.jpg")
        val stale = current.copy(
            customName = "Old title",
            customArtist = "Old artist",
            customCoverUrl = "https://example.com/old.jpg",
            originalName = "Old title",
            originalArtist = "Old artist",
            originalCoverUrl = "https://example.com/old.jpg",
            syncMembershipTokens = listOf(token("stale", 1L))
        )

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(current, stale))

        val merged = result.single()
        assertNull(merged.customName)
        assertNull(merged.customArtist)
        assertNull(merged.customCoverUrl)
        assertEquals("New title", merged.toSongItem().displayName())
        assertEquals("New artist", merged.toSongItem().displayArtist())
        assertEquals(
            listOf(token("current", 1L), token("stale", 1L)),
            merged.syncMembershipTokens
        )
    }

    @Test
    fun `newer remote batch metadata wins atomically over older local payloads`() {
        val local = syncSong(
            id = 1L,
            name = "Local old title",
            artist = "Local old artist",
            membershipTokens = listOf(token("local", 1L))
        ).copy(
            coverUrl = "https://example.com/local-old.jpg",
            userLyricOffsetMs = 500L,
            customName = "Stale local title",
            customArtist = "Stale local artist",
            customCoverUrl = "https://example.com/stale-local.jpg"
        )
        val remote = syncSong(
            id = 1L,
            name = "Remote title",
            artist = "Remote artist",
            membershipTokens = listOf(token("remote", 1L))
        ).copy(
            coverUrl = "https://example.com/remote.jpg",
            userLyricOffsetMs = 0L
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
            localModifiedAt = 200L,
            remoteModifiedAt = 300L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 100L,
            isFavorites = false
        )

        val merged = result.songs.single()
        assertEquals("Remote title", merged.name)
        assertEquals("Remote artist", merged.artist)
        assertEquals("https://example.com/remote.jpg", merged.coverUrl)
        assertNull(merged.customName)
        assertNull(merged.customArtist)
        assertNull(merged.customCoverUrl)
        assertEquals(0L, merged.userLyricOffsetMs)

        val displayedSong = merged.toSongItem()
        assertEquals("Remote title", displayedSong.displayName())
        assertEquals("Remote artist", displayedSong.displayArtist())
        assertEquals("https://example.com/remote.jpg", displayedSong.displayCoverUrl())
    }

    @Test
    fun `remote primary replaces the complete local metadata payload`() {
        val local = syncSong(
            id = 1L,
            name = "Old title",
            artist = "Old artist",
            membershipTokens = listOf(token("local", 1L))
        ).copy(
            coverUrl = "https://example.com/old.jpg",
            customName = "Old title",
            customArtist = "Old artist",
            customCoverUrl = "https://example.com/old.jpg",
            originalName = "Old title",
            originalArtist = "Old artist",
            originalCoverUrl = "https://example.com/old.jpg",
            matchedLyric = "[00:00.00]lyric",
            matchedTranslatedLyric = "[00:00.00]translation",
            matchedLyricSource = "CLOUD_MUSIC",
            matchedSongId = "netease:1",
            originalLyric = "[00:00.00]old lyric",
            originalTranslatedLyric = "[00:00.00]old translation"
        )
        val remote = syncSong(
            id = 1L,
            name = "New title",
            artist = "New artist",
            membershipTokens = listOf(token("remote", 1L))
        ).copy(
            coverUrl = "https://example.com/new.jpg",
            originalName = "Old title",
            originalArtist = "Old artist",
            originalCoverUrl = "https://example.com/old.jpg"
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
            localModifiedAt = 100L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 150L,
            isFavorites = false
        )

        val merged = result.songs.single()
        assertEquals("New title", merged.name)
        assertEquals("New artist", merged.artist)
        assertEquals("https://example.com/new.jpg", merged.coverUrl)
        assertNull(merged.customName)
        assertNull(merged.customArtist)
        assertNull(merged.customCoverUrl)
        assertEquals("Old title", merged.originalName)
        assertEquals("Old artist", merged.originalArtist)
        assertEquals("https://example.com/old.jpg", merged.originalCoverUrl)
        assertNull(merged.matchedLyric)
        assertNull(merged.matchedTranslatedLyric)
        assertNull(merged.matchedLyricSource)
        assertNull(merged.matchedSongId)
        assertNull(merged.originalLyric)
        assertNull(merged.originalTranslatedLyric)
        assertEquals(listOf(token("local", 1L), token("remote", 1L)), merged.syncMembershipTokens)

        val displayedSong = merged.toSongItem()
        assertEquals("New title", displayedSong.displayName())
        assertEquals("New artist", displayedSong.displayArtist())
        assertEquals("https://example.com/new.jpg", displayedSong.displayCoverUrl())
    }

    private fun syncSong(
        id: Long,
        name: String,
        album: String = "Album",
        artist: String = "Artist",
        channelId: String? = null,
        audioId: String? = null,
        mediaUri: String? = null,
        addedAt: Long = 0L,
        membershipTokens: List<SyncCausalToken> = emptyList(),
        syncMetadataVersion: Int = CURRENT_SYNC_METADATA_VERSION
    ): SyncSong {
        return SyncSong(
            id = id,
            name = name,
            artist = artist,
            album = album,
            channelId = channelId,
            audioId = audioId,
            mediaUri = mediaUri,
            addedAt = addedAt,
            syncMembershipTokens = membershipTokens,
            syncMetadataVersion = syncMetadataVersion
        )
    }

    private fun token(deviceId: String, counter: Long): SyncCausalToken {
        return SyncCausalToken(deviceId = deviceId, counter = counter)
    }
}
