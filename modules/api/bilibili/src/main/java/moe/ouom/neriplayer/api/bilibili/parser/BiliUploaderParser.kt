package moe.ouom.neriplayer.api.bilibili.parser

import moe.ouom.neriplayer.api.bilibili.model.collection.CollectionArchiveItem
import moe.ouom.neriplayer.api.bilibili.model.collection.SeriesArchivePage
import moe.ouom.neriplayer.api.bilibili.model.uploader.UploaderContent
import moe.ouom.neriplayer.api.bilibili.model.uploader.UploaderContentKind
import moe.ouom.neriplayer.api.bilibili.model.uploader.UploaderContentPage
import moe.ouom.neriplayer.api.bilibili.model.uploader.UploaderProfile
import moe.ouom.neriplayer.api.bilibili.model.uploader.UploaderVideo
import moe.ouom.neriplayer.api.bilibili.model.uploader.UploaderVideoPage
import org.json.JSONArray
import org.json.JSONObject

internal fun parseBiliUploaderProfile(
    data: JSONObject,
    fallbackMid: Long
): UploaderProfile {
    return UploaderProfile(
        mid = data.optLong("mid", fallbackMid).takeIf { it > 0L } ?: fallbackMid,
        name = data.optString("name"),
        faceUrl = normalizeBiliImageUrl(data.optString("face")),
        sign = data.optString("sign"),
        topPhotoUrl = normalizeBiliImageUrl(data.optString("top_photo"))
    )
}

internal fun parseBiliUploaderVideoPage(
    data: JSONObject,
    requestedPage: Int,
    requestedPageSize: Int,
    fallbackMid: Long
): UploaderVideoPage {
    val list = data.optJSONObject("list") ?: JSONObject()
    val videos = list.optJSONArray("vlist") ?: JSONArray()
    val items = ArrayList<UploaderVideo>(videos.length())
    for (index in 0 until videos.length()) {
        val video = videos.optJSONObject(index) ?: continue
        val aid = video.optLong("aid")
        val bvid = video.optString("bvid")
        val title = stripBiliHtml(video.optString("title"))
        if (aid <= 0L || bvid.isBlank() || title.isBlank()) continue
        items += UploaderVideo(
            aid = aid,
            bvid = bvid,
            title = title,
            coverUrl = normalizeBiliImageUrl(video.optString("pic")),
            durationSec = parseBiliSpaceDurationSeconds(video.optString("length")),
            uploaderMid = video.optLong("mid", fallbackMid).takeIf { it > 0L } ?: fallbackMid,
            uploaderName = video.optString("author"),
            play = video.optLongIfPresent("play"),
            pubdate = video.optLongIfPresent("created"),
        )
    }
    val page = data.optJSONObject("page") ?: JSONObject()
    val resolvedPage = page.optInt("pn", requestedPage).coerceAtLeast(1)
    val resolvedPageSize = page.optInt("ps", requestedPageSize).coerceAtLeast(1)
    val total = page.optInt("count", items.size).coerceAtLeast(items.size)
    return UploaderVideoPage(
        page = resolvedPage,
        pageSize = resolvedPageSize,
        total = total,
        items = items,
        hasMore = resolvedPage * resolvedPageSize < total
    )
}

internal fun parseBiliUploaderContentPage(
    data: JSONObject,
    requestedPage: Int,
    requestedPageSize: Int,
    fallbackMid: Long
): UploaderContentPage {
    val itemLists = data.optJSONObject("items_lists") ?: JSONObject()
    val page = itemLists.optJSONObject("page") ?: JSONObject()
    val resolvedPage = page.optInt("page_num", requestedPage).coerceAtLeast(1)
    val resolvedPageSize = page.optInt("page_size", requestedPageSize).coerceAtLeast(1)
    val collections = parseBiliUploaderContents(
        items = itemLists.optJSONArray("seasons_list"),
        kind = UploaderContentKind.COLLECTION,
        fallbackMid = fallbackMid
    )
    val series = parseBiliUploaderContents(
        items = itemLists.optJSONArray("series_list"),
        kind = UploaderContentKind.SERIES,
        fallbackMid = fallbackMid
    )
    val total = page.optInt("total", collections.size + series.size)
    return UploaderContentPage(
        page = resolvedPage,
        pageSize = resolvedPageSize,
        collections = collections,
        series = series,
        hasMore = total > resolvedPage * resolvedPageSize
    )
}

internal fun parseBiliSeriesArchivePage(
    data: JSONObject,
    requestedPage: Int,
    requestedPageSize: Int
): SeriesArchivePage {
    val archives = data.optJSONArray("archives") ?: JSONArray()
    val items = ArrayList<CollectionArchiveItem>(archives.length())
    for (index in 0 until archives.length()) {
        archives.optJSONObject(index)
            ?.let(::parseBiliSpaceArchiveItem)
            ?.let(items::add)
    }
    val page = data.optJSONObject("page") ?: JSONObject()
    val resolvedPage = page.optInt("num", requestedPage).coerceAtLeast(1)
    val resolvedPageSize = page.optInt("size", requestedPageSize).coerceAtLeast(1)
    val total = page.optInt("total", items.size).coerceAtLeast(items.size)
    return SeriesArchivePage(
        items = items,
        total = total,
        hasMore = resolvedPage * resolvedPageSize < total
    )
}

private fun parseBiliUploaderContents(
    items: JSONArray?,
    kind: UploaderContentKind,
    fallbackMid: Long
): List<UploaderContent> {
    if (items == null) return emptyList()
    val contents = ArrayList<UploaderContent>(items.length())
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val meta = item.optJSONObject("meta") ?: item
        val id = when (kind) {
            UploaderContentKind.COLLECTION -> meta.optLong("season_id")
            UploaderContentKind.SERIES -> meta.optLong("series_id")
        }
        val title = meta.optString("name").ifBlank { meta.optString("title") }
        if (id <= 0L || title.isBlank()) continue
        contents += UploaderContent(
            id = id,
            mid = meta.optLong("mid", fallbackMid).takeIf { it > 0L } ?: fallbackMid,
            kind = kind,
            title = title,
            coverUrl = normalizeBiliImageUrl(meta.optString("cover")),
            description = meta.optString("description"),
            total = meta.optInt("total")
        )
    }
    return contents.distinctBy { it.id }
}

private fun parseBiliSpaceArchiveItem(item: JSONObject): CollectionArchiveItem? {
    val aid = item.optLong("aid")
    val bvid = item.optString("bvid")
    val title = item.optString("title")
    if (aid <= 0L || bvid.isBlank() || title.isBlank()) return null
    return CollectionArchiveItem(
        aid = aid,
        bvid = bvid,
        title = title,
        coverUrl = normalizeBiliImageUrl(item.optString("pic")),
        durationSec = item.optInt("duration"),
        pubdate = item.optLongIfPresent("pubdate"),
        play = item.optJSONObject("stat")?.optLongIfPresent("view")
    )
}

private fun normalizeBiliImageUrl(url: String?): String {
    val value = url?.trim().orEmpty()
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> value.replaceFirst("http://", "https://")
        else -> value
    }
}

private fun stripBiliHtml(value: String): String = value.replace(Regex("<.*?>"), "")

private fun parseBiliSpaceDurationSeconds(value: String): Int {
    if (value.isBlank()) return 0
    var duration = 0
    for (part in value.split(':')) {
        val seconds = part.toIntOrNull() ?: return 0
        duration = duration * 60 + seconds
    }
    return duration
}

private fun JSONObject.optLongIfPresent(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = opt(name)
    return (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull()
}
