package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.invite.ListenTogetherInvite
import moe.ouom.neriplayer.listentogether.invite.configuredListenTogetherBaseUrlOrNull
import moe.ouom.neriplayer.listentogether.invite.configuredListenTogetherInviteBaseUrlOrNull
import moe.ouom.neriplayer.listentogether.invite.parseListenTogetherInvite
import moe.ouom.neriplayer.listentogether.invite.resolveListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.invite.resolveListenTogetherInviteJoinBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherBaseUrlTest {

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
    fun `invite parser normalizes valid custom server`() {
        val invite = parseListenTogetherInvite(
            "neriplayer://listen-together/join?roomId=P8BAEV&baseUrl=https%3A%2F%2Fexample.com%2F&secret=secret-value"
        )

        assertEquals("https://example.com", invite?.baseUrl)
        assertFalse(invite?.hasInvalidBaseUrl ?: true)
    }

    @Test
    fun `invite parser keeps encoded worker base url from shared text`() {
        val invite = parseListenTogetherInvite(
            "Neri5458C5 邀请你在 NeriPlayer 中加入房间 GTV42X。复制后打开 App 加入。\n" +
                "neriplayer://listen-together/join?roomId=GTV42X&inviter=Neri5458C5&baseUrl=https%3A%2F%2Fneriplayerltw.cwuomcwuom.workers.dev&secret=secret-value"
        )

        assertEquals("GTV42X", invite?.roomId)
        assertEquals("Neri5458C5", invite?.inviterNickname)
        assertEquals("https://neriplayerltw.cwuomcwuom.workers.dev", invite?.baseUrl)
        assertFalse(invite?.hasInvalidBaseUrl ?: true)
    }

    @Test
    fun `invite parser carries room join secret`() {
        val invite = parseListenTogetherInvite(
            "neriplayer://listen-together/join?roomId=GTV42X&secret=secret-value"
        )

        assertEquals("secret-value", invite?.joinSecret)
    }

    @Test
    fun `invite parser rejects missing room join secret`() {
        assertNull(
            parseListenTogetherInvite("neriplayer://listen-together/join?roomId=GTV42X")
        )
    }

    @Test
    fun `clipboard text without an invitation cannot be parsed for a room join`() {
        assertNull(parseListenTogetherInvite(null as String?))
        assertNull(
            parseListenTogetherInvite("roomId=GTV42X&secret=secret-value")
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
    fun `invite parser decodes encoded inviter`() {
        val invite = parseListenTogetherInvite(
            "neriplayer://listen-together/join?roomId=P8BAEV&inviter=Neri%E7%8C%AB&secret=secret-value"
        )

        assertEquals("Neri猫", invite?.inviterNickname)
    }

    @Test
    fun `invite parser rejects malformed query encoding without throwing`() {
        assertNull(
            parseListenTogetherInvite("neriplayer://listen-together/join?roomId=%")
        )
    }

    @Test
    fun `invite parser flags invalid custom server`() {
        val invite = parseListenTogetherInvite(
            "neriplayer://listen-together/join?roomId=P8BAEV&baseUrl=example.com&secret=secret-value"
        )

        assertNull(invite?.baseUrl)
        assertTrue(invite?.hasInvalidBaseUrl == true)
    }

    @Test
    fun `invite parser rejects cleartext http server`() {
        val invite = parseListenTogetherInvite(
            "neriplayer://listen-together/join?roomId=P8BAEV&baseUrl=http%3A%2F%2F192.168.1.10%3A8787%2F&secret=secret-value"
        )

        assertNull(invite?.baseUrl)
        assertTrue(invite?.hasInvalidBaseUrl == true)
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
