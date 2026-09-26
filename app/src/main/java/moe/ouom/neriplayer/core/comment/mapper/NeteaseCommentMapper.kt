package moe.ouom.neriplayer.core.comment.mapper

import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.CommentQuote
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

    val data = if (root.has("data")) {
        root.optJSONObject("data")?.takeIf { it.optJSONArray("comments") != null }
            ?: throw CommentApiException(200, CommentError.API, "Invalid NetEase comment data")
    } else {
        root
    }
    val array = data.optJSONArray("comments")
    val comments = ArrayList<SongComment>(array?.length() ?: 0)
    if (array != null) {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            comments += parseNeteaseComment(item)
        }
    }

    val total = data.optLong("totalCount", data.optLong("total", -1L)).takeIf { it >= 0L }
    val hasMore = if (data.has("hasMore")) {
        data.optBoolean("hasMore", false)
    } else if (data.has("more")) {
        data.optBoolean("more", false)
    } else {
        total?.let { page.toLong() * pageSize < it } ?: (comments.size >= pageSize)
    }

    return CommentPage(
        comments = comments,
        page = page,
        pageSize = pageSize,
        total = total,
        hasMore = hasMore,
        nextCursor = data.optString("cursor").takeIf { it.isNotBlank() && it != "null" }
    )
}

/**
 * 单条网易云评论字段映射: 从 user 子对象取昵称/头像/等级, 正文与点赞数取顶层字段,
 * 时间戳已是毫秒直接使用, 回复数交给 [resolveNeteaseReplyCount] 做防御性回退。
 */
private fun parseNeteaseComment(item: JSONObject, includePreview: Boolean = true): SongComment {
    val user = item.optJSONObject("user") ?: JSONObject()
    val createTime = item.optLong("time", 0L).takeIf { it > 0L }

    return SongComment(
        id = item.optLong("commentId", 0L).toString(),
        userId = user.optLong("userId", 0L).takeIf { it > 0L }?.toString(),
        username = user.optString("nickname"),
        avatarUrl = user.optString("avatarUrl").takeIf { it.isNotBlank() },
        content = item.optString("content"),
        likeCount = item.optLong("likedCount", 0L),
        replyCount = resolveNeteaseReplyCount(item),
        // 网易云的时间戳已经是毫秒
        createTime = createTime,
        platform = CommentPlatform.NETEASE,
        userLevel = user.optInt("level", 0).takeIf { it > 0 },
        isLiked = item.optBoolean("liked", false),
        quotedComments = item.optJSONArray("beReplied")?.let { quotes ->
            (0 until quotes.length()).mapNotNull { index ->
                val quote = quotes.optJSONObject(index) ?: return@mapNotNull null
                CommentQuote(
                    username = quote.optJSONObject("user")?.optString("nickname").orEmpty(),
                    content = if (quote.optInt("status", 0) == -5 || quote.isNull("content")) null
                        else quote.optString("content")
                )
            }
        }.orEmpty(),
        previewReplies = if (includePreview) {
            item.optJSONObject("showFloorComment")?.optJSONArray("comments")?.let { replies ->
                (0 until replies.length()).mapNotNull { index ->
                    replies.optJSONObject(index)?.let { parseNeteaseComment(it, false) }
                }
            }.orEmpty()
        } else emptyList(),
        rootId = item.optLong("parentCommentId", 0L).takeIf { it > 0L }?.toString()
    )
}

internal fun parseNeteaseReplyPage(rawJson: String, page: Int, pageSize: Int): CommentPage {
    val result = parseNeteaseCommentPage(rawJson, page, pageSize)
    val data = JSONObject(rawJson).optJSONObject("data")
        ?: throw CommentApiException(200, CommentError.API, "Missing NetEase reply data")
    val cursor = data.optLong("time", 0L).takeIf { it > 0L }?.toString()
        ?: result.comments.lastOrNull()?.createTime?.toString()
    return result.copy(nextCursor = cursor)
}

/**
 * 网易云回复数的位置随接口形态而变：老接口放在顶层 `replyCount`，
 * 新版 `/api/v1/resource/comments` 把它放在 `showFloorComment.replyCount`（"楼中楼"预览）。
 * 两处都没有时返回 null —— UI 会隐藏该字段；不能返回 0，否则会错误显示"0 条回复"。
 */
private fun resolveNeteaseReplyCount(item: JSONObject): Long? {
    val direct = item.optLong("replyCount", -1L)
    if (direct >= 0L) return direct

    val floorComment = item.optJSONObject("showFloorComment") ?: return null
    return floorComment.optLong("replyCount", -1L).takeIf { it >= 0L }
}

/**
 * 网易云业务错误码 -> 统一错误分类。
 */
internal fun neteaseCommentError(code: Int): CommentError = when (code) {
    301, 315, 401, 403, -460 -> CommentError.PERMISSION
    404 -> CommentError.NOT_FOUND
    in 500..599 -> CommentError.SERVER
    else -> CommentError.API
}
