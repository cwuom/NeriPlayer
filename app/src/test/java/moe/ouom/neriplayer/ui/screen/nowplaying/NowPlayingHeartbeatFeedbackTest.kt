package moe.ouom.neriplayer.ui.screen.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingHeartbeatFeedbackTest {

    @Test
    fun `login required is announced when nothing else happened`() {
        assertEquals(
            HeartbeatNotice.LOGIN_REQUIRED,
            pickHeartbeatNotice(roomRejected = false, loginRequired = true, roomAutoOff = false),
        )
    }

    @Test
    fun `the user's own rejected action wins over passive changes`() {
        assertEquals(
            HeartbeatNotice.ROOM_REJECTED,
            pickHeartbeatNotice(roomRejected = true, loginRequired = true, roomAutoOff = true),
        )
        assertEquals(
            HeartbeatNotice.LOGIN_REQUIRED,
            pickHeartbeatNotice(roomRejected = false, loginRequired = true, roomAutoOff = true),
        )
    }

    @Test
    fun `room auto off is announced on its own and nothing otherwise`() {
        assertEquals(
            HeartbeatNotice.ROOM_AUTO_OFF,
            pickHeartbeatNotice(roomRejected = false, loginRequired = false, roomAutoOff = true),
        )
        assertNull(pickHeartbeatNotice(roomRejected = false, loginRequired = false, roomAutoOff = false))
    }
}
