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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset
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
            assertEquals("Song 1 | line | translation", builders.string(1, MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
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

    /**
     * 队列当前项的 title/artist 必须和 metadata 一致,
     * 否则 AOSP 蓝牙栈的 isMetadataSynced() 不通过, 歌词更新会被拖进 2 秒超时。
     */
    @Test
    fun `bluetooth lyric metadata keeps the session queue current item in sync`() = runTest {
        MetadataBuilders().use { builders ->
            val source = FakeSource(playback(listOf(song(1)), 0)).apply {
                metadataInputs = bluetoothLyrics()
            }
            val fixture = fixture(source, backgroundScope)
            fixture.owner.updateMetadata()

            val queueCalls = queueOverrides(fixture.port)
            assertEquals(1, queueCalls.size)
            val (title, subtitle) = queueCalls.first()
            assertEquals(builders.string(0, MediaMetadata.METADATA_KEY_TITLE), title)
            assertEquals(builders.string(0, MediaMetadata.METADATA_KEY_ARTIST), subtitle)

            // 歌词变了, 队列当前项要跟着变
            source.metadataInputs = bluetoothLyrics().copy(
                payload = ExternalBluetoothLyricPayload("next line", null),
            )
            fixture.owner.updateMetadata()

            val updated = queueOverrides(fixture.port)
            assertEquals(2, updated.size)
            assertEquals(builders.string(1, MediaMetadata.METADATA_KEY_TITLE), updated.last().first)
            assertEquals(builders.string(1, MediaMetadata.METADATA_KEY_ARTIST), updated.last().second)
            assertTrue(updated.last().first != title)
        }
    }

    /** 非蓝牙输出时不应改写队列当前项的标题 */
    @Test
    fun `non bluetooth output leaves the session queue current item untouched`() = runTest {
        MetadataBuilders().use {
            val source = FakeSource(playback(listOf(song(1)), 0))
            val fixture = fixture(source, backgroundScope)
            fixture.owner.updateMetadata()
            assertEquals(0, queueOverrides(fixture.port).size)
        }
    }

    /**
     * 从蓝牙切回扬声器时, 会话队列当前项要恢复成歌名/歌手。
     * 否则控制器会一直看到上一行歌词, 与随后发布的普通 metadata 对不上。
     */
    @Test
    fun `leaving bluetooth restores the session queue current item to the song title`() = runTest {
        MetadataBuilders().use { builders ->
            val current = song(1)
            val source = FakeSource(playback(listOf(current), 0)).apply {
                metadataInputs = bluetoothLyrics()
            }
            val fixture = fixture(source, backgroundScope)
            fixture.owner.updateMetadata()

            // 切回扬声器: 路由不再是蓝牙
            source.metadataInputs = source.metadataInputs.copy(audioDeviceType = null)
            fixture.owner.updateMetadata()

            val overrides = queueOverrides(fixture.port)
            assertTrue("切回扬声器后应重新下发队列", overrides.size >= 2)
            // 最后一次发布不再带覆盖值, 队列当前项回落到歌名/歌手
            assertNull(overrides.last().first)
            assertNull(overrides.last().second)
        }
    }

    /**
     * 队列发布失败时, 期望的覆盖值要保留下来。
     * 下一次队列变化会用这份最新的期望覆盖值重试, 否则会拿旧值重新下发, 队列又和 metadata 对不上。
     */
    @Test
    fun `a failed queue publish keeps the latest override for the next attempt`() = runTest {
        MetadataBuilders().use {
            val source = FakeSource(playback(listOf(song(1)), 0)).apply {
                metadataInputs = bluetoothLyrics()
            }
            val fixture = fixture(source, backgroundScope)
            val port = fixture.port
            // 第一次发布直接失败
            doThrow(IllegalStateException("queue rejected"))
                .`when`(port)
                .setQueue(anyList(), anyInt(), nullable(String::class.java), nullable(String::class.java))

            fixture.owner.updateMetadata()
            val failedAttempt = queueOverrides(port).lastOrNull()
            assertNotNull("失败时应该尝试过发布队列", failedAttempt)
            val attempt = failedAttempt!!
            assertNotNull("失败前也应带上期望覆盖值", attempt.first)

            // 之后队列发布恢复正常, 重试时不带显式覆盖值
            reset(port)
            fixture.owner.publishCarQueue(
                songs = listOf(song(1)),
                selectedIndex = 0,
            )

            val overrides = queueOverrides(port)
            assertTrue("失败后应重新发布队列", overrides.isNotEmpty())
            // 重试时用的是失败前记住的期望覆盖值, 不是 null
            assertEquals(attempt.first, overrides.last().first)
            assertEquals(attempt.second, overrides.last().second)
        }
    }

    /**
     * 换歌时队列会被重新下发, 这时不能把当前项的标题覆盖回歌名,
     * 否则蓝牙栈又不认这次 metadata, 歌词会丢一行。
     */
    @Test
    fun `queue republish keeps the bluetooth lyric override on the current item`() = runTest {
        MetadataBuilders().use { builders ->
            val source = FakeSource(playback(listOf(song(1)), 0)).apply {
                metadataInputs = bluetoothLyrics()
            }
            val fixture = fixture(source, backgroundScope)
            fixture.owner.updateMetadata()

            // 换歌路径: 队列重新发布, 不带覆盖值
            fixture.owner.publishCarQueue(
                songs = listOf(song(1)),
                selectedIndex = 0,
            )

            val overrides = queueOverrides(fixture.port)
            assertTrue(overrides.size >= 2)
            // 最后一次发布的当前项仍然是歌词, 不是歌名
            assertEquals(builders.string(0, MediaMetadata.METADATA_KEY_TITLE), overrides.last().first)
            assertEquals(builders.string(0, MediaMetadata.METADATA_KEY_ARTIST), overrides.last().second)
        }
    }

    /** 路由切到蓝牙时, 即使 metadata 内容没变也要重新对齐队列当前项 */
    @Test
    fun `switching to bluetooth re-aligns the queue current item`() = runTest {
        MetadataBuilders().use {
            val source = FakeSource(playback(listOf(song(1)), 0))
            val fixture = fixture(source, backgroundScope)
            fixture.owner.updateMetadata()
            assertEquals(0, queueOverrides(fixture.port).size)

            source.metadataInputs = bluetoothLyrics()
            fixture.owner.updateMetadata()

            assertEquals(1, queueOverrides(fixture.port).size)
        }
    }

    /**
     * 翻译开关变化时用收集到的值直接刷新。
     * PlayerManager 的状态由另一个收集器写入, 两者没有先后保证,
     * 这里必须用收集到的值, 不能去读可能还是旧值的状态。
     */
    @Test
    fun `the translation override wins over the state read from the source`() = runTest {
        MetadataBuilders().use { builders ->
            // 来源里的翻译开关是开的, 但收集器发来的值是关的
            val source = FakeSource(playback(listOf(song(1)), 0)).apply {
                metadataInputs = bluetoothLyrics()
            }
            val fixture = fixture(source, backgroundScope)
            fixture.owner.updateMetadata()

            fixture.owner.updateMetadata(bluetoothTranslationOverride = false)

            val titles = builders.titles()
            assertTrue("关闭翻译后不应再带译文", titles.last().contains("译文").not())
        }
    }

    private fun queueOverrides(port: PlaybackServicePresentationPort): List<Pair<String?, String?>> =
        mockingDetails(port).invocations.filter { it.method.name == "setQueue" }.map { invocation ->
            val args = invocation.arguments
            args[2] as String? to args[3] as String?
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
        ExternalBluetoothLyricPayload("line", "translation"),
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        false,
        bluetoothTranslationEnabled = true,
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

        /** 每次构建写入的标题, 顺序与构建顺序一致 */
        fun titles(): List<String> = results.indices.mapNotNull { index ->
            string(index, MediaMetadata.METADATA_KEY_TITLE)
        }

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
