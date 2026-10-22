package moe.ouom.neriplayer.core.player.service

import android.app.Application
import android.media.session.MediaSession
import android.os.Looper
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.service.car.clearCarQueueIdentityCache
import moe.ouom.neriplayer.core.player.service.presentation.AndroidPlaybackServicePresentationPort
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AudioPlayerServiceCarQueueTest {
    @Test
    fun `duplicate queue and song notifications publish the car queue once`() = withFixture {
        PlayerManager.publishCurrentQueue(List(200) { song(it.toLong()) }, 0)

        updateQueue()
        updateQueue()

        assertEquals(1, publications.size)
        assertEquals(100, publications.single().size)
        assertEquals("Song 0", publications.single().first().description.title.toString())
    }

    @Test
    fun `selection and metadata changes republish the latest queue including duplicate songs`() = withFixture {
        val duplicate = song(999L)
        PlayerManager.publishCurrentQueue(List(200) { index ->
            if (index == 0 || index == 199) duplicate else song(index.toLong())
        }, 0)
        updateQueue()
        val firstDuplicateId = publications.last().first().queueId

        PlayerManager.currentIndex = 199
        updateQueue()
        updateQueue()

        assertEquals(2, publications.size)
        assertEquals("Song 100", publications.last().first().description.title.toString())
        assertEquals("Song 999", publications.last().last().description.title.toString())
        assertNotEquals(firstDuplicateId, publications.last().last().queueId)

        PlayerManager.updateCurrentQueueSongs { songs ->
            songs.toMutableList().also { it[199] = duplicate.copy(customName = "Edited title") }
        }
        updateQueue()
        updateQueue()

        assertEquals(3, publications.size)
        assertEquals("Edited title", publications.last().last().description.title.toString())
    }

    @Test
    fun `missing replacement and released sessions do not reuse another publication`() = withFixture {
        PlayerManager.publishCurrentQueue(listOf(song(1L)), 0)
        attachSession(null)
        updateQueue()
        assertEquals(0, publications.size)

        attachSession(newSession())
        updateQueue()
        updateQueue()
        assertEquals(1, publications.size)

        attachSession(newSession())
        updateQueue()
        updateQueue()
        assertEquals(2, publications.size)

        invokeService("releaseMediaSessionForDestroy")
        updateQueue()
        assertEquals(3, publications.size)
    }

    @Test
    fun `failed queue publication remains eligible for retry`() = withFixture {
        PlayerManager.publishCurrentQueue(listOf(song(1L)), 0)
        val failure = IllegalStateException("Session rejected queue")
        nextPublicationFailure = failure

        val thrown = assertThrows(InvocationTargetException::class.java) { updateQueue() }
        assertSame(failure, thrown.cause)
        assertEquals(0, publications.size)

        updateQueue()
        updateQueue()
        assertEquals(1, publications.size)
    }

    private fun withFixture(block: CarQueueFixture.() -> Unit) {
        CarQueueFixture().use { it.block() }
    }

    private class CarQueueFixture : AutoCloseable {
        private val previousQueue = PlayerManager.currentQueueSnapshot()
        private val service = Robolectric.buildService(AudioPlayerService::class.java).get()
        private val port = (serviceField("presentationPortDelegate").get(service) as Lazy<*>)
            .value as AndroidPlaybackServicePresentationPort
        private val sessionField = AndroidPlaybackServicePresentationPort::class.java
            .getDeclaredField("mediaSession").apply { isAccessible = true }
        val publications = mutableListOf<List<MediaSession.QueueItem>>()
        var nextPublicationFailure: RuntimeException? = null

        init {
            attachSession(newSession())
        }

        fun newSession(): MediaSession = mock(MediaSession::class.java).also { session ->
            doAnswer { invocation ->
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                nextPublicationFailure?.let { failure ->
                    nextPublicationFailure = null
                    throw failure
                }
                publications += invocation.getArgument<List<MediaSession.QueueItem>>(0)
                null
            }.`when`(session).setQueue(anyList())
        }

        fun attachSession(session: MediaSession?) = sessionField.set(port, session)

        fun updateQueue() = invokeService("updateCarQueue")

        fun invokeService(name: String) {
            AudioPlayerService::class.java.getDeclaredMethod(name).apply {
                isAccessible = true
            }.invoke(service)
        }

        override fun close() {
            (serviceField("serviceScope").get(service) as CoroutineScope).cancel()
            clearCarQueueIdentityCache()
            PlayerManager.publishCurrentQueue(previousQueue.playlist, previousQueue.currentIndex)
        }
    }

    private companion object {
        fun song(id: Long) = SongItem(
            id = id, name = "Song $id", artist = "Artist", album = "Album", albumId = 1L,
            durationMs = 1_000L, coverUrl = null, mediaUri = "https://example.test/$id.mp3",
        )

        fun serviceField(name: String) = AudioPlayerService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
    }
}
