package moe.ouom.neriplayer.platform.netease.playmode

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.netease.mapping.parseNeteaseSongDetail
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网易云「心动模式」(`/api/playmode/intelligence/list`) 的解析结果。
 *
 * 未登录（code=301）与「已登录但没有推荐」（code=200 且 data 为空）都属于预期情况，
 * 调用方应当静默降级，而不是当成错误抛给用户。
 */
sealed class PlaymodeIntelligenceResult {
    data class Success(val song: SongItem) : PlaymodeIntelligenceResult()

    /** code 301：未登录，属于预期情况，调用方静默降级 */
    data object RequiresLogin : PlaymodeIntelligenceResult()

    /** code 200 但 data 为空数组：已登录但没有推荐结果 */
    data object Empty : PlaymodeIntelligenceResult()

    /** 其它 code / 解析失败 / 结构异常 */
    data class Failure(val code: Int, val message: String?) : PlaymodeIntelligenceResult()
}

/**
 * 心动模式响应解析器。
 *
 * 纯函数：任何非法输入（非法 JSON、字段缺失、字段类型不符）都收敛成
 * [PlaymodeIntelligenceResult.Failure]，绝不向外抛异常。
 *
 * 实测事实：
 * - 2026-10-19 匿名探测：返回 `{"code":301,"message":null}`。
 * - 2026-10-19 真机抓包（小米 22041211AC，网易云已登录）：返回
 *   `{"code":200,"message":"SUCCESS","data":[{"id":610141,"alg":"Alg_AI_One_28018269:610141_simArtist-RH",
 *   "recommended":false,"songInfo":{...完整歌曲对象...}}]}`。
 *
 * 也就是说 `data[]` 的每个元素是**包装对象**而非歌曲本身：
 * 外层只有 `id` / `alg` / `recommended`，真正的歌曲字段（name/ar/al/dt/id…）在
 * **[songInfo]** 里。若按「元素即歌曲」解析，`parseNeteaseSong` 会因为
 * 取不到 name/ar/al 而对每个元素返回 null（见 [moe.ouom.neriplayer.platform.netease.mapping.parseNeteaseSongDetail]）。
 *
 * 因此这里按三种形状依次兼容，取到第一个可解析的歌曲即返回：
 *  1. `songInfo` 是对象 → 用它（实测形状，**生产主要路径**）；
 *  2. 元素本身就是歌曲（id/name/ar/al/dt）→ 兼容结构变更前的直传形状；
 *  3. `song` / `songDetail` / `data` 兜底，防止字段再次改名时彻底失效。
 *
 * 该接口在生产环境走 **eapi** 加密（`/eapi/playmode/intelligence/list`），见
 * [moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient.getPlaymodeIntelligenceListCancellable]。
 */
object PlaymodeIntelligenceResponseParser {

    /** 解析失败时统一使用的兜底 code，接口本身不会返回 -1。 */
    const val CODE_UNPARSABLE = -1

    private const val CODE_OK = 200
    private const val CODE_REQUIRES_LOGIN = 301

    /** `data[]` 元素中歌曲对象可能出现的键，按实测优先级排列。 */
    private val SONG_CONTAINER_KEYS = listOf("songInfo", "song", "songDetail", "data")

    fun parse(raw: String): PlaymodeIntelligenceResult {
        val root = try {
            JSONObject(raw)
        } catch (error: Exception) {
            return PlaymodeIntelligenceResult.Failure(
                CODE_UNPARSABLE,
                error.message ?: "响应不是合法 JSON"
            )
        }

        val code = root.optInt("code", CODE_UNPARSABLE)
        if (code == CODE_REQUIRES_LOGIN) return PlaymodeIntelligenceResult.RequiresLogin
        if (code != CODE_OK) {
            return PlaymodeIntelligenceResult.Failure(
                code,
                root.optCleanString("message") ?: root.optCleanString("msg")
            )
        }

        // data 缺失 / 为 null 与空数组同义：已登录但没有推荐结果
        if (!root.has("data") || root.isNull("data")) return PlaymodeIntelligenceResult.Empty

        val data = root.optJSONArray("data")
            ?: root.optJSONObject("data")?.optJSONArray("songs")
            ?: return PlaymodeIntelligenceResult.Failure(CODE_UNPARSABLE, "data 结构无法识别")
        if (data.length() == 0) return PlaymodeIntelligenceResult.Empty

        for (index in 0 until data.length()) {
            val songJson = data.optJSONObject(index) ?: continue
            val song = parseSong(songJson) ?: continue
            return PlaymodeIntelligenceResult.Success(song)
        }
        return PlaymodeIntelligenceResult.Failure(CODE_UNPARSABLE, "data 中没有可解析的歌曲")
    }

    /**
     * `data[]` 元素 → [SongItem]。
     *
     * 实测形状是包装对象（外层 `id`/`alg`/`recommended` + `songInfo` 承载歌曲字段），
     * 因此先按 [SONG_CONTAINER_KEYS] 找歌曲容器；容器缺失时再回退到「元素本身即歌曲」，
     * 兼容结构变更前的直传形状。
     *
     * 歌曲对象与 `/api/song/detail` 的 `songs[]` 元素同构（id/name/ar/al/dt…），
     * 因此这里把它包成 `{"code":200,"songs":[...]}` 交给既有的 [parseNeteaseSongDetail]，
     * 字段映射（artist 用 " / " 拼接、channelId="netease"、audioId=songId、durationMs=dt 等）
     * 与搜索/详情共用同一份实现，不复制第二份逻辑。
     *
     * 外层 `id` 只作为兜底：当 `songInfo` 里没有 `id`（理论上不该发生）时补上，
     * 否则 [parseNeteaseSong] 会因 `id <= 0` 丢掉这首歌。
     */
    private fun parseSong(element: JSONObject): SongItem? = try {
        val songJson = resolveSongContainer(element) ?: return null
        if (!songJson.has("id") || songJson.optLong("id", 0L) <= 0L) {
            val outerId = element.optLong("id", 0L)
            if (outerId > 0L) {
                songJson.put("id", outerId)
            }
        }
        parseNeteaseSongDetail(
            JSONObject()
                .put("code", CODE_OK)
                .put("songs", JSONArray().put(songJson))
                .toString()
        )
    } catch (error: Exception) {
        null
    }

    /**
     * 从 `data[]` 元素里取出承载歌曲字段的对象。
     *
     * 按 [SONG_CONTAINER_KEYS] 顺序找**第一个对象类型的值**；都不命中时
     * 若元素自身带 `name`，则认为它本身就是歌曲对象（直传形状）。
     * 返回 null 表示这个元素不是可识别的歌曲包装。
     */
    private fun resolveSongContainer(element: JSONObject): JSONObject? {
        for (key in SONG_CONTAINER_KEYS) {
            val candidate = element.optJSONObject(key)
            if (candidate != null) return candidate
        }
        // 直传形状兜底：元素本身就是歌曲（有 name 才敢认，避免把纯包装对象误当歌曲）
        return element.takeIf { it.has("name") }
    }
}

/** org.json 会把 JSON null 读成字符串 "null"，这里统一收敛成 null。 */
private fun JSONObject.optCleanString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
}
