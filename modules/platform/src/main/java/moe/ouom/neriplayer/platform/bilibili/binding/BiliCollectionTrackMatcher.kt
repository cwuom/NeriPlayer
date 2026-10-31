package moe.ouom.neriplayer.platform.bilibili.binding

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBindingTrack
import moe.ouom.neriplayer.data.model.bilibili.binding.compactBiliBindingText
import moe.ouom.neriplayer.data.model.bilibili.binding.normalizeBiliBindingText
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import kotlin.math.absoluteValue

/** 绑定解析出的 B 站视频标识，BV 号优先 */
data class BiliCollectionVideoId(
    val bvid: String = "",
    val avid: Long = 0L
) {
    fun isBlank(): Boolean = bvid.isBlank() && avid <= 0L
}

private val bvidRegex = Regex("BV[0-9A-Za-z]{10}")
private val avidRegex = Regex("(?i)(?:^|[^0-9])av(\\d{1,19})")
private val biliPartIndexPrefixRegex = Regex("^\\s*\\d+\\s*[.、)]?\\s*")
private val biliShareUrlRegex =
    Regex(
        "(?i)https?://(?:b23\\.tv|bili2233\\.cn|www\\.bilibili\\.com)/" +
            "[^\\s\\u3000-\\u303f\\uff00-\\uffef\\u4e00-\\u9fff]+"
    )

/** 从纯文本、链接或分享文案中提取视频标识（不支持短链） */
fun parseBiliCollectionVideoId(input: String): BiliCollectionVideoId? {
    val text = input.trim()
    if (text.isEmpty()) return null
    bvidRegex.find(text)?.let { return BiliCollectionVideoId(bvid = it.value) }
    avidRegex.find(text)?.let { match ->
        val avid = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return@let
        if (avid > 0L) return BiliCollectionVideoId(avid = avid)
    }
    return null
}

/** 从分享文案里抠出可访问的链接；手机端分享一般是「【标题】 https://b23.tv/xxxx」 */
fun extractBiliShareUrl(input: String): String? =
    biliShareUrlRegex.find(input.trim())?.value?.trimEnd('，', ',', '。', ')', '）')

/**
 * 解析用户输入：先试 BV / av 号，失败了再把短链还原成完整地址重试。
 *
 * 手机端分享链接是 b23.tv 短链，里面没有 BV 号，必须先跟随一次跳转才能拿到。
 */
suspend fun resolveBiliCollectionVideoId(
    input: String,
    resolveShareLink: suspend (String) -> String?
): BiliCollectionVideoId? {
    parseBiliCollectionVideoId(input)?.let { return it }
    val shareUrl = extractBiliShareUrl(input) ?: return null
    val resolved = runCatching { resolveShareLink(shareUrl) }.getOrNull() ?: return null
    return parseBiliCollectionVideoId(resolved)
}

data class BiliCollectionTrackMatch(
    val songId: Long,
    val page: VideoPage,
    val score: Int
)

/** 名字相似度达到这个分数就认为配上了；时长只影响同分时的排序 */
private const val COLLECTION_MATCH_MIN_SCORE = 55

/**
 * 把曲目与分 P 做贪心配对：先按分数从高到低占用，避免一首歌抢走更贴合的分 P。
 * 返回的列表顺序与 [songs] 一致，名字对不上的曲目不会出现。
 */
fun matchSongsToPages(
    songs: List<SongItem>,
    pages: List<VideoPage>,
    minScore: Int = COLLECTION_MATCH_MIN_SCORE
): List<BiliCollectionTrackMatch> {
    val usablePages = pages.filter { it.cid > 0L }
    if (songs.isEmpty() || usablePages.isEmpty()) return emptyList()

    val pairs = ArrayList<TrackCandidate>(songs.size * usablePages.size)
    for (song in songs) {
        for (page in usablePages) {
            val score = scoreTrackPage(song, page)
            if (score >= minScore) pairs += TrackCandidate(song, page, score)
        }
    }

    val assignedSongs = HashSet<Long>(songs.size)
    val assignedPages = HashSet<Long>(usablePages.size)
    val matched = LinkedHashMap<Long, BiliCollectionTrackMatch>()

    pairs.sortedWith(
        compareByDescending<TrackCandidate> { it.score }
            .thenBy { it.page.page }
    ).forEach { candidate ->
        val song = candidate.song
        val page = candidate.page
        if (song.id in assignedSongs || page.cid in assignedPages) return@forEach
        assignedSongs += song.id
        assignedPages += page.cid
        matched[song.id] = BiliCollectionTrackMatch(
            songId = song.id,
            page = page,
            score = candidate.score
        )
    }

    return songs.mapNotNull { matched[it.id] }
}

private data class TrackCandidate(
    val song: SongItem,
    val page: VideoPage,
    val score: Int
)

fun scoreTrackPage(song: SongItem, page: VideoPage): Int {
    val songName = song.originalName ?: song.name
    val normalizedSong = normalizeBiliBindingText(songName)
    val normalizedPart = normalizeBiliBindingText(page.part.replace(biliPartIndexPrefixRegex, ""))
    val compactSong = compactBiliBindingText(normalizedSong)
    val compactPart = compactBiliBindingText(normalizedPart)

    val nameScore = when {
        compactSong.isEmpty() || compactPart.isEmpty() -> 0
        compactSong == compactPart -> 100
        compactPart.contains(compactSong) || compactSong.contains(compactPart) -> 92
        else -> maxOf(
            tokenOverlapScore(normalizedSong, normalizedPart),
            bigramSimilarityScore(compactSong, compactPart)
        )
    }
    return nameScore + durationTieBreak(song.durationMs, page.durationSec * 1000L)
}

/** 歌名里能对上分 P 的词越多越像；只对上一部分仍有机会过线 */
private fun tokenOverlapScore(normalizedSong: String, normalizedPart: String): Int {
    val tokens = normalizedSong.split(' ').filter { it.length >= 2 }
    if (tokens.isEmpty()) return 0
    val hits = tokens.count { normalizedPart.contains(it) }
    if (hits == 0) return 0
    return (40 + 35 * (hits.toDouble() / tokens.size)).toInt()
}

/**
 * 字符二元组 Dice 相似度（0..100）：容忍繁简、缺字、多字这类"名字差不多"，
 * 但不至于把完全不同的歌名凑到一起。
 */
private fun bigramSimilarityScore(a: String, b: String): Int {
    if (a.length < 2 || b.length < 2) return 0
    val remaining = b.windowed(2).toMutableList()
    var shared = 0
    for (pair in a.windowed(2)) {
        val index = remaining.indexOf(pair)
        if (index >= 0) {
            remaining.removeAt(index)
            shared++
        }
    }
    val total = (a.length - 1) + (b.length - 1)
    return if (total <= 0) 0 else (200 * shared) / total
}

/**
 * 时长只用来在同名分 P 之间排序：长度对不上（现场版、剪辑片段）不该让这首歌配不上。
 */
private fun durationTieBreak(songDurationMs: Long, pageDurationMs: Long): Int {
    if (songDurationMs <= 0L || pageDurationMs <= 0L) return 0
    val diffMs = (pageDurationMs - songDurationMs).absoluteValue
    return when {
        diffMs <= 5_000L -> 8
        diffMs <= 15_000L -> 5
        diffMs <= 40_000L -> 2
        else -> 0
    }
}

fun VideoPage.toBindingTrack(song: SongItem): BiliCollectionBindingTrack =
    BiliCollectionBindingTrack(
        songId = song.id,
        songName = song.originalName ?: song.name,
        cid = cid,
        page = page,
        part = part,
        durationSec = durationSec
    )

/** 把匹配结果转成可持久化的绑定条目 */
fun List<BiliCollectionTrackMatch>.toBindingTracks(
    songs: List<SongItem>
): List<BiliCollectionBindingTrack> {
    val songsById = songs.associateBy { it.id }
    return mapNotNull { match ->
        val song = songsById[match.songId] ?: return@mapNotNull null
        match.page.toBindingTrack(song)
    }
}
