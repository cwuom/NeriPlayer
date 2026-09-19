package moe.ouom.neriplayer.core.player.download

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.manager.runtime.validateExistingDownloadedAudio
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.downloadedSongPlaybackReferenceCandidates
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import android.os.Looper
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.core.download.resolveDownloadedSongPlaybackReference
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_FILE_PREFIX
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_FILE_SUFFIX
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport

/**
 * 本地播放引用协调器
 *
 * 下载主类只负责把自身状态接入 hooks。这里集中处理目录迁移、完成桥接、
 * 受管引用完整性和短窗口重绑定，避免播放请求重复触发 SAF 扫描
 */
internal class AudioDownloadPlaybackCoordinator(
    private val completedAudioReferenceRegistry: AudioDownloadReferenceRegistry,
    private val isSongDownloadActive: (String) -> Boolean,
    private val directoryMutationWaitMs: Long,
    private val tag: String = "NERI-Downloader"
) {
    private val LEGACY_INDEX_REFRESH_COOLDOWN_MS = 5_000L
    private val MANAGED_PLAYBACK_REBIND_COOLDOWN_MS = 1_500L

    private val managedPlaybackRebindAtMsBySongKey =
        ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastLegacyIndexRefreshAtMs = 0L

    private data class CachedManagedAudio(
        val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        val audio: ManagedDownloadStorage.StoredEntry
    )

    private fun canBlockStorageLookup(): Boolean {
        return android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
    }

    private fun requestBackgroundDownloadIndexRefresh(context: Context) {
        val nowMs = System.currentTimeMillis()
        val shouldRequest = synchronized(this) {
            val previousAtMs = lastLegacyIndexRefreshAtMs
            if (previousAtMs in 1..nowMs &&
                nowMs - previousAtMs < LEGACY_INDEX_REFRESH_COOLDOWN_MS
            ) {
                false
            } else {
                lastLegacyIndexRefreshAtMs = nowMs
                true
            }
        }
        if (!shouldRequest) {
            return
        }
        NPLogger.d(tag, "本地音频已可读，后台轻量刷新下载索引")
        GlobalDownloadManager.scanLocalFiles(context, forceRefresh = false)
    }

    private fun safeToPlayableUri(reference: String?): String? {
        return runCatching {
            ManagedDownloadStorage.toPlayableUri(reference)
        }.getOrNull()
    }


    fun getLocalPlaybackUri(context: Context, song: SongItem): String? {
        val songKey = song.stableKey()
        if (GlobalDownloadManager.isSongCancelled(songKey)) {
            return null
        }
        val managedDownloadHint = runCatching {
            ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, song)
        }.getOrDefault(false)
        if (
            shouldWaitForManagedPlaybackDirectoryMutation(
                isManagedDownload = managedDownloadHint,
                mutationActive = ManagedDownloadDirectoryMutationFence.isActiveFast(context)
            )
        ) {
            NPLogger.d(
                tag,
                "同步播放解析遇到目录迁移，暂不使用旧引用: song=${song.name}"
            )
            return null
        }
        resolveRecentlyCommittedAudioReference(
            context = context,
            song = song,
            rawReference = null
        )?.let { return it }
        // core 提交后内存目录已经有可验证引用时，先走零扫描路径
        val catalogPlaybackReference = GlobalDownloadManager
            .findAccessibleDownloadedSongPlaybackUri(
                context = context,
                song = song
            )
        if (
            catalogPlaybackReference != null &&
            (!managedDownloadHint || isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                reference = catalogPlaybackReference,
                configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
            ))
        ) {
            return catalogPlaybackReference
        }
        if (catalogPlaybackReference != null) {
            NPLogger.d(
                tag,
                "目录切换后忽略缓存中的旧播放引用: " +
                    "song=${song.name}, reference=$catalogPlaybackReference"
            )
        }
        resolveReadableManagedDownload(context, song)
            ?.let(::playableReferenceForManagedAudio)
            ?.let { return it }
        val indexedReferences = GlobalDownloadManager.findDownloadedSongCached(song)
            ?.let(::downloadedSongPlaybackReferenceCandidates)
            ?: return null
        return indexedReferences.asSequence()
            .filter { reference ->
                !managedDownloadHint || isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                    reference = reference,
                    configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
                )
            }
            .mapNotNull { reference ->
                resolveReboundDownloadedPlaybackUri(context, reference)
            }
            .firstOrNull()
    }

    suspend fun resolvePermittedLocalPlaybackUri(
        context: Context,
        song: SongItem,
        rawLocalReference: String?
    ): String? {
        return (resolvePermittedLocalPlayback(
            context = context,
            song = song,
            rawLocalReference = rawLocalReference
        ) as? LocalPlaybackReferenceResolution.Playable)?.reference
    }

    suspend fun resolvePermittedLocalPlayback(
        context: Context,
        song: SongItem,
        rawLocalReference: String?
    ): LocalPlaybackReferenceResolution = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val reference = rawLocalReference?.trim()?.takeIf(String::isNotBlank)
        if (reference == null) {
            // 队列恢复可能先拿到歌曲身份，再异步补齐引用
            getLocalPlaybackUri(appContext, song)?.let { verifiedReference ->
                return@withContext LocalPlaybackReferenceResolution.Playable(
                    verifiedReference
                )
            }
            return@withContext LocalPlaybackReferenceResolution.TemporarilyUnavailable(
                ManagedDownloadReferenceLookup.Result.OutOfScope
            )
        }
        val managedDownloadHint = runCatching {
            ManagedDownloadStorage.isLikelyManagedDownloadSongFast(appContext, song)
        }.getOrDefault(false)
        if (
            shouldWaitForManagedPlaybackDirectoryMutation(
                isManagedDownload = managedDownloadHint,
                mutationActive = runCatching {
                    ManagedDownloadDirectoryMutationFence.isActive(appContext)
                }.getOrDefault(true)
            )
        ) {
            val opened = withTimeoutOrNull(directoryMutationWaitMs) {
                ManagedDownloadDirectoryMutationFence.awaitOpen()
                true
            } == true
            if (!opened) {
                NPLogger.d(
                    tag,
                    "目录迁移仍在进行，暂不使用旧播放引用: song=${song.name}"
                )
                return@withContext LocalPlaybackReferenceResolution.TemporarilyUnavailable(
                    ManagedDownloadReferenceLookup.Result.OutOfScope
                )
            }
        }
        // core 提交桥已验证完整音频，必须在 SAF 查询前消费
        resolveRecentlyCommittedAudioReference(
            context = appContext,
            song = song,
            rawReference = reference
        )?.let { bridgedReference ->
            return@withContext LocalPlaybackReferenceResolution.Playable(bridgedReference)
        }
        val isManagedDownload = try {
            ManagedDownloadStorage.isLikelyManagedDownloadSong(appContext, song)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            NPLogger.w(
                tag,
                "识别受管本地音频失败，按内存线索保守处理: ${error.message}"
            )
            ManagedDownloadStorage.isLikelyManagedDownloadSongFast(appContext, song)
        }
        val configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        val rawReferenceCompatible = !isManagedDownload ||
            isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                reference = reference,
                configuredDirectoryUri = configuredDirectoryUri
            )
        if (isManagedDownload && !rawReferenceCompatible) {
            NPLogger.d(
                tag,
                "目录切换后拒绝旧受管播放引用，准备重绑当前根: " +
                    "song=${song.name}, reference=$reference"
            )
        }
        val rawEvidence = if (rawReferenceCompatible) {
            ManagedDownloadReferenceLookup.inspect(appContext, reference)
        } else {
            // 旧根引用不再探测 Provider，统一交给有界重绑定流程
            ManagedDownloadReferenceLookup.Result.Missing
        }
        val managedReferenceIsExplicitlyIncomplete = if (isManagedDownload && rawReferenceCompatible) {
            isManagedReferenceExplicitlyIncomplete(
                context = appContext,
                song = song,
                reference = reference
            )
        } else {
            false
        }
        if (
            rawReferenceCompatible && shouldUseDirectPresentLocalPlayback(
                reference = reference,
                isManagedDownload = isManagedDownload,
                evidence = rawEvidence,
                downloadCancelled = GlobalDownloadManager.isSongCancelled(song.stableKey())
            )
        ) {
            if (isManagedDownload && managedReferenceIsExplicitlyIncomplete) {
                NPLogger.d(
                    tag,
                    "正式下载引用已确认可读，跳过旧完成状态门禁: " +
                        "song=${song.name}, reference=$reference"
                )
            }
            return@withContext selectPermittedLocalPlaybackResolution(
                rawLocalReference = reference,
                isManagedDownload = isManagedDownload,
                verifiedManagedReference = null,
                rawEvidence = rawEvidence,
                managedReferenceIsExplicitlyIncomplete = false
            )
        }
        val verifiedManagedReference = if (isManagedDownload) {
            getLocalPlaybackUri(appContext, song)
                ?: if (rawEvidence == ManagedDownloadReferenceLookup.Result.Missing) {
                    forceResolveManagedPlaybackAfterMissing(
                        context = appContext,
                        song = song
                    )
                } else {
                    null
                }
        } else {
            null
        }
        // 已验证的新引用不能再被旧 pending 标记覆盖
        val effectiveIncomplete = if (verifiedManagedReference != null) {
            false
        } else {
            managedReferenceIsExplicitlyIncomplete
        }
        selectPermittedLocalPlaybackResolution(
            rawLocalReference = reference,
            isManagedDownload = isManagedDownload,
            verifiedManagedReference = verifiedManagedReference,
            rawEvidence = rawEvidence,
            managedReferenceIsExplicitlyIncomplete = effectiveIncomplete,
            missingIsTransient = isRecentManagedPlaybackReference(
                song = song,
                reference = reference
            )
        )
    }
    private fun resolveReadableManagedDownload(
        context: Context,
        song: SongItem
    ): ManagedDownloadStorage.StoredEntry? {
        val cachedSnapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        )
        val cachedAudio = cachedSnapshot?.let { snapshot ->
            ManagedDownloadStorage.findDownloadedAudio(snapshot, song)
                ?: ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song)
        } ?: ManagedDownloadStorage.peekDownloadedAudio(song)
            ?: ManagedDownloadStorage.peekPendingDownloadedAudio(song)
        cachedAudio?.let { audio ->
            val reference = playableReferenceForManagedAudio(audio)
                ?: return@let
            val evidence = ManagedDownloadReferenceLookup.inspect(
                context,
                reference
            )
            if (canUseReadableManagedAudioForPlayback(
                    song = song,
                    snapshot = cachedSnapshot,
                    audio = audio,
                    evidence = evidence
                )
            ) {
                if (!canExposeManagedDownloadForPlayback(cachedSnapshot, audio)) {
                    NPLogger.d(
                        tag,
                        "直接确认本地下载音频可读，跳过未完成快照门禁: " +
                            "song=${song.name}, file=${audio.name}, " +
                            "rootEntriesComplete=${cachedSnapshot?.rootEntriesComplete}"
                    )
                    requestBackgroundDownloadIndexRefresh(context)
                }
                return audio
            }
            if (evidence == ManagedDownloadReferenceLookup.Result.Present) {
                NPLogger.d(
                    tag,
                    "下载音频已存在但仍处于临时或替换状态，暂不作为本地歌曲播放: " +
                        "song=${song.name}, file=${audio.name}"
                )
                return null
            }
            when (evidence) {
                ManagedDownloadReferenceLookup.Result.Present -> return null
                ManagedDownloadReferenceLookup.Result.Missing -> {
                    NPLogger.w(
                        tag,
                        "本地下载索引确认缺失，准备强制刷新: " +
                            "song=${song.name}, reference=" +
                                "${playableReferenceForManagedAudio(audio)}"
                    )
                    GlobalDownloadManager.scanLocalFiles(context, forceRefresh = true)
                }
                is ManagedDownloadReferenceLookup.Result.PermissionLost,
                is ManagedDownloadReferenceLookup.Result.ProviderFailure,
                ManagedDownloadReferenceLookup.Result.OutOfScope -> {
                    NPLogger.w(
                        tag,
                        "本地下载索引引用暂不可确认，保留并等待对账: " +
                            "song=${song.name}, reference=" +
                                "${playableReferenceForManagedAudio(audio)}, " +
                            "evidence=$evidence"
                    )
                    return null
                }
            }
        }

        if (!canBlockStorageLookup()) {
            return null
        }
        val snapshot = cachedSnapshot
            ?: if (ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
                ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context, restorePersisted = false)
            } else {
                null
            }
        if (snapshot == null) {
            return null
        }

        val indexedAudio = ManagedDownloadStorage.findDownloadedAudio(snapshot, song)
            ?: ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song)
        if (indexedAudio != null) {
            val reference = playableReferenceForManagedAudio(indexedAudio)
                ?: return null
            val evidence = ManagedDownloadReferenceLookup.inspect(
                context,
                reference
            )
            if (canUseReadableManagedAudioForPlayback(
                    song = song,
                    snapshot = snapshot,
                    audio = indexedAudio,
                    evidence = evidence
                )
            ) {
                if (!canExposeManagedDownloadForPlayback(snapshot, indexedAudio)) {
                    NPLogger.d(
                        tag,
                        "直接确认索引下载音频可读，跳过未完成快照门禁: " +
                            "song=${song.name}, file=${indexedAudio.name}, " +
                            "rootEntriesComplete=${snapshot.rootEntriesComplete}"
                    )
                    requestBackgroundDownloadIndexRefresh(context)
                }
                return indexedAudio
            }
            if (evidence == ManagedDownloadReferenceLookup.Result.Present) {
                NPLogger.d(
                    tag,
                    "索引音频已存在但仍处于临时或替换状态，暂不作为本地歌曲播放: " +
                        "song=${song.name}, file=${indexedAudio.name}"
                )
                return null
            }
            when (evidence) {
                ManagedDownloadReferenceLookup.Result.Present -> return null
                ManagedDownloadReferenceLookup.Result.Missing -> {
                    NPLogger.w(
                        tag,
                        "下载索引确认缺失，准备强制刷新: " +
                            "song=${song.name}, reference=" +
                                "${playableReferenceForManagedAudio(indexedAudio)}"
                    )
                    GlobalDownloadManager.scanLocalFiles(context, forceRefresh = true)
                }
                is ManagedDownloadReferenceLookup.Result.PermissionLost,
                is ManagedDownloadReferenceLookup.Result.ProviderFailure,
                ManagedDownloadReferenceLookup.Result.OutOfScope -> {
                    NPLogger.w(
                        tag,
                        "下载索引引用暂不可确认，保留并等待对账: " +
                            "song=${song.name}, reference=" +
                                "${playableReferenceForManagedAudio(indexedAudio)}, " +
                            "evidence=$evidence"
                    )
                    return null
                }
            }
        }

        if (!GlobalDownloadManager.hasDownloadedSongCached(song)) {
            return null
        }

        NPLogger.w(
            tag,
            "下载目录缓存命中但快照索引未命中，回退目录缓存播放并后台对账: song=${song.name}"
        )
        GlobalDownloadManager.scanLocalFiles(context, forceRefresh = false)
        return null
    }

    /**
     * catalog 已就绪但歌曲身份尚未同步时，只读取持久化快照中的目标条目
     * 这里不枚举 SAF，避免把播放请求拖进整目录扫描
     */
    private fun findDurableCachedManagedAudio(
        context: Context,
        song: SongItem,
        restorePersisted: Boolean = true
    ): CachedManagedAudio? {
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = restorePersisted
        )
        val audio = snapshot?.let { currentSnapshot ->
            ManagedDownloadStorage.findDownloadedAudio(currentSnapshot, song)
                ?: ManagedDownloadStorage.findPendingDownloadedAudio(currentSnapshot, song)
        } ?: ManagedDownloadStorage.peekDownloadedAudio(song)
            ?: ManagedDownloadStorage.peekPendingDownloadedAudio(song)
        return audio?.let { CachedManagedAudio(snapshot = snapshot, audio = it) }
    }

    private fun canUseReadableManagedAudioForPlayback(
        song: SongItem,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        audio: ManagedDownloadStorage.StoredEntry,
        evidence: ManagedDownloadReferenceLookup.Result
    ): Boolean {
        if (evidence != ManagedDownloadReferenceLookup.Result.Present) {
            return false
        }
        val metadata = metadataForManagedAudio(snapshot, audio)
        return isReadableManagedAudioPlaybackAllowed(
            audioIsPending = audio.isPendingAudioWrite,
            downloadActive = isSongDownloadActive(song.stableKey()),
            downloadCancelled = GlobalDownloadManager.isSongCancelled(song.stableKey()),
            metadata = metadata,
            allowLegacyPublishedAudio = !audio.isPendingAudioWrite
        )
    }

    private fun metadataForManagedAudio(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
        audio: ManagedDownloadStorage.StoredEntry
    ): ManagedDownloadStorage.DownloadedAudioMetadata? {
        return ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
    }

    private fun playableReferenceForManagedAudio(
        audio: ManagedDownloadStorage.StoredEntry
    ): String? {
        // 迁移快照可能同时包含旧私有路径和新的 SAF URI，优先使用当前根下的引用
        // 避免目录切换和目录刷新之间播放器打开过期路径
        return selectManagedPlaybackReferenceForConfiguredSaf(
            references = listOfNotNull(
                audio.mediaUri,
                audio.reference,
                audio.localFilePath
            ),
            configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri(),
            allowPending = audio.isPendingAudioWrite
        )
    }

    /**
     * 只读取现有内存快照判断是否存在明确的临时写入或替换凭据
     *
     * 这里不能触发目录扫描，否则播放线程会被 SAF 查询拖慢
     */
    private fun isManagedReferenceExplicitlyIncomplete(
        context: Context,
        song: SongItem,
        reference: String
    ): Boolean {
        val songKey = song.stableKey()
        if (
            GlobalDownloadManager.isSongCancelled(songKey) ||
            reference.lowercase(Locale.ROOT).contains(
                DOWNLOAD_STAGING_DIR_NAME.lowercase(Locale.ROOT)
            )
        ) {
            return true
        }
        val referenceLooksPending = reference.contains(
            PENDING_AUDIO_WRITE_MARKER,
            ignoreCase = true
        )
        val referenceName = ManagedDownloadStorage.normalizeManagedAudioFileName(reference)
        if (
            referenceName?.let { name ->
                name.startsWith(DOWNLOAD_STAGING_FILE_PREFIX) &&
                    name.endsWith(DOWNLOAD_STAGING_FILE_SUFFIX)
            } == true
        ) {
            return true
        }
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        ) ?: return referenceLooksPending
        val indexedFileName = referenceName
        // 先按用户手里的精确引用匹配, 防止同一首歌的 pending 条目遮蔽旧正式文件
        val exactReferences = listOfNotNull(
            reference,
            safeToPlayableUri(reference)
        ).distinct()
        fun matchesReference(audio: ManagedDownloadStorage.StoredEntry): Boolean {
            return exactReferences.any { candidate ->
                candidate == audio.reference ||
                    candidate == audio.mediaUri ||
                    candidate == audio.localFilePath ||
                    candidate == ManagedDownloadStorage.resolveStoredEntryPlaybackUri(
                        entry = audio,
                        allowPending = true
                    )
            }
        }
        val exactAudio = snapshot.audioEntries.firstOrNull(::matchesReference)
            ?: snapshot.pendingAudioEntries.firstOrNull(::matchesReference)
        if (exactAudio == null && !referenceLooksPending) {
            // 旧版本保存的 tree/document URI 可能与当前扫描得到的 URI 外形不同。
            // 只要用户手里的正式引用已经得到 Present 证据, 不能让另一个
            // 同身份的 pending 条目遮蔽它并误报为暂不可播放。
            val formalAudio = snapshot.audioEntries.firstOrNull { audio ->
                !audio.isPendingAudioWrite &&
                    (audio.name == indexedFileName ||
                        audio.logicalName == indexedFileName)
            }
            return formalAudio?.let { audio ->
                !isReadableManagedAudioPlaybackAllowed(
                    audioIsPending = false,
                    downloadActive = false,
                    downloadCancelled = false,
                    metadata = metadataForManagedAudio(snapshot, audio),
                    allowLegacyPublishedAudio = true
                )
            } ?: false
        }
        val matchedAudio = exactAudio
            ?: ManagedDownloadStorage.findDownloadedAudio(snapshot, song)
            ?: ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song)
            ?: snapshot.audioEntries.firstOrNull { audio ->
                audio.name == indexedFileName
            }
            ?: return referenceLooksPending
        return !isReadableManagedAudioPlaybackAllowed(
            audioIsPending = matchedAudio.isPendingAudioWrite,
            downloadActive = false,
            downloadCancelled = false,
            metadata = metadataForManagedAudio(snapshot, matchedAudio),
            allowLegacyPublishedAudio = !matchedAudio.isPendingAudioWrite
        )
    }

    internal suspend fun hasFastCachedManagedDownloadForStart(
        context: Context,
        song: SongItem
    ): Boolean {
        val cached = findDurableCachedManagedAudio(context, song)
        val snapshot = cached?.snapshot
        val cachedAudio = cached?.audio
        if (cachedAudio != null) {
            // 与队列入口共用校验，不能把已拒绝的旧音频再次送回收尾
            return GlobalDownloadManager.validateExistingDownloadedAudio(
                context, song, cachedAudio,
                ManagedDownloadStorage.metadataForAudioEntry(snapshot, cachedAudio)
            ) != null
        }
        return GlobalDownloadManager.findFastCachedDownloadedSongPlaybackUri(context, song) != null
    }

    private fun resolveRecentlyCommittedAudioReference(
        context: Context,
        song: SongItem,
        rawReference: String? = null
    ): String? {
        val songKey = song.stableKey()
        val audio = rawReference?.let { reference ->
            completedAudioReferenceRegistry.peekCompletedAudioReferenceByRawReference(reference)
        }
            ?: completedAudioReferenceRegistry.peekCompletedAudioReference(song)
            ?: return null
        val reference = playableReferenceForManagedAudio(audio) ?: return null
        if (shouldDiscardCompletedReferenceForConfiguredSaf(
                reference = reference,
                configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
            )
        ) {
            completedAudioReferenceRegistry.invalidateCompletedAudioReference(song)
            NPLogger.d(
                tag,
                "目录已切换到 SAF，丢弃旧完成桥接引用并等待当前根重绑定: " +
                    "songKey=$songKey, reference=$reference"
            )
            return null
        }
        if (isMissingFilePlaybackReference(reference)) {
            completedAudioReferenceRegistry.invalidateCompletedAudioReference(song)
            NPLogger.d(
                tag,
                "完成桥接引用已不存在，等待当前存储根重新绑定: " +
                    "songKey=$songKey, reference=$reference"
            )
            return null
        }
        if (!shouldUseCompletedAudioReferenceDirectly(
                reference = reference,
                downloadCancelled = GlobalDownloadManager.isSongCancelled(songKey)
            )
        ) {
            return null
        }
        NPLogger.d(
            tag,
            "core 音频已提交，目录回调尚未完成，直接使用已校验引用并后台校验: " +
                "songKey=$songKey, reference=$reference"
        )
        // 这里不能同步查询 SAF。后台扫描只负责更新索引，Provider 短暂异常时保留桥接。
        GlobalDownloadManager.scanLocalFiles(context, forceRefresh = false)
        return ManagedDownloadStorage.toPlayableUri(reference) ?: reference
    }

    /**
     * 受管目录中的本地引用必须经过完成凭据校验，不能仅凭文件仍可读取就播放
     */
    /** 旧 catalog 只剩失效路径时，单飞一次强制快照重绑到迁移后的引用 */
    private suspend fun forceResolveManagedPlaybackAfterMissing(
        context: Context,
        song: SongItem
    ): String? {
        val songKey = song.stableKey()
        val nowMs = System.currentTimeMillis()
        val shouldRefresh = synchronized(managedPlaybackRebindAtMsBySongKey) {
            val previousAtMs = managedPlaybackRebindAtMsBySongKey[songKey]
            if (previousAtMs != null && nowMs - previousAtMs < MANAGED_PLAYBACK_REBIND_COOLDOWN_MS) {
                false
            } else {
                managedPlaybackRebindAtMsBySongKey[songKey] = nowMs
                true
            }
        }
        if (!shouldRefresh) {
            return null
        }
        val audio = try {
            ManagedDownloadStorage.findDownloadedAudio(
                context = context,
                song = song,
                forceRefresh = true
            ) ?: ManagedDownloadStorage.peekDownloadedAudio(song)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                tag,
                "旧本地引用缺失时强制重绑失败，保留目录等待重试: " +
                    "song=${song.name}, error=${error.message}",
                error
            )
            return null
        }
        val reference = audio
            ?.let(::playableReferenceForManagedAudio)
            ?.takeIf { candidate ->
                ManagedDownloadReferenceLookup.inspect(context, candidate) ==
                    ManagedDownloadReferenceLookup.Result.Present
            }
        if (reference != null) {
            requestBackgroundDownloadIndexRefresh(context)
            return ManagedDownloadStorage.toPlayableUri(reference) ?: reference
        }
        GlobalDownloadManager.scanLocalFiles(context, forceRefresh = false)
        return null
    }

    private fun isMissingFilePlaybackReference(reference: String): Boolean {
        val normalized = reference.trim()
        val path = when {
            normalized.startsWith("/") -> normalized
            normalized.startsWith("file:", ignoreCase = true) -> {
                runCatching { URI(normalized).path }.getOrNull()
            }
            else -> null
        } ?: return false
        return !File(path).isFile
    }

    internal fun resolveIndexedLocalPlaybackReference(
        context: Context,
        song: SongItem
    ): LocalPlaybackReferenceResolution {
        if (!mayHaveIndexedLocalDownload(context, song)) {
            return LocalPlaybackReferenceResolution.NotIndexed
        }
        // mayHaveIndexedLocalDownload 可能刚从 Room/磁盘快照恢复了条目。
        // catalog 仍未发布时也要把该持久化引用带入后续完整性门禁。
        val durableCachedAudio = findDurableCachedManagedAudio(
            context = context,
            song = song,
            restorePersisted = false
        )
        val verifiedReference = getLocalPlaybackUri(context, song)
        val indexedReference = listOfNotNull(
            GlobalDownloadManager.findDownloadedSongCached(song)
                ?.let(::resolveDownloadedSongPlaybackReference),
            durableCachedAudio?.audio?.let(::playableReferenceForManagedAudio),
            ManagedDownloadStorage.peekPendingDownloadedAudio(song)
                ?.let(::playableReferenceForManagedAudio)
        ).firstOrNull { reference ->
            isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                reference = reference,
                configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
            )
        }
        val indexedEvidence = if (verifiedReference == null && indexedReference != null) {
            ManagedDownloadReferenceLookup.inspect(context, indexedReference)
        } else {
            null
        }
        if (
            verifiedReference == null &&
            indexedReference != null &&
            shouldUseDirectPresentLocalPlayback(
                reference = indexedReference,
                isManagedDownload = true,
                evidence = indexedEvidence
                    ?: ManagedDownloadReferenceLookup.Result.OutOfScope,
                downloadCancelled = GlobalDownloadManager.isSongCancelled(song.stableKey())
            )
        ) {
            NPLogger.d(
                tag,
                "索引中的正式下载音频已确认可读，跳过旧完成状态门禁: " +
                    "song=${song.name}, reference=$indexedReference"
            )
            return LocalPlaybackReferenceResolution.Playable(indexedReference)
        }
        val indexedReferenceIsExplicitlyIncomplete = if (
            verifiedReference == null &&
                indexedReference != null &&
                indexedEvidence == ManagedDownloadReferenceLookup.Result.Present
        ) {
            isManagedReferenceExplicitlyIncomplete(
                context = context,
                song = song,
                reference = indexedReference
            )
        } else {
            false
        }
        return selectIndexedLocalPlaybackResolution(
            verifiedReference = verifiedReference,
            indexedReference = indexedReference,
            indexedEvidence = indexedEvidence,
            indexedReferenceIsExplicitlyIncomplete = indexedReferenceIsExplicitlyIncomplete,
            missingIsTransient = indexedReference?.let {
                isRecentManagedPlaybackReference(song = song, reference = it)
            } == true
        )
    }

    private fun isRecentManagedPlaybackReference(
        song: SongItem,
        reference: String
    ): Boolean {
        return reference.contains(PENDING_AUDIO_WRITE_MARKER, ignoreCase = true) ||
            completedAudioReferenceRegistry.peekCompletedAudioReference(song) != null ||
            isSongDownloadActive(song.stableKey())
    }

    fun mayHaveIndexedLocalDownload(context: Context, song: SongItem): Boolean {
        if (completedAudioReferenceRegistry.peekCompletedAudioReference(song) != null) {
            return true
        }
        if (GlobalDownloadManager.hasDownloadedSongCached(song)) {
            return true
        }
        if (ManagedDownloadStorage.peekPendingDownloadedAudio(song) != null) {
            return true
        }
        // catalogReady 只表示内存目录已发布，不代表它已包含当前队列身份。
        // 仅在目录已发布且内存索引 miss 时恢复一次 Room/磁盘快照，避免播放
        // 请求重复读取持久化索引；快照查询本身不会枚举 SAF。
        val catalogReady = GlobalDownloadManager.isDownloadedSongCatalogReady()
        if (
            findDurableCachedManagedAudio(
                context = context,
                song = song,
                restorePersisted = catalogReady
            ) != null
        ) {
            return true
        }
        if (catalogReady) {
            return false
        }
        if (!canBlockStorageLookup()) {
            return false
        }
        if (!ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
            return false
        }
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        ) ?: return false
        val audio = ManagedDownloadStorage.findDownloadedAudio(snapshot, song)
        if (audio != null) {
            // 快照可能仍是增量或预览状态，真正的完整性和可读性由
            // getLocalPlaybackUri 的 Present 和 artifact 凭据校验负责
            return true
        }
        return ManagedDownloadStorage.findPendingDownloadedAudio(snapshot, song) != null
    }

    fun hasLocalDownload(context: Context, song: SongItem): Boolean {
        val songKey = song.stableKey()
        if (GlobalDownloadManager.isSongCancelled(songKey) || isSongDownloadActive(songKey)) {
            return false
        }
        return resolveReadableDownloadedPlaybackUri(context, song) != null
    }

    /**
     * 只读取已驻留下载索引，供滚动列表恢复已有封面，避免触发目录扫描
     */
    fun peekLocalCoverUri(song: SongItem): String? {
        if (!GlobalDownloadManager.hasDownloadedSongCached(song)) {
            return null
        }
        val localAudio = ManagedDownloadStorage.peekDownloadedAudio(song) ?: return null
        val coverReference = ManagedDownloadStorage.peekCoverReference(localAudio) ?: return null
        return ManagedDownloadStorage.toPlayableUri(coverReference) ?: coverReference
    }

    /** 解析下载歌曲对应的本地封面, 供离线 UI 兜底使用 */
    fun getLocalCoverUri(
        context: Context,
        song: SongItem,
        resolveLocalMediaFallback: Boolean = true
    ): String? {
        val allowBlockingLookup = canBlockStorageLookup()
        val localAudio = findFinalizedCachedAudioForCover(
            context = context,
            song = song,
            allowBlockingLookup = allowBlockingLookup
        )
        val coverReference = localAudio?.let {
            ManagedDownloadStorage.peekCoverReference(it)
                ?: if (allowBlockingLookup && ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
                    runBlocking(Dispatchers.IO) {
                        ManagedDownloadStorage.findCoverReference(context, it)
                    }
                } else {
                    null
                }
        }
        if (!coverReference.isNullOrBlank()) {
            return ManagedDownloadStorage.toPlayableUri(coverReference) ?: coverReference
        }

        if (localAudio != null || !allowBlockingLookup) {
            return null
        }
        if (!resolveLocalMediaFallback) {
            return null
        }
        if (!LocalSongSupport.isLocalSong(song)) {
            return null
        }

        return runCatching {
            LocalMediaSupport.resolveCoverUri(context, song)
        }.getOrElse {
            NPLogger.w(tag, "resolve local cover fallback failed: ${it.message}")
            null
        }
    }

    private fun findFinalizedCachedAudioForCover(
        context: Context,
        song: SongItem,
        allowBlockingLookup: Boolean
    ): ManagedDownloadStorage.StoredEntry? {
        fun finalizedAudio(
            snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?
        ): ManagedDownloadStorage.StoredEntry? {
            val audio = snapshot?.let { currentSnapshot ->
                ManagedDownloadStorage.findDownloadedAudio(currentSnapshot, song)
            }
            return audio?.takeIf { candidate ->
                canExposeManagedDownloadForPlayback(snapshot, candidate)
            }
        }

        finalizedAudio(
            ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                context = context,
                restorePersisted = false
            )
        )?.let { return it }
        if (!allowBlockingLookup || !ManagedDownloadStorage.ensureSnapshotCacheReady(context)) {
            return null
        }
        return finalizedAudio(
            ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                context = context,
                restorePersisted = false
            )
        )
    }

    private fun resolveReadableDownloadedPlaybackUri(
        context: Context,
        song: SongItem
    ): String? {
        resolveReadableManagedDownload(context, song)
            ?.let(::playableReferenceForManagedAudio)
            ?.let { return it }
        val catalogPlaybackUri = GlobalDownloadManager.findAccessibleDownloadedSongPlaybackUri(
            context = context,
            song = song
        )?.takeIf { reference ->
            !ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, song) ||
                isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                    reference = reference,
                    configuredDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
                )
        } ?: return null
        NPLogger.d(
            tag,
            "下载索引未命中，回退下载目录缓存播放: song=${song.name}, reference=$catalogPlaybackUri"
        )
        return catalogPlaybackUri
    }

    private fun resolveReboundDownloadedPlaybackUri(
        context: Context,
        indexedReference: String
    ): String? {
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        ) ?: return null
        val reboundAudio = findReboundFinalizedManagedAudio(snapshot, indexedReference)
            ?: return null
        val playbackUri = playableReferenceForManagedAudio(reboundAudio) ?: return null
        return when (ManagedDownloadReferenceLookup.inspect(context, playbackUri)) {
            ManagedDownloadReferenceLookup.Result.Present -> playbackUri
            else -> null
        }
    }

}
