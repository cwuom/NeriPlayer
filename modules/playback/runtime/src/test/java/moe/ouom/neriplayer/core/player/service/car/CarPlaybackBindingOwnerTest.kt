package moe.ouom.neriplayer.core.player.service.car

import android.content.Intent
import android.media.session.MediaSession
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class CarPlaybackBindingOwnerTest {
    @Test
    fun `cached binder survives unbind and rebind with live token and readiness`() {
        var token: MediaSession.Token? = mock(MediaSession.Token::class.java)
        val owner = CarPlaybackBindingOwner { token }
        val intent = intent(AudioPlayerService.ACTION_BIND_CAR)
        val binder = checkNotNull(owner.bind(intent))
        assertTrue(owner.isBound)
        assertSame(token, binder.sessionToken)
        assertFalse(binder.runtimeReady.value)
        owner.markRuntimeReady(true)
        assertTrue(binder.runtimeReady.value)

        assertTrue(owner.unbind(intent))
        assertFalse(owner.isBound)
        owner.rebind(intent)
        assertTrue(owner.isBound)
        assertSame(binder, owner.bind(intent))
        token = mock(MediaSession.Token::class.java)
        assertSame(token, binder.sessionToken)
        owner.markRuntimeReady(false)
        assertFalse(binder.runtimeReady.value)
        token = null
        assertNull(binder.sessionToken)
    }

    @Test
    fun `invalid binding actions do not acquire or release a valid binding`() {
        val owner = CarPlaybackBindingOwner { null }
        val other = intent("other_action")
        assertNull(owner.bind(null))
        assertNull(owner.bind(other))
        assertFalse(owner.unbind(null))
        owner.rebind(other)
        assertFalse(owner.isBound)

        owner.bind(intent(AudioPlayerService.ACTION_BIND_CAR))
        assertFalse(owner.unbind(other))
        assertTrue(owner.isBound)
    }

    private fun intent(action: String): Intent = mock(Intent::class.java).apply {
        `when`(this.action).thenReturn(action)
    }
}
