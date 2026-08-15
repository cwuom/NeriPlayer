@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Parcelize
@Serializable
data class SyncCausalToken(
    // proto3/桌面对默认值(空 deviceId, counter=0)会省略 tag, 解码侧必须提供默认值
    // 且不能在构造期硬校验, 否则含非法 token 的报文会整份解码失败 (MissingFieldException / IllegalArgumentException) , 导致 restore 变砖
    @ProtoNumber(1) val deviceId: String = "",
    @ProtoNumber(2) val counter: Long = 0L,
    // 可选的闭区间终点, 0 表示旧格式单点令牌
    @ProtoNumber(3) val counterEnd: Long = 0L
) : Parcelable {
    // 合法性判定与桌面 normalize_sync_causal_tokens 对齐; 非法 token 在归一化阶段被丢弃而非在构造期抛异常
    fun isValid(): Boolean = deviceId.isNotBlank() &&
        counter > 0L &&
        (counterEnd == 0L || counterEnd >= counter)

    fun endCounter(): Long = if (counterEnd == 0L) counter else counterEnd

    fun contains(other: SyncCausalToken): Boolean {
        return isValid() && other.isValid() &&
            deviceId == other.deviceId &&
            counter <= other.counter &&
            endCounter() >= other.endCounter()
    }

    fun overlaps(other: SyncCausalToken): Boolean {
        return isValid() && other.isValid() &&
            deviceId == other.deviceId &&
            counter <= other.endCounter() &&
            other.counter <= endCounter()
    }

    companion object {
        val DETERMINISTIC_COMPARATOR: Comparator<SyncCausalToken> =
            compareBy<SyncCausalToken>(SyncCausalToken::deviceId)
                .thenBy(SyncCausalToken::counter)
                .thenBy(SyncCausalToken::endCounter)
    }
}

internal const val MAX_CAUSAL_TOKEN_RANGES_PER_ENTITY = 64

/**
 * 归一化 token 列表: 先丢弃非法 token (空 deviceId 或 counter<=0) , 再去重并按确定性顺序排序
 * 丢弃语义与桌面 normalize_sync_causal_tokens 一致, 确保损坏/第三方产出的非法 token 不会污染同步数据或使解码失败
 */
internal fun Iterable<SyncCausalToken>?.normalizedSyncCausalTokens(): List<SyncCausalToken> {
    return this
        ?.filter { it.isValid() }
        ?.map { token ->
            token.copy(counterEnd = token.endCounter().takeIf { it > token.counter } ?: 0L)
        }
        ?.sortedWith(SyncCausalToken.DETERMINISTIC_COMPARATOR)
        ?.distinct()
        .orEmpty()
}

internal fun Iterable<SyncCausalToken>?.compactedSyncCausalTokens(): List<SyncCausalToken> {
    val sorted = normalizedSyncCausalTokens()
    if (sorted.isEmpty()) return emptyList()

    val compacted = ArrayList<SyncCausalToken>(sorted.size)
    sorted.forEach { candidate ->
        val previous = compacted.lastOrNull()
        if (previous != null &&
            previous.deviceId == candidate.deviceId &&
            candidate.counter <= previous.endCounter().let { end ->
                if (end == Long.MAX_VALUE) Long.MAX_VALUE else end + 1L
            }
        ) {
            val end = maxOf(previous.endCounter(), candidate.endCounter())
            compacted[compacted.lastIndex] = previous.copy(
                counterEnd = end.takeIf { it > previous.counter } ?: 0L
            )
        } else {
            compacted += candidate
        }
    }
    return compacted
}

internal fun requireCausalTokenRangeCapacity(
    tokens: Iterable<SyncCausalToken>,
    maxRanges: Int = MAX_CAUSAL_TOKEN_RANGES_PER_ENTITY
) {
    require(maxRanges >= 0) { "Causal token range capacity must not be negative" }
    val normalized = tokens.compactedSyncCausalTokens()
    require(normalized.size <= maxRanges) {
        "Causal token range capacity exceeded: ${normalized.size} > $maxRanges"
    }
}
