package moe.ouom.neriplayer.core.api.youtube

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：player.js 换版后 NewPipe 抽错 n 函数，求值得到 JS 对象被拼成
 * n=[object Object]，非空且已改变所以骗过朴素校验，还因为比 EJS 快而赢下竞速，
 * 导致播放与下载全线 403
 */
class YouTubeCipherTokenValidationTest {

    @Test
    fun `rejects js object stringification`() {
        assertFalse(isPlausibleCipherToken("[object Object]"))
        assertFalse(isPlausibleCipherToken("[object object]"))
    }

    @Test
    fun `rejects js undefined and null literals`() {
        assertFalse(isPlausibleCipherToken("undefined"))
        assertFalse(isPlausibleCipherToken("null"))
        assertFalse(isPlausibleCipherToken("NaN"))
        assertFalse(isPlausibleCipherToken("true"))
        assertFalse(isPlausibleCipherToken("false"))
    }

    @Test
    fun `rejects blank and null input`() {
        assertFalse(isPlausibleCipherToken(null))
        assertFalse(isPlausibleCipherToken(""))
        assertFalse(isPlausibleCipherToken("   "))
    }

    @Test
    fun `rejects tokens carrying url structure or whitespace`() {
        // 抽错函数时可能返回代码片段或含分隔符的串
        assertFalse(isPlausibleCipherToken("abc def"))
        assertFalse(isPlausibleCipherToken("abc&n=def"))
        assertFalse(isPlausibleCipherToken("https://example.invalid/x"))
        assertFalse(isPlausibleCipherToken("function(a){return a}"))
    }

    @Test
    fun `accepts real world throttling and signature tokens`() {
        // 取自真实直链的参数形态
        assertTrue(isPlausibleCipherToken("e23x5TxISViAnH4mKA4f1-oX"))
        assertTrue(isPlausibleCipherToken("wPqvKAV1z0OJVXwl"))
        assertTrue(
            isPlausibleCipherToken(
                "AE0s2JYwRAIgNkJW-OcuZ_XxUjK2LUayn_r011cFI-UrUZtbxBDZO8wCIF13PlQnmj4CjJ_XDWU4"
            )
        )
    }

    @Test
    fun `detects implausible n parameter inside resolved url`() {
        val poisoned = "https://rr5.googlevideo.com/videoplayback?expire=1785098966" +
            "&itag=140&n=[object Object]&sig=abc"
        assertFalse(hasPlausibleThrottlingParameter(poisoned))
    }

    @Test
    fun `accepts healthy n parameter inside resolved url`() {
        val healthy = "https://rr5.googlevideo.com/videoplayback?expire=1785098966" +
            "&itag=140&n=wPqvKAV1z0OJVXwl&sig=abc"
        assertTrue(hasPlausibleThrottlingParameter(healthy))
    }

    @Test
    fun `url without n parameter is not treated as resolved`() {
        val missing = "https://rr5.googlevideo.com/videoplayback?expire=1785098966&itag=140"
        assertFalse(hasPlausibleThrottlingParameter(missing))
    }

    @Test
    fun `hasFailedChallenge reports completed candidate without value`() = runBlocking {
        val failed = CompletableDeferred<ChallengeCandidateResult<String>>().apply {
            complete(ChallengeCandidateResult(source = "NEWPIPE", value = null, elapsedMs = 12))
        }
        assertTrue(failed.hasFailedChallenge())
    }

    @Test
    fun `hasFailedChallenge ignores successful candidate`() = runBlocking {
        val ok = CompletableDeferred<ChallengeCandidateResult<String>>().apply {
            complete(ChallengeCandidateResult(source = "EJS_FALLBACK", value = "abc", elapsedMs = 780))
        }
        assertFalse(ok.hasFailedChallenge())
    }

    @Test
    fun `hasFailedChallenge ignores cancelled candidate`() = runBlocking {
        // 竞速失败方会被取消，不该误记为解析失败
        val cancelled = CompletableDeferred<ChallengeCandidateResult<String>>().apply { cancel() }
        assertFalse(cancelled.hasFailedChallenge())
    }

    @Test
    fun `hasFailedChallenge ignores pending candidate`() = runBlocking {
        val pending = CompletableDeferred<ChallengeCandidateResult<String>>()
        assertFalse(pending.hasFailedChallenge())
        pending.cancel()
    }
}
