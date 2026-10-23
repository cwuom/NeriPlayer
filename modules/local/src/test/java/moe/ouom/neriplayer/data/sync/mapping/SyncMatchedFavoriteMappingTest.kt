package moe.ouom.neriplayer.data.sync.mapping

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepositoryTestSupport
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.merge.playlist.SyncPlaylistDeletionPolicy
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SyncMatchedFavoriteMappingTest : LocalPlaylistRepositoryTestSupport() {
    @Test
    fun `favorite snapshot exports the matched remote member without changing the local file identity`() {
        val local = localSong()
        val identityBefore = local.identity()

        val snapshot = SyncPlaylist.fromLocalPlaylist(playlist(local))

        assertEquals(1, snapshot.songs.size)
        val exported = snapshot.songs.single()
        assertEquals(42L, exported.id)
        assertEquals("netease", exported.album)
        assertEquals("netease", exported.channelId)
        assertEquals("42", exported.audioId)
        assertNull(exported.mediaUri)
        assertEquals(listOf(SyncCausalToken("phone", 2L)), exported.syncMembershipTokens)
        assertEquals(identityBefore, local.identity())
        assertEquals("/music/matched.mp3", local.localFilePath)
        assertNull(local.sourceStableKey)
        assertTrue(LocalSongSupport.isLocalSong(local))
    }

    @Test
    fun `ordinary playlist snapshots still exclude a local lyric match`() {
        val snapshot = SyncPlaylist.fromLocalPlaylist(playlist(localSong(), playlistId = 7L))

        assertTrue(snapshot.songs.isEmpty())
    }

    @Test
    fun `resolved legacy favorites use the same remote snapshot identity`() {
        val snapshot = mapResolvedLocalPlaylist(
            playlist = playlist(localSong(), playlistId = -7L),
            modifiedAt = 50L,
            systemDescriptor = SystemLocalPlaylists.Descriptor(FavoritesPlaylist.SYSTEM_ID, "Favorites"),
            context = null
        )

        assertEquals(FavoritesPlaylist.SYSTEM_ID, snapshot.id)
        assertEquals(listOf(42L), snapshot.songs.map { it.id })
    }

    @Test
    fun `favorite snapshot keeps an explicit remote source ahead of a different lyric match`() {
        val local = localSong().copy(sourceStableKey = "77|bilibili|")

        val snapshot = SyncPlaylist.fromLocalPlaylist(playlist(local))

        assertEquals(1, snapshot.songs.size)
        assertEquals(77L, snapshot.songs.single().id)
        assertEquals("bilibili", snapshot.songs.single().album)
        assertNull(snapshot.songs.single().mediaUri)
    }

    @Test
    fun `renewed matched favorite survives old causal and legacy remote deletions`() {
        val snapshot = SyncPlaylist.fromLocalPlaylist(playlist(localSong()))
        val oldCausal = SyncPlaylistSongDeletion(
            playlistId = FavoritesPlaylist.SYSTEM_ID,
            songId = 42L,
            album = "netease",
            deletedAt = 30L,
            deviceId = "phone",
            removedMembershipTokens = listOf(SyncCausalToken("phone", 1L))
        )
        val legacy = oldCausal.copy(removedMembershipTokens = emptyList())

        val surviving = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = FavoritesPlaylist.SYSTEM_ID,
            songs = snapshot.songs,
            deletions = listOf(oldCausal, legacy)
        )

        assertEquals(1, surviving.size)
        assertEquals(42L, surviving.single().id)
        assertEquals(listOf(SyncCausalToken("phone", 2L)), surviving.single().syncMembershipTokens)
        assertFalse(surviving.single().syncMembershipTokens.orEmpty().contains(SyncCausalToken("phone", 1L)))
    }

    @Test
    fun `favorite restore keeps the local file and accepts synchronized member edits and tokens`() {
        val local = localSong().copy(customName = "Old local title")
        val remote = SyncSong(
            id = 42L,
            name = "Remote title",
            artist = "Remote artist",
            album = "netease",
            channelId = "netease",
            audioId = "42",
            customName = "Synchronized title",
            matchedLyric = "Synchronized lyrics",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC.name,
            matchedSongId = "42",
            lyricSyncEdited = true,
            lyricSyncRevision = 2L,
            addedAt = 80L,
            syncMembershipTokens = listOf(SyncCausalToken("tablet", 3L))
        )

        val restored = restoreFavorites(listOf(remote), listOf(local))

        assertEquals(1, restored.songs.size)
        val member = restored.songs.single()
        assertEquals(local.identity(), member.identity())
        assertEquals(local.localFilePath, member.localFilePath)
        assertEquals(local.mediaUri, member.mediaUri)
        assertEquals(local.channelId, member.channelId)
        assertEquals(local.audioId, member.audioId)
        assertNull(member.sourceStableKey)
        assertEquals("Synchronized title", member.customName)
        assertEquals("Synchronized lyrics", member.matchedLyric)
        assertEquals(80L, member.addedAt)
        assertEquals(listOf(SyncCausalToken("tablet", 3L)), member.syncMembershipTokens)
        val exported = SyncPlaylist.fromLocalPlaylist(restored)
        assertEquals(listOf(42L), exported.songs.map { it.id })
        assertEquals(listOf(SyncCausalToken("tablet", 3L)), exported.songs.single().syncMembershipTokens)
    }

    @Test
    fun `favorite restore removes a matched local member deleted by synchronization`() {
        val restored = restoreFavorites(emptyList(), listOf(localSong()))

        assertTrue(restored.songs.isEmpty())
    }

    @Test
    fun `native remote favorites do not erase the local matching association during restore`() {
        val remote = SyncSong(
            id = 42L,
            album = "netease",
            channelId = "netease",
            audioId = "42",
            syncMembershipTokens = listOf(SyncCausalToken("tablet", 3L))
        )

        val restored = restoreFavorites(listOf(remote), listOf(localSong()))

        assertEquals(1, restored.songs.size)
        assertEquals(MusicPlatform.CLOUD_MUSIC, restored.songs.single().matchedLyricSource)
        assertEquals("42", restored.songs.single().matchedSongId)
        assertEquals(listOf(42L), SyncPlaylist.fromLocalPlaylist(restored).songs.map { it.id })
    }

    @Test
    fun `favorite restore retains local songs without a usable netease match`() {
        val localOnly = listOf(
            localSong().copy(
                id = 100L, matchedSongId = null,
                mediaUri = "/music/unmatched.mp3", localFilePath = "/music/unmatched.mp3"
            ),
            localSong().copy(
                id = 101L, matchedSongId = "invalid",
                mediaUri = "/music/invalid.mp3", localFilePath = "/music/invalid.mp3"
            ),
            localSong().copy(
                id = 102L, matchedLyricSource = MusicPlatform.QQ_MUSIC,
                mediaUri = "/music/qq.mp3", localFilePath = "/music/qq.mp3"
            )
        )

        val restored = restoreFavorites(emptyList(), localOnly)

        assertEquals(localOnly.map { it.identity() }, restored.songs.map { it.identity() })
    }

    @Test
    fun `ordinary playlist restore still retains a local match beside its remote song`() {
        val local = localSong()
        val remote = SyncSong(id = 42L, album = "netease", channelId = "netease", audioId = "42")

        val restored = restorePlaylist(listOf(remote), listOf(local), playlistId = 7L)

        assertEquals(2, restored.songs.size)
        assertEquals(local.identity(), restored.songs.last().identity())
    }

    @Test
    fun `raw duplicate favorites retain every legacy candidate and the newest existing lyric revision`() {
        val remote = SongItem(
            id = 42L, name = "Remote favorite", artist = "Artist", album = "netease",
            albumId = 0L, durationMs = 180_000L, coverUrl = null,
            channelId = "netease", audioId = "42", matchedLyric = "Legacy remote first"
        )
        val duplicate = remote.copy(matchedLyric = "Legacy remote second")
        val latest = remote.copy(
            matchedLyric = "Newest existing edit", lyricSyncEdited = true, lyricSyncRevision = 7L
        )
        val local = localSong().copy(matchedLyric = "Legacy local baseline")
        val incoming = SyncSong.fromSongItem(latest.copy(
            matchedLyric = "Older incoming edit", lyricSyncRevision = 6L
        ))
        var retained = emptyList<SyncSong>()

        val restored = restorePlaylist(
            remote = listOf(incoming),
            current = listOf(remote, duplicate, latest, local),
            playlistId = FavoritesPlaylist.SYSTEM_ID,
            retainLegacyLyrics = { retained = it }
        )

        assertEquals(1, restored.songs.size)
        assertEquals(local.identity(), restored.songs.single().identity())
        assertEquals("Newest existing edit", restored.songs.single().matchedLyric)
        assertEquals(7L, restored.songs.single().lyricSyncRevision)
        assertEquals(true, restored.songs.single().lyricSyncEdited)
        assertEquals(
            setOf("Legacy remote first", "Legacy remote second", "Legacy local baseline"),
            retained.map { it.matchedLyric }.toSet()
        )
        assertEquals(3, retained.size)
        assertTrue(retained.all { it.lyricSyncEdited == null && it.lyricSyncRevision == 0L })
        assertEquals(listOf(42L), SyncPlaylist.fromLocalPlaylist(restored).songs.map { it.id })
    }

    private fun restoreFavorites(remote: List<SyncSong>, current: List<SongItem>) =
        restorePlaylist(remote, current, FavoritesPlaylist.SYSTEM_ID)

    private fun restorePlaylist(
        remote: List<SyncSong>,
        current: List<SongItem>,
        playlistId: Long,
        retainLegacyLyrics: (List<SyncSong>) -> Unit = {}
    ): LocalPlaylist {
        val originalLocale = Locale.getDefault()
        try {
            val context = syncMappingContext()
            val data = SyncData(playlists = listOf(SyncPlaylist(id = playlistId, name = "Favorites", songs = remote)))
            val existing = LocalPlaylist(id = playlistId, name = "Favorites", songs = current.toMutableList())
            return SyncLocalRestoreMapping(context, retainLegacyLyrics).playlists(data, listOf(existing)).single()
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    private fun syncMappingContext(): Context {
        val preferences = mock(SharedPreferences::class.java)
        val resources = mock(Resources::class.java)
        val configuration = mock(Configuration::class.java)
        val locales = mock(LocaleList::class.java)
        `when`(preferences.getString("selected_language", "")).thenReturn("")
        `when`(resources.configuration).thenReturn(configuration)
        `when`(configuration.locales).thenReturn(locales)
        `when`(locales.size()).thenReturn(1)
        `when`(locales[0]).thenReturn(Locale.getDefault())
        return mockContext().apply {
            `when`(applicationContext).thenReturn(mock(Context::class.java))
            `when`(getSharedPreferences("language_settings", Context.MODE_PRIVATE)).thenReturn(preferences)
            `when`(this.resources).thenReturn(resources)
            `when`(createConfigurationContext(any(Configuration::class.java))).thenReturn(this)
        }
    }

    private fun playlist(song: SongItem, playlistId: Long = FavoritesPlaylist.SYSTEM_ID) = LocalPlaylist(
        id = playlistId,
        name = if (playlistId < 0L) "Favorites" else "Mix",
        songs = mutableListOf(song),
        modifiedAt = 50L
    )

    private fun localSong() = SongItem(
        id = 99L,
        name = "Matched local song",
        artist = "Local artist",
        album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        mediaUri = "/music/matched.mp3",
        localFileName = "matched.mp3",
        localFilePath = "/music/matched.mp3",
        channelId = "local",
        audioId = "99",
        matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
        matchedSongId = "42",
        addedAt = 40L,
        syncMembershipTokens = listOf(SyncCausalToken("phone", 2L))
    )
}
