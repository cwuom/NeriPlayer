package moe.ouom.neriplayer.data.heartbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「跳过」计数的纯逻辑部分。
 *
 * 与 [DislikedSongRepositoryTest] 一样只测不依赖 Android Context 的部分：
 * 解码容错与上限裁剪。DataStore 的真实读写属于集成范畴。
 */
class HeartbeatSkipRepositoryTest {

    @Test
    fun `decode returns empty for null or blank payload`() {
        assertEquals(emptyMap<String, Int>(), decodeSkipCounts(null))
        assertEquals(emptyMap<String, Int>(), decodeSkipCounts(""))
        assertEquals(emptyMap<String, Int>(), decodeSkipCounts("   "))
    }

    @Test
    fun `decode returns empty for malformed json`() {
        // 文件被改坏时不能让整个心动模式崩掉，退化成「没有任何跳过记录」
        assertEquals(emptyMap<String, Int>(), decodeSkipCounts("{not json"))
        assertEquals(emptyMap<String, Int>(), decodeSkipCounts("""["netease:1"]"""))
    }

    @Test
    fun `decode keeps counts and drops blank keys`() {
        val decoded = decodeSkipCounts("""{"netease:1":3,"  ":9,"netease:2":1}""")

        assertEquals(mapOf("netease:1" to 3, "netease:2" to 1), decoded)
    }

    @Test
    fun `decode drops non positive counts`() {
        // 0 或负数没有意义，留着只会让「有记录」的判断变复杂
        val decoded = decodeSkipCounts("""{"netease:1":0,"netease:2":-4,"netease:3":2}""")

        assertEquals(mapOf("netease:3" to 2), decoded)
    }

    @Test
    fun `trim keeps entries below the limit untouched`() {
        val counts = mapOf("a" to 1, "b" to 2)

        assertEquals(counts, trimToLimit(counts))
    }

    @Test
    fun `trim drops the least skipped entries first`() {
        // 回归：早期若按插入顺序淘汰，跳过次数最多的曲目反而可能被先扔掉，
        // 等于把最有价值的负反馈丢了。
        val counts = buildMap {
            put("most", MAX_SKIP_COUNT)
            repeat(DEFAULT_SKIP_ENTRY_LIMIT) { index -> put("filler:$index", 1) }
        }

        val trimmed = trimToLimit(counts)

        assertEquals(DEFAULT_SKIP_ENTRY_LIMIT, trimmed.size)
        assertTrue("most" in trimmed)
        assertEquals(MAX_SKIP_COUNT, trimmed["most"])
    }

    @Test
    fun `trim keeps at most the entry limit`() {
        val counts = (1..(DEFAULT_SKIP_ENTRY_LIMIT + 40)).associate { "netease:$it" to 1 }

        assertEquals(DEFAULT_SKIP_ENTRY_LIMIT, trimToLimit(counts).size)
    }

    @Test
    fun `skip count cap is a bounded small number`() {
        // 计数封顶后重复惩罚已到下限，继续累加没有额外意义，
        // 但上限也不能小到「跳过一次就永远沉底」。
        assertTrue(MAX_SKIP_COUNT in 5..50)
        assertFalse(MAX_SKIP_COUNT <= 1)
    }
}
