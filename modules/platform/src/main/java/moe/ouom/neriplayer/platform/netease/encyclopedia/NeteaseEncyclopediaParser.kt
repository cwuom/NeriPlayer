package moe.ouom.neriplayer.platform.netease.encyclopedia

import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaAward
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaArtistInfo
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaCreditRole
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaRelatedPlaylist
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaSimilarSong
import org.json.JSONArray
import org.json.JSONObject

private const val BASIC_BLOCK_CODE = "SONG_PLAY_ABOUT_SONG_BASIC"
private const val SIMILAR_SONG_BLOCK_CODE = "SONG_PLAY_ABOUT_SIMILAR_SONG"
private const val RELATED_PLAYLIST_BLOCK_CODE = "SONG_PLAY_ABOUT_RELATED_PLAYLIST"

/**
 * 网易云「音乐百科」基础板块的解析结果。
 *
 * 所有字段都直接来自接口，缺失即留空；禁止按歌名或歌手推断任何属性。
 */
internal data class NeteaseEncyclopediaSummary(
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val language: String? = null,
    val bpm: Int? = null,
    val awards: List<EncyclopediaAward> = emptyList(),
    val appearances: List<String> = emptyList()
)

/**
 * 解析 `SONG_PLAY_ABOUT_SONG_BASIC` 板块。
 *
 * 歌曲不存在时接口同样返回 code=200，但不会带该板块，此时返回 null 表示「暂无资料」。
 * 响应不是合法 JSON 时抛异常，由调用方判定为可重试的失败。
 */
internal fun parseNeteaseEncyclopediaSummary(raw: String): NeteaseEncyclopediaSummary? {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return null
    val blocks = root.optJSONObject("data")?.optJSONArray("blocks") ?: return null
    val basic = blocks.firstObjectOrNull { it.optTrimmedString("code") == BASIC_BLOCK_CODE }
        ?: return null
    val creatives = basic.optJSONArray("creatives") ?: return null
    return NeteaseEncyclopediaSummary(
        genres = creatives.resourceTitlesOf("songTag"),
        tags = creatives.resourceTitlesOf("songBizTag"),
        language = creatives.firstCreativeOf("language")?.firstTextLinkText(),
        bpm = creatives.firstCreativeOf("bpm")?.firstTextLinkText()?.toIntOrNull()?.takeIf { it > 0 },
        awards = creatives.awardsOf("songAward"),
        appearances = creatives.resourceTitlesOf("entertainment")
    )
}

/** 解析创作者角色列表；无数据（songCreatorsRoleVos 为 null）或响应异常时返回空列表。 */
internal fun parseNeteaseEncyclopediaCredits(raw: String): List<EncyclopediaCreditRole> {
    val root = jsonObjectOrNull(raw) ?: return emptyList()
    if (root.optInt("code", -1) != 200) return emptyList()
    val roles = root.optJSONObject("data")?.optJSONArray("songCreatorsRoleVos") ?: return emptyList()
    val result = ArrayList<EncyclopediaCreditRole>(roles.length())
    for (index in 0 until roles.length()) {
        val role = roles.optJSONObject(index) ?: continue
        val roleName = role.optTrimmedString("roleName") ?: continue
        val names = role.creatorNames()
        if (names.isNotEmpty()) result.add(EncyclopediaCreditRole(role = roleName, names = names))
    }
    return result
}

/** 创作者角色列表解析后的简要档案（头衔只保留角色名与作者名，不含头像等额外信息）。 */
internal data class EncyclopediaSongDetail(
    val name: String,
    val artist: String,
    val albumId: Long?,
    val albumName: String?,
    val albumPicUrl: String? = null,
    val albumCompany: String? = null,
    val publishTimeMs: Long? = null,
    val durationMs: Long? = null,
    val artistId: Long? = null
)

/**
 * 解析 `/api/song/detail` 的第一首歌。
 *
 * code != 200 或 songs 为空返回 null（调用方据此把专辑/发行信息全部留空）。
 * 响应不是合法 JSON 时抛异常，由调用方判定为可重试的失败。
 */
internal fun parseNeteaseSongDetail(raw: String): EncyclopediaSongDetail? {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return null
    val songs = root.optJSONArray("songs") ?: return null
    val song = songs.optJSONObject(0) ?: return null
    val album = song.optJSONObject("album") ?: JSONObject()
    val artists = song.optJSONArray("artists") ?: JSONArray()
    val artistName = StringBuilder()
    for (index in 0 until artists.length()) {
        val artist = artists.optJSONObject(index) ?: continue
        val name = artist.optTrimmedString("name") ?: continue
        if (artistName.isNotEmpty()) artistName.append("/")
        artistName.append(name)
    }
    return EncyclopediaSongDetail(
        name = song.optTrimmedString("name") ?: return null,
        artist = artistName.toString(),
        albumId = album.optLongOrNull("id"),
        albumName = album.optTrimmedString("name"),
        albumPicUrl = album.optTrimmedString("picUrl"),
        albumCompany = album.optTrimmedString("company"),
        publishTimeMs = song.optLongOrNull("publishTime"),
        durationMs = song.optLongOrNull("duration"),
        artistId = artists.optJSONObject(0)?.optLongOrNull("id")
    )
}

/**
 * 解析 `/api/v1/artist/{id}` 的艺人简要资料。
 *
 * code != 200（含 404）或无 artist 对象返回 null。
 * [EncyclopediaArtistInfo.fansCount] 恒为 null：该端点没有粉丝数字段，
 * 而粉丝数专用接口实测不可用，禁止用任何方式估算。
 */
internal fun parseNeteaseArtistInfo(raw: String): EncyclopediaArtistInfo? {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return null
    val artist = root.optJSONObject("artist") ?: return null
    val artistId = artist.optLongOrNull("id") ?: return null
    val name = artist.optTrimmedString("name") ?: return null
    return EncyclopediaArtistInfo(
        artistId = artistId,
        name = name,
        picUrl = artist.optTrimmedString("picUrl"),
        briefDesc = artist.optTrimmedString("briefDesc"),
        fansCount = null,
        albumSize = artist.optIntOrNull("albumSize"),
        musicSize = artist.optIntOrNull("musicSize")
    )
}

/**
 * 解析相似歌曲板块。
 *
 * 与 [parseNeteaseEncyclopediaSummary] 同源（`/api/song/play/about/block/page` 的原始返回），
 * 因此调用方可以只请求一次、由这里与 summary 分别解析，避免重复请求。
 * 非法 JSON 或无该板块/无有效条目时返回空列表。
 */
internal fun parseNeteaseSimilarSongs(raw: String): List<EncyclopediaSimilarSong> {
    val root = jsonObjectOrNull(raw) ?: return emptyList()
    if (root.optInt("code", -1) != 200) return emptyList()
    val blocks = root.optJSONObject("data")?.optJSONArray("blocks") ?: return emptyList()
    val block = blocks.firstObjectOrNull { it.optTrimmedString("code") == SIMILAR_SONG_BLOCK_CODE }
        ?: return emptyList()
    val creatives = block.optJSONArray("creatives") ?: return emptyList()
    val result = ArrayList<EncyclopediaSimilarSong>(creatives.length())
    for (index in 0 until creatives.length()) {
        val resources = creatives.optJSONObject(index)?.optJSONArray("resources") ?: continue
        for (resourceIndex in 0 until resources.length()) {
            val resource = resources.optJSONObject(resourceIndex) ?: continue
            if (resource.optTrimmedString("resourceType") != "SONG") continue
            if (resource.optBoolean("valid", false).not()) continue
            val songId = resource.optLongOrNull("resourceId") ?: continue
            val element = resource.optJSONObject("uiElement") ?: continue
            val name = element.optJSONObject("mainTitle")?.optTrimmedString("title") ?: continue
            val artist = element.subTitleTexts().firstOrNull() ?: continue
            val images = element.optJSONArray("images") ?: JSONArray()
            val coverUrl = images.optJSONObject(0)
                ?.optTrimmedString("imageUrl")
                ?.replace("http://", "https://")
            result.add(
                EncyclopediaSimilarSong(
                    songId = songId,
                    name = name,
                    artist = artist,
                    coverUrl = coverUrl
                )
            )
        }
    }
    return result
}

/**
 * 解析相关歌单板块。
 *
 * [EncyclopediaRelatedPlaylist.trackCount] 恒为 null：接口只给播放量，歌单歌曲数无从取得。
 * 非法 JSON 或无该板块/无有效条目时返回空列表。
 */
internal fun parseNeteaseRelatedPlaylists(raw: String): List<EncyclopediaRelatedPlaylist> {
    val root = jsonObjectOrNull(raw) ?: return emptyList()
    if (root.optInt("code", -1) != 200) return emptyList()
    val blocks = root.optJSONObject("data")?.optJSONArray("blocks") ?: return emptyList()
    val block = blocks.firstObjectOrNull { it.optTrimmedString("code") == RELATED_PLAYLIST_BLOCK_CODE }
        ?: return emptyList()
    val creatives = block.optJSONArray("creatives") ?: return emptyList()
    val result = ArrayList<EncyclopediaRelatedPlaylist>(creatives.length())
    for (index in 0 until creatives.length()) {
        val resources = creatives.optJSONObject(index)?.optJSONArray("resources") ?: continue
        for (resourceIndex in 0 until resources.length()) {
            val resource = resources.optJSONObject(resourceIndex) ?: continue
            if (resource.optTrimmedString("resourceType") != "PLAYLIST") continue
            val playlistId = resource.optLongOrNull("resourceId") ?: continue
            val element = resource.optJSONObject("uiElement") ?: continue
            val name = element.optJSONObject("mainTitle")?.optTrimmedString("title") ?: continue
            val images = element.optJSONArray("images") ?: JSONArray()
            val coverUrl = images.optJSONObject(0)
                ?.optTrimmedString("imageUrl")
                ?.replace("http://", "https://")
            val resourceExt = resource.optJSONObject("resourceExt") ?: JSONObject()
            val playCount = resourceExt.optLongOrNull("playCount")
            result.add(
                EncyclopediaRelatedPlaylist(
                    playlistId = playlistId,
                    name = name,
                    coverUrl = coverUrl,
                    playCount = playCount,
                    trackCount = null
                )
            )
        }
    }
    return result
}

private fun jsonObjectOrNull(raw: String): JSONObject? {
    return try {
        JSONObject(raw)
    } catch (_: Exception) {
        null
    }
}

private inline fun JSONArray.firstObjectOrNull(predicate: (JSONObject) -> Boolean): JSONObject? {
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        if (predicate(item)) return item
    }
    return null
}

private fun JSONArray.creativesOfType(type: String): List<JSONObject> {
    val result = ArrayList<JSONObject>(length())
    for (index in 0 until length()) {
        val creative = optJSONObject(index) ?: continue
        if (creative.optTrimmedString("creativeType") == type) result.add(creative)
    }
    return result
}

private fun JSONArray.firstCreativeOf(type: String): JSONObject? = creativesOfType(type).firstOrNull()

private fun JSONArray.resourceTitlesOf(type: String): List<String> =
    creativesOfType(type).flatMap { it.resourceTitles() }

private fun JSONArray.awardsOf(type: String): List<EncyclopediaAward> =
    creativesOfType(type).flatMap { it.awardEntries() }

private fun JSONObject.resourceTitles(): List<String> {
    val resources = optJSONArray("resources") ?: return emptyList()
    val titles = ArrayList<String>(resources.length())
    for (index in 0 until resources.length()) {
        val title = resources.optJSONObject(index)
            ?.optJSONObject("uiElement")
            ?.optJSONObject("mainTitle")
            ?.optTrimmedString("title")
        if (title != null) titles.add(title)
    }
    return titles
}

private fun JSONObject.awardEntries(): List<EncyclopediaAward> {
    val resources = optJSONArray("resources") ?: return emptyList()
    val awards = ArrayList<EncyclopediaAward>(resources.length())
    for (index in 0 until resources.length()) {
        val element = resources.optJSONObject(index)?.optJSONObject("uiElement") ?: continue
        val title = element.optJSONObject("mainTitle")?.optTrimmedString("title") ?: continue
        val detail = element.subTitleTexts().joinToString(" ").takeIf { it.isNotEmpty() }
        awards.add(EncyclopediaAward(title = title, detail = detail))
    }
    return awards
}

private fun JSONObject.firstTextLinkText(): String? {
    val links = optJSONObject("uiElement")?.optJSONArray("textLinks") ?: return null
    for (index in 0 until links.length()) {
        val text = links.optJSONObject(index)?.optTrimmedString("text")
        if (text != null) return text
    }
    return null
}

private fun JSONObject.subTitleTexts(): List<String> {
    val subTitles = optJSONArray("subTitles") ?: return emptyList()
    val texts = ArrayList<String>(subTitles.length())
    for (index in 0 until subTitles.length()) {
        subTitles.optJSONObject(index)?.optTrimmedString("title")?.let(texts::add)
    }
    return texts
}

private fun JSONObject.creatorNames(): List<String> {
    val creators = optJSONArray("creatorMetaVOS") ?: return emptyList()
    val names = ArrayList<String>(creators.length())
    for (index in 0 until creators.length()) {
        creators.optJSONObject(index)?.optTrimmedString("artistName")?.let(names::add)
    }
    return names
}

private fun JSONObject.optTrimmedString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").trim().takeIf { it.isNotEmpty() }
}

/** 缺失、null、非数字、非正数一律返回 null，避免用 0 冒充未知。 */
private fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key).takeIf { it > 0L }
}

/** 缺失或非数字返回 null；0 是合法值（艺人可能确实没有专辑），因此只过滤负数。 */
private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key).takeIf { it >= 0 }
}
