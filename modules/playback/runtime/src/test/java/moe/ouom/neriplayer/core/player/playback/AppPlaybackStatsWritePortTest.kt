package moe.ouom.neriplayer.core.player.playback

import android.app.Application
import java.io.IOException
import java.lang.reflect.Field
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerEnvironment
import moe.ouom.neriplayer.core.player.host.PlayerListenTogetherAccess
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class AppPlaybackStatsWritePortTest {
    @Test
    fun `ready player dependencies take priority over bound application singleton repositories`() = runTest {
        Fixture().use { fixture ->
            fixture.install(ready = true)
            fixture.bindApplication()
            `when`(fixture.injectedStats.hasPendingWrites()).thenReturn(true)
            `when`(fixture.injectedStats.statsClearedAtFlow).thenReturn(MutableStateFlow(100L))
            val snapshot = snapshot()

            assertTrue(AppPlaybackStatsWritePort.hasPendingWrites())
            assertEquals(100L, AppPlaybackStatsWritePort.clearedAt())
            AppPlaybackStatsWritePort.record(snapshot)
            AppPlaybackStatsWritePort.flushPendingWrites()

            fixture.verifyRecorded(fixture.injectedStats, fixture.injectedPlaylist, snapshot)
            verify(fixture.injectedStats).flushPendingWrites()
            verifyNoInteractions(fixture.singletonStats, fixture.singletonPlaylist)
        }
    }

    @Test
    fun `safe mode uses bound singletons without asking unready application dependencies for repositories`() = runTest {
        Fixture().use { fixture ->
            fixture.install(ready = false, forbidRepositories = true)
            fixture.bindApplication()
            `when`(fixture.singletonStats.hasPendingWrites()).thenReturn(true)
            `when`(fixture.singletonStats.statsClearedAtFlow).thenReturn(MutableStateFlow(10L))
            val snapshot = snapshot()

            assertTrue(AppPlaybackStatsWritePort.hasPendingWrites())
            assertEquals(10L, AppPlaybackStatsWritePort.clearedAt())
            AppPlaybackStatsWritePort.record(snapshot)
            AppPlaybackStatsWritePort.flushPendingWrites()

            fixture.verifyRecorded(fixture.singletonStats, fixture.singletonPlaylist, snapshot)
            verify(fixture.singletonStats).flushPendingWrites()
            verifyNoInteractions(fixture.repositories, fixture.injectedStats, fixture.injectedPlaylist)
            verify(fixture.application, never()).filesDir
        }
    }

    @Test
    fun `an unbound port retains the existing injected repository contract before readiness`() = runTest {
        Fixture().use { fixture ->
            fixture.install(ready = false)
            val snapshot = snapshot()
            AppPlaybackStatsWritePort.record(snapshot)
            fixture.verifyRecorded(fixture.injectedStats, fixture.injectedPlaylist, snapshot)
            verifyNoInteractions(fixture.singletonStats, fixture.singletonPlaylist)
        }
    }

    @Test
    fun `listening without a count and a counted play without local attribution never write playlist stats`() = runTest {
        Fixture().use { fixture ->
            fixture.install(ready = true)
            val listened = snapshot().copy(playCountIncrement = 0)
            val unattributed = snapshot().copy(localPlaylistId = null, eventId = "unattributed")
            AppPlaybackStatsWritePort.record(listened)
            AppPlaybackStatsWritePort.record(unattributed)
            fixture.verifySongRecord(fixture.injectedStats, listened)
            fixture.verifySongRecord(fixture.injectedStats, unattributed)
            verifyNoInteractions(fixture.injectedPlaylist)
        }
    }

    @Test
    fun `song write failure cannot confirm or begin the playlist write`() = runTest {
        Fixture().use { fixture ->
            fixture.install(ready = true)
            val snapshot = snapshot()
            doAnswer { throw IOException("song storage unavailable") }.`when`(fixture.injectedStats)
                .recordListenDeltaNow(snapshot.song, snapshot.listenedMs, snapshot.playCountIncrement,
                    snapshot.scheduleSync, snapshot.eventId, snapshot.playedAt, snapshot.observedClearedAt)
            try {
                AppPlaybackStatsWritePort.record(snapshot)
                error("the song write must fail")
            } catch (expected: IOException) { assertEquals("song storage unavailable", expected.message) }
            verifyNoInteractions(fixture.injectedPlaylist)
        }
    }

    @Test
    fun `playlist failure propagates after the song write so the caller retains the same event`() = runTest {
        Fixture().use { fixture ->
            fixture.install(ready = true)
            val snapshot = snapshot()
            doAnswer { throw IOException("playlist storage unavailable") }.`when`(fixture.injectedPlaylist)
                .recordPlayNow(42, snapshot.playedAt, snapshot.eventId)
            try {
                AppPlaybackStatsWritePort.record(snapshot)
                error("the playlist write must fail")
            } catch (expected: IOException) { assertEquals("playlist storage unavailable", expected.message) }
            fixture.verifySongRecord(fixture.injectedStats, snapshot)
        }
    }

    private class Fixture : AutoCloseable {
        val application = mock(Application::class.java)
        val repositories = mock(PlayerRepositoryDependencies::class.java)
        val injectedStats = mock(PlaybackStatsRepository::class.java)
        val injectedPlaylist = mock(LocalPlaylistPlaybackStatsRepository::class.java)
        val singletonStats = mock(PlaybackStatsRepository::class.java)
        val singletonPlaylist = mock(LocalPlaylistPlaybackStatsRepository::class.java)
        private val registry = requireNotNull(field(PlayerDependencies::class.java, "registry").get(null))
        private val environment = field(registry.javaClass, "environment")
        private val applicationField = field(AppPlaybackStatsWritePort::class.java, "application")
        private val statsSingleton = field(PlaybackStatsRepository::class.java, "INSTANCE")
        private val playlistSingleton = field(LocalPlaylistPlaybackStatsRepository::class.java, "instance")
        private val previous = listOf(environment.get(registry), applicationField.get(null),
            statsSingleton.get(null), playlistSingleton.get(null))

        init {
            environment.set(registry, null)
            applicationField.set(null, null)
            statsSingleton.set(null, singletonStats)
            playlistSingleton.set(null, singletonPlaylist)
            `when`(application.applicationContext).thenReturn(application)
        }

        fun install(ready: Boolean, forbidRepositories: Boolean = false) {
            if (forbidRepositories) {
                `when`(repositories.playbackStatsRepo).thenThrow(UninitializedPropertyAccessException("application"))
                `when`(repositories.localPlaylistPlaybackStatsRepo).thenThrow(UninitializedPropertyAccessException("application"))
            } else {
                `when`(repositories.playbackStatsRepo).thenReturn(injectedStats)
                `when`(repositories.localPlaylistPlaybackStatsRepo).thenReturn(injectedPlaylist)
            }
            PlayerDependencies.install(PlayerEnvironment(application, repositories,
                mock(PlayerDownloadAccess::class.java), mock(PlayerListenTogetherAccess::class.java),
                mock(PlayerPresentationHost::class.java), { ready }, { Job().apply { complete() } }))
            clearInvocations(repositories)
        }

        fun bindApplication() { AppPlaybackStatsWritePort.bind(application) }

        suspend fun verifyRecorded(stats: PlaybackStatsRepository, playlist: LocalPlaylistPlaybackStatsRepository,
            snapshot: PlaybackStatsSnapshot) {
            verifySongRecord(stats, snapshot)
            verify(playlist).recordPlayNow(requireNotNull(snapshot.localPlaylistId), snapshot.playedAt, snapshot.eventId)
        }

        suspend fun verifySongRecord(stats: PlaybackStatsRepository, snapshot: PlaybackStatsSnapshot) {
            verify(stats).recordListenDeltaNow(snapshot.song, snapshot.listenedMs, snapshot.playCountIncrement,
                snapshot.scheduleSync, snapshot.eventId, snapshot.playedAt, snapshot.observedClearedAt)
        }

        override fun close() {
            environment.set(registry, previous[0])
            applicationField.set(null, previous[1])
            statsSingleton.set(null, previous[2])
            playlistSingleton.set(null, previous[3])
        }

        private fun field(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }
    }

    private fun snapshot() = PlaybackStatsSnapshot(SongItem(1, "synthetic", "artist", "album", 1, 60_000, null),
        15_000, 1, false, 42, "event", 1_700_000_000_000, 100)
}
