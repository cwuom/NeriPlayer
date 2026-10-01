package moe.ouom.neriplayer.data.model.bilibili.skip

import kotlinx.serialization.Serializable

const val MAX_BILI_VIDEO_SKIP_INTERVALS = 100
const val MAX_BILI_VIDEO_SKIP_RULES = 2_000
const val MAX_BILI_VIDEO_SKIP_DRAFT_TEXT_LENGTH = 128

@Serializable
data class BiliVideoSkipTarget(
    val bvid: String,
    val cid: Long
) {
    fun normalizedOrNull(): BiliVideoSkipTarget? {
        val normalizedBvid = bvid.trim()
        return if (normalizedBvid.isEmpty() || cid <= 0L) {
            null
        } else {
            copy(bvid = normalizedBvid)
        }
    }

    fun stableKey(): String = "$bvid|$cid"
}

@Serializable
data class BiliVideoSkipInterval(
    val startMs: Long,
    val endMs: Long
)

@Serializable
data class BiliVideoSkipRule(
    val target: BiliVideoSkipTarget,
    val intervals: List<BiliVideoSkipInterval> = emptyList(),
    val modifiedAt: Long = 0L,
    val isDeleted: Boolean = false
)

@Serializable
data class BiliVideoSkipDraft(
    val target: BiliVideoSkipTarget,
    val startText: String = "",
    val endText: String = "",
    val modifiedAt: Long = 0L
)
