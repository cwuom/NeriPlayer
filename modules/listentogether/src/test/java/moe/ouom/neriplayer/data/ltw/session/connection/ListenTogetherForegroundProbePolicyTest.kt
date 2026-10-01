package moe.ouom.neriplayer.data.ltw.session.connection

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import org.junit.Assert.assertFalse
import org.junit.Test

class ListenTogetherForegroundProbePolicyTest {
    @Test
    fun `foreground probe cannot reconnect without an expected active room`() {
        assertFalse(shouldReconnectListenTogetherForegroundSocket(true, ListenTogetherConnectionState.CONNECTED, null, "room", 0L, 1L))
        assertFalse(shouldReconnectListenTogetherForegroundSocket(true, ListenTogetherConnectionState.CONNECTED, " ", "room", 0L, 1L))
    }
}
