package moe.ouom.neriplayer.core.player.persistence

import android.app.Application
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsOwner
import moe.ouom.neriplayer.core.player.testing.FakeExoPlayer
import moe.ouom.neriplayer.core.player.testing.FakeListenTogetherAccess
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerManagerQueueRemovalTest {
    private val manager = PlayerManager

    @Test
    fun `removing all 500 rows publishes one queue command and stops the player once`() {
        val songs = List(500) { song(it.toLong()) }
        withQueue(songs, currentIndex = 250) { fake, commands ->
            val revision = manager.currentQueueDisplayRevisionFlow.value

            manager.removeQueueItemsImpl(songs.indices.toSet())

            assertTrue(manager.currentPlaylist.isEmpty())
            assertEquals(-1, manager.currentIndex)
            assertNull(manager.currentSongFlow.value)
            assertEquals(0L, manager.playbackPositionFlow.value)
            assertNull(manager.currentMediaUrlFlow.value)
            assertFalse(manager.resumePlaybackRequested)
            assertEquals(1, fake.commands.count { it == "stop" })
            assertEquals(0, fake.commands.count { it == "play" || it == "prepare" })
            assertEquals(revision + 1, manager.currentQueueDisplayRevisionFlow.value)
            val command = commands.single()
            assertEquals("SET_QUEUE", command.type)
            assertEquals(emptyList<SongItem>(), command.queue)
            assertEquals(-1, command.currentIndex)
            assertEquals(0L, command.positionMs)
            assertEquals(false, command.shouldPlay)
        }
    }

    @Test
    fun `partial removal preserves the selected occurrence and playback position`() {
        val repeated = song(1)
        val second = song(2)
        val last = song(3)
        withQueue(listOf(repeated, second, repeated, last), currentIndex = 2) { fake, commands ->
            val revision = manager.currentQueueDisplayRevisionFlow.value

            manager.removeQueueItemsImpl(setOf(0, 3))

            assertEquals(listOf(second, repeated), manager.currentPlaylist)
            assertEquals(1, manager.currentIndex)
            assertSame(repeated, manager.currentSongFlow.value)
            assertEquals(POSITION_MS, manager.playbackPositionFlow.value)
            assertEquals(MEDIA_URL, manager.currentMediaUrlFlow.value)
            assertTrue(manager.resumePlaybackRequested)
            assertTrue(fake.commands.isEmpty())
            assertEquals(revision + 1, manager.currentQueueDisplayRevisionFlow.value)
            val command = commands.single()
            assertEquals("SET_QUEUE", command.type)
            assertEquals(listOf(second, repeated), command.queue)
            assertEquals(1, command.currentIndex)
            assertEquals(POSITION_MS, command.positionMs)
            assertNull(command.shouldPlay)
        }
    }

    @Test
    fun `removing the paused current row selects the next retained song without starting playback`() {
        val songs = List(4) { song(it.toLong()) }
        withQueue(songs, currentIndex = 1) { fake, commands ->
            manager.updateResumePlaybackRequested(false)
            manager._isPlayingFlow.value = false
            manager._playWhenReadyFlow.value = false
            manager._playerPlaybackStateFlow.value = Player.STATE_IDLE
            fake.isPlaying = false
            fake.playWhenReady = false

            manager.removeQueueItemsImpl(setOf(1, 2))

            assertEquals(listOf(songs[0], songs[3]), manager.currentPlaylist)
            assertEquals(1, manager.currentIndex)
            assertSame(songs[3], manager.currentSongFlow.value)
            assertEquals(0L, manager.playbackPositionFlow.value)
            assertNull(manager.currentMediaUrlFlow.value)
            assertFalse(manager.resumePlaybackRequested)
            assertEquals(1, fake.commands.count { it == "stop" })
            assertFalse(fake.commands.any { it == "play" || it == "prepare" })
            assertEquals(false, commands.single().shouldPlay)
        }
    }

    @Test
    fun `empty and invalid removals leave queue and player unchanged`() {
        val songs = listOf(song(1), song(2))
        withQueue(songs, currentIndex = 0) { fake, commands ->
            val snapshot = manager.currentQueueSnapshot()
            val revision = manager.currentQueueDisplayRevisionFlow.value

            manager.removeQueueItemsImpl(emptySet())
            manager.removeQueueItemsImpl(setOf(-1, songs.size, Int.MAX_VALUE))

            assertSame(snapshot, manager.currentQueueSnapshot())
            assertEquals(revision, manager.currentQueueDisplayRevisionFlow.value)
            assertEquals(POSITION_MS, manager.playbackPositionFlow.value)
            assertTrue(fake.commands.isEmpty())
            assertTrue(commands.isEmpty())
        }
    }

    @Test
    fun `a restricted listen together member cannot remove selected rows`() {
        val songs = listOf(song(1), song(2))
        val listenTogether = FakeListenTogetherAccess().apply {
            sessionState.value = ListenTogetherSessionState(
                roomId = "queue-removal-room",
                userUuid = "listener",
                role = "listener"
            )
            roomState.value = ListenTogetherRoomState(
                roomId = "queue-removal-room",
                version = 1L,
                controllerUserUuid = "controller",
                settings = ListenTogetherRoomSettings(allowMemberControl = false),
                roomStatus = ListenTogetherRoomStatuses.ACTIVE
            )
        }
        withQueue(songs, currentIndex = 0, listenTogether = listenTogether) { fake, commands ->
            val snapshot = manager.currentQueueSnapshot()
            val revision = manager.currentQueueDisplayRevisionFlow.value

            manager.removeQueueItemsImpl(songs.indices.toSet())

            assertSame(snapshot, manager.currentQueueSnapshot())
            assertEquals(revision, manager.currentQueueDisplayRevisionFlow.value)
            assertEquals(POSITION_MS, manager.playbackPositionFlow.value)
            assertTrue(fake.commands.isEmpty())
            assertTrue(commands.isEmpty())
        }
    }

    private fun withQueue(
        songs: List<SongItem>,
        currentIndex: Int,
        listenTogether: FakeListenTogetherAccess = FakeListenTogetherAccess(),
        check: (FakeExoPlayer, List<PlaybackCommand>) -> Unit
    ) = runTest {
        val previousMainScope = manager.mainScope
        val previousIoScope = manager.ioScope
        val previousStatsOwner = manager.playbackStatsOwner
        val previousInitialized = manager.initialized
        val previousSession = manager.queueStore.sessionSnapshot()
        val previousRevision = manager.currentQueueDisplayRevisionFlow.value
        val previousSong = manager._currentSongFlow.value
        val previousPosition = manager._playbackPositionMs.value
        val previousDuration = manager.playbackProgressOwner.duration.value
        val previousRequestToken = manager.playbackRequestToken
        val previousResumeRequested = manager.resumePlaybackRequested
        val previousPlaying = manager._isPlayingFlow.value
        val previousPlayWhenReady = manager._playWhenReadyFlow.value
        val previousPlayerState = manager._playerPlaybackStateFlow.value
        val previousMediaUrl = manager._currentMediaUrl.value
        val previousAudioInfo = manager._currentPlaybackAudioInfo.value
        val previousUrlResolvedAt = manager.currentMediaUrlResolvedAtMs
        val previousRememberProgress = manager.rememberLongFormPlaybackProgressEnabled
        val previousFailures = manager.consecutivePlayFailures
        val previousPendingLoad = manager.pendingMediaLoadActive
        val previousPendingSeek = manager.pendingSeekPositionOrNull()
        val previousPlayJob = manager.playJob
        val previousPauseJob = manager.pendingPauseJob
        val previousProgressJob = manager.progressJob
        val previousVolumeFadeJob = manager.volumeFadeJob
        val previousStartupWatchdogJob = manager.playbackStartupWatchdogJob
        val previousStartupWatchdogToken = manager.playbackStartupWatchdogToken
        val previousLyriconEnabled = manager.lyriconEnabled
        val previousRestoredPosition = manager.restoredResumePositionMs
        val previousRestoredResume = manager.restoredShouldResumePlayback
        val isolatedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val commands = mutableListOf<PlaybackCommand>()
        val fake = FakeExoPlayer().apply {
            isPlaying = true
            playWhenReady = true
            playbackState = Player.STATE_READY
            positionMs = POSITION_MS
        }
        PlayerTestEnvironment.install(listenTogether)
        fake.installInto(manager, ApplicationProvider.getApplicationContext<Application>()) {
            try {
                // 暂停后台任务，测试只观察命令，不访问播放状态数据库或启动音源解析
                manager.mainScope = isolatedScope
                manager.ioScope = isolatedScope
                manager.playbackStatsOwner = mock(PlaybackStatsOwner::class.java)
                manager.initialized = true
                manager.rememberLongFormPlaybackProgressEnabled = false
                manager.lyriconEnabled = false
                manager.playJob = null
                manager.pendingPauseJob = null
                manager.progressJob = null
                manager.volumeFadeJob = null
                manager.playbackStartupWatchdogJob = null
                manager.publishCurrentQueue(songs, currentIndex)
                manager._currentSongFlow.value = songs[currentIndex]
                manager._playbackPositionMs.value = POSITION_MS
                manager._currentMediaUrl.value = MEDIA_URL
                manager._isPlayingFlow.value = true
                manager._playWhenReadyFlow.value = true
                manager._playerPlaybackStateFlow.value = Player.STATE_READY
                manager.updateResumePlaybackRequested(true)
                manager.pendingMediaLoadActive = false
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    manager.playbackCommandFlow.collect { commands += it }
                }

                check(fake, commands)
            } finally {
                isolatedScope.cancel()
                manager.mainScope = previousMainScope
                manager.ioScope = previousIoScope
                manager.playbackStatsOwner = previousStatsOwner
                manager.initialized = previousInitialized
                manager.queueStore.restoreSession(
                    previousSession.queue,
                    previousSession.shuffleEnabled,
                    previousSession.shuffleRestore
                )
                manager._currentQueueDisplayRevisionFlow.value = previousRevision
                manager._currentSongFlow.value = previousSong
                manager._playbackPositionMs.value = previousPosition
                manager.playbackProgressOwner.setDuration(previousDuration)
                manager.playbackRequestToken = previousRequestToken
                manager.updateResumePlaybackRequested(previousResumeRequested)
                manager._isPlayingFlow.value = previousPlaying
                manager._playWhenReadyFlow.value = previousPlayWhenReady
                manager._playerPlaybackStateFlow.value = previousPlayerState
                manager._currentMediaUrl.value = previousMediaUrl
                manager._currentPlaybackAudioInfo.value = previousAudioInfo
                manager.currentMediaUrlResolvedAtMs = previousUrlResolvedAt
                manager.rememberLongFormPlaybackProgressEnabled = previousRememberProgress
                manager.consecutivePlayFailures = previousFailures
                manager.pendingMediaLoadActive = previousPendingLoad
                manager.playJob = previousPlayJob
                manager.pendingPauseJob = previousPauseJob
                manager.progressJob = previousProgressJob
                manager.volumeFadeJob = previousVolumeFadeJob
                manager.playbackStartupWatchdogJob = previousStartupWatchdogJob
                manager.playbackStartupWatchdogToken = previousStartupWatchdogToken
                manager.lyriconEnabled = previousLyriconEnabled
                manager.clearPendingSeekPosition()
                previousPendingSeek?.let(manager::rememberPendingSeekPosition)
                manager.setRestoredPlayback(previousRestoredPosition, previousRestoredResume)
                PlayerTestEnvironment.reset()
            }
        }
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = id,
        durationMs = 180_000L,
        coverUrl = null
    )

    private companion object {
        const val POSITION_MS = 42_000L
        const val MEDIA_URL = "https://example.com/queue-song.flac"
    }
}
