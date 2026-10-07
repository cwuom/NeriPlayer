package moe.ouom.neriplayer.data.model.ltw.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTogetherInviteTest {

    @Test
    fun `invite signatures join every field with missing values left empty`() {
        assertEquals("room||||false", ListenTogetherInvite(roomId = "room").signature)
        assertEquals(
            "room|neri|https://ltw.example.invalid|secret|true",
            ListenTogetherInvite(
                roomId = "room",
                inviterNickname = "neri",
                baseUrl = "https://ltw.example.invalid",
                joinSecret = "secret",
                hasInvalidBaseUrl = true
            ).signature
        )
    }
}
