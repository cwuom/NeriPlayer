package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidPlaybackNotificationPublicationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val notifications = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val ports = mutableListOf<AndroidPlaybackServicePresentationPort>()
    private lateinit var port: AndroidPlaybackServicePresentationPort
    private val song = SongItem(1L, "Synthetic title", "Synthetic artist", "Synthetic album", 0L, 30_000L, null)
    private val inputs = PlaybackServiceNotificationRenderInputs(
        song = song, text = song.artist, audioRouteMuted = false, playbackControlPlaying = true,
        favorite = false, interactiveFavorite = false, floatingLyricsEnabled = false,
        lyricState = StatusBarLyricNotificationState(false, null), artwork = null, shareUrl = null
    )

    @Before
    fun setUp() {
        val presentation = mock(PlayerPresentationHost::class.java)
        doAnswer { Intent(Intent.ACTION_MAIN).setPackage(context.packageName) }
            .`when`(presentation).mainActivityIntent(context)
        PlayerTestEnvironment.install(presentation = presentation)
        notifications.cancelAll()
        port = newPort()
    }

    @After
    fun tearDown() {
        ports.forEach(AndroidPlaybackServicePresentationPort::releaseSessionForDestroy)
        notifications.cancelAll()
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `actual publication tags and confirms the system media notification`() {
        assertNull(port.notificationPublicationToken())

        port.publishNotification(inputs)

        val token = checkNotNull(port.notificationPublicationToken())
        val posted = notifications.activeNotifications.single()
        assertEquals(AudioPlayerService.NOTIFICATION_ID, posted.id)
        assertNull(posted.tag)
        assertEquals(token, publicationToken(posted.notification))
        assertEquals(song.name, posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(song.artist, posted.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertTrue(port.isNotificationPublicationCurrent(token))
    }

    @Test
    fun `foreground builds carry distinct tokens before the notify entry is used`() {
        val first = port.buildNotification(inputs)
        val second = port.buildNotification(inputs)
        val anotherInstance = newPort().buildNotification(inputs)

        assertNotEquals(publicationToken(first), publicationToken(second))
        assertNotEquals(publicationToken(first), publicationToken(anotherInstance))
        assertNull(port.notificationPublicationToken())
        assertFalse(port.isNotificationPublicationCurrent(publicationToken(first)))

        notifications.notify(AudioPlayerService.NOTIFICATION_ID, first)

        assertTrue(port.isNotificationPublicationCurrent(publicationToken(first)))
        assertFalse(port.isNotificationPublicationCurrent(publicationToken(second)))
    }

    @Test
    fun `a later identical publication invalidates the earlier token`() {
        port.publishNotification(inputs)
        val first = checkNotNull(port.notificationPublicationToken())

        port.publishNotification(inputs)

        val latest = checkNotNull(port.notificationPublicationToken())
        assertNotEquals(first, latest)
        assertFalse(port.isNotificationPublicationCurrent(first))
        assertTrue(port.isNotificationPublicationCurrent(latest))
    }

    @Test
    fun `another notification id cannot confirm the media publication token`() {
        val notification = port.buildNotification(inputs)
        notifications.notify(AudioPlayerService.NOTIFICATION_ID + 1, notification)

        assertFalse(port.isNotificationPublicationCurrent(publicationToken(notification)))
    }

    @Test
    fun `a tagged notification cannot confirm the untagged media publication`() {
        val notification = port.buildNotification(inputs)
        notifications.notify("synthetic-other-channel", AudioPlayerService.NOTIFICATION_ID, notification)

        assertFalse(port.isNotificationPublicationCurrent(publicationToken(notification)))
    }

    @Test
    fun `an untagged media notification without the token cannot confirm a publication`() {
        val notification = port.buildNotification(inputs)
        val token = publicationToken(notification)
        notification.extras.remove(PublicationTokenExtra)
        notifications.notify(AudioPlayerService.NOTIFICATION_ID, notification)

        assertFalse(port.isNotificationPublicationCurrent(token))
    }

    private fun newPort(): AndroidPlaybackServicePresentationPort = AndroidPlaybackServicePresentationPort(context)
        .also { instance ->
            instance.initializeSession(object : MediaSession.Callback() {})
            ports += instance
        }

    private fun publicationToken(notification: Notification): String =
        checkNotNull(notification.extras.getString(PublicationTokenExtra))

    private companion object {
        const val PublicationTokenExtra = "moe.ouom.neriplayer.media.publication"
    }
}
