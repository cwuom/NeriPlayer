package moe.ouom.neriplayer.platform.netease.auth

import moe.ouom.neriplayer.data.model.netease.auth.NeteaseAuthBundle
import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseAuthBundleCodecTest {

    @Test
    fun `json round trip keeps cookies and saved time`() {
        val bundle = NeteaseAuthBundle(cookies = linkedMapOf("MUSIC_U" to "token"), savedAt = 42L)

        assertEquals(bundle, NeteaseAuthBundle.fromJson(bundle.toJson()))
    }

    @Test
    fun `json decoding drops blank cookie names and tolerates missing fields`() {
        assertEquals(
            NeteaseAuthBundle(cookies = linkedMapOf("MUSIC_U" to "token", "__csrf" to ""), savedAt = 0L),
            NeteaseAuthBundle.fromJson("""{"cookies":{" ":"orphan","MUSIC_U":"token","__csrf":""}}""")
        )
        assertEquals(
            NeteaseAuthBundle(savedAt = 9L),
            NeteaseAuthBundle.fromJson("""{"cookies":"not-an-object","savedAt":9}""")
        )
    }

    @Test
    fun `unreadable json restores an empty bundle`() {
        assertEquals(NeteaseAuthBundle(), NeteaseAuthBundle.fromJson("not json"))
    }
}
