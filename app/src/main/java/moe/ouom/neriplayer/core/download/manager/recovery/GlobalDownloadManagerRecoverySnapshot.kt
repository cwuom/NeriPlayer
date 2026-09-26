package moe.ouom.neriplayer.core.download.manager.recovery

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.manager.batch.isUsableFinalizationAudioEntry
import moe.ouom.neriplayer.core.download.manager.batch.matchesFinalizationCandidateName
import moe.ouom.neriplayer.core.download.manager.batch.matchesFinalizationStoredName
import moe.ouom.neriplayer.core.download.manager.runtime.isRecoveryMetadataOwnedBySong
import moe.ouom.neriplayer.core.download.manager.runtime.loadFinalizationRecoverySnapshot
import moe.ouom.neriplayer.core.download.manager.runtime.resolveStoredAudio
import moe.ouom.neriplayer.core.download.policy.isDurableCoreArtifactState
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.CoreRecoveryAudioCandidate
import android.content.Context
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import java.util.Locale


internal suspend fun GlobalDownloadManager.resolveCoreRecoveryAudioCandidate(
    context: Context,
    song: SongItem,
    operationId: String?,
    forceRefreshSnapshot: Boolean = false,
    allowFormalAudio: Boolean = false,
    preferredAudioName: String? = null
): CoreRecoveryAudioCandidate? {
    val normalizedOperationId = operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val cachedSnapshot = if (forceRefreshSnapshot) {
        null
    } else {
        ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        )
    }
    val snapshot = cachedSnapshot ?: loadFinalizationRecoverySnapshot(
        context = context,
        forceRefresh = true
    )

    val candidates = linkedMapOf<String, ManagedDownloadStorage.StoredEntry>()
    fun addCandidate(audio: ManagedDownloadStorage.StoredEntry?) {
        if (audio == null || audio.reference.isBlank()) return
        candidates.putIfAbsent(audio.reference, audio)
    }

    addCandidate(AudioDownloadManager.peekCompletedAudioReference(song))
    runCatching { resolveStoredAudio(context, song) }
        .getOrNull()
        ?.let(::addCandidate)
    downloadedSongCatalogIndex.find(song)?.let { catalogSong ->
        listOfNotNull(
            catalogSong.mediaUri,
            catalogSong.filePath
        ).forEach { reference ->
            runCatching { resolveStoredAudio(context, reference) }
                .getOrNull()
                ?.let(::addCandidate)
        }
    }
    snapshot?.let { currentSnapshot ->
        val pending = ManagedDownloadStorage.findPendingDownloadedAudio(
            currentSnapshot,
            song
        )
        addCandidate(pending)
        addCandidate(ManagedDownloadStorage.findDownloadedAudio(currentSnapshot, song))
        preferredAudioName
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { expectedName ->
                (currentSnapshot.audioEntries +
                    currentSnapshot.audioEntriesWithoutMetadata +
                    currentSnapshot.pendingAudioEntries)
                    .asSequence()
                    .filter(::isUsableFinalizationAudioEntry)
                    .filter { audio ->
                        listOf(audio.name, audio.logicalName).any { actualName ->
                            matchesFinalizationStoredName(
                                actualName = actualName,
                                expectedName = expectedName
                            )
                        }
                    }
                    .forEach(::addCandidate)
            }
    }
    if (snapshot == null) {
        runCatching {
            ManagedDownloadStorage.listPendingAudioWrites(
                context = context,
                forceRefresh = true
            )
        }.getOrNull()?.forEach(::addCandidate)
        runCatching {
            ManagedDownloadStorage.findDownloadedAudio(
                context = context,
                song = song,
                forceRefresh = true
            )
        }.getOrNull()?.let(::addCandidate)
    }
    val coreRecoveryCandidateBaseNames =
        ManagedDownloadStorage.buildCandidateBaseNames(song).toSet()
    fun matchesCoreRecoveryCandidateName(
        audio: ManagedDownloadStorage.StoredEntry
    ): Boolean {
        return matchesFinalizationCandidateName(
            audio = audio,
            candidateBaseNames = coreRecoveryCandidateBaseNames
        )
    }
    if (allowFormalAudio) {
        snapshot?.let { currentSnapshot ->
            (currentSnapshot.audioEntries + currentSnapshot.audioEntriesWithoutMetadata)
                .asSequence()
                .filter(::isUsableFinalizationAudioEntry)
                .filter(::matchesCoreRecoveryCandidateName)
                .forEach(::addCandidate)
        }
    }
    if (allowFormalAudio) {
        // 旧版本可能只保留 metadata 的 audioFileName 或 SAF 引用，
        // 不能把文件名模板差异当成音频不存在
        snapshot?.let { currentSnapshot ->
            currentSnapshot.audioEntries
                .asSequence()
                .filter(::isUsableFinalizationAudioEntry)
                .filter { audio ->
                    val metadata = ManagedDownloadStorage.metadataForAudioEntry(
                        snapshot = currentSnapshot,
                        audio = audio
                    ) ?: return@filter false
                    isRecoveryMetadataOwnedBySong(metadata, song, normalizedOperationId)
                }
                .forEach(::addCandidate)
        }
    }
    var directMetadataProbeBudget = 8
    suspend fun metadataCandidates(
        audio: ManagedDownloadStorage.StoredEntry
    ): List<ManagedDownloadStorage.DownloadedAudioMetadata> {
        val indexed = listOfNotNull(
            snapshot?.let { currentSnapshot ->
                ManagedDownloadStorage.metadataForAudioEntry(
                    snapshot = currentSnapshot,
                    audio = audio
                )
            }
        )
        // 快照已有解析结果时不再为每个 pending 重复读 SAF；只有少量无索引
        // 候选允许直接探测，避免取消风暴把 Provider 读放大成新的卡顿
        val likelyCandidate = matchesCoreRecoveryCandidateName(audio)
        val direct = if (
            indexed.isEmpty() &&
                (likelyCandidate || directMetadataProbeBudget > 0)
        ) {
            if (!likelyCandidate) directMetadataProbeBudget--
            runCatching {
                readDownloadedMetadata(context, audio)
            }.getOrNull()
        } else {
            null
        }
        return (indexed + listOfNotNull(direct))
            .distinct()
            .filter { metadata ->
                metadata.downloadFinalized == true ||
                    isDurableCoreArtifactState(
                        metadata.artifactState
                            ?.trim()
                            ?.uppercase(Locale.ROOT)
                    )
            }
    }

    suspend fun collectMatches(): List<CoreRecoveryAudioCandidate> {
        val matches = mutableListOf<CoreRecoveryAudioCandidate>()
        candidates.values.forEach { audio ->
            metadataCandidates(audio).forEach { metadata ->
                val metadataOperationId = metadata.operationId
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                val operationMatches = normalizedOperationId != null &&
                    metadataOperationId == normalizedOperationId
                val identityMatches = isRecoveryMetadataOwnedBySong(
                    metadata, song, normalizedOperationId
                )
                if (
                    identityMatches &&
                        (audio.isPendingAudioWrite || operationMatches || allowFormalAudio) &&
                        invalidCoreAudioReason(context, song, audio, normalizedOperationId.orEmpty()) == null
                ) {
                    matches += CoreRecoveryAudioCandidate(
                        audio = audio,
                        metadata = metadata,
                        operationMatches = operationMatches,
                        identityMatches = identityMatches
                    )
                }
            }
        }
        return matches
    }

    fun sortMatches(
        matches: List<CoreRecoveryAudioCandidate>
    ): CoreRecoveryAudioCandidate? {
        return matches
            .distinctBy { candidate ->
                candidate.audio.reference to candidate.metadata
            }
            .sortedWith(
                compareByDescending<CoreRecoveryAudioCandidate> {
                    it.operationMatches
                }
                    .thenByDescending { it.identityMatches }
                    .thenByDescending { it.audio.isPendingAudioWrite }
                    .thenByDescending { it.metadata?.downloadFinalized == true }
                    .thenByDescending { it.audio.lastModifiedMs }
                    .thenByDescending { it.audio.sizeBytes }
            )
            .firstOrNull()
    }

    val bestMatch = sortMatches(collectMatches())
    if (bestMatch != null) return bestMatch

    // 快照索引若因旧版本或 provider 延迟漏掉了目标，才扩展到全部 pending
    // 候选。正常取消路径不会为每首歌重复读取整棵目录
    if (cachedSnapshot != null) {
        return resolveCoreRecoveryAudioCandidate(
            context = context,
            song = song,
            operationId = operationId,
            forceRefreshSnapshot = true,
            allowFormalAudio = allowFormalAudio,
            preferredAudioName = preferredAudioName
        )
    }
    snapshot?.pendingAudioEntries?.forEach(::addCandidate)
    if (snapshot == null) {
        runCatching {
            ManagedDownloadStorage.listPendingAudioWrites(
                context = context,
                forceRefresh = true
            )
        }.getOrNull()?.forEach(::addCandidate)
    }
    directMetadataProbeBudget = 8
    sortMatches(collectMatches())?.let { return it }
    if (allowFormalAudio) {
        // seed metadata 也可能因旧版本或 Provider 瞬时失败完全缺失。
        // 已知 operation 已越过 core 边界时，只接受当前歌曲命名匹配的
        // 音频，先补写 core metadata，再走同一提升器
        val metadataLessCandidates = candidates.values
            .asSequence()
            .filter(::matchesCoreRecoveryCandidateName)
            .filter(::isUsableFinalizationAudioEntry)
            .distinctBy(ManagedDownloadStorage.StoredEntry::reference)
            .toList()
        val metadataLessAudio = metadataLessCandidates
            .singleOrNull()
        if (metadataLessAudio != null &&
            readDownloadedMetadata(context, metadataLessAudio) == null &&
            snapshot?.let {
                ManagedDownloadStorage.metadataForAudioEntry(it, metadataLessAudio)
            } == null
        ) {
            return CoreRecoveryAudioCandidate(
                audio = metadataLessAudio,
                metadata = null,
                operationMatches = false,
                identityMatches = true
            )
        }
    }
    return null
}
