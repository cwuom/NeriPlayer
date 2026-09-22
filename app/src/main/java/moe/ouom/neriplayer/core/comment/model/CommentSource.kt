package moe.ouom.neriplayer.core.comment.model

/**
 * 歌曲对应的评论逻辑音源。
 *
 * @param resourceId 网易云为歌曲 id, Bilibili 为 aid (视频 av 号)
 * @param secondaryId Bilibili 的 bvid (若已知), 网易云为 null
 */
data class CommentSource(
    val platform: CommentPlatform,
    val resourceId: Long,
    val secondaryId: String? = null
) {
    /**
     * 请求 / 缓存绑定键, 形如 `NETEASE:123` 或 `BILIBILI:456`。
     *
     * 每个异步结果都用它校验是否仍属于当前歌曲。
     */
    val key: String
        get() = "${platform.name}:$resourceId"
}
