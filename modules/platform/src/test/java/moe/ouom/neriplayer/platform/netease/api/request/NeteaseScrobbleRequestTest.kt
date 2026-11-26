package moe.ouom.neriplayer.platform.netease.api.request

import moe.ouom.neriplayer.common.json.JsonUtil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseScrobbleRequestTest {
    @Test
    fun `scrobble sends startplay then play as separate single entry logs`() {
        val logs = buildNeteaseScrobbleLogs(songId = 186016L, listenedSec = 213L).map(::JSONArray)

        assertEquals(2, logs.size)
        assertTrue(logs.all { it.length() == 1 })
        val startPlay = logs[0].getJSONObject(0)
        val play = logs[1].getJSONObject(0)
        assertEquals("startplay", startPlay.getString("action"))
        assertEquals("186016", startPlay.getJSONObject("json").getString("id"))
        assertEquals("play", play.getString("action"))
        val json = play.getJSONObject("json")
        assertEquals("186016", json.getString("id"))
        assertEquals(213, json.getInt("time"))
        assertEquals("playend", json.getString("end"))
        assertEquals("song", json.getString("type"))
        assertEquals("list", json.getString("source"))
    }

    @Test
    fun `scrobble never reports zero seconds or invalid songs`() {
        val play = JSONArray(buildNeteaseScrobbleLogs(1L, 0L)[1]).getJSONObject(0).getJSONObject("json")

        assertEquals(1, play.getInt("time"))
        assertThrows(IllegalArgumentException::class.java) { buildNeteaseScrobbleLogs(0L, 30L) }
    }

    @Test
    fun `header identifies as the desktop client and mirrors into the cookie`() {
        val header = buildNeteaseScrobbleHeader(
            identity = NeteaseScrobbleIdentity(musicU = "a b+c", csrf = "token", deviceId = "DEVICE"),
            nowMs = 1_700_000_000_123L,
            requestSuffix = 7
        )

        assertEquals("osx", header["os"])
        assertEquals("1700000000", header["buildver"])
        assertEquals("1700000000123_0007", header["requestId"])
        assertEquals("a b+c", header["MUSIC_U"])
        val cookie = neteaseScrobbleCookieHeader(header)
        assertTrue(cookie.contains("os=osx"))
        assertTrue(cookie.contains("MUSIC_U=a%20b%2Bc"))
        assertTrue(cookie.contains("__csrf=token"))
        assertFalse(cookie.contains("os=pc"))
    }

    @Test
    fun `payload nests the header object next to the log string`() {
        val header = mapOf("os" to "osx")
        val payload = JSONObject(JsonUtil.toJson(buildNeteaseScrobblePayload("[]", header)))

        assertEquals("[]", payload.getString("logs"))
        assertFalse(payload.getBoolean("e_r"))
        assertEquals("osx", payload.getJSONObject("header").getString("os"))
    }
}
