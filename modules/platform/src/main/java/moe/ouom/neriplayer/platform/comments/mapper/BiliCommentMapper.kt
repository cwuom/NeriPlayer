package moe.ouom.neriplayer.platform.comments.mapper

import moe.ouom.neriplayer.platform.bilibili.api.image.buildBiliThumbnailUrl
import moe.ouom.neriplayer.platform.comments.CommentApiException
import moe.ouom.neriplayer.data.model.comments.CommentEmote
import moe.ouom.neriplayer.data.model.comments.CommentError
import moe.ouom.neriplayer.data.model.comments.CommentImage
import moe.ouom.neriplayer.data.model.comments.CommentPage
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.SongComment
import moe.ouom.neriplayer.common.json.mapObjectsNotNull
import org.json.JSONObject

/** Bilibili 评论区「评论已关闭」业务码 */
private const val BILI_CODE_REPLY_CLOSED = 12061

/**
 * Bilibili 评论 JSON (`x/v2/reply` 的根对象) -> 统一评论模型。
 *
 * 所有 Bilibili 评论的字段解析都集中在这里, 平台 JSON 不允许泄漏到上层 (§63/§64)。
 */
internal fun parseBiliCommentPage(
    root: JSONObject,
    page: Int,
    pageSize: Int,
    includeReplyTotal: Boolean = false
): CommentPage {
    val code = root.optInt("code", -1)
    if (code != 0) {
        throw CommentApiException(
            code = code,
            reason = biliCommentError(code),
            message = "Bili comment API failed: code=$code"
        )
    }

    val data = root.optJSONObject("data")
    val pageObject = data?.optJSONObject("page")
    // 匿名访问可能返回全零分页信息，不能把受限响应当作评论已读完
    if (pageObject == null || pageObject.optInt("num", 0) != page ||
        pageObject.optInt("size", 0) <= 0
    ) {
        throw CommentApiException(
            code = 0,
            reason = CommentError.API,
            message = "Bili comment API returned invalid pagination: requested page=$page"
        )
    }
    val total = pageObject.optLong("count", -1L).takeIf { it >= 0L }

    val comments = data.optJSONArray("replies")
        ?.mapObjectsNotNull { parseBiliComment(it) }
        .orEmpty()

    val loadedThrough = (page.toLong() - 1L) * pageSize + comments.size
    if (comments.isEmpty() && total != null && loadedThrough < total) {
        throw CommentApiException(
            code = 0,
            reason = CommentError.API,
            message = "Bili comment API returned an incomplete empty page: page=$page"
        )
    }
    // 按实际返回条数判断，避免总数不足一页时把匿名截断误当作结束
    val hasMore = total?.let { loadedThrough < it } ?: (comments.size >= pageSize)

    return CommentPage(
        comments = comments,
        page = page,
        pageSize = pageSize,
        total = total,
        hasMore = hasMore,
        totalIncludingReplies = if (includeReplyTotal) {
            pageObject.optLong("acount", -1L).takeIf { it >= (total ?: 0L) }
        } else null
    )
}

/**
 * 单条 B 站评论字段映射: 从 member/content 子对象取昵称、正文与等级, ctime 由秒换算为毫秒,
 * 回复数优先 rcount、缺失时回退 count, 头像经 [normalizeBiliAvatarUrl] 归一化。
 */
private fun parseBiliComment(item: JSONObject, includePreview: Boolean = true): SongComment {
    val member = item.optJSONObject("member") ?: JSONObject()
    val content = item.optJSONObject("content") ?: JSONObject()
    val levelInfo = member.optJSONObject("level_info")
    // Bilibili 的 ctime 是秒级时间戳, 统一归一化为毫秒
    val createTime = item.optLong("ctime", 0L).takeIf { it > 0L }?.let { it * 1000L }

    return SongComment(
        id = item.optLong("rpid", 0L).toString(),
        userId = member.optString("mid").takeIf { it.isNotBlank() },
        username = member.optString("uname"),
        avatarUrl = normalizeBiliAvatarUrl(member.optString("avatar")),
        content = content.optString("message"),
        likeCount = item.optLong("like", 0L),
        // rcount 为楼中楼数量, 缺失时回退到 count (含子评论的回复数)
        replyCount = item.optLong("rcount", -1L).takeIf { it >= 0L }
            ?: item.optLong("count", -1L).takeIf { it >= 0L },
        createTime = createTime,
        platform = CommentPlatform.BILIBILI,
        userLevel = levelInfo?.optInt("current_level", 0)?.takeIf { it > 0 },
        isLiked = item.optInt("action", 0) == 1,
        previewReplies = if (includePreview) {
            item.optJSONArray("replies")
                ?.mapObjectsNotNull { parseBiliComment(it, false) }
                .orEmpty()
        } else emptyList(),
        rootId = item.optLong("root", 0L).takeIf { it > 0L }?.toString(),
        // 表情与配图都挂在 content 子对象上, 一级评论与楼中楼走同一条解析路径
        emotes = parseBiliEmotes(content),
        images = parseBiliImages(content)
    )
}

/**
 * Bilibili 评论内联表情 (`content.emote`) -> 统一表情模型。
 *
 * 载荷形如 `{"[doge]":{"id":26,"text":"[doge]","url":"//i0.hdslb.com/bfs/emote/xxx.png"}}`,
 * JSON key 就是正文里出现的标记文本; 地址为空或非对象的条目直接丢弃, 不做占位。
 */
private fun parseBiliEmotes(content: JSONObject): List<CommentEmote> {
    val emoteObject = content.optJSONObject("emote") ?: return emptyList()
    // 不用 mutableListOf: 它是 inline, 会把 java.util.ArrayList 带进本文件的常量池,
    // 撞上 platform-comments 域的依赖白名单 (:platform:verifyDomainDependencies)
    var result: List<CommentEmote> = emptyList()
    val placeholders = emoteObject.keys()
    while (placeholders.hasNext()) {
        val placeholder = placeholders.next()
        val url = ensureBiliMediaScheme(emoteObject.optJSONObject(placeholder)?.optString("url"))
            ?: continue
        result = result + CommentEmote(placeholder = placeholder, url = url)
    }
    return result
}

/**
 * Bilibili 评论配图 (`content.pictures`) -> 统一配图模型。
 *
 * 载荷形如 `[{"img_src":"//i0.hdslb.com/bfs/xxx.jpg","img_width":2400,"img_height":1080}]`;
 * 宽高缺失时取 0 (`optInt` 默认值), 地址为空的条目丢弃。
 */
private fun parseBiliImages(content: JSONObject): List<CommentImage> =
    content.optJSONArray("pictures")
        ?.mapObjectsNotNull { picture ->
            val url = ensureBiliMediaScheme(picture.optString("img_src"))
                ?: return@mapObjectsNotNull null
            CommentImage(
                url = url,
                width = picture.optInt("img_width", 0),
                height = picture.optInt("img_height", 0)
            )
        }
        .orEmpty()

/**
 * Bilibili 图片地址协议归一化: `//` 开头补 `https:`, 空值返回 null。
 */
private fun ensureBiliMediaScheme(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
}

/**
 * Bilibili 头像地址归一化: 补齐协议头 + 追加尺寸参数 (复用 BiliImageUrl 的逻辑)。
 */
private fun normalizeBiliAvatarUrl(raw: String?): String? {
    val withScheme = ensureBiliMediaScheme(raw) ?: return null
    return buildBiliThumbnailUrl(withScheme, width = 96, height = 96)
        .takeIf { it.isNotBlank() }
}

/**
 * Bilibili 业务错误码 -> 统一错误分类。
 */
internal fun biliCommentError(code: Int): CommentError = when (code) {
    -101, -102, -111, -403, -412, 12004 -> CommentError.PERMISSION
    -404, 12006 -> CommentError.NOT_FOUND
    BILI_CODE_REPLY_CLOSED, 12002 -> CommentError.CLOSED
    in 500..599 -> CommentError.SERVER
    else -> CommentError.API
}
