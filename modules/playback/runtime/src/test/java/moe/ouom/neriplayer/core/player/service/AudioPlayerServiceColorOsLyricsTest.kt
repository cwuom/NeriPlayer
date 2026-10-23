package moe.ouom.neriplayer.core.player.service

import android.app.Application
import android.media.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkOwner
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.core.player.service.presentation.AndroidPlaybackServicePresentationSource
import moe.ouom.neriplayer.core.player.service.presentation.PlaybackServiceNotificationRenderInputs
import moe.ouom.neriplayer.core.player.service.presentation.PlaybackServicePlaybackSnapshot
import moe.ouom.neriplayer.core.player.service.presentation.PlaybackServicePresentationOwner
import moe.ouom.neriplayer.core.player.service.presentation.PlaybackServicePresentationPort
import moe.ouom.neriplayer.core.player.service.presentation.PlaybackServicePresentationSource
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.settings.SettingsRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AudioPlayerServiceColorOsLyricsTest {
    @Test
    fun `ColorOS-only timeline updates repost metadata while Bluetooth payload stays unchanged`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            Fixture(backgroundScope).use { fixture ->
                fixture.start()
                runCurrent()
                val initialNotifications = fixture.notifications
                val bluetoothPayload = PlayerManager.externalBluetoothLyricPayloadFlow.value

                fixture.publish("first")
                runCurrent()

                assertEquals("[00:01.000]first\n", JSONObject(requireNotNull(fixture.metadata.last().getString("lyricInfo"))).getString("lyric"))
                assertEquals(bluetoothPayload, PlayerManager.externalBluetoothLyricPayloadFlow.value)
                assertEquals(initialNotifications + 1, fixture.notifications)

                PlayerManager.colorOsLockscreenLyricsEnabled = false
                runCurrent()
                assertNull(fixture.metadata.last().getString("lyricInfo"))
                assertEquals(initialNotifications + 2, fixture.notifications)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `ColorOS timeline changes defer one repost until the screen becomes interactive`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            Fixture(backgroundScope).use { fixture ->
                fixture.start()
                runCurrent()
                val initialNotifications = fixture.notifications
                fixture.screenInteractive(false)

                fixture.publish("first")
                runCurrent()
                fixture.publish("second")
                runCurrent()
                assertEquals(initialNotifications, fixture.notifications)
                fixture.screenInteractive(true)
                fixture.screenInteractive(true)

                assertEquals("[00:01.000]second\n", JSONObject(requireNotNull(fixture.metadata.last().getString("lyricInfo"))).getString("lyric"))
                assertEquals(initialNotifications + 1, fixture.notifications)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class Fixture(scope: CoroutineScope) : AutoCloseable {
        private val previousSong = PlayerManager.currentSongFlow.value
        private val previousEnabled = PlayerManager.colorOsLockscreenLyricsEnabled
        private val previousQueue = PlayerManager.currentQueueSnapshot()
        private val registry = PlayerDependencies::class.java.getDeclaredField("registry").apply { isAccessible = true }.get(null)
        private val environmentField = registry.javaClass.getDeclaredField("environment").apply { isAccessible = true }
        private val previousEnvironment = environmentField.get(registry)
        private val song = SongItem(7_300_001L, "Song", "Artist", "Album", 0L, 60_000L, null)
        private val service: AudioPlayerService
        private val serviceScope: CoroutineScope
        val metadata = mutableListOf<MediaMetadata>()
        var notifications = 0
            private set

        init {
            val settings = mock(SettingsRepository::class.java)
            `when`(settings.playbackServiceIdleShutdownMinutesFlow).thenReturn(emptyFlow())
            `when`(settings.usbDeviceAttachHandlingEnabledFlow).thenReturn(emptyFlow())
            `when`(settings.statusBarLyricsEnabledFlow).thenReturn(emptyFlow())
            `when`(settings.floatingLyricsPreferencesFlow).thenReturn(emptyFlow())
            val repositories = mock(PlayerRepositoryDependencies::class.java)
            `when`(repositories.settingsRepo).thenReturn(settings)
            val downloads = mock(PlayerDownloadAccess::class.java)
            `when`(downloads.downloadPresenceVersion).thenReturn(MutableStateFlow(0))
            PlayerTestEnvironment.install(repositories = repositories, downloads = downloads)
            PlayerManager._currentSongFlow.value = song
            PlayerManager.publishCurrentQueue(listOf(song), 0)
            PlayerManager.colorOsLockscreenLyricsEnabled = true
            PlayerManager.colorOsLyricTimelineOwner.begin(null)
            service = Robolectric.buildService(AudioPlayerService::class.java).get()
            serviceScope = serviceField("serviceScope").get(service) as CoroutineScope
            val source = object : PlaybackServicePresentationSource by AndroidPlaybackServicePresentationSource(service) {
                override fun playback() = PlaybackServicePlaybackSnapshot(
                    song, true, 0L, 0L, false, false, false, false, false, false, 1f,
                )
            }
            val port = object : PlaybackServicePresentationPort by mock(PlaybackServicePresentationPort::class.java) {
                override fun setMetadata(metadata: MediaMetadata) { this@Fixture.metadata += metadata }
                override fun publishNotification(inputs: PlaybackServiceNotificationRenderInputs) { notifications++ }
                override fun localizedString(resId: Int, arg: String?) = "action"
            }
            val artwork = mock(PlaybackArtworkOwner::class.java)
            val emptyArtwork = PlaybackArtworkSnapshot(null, null, null, false, false, false)
            `when`(artwork.observe(song)).thenReturn(emptyArtwork)
            `when`(artwork.snapshotFor(song)).thenReturn(emptyArtwork)
            serviceField("presentationOwnerDelegate").set(service, lazy { PlaybackServicePresentationOwner(source, port, artwork, scope) })
            serviceField("isForegroundStarted").setBoolean(service, true)
        }

        fun start() {
            assertFalse(serviceField("playerRuntimeReady").getBoolean(service))
            invoke("finishPlayerRuntimeSetup")
        }

        fun publish(text: String) {
            val owner = PlayerManager.colorOsLyricTimelineOwner
            val request = owner.begin(song.stableKey())
            assertTrue(owner.publish(request, listOf(LyricEntry(text, 1_000L, 2_000L)), emptyList(), 0L))
        }

        fun screenInteractive(interactive: Boolean) {
            AudioPlayerService::class.java.getDeclaredMethod("onScreenInteractiveChanged", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, interactive)
        }

        private fun invoke(name: String) {
            AudioPlayerService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
        }

        override fun close() {
            serviceScope.cancel()
            invoke("unregisterNoisyReceiverForDestroy")
            PlayerManager.colorOsLyricTimelineOwner.begin(null)
            PlayerManager._currentSongFlow.value = previousSong
            PlayerManager.publishCurrentQueue(previousQueue.playlist, previousQueue.currentIndex)
            PlayerManager.colorOsLockscreenLyricsEnabled = previousEnabled
            environmentField.set(registry, previousEnvironment)
        }
    }

    private companion object {
        fun serviceField(name: String) = AudioPlayerService::class.java.getDeclaredField(name).apply { isAccessible = true }
    }
}
