package moe.ouom.neriplayer.core.comment

import moe.ouom.neriplayer.core.comment.model.CommentError

/**
 * 平台评论接口返回的业务错误 (HTTP 成功但业务 code != 0)。
 *
 * 只在 comment 层内部使用, 由 Mapper 抛出、Repository 透传、ViewModel 转换为 [CommentError]。
 */
internal class CommentApiException(
    val code: Int,
    val reason: CommentError,
    message: String
) : Exception(message)
