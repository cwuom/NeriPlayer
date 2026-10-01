package moe.ouom.neriplayer.data.model.sync

import moe.ouom.neriplayer.data.sync.model.SyncCausalToken

/**
 * 归一化 token 列表: 先丢弃非法 token (空 deviceId 或 counter<=0) , 再去重并按确定性顺序排序
 * 丢弃语义与桌面 normalize_sync_causal_tokens 一致, 确保损坏/第三方产出的非法 token 不会污染同步数据或使解码失败
 */
fun Iterable<SyncCausalToken>?.normalizedSyncCausalTokens(): List<SyncCausalToken> {
    return this
        ?.filter { it.isValid() }
        ?.distinct()
        ?.sortedWith(SyncCausalToken.DETERMINISTIC_COMPARATOR)
        .orEmpty()
}
