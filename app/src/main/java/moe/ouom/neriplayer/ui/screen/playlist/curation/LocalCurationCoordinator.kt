package moe.ouom.neriplayer.ui.screen.playlist.curation

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation
import moe.ouom.neriplayer.data.local.media.curation.EmbeddedCoverSizePreset
import moe.ouom.neriplayer.data.local.media.curation.LocalEmbeddedAssetState
import moe.ouom.neriplayer.data.local.media.curation.PreparedEmbeddedCover
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricTimelineFit
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch

data class LocalCurrentLyrics(
    val lyrics: String,
    val translatedLyrics: String?,
    val romanizedLyrics: String?
)

/** 批量词图管理需要的全部副作用，测试用假实现替换，生产实现见 [AppLocalCurationPorts] */
interface LocalCurationPorts {
    fun songKey(song: SongItem): String
    fun currentSongKey(): String?
    suspend fun preferWordTimedLyrics(): Boolean
    suspend fun inspect(song: SongItem): LocalEmbeddedAssetState?
    suspend fun currentLyrics(song: SongItem): LocalCurrentLyrics?
    suspend fun folderCover(song: SongItem): String?
    suspend fun matchLyrics(request: EditableLyricMatchRequest): List<RankedEditableLyricMatch>
    suspend fun prepareCover(reference: String, preset: EmbeddedCoverSizePreset): PreparedEmbeddedCover?
    suspend fun commitCover(prepared: PreparedEmbeddedCover): String?

    /** 一次事务写入歌词与封面；返回失败类型，成功为 null */
    suspend fun apply(
        song: SongItem,
        lyric: LocalCurationLyricProposal?,
        coverReference: String?
    ): LocalCurationFailure?

    suspend fun resetSessionCaches()
    fun notifyFinished(applied: Int, failed: Int)
    fun loadOptions(): LocalCurationOptions?
    fun saveOptions(options: LocalCurationOptions)
}

/**
 * 进程级的批量词图会话：分析与写入都在 [scope] 中运行，关闭面板或离开页面不会中断，
 * 再次打开时直接恢复到当前进度。同一时间只允许一个会话，避免两个批次争抢同一文件。
 */
class LocalCurationCoordinator(
    private val ports: LocalCurationPorts,
    private val scope: CoroutineScope
) {
    private val sessionIds = AtomicLong()
    private val mutableSession = MutableStateFlow<LocalCurationSession?>(null)
    val session: StateFlow<LocalCurationSession?> = mutableSession.asStateFlow()

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var cleanupJob: Job? = null
    private val coverCache = HashMap<String, CompletableDeferred<PreparedEmbeddedCover?>>()

    val isBusy: Boolean
        get() = mutableSession.value?.stage.let {
            it == LocalCurationStage.ANALYZING || it == LocalCurationStage.APPLYING
        }

    fun start(songs: List<SongItem>, options: LocalCurationOptions): Boolean {
        if (isBusy || !options.hasWork) return false
        val items = songs.distinctBy(ports::songKey).map { song ->
            LocalCurationItem(key = ports.songKey(song), song = song)
        }
        if (items.isEmpty()) return false
        val normalized = options.copy(
            searchParallelism = clampLocalCurationParallelism(options.searchParallelism),
            writeParallelism = clampLocalCurationParallelism(options.writeParallelism)
        )
        ports.saveOptions(normalized)
        activeJob?.cancel()
        synchronized(coverCache) { coverCache.clear() }
        val session = LocalCurationSession(
            id = sessionIds.incrementAndGet(),
            options = normalized,
            stage = LocalCurationStage.ANALYZING,
            items = items
        )
        mutableSession.value = session
        val previousCleanup = cleanupJob
        activeJob = scope.launch { analyze(session, previousCleanup) }
        return true
    }

    fun setLyricSelected(key: String, selected: Boolean) = updateReviewItem(key) { item ->
        item.copy(applyLyric = selected && item.lyric != null)
    }

    fun setCoverSelected(key: String, selected: Boolean) = updateReviewItem(key) { item ->
        item.copy(applyCover = selected && item.cover != null)
    }

    /**
     * 用户主动换成其他候选即表示认可，直接勾选写入；被换下的在线结果留在候选里可以换回。
     * 在线封面随歌词一起换成新候选的，新候选没有可信封面时清除，避免词图来自不同的歌
     */
    fun chooseLyricAlternative(key: String, index: Int) {
        val session = mutableSession.value ?: return
        var followingCover: Pair<LocalCurationLyricProposal, String>? = null
        updateReviewItem(key) { item ->
            val chosen = item.lyricAlternatives.getOrNull(index) ?: return@updateReviewItem item
            val previous = item.lyric?.takeIf { it.origin == LocalCurationLyricOrigin.ONLINE }
            val coverFollows = coverFollowsLyricChoice(item, session.options)
            followingCover = chosen.coverUrl?.takeIf { coverFollows }?.let { chosen to it }
            item.copy(
                lyric = chosen,
                lyricAlternatives = listOfNotNull(previous) + item.lyricAlternatives.filterIndexed { i, _ -> i != index },
                applyLyric = true,
                cover = item.cover.takeUnless { coverFollows },
                applyCover = item.applyCover && !coverFollows
            )
        }
        val (chosen, coverUrl) = followingCover ?: return
        scope.launch {
            val prepared = prepareCoverOnce(coverUrl, session.options.coverSize) ?: return@launch
            updateReviewItem(key) { item ->
                // 准备期间用户可能又换了候选，只给仍然选中的那条配封面
                if (item.lyric != chosen) return@updateReviewItem item
                item.copy(
                    cover = LocalCurationCoverProposal(prepared, LocalCurationCoverOrigin.ONLINE, chosen.confidence),
                    applyCover = true
                )
            }
        }
    }

    /** 上次使用的选项，面板打开时作为默认值 */
    fun lastOptions(): LocalCurationOptions = ports.loadOptions() ?: LocalCurationOptions()

    fun setAllSelected(selected: Boolean) {
        mutableSession.update { current ->
            current?.takeIf { it.stage == LocalCurationStage.REVIEW }?.copy(
                items = current.items.map { item ->
                    if (item.phase != LocalCurationItemPhase.READY) return@map item
                    item.copy(
                        applyLyric = selected && item.lyric != null,
                        applyCover = selected && item.cover != null
                    )
                }
            ) ?: current
        }
    }

    fun apply(): Boolean {
        val current = mutableSession.value ?: return false
        if (current.stage != LocalCurationStage.REVIEW || current.selectedCount == 0) return false
        val currentSongKey = ports.currentSongKey()
        val ordered = orderLocalCurationApply(current.items, currentSongKey).map(LocalCurationItem::key)
        val (playing, others) = ordered.partition { it == currentSongKey }
        mutableSession.value = current.copy(stage = LocalCurationStage.APPLYING, cancelled = false)
        activeJob = scope.launch {
            applyItems(current.id, others, playing, current.options.writeParallelism)
        }
        return true
    }

    fun retryFailed(): Boolean {
        val current = mutableSession.value ?: return false
        if (current.stage != LocalCurationStage.FINISHED || current.failedCount == 0) return false
        mutableSession.value = current.copy(
            stage = LocalCurationStage.REVIEW,
            cancelled = false,
            items = current.items.map { item ->
                if (item.phase == LocalCurationItemPhase.FAILED) {
                    item.copy(phase = LocalCurationItemPhase.READY, failure = null)
                } else {
                    item.copy(applyLyric = false, applyCover = false)
                }
            }
        )
        return apply()
    }

    /** 分析中取消会保留已完成的结果进入核对；写入中取消会等正在写入的歌曲完成后停止 */
    fun cancel() {
        val current = mutableSession.value ?: return
        when (current.stage) {
            LocalCurationStage.ANALYZING, LocalCurationStage.APPLYING -> activeJob?.cancel()
            else -> Unit
        }
    }

    fun dismiss() {
        if (isBusy) return
        mutableSession.value = null
        synchronized(coverCache) { coverCache.clear() }
        cleanupJob = scope.launch { ports.resetSessionCaches() }
    }

    private suspend fun analyze(session: LocalCurationSession, previousCleanup: Job?) {
        val semaphore = Semaphore(session.options.searchParallelism)
        try {
            // 上个会话的暂存清理必须先完成，否则会删掉本次刚生成的封面
            previousCleanup?.join()
            ports.resetSessionCaches()
            coroutineScope {
                session.items.forEach { item ->
                    launch {
                        semaphore.withPermit {
                            updateItem(session.id, item.key) { it.copy(phase = LocalCurationItemPhase.ANALYZING) }
                            val analyzed = analyzeItem(item, session.options)
                            updateItem(session.id, item.key) { analyzed }
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { finishAnalysis(session.id, cancelled = true) }
            throw cancellation
        }
        finishAnalysis(session.id, cancelled = false)
    }

    private fun finishAnalysis(sessionId: Long, cancelled: Boolean) {
        mutableSession.update { current ->
            if (current?.id != sessionId) return@update current
            current.copy(
                stage = LocalCurationStage.REVIEW,
                cancelled = cancelled,
                items = current.items.map { item ->
                    if (item.phase == LocalCurationItemPhase.QUEUED ||
                        item.phase == LocalCurationItemPhase.ANALYZING
                    ) {
                        item.copy(phase = LocalCurationItemPhase.QUEUED)
                    } else {
                        item
                    }
                }
            )
        }
    }

    private suspend fun analyzeItem(item: LocalCurationItem, options: LocalCurationOptions): LocalCurationItem {
        val song = item.song
        val existing = runCatchingNonCancellation { ports.inspect(song) }.getOrNull()
            ?: return item.copy(phase = LocalCurationItemPhase.UNSUPPORTED)
        val currentLyrics = if (options.lyricMode == LocalCurationLyricMode.SKIP) {
            null
        } else {
            runCatchingNonCancellation { ports.currentLyrics(song) }.getOrNull()
        }
        val lyricWork = resolveLocalLyricWork(options.lyricMode, existing, currentLyrics != null)
        val coverNeeded = needsLocalCover(options.coverMode, existing)

        val folderCover = if (coverNeeded && options.useFolderCover) {
            runCatchingNonCancellation { ports.folderCover(song) }.getOrNull()
                ?.let { reference -> prepareCoverOnce(reference, options.coverSize) }
                ?.let { prepared ->
                    LocalCurationCoverProposal(
                        prepared = prepared,
                        origin = LocalCurationCoverOrigin.FOLDER,
                        confidence = EditableLyricMatchConfidence.HIGH
                    )
                }
        } else {
            null
        }
        val needsOnlineCover = coverNeeded && folderCover == null && options.useOnlineCover
        val ranked = if (lyricWork == LocalLyricWork.MATCH_ONLINE || needsOnlineCover) {
            runCatchingNonCancellation {
                ports.matchLyrics(buildLocalCurationMatchRequest(song, ports.preferWordTimedLyrics()))
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val lyricMatch = if (lyricWork == LocalLyricWork.MATCH_ONLINE) pickLocalLyricMatch(ranked) else null
        val alternatives = if (lyricMatch != null) {
            pickLocalLyricAlternatives(ranked, lyricMatch).map { it.toProposal() }
        } else {
            emptyList()
        }
        val lyric = when (lyricWork) {
            LocalLyricWork.NONE -> null
            LocalLyricWork.EMBED_EXISTING -> currentLyrics?.toProposal(song)
            LocalLyricWork.MATCH_ONLINE -> lyricMatch?.toProposal()
        }
        val cover = folderCover ?: if (needsOnlineCover) {
            onlineCoverProposal(ranked, lyricMatch, options.coverSize)
        } else {
            null
        }
        return item.copy(
            phase = resolveAnalyzedPhase(existing, lyricWork, coverNeeded, lyric, cover),
            existing = existing,
            lyric = lyric,
            lyricAlternatives = alternatives,
            cover = cover,
            applyLyric = lyric != null && shouldAutoApplyLyric(lyric),
            applyCover = cover != null && shouldAutoApplyCover(cover),
            failure = null
        )
    }

    private suspend fun onlineCoverProposal(
        ranked: List<RankedEditableLyricMatch>,
        lyricMatch: RankedEditableLyricMatch?,
        preset: EmbeddedCoverSizePreset
    ): LocalCurationCoverProposal? {
        val match = pickLocalCoverMatch(ranked, lyricMatch) ?: return null
        val coverUrl = match.candidate.coverUrl ?: return null
        val prepared = prepareCoverOnce(coverUrl, preset) ?: return null
        return LocalCurationCoverProposal(
            prepared = prepared,
            origin = LocalCurationCoverOrigin.ONLINE,
            confidence = match.confidence
        )
    }

    /** 同专辑的多首歌常指向同一张图片，按来源与尺寸去重，只下载和压缩一次 */
    private suspend fun prepareCoverOnce(
        reference: String,
        preset: EmbeddedCoverSizePreset
    ): PreparedEmbeddedCover? {
        val key = "${preset.name}|$reference"
        var owner = false
        val deferred = synchronized(coverCache) {
            coverCache.getOrPut(key) {
                owner = true
                CompletableDeferred()
            }
        }
        if (owner) {
            try {
                deferred.complete(
                    runCatchingNonCancellation { ports.prepareCover(reference, preset) }.getOrNull()
                )
            } finally {
                if (!deferred.isCompleted) {
                    synchronized(coverCache) { coverCache.remove(key) }
                    deferred.complete(null)
                }
            }
        }
        return deferred.await()
    }

    /**
     * 其余歌曲按 [parallelism] 并发写入；正在播放的歌曲写入时需要短暂释放播放器，
     * 放到最后单独执行，尽量少打断收听
     */
    private suspend fun applyItems(
        sessionId: Long,
        keys: List<String>,
        playingKeys: List<String>,
        parallelism: Int
    ) {
        try {
            val semaphore = Semaphore(parallelism)
            coroutineScope {
                keys.forEach { key ->
                    launch { semaphore.withPermit { applyKey(sessionId, key) } }
                }
            }
            playingKeys.forEach { key -> applyKey(sessionId, key) }
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { finishApply(sessionId, cancelled = true) }
            throw cancellation
        }
        finishApply(sessionId, cancelled = false)
    }

    private suspend fun applyKey(sessionId: Long, key: String) {
        // 单首写入不可取消，取消只阻止尚未开始的歌曲
        currentCoroutineContext().ensureActive()
        val item = mutableSession.value?.takeIf { it.id == sessionId }
            ?.items?.firstOrNull { it.key == key }
            ?: return
        if (!item.hasSelection) return
        updateItem(sessionId, key) { it.copy(phase = LocalCurationItemPhase.APPLYING) }
        val failure = withContext(NonCancellable) { applyItem(item) }
        updateItem(sessionId, key) {
            it.copy(
                phase = if (failure == null) LocalCurationItemPhase.APPLIED else LocalCurationItemPhase.FAILED,
                failure = failure
            )
        }
    }

    private suspend fun applyItem(item: LocalCurationItem): LocalCurationFailure? {
        val lyric = item.lyric?.takeIf { item.applyLyric }
        val coverReference = item.cover?.takeIf { item.applyCover }?.let { proposal ->
            runCatchingNonCancellation { ports.commitCover(proposal.prepared) }.getOrNull()
                ?: return LocalCurationFailure.COVER_PREPARE
        }
        return runCatchingNonCancellation { ports.apply(item.song, lyric, coverReference) }
            .getOrElse {
                if (lyric != null) LocalCurationFailure.LYRICS_WRITE else LocalCurationFailure.COVER_WRITE
            }
    }

    private fun finishApply(sessionId: Long, cancelled: Boolean) {
        var finished: LocalCurationSession? = null
        mutableSession.update { current ->
            if (current?.id != sessionId) return@update current
            current.copy(
                stage = LocalCurationStage.FINISHED,
                cancelled = cancelled,
                items = current.items.map { item ->
                    if (item.phase == LocalCurationItemPhase.APPLYING) {
                        item.copy(phase = LocalCurationItemPhase.READY)
                    } else {
                        item
                    }
                }
            ).also { finished = it }
        }
        finished?.let { ports.notifyFinished(it.appliedCount, it.failedCount) }
    }

    private fun updateReviewItem(key: String, transform: (LocalCurationItem) -> LocalCurationItem) {
        mutableSession.update { current ->
            if (current?.stage != LocalCurationStage.REVIEW) return@update current
            current.copy(items = current.items.map { if (it.key == key) transform(it) else it })
        }
    }

    private fun updateItem(sessionId: Long, key: String, transform: (LocalCurationItem) -> LocalCurationItem) {
        mutableSession.update { current ->
            if (current?.id != sessionId) return@update current
            current.copy(items = current.items.map { if (it.key == key) transform(it) else it })
        }
    }

    private fun LocalCurrentLyrics.toProposal(song: SongItem) = LocalCurationLyricProposal(
        lyrics = lyrics,
        translatedLyrics = translatedLyrics,
        romanizedLyrics = romanizedLyrics,
        origin = LocalCurationLyricOrigin.EXISTING,
        source = null,
        matchedTitle = song.customName ?: song.name,
        matchedArtist = song.customArtist ?: song.artist,
        confidence = EditableLyricMatchConfidence.HIGH,
        timelineFit = EditableLyricTimelineFit.UNKNOWN,
        hasWordTiming = false
    )

    private fun RankedEditableLyricMatch.toProposal() = LocalCurationLyricProposal(
        lyrics = candidate.lyrics,
        translatedLyrics = candidate.translatedLyrics?.takeIf(String::isNotBlank),
        romanizedLyrics = null,
        origin = LocalCurationLyricOrigin.ONLINE,
        source = candidate.source,
        matchedTitle = candidate.title,
        matchedArtist = candidate.artist,
        confidence = confidence,
        timelineFit = timelineFit,
        hasWordTiming = hasWordTiming,
        coverUrl = candidate.coverUrl.takeIf { isUsableCoverMatch(this) }
    )
}
