package moe.ouom.neriplayer.ui.screen.playlist.curation

import moe.ouom.neriplayer.data.local.media.curation.LocalEmbeddedAssetState
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricTimelineFit
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import moe.ouom.neriplayer.lyrics.embedded.standardLyricsMetadataKeys
import moe.ouom.neriplayer.lyrics.embedded.translatedLyricsMetadataKeys

/** 网易云与 QQ 同时提供封面，酷狗与 LRCLIB 补充歌词覆盖面；AMLL 体积大且慢，留给单曲编辑器 */
internal val LOCAL_CURATION_MATCH_SOURCES: Set<EditableLyricMatchSource> = setOf(
    EditableLyricMatchSource.CLOUD_MUSIC,
    EditableLyricMatchSource.QQ_MUSIC,
    EditableLyricMatchSource.KUGOU,
    EditableLyricMatchSource.LRCLIB
)

internal const val LOCAL_CURATION_DEFAULT_PARALLELISM = 10
internal val LOCAL_CURATION_PARALLELISM_RANGE = 1..16

internal fun clampLocalCurationParallelism(value: Int): Int =
    value.coerceIn(LOCAL_CURATION_PARALLELISM_RANGE.first, LOCAL_CURATION_PARALLELISM_RANGE.last)

internal enum class LocalLyricWork {
    NONE,
    EMBED_EXISTING,
    MATCH_ONLINE
}

internal fun resolveLocalLyricWork(
    mode: LocalCurationLyricMode,
    existing: LocalEmbeddedAssetState,
    hasCurrentLyrics: Boolean
): LocalLyricWork = when (mode) {
    LocalCurationLyricMode.SKIP -> LocalLyricWork.NONE
    LocalCurationLyricMode.FILL_MISSING -> when {
        existing.hasEmbeddedLyrics -> LocalLyricWork.NONE
        // 用户已经有侧载或保存过的歌词时优先信任它，避免在线匹配把正确歌词换掉
        hasCurrentLyrics -> LocalLyricWork.EMBED_EXISTING
        else -> LocalLyricWork.MATCH_ONLINE
    }
    LocalCurationLyricMode.REMATCH_ALL -> LocalLyricWork.MATCH_ONLINE
    LocalCurationLyricMode.REEMBED_CURRENT ->
        if (hasCurrentLyrics) LocalLyricWork.EMBED_EXISTING else LocalLyricWork.NONE
}

internal fun needsLocalCover(mode: LocalCurationCoverMode, existing: LocalEmbeddedAssetState): Boolean =
    when (mode) {
        LocalCurationCoverMode.SKIP -> false
        LocalCurationCoverMode.FILL_MISSING -> !existing.hasEmbeddedCover
        LocalCurationCoverMode.REPLACE_ALL -> true
    }

/** 换选歌词后封面是否跟随新候选：文件夹封面与匹配结果无关，保持不变；在线封面必须与歌词同源 */
internal fun coverFollowsLyricChoice(item: LocalCurationItem, options: LocalCurationOptions): Boolean {
    val cover = item.cover
    if (cover != null) return cover.origin == LocalCurationCoverOrigin.ONLINE
    val existing = item.existing ?: return false
    return options.useOnlineCover && needsLocalCover(options.coverMode, existing)
}

/** 时间轴超出歌曲时长的候选直接淘汰，其余按匹配器排序取第一名 */
internal fun pickLocalLyricMatch(ranked: List<RankedEditableLyricMatch>): RankedEditableLyricMatch? =
    ranked.firstOrNull { match ->
        match.timelineFit != EditableLyricTimelineFit.OVERFLOWS && match.candidate.lyrics.isNotBlank()
    }

/** 首选之外的候选，按匹配器排序保留前几名；同一份歌词在多个来源重复出现时只留一份 */
internal fun pickLocalLyricAlternatives(
    ranked: List<RankedEditableLyricMatch>,
    chosen: RankedEditableLyricMatch?,
    limit: Int = LOCAL_CURATION_ALTERNATIVE_LIMIT
): List<RankedEditableLyricMatch> {
    val chosenLyrics = chosen?.candidate?.lyrics?.trim()
    return ranked.asSequence()
        .filter { it !== chosen }
        .filter { it.timelineFit != EditableLyricTimelineFit.OVERFLOWS && it.candidate.lyrics.isNotBlank() }
        .filter { it.candidate.lyrics.trim() != chosenLyrics }
        .distinctBy { it.candidate.lyrics.trim() }
        .take(limit)
        .toList()
}

internal const val LOCAL_CURATION_ALTERNATIVE_LIMIT = 3

/** 封面优先与选中歌词同源，保证词图来自同一首；低置信度候选不提供封面 */
internal fun pickLocalCoverMatch(
    ranked: List<RankedEditableLyricMatch>,
    preferred: RankedEditableLyricMatch?
): RankedEditableLyricMatch? {
    preferred?.takeIf(::isUsableCoverMatch)?.let { return it }
    return ranked.firstOrNull(::isUsableCoverMatch)
}

internal fun isUsableCoverMatch(match: RankedEditableLyricMatch): Boolean {
    return !match.candidate.coverUrl.isNullOrBlank() &&
        match.confidence != EditableLyricMatchConfidence.LOW &&
        match.timelineFit != EditableLyricTimelineFit.OVERFLOWS
}

internal fun shouldAutoApplyLyric(proposal: LocalCurationLyricProposal): Boolean = when {
    proposal.origin == LocalCurationLyricOrigin.EXISTING -> true
    proposal.timelineFit == EditableLyricTimelineFit.OVERFLOWS -> false
    proposal.confidence == EditableLyricMatchConfidence.HIGH -> true
    proposal.confidence == EditableLyricMatchConfidence.MEDIUM ->
        proposal.timelineFit == EditableLyricTimelineFit.FITS
    else -> false
}

internal fun shouldAutoApplyCover(proposal: LocalCurationCoverProposal): Boolean =
    proposal.origin == LocalCurationCoverOrigin.FOLDER ||
        proposal.confidence == EditableLyricMatchConfidence.HIGH

internal fun buildLocalCurationMatchRequest(
    song: SongItem,
    preferWordTimed: Boolean
): EditableLyricMatchRequest {
    val title = song.displayName().trim()
    val artist = song.displayArtist().trim()
    return EditableLyricMatchRequest(
        keyword = listOf(title, artist).filter(String::isNotBlank).joinToString(" "),
        trackName = title,
        artistName = artist,
        albumName = song.album.trim().takeIf(String::isNotBlank),
        durationMs = song.durationMs.coerceAtLeast(0L),
        preferWordTimed = preferWordTimed,
        sources = LOCAL_CURATION_MATCH_SOURCES
    )
}

/**
 * 与写入器保持一致：原文与翻译合并后写入容器的每个标准歌词字段，原文另存一份私有字段，
 * 翻译写入全部翻译字段，音译一份。旧值按一份扣除，结果偏保守，宁可多报也不让用户在空间不足时意外失败
 */
internal fun estimatedLyricTagBytes(proposal: LocalCurationLyricProposal, audioExtension: String?): Long {
    val lyric = proposal.lyrics.utf8Size()
    val translated = proposal.translatedLyrics.orEmpty().utf8Size()
    val romanized = proposal.romanizedLyrics.orEmpty().utf8Size()
    val standardKeys = standardLyricsMetadataKeys(audioExtension).size
    return standardKeys * (lyric + translated) + lyric + translatedLyricsMetadataKeys.size * translated + romanized
}

internal fun estimateLocalCurationBytesDelta(items: List<LocalCurationItem>): Long =
    items.sumOf { item ->
        var delta = 0L
        val lyric = item.lyric
        if (item.applyLyric && lyric != null) {
            delta += estimatedLyricTagBytes(lyric, item.existing?.audioExtension) -
                (item.existing?.embeddedLyricBytes ?: 0)
        }
        val cover = item.cover
        if (item.applyCover && cover != null) {
            delta += cover.prepared.byteSize - (item.existing?.embeddedCoverBytes ?: 0)
        }
        delta
    }

/** 正在播放的歌曲放到最后写，写入期间需要短暂释放播放器，越晚越不打扰收听 */
internal fun orderLocalCurationApply(
    items: List<LocalCurationItem>,
    currentSongKey: String?
): List<LocalCurationItem> {
    val selected = items.filter(LocalCurationItem::hasSelection)
    if (currentSongKey == null) return selected
    val (current, others) = selected.partition { it.key == currentSongKey }
    return others + current
}

internal fun resolveAnalyzedPhase(
    existing: LocalEmbeddedAssetState?,
    lyricWork: LocalLyricWork,
    coverNeeded: Boolean,
    lyric: LocalCurationLyricProposal?,
    cover: LocalCurationCoverProposal?
): LocalCurationItemPhase = when {
    existing == null -> LocalCurationItemPhase.UNSUPPORTED
    lyric != null || cover != null -> LocalCurationItemPhase.READY
    lyricWork == LocalLyricWork.NONE && !coverNeeded -> LocalCurationItemPhase.COMPLETE
    else -> LocalCurationItemPhase.NO_MATCH
}

internal enum class LocalCurationFilter {
    ALL,
    READY,
    ATTENTION,
    SKIPPED
}

/** 在线结果未达到高置信度，或时间轴不吻合时，需要用户逐首确认 */
internal fun needsLocalCurationReview(item: LocalCurationItem): Boolean {
    if (item.phase != LocalCurationItemPhase.READY) return false
    val lyricUncertain = item.lyric?.let { !shouldAutoApplyLyric(it) } == true
    val coverUncertain = item.cover?.let { !shouldAutoApplyCover(it) } == true
    return lyricUncertain || coverUncertain
}

internal fun LocalCurationItem.matches(filter: LocalCurationFilter): Boolean = when (filter) {
    LocalCurationFilter.ALL -> true
    LocalCurationFilter.READY -> phase == LocalCurationItemPhase.READY ||
        phase == LocalCurationItemPhase.FAILED
    LocalCurationFilter.ATTENTION -> needsLocalCurationReview(this)
    LocalCurationFilter.SKIPPED -> phase == LocalCurationItemPhase.COMPLETE ||
        phase == LocalCurationItemPhase.NO_MATCH ||
        phase == LocalCurationItemPhase.UNSUPPORTED ||
        phase == LocalCurationItemPhase.QUEUED
}

/** 去掉时间戳与逐字标记，供核对时快速预览歌词前几行 */
internal fun localCurationLyricPreview(lyrics: String, maxLines: Int): List<String> {
    return lyrics.lineSequence()
        .map { line ->
            line.replace(lyricPreviewTimingRegex, "").trim()
        }
        .filter { it.isNotEmpty() && !lyricPreviewTagRegex.matches(it) }
        .take(maxLines)
        .toList()
}

// LRC/YRC 时间戳、YRC 逐字括号，以及增强 LRC 与 TTML 的尖括号标签
private val lyricPreviewTimingRegex = Regex(
    """\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]|\[\d+,\s*\d+]|\(\d+,\d+(?:,\d+)?\)|<[^>]*>"""
)
private val lyricPreviewTagRegex = Regex("""^\[[a-zA-Z]+\s*:.*]$""")

private fun String.utf8Size(): Long = toByteArray(Charsets.UTF_8).size.toLong()
