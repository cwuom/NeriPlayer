package moe.ouom.neriplayer.ui.viewmodel.nowplaying

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseLikeSyncResult
import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseRemotePlaylist
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.ui.i18n.UiText
import org.junit.Assert.*
import org.junit.Test
import moe.ouom.neriplayer.common.R as CoreCommonR

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingNeteasePlaylistOwnerTest {
    private val song = SongItem(123L, "Song", "Artist", "Album", 1L, 120_000L, null, channelId = "netease")
    private val audio = PlaybackAudioInfo(PlaybackAudioSource.NETEASE)
    private val playlist = NeteaseRemotePlaylist(42L, "My playlist", 2)

    @Test
    fun `availability follows actual audio source not lyrics artwork or original tag`() {
        for (source in PlaybackAudioSource.entries) {
            assertEquals(source == PlaybackAudioSource.NETEASE,
                canCollectToNeteasePlaylist(song, audio.copy(source = source)))
        }
        assertFalse(canCollectToNeteasePlaylist(song, null))
        assertFalse(canCollectToNeteasePlaylist(null, audio))
        assertFalse(canCollectToNeteasePlaylist(song, audio.copy(isNeteaseLocalFallback = true)))
        assertFalse(canCollectToNeteasePlaylist(song.copy(localFilePath = "/music/song.flac"), audio))
        assertFalse(canCollectToNeteasePlaylist(song.copy(mediaUri = "content://media/1"), audio))
        val matched = song.copy(channelId = "bilibili", matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "123", coverUrl = "https://p1.music.126.net/cover.jpg")
        assertFalse(canCollectToNeteasePlaylist(matched, audio.copy(source = PlaybackAudioSource.BILIBILI)))
    }

    @Test
    fun `login and offline guards prevent requests both before loading and before submitting`() = runTest {
        var loggedIn = false
        var loads = 0
        var adds = 0
        val messages = mutableListOf<UiText>()
        val owner = NowPlayingNeteasePlaylistOwner(this, { loggedIn },
            { loads++; listOf(playlist) }, { _, _ -> adds++; result(added = 1) }, messages::add)
        owner.open(song, audio, offline = false)
        assertEquals(UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_login_required), messages.last())
        loggedIn = true
        owner.open(song, audio, offline = true)
        assertEquals(UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_offline), messages.last())
        runCurrent()
        assertEquals(0, loads)
        owner.open(song, audio, offline = false)
        runCurrent()
        owner.select(playlist, song, audio, offline = true)
        assertEquals(UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_offline), owner.state.value.error)
        loggedIn = false
        owner.select(playlist, song, audio, offline = false)
        runCurrent()
        assertEquals(UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_login_required), owner.state.value.error)
        assertEquals(1, loads)
        assertEquals(0, adds)
    }

    @Test
    fun `loading failure and empty playlists are recoverable without submitting`() = runTest {
        var fail = true
        val owner = NowPlayingNeteasePlaylistOwner(this, { true },
            { if (fail) error("network") else emptyList() }, { _, _ -> error("must not add") }, {})
        owner.open(song, audio, false)
        runCurrent()
        assertFalse(owner.state.value.loading)
        assertEquals(UiText.Resource(CoreCommonR.string.local_playlist_sync_netease_load_failed), owner.state.value.error)
        owner.dismiss()
        fail = false
        owner.open(song, audio, false)
        runCurrent()
        assertEquals(UiText.Resource(CoreCommonR.string.local_playlist_sync_netease_no_playlists), owner.state.value.error)
        owner.select(playlist, song, audio, false)
        assertFalse(owner.state.value.submitting)
    }

    @Test
    fun `dismissed late load cannot overwrite a reopened picker`() = runTest {
        val late = CompletableDeferred<List<NeteaseRemotePlaylist>>()
        var loads = 0
        val owner = NowPlayingNeteasePlaylistOwner(this, { true }, {
            if (++loads == 1) withContext(NonCancellable) { late.await() } else listOf(playlist)
        }, { _, _ -> error("must not add") }, {})
        owner.open(song, audio, false)
        runCurrent()
        owner.dismiss()
        val next = song.copy(id = 124L)
        owner.open(next, audio, false)
        runCurrent()
        late.complete(listOf(playlist.copy(id = 99L)))
        runCurrent()
        assertEquals(next, owner.state.value.song)
        assertEquals(listOf(playlist), owner.state.value.playlists)
    }

    @Test
    fun `track and source changes invalidate unsubmitted selection`() = runTest {
        val owner = NowPlayingNeteasePlaylistOwner(this, { true }, { listOf(playlist) },
            { _, _ -> error("must not add") }, {})
        owner.open(song, audio, false)
        runCurrent()
        owner.select(playlist, song.copy(id = 124L), audio, false)
        assertNull(owner.state.value.song)
        owner.open(song, audio, false)
        runCurrent()
        owner.updatePlayback(song, audio.copy(source = PlaybackAudioSource.BILIBILI))
        assertNull(owner.state.value.song)
        owner.open(song, audio, false)
        runCurrent()
        owner.updatePlayback(song, audio.copy(isNeteaseLocalFallback = true))
        assertNull(owner.state.value.song)
    }

    @Test
    fun `submission uses original snapshot once even after playback changes or sheet dismissal`() = runTest {
        val completion = CompletableDeferred<NeteaseLikeSyncResult>()
        val submissions = mutableListOf<Pair<Long, SongItem>>()
        val messages = mutableListOf<UiText>()
        val owner = NowPlayingNeteasePlaylistOwner(this, { true }, { listOf(playlist) }, { id, track ->
            submissions += id to track
            completion.await()
        }, messages::add)
        owner.open(song, audio, false)
        runCurrent()
        owner.select(playlist, song, audio, false)
        owner.select(playlist, song, audio, false)
        owner.updatePlayback(song.copy(id = 124L), audio)
        owner.dismiss()
        owner.open(song.copy(id = 124L), audio, false)
        runCurrent()
        assertTrue(owner.state.value.submitting)
        assertNull(owner.state.value.song)
        assertEquals(listOf(playlist.id to song), submissions)
        completion.complete(result(added = 1))
        runCurrent()
        assertFalse(owner.state.value.submitting)
        assertEquals(UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_added, listOf(playlist.name)), messages.last())
    }

    @Test
    fun `restored tracks submit the playback id despite conflicting lyrics and audio metadata`() = runTest {
        val restored = song.copy(channelId = null, audioId = "456", matchedSongId = "789",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC)
        val submissions = mutableListOf<SongItem>()
        val owner = NowPlayingNeteasePlaylistOwner(this, { true }, { listOf(playlist) },
            { _, track -> submissions += track; result(added = 1) }, {})
        owner.open(restored, audio, false)
        runCurrent()
        assertTrue(owner.select(playlist, restored, audio, false))
        runCurrent()
        val submitted = submissions.single()
        assertEquals(restored.id, submitted.id)
        assertEquals("netease", submitted.channelId)
        assertNull(submitted.audioId)
    }

    @Test
    fun `submission failure allows retry and cancellation is not reported as failure`() = runTest {
        var cancel = false
        val messages = mutableListOf<UiText>()
        val owner = NowPlayingNeteasePlaylistOwner(this, { true }, { listOf(playlist) }, { _, _ ->
            if (cancel) throw CancellationException() else error("network")
        }, messages::add)
        owner.open(song, audio, false)
        runCurrent()
        owner.select(playlist, song, audio, false)
        runCurrent()
        assertFalse(owner.state.value.submitting)
        assertEquals(UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_failed, listOf(playlist.name)), messages.last())
        cancel = true
        messages.clear()
        owner.open(song, audio, false)
        runCurrent()
        owner.select(playlist, song, audio, false)
        runCurrent()
        assertFalse(owner.state.value.submitting)
        assertEquals(listOf(UiText.Resource(CoreCommonR.string.nowplaying_netease_playlist_adding, listOf(playlist.name))), messages)
    }

    @Test
    fun `result distinguishes success existing rejected and unverified outcomes`() {
        val outcomes = listOf(
            result(added = 1) to CoreCommonR.string.nowplaying_netease_playlist_added,
            result(existing = 1) to CoreCommonR.string.nowplaying_netease_playlist_existing,
            result(failed = 1) to CoreCommonR.string.nowplaying_netease_playlist_failed,
            result().copy(skippedUnsupported = 1) to CoreCommonR.string.nowplaying_netease_playlist_failed,
            result() to CoreCommonR.string.nowplaying_netease_playlist_failed
        )
        outcomes.forEach { (result, resource) ->
            assertEquals(UiText.Resource(resource, listOf(playlist.name)), neteasePlaylistCollectionResultText(result, playlist.name))
        }
    }

    private fun result(added: Int = 0, existing: Int = 0, failed: Int = 0) = NeteaseLikeSyncResult(
        totalSongs = 1, supportedSongs = 1, skippedUnsupported = 0,
        skippedExisting = existing, added = added, failed = failed, targetPlaylistId = playlist.id
    )
}
