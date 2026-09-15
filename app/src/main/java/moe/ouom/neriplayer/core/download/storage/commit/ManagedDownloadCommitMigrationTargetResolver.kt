package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.LinkedHashMap
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationTargetResolver
import moe.ouom.neriplayer.core.download.storage.migration.StoredWriteResult
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger

internal class ManagedDownloadCommitMigrationTargetResolver(
    private val treeChildRegistry: ManagedDownloadTreeChildRegistry,
    private val tag: String
) {
    private val fileTargetCacheLock = Any()
    private val fileTargetCaches = LinkedHashMap<String, FileTargetCache>(
        FILE_TARGET_CACHE_LIMIT,
        0.75f,
        true
    )
    private val treeTargetCacheLock = Any()
    private val treeTargetCaches = LinkedHashMap<String, TreeTargetCache>(
        TREE_TARGET_CACHE_LIMIT,
        0.75f,
        true
    )

    fun resolveFileTarget(
        parent: File,
        displayName: String,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry? = null
    ): StoredWriteResult {
        val targetCache = fileTargetCache(
            parent = parent,
            targetNames = targetNames,
            readExistingEntry = { file ->
                file.takeIf(File::isFile)?.let(ManagedDownloadStoredEntryMapper::fromFile)
            }
        )
        return ManagedDownloadMigrationTargetResolver.resolveFileTarget(
            parent = parent,
            displayName = displayName,
            sourceEntry = sourceEntry,
            targetNames = targetNames,
            targetEntry = targetEntry,
            readExistingEntry = { existing ->
                existing.takeIf(File::isFile)?.let(ManagedDownloadStoredEntryMapper::fromFile)
            },
            reserveName = { reservedName -> treeChildRegistry.reserveUniqueFileChildName(parent, reservedName) },
            onReuseMetadata = { existingEntry ->
                NPLogger.d(tag, "迁移复用目标 metadata: ${existingEntry.name}")
            },
            onReuseFile = { existingEntry ->
                NPLogger.d(tag, "迁移复用目标文件: ${existingEntry.name}")
            },
            preloadedMetadataCandidates = targetCache.metadataCandidates(sourceEntry),
            preloadedExistingEntry = targetCache.entriesByExactName[displayName]
        )
    }

    fun resolveTreeTarget(
        context: Context,
        parent: DocumentFile,
        displayName: String,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry? = null
    ): StoredWriteResult {
        if (
            targetEntry == null &&
                displayName !in targetNames
        ) {
            return ManagedDownloadMigrationTargetResolver.resolveTreeTarget(
                displayName = displayName,
                sourceEntry = sourceEntry,
                targetNames = targetNames,
                targetEntry = null,
                existingChildEntry = null,
                reserveName = { plannedName -> plannedName },
                onReuseMetadata = {},
                onReuseFile = {}
            )
        }
        val metadataAudioName = ManagedDownloadTreeNaming.metadataAudioName(displayName)
        // targetIndex 已经在迁移开始时完成一次完整枚举, 优先复用其中的真实文档
        // 引用, 避免每个文件再次查询 SAF 子项。最终布局校验仍会发现外部变更。
        val existingChildEntry = targetEntry
            ?: peekExistingChildEntry(parent, displayName, metadataAudioName)
            ?: if (displayName in targetNames) {
                treeChildRegistry.treeChildrenForWrite(context, parent)
                    .children
                    .asSequence()
                    .filterNot(QueriedTreeChild::isDirectory)
                    .filter { child ->
                        child.name.equals(displayName, ignoreCase = true) ||
                            metadataAudioName?.let { audioName ->
                                ManagedDownloadTreeNaming.metadataAudioName(child.name)
                                    ?.equals(audioName, ignoreCase = true) == true
                            } == true
                    }
                    .minWithOrNull(
                        compareBy<QueriedTreeChild>(
                            { if (it.name.equals(displayName, ignoreCase = true)) 0 else 1 },
                            {
                                metadataAudioName?.let { audioName ->
                                    ManagedDownloadTreeNaming.metadataNameOrdinal(it.name, audioName)
                                        ?: Int.MAX_VALUE
                                } ?: Int.MAX_VALUE
                            },
                            QueriedTreeChild::name
                        )
                    )
                    ?.let(ManagedDownloadStoredEntryMapper::fromTreeChild)
            } else {
                null
            }
        return ManagedDownloadMigrationTargetResolver.resolveTreeTarget(
            displayName = displayName,
            sourceEntry = sourceEntry,
            targetNames = targetNames,
            targetEntry = targetEntry,
            existingChildEntry = existingChildEntry,
            reserveName = { reservedName -> treeChildRegistry.reserveUniqueTreeChildName(context, parent, reservedName) },
            onReuseMetadata = { existingEntry ->
                NPLogger.d(tag, "迁移复用目标 SAF metadata: ${existingEntry.name}")
            },
            onReuseFile = { existingEntry ->
                NPLogger.d(tag, "迁移复用目标 SAF 文件: ${existingEntry.name}")
            }
        )
    }

    private fun peekExistingChildEntry(
        parent: DocumentFile,
        displayName: String,
        metadataAudioName: String?
    ): ManagedDownloadStorage.StoredEntry? {
        val children = treeChildRegistry.peekTreeChildren(parent) ?: return null
        return treeTargetCache(parent, children).find(
            displayName = displayName,
            metadataAudioName = metadataAudioName
        )
    }

    private fun treeTargetCache(
        parent: DocumentFile,
        children: Collection<QueriedTreeChild>
    ): TreeTargetCache {
        val cacheKey = parent.uri.toString()
        synchronized(treeTargetCacheLock) {
            treeTargetCaches[cacheKey]
                ?.takeIf { cache -> cache.children === children }
                ?.let { cache -> return cache }

            val entries = children.asSequence()
                .filterNot(QueriedTreeChild::isDirectory)
                .map(ManagedDownloadStoredEntryMapper::fromTreeChild)
                .toList()
            val cache = TreeTargetCache(
                children = children,
                entriesByName = entries.groupBy {
                    ManagedDownloadStorageNaming.canonicalNameKey(it.name)
                },
                entriesByMetadataAudioName = entries
                    .mapNotNull { entry ->
                        ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                            ?.let { audioName ->
                                ManagedDownloadStorageNaming.canonicalNameKey(audioName) to entry
                            }
                    }
                    .groupBy({ (audioName, _) -> audioName }, { (_, entry) -> entry })
            )
            treeTargetCaches[cacheKey] = cache
            while (treeTargetCaches.size > TREE_TARGET_CACHE_LIMIT) {
                val eldestKey = treeTargetCaches.entries.firstOrNull()?.key ?: break
                treeTargetCaches.remove(eldestKey)
            }
            return cache
        }
    }

    private fun fileTargetCache(
        parent: File,
        targetNames: Set<String>,
        readExistingEntry: (File) -> ManagedDownloadStorage.StoredEntry?
    ): FileTargetCache {
        val cacheKey = runCatching { parent.canonicalPath }
            .getOrElse { parent.absolutePath }
        synchronized(fileTargetCacheLock) {
            fileTargetCaches[cacheKey]
                ?.takeIf { cache -> cache.targetNames === targetNames }
                ?.let { cache -> return cache }

            val entries = targetNames.asSequence()
                .mapNotNull { name -> readExistingEntry(File(parent, name)) }
                .toList()
            val entriesByExactName = entries.associateBy(ManagedDownloadStorage.StoredEntry::name)
            val metadataCandidates = buildMap<String, MutableList<ManagedDownloadStorage.StoredEntry>> {
                entries.forEach { entry ->
                    ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                        ?.let { audioName ->
                            getOrPut(ManagedDownloadStorageNaming.canonicalNameKey(audioName)) {
                                mutableListOf()
                            }.add(entry)
                        }
                }
            }.mapValues { (_, candidates) -> candidates.toList() }
            val cache = FileTargetCache(
                targetNames = targetNames,
                entriesByExactName = entriesByExactName,
                metadataCandidatesByAudioName = metadataCandidates
            )
            fileTargetCaches[cacheKey] = cache
            while (fileTargetCaches.size > FILE_TARGET_CACHE_LIMIT) {
                val eldestKey = fileTargetCaches.entries.firstOrNull()?.key ?: break
                fileTargetCaches.remove(eldestKey)
            }
            return cache
        }
    }

    private data class FileTargetCache(
        val targetNames: Set<String>,
        val entriesByExactName: Map<String, ManagedDownloadStorage.StoredEntry>,
        val metadataCandidatesByAudioName:
            Map<String, List<ManagedDownloadStorage.StoredEntry>>
    ) {
        fun metadataCandidates(
            sourceEntry: ManagedDownloadStorage.StoredEntry
        ): Collection<ManagedDownloadStorage.StoredEntry> {
            val audioName = ManagedDownloadTreeNaming.metadataAudioName(sourceEntry.name)
                ?: return emptyList()
            return metadataCandidatesByAudioName[
                ManagedDownloadStorageNaming.canonicalNameKey(audioName)
            ].orEmpty()
        }
    }

    private data class TreeTargetCache(
        val children: Collection<QueriedTreeChild>,
        val entriesByName: Map<String, List<ManagedDownloadStorage.StoredEntry>>,
        val entriesByMetadataAudioName:
            Map<String, List<ManagedDownloadStorage.StoredEntry>>
    ) {
        fun find(
            displayName: String,
            metadataAudioName: String?
        ): ManagedDownloadStorage.StoredEntry? {
            val byName = entriesByName[
                ManagedDownloadStorageNaming.canonicalNameKey(displayName)
            ].orEmpty()
            val byMetadata = metadataAudioName?.let { audioName ->
                entriesByMetadataAudioName[
                    ManagedDownloadStorageNaming.canonicalNameKey(audioName)
                ].orEmpty()
            }.orEmpty()
            return (byName.asSequence() + byMetadata.asSequence())
                .distinctBy { entry -> entry.name to entry.reference }
                .filter { entry ->
                    entry.name.equals(displayName, ignoreCase = true) ||
                        metadataAudioName?.let { audioName ->
                            ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                                ?.equals(audioName, ignoreCase = true) == true
                        } == true
                }
                .minWithOrNull(
                    compareBy<ManagedDownloadStorage.StoredEntry>(
                        { entry -> if (entry.name.equals(displayName, ignoreCase = true)) 0 else 1 },
                        { entry ->
                            metadataAudioName?.let { audioName ->
                                ManagedDownloadTreeNaming.metadataNameOrdinal(entry.name, audioName)
                                    ?: Int.MAX_VALUE
                            } ?: Int.MAX_VALUE
                        },
                        ManagedDownloadStorage.StoredEntry::name
                    )
                )
        }
    }

    private companion object {
        private const val FILE_TARGET_CACHE_LIMIT = 8
        private const val TREE_TARGET_CACHE_LIMIT = 8
    }
}
