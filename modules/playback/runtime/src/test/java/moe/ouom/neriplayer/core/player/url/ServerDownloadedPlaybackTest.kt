package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshSideEffectGate
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.storage.PlayerLocalPlaybackResolution
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ServerDownloadedPlaybackTest {
    @After
    fun resetHosts() = PlayerTestEnvironment.reset()

    @Test
    fun `server playlist item uses indexed download after its profile is deleted`() = runBlocking {
        val ref = ServerSongRef("00000000-0000-0000-0000-000000000001", "same-id")
        val song = SongItem(ref.numericId, "Song", "Artist", "Album", 0L, 1000L, null,
            channelId = ServerSongRef.CHANNEL, audioId = ref.audioId, mediaUri = ref.mediaUri)
        val downloads = mock(PlayerDownloadAccess::class.java)
        `when`(downloads.resolveIndexedLocalPlaybackReference(PlayerTestEnvironment.application, song))
            .thenReturn(PlayerLocalPlaybackResolution.Playable("file:///music/server.flac"))
        PlayerTestEnvironment.install(downloads = downloads,
            repositories = mock(PlayerRepositoryDependencies::class.java))
        PlayerManager.bindApplication(PlayerTestEnvironment.application)

        val result = PlayerManager.resolveSongUrl(song,
            sideEffects = RefreshResolverSideEffects(RefreshSideEffectGate { false }))

        assertTrue("download must be available without its former profile", result is SongUrlResult.Success)
        assertEquals("file:///music/server.flac", (result as SongUrlResult.Success).url)
    }
}
