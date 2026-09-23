package moe.ouom.neriplayer.core.comment.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.CommentMemoryCache
import moe.ouom.neriplayer.core.comment.mapper.parseBiliCommentPage
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger

/** 旧版 Bilibili 歌曲把 avid 与分P序号打包进播放 id 时使用的进制 (见 `BiliSongResolver.resolveLegacy`) */
private const val BILI_LEGACY_ID_PACK_FACTOR = 10_000L

/** 每个资源 id 最多缓存多少个 legacy 解包结果, 避免长期运行后无界增长 */
private const val BILI_LEGACY_ID_CACHE_MAX = 32

/**
 * 把旧版 Bilibili 歌曲的「打包播放 id」还原为视频 avid。
 *
 * 旧版本把 `avid * 10000 + 分P序号` 直接存进播放 id, 项目播放侧在
 * `BiliSongResolver.resolveLegacy` 中同样用 `id / 10_000L` 反解;
 * 这里沿用同一规则, 使恢复的旧歌曲也能取到正确的评论 oid。
 *
 * 不像打包值时返回 null (负数 / 小于 10000 / 余数为 0)。
 */
internal fun legacyBiliResourceIdOrNull(resourceId: Long): Long? {
    if (resourceId < BILI_LEGACY_ID_PACK_FACTOR) return null
    val page = resourceId % BILI_LEGACY_ID_PACK_FACTOR
    if (page <= 0L) return null
    return (resourceId / BILI_LEGACY_ID_PACK_FACTOR).takeIf { it > 0L }
}

/**
 * 只有「平台明确拒绝了这个 oid」的业务错误才允许尝试解包重试。
 *
 * 网络不可用 / 服务端 5xx / 未知异常都只是临时故障, 用猜测出来的另一个 id 去「恢复」,
 * 只会把**别的视频**的评论展示并缓存给用户; 因此这些分类一律直接抛出原始错误。
 * [CommentError.API] 表示「HTTP 成功但业务 code != 0」, 属于确定性的业务拒绝,
 * 旧版打包 id 实测返回的 `-404` 与 `12002` 分别落在 [CommentError.NOT_FOUND] 与 [CommentError.API]。
 */
internal fun isLegacyFallbackEligible(reason: CommentError): Boolean = when (reason) {
    CommentError.NOT_FOUND, CommentError.CLOSED, CommentError.API -> true
    CommentError.NETWORK, CommentError.PERMISSION, CommentError.SERVER, CommentError.UNKNOWN -> false
}

/**
 * 解包候选是否拿到「这确实是同一首歌的视频」的证据。
 *
 * 视频信息接口返回的 aid 必须等于候选值, 并且 bvid 必须与歌曲 album 中记录的 bvid
 * (`Bilibili|<cid>|<bvid>`, 见 [moe.ouom.neriplayer.core.api.bili.buildBiliSongAlbum]) 一致:
 * 只有 `avid * 10000 + 分P序号` 这种打包格式才会解出同一个视频, 从而排除
 * 「`aid / 10000` 恰好是另一条真实视频」的猜测。album 没记录 bvid 时无从验证, 直接判定失败。
 */
internal fun hasVerifiedLegacyVideo(
    info: BiliClient.VideoBasicInfo?,
    candidateAvid: Long,
    bvid: String?
): Boolean {
    if (info == null || info.aid != candidateAvid) return false
    val expectedBvid = bvid?.trim().orEmpty()
    if (expectedBvid.isEmpty()) return false
    return info.bvid.equals(expectedBvid, ignoreCase = true)
}

/**
 * Bilibili 评论仓库。
 *
 * 复用项目已有的 [AppContainer.biliClient] (同一条 HTTP / Cookie 链路)，
 * 不新建任何 Bilibili 网络层 (§16/§20/§35)。
 */
internal object BiliCommentRepository : CommentRepository {

    private const val TAG = "NERI-BiliComment"

    /** 打包播放 id -> 真实 avid, 记住解包结果后翻页 / 刷新不必再试错 */
    private val legacyResourceIds = LinkedHashMap<Long, Long>()

    override val platform: CommentPlatform = CommentPlatform.BILIBILI

    /**
     * 加载 Bilibili 某页评论: 非强制刷新时先查内存缓存, 未命中则以 aid 为资源 id
     * 调用 [AppContainer.biliClient] 的视频评论接口, 解析后写入缓存再返回。
     *
     * 恢复的旧版歌曲用的是打包播放 id, 直接用它会拿到 `-404` / 业务码 `12002`;
     * 此时只在「确定性业务拒绝 + 视频信息接口确认解包候选的 aid 与 bvid 都与本歌曲一致」
     * 两个条件同时成立时, 才按播放侧同一规则解包重试一次, 且只有重试成功才采用结果,
     * 真正「视频不存在 / 评论已关闭」的歌曲仍然保留原始错误。
     */
    override suspend fun loadComments(
        resourceId: Long,
        secondaryId: String?,
        page: Int,
        pageSize: Int,
        forceRefresh: Boolean
    ): CommentPage {
        if (!forceRefresh) {
            CommentMemoryCache.get(platform.name, resourceId, page)?.let { return it }
        }

        val effectiveId = knownLegacyResourceId(resourceId) ?: resourceId

        // 只记录非敏感上下文, 不打印评论正文 / Cookie (§36)
        NPLogger.d(TAG, "load comments: platform=BILIBILI, resourceId=$effectiveId, page=$page")

        val result = try {
            loadPage(effectiveId, page, pageSize)
        } catch (apiError: CommentApiException) {
            resolveWithLegacyId(apiError, resourceId, secondaryId, effectiveId, page, pageSize)
        }

        CommentMemoryCache.put(platform.name, resourceId, page, result)
        return result
    }

    /**
     * 按解包后的 avid 重试一次; 成功时记住映射并返回该页,
     * 失败 / 未通过验证则抛出最初那次请求的错误 (保持「视频不存在 / 评论已关闭」的原判)。
     */
    private suspend fun resolveWithLegacyId(
        apiError: CommentApiException,
        resourceId: Long,
        bvid: String?,
        effectiveId: Long,
        page: Int,
        pageSize: Int
    ): CommentPage {
        if (!isLegacyFallbackEligible(apiError.reason)) throw apiError

        val legacyId = legacyBiliResourceIdOrNull(resourceId) ?: throw apiError
        if (legacyId == effectiveId) throw apiError
        if (!verifyLegacyVideo(legacyId, bvid)) throw apiError

        val pageResult = try {
            loadPage(legacyId, page, pageSize)
        } catch (_: CommentApiException) {
            throw apiError
        }

        NPLogger.d(TAG, "legacy packed playback id decoded: $resourceId -> $legacyId")
        rememberLegacyResourceId(resourceId, legacyId)
        return pageResult
    }

    /**
     * 用播放侧同一条视频信息接口确认解包候选就是这首歌的视频;
     * 请求异常或信息不匹配一律视为「未验证」, 不采用该候选。
     */
    private suspend fun verifyLegacyVideo(candidateAvid: Long, bvid: String?): Boolean {
        val info = runCatching {
            withContext(Dispatchers.IO) {
                AppContainer.biliClient.getVideoBasicInfoByAvid(candidateAvid)
            }
        }.getOrNull()

        return hasVerifiedLegacyVideo(info, candidateAvid, bvid)
    }

    private suspend fun loadPage(resourceId: Long, page: Int, pageSize: Int): CommentPage {
        val root = withContext(Dispatchers.IO) {
            AppContainer.biliClient.getVideoComments(
                aid = resourceId,
                page = page,
                pageSize = pageSize
            )
        }

        return parseBiliCommentPage(root, page = page, pageSize = pageSize)
    }

    private fun knownLegacyResourceId(resourceId: Long): Long? = synchronized(legacyResourceIds) {
        legacyResourceIds[resourceId]
    }

    private fun rememberLegacyResourceId(resourceId: Long, legacyId: Long) {
        synchronized(legacyResourceIds) {
            if (legacyResourceIds[resourceId] == null &&
                legacyResourceIds.size >= BILI_LEGACY_ID_CACHE_MAX
            ) {
                legacyResourceIds.clear()
            }
            legacyResourceIds[resourceId] = legacyId
        }
    }
}
