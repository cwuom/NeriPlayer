package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedAudioEntry
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_FILE_PREFIX
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_FILE_SUFFIX
import moe.ouom.neriplayer.core.download.storage.directory.ManagedDownloadDirectoryIdentity
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject
import java.util.Locale

internal fun shouldAbortDownloadWork(
    allDownloadsCancelled: Boolean,
    batchSessionCurrent: Boolean,
    songCancelled: Boolean,
    networkPolicyPaused: Boolean,
    attemptAllowsWork: Boolean
): Boolean {
    return allDownloadsCancelled ||
        !batchSessionCurrent ||
        songCancelled ||
        networkPolicyPaused ||
        !attemptAllowsWork
}

/** system and stale-attempt cancellation must leave a resumable staging file behind */
internal fun shouldPreserveWorkingArtifactsAfterCancellation(
    cancellation: Boolean,
    allDownloadsCancelled: Boolean,
    songCancelled: Boolean,
    networkPolicyPaused: Boolean
): Boolean {
    return networkPolicyPaused || (
        cancellation &&
            !allDownloadsCancelled &&
            !songCancelled
        )
}

/** 只有网络从未确认切换为已确认时才唤醒一次恢复流程 */
internal fun shouldTriggerNetworkRecovery(
    wasConfirmed: Boolean,
    isConfirmed: Boolean
): Boolean {
    return isConfirmed && !wasConfirmed
}

internal fun selectPermittedLocalPlaybackReference(
    rawLocalReference: String?,
    isManagedDownload: Boolean,
    verifiedManagedReference: String?
): String? {
    val rawReference = rawLocalReference?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (!isManagedDownload) {
        return rawReference
    }
    return verifiedManagedReference?.trim()?.takeIf(String::isNotBlank)
}

internal sealed interface LocalPlaybackReferenceResolution {
    data class Playable(val reference: String) : LocalPlaybackReferenceResolution

    data object NotIndexed : LocalPlaybackReferenceResolution

    data object Missing : LocalPlaybackReferenceResolution

    data class TemporarilyUnavailable(
        val evidence: ManagedDownloadReferenceLookup.Result
    ) : LocalPlaybackReferenceResolution
}

/**
 * 正式文件已经得到 provider Present 证据时可以立即播放
 * pending 和 staging 只代表写入中的候选引用, 仍需走完成凭据门禁
 */
internal fun shouldUseDirectPresentLocalPlayback(
    reference: String?,
    isManagedDownload: Boolean,
    evidence: ManagedDownloadReferenceLookup.Result,
    downloadCancelled: Boolean = false
): Boolean {
    if (downloadCancelled || evidence != ManagedDownloadReferenceLookup.Result.Present) {
        return false
    }
    if (!isManagedDownload) {
        return reference?.trim()?.isNotBlank() == true
    }
    return isFormalManagedAudioReference(reference)
}

/**
 * core 提交桥只跳过瞬时 Provider 查询, 不跳过取消和 staging 安全边界
 * pending 引用在桥接存在时可以播放, 因为登记前已经完成完整性校验
 */
internal fun shouldUseCompletedAudioReferenceDirectly(
    reference: String?,
    downloadCancelled: Boolean = false
): Boolean {
    val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return false
    if (downloadCancelled || normalized.contains(DOWNLOAD_STAGING_DIR_NAME, ignoreCase = true)) {
        return false
    }
    val fileName = ManagedDownloadStorage.normalizeManagedAudioFileName(normalized)
        ?: return false
    return !(fileName.startsWith(DOWNLOAD_STAGING_FILE_PREFIX) &&
        fileName.endsWith(DOWNLOAD_STAGING_FILE_SUFFIX))
}

internal fun shouldWaitForManagedPlaybackDirectoryMutation(
    isManagedDownload: Boolean,
    mutationActive: Boolean
): Boolean = isManagedDownload && mutationActive

internal fun shouldInvalidateCompletedAudioReferenceForRoot(
    referenceRootGeneration: Long,
    currentRootGeneration: Long
): Boolean = referenceRootGeneration != currentRootGeneration

/**
 * SAF 根切换后，完成桥中的旧 file URI 或其他 tree 不能再直接交给播放器
 * 这里只做 URI 字符串解析，不访问 DocumentsProvider，避免拖慢首播
 */
internal fun shouldDiscardCompletedReferenceForConfiguredSaf(
    reference: String?,
    configuredDirectoryUri: String?
): Boolean {
    val normalizedReference = reference?.trim()?.takeIf(String::isNotBlank) ?: return false
    val configured = configuredDirectoryUri?.trim()?.takeIf(String::isNotBlank) ?: return false
    if (normalizedReference.startsWith("/") ||
        normalizedReference.startsWith("file:", ignoreCase = true)
    ) {
        return true
    }
    if (!normalizedReference.startsWith("content:", ignoreCase = true)) {
        return false
    }
    val normalizedConfigured = ManagedDownloadDirectoryIdentity
        .normalizeConfiguredDirectoryUri(configured)
        ?: return false
    val configuredAuthority = ManagedDownloadDirectoryIdentity
        .extractDirectoryAuthority(normalizedConfigured)
        .takeIf(String::isNotBlank)
        ?: return false
    val referenceAuthority = ManagedDownloadDirectoryIdentity
        .extractDirectoryAuthority(normalizedReference)
    if (!referenceAuthority.equals(configuredAuthority, ignoreCase = true)) {
        return true
    }
    val configuredTreeId = ManagedDownloadDirectoryIdentity.extractDirectoryDocumentId(
        normalizedConfigured,
        "/tree/"
    ) ?: return false
    val referenceTreeId = ManagedDownloadDirectoryIdentity.extractDirectoryDocumentId(
        normalizedReference,
        "/tree/"
    )
    if (referenceTreeId != null) {
        return referenceTreeId != configuredTreeId
    }
    val referenceDocumentId = ManagedDownloadDirectoryIdentity.extractDirectoryDocumentId(
        normalizedReference,
        "/document/"
    ) ?: return false
    return referenceDocumentId != configuredTreeId &&
        !referenceDocumentId.startsWith("$configuredTreeId/")
}

/**
 * 判断受管下载引用是否仍属于当前配置的 SAF 根
 *
 * 迁移期间一个条目可能同时保留旧私有路径和新的 content URI。播放侧
 * 必须先过滤旧根引用，避免目录切换和播放器打开文件之间出现竞态
 */
internal fun isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
    reference: String?,
    configuredDirectoryUri: String?
): Boolean {
    return !shouldDiscardCompletedReferenceForConfiguredSaf(
        reference = reference,
        configuredDirectoryUri = configuredDirectoryUri
    )
}

/**
 * 从一个受管条目的多个别名中选出当前存储根可用的引用
 *
 * 旧快照可能把私有路径写在 mediaUri，而迁移后的 SAF 地址仍在 reference
 * 过滤后再选择可以保持热路径无 Provider I/O，并让调用方触发一次重绑定
 */
internal fun selectManagedPlaybackReferenceForConfiguredSaf(
    references: List<String>,
    configuredDirectoryUri: String?,
    allowPending: Boolean = false
): String? {
    return references.asSequence()
        .mapNotNull { it.trim().takeIf(String::isNotBlank) }
        .filter { reference ->
            reference.startsWith("/") ||
                reference.startsWith("file:", ignoreCase = true) ||
                reference.startsWith("content:", ignoreCase = true)
        }
        .filter { reference ->
            allowPending || !reference.contains(PENDING_AUDIO_WRITE_MARKER, ignoreCase = true)
        }
        .filter { reference ->
            isManagedPlaybackReferenceCompatibleWithConfiguredSaf(
                reference = reference,
                configuredDirectoryUri = configuredDirectoryUri
            )
        }
        .distinct()
        .firstOrNull()
}

internal fun isFormalManagedAudioReference(reference: String?): Boolean {
    val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return false
    if (
        normalized.contains(PENDING_AUDIO_WRITE_MARKER, ignoreCase = true) ||
        normalized.contains(DOWNLOAD_STAGING_DIR_NAME, ignoreCase = true)
    ) {
        return false
    }
    val fileName = ManagedDownloadStorage.normalizeManagedAudioFileName(normalized)
        ?: return false
    return !(fileName.startsWith(DOWNLOAD_STAGING_FILE_PREFIX) &&
        fileName.endsWith(DOWNLOAD_STAGING_FILE_SUFFIX))
}

private val BLOCKED_MANAGED_PLAYBACK_ARTIFACT_STATES = setOf(
    "QUEUED",
    "DOWNLOADING",
    "VERIFYING",
    "COMMITTING",
    "PENDING",
    "STAGING",
    "REPLACING",
    "ACTIVE_REPLACEMENT",
    "REPLACEMENT_PENDING",
    "RENAME_PENDING",
    "MIGRATING",
    "MIGRATION_REPLACEMENT",
    "ACTIVE",
    "IN_PROGRESS",
    "WRITING",
    "COPYING",
    "PENDING_WRITE",
    "AUDIO_PENDING",
    "UNFINALIZED",
    "INCOMPLETE",
    "PARTIAL",
    "REPAIR_REQUIRED",
    "MISSING_CONFIRMED",
    "FAILED_RETRYABLE",
    "CANCELLED"
)

private val READABLE_MANAGED_PLAYBACK_ARTIFACT_STATES = setOf(
    "CORE_COMMITTED",
    "ASSETS_ENRICHING",
    "DEGRADED_COMPLETE",
    "FINALIZED",
    "COMPLETE",
    "LEGACY_V15_FINALIZED",
    "LEGACY_UNVERIFIED"
)

/**
 * 判断已有音频是否可以凭直接可读证据播放
 *
 * 快照不完整或旧 metadata 缺失不是音频损坏证据，明确的临时状态才需要阻断
 * 非 pending 的旧状态只表示目录或元信息待修复；调用方已取得 Present
 * 证据时仍可播放，避免升级后的旧歌曲被误判为没有播放地址
 * allowLegacyPublishedAudio 只能由已取得 Present 证据的调用方传入
 */
internal fun isReadableManagedAudioPlaybackAllowed(
    audioIsPending: Boolean,
    downloadActive: Boolean,
    downloadCancelled: Boolean,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
    allowLegacyPublishedAudio: Boolean = false
): Boolean {
    if (downloadCancelled) {
        return false
    }
    val artifactState = metadata?.artifactState
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.uppercase(Locale.ROOT)
    val legacyPublishedAllowed = !audioIsPending &&
        allowLegacyPublishedAudio &&
        artifactState != "MISSING_CONFIRMED" &&
        artifactState != "CANCELLED"
    if (
        artifactState in BLOCKED_MANAGED_PLAYBACK_ARTIFACT_STATES &&
        !legacyPublishedAllowed
    ) {
        return false
    }
    if (audioIsPending) {
        // saveAudioFromTemp 只会在完整写入并校验后返回 pending 条目。
        // 只有 core committed 等持久凭据才能让它跨进程安全播放。
        return artifactState in READABLE_MANAGED_PLAYBACK_ARTIFACT_STATES ||
            metadata?.downloadFinalized == true
    }
    if (legacyPublishedAllowed) {
        // 正式文件名只会在完整写入后发布。调用方必须先取得 Present
        // 证据，因此旧版本缺少 metadata 或阶段字段不应阻断本地首播。
        return true
    }
    if (downloadActive && artifactState !in READABLE_MANAGED_PLAYBACK_ARTIFACT_STATES) {
        return false
    }
    if (artifactState in READABLE_MANAGED_PLAYBACK_ARTIFACT_STATES) {
        return true
    }
    if (metadata == null) {
        return true
    }
    if (artifactState == null) {
        // 旧版本可能只写入 downloadFinalized=false，而没有记录阶段。
        // 普通音频已通过 Present 证据确认，不应因此切到远端播放。
        return allowLegacyPublishedAudio || metadata.downloadFinalized != false
    }
    if (metadata.downloadFinalized == false) {
        return false
    }
    return true
}

internal fun selectPermittedLocalPlaybackResolution(
    rawLocalReference: String?,
    isManagedDownload: Boolean,
    verifiedManagedReference: String?,
    rawEvidence: ManagedDownloadReferenceLookup.Result,
    managedReferenceIsExplicitlyIncomplete: Boolean = false,
    missingIsTransient: Boolean = false
): LocalPlaybackReferenceResolution {
    val rawReference = rawLocalReference?.trim()?.takeIf(String::isNotBlank)
        ?: return LocalPlaybackReferenceResolution.TemporarilyUnavailable(
            ManagedDownloadReferenceLookup.Result.OutOfScope
        )
    val verifiedReference = verifiedManagedReference
        ?.trim()
        ?.takeIf(String::isNotBlank)
    if (
        isManagedDownload &&
        verifiedReference != null &&
        !managedReferenceIsExplicitlyIncomplete
    ) {
        return LocalPlaybackReferenceResolution.Playable(verifiedReference)
    }
    return when (rawEvidence) {
        ManagedDownloadReferenceLookup.Result.Present -> {
            if (isManagedDownload && managedReferenceIsExplicitlyIncomplete) {
                LocalPlaybackReferenceResolution.TemporarilyUnavailable(rawEvidence)
            } else {
                LocalPlaybackReferenceResolution.Playable(rawReference)
            }
        }
        ManagedDownloadReferenceLookup.Result.Missing -> {
            if (missingIsTransient) {
                LocalPlaybackReferenceResolution.TemporarilyUnavailable(rawEvidence)
            } else {
                LocalPlaybackReferenceResolution.Missing
            }
        }
        ManagedDownloadReferenceLookup.Result.OutOfScope,
        is ManagedDownloadReferenceLookup.Result.PermissionLost,
        is ManagedDownloadReferenceLookup.Result.ProviderFailure ->
            LocalPlaybackReferenceResolution.TemporarilyUnavailable(rawEvidence)
    }
}

internal fun selectIndexedLocalPlaybackResolution(
    verifiedReference: String?,
    indexedReference: String?,
    indexedEvidence: ManagedDownloadReferenceLookup.Result?,
    indexedReferenceIsExplicitlyIncomplete: Boolean = false,
    missingIsTransient: Boolean = false
): LocalPlaybackReferenceResolution {
    verifiedReference
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { return LocalPlaybackReferenceResolution.Playable(it) }
    if (indexedReference.isNullOrBlank()) {
        return LocalPlaybackReferenceResolution.NotIndexed
    }
    return when (indexedEvidence) {
        ManagedDownloadReferenceLookup.Result.Missing -> {
            if (missingIsTransient) {
                LocalPlaybackReferenceResolution.TemporarilyUnavailable(indexedEvidence)
            } else {
                LocalPlaybackReferenceResolution.Missing
            }
        }
        ManagedDownloadReferenceLookup.Result.Present -> {
            if (indexedReferenceIsExplicitlyIncomplete) {
                LocalPlaybackReferenceResolution.TemporarilyUnavailable(indexedEvidence)
            } else {
                LocalPlaybackReferenceResolution.Playable(indexedReference)
            }
        }
        ManagedDownloadReferenceLookup.Result.OutOfScope,
        is ManagedDownloadReferenceLookup.Result.PermissionLost,
        is ManagedDownloadReferenceLookup.Result.ProviderFailure,
        null -> LocalPlaybackReferenceResolution.TemporarilyUnavailable(
            indexedEvidence ?: ManagedDownloadReferenceLookup.Result.OutOfScope
        )
    }
}

internal fun findReboundFinalizedManagedAudio(
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
    indexedReference: String
): ManagedDownloadStorage.StoredEntry? {
    if (!snapshot.rootEntriesComplete) return null
    val indexedFileName = ManagedDownloadStorage.normalizeManagedAudioFileName(indexedReference)
        ?.takeIf(String::isNotBlank)
        ?: return null
    val reboundAudio = snapshot.audioEntries
        .asSequence()
        .filter { entry -> entry.name == indexedFileName }
        .take(2)
        .toList()
        .singleOrNull()
        ?: return null
    return reboundAudio.takeIf { audio ->
        canExposeManagedDownloadForPlayback(snapshot, audio)
    }
}

internal fun resolveVisibleDownloadFileName(
    targetFileName: String?,
    fallbackTempFileName: String
): String {
    return targetFileName
        ?.takeIf(String::isNotBlank)
        ?: fallbackTempFileName
}

internal fun canExposeManagedDownloadForPlayback(
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
    audio: ManagedDownloadStorage.StoredEntry?
): Boolean {
    return isFinalizedDownloadedAudioEntry(
        rootEntriesComplete = snapshot?.rootEntriesComplete == true,
        isPendingAudioWrite = audio?.isPendingAudioWrite == true,
        metadata = audio?.let { entry ->
            ManagedDownloadStorage.metadataForAudioEntry(snapshot, entry)
        }
    )
}

internal fun coreCommittedSeedMetadataJson(rawMetadata: String): String? {
    return runCatching {
        JSONObject(rawMetadata)
            .put("downloadFinalized", false)
            .put("artifactState", "CORE_COMMITTED")
            .toString()
    }.getOrNull()
}

