package moe.ouom.neriplayer.core.download.cleanup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isDurableCoreArtifactState
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.isUnfinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import java.util.Locale

internal data class ManagedDownloadParsedMetadataEntry(
    val entry: ManagedDownloadStorage.StoredEntry,
    val metadata: ManagedDownloadStorage.DownloadedAudioMetadata
)

internal object ManagedDownloadUnfinalizedCleanupPlanner {
    fun planReferencesToDelete(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataEntries: List<ManagedDownloadParsedMetadataEntry>,
        managedSidecarReferences: Set<String>
    ): Set<String> {
        val unfinalizedMetadataEntries = parsedMetadataEntries
            .filter { parsed -> isUnfinalizedDownloadedMetadata(parsed.metadata) }
        if (unfinalizedMetadataEntries.isEmpty()) {
            return emptySet()
        }

        val audioEntriesByLogicalName = rootEntries
            .filter { entry ->
                entry.extension in audioExtensions || entry.isPendingAudioWrite
            }
            .groupBy(ManagedDownloadStorage.StoredEntry::logicalName)
        val protectedReferences = protectedSidecarReferences(
            parsedMetadataEntries = parsedMetadataEntries,
            managedSidecarReferences = managedSidecarReferences
        )

        return linkedSetOf<String>().apply {
            unfinalizedMetadataEntries.forEach { parsed ->
                // core 已跨过提交边界时，downloadFinalized 可能仍为 false
                // 例如标签收尾尚未完成，此时不能把未知大小的 SAF 条目当空文件删除
                if (isDurableCoreMetadata(parsed.metadata)) {
                    return@forEach
                }
                val audioEntries = ManagedDownloadTreeNaming.metadataAudioName(parsed.entry.name)
                    ?.let(audioEntriesByLogicalName::get)
                    .orEmpty()
                // SAF 可能不提供大小，未知大小不能当成空文件，留到下一轮确认
                if (audioEntries.any { audio ->
                        !audio.sizeKnown || audio.sizeBytes > 0L
                    }
                ) {
                    return@forEach
                }

                add(parsed.entry.reference)
                audioEntries.mapTo(this, ManagedDownloadStorage.StoredEntry::reference)
                sidecarReferences(parsed.metadata, managedSidecarReferences)
                    .filterNot(protectedReferences::contains)
                    .forEach(::add)
            }

            // 冲突编号时，下载流程会先以请求名写 pending metadata，随后再以
            // 实际保留名提交音频。只有同一 stableKey 和 operationId 已经有
            // 唯一的正式音频凭据时，才能确认请求名 metadata 是孤儿并清理它
            collisionOrphanPendingMetadataReferences(
                rootEntries = rootEntries,
                parsedMetadataEntries = parsedMetadataEntries,
                audioEntriesByLogicalName = audioEntriesByLogicalName
            ).forEach(::add)
        }
    }

    private fun isDurableCoreMetadata(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata
    ): Boolean {
        if (metadata.downloadFinalized == true) {
            return true
        }
        return isDurableCoreArtifactState(
            metadata.artifactState
                ?.trim()
                ?.uppercase(Locale.ROOT)
        )
    }

    private fun collisionOrphanPendingMetadataReferences(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        parsedMetadataEntries: List<ManagedDownloadParsedMetadataEntry>,
        audioEntriesByLogicalName: Map<String, List<ManagedDownloadStorage.StoredEntry>>
    ): Set<String> {
        val metadataWithAudioName = parsedMetadataEntries.mapNotNull { parsed ->
            ManagedDownloadTreeNaming.metadataAudioName(parsed.entry.name)?.let { audioName ->
                ParsedMetadataWithAudioName(
                    audioName = audioName,
                    parsed = parsed
                )
            }
        }
        val finalizedByIdentity = metadataWithAudioName
            .mapNotNull { candidate ->
                val metadata = candidate.parsed.metadata
                val stableKey = metadata.stableKey
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val operationId = metadata.operationId
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                if (!isFinalizedDownloadedMetadata(metadata)) {
                    return@mapNotNull null
                }
                val finalizedAudio = audioEntriesByLogicalName[candidate.audioName]
                    .orEmpty()
                    .firstOrNull { audio ->
                        !audio.isPendingAudioWrite &&
                            audio.sizeKnown &&
                            audio.sizeBytes > 0L &&
                            metadata.audioFileName?.trim() == audio.logicalName
                    }
                    ?: return@mapNotNull null
                CollisionIdentity(stableKey, operationId) to
                    FinalizedCollisionCandidate(audioName = candidate.audioName)
            }
            .groupBy({ (identity, _) -> identity }, { (_, candidate) -> candidate })

        if (finalizedByIdentity.isEmpty()) {
            return emptySet()
        }
        val pendingAudioLogicalNames = rootEntries
            .asSequence()
            .filterNot(ManagedDownloadStorage.StoredEntry::isDirectory)
            .filter(ManagedDownloadStorage.StoredEntry::isPendingAudioWrite)
            .map(ManagedDownloadStorage.StoredEntry::logicalName)
            .toSet()
        return metadataWithAudioName
            .asSequence()
            .filter { candidate ->
                ManagedDownloadTreeNaming.isPendingMetadataName(
                    actualName = candidate.parsed.entry.name,
                    audioName = candidate.audioName
                )
            }
            .filterNot { candidate -> isDurableCoreMetadata(candidate.parsed.metadata) }
            .filter { candidate -> candidate.audioName !in pendingAudioLogicalNames }
            .mapNotNull { candidate ->
                val metadata = candidate.parsed.metadata
                val stableKey = metadata.stableKey
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val operationId = metadata.operationId
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                if (metadata.audioFileName?.trim() != candidate.audioName) {
                    return@mapNotNull null
                }
                val counterparts = finalizedByIdentity[CollisionIdentity(stableKey, operationId)]
                    .orEmpty()
                    .filter { counterpart -> counterpart.audioName != candidate.audioName }
                if (counterparts.size != 1) {
                    return@mapNotNull null
                }
                candidate.parsed.entry.reference
            }
            .toSet()
    }

    private data class ParsedMetadataWithAudioName(
        val audioName: String,
        val parsed: ManagedDownloadParsedMetadataEntry
    )

    private data class CollisionIdentity(
        val stableKey: String,
        val operationId: String
    )

    private data class FinalizedCollisionCandidate(
        val audioName: String
    )

    private fun protectedSidecarReferences(
        parsedMetadataEntries: List<ManagedDownloadParsedMetadataEntry>,
        managedSidecarReferences: Set<String>
    ): Set<String> {
        return parsedMetadataEntries
            .asSequence()
            .filter { parsed ->
                !isUnfinalizedDownloadedMetadata(parsed.metadata) ||
                    isDurableCoreMetadata(parsed.metadata)
            }
            .flatMap { parsed -> sidecarReferences(parsed.metadata, managedSidecarReferences).asSequence() }
            .toSet()
    }

    private fun sidecarReferences(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata,
        managedSidecarReferences: Set<String>
    ): List<String> {
        return listOf(
            metadata.coverPath,
            metadata.lyricPath,
            metadata.translatedLyricPath,
            metadata.romanizedLyricPath
        )
            .mapNotNull { reference -> reference?.takeIf(String::isNotBlank) }
            .filter(managedSidecarReferences::contains)
    }
}
