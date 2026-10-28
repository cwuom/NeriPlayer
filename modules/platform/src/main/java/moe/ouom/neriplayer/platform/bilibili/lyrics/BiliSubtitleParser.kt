package moe.ouom.neriplayer.platform.bilibili.lyrics

import kotlin.math.roundToLong
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleCue
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleTrack
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.json.JSONObject

private const val SUBTITLE_AI_LANGUAGE_PREFIX = "ai-"
private const val SECONDS_TO_MS = 1000.0

/**
 * 把 `subtitle_url` 补全为可直接请求的地址。
 *
 * 接口返回的是协议相对地址（`//aisubtitle.hdslb.com/...`），旧数据里还存在 `http://`。
 */
internal fun normalizeBiliSubtitleUrl(rawUrl: String?): String {
    val trimmed = rawUrl?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    return when {
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("http://", ignoreCase = true) -> "https://${trimmed.substring(7)}"
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        else -> "https://$trimmed"
    }
}

/**
 * 解析播放器信息里的 `data.subtitle`，只保留可以直接播放的字幕轨道。
 */
internal fun parseBiliSubtitleTracks(subtitle: JSONObject?): List<BiliSubtitleTrack> {
    val items = subtitle?.optJSONArray("subtitles") ?: return emptyList()
    val tracks = ArrayList<BiliSubtitleTrack>(items.length())
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val url = normalizeBiliSubtitleUrl(item.optString("subtitle_url"))
        if (url.isEmpty()) continue
        val lan = item.optString("lan").trim()
        val lanDoc = item.optString("lan_doc").trim()
        tracks += BiliSubtitleTrack(
            id = item.optLong("id", item.optString("id_str").toLongOrNull() ?: 0L),
            lan = lan,
            lanDoc = lanDoc.ifEmpty { lan },
            url = url,
            isAiGenerated = isAiBiliSubtitleTrack(item, lan)
        )
    }
    return tracks
}

private fun isAiBiliSubtitleTrack(item: JSONObject, lan: String): Boolean =
    item.optInt("type", 0) == 1 ||
        lan.startsWith(SUBTITLE_AI_LANGUAGE_PREFIX, ignoreCase = true)

/**
 * 轨道候选顺序：显式选择优先，其次中文人工、中文自动、其他人工、其他自动。
 *
 * [aiSubtitleAllowed] 为 false 时只保留人工上传的字幕；显式选中的自动生成轨道仍然生效。
 * 顺序同时用于逐个尝试读取，单条轨道读取失败时还有后备轨道可用。
 */
internal fun biliSubtitleTrackCandidates(
    tracks: List<BiliSubtitleTrack>,
    preferredLanguage: String = "",
    aiSubtitleAllowed: Boolean = true
): List<BiliSubtitleTrack> {
    val usable = tracks.filter { it.url.isNotBlank() }
    if (usable.isEmpty()) return emptyList()
    val preferred = preferredLanguage.trim()
    val explicit = if (preferred.isEmpty()) {
        emptyList()
    } else {
        usable.filter { matchesBiliSubtitleLanguage(it.lan, preferred) }
            .sortedWith(
                compareBy<BiliSubtitleTrack> { !it.lan.equals(preferred, ignoreCase = true) }
                    .thenBy { it.isAiGenerated }
                    .thenBy { it.lan }
            )
    }
    val automatic = usable
        .filter { track -> explicit.none { it === track } }
        .filter { aiSubtitleAllowed || !it.isAiGenerated }
        .sortedWith(
            compareBy<BiliSubtitleTrack> { if (isChineseBiliSubtitleLanguage(it.lan)) 0 else 1 }
                .thenBy { it.isAiGenerated }
                .thenBy { it.lan }
        )
    return explicit + automatic
}

/** 自动选择规则下的第一条轨道 */
internal fun selectBiliSubtitleTrack(
    tracks: List<BiliSubtitleTrack>,
    preferredLanguage: String = "",
    aiSubtitleAllowed: Boolean = true
): BiliSubtitleTrack? = biliSubtitleTrackCandidates(
    tracks = tracks,
    preferredLanguage = preferredLanguage,
    aiSubtitleAllowed = aiSubtitleAllowed
).firstOrNull()

/**
 * 判断轨道是否命中偏好语种。
 *
 * 人工轨道按主语言比较，`zh-CN`、`zh-Hans` 这类写法差异不会导致选不中；
 * 自动生成轨道只做精确匹配，避免与同语言的人工字幕混淆。
 */
internal fun matchesBiliSubtitleLanguage(
    trackLan: String,
    preferredLanguage: String
): Boolean {
    val track = trackLan.trim().lowercase()
    val preferred = preferredLanguage.trim().lowercase()
    if (track.isEmpty() || preferred.isEmpty()) return false
    if (track == preferred) return true
    val trackIsAi = track.startsWith(SUBTITLE_AI_LANGUAGE_PREFIX)
    val preferredIsAi = preferred.startsWith(SUBTITLE_AI_LANGUAGE_PREFIX)
    if (trackIsAi != preferredIsAi) return false
    if (preferredIsAi) return false
    if (track.primarySubtitleLanguage() != preferred.primarySubtitleLanguage()) return false
    // 同为中文时区分简繁, 同时把 zh-CN/zh-SG 与 zh-Hans、zh-TW/zh-HK 与 zh-Hant 视为同一种写法
    val trackScript = track.subtitleScript()
    val preferredScript = preferred.subtitleScript()
    return preferredScript == BiliSubtitleScript.UNKNOWN ||
        trackScript == BiliSubtitleScript.UNKNOWN ||
        trackScript == preferredScript
}

private enum class BiliSubtitleScript { HANS, HANT, UNKNOWN }

private fun String.subtitleScript(): BiliSubtitleScript = when {
    contains("hant") || contains("tw") || contains("hk") || contains("mo") ->
        BiliSubtitleScript.HANT
    contains("hans") || contains("cn") || contains("sg") -> BiliSubtitleScript.HANS
    else -> BiliSubtitleScript.UNKNOWN
}

private fun String.primarySubtitleLanguage(): String =
    removePrefix(SUBTITLE_AI_LANGUAGE_PREFIX).substringBefore('-').substringBefore('_')

/**
 * 选择用于翻译行的轨道：必须是另一种语言，同语种的自动生成字幕视为重复内容。
 */
internal fun selectBiliTranslatedSubtitleTrack(
    primary: BiliSubtitleTrack,
    tracks: List<BiliSubtitleTrack>,
    aiSubtitleAllowed: Boolean = true
): BiliSubtitleTrack? {
    val primaryBase = primary.lan.primarySubtitleLanguage()
    return tracks
        .filter { it.url.isNotBlank() }
        .filter { !it.lan.equals(primary.lan, ignoreCase = true) }
        .filter { it.lan.primarySubtitleLanguage() != primaryBase }
        .filter { aiSubtitleAllowed || !it.isAiGenerated }
        .sortedWith(compareBy<BiliSubtitleTrack> { it.isAiGenerated }.thenBy { it.lan })
        .firstOrNull()
}

internal fun isChineseBiliSubtitleLanguage(lan: String): Boolean {
    val normalized = lan.trim().lowercase()
    if (normalized.isEmpty()) return false
    val base = normalized.removePrefix(SUBTITLE_AI_LANGUAGE_PREFIX)
    return base == "zh" || base.startsWith("zh-") || base.startsWith("zh_")
}

/**
 * 解析字幕文件的 `body` 数组，`from` / `to` 的单位是秒。
 */
internal fun parseBiliSubtitleCues(payload: JSONObject): List<BiliSubtitleCue> {
    val body = payload.optJSONArray("body") ?: return emptyList()
    val cues = ArrayList<BiliSubtitleCue>(body.length())
    for (index in 0 until body.length()) {
        val item = body.optJSONObject(index) ?: continue
        val content = item.optString("content").trim()
        if (content.isEmpty()) continue
        val startMs = subtitleSecondsToMs(item.optDouble("from")) ?: continue
        val endMs = subtitleSecondsToMs(item.optDouble("to")) ?: startMs
        cues += BiliSubtitleCue(
            startTimeMs = startMs,
            endTimeMs = endMs.coerceAtLeast(startMs),
            content = content
        )
    }
    return cues.sortedBy { it.startTimeMs }
}

private fun subtitleSecondsToMs(seconds: Double): Long? {
    if (!seconds.isFinite() || seconds < 0.0) return null
    return (seconds * SECONDS_TO_MS).roundToLong()
}

/**
 * 把字幕条目映射为歌词行，过滤空行与重复行，并保证每行有可显示的时间宽度。
 */
internal fun toBiliSubtitleLyricEntries(cues: List<BiliSubtitleCue>): List<LyricEntry> {
    if (cues.isEmpty()) return emptyList()
    val entries = ArrayList<LyricEntry>(cues.size)
    var previousStartMs: Long? = null
    var previousText: String? = null
    for (cue in cues.sortedBy { it.startTimeMs }) {
        val text = cue.content.trim()
        if (text.isEmpty()) continue
        if (cue.startTimeMs == previousStartMs && text == previousText) continue
        entries += LyricEntry(
            text = text,
            startTimeMs = cue.startTimeMs,
            endTimeMs = cue.endTimeMs.coerceAtLeast(cue.startTimeMs + 1L)
        )
        previousStartMs = cue.startTimeMs
        previousText = text
    }
    return entries
}
