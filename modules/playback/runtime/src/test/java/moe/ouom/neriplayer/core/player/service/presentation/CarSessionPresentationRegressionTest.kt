package moe.ouom.neriplayer.core.player.service.presentation

import android.media.AudioDeviceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkOwner
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.core.player.service.car.library.CarLibrarySnapshot
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaIds
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaLibrary
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import moe.ouom.neriplayer.data.model.settings.lyrics.BluetoothMetadataMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.withSettings

@OptIn(ExperimentalCoroutinesApi::class)
class CarSessionPresentationRegressionTest {
    @Test
    fun `initial settings emission publishes only after the session is initialized`() = runTest {
        MetadataBuilders().use { builders ->
            val source = FakeSource(playback(listOf(song(1)), 0)).apply {
                metadataInputs = bluetoothLyrics().copy(album = "Display album")
            }
            val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
            val fixture = fixture(source, scope)
            val callback = mock(MediaSession.Callback::class.java)

            verifyNoInteractions(fixture.port)
            assertEquals(0, builders.count)
            fixture.owner.initializeSession(callback)

            val order = inOrder(fixture.port)
            order.verify(fixture.port).initializeSession(callback)
            order.verify(fixture.port).setMetadata(builders.results.single())
            assertEquals("Song 1", builders.string(0, MediaMetadata.METADATA_KEY_TITLE))
            assertEquals("Artist", builders.string(0, MediaMetadata.METADATA_KEY_ARTIST))
            assertEquals("Display album", builders.string(0, MediaMetadata.METADATA_KEY_ALBUM))
            fixture.owner.updateMetadata()
            assertEquals(1, builders.count)
            verify(fixture.port, times(1)).setMetadata(builders.results.single())
        }
    }

    @Test
    fun `changing bluetooth modes publishes their real metadata and unchanged updates are deduplicated`() = runTest {
        MetadataBuilders().use { builders ->
            val source = FakeSource(playback(listOf(song(1)), 0)).apply {
                metadataInputs = bluetoothLyrics()
            }
            val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
            val fixture = fixture(source, scope)
            fixture.owner.initializeSession(mock(MediaSession.Callback::class.java))
            assertEquals("Song 1", builders.string(0, MediaMetadata.METADATA_KEY_TITLE))

            source.modes.value = BluetoothMetadataMode.SongAndLyrics
            assertEquals("Song 1 | line | translation", builders.string(1, MediaMetadata.METADATA_KEY_TITLE))
            assertEquals("Song 1", builders.string(1, MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            assertEquals("Artist", builders.string(1, MediaMetadata.METADATA_KEY_ARTIST))
            assertEquals("Album", builders.string(1, MediaMetadata.METADATA_KEY_ALBUM))

            source.modes.value = BluetoothMetadataMode.Lyrics
            assertEquals("line", builders.string(2, MediaMetadata.METADATA_KEY_TITLE))
            assertEquals("translation", builders.string(2, MediaMetadata.METADATA_KEY_ARTIST))
            source.modes.value = BluetoothMetadataMode.Lyrics
            repeat(3) { fixture.owner.updateMetadata() }
            assertEquals(3, builders.count)
            assertEquals(3, calls(fixture.port, "setMetadata"))

            source.modes.value = BluetoothMetadataMode.SongInfo
            assertEquals("Song 1", builders.string(3, MediaMetadata.METADATA_KEY_TITLE))
            assertEquals("Artist", builders.string(3, MediaMetadata.METADATA_KEY_ARTIST))
            assertEquals(4, calls(fixture.port, "setMetadata"))
        }
    }

    @Test
    fun `queue reorder track switch and queue size changes refresh resolvable current media IDs`() = runTest {
        MetadataBuilders().use { builders ->
            val first = song(1)
            val second = song(2)
            val source = FakeSource(playback(listOf(first, second), 0))
            val fixture = fixture(source, backgroundScope)
            fixture.owner.updateMetadata()

            val initialId = requireNotNull(builders.string(0, MediaMetadata.METADATA_KEY_MEDIA_ID))
            assertEquals(CarMediaLibrary(CarLibrarySnapshot(queue = source.currentPlayback.queue))
                .children(CarMediaIds.QUEUE).first().mediaId, initialId)
            assertEquals(1L, builders.long(0, MediaMetadata.METADATA_KEY_TRACK_NUMBER))
            assertEquals(2L, builders.long(0, MediaMetadata.METADATA_KEY_NUM_TRACKS))

            source.currentPlayback = playback(listOf(second, first), 1)
            fixture.owner.updateMetadata()
            val movedId = requireNotNull(builders.string(1, MediaMetadata.METADATA_KEY_MEDIA_ID))
            assertNotEquals(initialId, movedId)
            assertEquals("Song 1", builders.string(1, MediaMetadata.METADATA_KEY_TITLE))
            val reordered = CarMediaLibrary(CarLibrarySnapshot(queue = source.currentPlayback.queue))
            assertEquals(first, reordered.getItem(movedId)?.song)
            assertEquals(1, reordered.resolveId(movedId)?.startIndex)
            assertEquals(2L, builders.long(1, MediaMetadata.METADATA_KEY_TRACK_NUMBER))

            source.currentPlayback = playback(listOf(second, first), 0)
            fixture.owner.updateMetadata()
            val secondId = requireNotNull(builders.string(2, MediaMetadata.METADATA_KEY_MEDIA_ID))
            assertNotEquals(movedId, secondId)
            assertEquals("Song 2", builders.string(2, MediaMetadata.METADATA_KEY_TITLE))

            source.currentPlayback = playback(listOf(second, first, song(3)), 0)
            fixture.owner.updateMetadata()
            assertEquals(secondId, builders.string(3, MediaMetadata.METADATA_KEY_MEDIA_ID))
            assertEquals(3L, builders.long(3, MediaMetadata.METADATA_KEY_NUM_TRACKS))
            fixture.owner.updateMetadata()
            assertEquals(4, calls(fixture.port, "setMetadata"))
        }
    }

    @Test
    fun `clearing the current song removes its metadata ID artwork and track count`() = runTest {
        MetadataBuilders().use { builders ->
            val source = FakeSource(playback(listOf(song(1)), 0))
            val fixture = fixture(source, backgroundScope)
            fixture.owner.updateMetadata()
            source.currentPlayback = playback(emptyList(), -1)
            fixture.owner.updateMetadata()

            assertEquals("NeriPlayer", builders.string(1, MediaMetadata.METADATA_KEY_TITLE))
            assertEquals("", builders.string(1, MediaMetadata.METADATA_KEY_ARTIST))
            assertNull(builders.string(1, MediaMetadata.METADATA_KEY_MEDIA_ID))
            assertEquals(0L, builders.long(1, MediaMetadata.METADATA_KEY_TRACK_NUMBER))
            assertEquals(0L, builders.long(1, MediaMetadata.METADATA_KEY_NUM_TRACKS))
            assertTrue(mockingDetails(builders.builder(1)).invocations.none {
                it.method.name == "putString" && it.arguments[0] == MediaMetadata.METADATA_KEY_ART_URI
            })
            fixture.owner.updateMetadata()
            assertEquals(2, calls(fixture.port, "setMetadata"))
        }
    }

    @Test
    fun `a different queue occurrence publishes even when all playback values are unchanged`() = runTest {
        StateBuilders().use { builders ->
            val repeated = song(1)
            val source = FakeSource(playback(listOf(repeated, repeated), 0))
            val fixture = fixture(source, backgroundScope)
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = false)
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = false)
            assertEquals(1, calls(fixture.port, "setPlaybackState"))

            source.currentPlayback = source.currentPlayback.copy(queueIndex = 1)
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = false)
            assertEquals(builders.state(0), builders.state(1))
            assertNotEquals(builders.activeQueueId(0), builders.activeQueueId(1))
            assertTrue(builders.activeQueueId(0) >= 0L)
            assertTrue(builders.activeQueueId(1) >= 0L)
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = false)
            assertEquals(2, calls(fixture.port, "setPlaybackState"))

            source.currentPlayback = playback(emptyList(), -1)
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = false)
            assertEquals(-1L, builders.activeQueueId(2))
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = false)
            assertEquals(3, calls(fixture.port, "setPlaybackState"))
        }
    }

    @Test
    fun `an unchanged queue still throttles normal progress and dispatches a real seek`() = runTest {
        StateBuilders().use { builders ->
            val source = FakeSource(playback(listOf(song(1)), 0).copy(enginePlaying = true, transportActive = true))
            val fixture = fixture(source, backgroundScope)
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = false)

            `when`(fixture.port.elapsedRealtime()).thenReturn(100L)
            source.currentPlayback = source.currentPlayback.copy(playerPositionMs = 2_125L)
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = false)
            assertEquals(1, calls(fixture.port, "setPlaybackState"))

            source.currentPlayback = source.currentPlayback.copy(playerPositionMs = 8_000L)
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = false)
            assertEquals(2, calls(fixture.port, "setPlaybackState"))
            assertEquals(listOf(PlaybackState.STATE_PLAYING, 8_000L, 1.25f), builders.state(1))
            assertEquals(builders.activeQueueId(0), builders.activeQueueId(1))

            source.currentPlayback = source.currentPlayback.copy(playerPositionMs = 8_125L)
            fixture.owner.updatePlaybackState(force = true, floatingLyricsEnabled = false)
            assertEquals(3, calls(fixture.port, "setPlaybackState"))
            assertEquals(listOf(PlaybackState.STATE_PLAYING, 8_125L, 1.25f), builders.state(2))
        }
    }

    @Test
    fun `favorite and floating lyric controls still refresh an otherwise unchanged playback state`() = runTest {
        StateBuilders().use { builders ->
            val current = song(1)
            val source = FakeSource(playback(listOf(current), 0))
            val fixture = fixture(source, backgroundScope)
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = false)
            source.favorites = setOf(current.stableKey())
            fixture.owner.refreshFavoriteSongKeys()
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = false)
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = true)

            assertEquals(3, calls(fixture.port, "setPlaybackState"))
            assertEquals(listOf(builders.activeQueueId(0), builders.activeQueueId(0), builders.activeQueueId(0)),
                (0..2).map(builders::activeQueueId))
            fixture.owner.updatePlaybackState(force = false, floatingLyricsEnabled = true)
            assertEquals(3, calls(fixture.port, "setPlaybackState"))
        }
    }

    private data class Fixture(val owner: PlaybackServicePresentationOwner, val port: PlaybackServicePresentationPort)

    private fun fixture(source: FakeSource, scope: CoroutineScope): Fixture {
        val port = mock(PlaybackServicePresentationPort::class.java)
        val artwork = mock(PlaybackArtworkOwner::class.java)
        doReturn(PlaybackArtworkSnapshot(null, null, null, false, false, false))
            .`when`(artwork).observe(nullable(SongItem::class.java))
        return Fixture(PlaybackServicePresentationOwner(source, port, artwork, scope), port)
    }

    private fun playback(queue: List<SongItem>, index: Int) = PlaybackServicePlaybackSnapshot(
        song = queue.getOrNull(index),
        playerSongPresent = index in queue.indices,
        playerPositionMs = 2_000L,
        roomPositionMs = 2_000L,
        buffering = false,
        transportActive = false,
        enginePlaying = false,
        roomPlaying = false,
        playbackControlPlaying = false,
        audioRouteMuted = false,
        playbackSpeed = 1.25f,
        queue = queue,
        queueIndex = index,
    )

    private fun song(id: Long) = SongItem(id, "Song $id", "Artist", "Album", 1, 10_000, null)

    private fun bluetoothLyrics() = PlaybackServiceMetadataInputs(
        ExternalBluetoothLyricPayload("line", "translation"), AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false
    )

    private fun calls(port: PlaybackServicePresentationPort, method: String): Int =
        mockingDetails(port).invocations.count { it.method.name == method }

    private class FakeSource(var currentPlayback: PlaybackServicePlaybackSnapshot) : PlaybackServicePresentationSource {
        val modes = MutableStateFlow(BluetoothMetadataMode.SongInfo)
        var metadataInputs = PlaybackServiceMetadataInputs(ExternalBluetoothLyricPayload(), null, false)
        var favorites: Set<String> = emptySet()

        override fun playback() = currentPlayback
        override fun metadata() = metadataInputs
        override fun bluetoothMetadataModes(): Flow<BluetoothMetadataMode> = modes
        override fun timer() = PlaybackServiceTimerInputs(SleepTimerState(), "")
        override fun favoriteSongKeys() = favorites
        override fun localPlaylistsReady() = true
        override fun isLocalSong(song: SongItem) = false
        override fun shareUrl(song: SongItem): String? = null
        override suspend fun setFloatingLyricsEnabled(enabled: Boolean) = Unit
    }

    private class MetadataBuilders : AutoCloseable {
        val results = mutableListOf<MediaMetadata>()
        private val construction = mockConstruction(MediaMetadata.Builder::class.java,
            withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
            val metadata = mock(MediaMetadata::class.java)
            results += metadata
            `when`(builder.build()).thenReturn(metadata)
        }
        val count: Int get() = construction.constructed().size

        fun builder(index: Int): MediaMetadata.Builder = construction.constructed()[index]

        fun string(index: Int, key: String): String? = arguments(index, "putString", key)[1] as String?

        fun long(index: Int, key: String): Long = arguments(index, "putLong", key)[1] as Long

        private fun arguments(index: Int, method: String, key: String): List<Any?> =
            mockingDetails(builder(index)).invocations.single {
                it.method.name == method && it.arguments[0] == key
            }.arguments.toList()

        override fun close() = construction.close()
    }

    private class StateBuilders : AutoCloseable {
        private val construction = mockConstruction(PlaybackState.Builder::class.java,
            withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
            `when`(builder.build()).thenReturn(mock(PlaybackState::class.java))
        }
        private val customActions = mockConstruction(PlaybackState.CustomAction.Builder::class.java,
            withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
            `when`(builder.build()).thenReturn(mock(PlaybackState.CustomAction::class.java))
        }

        fun activeQueueId(index: Int): Long = arguments(index, "setActiveQueueItemId")[0] as Long

        fun state(index: Int): List<Any?> = arguments(index, "setState")

        private fun arguments(index: Int, method: String): List<Any?> =
            mockingDetails(construction.constructed()[index]).invocations.single {
                it.method.name == method
            }.arguments.toList()

        override fun close() {
            customActions.close()
            construction.close()
        }
    }
}
