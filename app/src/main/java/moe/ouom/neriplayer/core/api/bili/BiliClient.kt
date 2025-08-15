package moe.ouom.neriplayer.core.api.bili

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.api.bili/BiliClient
 * Updated: 2025/08/14
 */

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.BiliCookieRepository
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * B 站 Web 端 API 客户端
 */
class BiliClient(
    private val cookieRepo: BiliCookieRepository,
    client: OkHttpClient? = null,
) {

    companion object {
        private const val TAG = "NERI-BiliClient"

        // 官方接口 / WBI
        private const val BASE_PLAY_URL = "https://api.bilibili.com/x/player/wbi/playurl"
        private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"

        // 基础信息（WBI 可用）
        private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/wbi/view"
        private const val VIEW_DETAIL_URL = "https://api.bilibili.com/x/web-interface/wbi/view/detail"

        // 搜索（WBI）
        private const val SEARCH_TYPE_URL = "https://api.bilibili.com/x/web-interface/wbi/search/type"

        // 点赞近况
        private const val HAS_LIKE_URL = "https://api.bilibili.com/x/web-interface/archive/has/like"

        // 收藏夹
        private const val FAV_FOLDER_CREATED_LIST_ALL = "https://api.bilibili.com/x/v3/fav/folder/created/list-all"
        private const val FAV_FOLDER_INFO = "https://api.bilibili.com/x/v3/fav/folder/info"
        private const val FAV_RESOURCE_LIST = "https://api.bilibili.com/x/v3/fav/resource/list"
        private const val FAV_RESOURCE_IDS = "https://api.bilibili.com/x/v3/fav/resource/ids"
        private const val PAGELIST_URL = "https://api.bilibili.com/x/player/pagelist"

        /** 默认 UA（Web） */
        private const val DEFAULT_WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Safari/537.36"

        /** 默认 Referer */
        private const val REFERER = "https://www.bilibili.com"

        /** Wbi mixin 索引表 */
        private val MIXIN_INDEX = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 62, 6, 63, 57, 20, 34, 52, 59, 11, 36, 44
        )

        /** Wbi key 缓存时间 */
        private const val WBI_CACHE_MS = 10 * 60 * 1000L

        // ---- fnval 位 ----
        /** DASH 开关（必开，否则只有 durl/mp4） */
        const val FNVAL_DASH = 1 shl 4  // 16
        /** 杜比音频（E-AC-3/Atmos），要拿 dolby.audio 必开 */
        const val FNVAL_DOLBY = 1 shl 8  // 256
        /** 其它位（如 AV1/HDR/8K 等）按需再开，这里不强制 */
    }

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    // 外部可用的数据结构
    data class PlayOptions(
        /** 画质 qn；DASH 下此参数基本无效（会返回所有可用轨） */
        val qn: Int? = null,
        /** 流格式标识，推荐：DASH + Dolby，确保能下发普通音轨与杜比音轨 */
        val fnval: Int = FNVAL_DASH or FNVAL_DOLBY,
        val fnver: Int = 0,
        /** 允许 4K（配合 qn=120 & fourk=1），对音轨无影响 */
        val fourk: Int = 0,
        /** 平台：pc（默认，需 Referer），html5（无 Referer 校验，仅 MP4） */
        val platform: String = "pc",
        /** platform=html5 时为 1 可拉 1080p（high_quality=1） */
        val highQuality: Int? = null,
        /** 未登录试拉较高画质（64/80），1 开启 */
        val tryLook: Int? = null,
        /** session 透传 */
        val session: String? = null,
        /** 可选：gaia_source，无 Cookie 时有时需要（view-card / pre-load） */
        val gaiaSource: String? = null,
        /** 可选：isGaiaAvoided */
        val isGaiaAvoided: Boolean? = null,
    )

    data class Durl(
        val order: Int,
        val lengthMs: Long,
        val sizeBytes: Long,
        val url: String,
        val backupUrls: List<String>
    )

    data class DashStream(
        val id: Int,
        val baseUrl: String,
        val backupUrls: List<String>,
        val bandwidth: Long,
        val mimeType: String,
        val codecs: String,
        val width: Int,
        val height: Int,
        val frameRate: String,
        val codecid: Int
    )

    data class DolbyAudio(
        val type: Int,
        val audios: List<DashStream>
    )

    data class FlacAudio(
        val display: Boolean,
        val audio: DashStream?
    )

    /**
     * 统一的播放信息封装
     * MP4 看 durl；DASH 看 dashVideo/dashAudio
     */
    data class PlayInfo(
        val code: Int,
        val message: String,
        val qnSelected: Int?,
        val format: String?,
        val timeLengthMs: Long?,
        val acceptDescription: List<String>,
        val acceptQuality: List<Int>,
        // MP4
        val durl: List<Durl>,
        // DASH
        val dashVideo: List<DashStream>,
        val dashAudio: List<DashStream>,
        val dolby: DolbyAudio?,
        val flac: FlacAudio?,
        val raw: JSONObject
    )

    // 视频基础信息 / 搜索 / 收藏夹

    data class VideoStats(
        val view: Long,
        val danmaku: Long,
        val reply: Long,
        val favorite: Long,
        val coin: Long,
        val share: Long,
        val like: Long
    )

    data class VideoBasicInfo(
        val aid: Long,
        val bvid: String,
        val title: String,
        val coverUrl: String,
        val desc: String,
        val durationSec: Int,
        val ownerMid: Long,
        val ownerName: String,
        val ownerFace: String,
        val stats: VideoStats,
        val pages: List<VideoPage>
    )

    data class VideoPage(
        val cid: Long,
        val page: Int,
        val part: String,
        val durationSec: Int,
        val width: Int,
        val height: Int
    )

    data class SearchVideoItem(
        val aid: Long,
        val bvid: String,
        val titleHtml: String,
        val titlePlain: String,
        val author: String,
        val mid: Long,
        val coverUrl: String,
        val durationSec: Int,
        val play: Long?,
        val pubdate: Long?
    )

    data class SearchVideoPage(
        val page: Int,
        val pageSize: Int,
        val numResults: Int,
        val numPages: Int,
        val items: List<SearchVideoItem>
    )

    data class FavFolder(
        val mediaId: Long,
        val fid: Long,
        val mid: Long,
        val title: String,
        val coverUrl: String,
        val intro: String,
        val count: Int,
        val likeCount: Long?,
        val playCount: Long?,
        val collectCount: Long?
    )

    data class FavResourceItem(
        val type: Int,           // 2: 视频稿件, 12: 音频, 21: 合集
        val id: Long,           // 对应 avid/auid/合集 id
        val bvid: String?,
        val title: String,
        val coverUrl: String,
        val intro: String,
        val durationSec: Int,
        val upperMid: Long,
        val upperName: String,
        val play: Long?,
        val danmaku: Long?,
        val favTime: Long?
    )

    data class FavResourcePage(
        val info: FavFolder,
        val items: List<FavResourceItem>,
        val hasMore: Boolean
    )

    // 对外 API //

    /**
     * 通过 bvid + cid 获取取流信息
     */
    suspend fun getPlayInfoByBvid(
        bvid: String,
        cid: Long,
        opts: PlayOptions = PlayOptions()
    ): PlayInfo = withContext(Dispatchers.IO) {
        val params = mutableMapOf<String, String>().apply {
            put("bvid", bvid)
            put("cid", cid.toString())
            putCommonParams(opts)
        }
        requestPlayUrl(params)
    }

    /**
     * 通过 avid + cid 获取取流信息
     */
    suspend fun getPlayInfoByAvid(
        avid: Long,
        cid: Long,
        opts: PlayOptions = PlayOptions()
    ): PlayInfo = withContext(Dispatchers.IO) {
        val params = mutableMapOf<String, String>().apply {
            put("avid", avid.toString())
            put("cid", cid.toString())
            putCommonParams(opts)
        }
        requestPlayUrl(params)
    }

    // 直接拿所有可用音频流 并映射为统一结构
    suspend fun getAllAudioStreams(
        bvid: String,
        cid: Long,
        opts: PlayOptions = PlayOptions()
    ): List<BiliAudioStreamInfo> {
        val info = getPlayInfoByBvid(bvid, cid, opts)
        return info.toAudioStreamInfos()
    }

    // 视频基础信息 //

    suspend fun getVideoBasicInfoByBvid(bvid: String): VideoBasicInfo =
        fetchVideoBasicInfo(mapOf("bvid" to bvid))

    suspend fun getVideoBasicInfoByAvid(avid: Long): VideoBasicInfo =
        fetchVideoBasicInfo(mapOf("aid" to avid.toString()))

    private suspend fun fetchVideoBasicInfo(params: Map<String, String>): VideoBasicInfo =
        withContext(Dispatchers.IO) {
            // 优先 WBI 版本
            val jo = getJsonWbi(VIEW_URL, params)
            val data = jo.optJSONObject("data") ?: JSONObject()

            val aid = data.optLong("aid")
            val bvid = data.optString("bvid")
            val title = data.optString("title")
            val picRaw = data.optString("pic")
            val cover = ensureHttps(picRaw)

            // desc_v2 优先，回落 desc
            val descV2 = data.optJSONArray("desc_v2")
            val desc = if (descV2 != null && descV2.length() > 0) {
                buildString {
                    for (i in 0 until descV2.length()) {
                        val item = descV2.optJSONObject(i)
                        if (item != null) {
                            val t = item.optString("raw_text")
                            if (t.isNotBlank()) {
                                if (isNotEmpty()) append('\n')
                                append(t)
                            }
                        }
                    }
                }
            } else data.optString("desc", "")

            val durationSec = data.optInt("duration", 0)

            val owner = data.optJSONObject("owner") ?: JSONObject()
            val ownerMid = owner.optLong("mid")
            val ownerName = owner.optString("name")
            val ownerFace = ensureHttps(owner.optString("face"))

            val stat = data.optJSONObject("stat") ?: JSONObject()
            val stats = VideoStats(
                view = stat.optLong("view"),
                danmaku = stat.optLong("danmaku"),
                reply = stat.optLong("reply"),
                favorite = stat.optLong("favorite"),
                coin = stat.optLong("coin"),
                share = stat.optLong("share"),
                like = stat.optLong("like")
            )

            val pagesArr = data.optJSONArray("pages")
            val pages = if (pagesArr != null) {
                val out = ArrayList<VideoPage>(pagesArr.length())
                for (i in 0 until pagesArr.length()) {
                    val p = pagesArr.optJSONObject(i) ?: continue
                    val dim = p.optJSONObject("dimension") ?: JSONObject()
                    out += VideoPage(
                        cid = p.optLong("cid"),
                        page = p.optInt("page"),
                        part = p.optString("part"),
                        durationSec = p.optInt("duration"),
                        width = dim.optInt("width"),
                        height = dim.optInt("height")
                    )
                }
                out
            } else emptyList()

            VideoBasicInfo(
                aid = aid,
                bvid = bvid,
                title = title,
                coverUrl = cover,
                desc = desc,
                durationSec = durationSec,
                ownerMid = ownerMid,
                ownerName = ownerName,
                ownerFace = ownerFace,
                stats = stats,
                pages = pages
            )
        }

    suspend fun getVideoStatsByBvid(bvid: String): VideoStats =
        getVideoBasicInfoByBvid(bvid).stats

    suspend fun getVideoStatsByAvid(avid: Long): VideoStats =
        getVideoBasicInfoByAvid(avid).stats

    // 搜索 //

    suspend fun searchVideos(
        keyword: String,
        page: Int = 1,
        order: String = "totalrank", // 综合 / 最新 / 等
        duration: Int = 0,           // 0=全部; 1:<10m; 2:10-30m; 3:30-60m; 4:>60m
        tids: Int = 0                // 0=全部; 否则传分区 tid
    ): SearchVideoPage = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "order" to order,
            "duration" to duration.toString(),
            "tids" to tids.toString(),
            "page" to page.toString()
        )
        val jo = getJsonWbi(SEARCH_TYPE_URL, params)
        val data = jo.optJSONObject("data") ?: JSONObject()
        val itemsArr = data.optJSONArray("result") ?: JSONArray()
        val items = ArrayList<SearchVideoItem>(itemsArr.length())
        for (i in 0 until itemsArr.length()) {
            val it = itemsArr.optJSONObject(i) ?: continue
            if (it.optString("type") != "video") continue
            val aid = it.optLong("aid")
            val bvid = it.optString("bvid")
            val titleHtml = it.optString("title")
            val titlePlain = stripHtml(titleHtml)
            val author = it.optString("author")
            val mid = it.optLong("mid")
            val cover = ensureHttps(it.optString("pic"))
            val durationSec = parseDurationToSeconds(it.optString("duration"))
            val play = it.optLongOrNull("play")
            val pubdate = it.optLongOrNull("pubdate")

            items += SearchVideoItem(
                aid = aid,
                bvid = bvid,
                titleHtml = titleHtml,
                titlePlain = titlePlain,
                author = author,
                mid = mid,
                coverUrl = cover,
                durationSec = durationSec,
                play = play,
                pubdate = pubdate
            )
        }
        SearchVideoPage(
            page = data.optInt("page", page),
            pageSize = data.optInt("pagesize", items.size),
            numResults = data.optInt("numResults", items.size),
            numPages = data.optInt("numPages", 1),
            items = items
        )
    }

    // 点赞近况 //

    suspend fun hasLikedRecentlyByBvid(bvid: String): Boolean =
        queryHasLike(mapOf("bvid" to bvid))

    suspend fun hasLikedRecentlyByAvid(avid: Long): Boolean =
        queryHasLike(mapOf("aid" to avid.toString()))

    private suspend fun queryHasLike(params: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            val jo = getJson(HAS_LIKE_URL, params)
            // {"code":0,"message":"0","ttl":1,"data":1}  => 1 代表近期点过赞
            jo.optInt("code", -1) == 0 && (jo.optInt("data", 0) == 1)
        }

    // 收藏夹 //

    /** 获取指定用户创建的所有收藏夹（公开 + 登录可见私密） */
    suspend fun getUserCreatedFavFolders(upMid: Long): List<FavFolder> =
        withContext(Dispatchers.IO) {
            val jo = getJson(FAV_FOLDER_CREATED_LIST_ALL, mapOf("up_mid" to upMid.toString()))
            val data = jo.optJSONObject("data") ?: JSONObject()
            val list = data.optJSONArray("list") ?: JSONArray()
            val out = ArrayList<FavFolder>(list.length())
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                out += FavFolder(
                    mediaId = o.optLong("id"),
                    fid = o.optLong("fid"),
                    mid = o.optLong("mid"),
                    title = o.optString("title"),
                    coverUrl = ensureHttps(o.optString("cover")),
                    intro = o.optString("intro"),
                    count = o.optInt("media_count"),
                    likeCount = o.optJSONObject("cnt_info")?.optLong("thumb_up"),
                    playCount = o.optJSONObject("cnt_info")?.optLong("play"),
                    collectCount = o.optJSONObject("cnt_info")?.optLong("collect")
                )
            }
            out
        }

    /** 获取收藏夹元信息 */
    suspend fun getFavFolderInfo(mediaId: Long): FavFolder =
        withContext(Dispatchers.IO) {
            val jo = getJson(FAV_FOLDER_INFO, mapOf("media_id" to mediaId.toString()))
            val o = jo.optJSONObject("data") ?: JSONObject()
            val cnt = o.optJSONObject("cnt_info")
            FavFolder(
                mediaId = o.optLong("id"),
                fid = o.optLong("fid"),
                mid = o.optLong("mid"),
                title = o.optString("title"),
                coverUrl = ensureHttps(o.optString("cover")),
                intro = o.optString("intro"),
                count = o.optInt("media_count"),
                likeCount = cnt?.optLong("thumb_up"),
                playCount = cnt?.optLong("play"),
                collectCount = cnt?.optLong("collect")
            )
        }

    /** 获取收藏夹内容明细（分页） */
    suspend fun getFavFolderContents(
        mediaId: Long,
        page: Int = 1,
        pageSize: Int = 20,
        order: String = "mtime",   // mtime/view/pubtime
        keyword: String? = null,
        tid: Int? = null,          // 视频分区筛选
        scopeType: Int? = null     // 0:当前收藏夹, 1:全部收藏夹
    ): FavResourcePage = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "media_id" to mediaId.toString(),
            "pn" to page.toString(),
            "ps" to pageSize.toString(),
            "order" to order,
            "platform" to "web"
        )
        keyword?.let { params["keyword"] = it }
        tid?.let { params["tid"] = it.toString() }
        scopeType?.let { params["type"] = it.toString() }

        val jo = getJson(FAV_RESOURCE_LIST, params)
        val data = jo.optJSONObject("data") ?: JSONObject()
        val info = data.optJSONObject("info") ?: JSONObject()

        val folder = FavFolder(
            mediaId = info.optLong("id"),
            fid = info.optLong("fid"),
            mid = info.optLong("mid"),
            title = info.optString("title"),
            coverUrl = ensureHttps(info.optString("cover")),
            intro = info.optString("intro"),
            count = info.optInt("media_count"),
            likeCount = info.optJSONObject("cnt_info")?.optLong("thumb_up"),
            playCount = info.optJSONObject("cnt_info")?.optLong("play"),
            collectCount = info.optJSONObject("cnt_info")?.optLong("collect")
        )

        val medias = data.optJSONArray("medias") ?: JSONArray()
        val items = ArrayList<FavResourceItem>(medias.length())
        for (i in 0 until medias.length()) {
            val m = medias.optJSONObject(i) ?: continue
            val upper = m.optJSONObject("upper") ?: JSONObject()
            val cnt = m.optJSONObject("cnt_info") ?: JSONObject()
            items += FavResourceItem(
                type = m.optInt("type"),
                id = m.optLong("id"),
                bvid = m.optString("bvid", m.optString("bv_id", null)),
                title = m.optString("title"),
                coverUrl = ensureHttps(m.optString("cover")),
                intro = m.optString("intro"),
                durationSec = m.optInt("duration"),
                upperMid = upper.optLong("mid"),
                upperName = upper.optString("name"),
                play = cnt.optLongOrNull("play"),
                danmaku = cnt.optLongOrNull("danmaku"),
                favTime = m.optLongOrNull("fav_time")
            )
        }

        FavResourcePage(
            info = folder,
            items = items,
            hasMore = data.optBoolean("has_more", false)
        )
    }

    // 内部实现 //

    private fun MutableMap<String, String>.putCommonParams(opts: PlayOptions) {
        opts.qn?.let { put("qn", it.toString()) }
        put("fnval", opts.fnval.toString())
        put("fnver", opts.fnver.toString())
        put("fourk", opts.fourk.toString())
        put("otype", "json")
        put("platform", opts.platform)
        opts.highQuality?.let { put("high_quality", it.toString()) }
        opts.tryLook?.let { put("try_look", it.toString()) }
        opts.session?.let { put("session", it) }
        opts.gaiaSource?.let { put("gaia_source", it) }
        opts.isGaiaAvoided?.let { put("isGaiaAvoided", it.toString()) }
    }

    private suspend fun requestPlayUrl(params: MutableMap<String, String>): PlayInfo {
        // Wbi 签名
        val signedUrl = signWbiUrl(BASE_PLAY_URL, params)

        val text = executeGetAsText(signedUrl)

        val root = JSONObject(text)

        val code = root.optInt("code", -1)
        val msg = root.optString("message", "")
        if (code != 0) {
            Log.w(TAG, "requestPlayUrl failed: code=$code, message=$msg")
        }

        val data = root.optJSONObject("data") ?: JSONObject()
        val qnSelected = if (data.has("quality")) data.optInt("quality") else null
        val format = data.optString("format", null)
        val timeLengthMs = if (data.has("timelength")) data.optLong("timelength") else null

        val acceptDesc = data.optJSONArray("accept_description").toStringList()
        val acceptQuality = data.optJSONArray("accept_quality").toIntList()

        // MP4
        val durlList = parseDurl(data.optJSONArray("durl"))

        // DASH
        val dash = data.optJSONObject("dash")
        val dashVideo = parseDashArray(dash?.optJSONArray("video"))
        val dashAudio = parseDashArray(dash?.optJSONArray("audio"))

        val dolbyObj = dash?.optJSONObject("dolby")
        val dolby = if (dolbyObj != null) {
            val type = dolbyObj.optInt("type", 0)
            val audios = parseDashArray(dolbyObj.optJSONArray("audio"))
            DolbyAudio(type, audios)
        } else null

        val flacObj = dash?.optJSONObject("flac")
        val flac = if (flacObj != null) {
            val display = flacObj.optBoolean("display", false)
            val audio = flacObj.optJSONObject("audio")?.let { parseDashItem(it) }
            FlacAudio(display, audio)
        } else null

        return PlayInfo(
            code = code,
            message = msg,
            qnSelected = qnSelected,
            format = format,
            timeLengthMs = timeLengthMs,
            acceptDescription = acceptDesc,
            acceptQuality = acceptQuality,
            durl = durlList,
            dashVideo = dashVideo,
            dashAudio = dashAudio,
            dolby = dolby,
            flac = flac,
            raw = root
        )
    }

    private fun parseDurl(arr: JSONArray?): List<Durl> {
        if (arr == null) return emptyList()
        val out = ArrayList<Durl>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val order = o.optInt("order", i + 1)
            val len = o.optLong("length", 0L)
            val size = o.optLong("size", 0L)
            val url = o.optString("url", "")
            val backups = (o.optJSONArray("backup_url") ?: o.optJSONArray("backupUrl")).toStringList()
            out += Durl(order, len, size, url, backups)
        }
        return out
    }

    private fun parseDashArray(arr: JSONArray?): List<DashStream> {
        if (arr == null) return emptyList()
        val out = ArrayList<DashStream>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseDashItem(o)?.let(out::add)
        }
        return out
    }

    private fun parseDashItem(o: JSONObject): DashStream? {
        val id = o.optInt("id", -1)
        val baseUrl = o.optString("baseUrl", o.optString("base_url", ""))
        val backups = (o.optJSONArray("backupUrl") ?: o.optJSONArray("backup_url")).toStringList()
        val bandwidth = o.optLong("bandwidth", 0L)
        val mimeType = o.optString("mimeType", o.optString("mime_type", ""))
        val codecs = o.optString("codecs", "")
        val width = o.optInt("width", 0)
        val height = o.optInt("height", 0)
        val frameRate = o.optString("frameRate", o.optString("frame_rate", ""))
        val codecid = o.optInt("codecid", 0)
        if (baseUrl.isEmpty()) return null
        return DashStream(
            id = id,
            baseUrl = baseUrl,
            backupUrls = backups,
            bandwidth = bandwidth,
            mimeType = mimeType,
            codecs = codecs,
            width = width,
            height = height,
            frameRate = frameRate,
            codecid = codecid
        )
    }

    // 请求封装 //

    private suspend fun executeGetAsText(url: HttpUrl): String {
        val cookieMap = cookieRepo.getCookiesOnce()
        val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_WEB_UA)
            .header("Referer", REFERER)
            .apply { headerIfNotBlank("Cookie", cookieHeader) }
            .get()
            .build()

        return http.newCall(req).executeOrThrow().use { resp ->
            resp.body?.string().orEmpty()
        }
    }

    private suspend fun getJsonWbi(baseUrl: String, params: Map<String, String>): JSONObject {
        val signed = signWbiUrl(baseUrl, params)
        val text = executeGetAsText(signed)
        return JSONObject(text)
    }

    private suspend fun getJson(baseUrl: String, params: Map<String, String>): JSONObject {
        val builder = baseUrl.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val url = builder.build()
        val text = executeGetAsText(url)
        return JSONObject(text)
    }

    // Wbi 签名 //

    private val keyMutex = Mutex()
    @Volatile
    private var cachedMixinKey: String? = null
    @Volatile
    private var cachedAt: Long = 0L

    /**
     * 将原始参数做 Wbi 加签，返回完整 URL
     */
    private suspend fun signWbiUrl(base: String, paramsIn: Map<String, String>): HttpUrl {
        val mixinKey = getOrRefreshMixinKey()

        // 复制并加入 wts；对参数值做特殊字符过滤
        val params = paramsIn.mapValues { (_, v) -> filterValue(v) }.toMutableMap()
        val wts = (System.currentTimeMillis() / 1000L).toString()
        params["wts"] = wts

        // key 排序后拼接
        val sorted = params.toSortedMap()
        val query = sorted.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }

        val wRid = md5(query + mixinKey)

        val httpUrlBuilder = base.toHttpUrl().newBuilder()
        sorted.forEach { (k, v) -> httpUrlBuilder.addQueryParameter(k, v) }
        httpUrlBuilder.addQueryParameter("w_rid", wRid)
        return httpUrlBuilder.build()
    }

    private fun filterValue(v: String): String {
        return v.replace(Regex("""[!'()*]"""), "")
    }

    private suspend fun getOrRefreshMixinKey(): String {
        val now = System.currentTimeMillis()
        cachedMixinKey?.let { mk ->
            if (now - cachedAt < WBI_CACHE_MS) return mk
        }
        return keyMutex.withLock {
            val againNow = System.currentTimeMillis()
            if (cachedMixinKey != null && againNow - cachedAt < WBI_CACHE_MS) {
                return@withLock cachedMixinKey!!
            }
            val mk = fetchMixinKeyFromNav()
            cachedMixinKey = mk
            cachedAt = againNow
            mk
        }
    }

    /**
     * 拉取 nav，解析 wbi_img / wbi_sub 的文件名（不含后缀）作为 imgKey/subKey，
     * 然后执行 mixin 索引表，取前 32 位为最终 key
     */
    private suspend fun fetchMixinKeyFromNav(): String = withContext(Dispatchers.IO) {
        val cookieMap = cookieRepo.getCookiesOnce()
        val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

        val req = Request.Builder()
            .url(NAV_URL)
            .header("User-Agent", DEFAULT_WEB_UA)
            .header("Referer", REFERER)
            .apply { headerIfNotBlank("Cookie", cookieHeader) }
            .get()
            .build()

        val text = http.newCall(req).executeOrThrow().use { it.body?.string().orEmpty() }
        val jo = JSONObject(text)
        val data = jo.optJSONObject("data") ?: JSONObject()
        val wbiImg = data.optJSONObject("wbi_img") ?: JSONObject()
        val imgUrl = wbiImg.optString("img_url", "")
        val subUrl = wbiImg.optString("sub_url", "")

        val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
        val subKey = subUrl.substringAfterLast('/').substringBefore('.')
        val raw = imgKey + subKey
        val mixed = StringBuilder()
        for (idx in MIXIN_INDEX) {
            if (idx < raw.length) mixed.append(raw[idx])
        }
        val result = if (mixed.length >= 32) mixed.substring(0, 32) else mixed.toString()
        Log.d(TAG, "Refreshed Wbi mixin key: $result")
        result
    }

    // 工具 / 扩展 //

    /** 只在值非空且非空白时设置 Header（避免递归） */
    private fun Request.Builder.headerIfNotBlank(name: String, value: String?): Request.Builder {
        return if (!value.isNullOrBlank()) this.header(name, value) else this
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out += optString(i, "")
        return out
    }

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        val out = ArrayList<Int>(length())
        for (i in 0 until length()) out += optInt(i)
        return out
    }

    @Throws(IOException::class)
    private fun okhttp3.Call.executeOrThrow(): Response {
        val resp = execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            val text = resp.body?.string().orEmpty()
            resp.close()
            throw IOException("HTTP $code: $text")
        }
        return resp
    }

    private fun md5(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(v: String): String {
        return URLEncoder.encode(v, Charsets.UTF_8.name())
            .replace("+", "%20")
    }

    private fun ensureHttps(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun stripHtml(html: String): String {
        return html.replace(Regex("<.*?>"), "")
    }

    private fun parseDurationToSeconds(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val parts = s.split(':').mapNotNull { it.toIntOrNull() }
        var total = 0
        for (p in parts) total = total * 60 + p
        return total
    }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name)) optLong(name) else null

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name)) optInt(name) else null

    // 将 PlayInfo 映射为统一的音频流结构 //

    /**
     * 合并 dash.audio + dash.dolby.audio + dash.flac.audio
     * - 普通音轨：qualityTag = null
     * - 杜比音轨：qualityTag = "dolby"
     * - Hi-Res（flac）：qualityTag = "hires"
     *
     * bitrateKbps = bandwidth(Byte/s)*8/1000，做非负保护。
     */
    fun PlayInfo.toAudioStreamInfos(): List<BiliAudioStreamInfo> {
        val list = mutableListOf<BiliAudioStreamInfo>()

        // 普通音轨
        for (a in dashAudio) {
            list += BiliAudioStreamInfo(
                id = a.id,
                mimeType = a.mimeType.ifBlank { "audio/mp4" },
                bitrateKbps = max(0, ((a.bandwidth * 8) / 1000).toInt()),
                qualityTag = null,
                url = a.baseUrl
            )
        }

        // 杜比音轨
        dolby?.audios?.forEach { a ->
            list += BiliAudioStreamInfo(
                id = a.id,
                mimeType = a.mimeType.ifBlank { "audio/eac3" },
                bitrateKbps = max(0, ((a.bandwidth * 8) / 1000).toInt()),
                qualityTag = "dolby",
                url = a.baseUrl
            )
        }

        // Hi-Res（flac）
        flac?.audio?.let { a ->
            list += BiliAudioStreamInfo(
                id = a.id,
                mimeType = a.mimeType.ifBlank { "audio/flac" },
                bitrateKbps = max(0, ((a.bandwidth * 8) / 1000).toInt()),
                qualityTag = "hires",
                url = a.baseUrl
            )
        }

        return list
    }

    /**
     * 通过 bvid 获取视频分页列表
     */
    suspend fun getVideoPageList(bvid: String): List<VideoPage> = withContext(Dispatchers.IO) {
        val jo = getJson(PAGELIST_URL, mapOf("bvid" to bvid))
        parsePageListResponse(jo)
    }

    /**
     * 通过 aid 获取视频分页列表
     */
    suspend fun getVideoPageList(aid: Long): List<VideoPage> = withContext(Dispatchers.IO) {
        val jo = getJson(PAGELIST_URL, mapOf("aid" to aid.toString()))
        parsePageListResponse(jo)
    }

    /**
     * 解析分页列表响应
     */
    private fun parsePageListResponse(jo: JSONObject): List<VideoPage> {
        val data = jo.optJSONArray("data") ?: JSONArray()
        val pages = ArrayList<VideoPage>(data.length())

        for (i in 0 until data.length()) {
            val p = data.optJSONObject(i) ?: continue
            val dim = p.optJSONObject("dimension") ?: JSONObject()
            pages += VideoPage(
                cid = p.optLong("cid"),
                page = p.optInt("page"),
                part = p.optString("part"),
                durationSec = p.optInt("duration"),
                width = dim.optInt("width"),
                height = dim.optInt("height")
            )
        }

        return pages
    }

}

/**
 * 适配器：用 BiliClient 作为音频数据源，接到 BiliPlaybackRepository
 */
class BiliClientAudioDataSource(
    private val client: BiliClient
) : BiliAudioDataSource {
    override suspend fun fetchAudioStreams(bvid: String, cid: Long): List<BiliAudioStreamInfo> {
        val info = client.getPlayInfoByBvid(
            bvid = bvid,
            cid = cid,
            opts = BiliClient.PlayOptions()
        )
        return client.run { info.toAudioStreamInfos() }
    }
}
