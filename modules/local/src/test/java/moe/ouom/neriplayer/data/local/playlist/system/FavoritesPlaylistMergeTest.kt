package moe.ouom.neriplayer.data.local.playlist.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.sync.mapping.fromLocalPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoritesPlaylistMergeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `duplicate favorites merge into one system playlist with the latest time and last custom cover`() {
        val shared = song(1L)
        val merged = FavoritesPlaylist.merge(
            listOf(
                playlist(-3L, modifiedAt = 30L, customCoverUrl = "content://covers/first.jpg", songs = listOf(shared, song(2L))),
                playlist(-4L, modifiedAt = 50L, customCoverUrl = "content://covers/second.jpg", songs = listOf(shared)),
                playlist(-5L, modifiedAt = 40L, customCoverUrl = " ", songs = listOf(song(3L)))
            ),
            context
        )

        assertEquals(FavoritesPlaylist.SYSTEM_ID, merged.id)
        assertEquals(FavoritesPlaylist.currentName(context), merged.name)
        assertEquals(listOf(1L, 2L, 3L), merged.songs.map(SongItem::id))
        assertEquals(50L, merged.modifiedAt)
        assertEquals("content://covers/second.jpg", merged.customCoverUrl)
        assertEquals(DISPLAY_ORDER_SONG_ORDER_VERSION, merged.songOrderVersion)
    }

    @Test
    fun `merging nothing produces an empty playlist stamped with the current time`() {
        val before = System.currentTimeMillis()

        val merged = FavoritesPlaylist.merge(emptyList(), context)

        assertTrue(merged.songs.isEmpty())
        assertTrue(merged.modifiedAt >= before)
        assertNull(merged.customCoverUrl)
    }

    @Test
    fun `matched favorites normalize both input orders while preserving the playable local copy`() {
        val remote = song(42L).copy(channelId = "netease", audioId = "42", coverUrl = "https://example.com/cover.jpg")
        val local = matchedLocalSong()

        listOf(listOf(remote, local), listOf(local, remote)).forEach { songs ->
            val merged = FavoritesPlaylist.merge(listOf(playlist(-3L, 30L, null, songs)), context)

            val retained = merged.songs.single()
            assertEquals(local.identity(), retained.identity())
            assertEquals(local.localFilePath, retained.localFilePath)
            assertEquals(remote.coverUrl, retained.coverUrl)
        }
    }

    @Test
    fun `matched favorite normalization retains all observed membership tokens`() {
        val firstToken = SyncCausalToken("remote", 1L)
        val secondToken = SyncCausalToken("local", 2L)
        val remote = song(42L).copy(channelId = "netease", audioId = "42", syncMembershipTokens = listOf(firstToken))
        val local = matchedLocalSong().copy(syncMembershipTokens = listOf(secondToken))
        val alias = local.copy(id = 100L, sourceStableKey = "42|netease|", syncMembershipTokens = listOf(firstToken))

        val merged = FavoritesPlaylist.merge(
            listOf(playlist(-3L, 30L, null, listOf(remote, local, alias))), context
        )

        assertEquals(setOf(firstToken, secondToken), merged.songs.single().syncMembershipTokens.orEmpty().toSet())
    }

    @Test
    fun `other provider and invalid matches remain separate during favorite normalization`() {
        val remote = song(42L).copy(channelId = "netease", audioId = "42")
        listOf(
            matchedLocalSong().copy(matchedLyricSource = MusicPlatform.QQ_MUSIC),
            matchedLocalSong().copy(matchedSongId = "0"),
            matchedLocalSong().copy(sourceStableKey = "77|bilibili|")
        ).forEach { local ->
            val merged = FavoritesPlaylist.merge(listOf(playlist(-3L, 30L, null, listOf(remote, local))), context)
            assertEquals(2, merged.songs.size)
        }
    }

    @Test
    fun `strictly identical downloaded favorites preserve whichever representation appeared first`() {
        val remote = remoteMatchedSong()
        val downloaded = remote.copy(
            mediaUri = "/music/download.mp3", localFilePath = "/music/download.mp3",
            sourceStableKey = "42|netease|"
        )
        assertEquals(remote.identity(), downloaded.identity())
        listOf(listOf(remote, downloaded), listOf(downloaded, remote)).forEach { songs ->
            assertEquals(songs.first(), mergeFavoriteSongs(songs).songs.single())
        }
    }

    @Test
    fun `matched favorites fill missing user metadata while keeping local metadata conflicts`() {
        val remote = remoteMatchedSong().copy(
            customName = "Remote custom name", customArtist = "Remote custom artist",
            originalName = "Remote original name", originalArtist = "Remote original artist"
        )
        listOf(matchedLocalSong(), matchedLocalSong().copy(
            customName = "Local custom name", customArtist = "Local custom artist",
            originalName = "Local original name", originalArtist = "Local original artist"
        )).forEach { local ->
            listOf(listOf(remote, local), listOf(local, remote)).forEach { songs ->
                val retained = mergeFavoriteSongs(songs).songs.single()
                assertEquals(local.identity(), retained.identity())
                assertEquals(local.customName ?: remote.customName, retained.customName)
                assertEquals(local.customArtist ?: remote.customArtist, retained.customArtist)
                assertEquals(local.originalName ?: remote.originalName, retained.originalName)
                assertEquals(local.originalArtist ?: remote.originalArtist, retained.originalArtist)
            }
        }
    }

    @Test
    fun `remote edited lyric state survives matched normalization and sync export as a complete revision`() {
        val remote = remoteMatchedSong().copy(
            matchedLyric = "Edited lyrics", matchedTranslatedLyric = "Edited translation",
            matchedRomanizedLyric = "Edited romanization", originalLyric = "Remote baseline",
            originalTranslatedLyric = "Remote translation", originalRomanizedLyric = "Remote romanization",
            lyricSyncEdited = true, lyricSyncRevision = 5L,
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42"
        )
        val local = matchedLocalSong().copy(lyricSyncEdited = false)
        listOf(listOf(remote, local), listOf(local, remote)).forEach { songs ->
            val playlist = mergeFavoriteSongs(songs)
            val retained = playlist.songs.single()
            assertEquals(local.identity(), retained.identity())
            assertEquals(local.localFilePath, retained.localFilePath)
            assertEquals(remote.matchedLyric, retained.matchedLyric)
            assertEquals(remote.matchedTranslatedLyric, retained.matchedTranslatedLyric)
            assertEquals(remote.matchedRomanizedLyric, retained.matchedRomanizedLyric)
            assertEquals(remote.originalLyric, retained.originalLyric)
            assertEquals(remote.originalTranslatedLyric, retained.originalTranslatedLyric)
            assertEquals(remote.originalRomanizedLyric, retained.originalRomanizedLyric)
            assertEquals(true, retained.lyricSyncEdited)
            assertEquals(5L, retained.lyricSyncRevision)
            val exported = SyncPlaylist.fromLocalPlaylist(playlist).songs.single()
            assertEquals(remote.matchedLyric, exported.matchedLyric)
            assertEquals(remote.matchedTranslatedLyric, exported.matchedTranslatedLyric)
            assertEquals(remote.matchedRomanizedLyric, exported.matchedRomanizedLyric)
            assertEquals(5L, exported.lyricSyncRevision)
            assertEquals(true, exported.lyricSyncEdited)
        }
    }

    @Test
    fun `newer local lyric revision survives an older edited remote alias`() {
        val remote = remoteMatchedSong().copy(matchedLyric = "Old remote edit", lyricSyncEdited = true, lyricSyncRevision = 5L)
        val local = matchedLocalSong().copy(matchedLyric = "New local edit", lyricSyncEdited = true, lyricSyncRevision = 7L)
        listOf(listOf(remote, local), listOf(local, remote)).forEach { songs ->
            val retained = mergeFavoriteSongs(songs).songs.single()
            assertEquals(local.identity(), retained.identity())
            assertEquals(local.matchedLyric, retained.matchedLyric)
            assertEquals(true, retained.lyricSyncEdited)
            assertEquals(7L, retained.lyricSyncRevision)
        }
    }

    @Test
    fun `remote reset at the same or newer revision clears edited aliases and keeps the original baseline`() {
        val local = matchedLocalSong().copy(
            matchedLyric = "Edited local lyrics", matchedTranslatedLyric = "Edited local translation",
            matchedRomanizedLyric = "Edited local romanization", originalLyric = "Local baseline",
            originalTranslatedLyric = "Local translation", originalRomanizedLyric = "Local romanization",
            lyricSyncEdited = true, lyricSyncRevision = 5L
        )
        listOf(5L, 6L).forEach { revision ->
            val remote = remoteMatchedSong().copy(lyricSyncEdited = false, lyricSyncRevision = revision)
            listOf(listOf(remote, local), listOf(local, remote)).forEach { songs ->
                val playlist = mergeFavoriteSongs(songs)
                val retained = playlist.songs.single()
                assertEquals(local.identity(), retained.identity())
                assertEquals(local.originalLyric, retained.matchedLyric)
                assertEquals(local.originalTranslatedLyric, retained.matchedTranslatedLyric)
                assertEquals(local.originalRomanizedLyric, retained.matchedRomanizedLyric)
                assertEquals(false, retained.lyricSyncEdited)
                assertEquals(revision, retained.lyricSyncRevision)
                val exported = SyncPlaylist.fromLocalPlaylist(playlist).songs.single()
                assertNull(exported.matchedLyric)
                assertNull(exported.matchedTranslatedLyric)
                assertNull(exported.matchedRomanizedLyric)
                assertEquals(false, exported.lyricSyncEdited)
                assertEquals(revision, exported.lyricSyncRevision)
            }
        }
    }

    @Test
    fun `older remote edits cannot revive a newer local lyric reset`() {
        val remote = remoteMatchedSong().copy(matchedLyric = "Old remote edit", lyricSyncEdited = true, lyricSyncRevision = 5L)
        val local = matchedLocalSong().copy(
            matchedLyric = "Local baseline", originalLyric = "Local baseline",
            lyricSyncEdited = false, lyricSyncRevision = 6L
        )
        listOf(listOf(remote, local), listOf(local, remote)).forEach { songs ->
            val playlist = mergeFavoriteSongs(songs)
            val retained = playlist.songs.single()
            assertEquals(local.identity(), retained.identity())
            assertEquals(local.originalLyric, retained.matchedLyric)
            assertEquals(false, retained.lyricSyncEdited)
            assertEquals(6L, retained.lyricSyncRevision)
            val exported = SyncPlaylist.fromLocalPlaylist(playlist).songs.single()
            assertNull(exported.matchedLyric)
            assertEquals(false, exported.lyricSyncEdited)
            assertEquals(6L, exported.lyricSyncRevision)
        }
    }

    @Test
    fun `remote lyric rematches cannot change the matched local favorite membership`() {
        val local = matchedLocalSong()
        listOf(MusicPlatform.QQ_MUSIC to "77", MusicPlatform.CLOUD_MUSIC to "43").forEach { (platform, id) ->
            val remote = remoteMatchedSong().copy(
                matchedLyric = "New remote lyrics", lyricSyncEdited = true, lyricSyncRevision = 5L,
                matchedLyricSource = platform, matchedSongId = id
            )
            listOf(listOf(remote, local), listOf(local, remote)).forEach { songs ->
                val playlist = mergeFavoriteSongs(songs)
                val retained = playlist.songs.single()
                assertEquals(local.identity(), retained.identity())
                assertEquals(local.matchedLyricSource, retained.matchedLyricSource)
                assertEquals("42", retained.matchedSongId)
                assertEquals("New remote lyrics", retained.matchedLyric)
                assertEquals(5L, retained.lyricSyncRevision)
                assertEquals(listOf(42L), SyncPlaylist.fromLocalPlaylist(playlist).songs.map { it.id })
            }
        }
    }

    private fun mergeFavoriteSongs(songs: List<SongItem>) =
        FavoritesPlaylist.merge(listOf(playlist(-3L, 30L, null, songs)), context)

    private fun remoteMatchedSong() = song(42L).copy(channelId = "netease", audioId = "42")

    private fun matchedLocalSong() = song(99L).copy(
        album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
        mediaUri = "/music/local.mp3",
        localFilePath = "/music/local.mp3",
        channelId = "local",
        matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
        matchedSongId = "42"
    )

    private fun playlist(id: Long, modifiedAt: Long, customCoverUrl: String?, songs: List<SongItem>) = LocalPlaylist(
        id = id,
        name = "我喜欢的音乐",
        songs = songs.toMutableList(),
        modifiedAt = modifiedAt,
        customCoverUrl = customCoverUrl
    )

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null
    )
}
