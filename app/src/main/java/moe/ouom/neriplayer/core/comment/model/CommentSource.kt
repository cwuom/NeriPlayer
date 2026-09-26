package moe.ouom.neriplayer.core.comment.model

/**
 * 歌曲对应的评论逻辑音源。
 *
 * @param resourceId 网易云为歌曲 id, Bilibili 也可能是历史打包播放 id
 * @param secondaryId Bilibili 的 bvid (若已知), 网易云为 null
 */
data class CommentSource(
    val platform: CommentPlatform,
    val resourceId: Long,
    val secondaryId: String? = null,
    /** 保留 Bilibili cid，历史打包 id 需要按分 P 校验 */
    val subResourceId: Long? = null,
    /** 没有 BV 号或 cid 时，播放侧还需要按标题匹配分 P */
    val resourceTitle: String? = null,
    /** 保留 audioId 是否明确提供，沿用播放侧的历史 id 判定 */
    val hasExplicitResourceId: Boolean = false
) {
    /** 逻辑来源键，Bilibili 分页缓存另按确认后的 aid 绑定 */
    val key: String
        get() = "${platform.name}:$resourceId"
}
