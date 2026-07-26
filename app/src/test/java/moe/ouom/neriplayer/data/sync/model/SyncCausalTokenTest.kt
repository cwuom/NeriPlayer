package moe.ouom.neriplayer.data.sync.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCausalTokenTest {
    @Test
    fun `normalization removes duplicates and sorts deterministically`() {
        val tokens = listOf(
            SyncCausalToken("device-b", 2L),
            SyncCausalToken("device-a", 2L),
            SyncCausalToken("device-a", 1L),
            SyncCausalToken("device-b", 2L)
        )

        assertEquals(
            listOf(
                SyncCausalToken("device-a", 1L),
                SyncCausalToken("device-a", 2L),
                SyncCausalToken("device-b", 2L)
            ),
            tokens.normalizedSyncCausalTokens()
        )
    }

    @Test
    fun `invalid tokens are constructible without throwing`() {
        // 解码侧不得在构造期抛异常，否则含非法 token 的报文会整份解码失败（restore 变砖）
        val blankDevice = SyncCausalToken(" ", 1L)
        val zeroCounter = SyncCausalToken("device", 0L)
        val bothInvalid = SyncCausalToken("", 0L)

        assertFalse(blankDevice.isValid())
        assertFalse(zeroCounter.isValid())
        assertFalse(bothInvalid.isValid())
        assertTrue(SyncCausalToken("device", 1L).isValid())
    }

    @Test
    fun `normalization drops invalid tokens and keeps valid ones`() {
        // 与桌面 normalize_sync_causal_tokens 对齐：counter<=0 或空白 deviceId 的 token 被丢弃
        val tokens = listOf(
            SyncCausalToken("device-a", 3L),
            SyncCausalToken("device-b", 0L),   // 非法：counter<=0
            SyncCausalToken(" ", 5L),          // 非法：空白 deviceId
            SyncCausalToken("device-c", -1L)   // 非法：counter<0
        )

        assertEquals(
            listOf(SyncCausalToken("device-a", 3L)),
            tokens.normalizedSyncCausalTokens()
        )
    }
}
