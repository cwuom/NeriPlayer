package moe.ouom.neriplayer.ui.screen.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingHeartbeatFeedbackTest {

    @Test
    fun `the user's rejected action wins over the automatic turn off`() {
        assertEquals(HeartbeatNotice.ROOM_REJECTED, pickHeartbeatNotice(roomRejected = true, roomAutoOff = true))
    }

    @Test
    fun `room auto off is announced on its own and nothing otherwise`() {
        assertEquals(HeartbeatNotice.ROOM_AUTO_OFF, pickHeartbeatNotice(roomRejected = false, roomAutoOff = true))
        assertNull(pickHeartbeatNotice(roomRejected = false, roomAutoOff = false))
    }
}
