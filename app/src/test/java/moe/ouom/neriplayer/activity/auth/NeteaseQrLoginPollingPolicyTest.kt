package moe.ouom.neriplayer.activity.auth

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseQrLoginPollingPolicyTest {

    @Test
    fun `visible QR login keeps polling`() {
        assertTrue(shouldPollNeteaseQrLogin(Lifecycle.State.STARTED, hasReturned = false))
        assertTrue(shouldPollNeteaseQrLogin(Lifecycle.State.RESUMED, hasReturned = false))
    }

    @Test
    fun `backgrounded or completed QR login stops polling`() {
        assertFalse(shouldPollNeteaseQrLogin(Lifecycle.State.CREATED, hasReturned = false))
        assertFalse(shouldPollNeteaseQrLogin(Lifecycle.State.STARTED, hasReturned = true))
    }
}
