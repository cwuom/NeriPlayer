package moe.ouom.neriplayer.data.model.comments
/**
 * 评论正文里的一条内联表情。
 *
 * [placeholder] 是正文中实际出现的标记文本 (Bilibili 形如 `[doge]`, 网易云形如 `[大笑]`),
 * [url] 是平台官方 CDN 上的表情图片地址; UI 层按标记把正文切段后内联渲染成小图。
 *
 * 平台 JSON 只在 Mapper 层解析 (§63/§64), 这里是统一模型, UI 不接触任何平台字段。
 */
data class CommentEmote(
    val placeholder: String,
    val url: String
)
