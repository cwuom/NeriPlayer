package moe.ouom.neriplayer.data.model.comments
/**
 * 评论里的一张配图 (Bilibili `content.pictures`)。
 *
 * [width] / [height] 是平台给出的原始像素尺寸, 未知时为 0; UI 用 [aspectRatio] 计算占位比例,
 * 尺寸不可信时回退 1:1, 避免图片加载完成前的高度跳动。
 */
data class CommentImage(
    val url: String,
    val width: Int = 0,
    val height: Int = 0
) {
    /** 宽高比 (宽 / 高); 尺寸不可信时回退 1f。 */
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f
}
