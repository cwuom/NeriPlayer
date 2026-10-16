package moe.ouom.neriplayer.ui.screen.playlist.curation

import moe.ouom.neriplayer.data.local.media.curation.EmbeddedCoverSizePreset
import moe.ouom.neriplayer.data.local.media.curation.LocalEmbeddedAssetState
import moe.ouom.neriplayer.data.local.media.curation.PreparedEmbeddedCover
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricTimelineFit

enum class LocalCurationLyricMode {
    /** 不处理歌词 */
    SKIP,

    /** 只处理文件内还没有歌词的歌曲；已有侧载或应用内歌词时直接嵌入，否则在线匹配 */
    FILL_MISSING,

    /** 无论文件内是否已有歌词，都重新在线匹配，用于纠正错误歌词 */
    REMATCH_ALL,

    /** 不联网，把应用当前显示的歌词重新写入文件，用于编辑器修正后的纠错 */
    REEMBED_CURRENT
}

enum class LocalCurationCoverMode {
    SKIP,
    FILL_MISSING,
    REPLACE_ALL
}

data class LocalCurationOptions(
    val lyricMode: LocalCurationLyricMode = LocalCurationLyricMode.FILL_MISSING,
    val coverMode: LocalCurationCoverMode = LocalCurationCoverMode.FILL_MISSING,
    val coverSize: EmbeddedCoverSizePreset = EmbeddedCoverSizePreset.STANDARD,
    val useFolderCover: Boolean = true,
    val useOnlineCover: Boolean = true,
    /** 同时分析的歌曲数；每首歌内部的各歌词来源本身也是并发请求 */
    val searchParallelism: Int = LOCAL_CURATION_DEFAULT_PARALLELISM,
    /** 同时写入的歌曲数；同一文件由本地媒体层按目标排队，正在播放的歌曲始终最后单独写 */
    val writeParallelism: Int = LOCAL_CURATION_DEFAULT_PARALLELISM
) {
    val hasWork: Boolean
        get() = lyricMode != LocalCurationLyricMode.SKIP || coverMode != LocalCurationCoverMode.SKIP

    val needsNetwork: Boolean
        get() = lyricMode == LocalCurationLyricMode.FILL_MISSING ||
            lyricMode == LocalCurationLyricMode.REMATCH_ALL ||
            (coverMode != LocalCurationCoverMode.SKIP && useOnlineCover)
}

enum class LocalCurationLyricOrigin {
    /** 应用内已有的歌词（侧载 `.lrc` 或之前匹配并保存的歌词） */
    EXISTING,
    ONLINE
}

data class LocalCurationLyricProposal(
    val lyrics: String,
    val translatedLyrics: String?,
    val romanizedLyrics: String?,
    val origin: LocalCurationLyricOrigin,
    val source: EditableLyricMatchSource?,
    val matchedTitle: String,
    val matchedArtist: String,
    val confidence: EditableLyricMatchConfidence,
    val timelineFit: EditableLyricTimelineFit,
    val hasWordTiming: Boolean,
    /** 同一候选可信的在线封面；换选歌词时封面跟着换，词图始终来自同一首 */
    val coverUrl: String? = null,
    /** 在线候选在 [source] 平台上的曲目 ID */
    val candidateId: String? = null
)

enum class LocalCurationCoverOrigin {
    /** 同目录 cover/folder/front 图片或 Covers 侧载 */
    FOLDER,
    ONLINE
}

data class LocalCurationCoverProposal(
    val prepared: PreparedEmbeddedCover,
    val origin: LocalCurationCoverOrigin,
    val confidence: EditableLyricMatchConfidence
)

enum class LocalCurationItemPhase {
    QUEUED,
    ANALYZING,

    /** 至少有一项待写入的提议 */
    READY,

    /** 文件已经包含所需词图 */
    COMPLETE,

    /** 需要补全但没有找到可信来源 */
    NO_MATCH,

    /** 文件无法读取标签或容器不支持嵌入 */
    UNSUPPORTED,
    APPLYING,
    APPLIED,
    FAILED
}

enum class LocalCurationFailure {
    LYRICS_WRITE,
    COVER_PREPARE,
    COVER_WRITE,

    /** 歌词与封面合并为一次文件写入时失败 */
    FILE_WRITE
}

data class LocalCurationItem(
    val key: String,
    val song: SongItem,
    val phase: LocalCurationItemPhase = LocalCurationItemPhase.QUEUED,
    val existing: LocalEmbeddedAssetState? = null,
    val lyric: LocalCurationLyricProposal? = null,
    /** 同一首歌的其他可信候选，核对时可以一键替换当前歌词 */
    val lyricAlternatives: List<LocalCurationLyricProposal> = emptyList(),
    val cover: LocalCurationCoverProposal? = null,
    val applyLyric: Boolean = false,
    val applyCover: Boolean = false,
    val failure: LocalCurationFailure? = null
) {
    val hasSelection: Boolean
        get() = (applyLyric && lyric != null) || (applyCover && cover != null)
}

enum class LocalCurationStage {
    ANALYZING,
    REVIEW,
    APPLYING,
    FINISHED
}

data class LocalCurationSession(
    val id: Long,
    val options: LocalCurationOptions,
    val stage: LocalCurationStage,
    val items: List<LocalCurationItem>,
    val cancelled: Boolean = false
) {
    val total: Int get() = items.size

    // 大曲库每次刷新都会重组多处计数，每个快照创建时一次遍历算好，避免每帧反复扫描整个列表
    private val phaseCounts: IntArray = IntArray(LocalCurationItemPhase.entries.size).also { counts ->
        items.forEach { counts[it.phase.ordinal]++ }
    }

    val selectedCount: Int = items.count { it.hasSelection }

    val analyzedCount: Int
        get() = total - count(LocalCurationItemPhase.QUEUED) - count(LocalCurationItemPhase.ANALYZING)

    val appliedCount: Int
        get() = count(LocalCurationItemPhase.APPLIED)

    val failedCount: Int
        get() = count(LocalCurationItemPhase.FAILED)

    val applyingDoneCount: Int
        get() = appliedCount + failedCount

    val applyTotal: Int = items.count {
        it.hasSelection || it.phase == LocalCurationItemPhase.APPLIED ||
            it.phase == LocalCurationItemPhase.FAILED || it.phase == LocalCurationItemPhase.APPLYING
    }

    fun count(phase: LocalCurationItemPhase): Int = phaseCounts[phase.ordinal]
}
