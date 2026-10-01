package moe.ouom.neriplayer.data.ltw

import moe.ouom.neriplayer.data.ltw.invite.configuredListenTogetherBaseUrlOrNull
import moe.ouom.neriplayer.data.ltw.invite.configuredListenTogetherInviteBaseUrlOrNull
import moe.ouom.neriplayer.data.ltw.invite.resolveListenTogetherBaseUrl
import moe.ouom.neriplayer.data.ltw.invite.resolveListenTogetherInviteJoinBaseUrl
import moe.ouom.neriplayer.data.ltw.session.state.normalized
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenTogetherBaseUrlTest {

    @Test
    fun `invite join falls back through normalized saved server to bundled default`() {
        val invite = ListenTogetherInvite(roomId = "room", baseUrl = "invalid")
        assertEquals("https://saved.example.com", resolveListenTogetherInviteJoinBaseUrl(invite, "invalid", "https://saved.example.com/"))
        assertEquals("https://neriplayer.hancat.work", resolveListenTogetherInviteJoinBaseUrl(invite, null, "invalid"))
    }

    @Test
    fun `configured base url keeps valid custom server`() {
        assertEquals(
            "https://example.com",
            configuredListenTogetherBaseUrlOrNull(" https://example.com/ ")
        )
    }

    @Test
    fun `configured base url keeps valid cleartext custom server`() {
        assertEquals(
            "http://192.168.1.10:8787",
            configuredListenTogetherBaseUrlOrNull(" http://192.168.1.10:8787/ ")
        )
    }

    @Test
    fun `configured base url rejects invalid custom server`() {
        assertNull(configuredListenTogetherBaseUrlOrNull("example.com"))
    }

    @Test
    fun `resolve base url falls back to default for blank input`() {
        assertEquals(
            "https://neriplayer.hancat.work",
            resolveListenTogetherBaseUrl(" ")
        )
    }

    @Test
    fun `invite join base url prefers invite server over saved server`() {
        val invite = ListenTogetherInvite(
            roomId = "GTV42X",
            baseUrl = "https://neriplayerltw.cwuomcwuom.workers.dev"
        )

        assertEquals(
            "https://neriplayerltw.cwuomcwuom.workers.dev",
            resolveListenTogetherInviteJoinBaseUrl(
                invite = invite,
                savedBaseUrlInput = "https://saved.example.com",
                savedBaseUrl = "https://normalized.example.com"
            )
        )
    }

    @Test
    fun `invite join base url falls back to saved server when invite has none`() {
        val invite = ListenTogetherInvite(roomId = "GTV42X")

        assertEquals(
            "https://saved.example.com",
            resolveListenTogetherInviteJoinBaseUrl(
                invite = invite,
                savedBaseUrlInput = "https://saved.example.com/",
                savedBaseUrl = null
            )
        )
    }

    @Test
    fun `invite base url helper enforces https only`() {
        assertEquals(
            "https://example.com",
            configuredListenTogetherInviteBaseUrlOrNull(" https://example.com/ ")
        )
        assertNull(configuredListenTogetherInviteBaseUrlOrNull("http://192.168.1.10:8787"))
        assertNull(configuredListenTogetherInviteBaseUrlOrNull("example.com"))
    }
}
