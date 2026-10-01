package moe.ouom.neriplayer.data.ltw.invite

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ListenTogetherInviteContractTest {
    @Test
    fun `invite parser rejects other schemes hosts routes and missing required query`() {
        for (url in listOf(
            "https://listen-together/join?roomId=ABC234&secret=secret",
            "neriplayer://other/join?roomId=ABC234&secret=secret",
            "neriplayer://listen-together/leave?roomId=ABC234&secret=secret",
            "neriplayer://listen-together?roomId=ABC234&secret=secret",
            "neriplayer://listen-together/join",
            "neriplayer://listen-together/join?secret=secret"
        )) assertNull(parseListenTogetherInvite(url))
        assertNull(parseListenTogetherInvite(null as Uri?))
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn("neriplayer://listen-together/join?roomId=ABC234&secret=secret")
        assertEquals("ABC234", parseListenTogetherInvite(uri)?.roomId)
    }

    @Test
    fun `invite query tolerates unknown flags blank names and invalid optional nicknames`() {
        val prefix = "neriplayer://listen-together/join?roomId=ABC234&secret=secret&flag&=value&%20=value&foo=bar"
        for (nickname in listOf("", "%20", "Invalid_Name")) {
            val invite = requireNotNull(parseListenTogetherInvite("$prefix&inviter=$nickname"))
            assertNull(invite.inviterNickname)
            assertEquals("secret", invite.joinSecret)
        }
        assertEquals("Tester", parseListenTogetherInvite("$prefix&inviter=%20Tester%20")?.inviterNickname)
    }

    @Test
    fun `invite builder normalizes required identity and omits blank or default server`() {
        val parameters = mutableMapOf<String, String>()
        mockBuilders(parameters).use {
            for (baseUrl in listOf(null, " ", DEFAULT_LISTEN_TOGETHER_BASE_URL)) {
                parameters.clear()
                assertEquals("built", buildListenTogetherInviteUri(" abc234 ", " Tester ", baseUrl, " secret ") { "invalid:${it.messageResId}" })
                assertEquals(mapOf("roomId" to "ABC234", "inviter" to "Tester", "secret" to "secret"), parameters)
            }
            parameters.clear()
            buildListenTogetherInviteUri("ABC234", " ", "https://example.test/", "secret") { "invalid:${it.messageResId}" }
            assertEquals("https://example.test", parameters["baseUrl"])
            assertFalse(parameters.containsKey("inviter"))
            buildListenTogetherInviteUri("ABC234", null, null, "secret") { "invalid:${it.messageResId}" }
        }
    }

    @Test
    fun `invite builder exposes validation failure through explicit host formatter`() {
        mockBuilders(mutableMapOf()).use {
            for (args in listOf(Triple("INVALID", "Tester", "secret"), Triple("ABC234", "Bad_Name", "secret"), Triple("ABC234", "Tester", " "))) {
                val error = assertThrows(IllegalStateException::class.java) {
                    buildListenTogetherInviteUri(args.first, args.second, joinSecret = args.third) { "validation rejected" }
                }
                assertEquals("validation rejected", error.message)
            }
        }
    }

    private fun mockBuilders(parameters: MutableMap<String, String>): org.mockito.MockedConstruction<Uri.Builder> {
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn("built")
        return mockConstruction(Uri.Builder::class.java) { builder, _ ->
            `when`(builder.scheme(anyString())).thenReturn(builder)
            `when`(builder.authority(anyString())).thenReturn(builder)
            `when`(builder.appendPath(anyString())).thenReturn(builder)
            `when`(builder.appendQueryParameter(anyString(), anyString())).thenAnswer {
                parameters[it.getArgument(0)] = it.getArgument(1)
                builder
            }
            `when`(builder.build()).thenReturn(uri)
        }
    }
}
