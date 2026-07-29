package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSessionState
import moe.ouom.neriplayer.listentogether.session.resolveReusableListenTogetherMembershipCredential
import moe.ouom.neriplayer.listentogether.session.toMembershipCredentialOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ListenTogetherMembershipCredentialTest {

    @Test
    fun `retained credential is reused after leaving the same room`() {
        val connectedSession = ListenTogetherSessionState(
            baseUrl = "https://listen.example.com",
            roomId = "ROOM12",
            userUuid = "member-uuid",
            token = "member-token",
            memberSecret = "member-secret",
            joinSecret = "join-secret"
        )
        val retained = connectedSession.toMembershipCredentialOrNull()
        val leftSession = ListenTogetherSessionState(
            baseUrl = "https://listen.example.com",
            userUuid = "member-uuid"
        )

        val reusable = resolveReusableListenTogetherMembershipCredential(
            activeSession = leftSession,
            retainedCredential = retained,
            baseUrl = "https://listen.example.com",
            roomId = "ROOM12",
            userUuid = "member-uuid"
        )

        assertSame(retained, reusable)
        assertEquals("member-secret", reusable?.memberSecret)
        assertEquals("member-token", reusable?.token)
    }

    @Test
    fun `retained credential is not reused for another room or identity`() {
        val retained = ListenTogetherSessionState(
            baseUrl = "https://listen.example.com",
            roomId = "ROOM12",
            userUuid = "member-uuid",
            memberSecret = "member-secret"
        ).toMembershipCredentialOrNull()

        val reusable = resolveReusableListenTogetherMembershipCredential(
            activeSession = ListenTogetherSessionState(),
            retainedCredential = retained,
            baseUrl = "https://listen.example.com",
            roomId = "OTHER12",
            userUuid = "member-uuid"
        )

        assertNull(reusable)
    }
}
