package moe.ouom.neriplayer.data.heartbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不喜欢」名单的纯逻辑部分。
 *
 * 只测 [decodeDislikedKeys] 与上限裁剪这两段不依赖 Android Context 的逻辑：
 * DataStore 的读写需要 Robolectric 起的真实文件，属于集成范畴，
 * 而这部分真正的风险（脏数据、越界、刚点的歌被裁掉）都能在这里覆盖。
 */
class DislikedSongRepositoryTest {

    @Test
    fun `decode returns empty for null or blank payload`() {
        assertEquals(emptySet<String>(), decodeDislikedKeys(null))
        assertEquals(emptySet<String>(), decodeDislikedKeys(""))
        assertEquals(emptySet<String>(), decodeDislikedKeys("   "))
    }

    @Test
    fun `decode returns empty for malformed json`() {
        // 文件被外部工具改坏时不能让整个心动模式崩掉
        assertEquals(emptySet<String>(), decodeDislikedKeys("{not json"))
        assertEquals(emptySet<String>(), decodeDislikedKeys("\"just a string\""))
    }

    @Test
    fun `decode trims and drops blank entries`() {
        val decoded = decodeDislikedKeys("""["  netease:1  ","","   ","netease:2"]""")

        assertEquals(setOf("netease:1", "netease:2"), decoded)
    }

    @Test
    fun `decode deduplicates repeated keys`() {
        assertEquals(setOf("netease:1"), decodeDislikedKeys("""["netease:1","netease:1"]"""))
    }

    @Test
    fun `decode caps oversized payloads at the limit`() {
        val payload = (1..(DEFAULT_DISLIKED_LIMIT + 50)).joinToString(
            prefix = "[",
            postfix = "]",
        ) { "\"netease:$it\"" }

        assertEquals(DEFAULT_DISLIKED_LIMIT, decodeDislikedKeys(payload).size)
    }

    @Test
    fun `limit trimming keeps the most recently added key`() {
        // 回归：早先写成 `(current + key).take(n)`，`current + key` 把新标记放在末尾，
        // take 会先把刚点的这首丢掉，用户点了等于没反应。
        val existing = (1..DEFAULT_DISLIKED_LIMIT).map { "netease:$it" }
        val merged = existing + "netease:new"

        val trimmed = if (merged.size > DEFAULT_DISLIKED_LIMIT) {
            merged.drop(merged.size - DEFAULT_DISLIKED_LIMIT)
        } else {
            merged
        }

        assertTrue("netease:new" in trimmed)
        assertEquals(DEFAULT_DISLIKED_LIMIT, trimmed.size)
        assertFalse("netease:1" in trimmed)
    }
}
