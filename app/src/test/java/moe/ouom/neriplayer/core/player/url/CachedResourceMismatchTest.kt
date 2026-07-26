package moe.ouom.neriplayer.core.player.url

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：同一 videoId 不同轮次可能解析到不同表示且共用缓存键，
 * 只判缓存偏小的方向时反向差值为负会直接放行，新旧字节混写后 seek 会崩在
 * MatroskaExtractor 的 ArrayIndexOutOfBoundsException
 */
class CachedResourceMismatchTest {

    @Test
    fun `preview fragment smaller than real resource is replaced`() {
        // 缓存是预览片段
        assertTrue(shouldReplaceCachedPreviewResource(cachedContentLength = 1_000_000, expectedContentLength = 50_000_000))
    }

    @Test
    fun `cached larger representation from a previous session is replaced`() {
        // 真机数值，上次会话缓存 124615180，本次解析 72498309
        assertTrue(
            shouldReplaceCachedPreviewResource(
                cachedContentLength = 124_615_180,
                expectedContentLength = 72_498_309
            )
        )
    }

    @Test
    fun `identical length is kept`() {
        assertFalse(shouldReplaceCachedPreviewResource(72_498_309, 72_498_309))
    }

    @Test
    fun `small difference is tolerated in both directions`() {
        // 小于 512KB 的差异不失效，避免误伤正常抖动
        assertFalse(shouldReplaceCachedPreviewResource(50_000_000, 50_100_000))
        assertFalse(shouldReplaceCachedPreviewResource(50_100_000, 50_000_000))
    }

    @Test
    fun `difference within 15 percent is tolerated in both directions`() {
        // 差值超过 512KB 但比例仍在 85% 以上
        assertFalse(shouldReplaceCachedPreviewResource(95_000_000, 100_000_000))
        assertFalse(shouldReplaceCachedPreviewResource(100_000_000, 95_000_000))
    }

    @Test
    fun `unknown lengths never trigger invalidation`() {
        assertFalse(shouldReplaceCachedPreviewResource(0, 50_000_000))
        assertFalse(shouldReplaceCachedPreviewResource(50_000_000, 0))
        assertFalse(shouldReplaceCachedPreviewResource(-1, -1))
    }

    @Test
    fun `both directions are symmetric`() {
        val a = 124_615_180L
        val b = 72_498_309L
        assertTrue(
            "判定必须对称，否则取决于哪一侧先落盘",
            shouldReplaceCachedPreviewResource(a, b) == shouldReplaceCachedPreviewResource(b, a)
        )
    }
}
