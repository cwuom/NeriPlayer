package moe.ouom.neriplayer.platform.comments.mapper

import moe.ouom.neriplayer.platform.comments.CommentApiException
import moe.ouom.neriplayer.data.model.comments.CommentEmote
import moe.ouom.neriplayer.data.model.comments.CommentError
import moe.ouom.neriplayer.data.model.comments.CommentPage
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentQuote
import moe.ouom.neriplayer.data.model.comments.SongComment
import moe.ouom.neriplayer.data.model.comments.neteaseEmoteUrlMap
import moe.ouom.neriplayer.common.json.mapObjectsNotNull
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
    val comments = data.optJSONArray("comments")
        ?.mapObjectsNotNull { parseNeteaseComment(it) }
        .orEmpty()

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
    // 正文原样保留 (含表情标记), UI 按 emotes 自行切分渲染
    val contentText = item.optString("content")

    return SongComment(
        id = item.optLong("commentId", 0L).toString(),
        userId = user.optLong("userId", 0L).takeIf { it > 0L }?.toString(),
        username = user.optString("nickname"),
        avatarUrl = user.optString("avatarUrl").takeIf { it.isNotBlank() },
        content = contentText,
        likeCount = item.optLong("likedCount", 0L),
        replyCount = resolveNeteaseReplyCount(item),
        // 网易云的时间戳已经是毫秒
        createTime = createTime,
        platform = CommentPlatform.NETEASE,
        userLevel = user.optInt("level", 0).takeIf { it > 0 },
        isLiked = item.optBoolean("liked", false),
        quotedComments = item.optJSONArray("beReplied")?.mapObjectsNotNull { quote ->
            CommentQuote(
                username = quote.optJSONObject("user")?.optString("nickname").orEmpty(),
                content = if (quote.optInt("status", 0) == -5 || quote.isNull("content")) null
                    else quote.optString("content")
            )
        }.orEmpty(),
        previewReplies = if (includePreview) {
            item.optJSONObject("showFloorComment")?.optJSONArray("comments")
                ?.mapObjectsNotNull { parseNeteaseComment(it, false) }
                .orEmpty()
        } else emptyList(),
        rootId = item.optLong("parentCommentId", 0L).takeIf { it > 0L }?.toString(),
        // 正文里的 `[名称]` 标记按内置表情表展开, 楼中楼回复走同一条解析路径
        emotes = parseNeteaseEmotes(contentText)
    )
}

/**
 * 网易云正文里的 `[名称]` 表情标记 -> 统一表情模型。
 *
 * 只展开能命中 [neteaseEmoteUrlMap] 的标记 (未知 `[xxx]` 不产生表情, 由 UI 当纯文本渲染),
 * 同一个标记在正文里出现多次只产出一条; 正文本身不改写, 切分交给 UI。
 */
private fun parseNeteaseEmotes(content: String): List<CommentEmote> {
    if (content.isEmpty()) return emptyList()
    val catalog = neteaseEmoteUrlMap()
    if (catalog.isEmpty()) return emptyList()

    // 不用 mutableListOf / mutableSetOf / Regex: 它们会把 java.util.ArrayList、
    // java.util.LinkedHashSet、kotlin.text.Regex + MatchResult、kotlin.sequences.*
    // 带进本文件常量池, 全部落在 platform-comments 域的依赖白名单之外
    // (:platform:verifyDomainDependencies)。手工扫描与 `\[[^\[\]]+]` 等价:
    // 标记非空、内部不含 `[`, 非重叠, 未知与重复标记不产出。
    var result: List<CommentEmote> = emptyList()
    var cursor = 0
    while (cursor < content.length) {
        val start = content.indexOf('[', cursor)
        if (start < 0) break
        val end = content.indexOf(']', start + 1)
        if (end < 0) break
        val inner = content.substring(start + 1, end)
        if (inner.isEmpty() || '[' in inner) {
            // 与正则一致: 该位置匹配失败, 从下一个字符继续找 `[` (内部的 `[` 仍可能成对)
            cursor = start + 1
            continue
        }
        val url = catalog[inner]
        if (url != null) {
            val token = content.substring(start, end + 1)
            if (result.none { it.placeholder == token }) {
                result = result + CommentEmote(placeholder = token, url = url)
            }
        }
        cursor = end + 1
    }
    return result
}

/**
 * 网易云楼中楼回复的分页解析: 复用 [parseNeteaseCommentPage] 拿到评论页后, 把 `nextCursor` 换成该接口自己的游标。
 *
 * 游标优先取 `data.time` (楼中楼按时间翻页), 缺失时退回本页最后一条回复的发布时间; 响应里没有 `data`
 * 对象时抛 [CommentApiException]。
 *
 * @param rawJson 接口返回的原始 JSON
 * @param page 请求的页码, 原样写回 [CommentPage]
 * @param pageSize 请求的每页条数, 原样写回 [CommentPage]
 * @return 带楼中楼游标的评论分页
 */
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
