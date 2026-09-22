package moe.ouom.neriplayer.core.comment.mapper

import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.SongComment
import org.json.JSONObject

/**
 * 网易云评论 JSON -> 统一评论模型。
 *
 * 所有网易云评论的字段解析都集中在这里, 平台 JSON 不允许泄漏到上层 (§63/§64)。
 */
internal fun parseNeteaseCommentPage(
    rawJson: String,
    page: Int,
    pageSize: Int
): CommentPage {
    val root = JSONObject(rawJson)
    val code = root.optInt("code", -1)
    if (code != 200) {
        throw CommentApiException(
            code = code,
            reason = neteaseCommentError(code),
            message = "NetEase comment API failed: code=$code"
        )
    }

    val array = root.optJSONArray("comments")
    val comments = ArrayList<SongComment>(array?.length() ?: 0)
    if (array != null) {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            comments += parseNeteaseComment(item)
        }
    }

    val total = root.optLong("total", -1L).takeIf { it >= 0L }
    val hasMore = if (root.has("more")) {
        root.optBoolean("more", false)
    } else {
        total?.let { page.toLong() * pageSize < it } ?: (comments.size >= pageSize)
    }

    return CommentPage(
        comments = comments,
        page = page,
        pageSize = pageSize,
        total = total,
        hasMore = hasMore
    )
}

private fun parseNeteaseComment(item: JSONObject): SongComment {
    val user = item.optJSONObject("user") ?: JSONObject()
    val createTime = item.optLong("time", 0L).takeIf { it > 0L }

    return SongComment(
        id = item.optLong("commentId", 0L).toString(),
        userId = user.optLong("userId", 0L).takeIf { it > 0L }?.toString(),
        username = user.optString("nickname"),
        avatarUrl = user.optString("avatarUrl").takeIf { it.isNotBlank() },
        content = item.optString("content"),
        likeCount = item.optLong("likedCount", 0L),
        replyCount = item.optLong("replyCount", -1L).takeIf { it >= 0L },
        // 网易云的时间戳已经是毫秒
        createTime = createTime,
        platform = CommentPlatform.NETEASE,
        userLevel = user.optInt("level", 0).takeIf { it > 0 }
    )
}

/**
 * 网易云业务错误码 -> 统一错误分类。
 */
internal fun neteaseCommentError(code: Int): CommentError = when (code) {
    301, 401, 403, -460 -> CommentError.PERMISSION
    404 -> CommentError.NOT_FOUND
    in 500..599 -> CommentError.SERVER
    else -> CommentError.API
}
