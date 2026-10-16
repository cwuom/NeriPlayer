package moe.ouom.neriplayer.platform.netease.encyclopedia

import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaAward
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaCreditRole
import org.json.JSONArray
import org.json.JSONObject

private const val BASIC_BLOCK_CODE = "SONG_PLAY_ABOUT_SONG_BASIC"

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
