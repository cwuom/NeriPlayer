package moe.ouom.neriplayer.core.api.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeWebPoTokenCacheTest {

    private val nowMs = 1_700_000_000_000L

    @Test
    fun sessionKeySeparatesIdentitiesAndExitNodes() {
        val base = buildYouTubePoTokenSessionKey("host-a", "fingerprint-a")

        assertEquals(base, buildYouTubePoTokenSessionKey("host-a", "fingerprint-a"))
        // 换出口或换账号都必须重铸, 复用会直接被服务端拒
        assertNotEquals(base, buildYouTubePoTokenSessionKey("host-b", "fingerprint-a"))
        assertNotEquals(base, buildYouTubePoTokenSessionKey("host-a", "fingerprint-b"))
    }

    @Test
    fun returnsTheEntryWhileItIsStillValid() {
        val tokens = mapOf("key" to CachedWebPoToken(token = "tok", expiresAtMs = nowMs + 1L))

        val selected = selectUsableYouTubePoToken("key", tokens, nowMs)

        assertEquals("key", selected?.first)
        assertEquals("tok", selected?.second?.token)
    }

    @Test
    fun refusesAnEntryThatHasExpired() {
        val tokens = mapOf("key" to CachedWebPoToken(token = "tok", expiresAtMs = nowMs))

        assertNull(selectUsableYouTubePoToken("key", tokens, nowMs))
    }

    @Test
    fun refusesAnEntryWhoseTokenIsEmpty() {
        val tokens = mapOf("key" to CachedWebPoToken(token = "", expiresAtMs = nowMs + 60_000L))

        // 空 token 拿去请求只会 403, 不如当没缓存去重铸
        assertNull(selectUsableYouTubePoToken("key", tokens, nowMs))
    }

    @Test
    fun refusesLookupsWithNothingToLookUp() {
        val tokens = mapOf("key" to CachedWebPoToken(token = "tok", expiresAtMs = nowMs + 60_000L))

        assertNull(selectUsableYouTubePoToken(null, tokens, nowMs))
        assertNull(selectUsableYouTubePoToken("", tokens, nowMs))
        assertNull(selectUsableYouTubePoToken("missing", tokens, nowMs))
    }

    @Test
    fun refusesAnIndexPointingAtAnEvictedEntry() {
        // 索引还在但 token 已经被淘汰时, 必须回落到正常铸造路径
        assertNull(selectUsableYouTubePoToken("evicted", emptyMap(), nowMs))
    }
}
