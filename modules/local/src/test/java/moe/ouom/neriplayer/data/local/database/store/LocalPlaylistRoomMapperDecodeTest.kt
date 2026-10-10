package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPlaylistRoomMapperDecodeTest {
    private val mapper = LocalPlaylistRoomMapper()

    @Test
    fun `undecodable member is skipped while other songs and playlists still load`() {
        val snapshot = mapper.toSnapshot(
            listOf(
                playlist(1L, "first", song(11L), song(12L), song(13L)),
                playlist(2L, "second", song(21L))
            )
        )
        val brokenKey = snapshot.members.first { it.songId() == 12L }.identityKey
        val members = snapshot.members.map { member ->
            if (member.identityKey == brokenKey) member.copy(memberPayloadJson = "{not json") else member
        }
        val tracks = snapshot.tracks.map { track ->
            if (track.identityKey == brokenKey) track.copy(durablePayloadJson = "[]") else track
        }

        val loaded = mapper.toDomain(snapshot.playlists, tracks, members, snapshot.memberTokens)

        assertEquals(listOf(1L, 2L), loaded.map(LocalPlaylist::id))
        assertEquals(listOf(11L, 13L), loaded[0].songs.map(SongItem::id))
        assertEquals(listOf(21L), loaded[1].songs.map(SongItem::id))
    }

    @Test
    fun `member payload falls back to the durable track payload`() {
        val snapshot = mapper.toSnapshot(listOf(playlist(1L, "first", song(11L))))
        val members = snapshot.members.map { it.copy(memberPayloadJson = "null") }

        val loaded = mapper.toDomain(snapshot.playlists, snapshot.tracks, members, snapshot.memberTokens)

        assertEquals(listOf("song 11"), loaded.single().songs.map(SongItem::name))
    }

    @Test
    fun `member without a track row still loads from its own payload`() {
        val snapshot = mapper.toSnapshot(listOf(playlist(1L, "first", song(11L), song(12L))))

        val loaded = mapper.toDomain(snapshot.playlists, emptyList(), snapshot.members, snapshot.memberTokens)

        assertEquals(listOf(11L, 12L), loaded.single().songs.map(SongItem::id))
    }

    @Test
    fun `lyric source written by a newer app decodes as unknown instead of failing`() {
        val snapshot = mapper.toSnapshot(
            listOf(playlist(1L, "first", song(11L).copy(matchedLyricSource = MusicPlatform.QQ_MUSIC)))
        )
        val members = snapshot.members.map { member ->
            member.copy(
                memberPayloadJson = member.memberPayloadJson.replace("\"QQ_MUSIC\"", "\"FUTURE_PLATFORM\"")
            )
        }

        val loaded = mapper.toDomain(snapshot.playlists, snapshot.tracks, members, snapshot.memberTokens)

        val restored = loaded.single().songs.single()
        assertEquals(11L, restored.id)
        assertNull(restored.matchedLyricSource)
    }

    private fun moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberEntity.songId(): Long {
        return Regex("\"id\":(\\d+)").find(memberPayloadJson)!!.groupValues[1].toLong()
    }

    private fun playlist(id: Long, name: String, vararg songs: SongItem) = LocalPlaylist(
        id = id,
        name = name,
        songs = songs.toMutableList(),
        modifiedAt = 1_000L
    )

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song $id",
        artist = "artist $id",
        album = "netease",
        albumId = id + 100L,
        durationMs = 180_000L,
        coverUrl = null,
        channelId = "netease",
        audioId = id.toString(),
        addedAt = 1_000L + id
    )
}
