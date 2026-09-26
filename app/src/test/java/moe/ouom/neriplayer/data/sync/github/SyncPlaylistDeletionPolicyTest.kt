package moe.ouom.neriplayer.data.sync.github

import com.google.gson.Gson
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.sync.model.SyncPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncPlaylistDeletionPolicyTest {
    @Test
    fun `latest deletion wins for same playlist song`() {
        val first = deletion(playlistId = 7L, songId = 11L, deletedAt = 100L, deviceId = "a")
        val latest = deletion(playlistId = 7L, songId = 11L, deletedAt = 200L, deviceId = "b")

        val merged = SyncPlaylistDeletionPolicy.mergeDeletions(
            local = listOf(first),
            remote = listOf(latest)
        )

        assertEquals(listOf(latest), merged)
    }

    @Test
    fun `deletion merge unions observed tokens with deterministic legacy mirror`() {
        val first = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 100L,
            deviceId = "a",
            removedTokens = listOf(token("b", 1L), token("a", 2L))
        )
        val latest = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            deviceId = "b",
            removedTokens = listOf(token("a", 2L), token("a", 1L))
        )
        val expected = latest.copy(
            removedMembershipTokens = listOf(
                token("a", 1L, counterEnd = 2L),
                token("b", 1L)
            )
        )

        val localFirst = SyncPlaylistDeletionPolicy.mergeDeletions(listOf(first), listOf(latest))
        val remoteFirst = SyncPlaylistDeletionPolicy.mergeDeletions(listOf(latest), listOf(first))

        assertEquals(listOf(expected), localFirst)
        assertEquals(localFirst, remoteFirst)
    }

    @Test
    fun `causal range merge is commutative and idempotent`() {
        val local = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 100L,
            removedTokens = listOf(token("device", 1L, counterEnd = 3L))
        )
        val remote = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            removedTokens = listOf(token("device", 4L, counterEnd = 6L))
        )

        val first = SyncPlaylistDeletionPolicy.mergeDeletions(listOf(local), listOf(remote))
        val reverse = SyncPlaylistDeletionPolicy.mergeDeletions(listOf(remote), listOf(local))
        val replayed = SyncPlaylistDeletionPolicy.mergeDeletions(first, first)

        assertEquals(first, reverse)
        assertEquals(first, replayed)
        assertEquals(
            listOf(remote.copy(removedMembershipTokens = listOf(token("device", 1L, counterEnd = 6L)))),
            first
        )
    }

    @Test
    fun `stale song is filtered by deletion`() {
        val songs = listOf(syncSong(id = 11L, addedAt = 100L))
        val deletions = listOf(deletion(playlistId = 7L, songId = 11L, deletedAt = 200L))

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = songs,
            deletions = deletions
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `readded song survives newer than deletion`() {
        val readded = syncSong(id = 11L, addedAt = 300L)
        val deletions = listOf(deletion(playlistId = 7L, songId = 11L, deletedAt = 200L))

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = listOf(readded),
            deletions = deletions
        )

        assertEquals(listOf(readded), merged)
    }

    @Test
    fun `observed token deletion removes ordinary membership`() {
        val membership = token("device", 1L)
        val song = syncSong(
            id = 11L,
            addedAt = 300L,
            membershipTokens = listOf(membership)
        )
        val deletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 100L,
            removedTokens = listOf(membership)
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(7L, listOf(song), listOf(deletion))
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `restored membership survives deletion of old token regardless of timestamp`() {
        val restored = syncSong(
            id = 11L,
            addedAt = 100L,
            membershipTokens = listOf(token("device", 2L))
        )
        val deletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            removedTokens = listOf(token("device", 1L))
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = listOf(restored),
            deletions = listOf(deletion)
        )

        assertEquals(listOf(restored), merged)
    }

    @Test
    fun `partial observed deletion strips old token and keeps concurrent membership`() {
        val oldToken = token("device", 1L)
        val concurrentToken = token("other", 1L)
        val song = syncSong(
            id = 11L,
            addedAt = 100L,
            membershipTokens = listOf(oldToken, concurrentToken)
        )
        val deletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            removedTokens = listOf(oldToken)
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(7L, listOf(song), listOf(deletion))

        assertEquals(
            listOf(song.copy(syncMembershipTokens = listOf(concurrentToken))),
            merged
        )
    }

    @Test
    fun `observed deletion removes token after identity drift`() {
        val membership = token("device", 1L)
        val song = syncSong(
            id = 99L,
            addedAt = 100L,
            album = "New Album",
            mediaUri = "https://new.invalid/audio",
            membershipTokens = listOf(membership)
        )
        val deletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            album = "Old Album",
            mediaUri = "https://old.invalid/audio",
            removedTokens = listOf(membership)
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(7L, listOf(song), listOf(deletion))

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `all causal deletions apply regardless of identity alias`() {
        val firstToken = token("device", 1L)
        val secondToken = token("other", 1L)
        val song = syncSong(
            id = 99L,
            addedAt = 100L,
            membershipTokens = listOf(firstToken, secondToken)
        )
        val deletions = listOf(
            deletion(
                playlistId = 7L,
                songId = 11L,
                deletedAt = 200L,
                removedTokens = listOf(firstToken)
            ),
            deletion(
                playlistId = 7L,
                songId = 12L,
                deletedAt = 300L,
                removedTokens = listOf(secondToken)
            )
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(7L, listOf(song), deletions)

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `causal deletion application is independent of deletion order`() {
        val firstToken = token("device", 1L)
        val secondToken = token("other", 1L)
        val survivingToken = token("survivor", 1L)
        val song = syncSong(
            id = 99L,
            addedAt = 100L,
            membershipTokens = listOf(firstToken, secondToken, survivingToken)
        )
        val deletions = listOf(
            deletion(
                playlistId = 7L,
                songId = 11L,
                deletedAt = 200L,
                removedTokens = listOf(firstToken)
            ),
            deletion(
                playlistId = 7L,
                songId = 12L,
                deletedAt = 300L,
                removedTokens = listOf(secondToken)
            )
        )

        val firstOrder = SyncPlaylistDeletionPolicy.applyDeletions(7L, listOf(song), deletions)
        val reverseOrder = SyncPlaylistDeletionPolicy.applyDeletions(
            7L,
            listOf(song),
            deletions.reversed()
        )

        assertEquals(firstOrder, reverseOrder)
        assertEquals(
            listOf(song.copy(syncMembershipTokens = listOf(survivingToken))),
            firstOrder
        )
    }

    @Test
    fun `legacy snapshot does not delete causal readd`() {
        val oldToken = token("device", 1L)
        val causalDeletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 100L,
            deviceId = "causal",
            removedTokens = listOf(oldToken)
        )
        val legacyDeletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 300L,
            deviceId = "legacy"
        )

        val mergedDeletions = SyncPlaylistDeletionPolicy.mergeDeletions(
            local = listOf(causalDeletion),
            remote = listOf(legacyDeletion)
        )
        val mergedSongs = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = listOf(syncSong(
                id = 11L,
                addedAt = 200L,
                membershipTokens = listOf(token("device", 2L))
            )),
            deletions = mergedDeletions
        )

        assertEquals(listOf(legacyDeletion, causalDeletion), mergedDeletions)
        assertEquals(1, mergedSongs.size)
    }

    @Test
    fun `deletion only affects matching playlist`() {
        val song = syncSong(id = 11L, addedAt = 100L)
        val deletions = listOf(deletion(playlistId = 8L, songId = 11L, deletedAt = 200L))

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = listOf(song),
            deletions = deletions
        )

        assertEquals(listOf(song), merged)
    }

    @Test
    fun `resolved readd with membership token prunes legacy deletion`() {
        // 真正重新添加会拿到新 token (该 identity 由 causal token 接管) , legacy 墓碑冗余可裁
        val deletions = listOf(deletion(playlistId = 7L, songId = 11L, deletedAt = 200L))
        val playlists = listOf(
            SyncPlaylist(
                id = 7L,
                name = "playlist",
                songs = listOf(
                    syncSong(
                        id = 11L,
                        addedAt = 300L,
                        membershipTokens = listOf(token("device", 5L))
                    )
                ),
                createdAt = 0L,
                modifiedAt = 300L
            )
        )

        val pruned = SyncPlaylistDeletionPolicy.pruneResolvedDeletions(
            deletions = deletions,
            playlists = playlists
        )

        assertTrue(pruned.isEmpty())
    }

    @Test
    fun `legacy migrated song without token does not prune deletion`() {
        // P1-1 回归: 无 token 的活跃歌曲 (addedAt 可能来自 legacy 迁移合成, 被抬到 deletedAt 之后)
        // 不得据其 addedAt 裁掉 legacy 墓碑, 否则墓碑全网消失导致已删歌永久复活
        val deletions = listOf(deletion(playlistId = 7L, songId = 11L, deletedAt = 200L))
        val playlists = listOf(
            SyncPlaylist(
                id = 7L,
                name = "playlist",
                songs = listOf(syncSong(id = 11L, addedAt = 999L)),
                createdAt = 0L,
                modifiedAt = 999L
            )
        )

        val pruned = SyncPlaylistDeletionPolicy.pruneResolvedDeletions(
            deletions = deletions,
            playlists = playlists
        )

        assertEquals(deletions, pruned)
    }

    @Test
    fun `legacy snapshot migration keeps deleted song deleted`() {
        // P1-1 端到端回归: 旧格式快照 (songOrderVersion 缺省=LEGACY) 经迁移后
        // 锚点用歌单 modifiedAt (快照产生时刻, 早于删除) 而非墙钟 now
        // 使 identity 墓碑 (deletedAt 晚于 modifiedAt) 仍然生效, 已删歌不复活
        val legacyPlaylist = SyncPlaylist(
            id = 7L,
            name = "legacy",
            songs = listOf(syncSong(id = 11L, addedAt = 0L, album = "netease")),
            createdAt = 0L,
            modifiedAt = 100L
        )
        val migrated = legacyPlaylist.normalizedForDisplayOrder()

        // 迁移后的 addedAt 不得晚于快照 modifiedAt (不再被抬到墙钟 now)
        assertTrue(
            "迁移锚点不得使用墙钟 now",
            migrated.songs.single().addedAt <= 100L
        )

        val deletions = listOf(
            deletion(playlistId = 7L, songId = 11L, deletedAt = 200L, album = "netease")
        )
        val survivors = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = migrated.songs,
            deletions = deletions
        )

        assertTrue("已删歌迁移后不得复活", survivors.isEmpty())
    }

    @Test
    fun `legacy migration keeps the original addedAt for deletion comparisons`() {
        val legacyPlaylist = SyncPlaylist(
            id = 7L,
            name = "legacy",
            songs = listOf(syncSong(id = 11L, addedAt = 0L)),
            modifiedAt = 500L
        )
        val migrated = legacyPlaylist.normalizedForDisplayOrder().songs.single()

        assertEquals(0L, migrated.legacyAddedAt)
        assertTrue(migrated.addedAt > 0L)
        assertTrue(
            SyncPlaylistDeletionPolicy.applyDeletions(
                playlistId = 7L,
                songs = listOf(migrated),
                deletions = listOf(deletion(7L, 11L, deletedAt = 200L))
            ).isEmpty()
        )
    }

    @Test
    fun `favorite playlist deletion wins equal timestamp ties`() {
        val active = SyncFavoritePlaylist(
            id = 1L,
            source = "netease",
            modifiedAt = 100L,
            songs = listOf(syncSong(id = 11L, addedAt = 100L)),
            trackCount = 1
        )
        val deleted = active.copy(isDeleted = true, songs = emptyList(), trackCount = 0)

        val merged = SyncPlaylistDeletionPolicy.mergeFavoritePlaylists(active, deleted)

        assertTrue(merged.isDeleted)
        assertTrue(merged.songs.isEmpty())
        assertEquals(0, merged.trackCount)
    }

    @Test
    fun `observed token deletion is retained after a new membership appears`() {
        val deletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            removedTokens = listOf(token("device", 1L))
        )
        val playlists = listOf(
            SyncPlaylist(
                id = 7L,
                name = "playlist",
                songs = listOf(
                    syncSong(
                        id = 11L,
                        addedAt = 300L,
                        membershipTokens = listOf(token("device", 2L))
                    )
                ),
                createdAt = 0L,
                modifiedAt = 300L
            )
        )

        val pruned = SyncPlaylistDeletionPolicy.pruneResolvedDeletions(
            deletions = listOf(deletion),
            playlists = playlists
        )

        assertEquals(listOf(deletion), pruned)
    }

    @Test
    fun `readd clears legacy deletion but retains observed token deletion`() {
        val identitySong = syncSong(id = 11L, addedAt = 300L)
        val legacyDeletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 100L,
            deviceId = "legacy"
        )
        val causalDeletion = deletion(
            playlistId = 7L,
            songId = 12L,
            deletedAt = 200L,
            removedTokens = listOf(token("device", 1L))
        )

        val retained = SyncPlaylistDeletionPolicy.clearLegacyDeletionsForReaddedSongs(
            deletions = listOf(legacyDeletion, causalDeletion),
            playlistId = 7L,
            identities = listOf(identitySong.identity(), syncSong(12L, 300L).identity())
        )

        assertEquals(listOf(causalDeletion), retained)
    }

    @Test
    fun `newer restored playlist overrides older tombstone while tie keeps deletion`() {
        val active = SyncPlaylist(
            id = 7L,
            name = "playlist",
            modifiedAt = 300L
        )
        val deleted = active.copy(
            modifiedAt = 200L,
            isDeleted = true
        )

        assertFalse(SyncPlaylistDeletionPolicy.shouldKeepPlaylistDeleted(active, deleted))
        assertFalse(SyncPlaylistDeletionPolicy.shouldKeepPlaylistDeleted(deleted, active))

        val tiedDeletion = deleted.copy(modifiedAt = active.modifiedAt)
        assertTrue(SyncPlaylistDeletionPolicy.shouldKeepPlaylistDeleted(active, tiedDeletion))
        assertTrue(SyncPlaylistDeletionPolicy.shouldKeepPlaylistDeleted(tiedDeletion, active))
    }

    @Test
    fun `legacy song without addedAt still respects deletion`() {
        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = listOf(syncSong(id = 11L, addedAt = 0L)),
            deletions = listOf(deletion(playlistId = 7L, songId = 11L, deletedAt = 200L))
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `legacy raw identity deletion survives netease identity normalization`() {
        val song = syncSong(
            id = 11L,
            addedAt = 100L,
            album = "Legacy Album"
        )
        val legacyDeletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            album = "Legacy Album",
            deviceId = "legacy"
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = listOf(song),
            deletions = listOf(legacyDeletion)
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `gson legacy null token lists use timestamp fallback`() {
        val gson = Gson()
        val song = gson.fromJson(
            """{"id":11,"name":"Song","artist":"Artist","album":"netease","addedAt":100}""",
            SyncSong::class.java
        )
        val deletion = gson.fromJson(
            """{"playlistId":7,"songId":11,"album":"netease","deletedAt":200,"deviceId":"legacy"}""",
            SyncPlaylistSongDeletion::class.java
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(7L, listOf(song), listOf(deletion))
        val normalizedDeletion = SyncPlaylistDeletionPolicy
            .mergeDeletions(listOf(deletion), emptyList())
            .single()

        assertTrue(merged.isEmpty())
        assertEquals(emptyList<SyncCausalToken>(), normalizedDeletion.removedMembershipTokens)
    }

    @Test
    fun `deletion capacity preserves all tombstone classes when within capacity`() {
        val legacy = (1L..6L).map { id ->
            deletion(playlistId = 7L, songId = id, deletedAt = id, deviceId = "legacy")
        }
        val causal = (11L..16L).map { id ->
            deletion(
                playlistId = 7L,
                songId = id,
                deletedAt = id,
                deviceId = "causal",
                removedTokens = listOf(token("causal", id))
            )
        }

        val retained = SyncPlaylistDeletionPolicy.limitDeletions(
            deletions = legacy + causal,
            maxCount = 12
        )

        assertEquals(7, retained.size)
        assertEquals(6, retained.count { it.removedMembershipTokens.isEmpty() })
        assertEquals(1, retained.count { it.removedMembershipTokens.isNotEmpty() })
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), retained.filter {
            it.removedMembershipTokens.isEmpty()
        }.map { it.songId }.toSet())
        assertEquals(
            listOf(token("causal", 11L, counterEnd = 16L)),
            retained.single { it.removedMembershipTokens.isNotEmpty() }
                .removedMembershipTokens
        )
    }

    @Test
    fun `deletion capacity keeps unused capacity available without truncation`() {
        val legacy = (1L..2L).map { id ->
            deletion(playlistId = 7L, songId = id, deletedAt = id, deviceId = "legacy")
        }
        val causal = (11L..16L).map { id ->
            deletion(
                playlistId = 7L,
                songId = id,
                deletedAt = id,
                deviceId = "causal",
                removedTokens = listOf(token("causal", id))
            )
        }

        val retained = SyncPlaylistDeletionPolicy.limitDeletions(
            deletions = legacy + causal,
            maxCount = 8
        )

        assertEquals(3, retained.size)
        assertEquals(2, retained.count { it.removedMembershipTokens.isEmpty() })
        assertEquals(1, retained.count { it.removedMembershipTokens.isNotEmpty() })
    }

    @Test
    fun `causal tombstones across songs collapse into one playlist record`() {
        val deletions = (1L..3L).map { id ->
            deletion(
                playlistId = 7L,
                songId = id,
                deletedAt = id,
                removedTokens = listOf(token("device", id))
            )
        }

        val retained = SyncPlaylistDeletionPolicy.limitDeletions(
            deletions = deletions,
            maxCount = 1
        )

        assertEquals(1, retained.size)
        assertEquals(
            listOf(token("device", 1L, counterEnd = 3L)),
            retained.single().removedMembershipTokens
        )
    }

    @Test
    fun `deletion capacity overflow is rejected instead of dropping tombstones`() {
        val deletions = (1L..2L).map { id ->
            deletion(playlistId = 7L, songId = id, deletedAt = id)
        }

        assertThrows(IllegalArgumentException::class.java) {
            SyncPlaylistDeletionPolicy.limitDeletions(deletions, maxCount = 1)
        }
    }

    @Test
    fun `range tombstone removes only observed memberships`() {
        val observed = token("device", 11L)
        val concurrent = token("device", 13L)
        val deletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            removedTokens = listOf(token("device", 10L, counterEnd = 12L))
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = listOf(
                syncSong(
                    id = 11L,
                    addedAt = 100L,
                    membershipTokens = listOf(observed, concurrent)
                )
            ),
            deletions = listOf(deletion)
        )

        assertEquals(
            listOf(
                syncSong(
                    id = 11L,
                    addedAt = 100L,
                    membershipTokens = listOf(concurrent)
                )
            ),
            merged
        )
    }

    @Test
    fun `fragmented causal ranges are rejected instead of truncated`() {
        val fragmented = (1L..65L).map { counter ->
            deletion(
                playlistId = 7L,
                songId = 11L,
                deletedAt = 200L,
                removedTokens = listOf(token("device", counter * 2L))
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            SyncPlaylistDeletionPolicy.limitDeletions(
                deletions = fragmented,
                maxCount = 1
            )
        }
    }

    private fun syncSong(
        id: Long,
        addedAt: Long,
        album: String = "netease",
        mediaUri: String? = null,
        membershipTokens: List<SyncCausalToken> = emptyList()
    ): SyncSong {
        return SyncSong(
            id = id,
            name = "Song $id",
            artist = "Artist",
            album = album,
            mediaUri = mediaUri,
            addedAt = addedAt,
            syncMembershipTokens = membershipTokens
        )
    }

    private fun deletion(
        playlistId: Long,
        songId: Long,
        deletedAt: Long,
        album: String = "netease",
        mediaUri: String? = null,
        deviceId: String = "device",
        removedTokens: List<SyncCausalToken> = emptyList()
    ): SyncPlaylistSongDeletion {
        return SyncPlaylistSongDeletion(
            playlistId = playlistId,
            songId = songId,
            album = album,
            mediaUri = mediaUri,
            deletedAt = deletedAt,
            deviceId = deviceId,
            removedMembershipTokens = removedTokens
        )
    }

    private fun token(
        deviceId: String,
        counter: Long,
        counterEnd: Long = 0L
    ): SyncCausalToken {
        return SyncCausalToken(
            deviceId = deviceId,
            counter = counter,
            counterEnd = counterEnd
        )
    }
}
