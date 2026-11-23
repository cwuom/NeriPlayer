package moe.ouom.neriplayer.core.player.watchdog

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.runtime.transport.PlaybackTransportOwner
import moe.ouom.neriplayer.core.player.testing.FakeExoPlayer
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DeferredBiliRecoveryTest {
    private val manager = PlayerManager
    private val primary = SongUrlResult.Success(
        url = "https://audio.example/primary",
        candidateUrls = listOf("https://audio.example/backup"),
        cacheKeyOverride = "bili-auto-main",
        deferredNeteaseAutoBiliCandidateId = "main-video"
    )
    private val song = SongItem(
        id = 518L, name = "Song", artist = "Artist", album = "Album",
        albumId = 42L, durationMs = 180_000L, coverUrl = null
    )

    @Test
    fun `first recovery selects the backup CDN without starting a search`() = withPlayer {
        assertTrue(recover())
        assertEquals(primary.candidateUrls.single(), manager.currentPlaybackCandidate()?.url)
        assertEquals(12_000L, manager.activePlaybackResumePositionMs)
        assertNull(manager.urlRefreshController.currentSemantics())
    }

    @Test
    fun `one search handles repeated failures and a late result cannot replace another song`() = withPlayer { player ->
        manager.activePlaybackUrlIndex = 1
        val searched = mutableListOf<String>()
        withLateSearch(searched) {
            manager._currentSongFlow.value = song.copy(id = 519L)
            manager.playbackRequestToken++
        }.use {
            assertTrue(recover())
            val first = requireNotNull(manager.urlRefreshController.currentSemantics())
            assertEquals("main-video", first.failedNeteaseAutoBiliCandidateId)
            assertTrue(recover("startup_stall"))
            assertSame(first, manager.urlRefreshController.currentSemantics())
            runCurrent()
        }
        assertEquals(listOf("main-video"), searched)
        assertEquals(primary.candidateUrls.single(), manager.currentPlaybackCandidate()?.url)
        assertTrue(player.commands.isEmpty())
    }

    @Test
    fun `a newer refresh on the same playback request owns the result`() = withPlayer { player ->
        manager.activePlaybackUrlIndex = 1
        val searched = mutableListOf<String>()
        withLateSearch(searched) {
            val previous = requireNotNull(manager.urlRefreshController.currentSemantics())
            manager.urlRefreshController.startOrReuse(
                semantics = previous.copy(reason = "replacement", failedNeteaseAutoBiliCandidateId = null),
                start = { PlayerManager.UrlRefreshOperation(CompletableDeferred(), Job()) },
                cancel = { operation ->
                    operation.job.cancel()
                    operation.deferred.cancel()
                }
            )
        }.use {
            assertTrue(recover())
            runCurrent()
        }
        assertEquals(listOf("main-video"), searched)
        assertEquals("replacement", manager.urlRefreshController.currentSemantics()?.reason)
        assertEquals(primary.candidateUrls.single(), manager.currentPlaybackCandidate()?.url)
        assertTrue(player.commands.isEmpty())
    }

    @Test
    fun `an alternate video installs and resumes at the latest position`() = withPlayer { player ->
        val searched = mutableListOf<String>()
        manager.activePlaybackUrlIndex = 1
        manager.loadedMediaRequestToken = manager.playbackRequestToken
        manager._playbackPositionMs.value = 15_000L
        player.positionMs = 0L
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // 播放持久化属于独立的数据库测试，避免替换曲目时产生磁盘写入
            val persistence = Class.forName("moe.ouom.neriplayer.core.player.persistence.PlayerManagerStatePersistenceExtensionsKt")
            mockStatic(persistence) { call ->
                if (call.method.name == "persistStateImpl") Unit else call.callRealMethod()
            }.use {
                withLateSearch(searched) {
                    manager._playbackPositionMs.value = 18_000L
                }.use {
                    assertTrue(recover("startup_stall"))
                    val semantics = requireNotNull(manager.urlRefreshController.currentSemantics())
                    assertEquals(15_000L, semantics.resumePositionMs)
                    val active = manager.urlRefreshController.startOrReuse(
                        semantics, start = { error("missing refresh") }, cancel = {}
                    )
                    active.operation.job.join()
                }
            }
            val installed = ArgumentCaptor.forClass(MediaItem::class.java)
            verify(player.player).setMediaItem(installed.capture())
            assertEquals("https://audio.example/late-match", installed.value.localConfiguration?.uri.toString())
            assertEquals(listOf("main-video"), searched)
            assertEquals(18_000L, player.positionMs)
            assertTrue(player.commands.containsAll(listOf("seekTo(18000)", "prepare", "play")))
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun recover(reason: String = "player_error") =
        manager.trySwitchToNextPlaybackCandidateForRecovery(reason, invalidateCurrentCache = false)

    private fun withLateSearch(searched: MutableList<String>, beforeResult: () -> Unit): AutoCloseable {
        // 在搜索返回前模拟外部播放事件，保留真实的刷新所有权及结果安装流程
        val resolver = Class.forName("moe.ouom.neriplayer.core.player.resolver.netease.PlayerManagerNeteaseAutoSourceSwitchKt")
        return mockStatic(resolver) { call ->
            if (call.method.name == "tryResolveNeteaseAutoBiliSource") {
                searched += call.getArgument<String>(3)
                beforeResult()
                SongUrlResult.Success(url = "https://audio.example/late-match")
            } else {
                call.callRealMethod()
            }
        }
    }

    private fun withPlayer(block: suspend TestScope.(FakeExoPlayer) -> Unit) {
        val previousMain = manager.mainScope
        val previousIo = manager.ioScope
        val previousSong = manager._currentSongFlow.value
        val previousResume = manager.resumePlaybackRequested
        val previousToken = manager.playbackRequestToken
        val previousCandidates = manager.activePlaybackCandidates
        val previousIndex = manager.activePlaybackUrlIndex
        val previousPosition = manager.activePlaybackResumePositionMs
        val previousCommand = manager.activePlaybackCommandSource
        val previousRefreshKey = manager.lastUrlRefreshKey
        val previousRefreshTime = manager.lastUrlRefreshAtMs
        val previousInitialized = manager.initialized
        val previousLoaded = manager.loadedMediaRequestToken
        val previousPending = manager.pendingMediaLoadActive
        val previousUrl = manager._currentMediaUrl.value
        val previousUrlTime = manager.currentMediaUrlResolvedAtMs
        val previousAudio = manager._currentPlaybackAudioInfo.value
        val previousPositionFlow = manager._playbackPositionMs.value
        val previousProgress = manager.playbackProgressAdvanceReported
        val previousBaseline = manager.playbackProgressBaselinePositionMs
        val previousEndKey = manager.lastHandledTrackEndKey
        val previousEndAt = manager.lastTrackEndHandledAtMs
        val previousOffload = manager.lastRequiresPcmAudioProcessing
        val previousTransport = manager.playbackTransportOwner
        val previousFade = manager.volumeFadeJob
        val previousSeek = manager.pendingSeekPositionOrNull()
        val previousExpedited = manager.expeditedYouTubeSeekRecoveryPending
        check(manager.urlRefreshController.currentSemantics() == null)
        PlayerTestEnvironment.install()
        val fake = FakeExoPlayer().apply { positionMs = 12_000L }
        fake.installInto(manager, ApplicationProvider.getApplicationContext<Application>()) {
            runTest {
                try {
                    manager.mainScope = backgroundScope
                    manager.ioScope = backgroundScope
                    manager._currentSongFlow.value = song
                    manager.updateResumePlaybackRequested(true)
                    manager.activePlaybackCandidates = primary.playbackCandidates()
                    manager.activePlaybackUrlIndex = 0
                    manager.activePlaybackCommandSource = PlaybackCommandSource.LOCAL
                    manager.initialized = false
                    manager.playbackTransportOwner = mock(PlaybackTransportOwner::class.java)
                    manager.volumeFadeJob = null
                    manager.clearPendingSeekPosition()
                    block(fake)
                } finally {
                    manager.urlRefreshController.cancelCurrent()
                    backgroundScope.coroutineContext.cancelChildren()
                    manager.mainScope = previousMain
                    manager.ioScope = previousIo
                    manager._currentSongFlow.value = previousSong
                    manager.updateResumePlaybackRequested(previousResume)
                    manager.playbackRequestToken = previousToken
                    manager.activePlaybackCandidates = previousCandidates
                    manager.activePlaybackUrlIndex = previousIndex
                    manager.activePlaybackResumePositionMs = previousPosition
                    manager.activePlaybackCommandSource = previousCommand
                    manager.lastUrlRefreshKey = previousRefreshKey
                    manager.lastUrlRefreshAtMs = previousRefreshTime
                    manager.initialized = previousInitialized
                    manager.loadedMediaRequestToken = previousLoaded
                    manager.pendingMediaLoadActive = previousPending
                    manager._currentMediaUrl.value = previousUrl
                    manager.currentMediaUrlResolvedAtMs = previousUrlTime
                    manager._currentPlaybackAudioInfo.value = previousAudio
                    manager._playbackPositionMs.value = previousPositionFlow
                    manager.playbackProgressAdvanceReported = previousProgress
                    manager.playbackProgressBaselinePositionMs = previousBaseline
                    manager.lastHandledTrackEndKey = previousEndKey
                    manager.lastTrackEndHandledAtMs = previousEndAt
                    manager.lastRequiresPcmAudioProcessing = previousOffload
                    manager.playbackTransportOwner = previousTransport
                    manager.volumeFadeJob = previousFade
                    manager.clearPendingSeekPosition()
                    previousSeek?.let(manager::rememberPendingSeekPosition)
                    manager.playbackProgressOwner.setExpeditedYouTubeSeekRecoveryPending(previousExpedited)
                    PlayerTestEnvironment.reset()
                }
            }
        }
    }
}
