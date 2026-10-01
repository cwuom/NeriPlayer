package moe.ouom.neriplayer.platform.comments

import moe.ouom.neriplayer.data.model.comments.CommentError

/**
 * 平台评论接口返回的业务错误 (HTTP 成功但业务 code != 0)。
 *
 * 只在 comment 层内部使用, 由 Mapper 抛出、Repository 透传、ViewModel 转换为 [CommentError]。
 */
class CommentApiException(
    val code: Int,
    val reason: CommentError,
    message: String
) : Exception(message)
