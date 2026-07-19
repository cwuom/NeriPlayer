package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.sync.model.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.sync.model.LEGACY_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaylistSongMergePolicyTest {
    @Test
    fun `remote phone reorder keeps remote addedAt values and order`() {
        val local = listOf(
            syncSong(id = 1L, name = "A", addedAt = 300L),
            syncSong(id = 2L, name = "B", addedAt = 200L),
            syncSong(id = 3L, name = "C", addedAt = 100L)
        )
        val remote = listOf(
            local[2].copy(addedAt = 900L),
            local[0].copy(addedAt = 899L),
            local[1].copy(addedAt = 898L)
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = local,
            remoteSongs = remote,
            localModifiedAt = 100L,
            remoteModifiedAt = 300L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 200L,
            isFavorites = false
        )

        assertEquals(listOf(3L, 1L, 2L), result.songs.map(SyncSong::id))
        assertEquals(listOf(900L, 899L, 898L), result.songs.map(SyncSong::addedAt))
    }

    @Test
    fun `local desktop reorder keeps local addedAt values and order`() {
        val remote = listOf(
            syncSong(id = 1L, name = "A", addedAt = 300L),
            syncSong(id = 2L, name = "B", addedAt = 200L),
            syncSong(id = 3L, name = "C", addedAt = 100L)
        )
        val local = listOf(
            remote[1].copy(addedAt = 900L),
            remote[2].copy(addedAt = 899L),
            remote[0].copy(addedAt = 898L)
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = local,
            remoteSongs = remote,
            localModifiedAt = 300L,
            remoteModifiedAt = 100L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = false,
            lastSyncTime = 200L,
            isFavorites = false
        )

        assertEquals(listOf(2L, 3L, 1L), result.songs.map(SyncSong::id))
        assertEquals(listOf(900L, 899L, 898L), result.songs.map(SyncSong::addedAt))
    }

    @Test
    fun `legacy primary order keeps current rich metadata`() {
        val current = syncSong(id = 1L, name = "Original", addedAt = 100L).copy(
            matchedLyric = "[00:01.00]lyric",
            customName = "Custom title",
            originalName = "Original"
        )
        val legacyPrimary = current.copy(
            name = "Custom title",
            addedAt = 900L,
            matchedLyric = null,
            customName = null,
            originalName = null,
            syncMetadataVersion = LEGACY_SYNC_METADATA_VERSION
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(current),
            remoteSongs = listOf(legacyPrimary),
            localModifiedAt = 100L,
            remoteModifiedAt = 300L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 200L,
            isFavorites = false
        )

        val merged = result.songs.single()
        assertEquals(900L, merged.addedAt)
        assertEquals("Original", merged.name)
        assertEquals("[00:01.00]lyric", merged.matchedLyric)
        assertEquals("Custom title", merged.customName)
        assertEquals(CURRENT_SYNC_METADATA_VERSION, merged.syncMetadataVersion)
    }

    @Test
    fun `current primary can intentionally clear metadata`() {
        val local = syncSong(id = 1L, name = "Song", addedAt = 100L).copy(
            matchedLyric = "[00:01.00]old lyric",
            customName = "Old custom title"
        )
        val remote = local.copy(
            addedAt = 200L,
            matchedLyric = null,
            customName = null
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
            localModifiedAt = 100L,
            remoteModifiedAt = 300L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 200L,
            isFavorites = false
        )

        assertNull(result.songs.single().matchedLyric)
        assertNull(result.songs.single().customName)
    }

    @Test
    fun `primary missing addedAt inherits known value instead of moving to top`() {
        val current = syncSong(id = 1L, name = "Song", addedAt = 500L)
        val legacyPrimary = current.copy(
            addedAt = 0L,
            syncMetadataVersion = LEGACY_SYNC_METADATA_VERSION
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(current),
            remoteSongs = listOf(legacyPrimary),
            localModifiedAt = 100L,
            remoteModifiedAt = 300L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 200L,
            isFavorites = false
        )

        assertEquals(500L, result.songs.single().addedAt)
    }

    @Test
    fun `local clear wins when local playlist changed after sync`() {
        val remoteSong = syncSong(id = 1L, name = "Song")

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = emptyList(),
            remoteSongs = listOf(remoteSong),
            localModifiedAt = 200L,
            remoteModifiedAt = 100L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = false,
            lastSyncTime = 150L,
            isFavorites = false
        )

        assertTrue(result.songs.isEmpty())
        assertEquals(false, result.isUpdated)
    }

    @Test
    fun `remote clear wins when remote playlist changed after sync`() {
        val localSong = syncSong(id = 1L, name = "Song")

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(localSong),
            remoteSongs = emptyList(),
            localModifiedAt = 100L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 150L,
            isFavorites = false
        )

        assertTrue(result.songs.isEmpty())
        assertEquals(true, result.isUpdated)
    }

    @Test
    fun `local clear defers tokenized remote membership to deletion policy`() {
        val remoteSong = syncSong(
            id = 1L,
            name = "Restored",
            membershipTokens = listOf(token("remote", 2L))
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = emptyList(),
            remoteSongs = listOf(remoteSong),
            localModifiedAt = 300L,
            remoteModifiedAt = 100L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = false,
            lastSyncTime = 200L,
            isFavorites = false
        )

        assertEquals(listOf(remoteSong), result.songs)
    }

    @Test
    fun `remote clear defers tokenized local membership to deletion policy`() {
        val localSong = syncSong(
            id = 1L,
            name = "Restored",
            membershipTokens = listOf(token("local", 2L))
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(localSong),
            remoteSongs = emptyList(),
            localModifiedAt = 100L,
            remoteModifiedAt = 300L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 200L,
            isFavorites = false
        )

        assertEquals(listOf(localSong), result.songs)
    }

    @Test
    fun `same channel audio song is not duplicated when album changes`() {
        val localSong = syncSong(
            id = 1L,
            name = "Song",
            album = "Old Album",
            channelId = "netease",
            audioId = "1"
        )
        val remoteSong = syncSong(
            id = 1L,
            name = "Song",
            album = "New Album",
            channelId = "netease",
            audioId = "1"
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(localSong),
            remoteSongs = listOf(remoteSong),
            localModifiedAt = 200L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 100L,
            isFavorites = false
        )

        assertEquals(listOf(localSong), result.songs)
    }

    @Test
    fun `channel audio duplicate unions membership tokens`() {
        val localToken = token("local", 1L)
        val remoteToken = token("remote", 1L)
        val localSong = syncSong(
            id = 1L,
            name = "Song",
            album = "Old Album",
            channelId = "netease",
            audioId = "1",
            membershipTokens = listOf(localToken)
        )
        val remoteSong = localSong.copy(
            album = "New Album",
            syncMembershipTokens = listOf(remoteToken)
        )

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(localSong, remoteSong))

        assertEquals(
            listOf(localSong.copy(syncMembershipTokens = listOf(localToken, remoteToken))),
            result
        )
    }

    @Test
    fun `non exact membership merge resolves payload deterministically`() {
        val localSong = syncSong(
            id = 1L,
            name = "Song",
            album = "Old Album",
            channelId = "netease",
            audioId = "1",
            membershipTokens = listOf(token("local", 1L))
        )
        val remoteSong = localSong.copy(
            album = "New Album",
            syncMembershipTokens = listOf(token("remote", 1L))
        )

        val localFirst = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(localSong),
            remoteSongs = listOf(remoteSong),
            localModifiedAt = 200L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 100L,
            isFavorites = false
        )
        val remoteFirst = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(remoteSong),
            remoteSongs = listOf(localSong),
            localModifiedAt = 200L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 100L,
            isFavorites = false
        )

        assertEquals(localFirst.songs, remoteFirst.songs)
    }

    @Test
    fun `remote primary unions token from non exact local duplicate`() {
        val localToken = token("local", 1L)
        val remoteToken = token("remote", 1L)
        val localSong = syncSong(
            id = 1L,
            name = "Local",
            album = "Old Album",
            channelId = "netease",
            audioId = "1",
            membershipTokens = listOf(localToken)
        )
        val remoteSong = localSong.copy(
            name = "Remote",
            album = "New Album",
            syncMembershipTokens = listOf(remoteToken)
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(localSong),
            remoteSongs = listOf(remoteSong),
            localModifiedAt = 100L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 150L,
            isFavorites = false
        )

        assertEquals(
            listOf(remoteSong.copy(syncMembershipTokens = listOf(localToken, remoteToken))),
            result.songs
        )
    }

    @Test
    fun `unmarked legacy local metadata is not forcibly overwritten by remote`() {
        val local = syncSong(
            id = 1L,
            name = "Z Legacy user title",
            channelId = "netease",
            audioId = "1",
            membershipTokens = listOf(token("local", 1L)),
            syncMetadataVersion = LEGACY_SYNC_METADATA_VERSION
        ).copy(customName = "Z Legacy custom title")
        val remote = syncSong(
            id = 1L,
            name = "A Synced title",
            channelId = "netease",
            audioId = "1",
            membershipTokens = listOf(token("remote", 1L)),
            syncMetadataVersion = LEGACY_SYNC_METADATA_VERSION
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
            localModifiedAt = 100L,
            remoteModifiedAt = 100L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = false,
            lastSyncTime = 200L,
            isFavorites = false
        )

        assertEquals("Z Legacy user title", result.songs.single().name)
        assertEquals("Z Legacy custom title", result.songs.single().customName)
        assertEquals(
            listOf(token("local", 1L), token("remote", 1L)),
            result.songs.single().syncMembershipTokens
        )
        assertTrue(result.isUpdated)
    }

    @Test
    fun `fallback duplicate unions membership tokens`() {
        val first = syncSong(
            id = 1L,
            name = "Song",
            album = "Legacy A",
            mediaUri = "https://legacy-a.invalid/audio",
            membershipTokens = listOf(token("a", 1L))
        )
        val duplicate = first.copy(
            album = "Legacy B",
            mediaUri = "https://legacy-b.invalid/audio",
            syncMembershipTokens = listOf(token("b", 1L))
        )

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(first, duplicate))

        assertEquals(
            listOf(
                first.copy(
                    syncMembershipTokens = listOf(token("a", 1L), token("b", 1L))
                )
            ),
            result
        )
    }

    @Test
    fun `shared membership token collapses identity drift`() {
        val membership = token("device", 1L)
        val legacy = syncSong(
            id = 1L,
            name = "Legacy",
            album = "Legacy Album",
            membershipTokens = listOf(membership)
        )
        val hydrated = syncSong(
            id = 9L,
            name = "Hydrated",
            album = "New Album",
            channelId = "netease",
            audioId = "9",
            membershipTokens = listOf(membership)
        )

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(legacy, hydrated))

        assertEquals(listOf(legacy), result)
    }

    @Test
    fun `bridge aliases merge the complete membership component`() {
        val tokenA = token("a", 1L)
        val tokenB = token("b", 1L)
        val first = syncSong(
            id = 1L,
            name = "First",
            album = "First Album",
            mediaUri = "https://first.invalid/audio",
            membershipTokens = listOf(tokenA)
        )
        val second = syncSong(
            id = 2L,
            name = "Second",
            album = "Second Album",
            mediaUri = "https://second.invalid/audio",
            membershipTokens = listOf(tokenB)
        )
        val bridge = first.copy(syncMembershipTokens = listOf(tokenB))
        val permutations = listOf(
            listOf(first, second, bridge),
            listOf(second, bridge, first),
            listOf(bridge, first, second)
        )

        permutations.forEach { songs ->
            val result = SyncPlaylistSongMergePolicy.deduplicateSongs(songs)

            assertEquals(1, result.size)
            assertEquals(listOf(tokenA, tokenB), result.single().syncMembershipTokens)
        }
    }

    @Test
    fun `unknown fallback bridge merges every matching source component`() {
        val netease = syncSong(
            id = 1L,
            name = "Song",
            album = "netease album",
            mediaUri = "https://netease.invalid/audio",
            membershipTokens = listOf(token("netease", 1L))
        )
        val bilibili = syncSong(
            id = 1L,
            name = "Song",
            album = "bilibili album",
            mediaUri = "https://bilibili.invalid/audio",
            membershipTokens = listOf(token("bilibili", 1L))
        )
        val unknown = syncSong(
            id = 1L,
            name = "Song",
            album = "legacy album",
            mediaUri = "https://legacy.invalid/audio",
            membershipTokens = listOf(token("legacy", 1L))
        )

        listOf(
            listOf(netease, bilibili, unknown),
            listOf(bilibili, netease, unknown)
        ).forEach { songs ->
            val result = SyncPlaylistSongMergePolicy.deduplicateSongs(songs)

            assertEquals(1, result.size)
            assertEquals(
                listOf(
                    token("bilibili", 1L),
                    token("legacy", 1L),
                    token("netease", 1L)
                ),
                result.single().syncMembershipTokens
            )
        }
    }

    @Test
    fun `duplicate songs in same snapshot are collapsed`() {
        val first = syncSong(id = 1L, name = "Song")
        val duplicate = syncSong(id = 1L, name = "Song")

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(first, duplicate))

        assertEquals(listOf(first), result)
    }

    @Test
    fun `single song membership tokens are normalized deterministically`() {
        val song = syncSong(
            id = 1L,
            name = "Song",
            membershipTokens = listOf(
                token("b", 1L),
                token("a", 2L),
                token("a", 1L),
                token("a", 2L)
            )
        )

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(song))

        assertEquals(
            listOf(
                song.copy(
                    syncMembershipTokens = listOf(
                        token("a", 1L),
                        token("a", 2L),
                        token("b", 1L)
                    )
                )
            ),
            result
        )
    }

    @Test
    fun `exact duplicate songs union membership tokens and keep first metadata`() {
        val first = syncSong(
            id = 1L,
            name = "Local metadata",
            membershipTokens = listOf(token("b", 1L), token("a", 100L))
        )
        val duplicate = syncSong(
            id = 1L,
            name = "Remote metadata",
            membershipTokens = listOf(token("b", 1L), token("c", 1L))
        )

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(first, duplicate))

        assertEquals(
            listOf(
                first.copy(
                    syncMembershipTokens = listOf(
                        token("a", 100L),
                        token("b", 1L),
                        token("c", 1L)
                    )
                )
            ),
            result
        )
    }

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
    fun `snapshot deduplication keeps genuine selected custom display metadata`() {
        val song = syncSong(id = 1L, name = "New title", artist = "New artist").copy(
            customName = "Original title",
            customArtist = "Original artist",
            originalName = "Original title",
            originalArtist = "Original artist"
        )

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(song))

        assertEquals("Original title", result.single().customName)
        assertEquals("Original artist", result.single().customArtist)
    }

    @Test
    fun `two changed endpoints resolve exact duplicate payload deterministically`() {
        val local = syncSong(
            id = 1L,
            name = "Local metadata",
            membershipTokens = listOf(token("local", 1L))
        ).copy(
            customName = "Stale local title",
            customArtist = "Stale local artist",
            customCoverUrl = "https://example.com/stale-local.jpg"
        )
        val remote = syncSong(
            id = 1L,
            name = "Remote metadata",
            membershipTokens = listOf(token("remote", 1L))
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
            localModifiedAt = 200L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 100L,
            isFavorites = false
        )

        assertEquals(
            listOf(
                remote.copy(
                    syncMembershipTokens = listOf(
                        token("local", 1L),
                        token("remote", 1L)
                    )
                )
            ),
            result.songs
        )
    }

    @Test
    fun `newer local batch metadata wins over older remote payloads`() {
        val localFirst = syncSong(
            id = 1L,
            name = "Local title 1",
            artist = "Local artist 1",
            membershipTokens = listOf(token("local", 1L))
        ).copy(
            matchedLyric = "",
            userLyricOffsetMs = 0L
        )
        val localSecond = syncSong(
            id = 2L,
            name = "Local title 2",
            artist = "Local artist 2",
            membershipTokens = listOf(token("local", 2L))
        ).copy(customName = null)
        val remoteFirst = syncSong(
            id = 1L,
            name = "Remote old title 1",
            artist = "Remote old artist 1",
            membershipTokens = listOf(token("remote", 1L))
        ).copy(
            matchedLyric = "[00:00.00]old lyric",
            userLyricOffsetMs = 500L
        )
        val remoteSecond = syncSong(
            id = 2L,
            name = "Remote old title 2",
            artist = "Remote old artist 2",
            membershipTokens = listOf(token("remote", 2L))
        ).copy(customName = "Old custom")

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(localFirst, localSecond),
            remoteSongs = listOf(remoteFirst, remoteSecond),
            localModifiedAt = 300L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 100L,
            isFavorites = false
        )

        assertEquals(listOf("Local title 1", "Local title 2"), result.songs.map { it.name })
        assertEquals(listOf("Local artist 1", "Local artist 2"), result.songs.map { it.artist })
        assertEquals("", result.songs.first().matchedLyric)
        assertEquals(0L, result.songs.first().userLyricOffsetMs)
        assertNull(result.songs[1].customName)
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
    fun `remote primary keeps metadata while merging exact local token`() {
        val local = syncSong(
            id = 1L,
            name = "Local metadata",
            membershipTokens = listOf(token("local", 1L))
        )
        val remote = syncSong(
            id = 1L,
            name = "Remote metadata",
            membershipTokens = listOf(token("remote", 1L))
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

        assertEquals(
            listOf(
                remote.copy(
                    syncMembershipTokens = listOf(
                        token("local", 1L),
                        token("remote", 1L)
                    )
                )
            ),
            result.songs
        )
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

    @Test
    fun `remote primary custom metadata stays atomic`() {
        val local = syncSong(
            id = 1L,
            name = "Local metadata",
            membershipTokens = listOf(token("local", 1L))
        ).copy(customName = "Local custom title")
        val remote = syncSong(
            id = 1L,
            name = "Remote metadata",
            membershipTokens = listOf(token("remote", 1L))
        ).copy(customName = "Remote custom title")

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

        assertEquals("Remote custom title", result.songs.single().customName)
    }

    @Test
    fun `changed primary keeps explicitly cleared lyrics and reset offset`() {
        val local = syncSong(
            id = 1L,
            name = "Song",
            membershipTokens = listOf(token("local", 1L))
        ).copy(
            matchedLyric = "",
            matchedTranslatedLyric = "",
            matchedLyricSource = null,
            matchedSongId = null,
            userLyricOffsetMs = 0L
        )
        val remote = syncSong(
            id = 1L,
            name = "Song",
            membershipTokens = listOf(token("remote", 1L))
        ).copy(
            matchedLyric = "[00:00.00]old lyric",
            matchedTranslatedLyric = "[00:00.00]old translation",
            matchedLyricSource = "QQ_MUSIC",
            matchedSongId = "qq:old",
            userLyricOffsetMs = 350L
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
            localModifiedAt = 300L,
            remoteModifiedAt = 100L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = false,
            lastSyncTime = 200L,
            isFavorites = false
        )

        val merged = result.songs.single()
        assertEquals("", merged.matchedLyric)
        assertEquals("", merged.matchedTranslatedLyric)
        assertEquals(0L, merged.userLyricOffsetMs)
        assertEquals(listOf(token("local", 1L), token("remote", 1L)), merged.syncMembershipTokens)
    }

    @Test
    fun `local primary keeps cleared custom metadata`() {
        val local = syncSong(
            id = 1L,
            name = "Song",
            membershipTokens = listOf(token("local", 1L))
        )
        val remote = syncSong(
            id = 1L,
            name = "Song",
            membershipTokens = listOf(token("remote", 1L))
        ).copy(
            customName = "Old custom title",
            customArtist = "Old custom artist",
            customCoverUrl = "https://example.com/old.jpg"
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
            localModifiedAt = 300L,
            remoteModifiedAt = 100L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = false,
            lastSyncTime = 200L,
            isFavorites = false
        )

        val merged = result.songs.single()
        assertNull(merged.customName)
        assertNull(merged.customArtist)
        assertNull(merged.customCoverUrl)
        assertEquals(listOf(token("local", 1L), token("remote", 1L)), merged.syncMembershipTokens)
    }

    @Test
    fun `remote changed primary keeps reset lyric offset`() {
        val local = syncSong(
            id = 1L,
            name = "Song",
            membershipTokens = listOf(token("local", 1L))
        ).copy(userLyricOffsetMs = 350L)
        val remote = syncSong(
            id = 1L,
            name = "Song",
            membershipTokens = listOf(token("remote", 1L))
        ).copy(userLyricOffsetMs = 0L)

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
            localModifiedAt = 100L,
            remoteModifiedAt = 300L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 200L,
            isFavorites = false
        )

        assertEquals(0L, result.songs.single().userLyricOffsetMs)
    }

    @Test
    fun `deterministic merge keeps lyric offset from the selected payload`() {
        val local = syncSong(
            id = 1L,
            name = "A Song",
            membershipTokens = listOf(token("local", 1L))
        ).copy(userLyricOffsetMs = 420L)
        val remote = syncSong(
            id = 1L,
            name = "Z Song",
            membershipTokens = listOf(token("remote", 1L))
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
            localModifiedAt = 300L,
            remoteModifiedAt = 300L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 200L,
            isFavorites = false
        )

        assertEquals("Z Song", result.songs.single().name)
        assertEquals(0L, result.songs.single().userLyricOffsetMs)
    }

    @Test
    fun `large local clear skips remote refill`() {
        val remoteSongs = (1..2_000).map { index ->
            syncSong(id = index.toLong(), name = "Song $index")
        }

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = emptyList(),
            remoteSongs = remoteSongs,
            localModifiedAt = 300L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = false,
            lastSyncTime = 250L,
            isFavorites = false
        )

        assertTrue(result.songs.isEmpty())
        assertEquals(false, result.isUpdated)
    }

    @Test
    fun `concurrent disjoint lists merge without dropping songs when local is newer`() {
        val localSongs = (1..2_000).map { index ->
            syncSong(id = index.toLong(), name = "Local $index")
        }
        val remoteSongs = (2_001..4_000).map { index ->
            syncSong(id = index.toLong(), name = "Remote $index")
        }

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = localSongs,
            remoteSongs = remoteSongs,
            localModifiedAt = 300L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 100L,
            isFavorites = false
        )

        assertEquals(4_000, result.songs.size)
        assertEquals(localSongs.first(), result.songs.first())
        assertEquals(remoteSongs.last(), result.songs.last())
    }

    @Test
    fun `concurrent remote primary keeps local only songs and remote matching payload`() {
        val localCommon = syncSong(
            id = 1L,
            name = "Old title",
            membershipTokens = listOf(token("local", 1L))
        )
        val localOnly = syncSong(
            id = 2L,
            name = "Local only",
            membershipTokens = listOf(token("local", 2L))
        )
        val remoteCommon = syncSong(
            id = 1L,
            name = "New title",
            membershipTokens = listOf(token("remote", 1L))
        )
        val remoteOnly = syncSong(
            id = 3L,
            name = "Remote only",
            membershipTokens = listOf(token("remote", 3L))
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(localCommon, localOnly),
            remoteSongs = listOf(remoteCommon, remoteOnly),
            localModifiedAt = 200L,
            remoteModifiedAt = 300L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 100L,
            isFavorites = false
        )

        assertEquals(listOf(1L, 3L, 2L), result.songs.map(SyncSong::id))
        assertEquals("New title", result.songs.first().name)
        assertEquals(
            listOf(token("local", 1L), token("remote", 1L)),
            result.songs.first().syncMembershipTokens
        )
    }

    @Test
    fun `fallback merge keeps different source hints`() {
        val neteaseSong = syncSong(
            id = 1L,
            name = "Song",
            album = "netease album",
            channelId = null
        )
        val biliSong = syncSong(
            id = 1L,
            name = "Song",
            album = "bilibili album",
            channelId = null
        )

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(neteaseSong, biliSong))

        assertEquals(listOf(neteaseSong, biliSong), result)
    }

    @Test
    fun `empty snapshots are not marked updated`() {
        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = emptyList(),
            remoteSongs = emptyList(),
            localModifiedAt = 200L,
            remoteModifiedAt = 100L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = false,
            lastSyncTime = 150L,
            isFavorites = false
        )

        assertTrue(result.songs.isEmpty())
        assertEquals(false, result.isUpdated)
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
