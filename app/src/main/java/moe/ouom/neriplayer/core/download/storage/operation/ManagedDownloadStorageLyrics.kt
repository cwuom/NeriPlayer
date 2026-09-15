package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadLibrarySnapshot
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedLyricsBundle
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.LyricKind
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import java.io.File
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle


internal fun ManagedDownloadStorage.resolveSnapshotForIndexedLookup(context: Context): DownloadLibrarySnapshot? {
    snapshotCacheStore.peekSnapshot()?.let { return it }
    if (ensureSnapshotCacheReady(context)) {
        snapshotCacheStore.peekSnapshot()?.let { return it }
    }
    return null
}

internal fun ManagedDownloadStorage.hasCompleteLyricsSidecars(bundle: DownloadedLyricsBundle): Boolean {
    return bundle.hasOriginalSidecar &&
        bundle.hasTranslatedSidecar &&
        bundle.hasRomanizedSidecar
}

internal fun ManagedDownloadStorage.mergeLyricsBundles(
    preferred: DownloadedLyricsBundle,
    fallback: DownloadedLyricsBundle
): DownloadedLyricsBundle {
    fun selectValue(
        preferredValue: String?,
        preferredHasSidecar: Boolean,
        fallbackValue: String?,
        fallbackHasSidecar: Boolean
    ): String? {
        return when {
            preferredHasSidecar -> preferredValue
            fallbackHasSidecar -> fallbackValue
            else -> preferredValue ?: fallbackValue
        }
    }

    return DownloadedLyricsBundle(
        lyric = selectValue(
            preferredValue = preferred.lyric,
            preferredHasSidecar = preferred.hasOriginalSidecar,
            fallbackValue = fallback.lyric,
            fallbackHasSidecar = fallback.hasOriginalSidecar
        ),
        translatedLyric = selectValue(
            preferredValue = preferred.translatedLyric,
            preferredHasSidecar = preferred.hasTranslatedSidecar,
            fallbackValue = fallback.translatedLyric,
            fallbackHasSidecar = fallback.hasTranslatedSidecar
        ),
        romanizedLyric = selectValue(
            preferredValue = preferred.romanizedLyric,
            preferredHasSidecar = preferred.hasRomanizedSidecar,
            fallbackValue = fallback.romanizedLyric,
            fallbackHasSidecar = fallback.hasRomanizedSidecar
        ),
        hasOriginalSidecar = preferred.hasOriginalSidecar || fallback.hasOriginalSidecar,
        hasTranslatedSidecar =
            preferred.hasTranslatedSidecar || fallback.hasTranslatedSidecar,
        hasRomanizedSidecar = preferred.hasRomanizedSidecar || fallback.hasRomanizedSidecar
    )
}

internal fun ManagedDownloadStorage.hasManagedLocalReference(song: SongItem): Boolean {
    return song.localFilePath?.isNotBlank() == true ||
        song.mediaUri?.startsWith("/", ignoreCase = false) == true ||
        song.mediaUri?.startsWith("file:", ignoreCase = true) == true ||
        song.mediaUri?.startsWith("content:", ignoreCase = true) == true
}

internal fun ManagedDownloadStorage.readLyricsBundleFastInternal(
    context: Context,
    song: SongItem,
    allowColdSafProbe: Boolean
): DownloadedLyricsBundle {
    // 原文、翻译和罗马字必须共用一次快照解析, 避免首屏重复查询下载目录
    val snapshot = snapshotCacheStore.cachedSnapshot(
        context = context,
        restorePersisted = false
    )
    if (snapshot == null) {
        scheduleSnapshotWarmup(context)
        val directSidecar = readLyricsBundleFromDirectSongPathFast(context, song)
        val coldSafSidecar = if (
            !allowColdSafProbe ||
            directSidecar.hasOriginalSidecar ||
                directSidecar.hasTranslatedSidecar ||
                directSidecar.hasRomanizedSidecar ||
                !song.mediaUri.orEmpty().startsWith("content://", ignoreCase = true)
        ) {
            DownloadedLyricsBundle(null, null, null)
        } else {
            // 首次从 SAF 树恢复歌曲时没有可用索引, 仅查询当前歌曲的目录并缓存结果
            readLyricsBundleFromManagedRootFast(
                context = context,
                song = song
            )
        }
        return mergeLyricsBundles(
            preferred = mergeLyricsBundles(
                preferred = directSidecar,
                fallback = coldSafSidecar
            ),
            fallback = DownloadedLyricsBundle(
                lyric = song.matchedLyric ?: song.originalLyric,
                translatedLyric = song.matchedTranslatedLyric
                    ?: song.originalTranslatedLyric,
                romanizedLyric = song.matchedRomanizedLyric
                    ?: song.originalRomanizedLyric
            )
        )
    }
    val indexed = resolveDownloadedLyricsBundle(
        context = context,
        song = song,
        snapshot = snapshot,
        readText = { reference -> readTextInternal(context, reference) },
        exists = { lookupContext, reference ->
                inspectStorageReference(lookupContext, reference) ==
                ManagedDownloadReferenceIo.AccessResult.Accessible
        }
    )
    val indexedBundle = DownloadedLyricsBundle(
        lyric = if (indexed.hasOriginalSidecar) {
            indexed.lyric
        } else {
            indexed.lyric ?: song.matchedLyric ?: song.originalLyric
        },
        translatedLyric = if (indexed.hasTranslatedSidecar) {
            indexed.translatedLyric
        } else {
            indexed.translatedLyric
                ?: song.matchedTranslatedLyric
                ?: song.originalTranslatedLyric
        },
        romanizedLyric = if (indexed.hasRomanizedSidecar) {
            indexed.romanizedLyric
        } else {
            indexed.romanizedLyric
                ?: song.matchedRomanizedLyric
                ?: song.originalRomanizedLyric
        },
        hasOriginalSidecar = indexed.hasOriginalSidecar,
        hasTranslatedSidecar = indexed.hasTranslatedSidecar,
        hasRomanizedSidecar = indexed.hasRomanizedSidecar
    )
    if (
        !allowColdSafProbe ||
            hasCompleteLyricsSidecars(indexedBundle)
    ) {
        return indexedBundle
    }

    // 编辑器需要识别用户刚刚新建或删除的 Lyrics 文件, 播放首屏不会走这条冷探测
    val directSidecar = readLyricsBundleFromManagedRootFast(
        context = context,
        song = song
    )
    return mergeLyricsBundles(
        preferred = directSidecar,
        fallback = indexedBundle
    )
}

internal fun ManagedDownloadStorage.readLyricsBundleFromDirectSongPathFast(
    context: Context,
    song: SongItem
): DownloadedLyricsBundle {
    val sourcePath = listOfNotNull(song.localFilePath, song.mediaUri)
        .asSequence()
        .mapNotNull { reference ->
            when {
                reference.startsWith("/") -> reference
                reference.startsWith("file:", ignoreCase = true) -> {
                    runCatching { reference.toUri().path }.getOrNull()
                }
                else -> null
            }
        }
        .firstOrNull()
        ?: return DownloadedLyricsBundle(null, null, null)
    val audioFile = File(sourcePath)
    val parent = audioFile.parentFile ?: return DownloadedLyricsBundle(null, null, null)
    val lyricDirectories = buildList {
        add(File(parent, LYRIC_SUBDIRECTORY))
        add(parent)
        if (isPathInside(sourcePath, LEGACY_DOWNLOAD_ROOT_PATH)) {
            add(File(LEGACY_DOWNLOAD_ROOT_PATH, LYRIC_SUBDIRECTORY))
        }
    }.distinctBy(File::getAbsolutePath)
    val candidateBaseNames = buildManagedLyricBaseNames(
        song = song,
        audioName = audioFile.name
    )
    val entries = readLyricsFromNamedFiles(
        context = context,
        directories = lyricDirectories,
        candidateBaseNames = candidateBaseNames,
        alreadyRead = emptySet()
    )
    return DownloadedLyricsBundle(
        lyric = entries[LyricKind.ORIGINAL]?.first,
        translatedLyric = entries[LyricKind.TRANSLATED]?.first,
        romanizedLyric = entries[LyricKind.ROMANIZED]?.first,
        hasOriginalSidecar = entries[LyricKind.ORIGINAL]?.second == true,
        hasTranslatedSidecar = entries[LyricKind.TRANSLATED]?.second == true,
        hasRomanizedSidecar = entries[LyricKind.ROMANIZED]?.second == true
    )
}

internal fun ManagedDownloadStorage.readLyricsBundleFromManagedRootFast(
    context: Context,
    song: SongItem
): DownloadedLyricsBundle {
    val startedAtNs = System.nanoTime()
    val configuredRoot = try {
        resolveRootBlocking(context)
    } catch (error: SecurityException) {
        throw error
    } catch (error: IllegalStateException) {
        // 配置了 SAF 根目录时不能静默回退到其他根目录
        if (error is ManagedDownloadRootUnavailableException) {
            throw error
        }
        NPLogger.d(
            "ManagedDownloadLyricsPerf",
            "configured lyric root unavailable: ${error.message}"
        )
        null
    }
    val sourceTreeRoot = resolveSourceTreeRootFast(context, song)
    val root = when {
        sourceTreeRoot != null && (
            configuredRoot !is RootHandle.TreeRoot ||
                configuredRoot.tree.uri != sourceTreeRoot.tree.uri
            ) -> {
            NPLogger.d(
                "ManagedDownloadLyricsPerf",
                "首屏歌词改用歌曲自身 SAF 根: source=${sourceTreeRoot.tree.uri}, " +
                    "configured=${configuredRoot?.javaClass?.simpleName}"
            )
            sourceTreeRoot
        }
        configuredRoot != null -> configuredRoot
        else -> sourceTreeRoot
    }
        ?: return DownloadedLyricsBundle(null, null, null)
    val audioName = resolveManagedAudioDisplayName(context, song)
    val candidateBaseNames = buildManagedLyricBaseNames(
        song = song,
        audioName = audioName
    )
    val metadata = readDownloadedMetadataFast(
        context = context,
        root = root,
        song = song,
        audioName = audioName
    )
    val referenced = resolveLyricsBundleFromReferences(
        metadata = metadata,
        // 当前根 sidecar 由 directFiles 精确匹配，旧 URI 不能作为 provider 访问证据
        originalReference = null,
        translatedReference = null,
        romanizedReference = null,
        readText = { reference ->
            try {
                readTextInternal(context, reference)
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                NPLogger.d(
                    TAG,
                    "首屏歌词引用读取失败: reference=$reference, " +
                        "error=${error.message}"
                )
                null
            }
        }
    )
    val values = buildMap<LyricKind, Pair<String?, Boolean>> {
        if (referenced.hasOriginalSidecar) {
            put(LyricKind.ORIGINAL, referenced.lyric to true)
        }
        if (referenced.hasTranslatedSidecar) {
            put(LyricKind.TRANSLATED, referenced.translatedLyric to true)
        }
        if (referenced.hasRomanizedSidecar) {
            put(LyricKind.ROMANIZED, referenced.romanizedLyric to true)
        }
    }.toMutableMap()

    val directFiles = when (root) {
        is RootHandle.FileRoot -> readLyricsFromFileRootFast(
            context = context,
            root = root,
            song = song,
            candidateBaseNames = candidateBaseNames,
            alreadyRead = emptySet()
        )
        is RootHandle.TreeRoot -> readLyricsFromCachedTreeFast(
            context = context,
            root = root,
            song = song,
            candidateBaseNames = candidateBaseNames,
            alreadyRead = emptySet()
        )
    }
    // Lyrics 文件优先, 即使 npmeta.json 中仍是旧引用或嵌入歌词
    directFiles.forEach { (kind, value) -> values[kind] = value }

    val result = DownloadedLyricsBundle(
        lyric = values[LyricKind.ORIGINAL]?.first ?: referenced.lyric,
        translatedLyric = values[LyricKind.TRANSLATED]?.first
            ?: referenced.translatedLyric,
        romanizedLyric = values[LyricKind.ROMANIZED]?.first
            ?: referenced.romanizedLyric,
        hasOriginalSidecar = values[LyricKind.ORIGINAL]?.second == true,
        hasTranslatedSidecar = values[LyricKind.TRANSLATED]?.second == true,
        hasRomanizedSidecar = values[LyricKind.ROMANIZED]?.second == true
    )
    val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
    if (elapsedMs >= FAST_LYRICS_SLOW_LOG_MS) {
        NPLogger.d(
            "ManagedDownloadLyricsPerf",
            "fast lyric read elapsed=${elapsedMs}ms, root=${root::class.simpleName}, " +
                "metadata=${metadata != null}, original=${result.hasOriginalSidecar}, " +
                "translated=${result.hasTranslatedSidecar}, " +
                "romanized=${result.hasRomanizedSidecar}, song=${song.name}"
        )
    }
    if (
        root is RootHandle.TreeRoot &&
            metadata == null &&
            !result.hasOriginalSidecar &&
            !result.hasTranslatedSidecar &&
            !result.hasRomanizedSidecar
    ) {
        NPLogger.d(
            "ManagedDownloadLyricsPerf",
            "fast lyric miss root=${root.tree.uri}, audio=$audioName, " +
                "candidates=${candidateBaseNames.size}, source=${song.mediaUri}"
        )
        // 冷启动没有元信息引用时只显示已有字段, 完整目录索引放到后台
        scheduleSnapshotWarmup(context)
    }
    return result
}

internal fun ManagedDownloadStorage.resolveSourceTreeRootFast(
    context: Context,
    song: SongItem
): RootHandle.TreeRoot? {
    val references = listOfNotNull(song.mediaUri, song.localFilePath)
    val directTreeUri = references.asSequence()
        .mapNotNull(::managedDownloadTreeUri)
        .firstOrNull()
    val treeUri = directTreeUri ?: inferLegacyDownloadTreeUri(context, references)
        ?: return null
    val tree = try {
        DocumentFile.fromTreeUri(context, treeUri)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    } ?: return null
    return tree.takeIf { it.isDirectory }?.let(RootHandle::TreeRoot)
}

internal fun ManagedDownloadStorage.inferLegacyDownloadTreeUri(
    context: Context,
    references: Collection<String>
): Uri? {
    val hasLegacyPath = references.any(::isLegacyDownloadReference)
    val hasLegacyMediaStoreReference = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val mediaStoreReferences = references.asSequence()
            .filter { it.startsWith("content://", ignoreCase = true) }
            .mapNotNull { runCatching { it.toUri() }.getOrNull() }
            .filter { it.authority.equals("media", ignoreCase = true) }
            .toList()
        mediaStoreReferences.isNotEmpty() && isMediaStoreSongWithinManagedRoot(
            context = context,
            songReferences = mediaStoreReferences,
            treeDocumentId = null
        )
    } else {
        false
    }
    if (!hasLegacyPath && !hasLegacyMediaStoreReference) {
        return null
    }
    return context.contentResolver.persistedUriPermissions
        .asSequence()
        .filter { permission ->
            permission.isReadPermission &&
                DocumentsContract.isTreeUri(permission.uri) &&
                permission.uri.authority == "com.android.externalstorage.documents"
        }
        .map { permission -> permission.uri }
        .firstOrNull { uri ->
            runCatching { DocumentsContract.getTreeDocumentId(uri) }
                .getOrNull() == "primary:neriplayer-download"
        }
}

internal fun ManagedDownloadStorage.isLegacyDownloadReference(reference: String): Boolean {
    val raw = reference.trim()
    val path = when {
        raw.startsWith("/") -> raw
        raw.startsWith("file:", ignoreCase = true) -> {
            runCatching { raw.toUri().path }.getOrNull()
        }
        else -> null
    } ?: return false
    return isPathInside(path, LEGACY_DOWNLOAD_ROOT_PATH)
}

internal fun ManagedDownloadStorage.readDownloadedMetadataFast(
    context: Context,
    root: RootHandle,
    song: SongItem,
    audioName: String? = null
): DownloadedAudioMetadata? {
    val resolvedAudioName = audioName
        ?: resolveManagedAudioDisplayName(context, song)
        ?: return null
    val metadataName = "$resolvedAudioName$METADATA_SUFFIX"
    val entry = when (root) {
        is RootHandle.FileRoot -> {
            val candidates = buildList {
                add(File(root.dir, metadataName))
                root.dir.listFiles()
                    ?.filter { file ->
                        ManagedDownloadTreeNaming.metadataNameOrdinal(
                            actualName = file.name,
                            audioName = resolvedAudioName
                        ) != null
                    }
                    ?.sortedWith(
                        compareBy<File>(
                            { ManagedDownloadTreeNaming.metadataNameOrdinal(it.name, resolvedAudioName) ?: Int.MAX_VALUE },
                            { it.name }
                        )
                    )
                    ?.forEach(::add)
                song.localFilePath
                    ?.takeIf { it.startsWith("/") }
                    ?.let(::File)
                ?.parentFile
                    ?.let { parent ->
                        add(File(parent, metadataName))
                        parent.listFiles()
                            ?.filter { file ->
                                ManagedDownloadTreeNaming.metadataNameOrdinal(file.name, resolvedAudioName) != null
                            }
                            ?.sortedWith(
                                compareBy<File>(
                                    { ManagedDownloadTreeNaming.metadataNameOrdinal(it.name, resolvedAudioName) ?: Int.MAX_VALUE },
                                    { it.name }
                                )
                            )
                            ?.forEach(::add)
                    }
            }
            candidates.firstOrNull { it.isFile }
                ?.toStoredEntry()
        }
        is RootHandle.TreeRoot -> {
            findTreeSiblingByNameFast(
                context = context,
                root = root,
                song = song,
                childName = metadataName,
                nameMatches = { actualName ->
                    ManagedDownloadTreeNaming.metadataNameOrdinal(actualName, resolvedAudioName) != null
                }
            )
                ?: treeChildRegistry.peekTreeChild(root.tree, metadataName)
                    ?.toStoredEntry()
        }
    } ?: return null
    return readTextInternal(context, entry.reference)
        ?.let(::parseDownloadedAudioMetadataJson)
}

internal fun ManagedDownloadStorage.findTreeSiblingByNameFast(
    context: Context,
    root: RootHandle.TreeRoot,
    song: SongItem,
    childName: String,
    nameMatches: (String) -> Boolean = { actualName ->
        actualName.equals(childName, ignoreCase = true)
    }
): StoredEntry? {
    val parent = findTreeParentDocumentFast(context, root, song) ?: return null
    val children = try {
        treeChildRegistry.cachedTreeChildren(
            context = context,
            parent = parent,
            maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
        )
    } catch (error: SecurityException) {
        throw error
    } catch (error: Exception) {
        NPLogger.d(
            "ManagedDownloadLyricsPerf",
            "fast lyric sibling query failed parent=${parent.uri}, " +
                "error=${error.message}"
        )
        emptyList()
    }
    return children
        .firstOrNull { child -> nameMatches(child.name) }
        ?.toStoredEntry()
}

internal fun ManagedDownloadStorage.findTreeParentDocumentFast(
    context: Context,
    root: RootHandle.TreeRoot,
    song: SongItem
): DocumentFile? {
    val sourceUri = listOfNotNull(song.mediaUri, song.localFilePath)
        .firstOrNull { it.startsWith("content://", ignoreCase = true) }
        ?.let { runCatching { it.toUri() }.getOrNull() }
        ?: return null
    val path = try {
        DocumentsContract.findDocumentPath(context.contentResolver, sourceUri)?.path
    } catch (error: SecurityException) {
        throw error
    } catch (error: Exception) {
        NPLogger.d(
            "ManagedDownloadLyricsPerf",
            "fast lyric parent path failed source=$sourceUri, error=${error.message}"
        )
        null
    } ?: return null
    val treeDocumentId = try {
        DocumentsContract.getTreeDocumentId(root.tree.uri)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    }
    if (treeDocumentId == null || path.firstOrNull() != treeDocumentId) {
        NPLogger.d(
            "ManagedDownloadLyricsPerf",
            "fast lyric parent path mismatch root=$treeDocumentId, path=$path, " +
                "source=$sourceUri"
        )
        return null
    }
    val parentDocumentId = path.dropLast(1).lastOrNull()?.takeIf(String::isNotBlank)
        ?: return null
    val parentUri = try {
        DocumentsContract.buildDocumentUriUsingTree(root.tree.uri, parentDocumentId)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    } ?: return null
    return (
        // 保留 provider 返回的父文档 URI, 避免 fromTreeUri 将不规范 provider 的子目录折叠到根
        DocumentFile.fromSingleUri(context, parentUri)
            ?: DocumentFile.fromTreeUri(context, parentUri)
        ).also { parent ->
            if (parent == null) {
                NPLogger.d(
                    "ManagedDownloadLyricsPerf",
                    "fast lyric parent document unresolved uri=$parentUri, source=$sourceUri"
                )
            }
        }
}

internal fun ManagedDownloadStorage.readLyricsFromFileRootFast(
    context: Context,
    root: RootHandle.FileRoot,
    song: SongItem,
    candidateBaseNames: List<String>,
    alreadyRead: Set<LyricKind>
): Map<LyricKind, Pair<String, Boolean>> {
    val directories = buildList {
        add(File(root.dir, LYRIC_SUBDIRECTORY))
        add(root.dir)
        song.localFilePath
            ?.takeIf { it.startsWith("/") }
            ?.let { path ->
                val parent = File(path).parentFile
                if (parent != null) {
                    add(File(parent, LYRIC_SUBDIRECTORY))
                    add(parent)
                }
                val legacyRoot = File(LEGACY_DOWNLOAD_ROOT_PATH)
                if (isPathInside(path, legacyRoot.absolutePath)) {
                    add(File(legacyRoot, LYRIC_SUBDIRECTORY))
                }
            }
    }.distinctBy(File::getAbsolutePath)

    return readLyricsFromNamedFiles(
        context = context,
        directories = directories,
        candidateBaseNames = candidateBaseNames,
        alreadyRead = alreadyRead
    )
}

internal fun ManagedDownloadStorage.readLyricsFromCachedTreeFast(
    context: Context,
    root: RootHandle.TreeRoot,
    song: SongItem,
    candidateBaseNames: List<String>,
    alreadyRead: Set<LyricKind>
): Map<LyricKind, Pair<String, Boolean>> {
    if (alreadyRead.size >= LyricKind.entries.size) {
        return emptyMap()
    }

    val lyricParents = listOf(root.tree, findTreeParentDocumentFast(context, root, song))
        .filterNotNull()
        .distinctBy { parent -> parent.uri.toString() }

    data class LyricDirectoriesResult(
        val directories: List<DocumentFile>,
        val cacheIncomplete: Boolean
    )

    fun findLyricDirectories(forceRefresh: Boolean): LyricDirectoriesResult {
        var cacheIncomplete = false
        val directories = lyricParents.asSequence()
            .mapNotNull { parent ->
                val children = try {
                    if (forceRefresh) {
                        treeChildRegistry.refreshTreeChildren(context, parent)
                    } else {
                        treeChildRegistry.cachedTreeChildren(
                            context = context,
                            parent = parent,
                            maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
                        )
                    }
                } catch (error: SecurityException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
                if (!forceRefresh && treeChildRegistry.peekTreeChildren(parent) == null) {
                    // 提供方返回不完整时不能把结果当作歌词不存在来缓存
                    cacheIncomplete = true
                }
                children.firstOrNull { child ->
                    child.isDirectory && child.name.equals(LYRIC_SUBDIRECTORY, ignoreCase = true)
                }?.let { directory ->
                    treeChildRegistry.toDocumentFile(context, parent, directory)
                        // 部分 provider 不声明标准 DocumentProvider, 但仍支持树子项查询
                        ?: DocumentFile.fromSingleUri(context, directory.documentUri)
                }
            }
            .distinctBy { directory -> directory.uri.toString() }
            .toList()
        return LyricDirectoriesResult(
            directories = directories,
            cacheIncomplete = cacheIncomplete
        )
    }

    fun mergeSidecarBundles(
        primary: DownloadedLyricsBundle,
        fallback: DownloadedLyricsBundle
    ): DownloadedLyricsBundle {
        return DownloadedLyricsBundle(
            lyric = if (primary.hasOriginalSidecar) primary.lyric else fallback.lyric,
            translatedLyric = if (primary.hasTranslatedSidecar) {
                primary.translatedLyric
            } else {
                fallback.translatedLyric
            },
            romanizedLyric = if (primary.hasRomanizedSidecar) {
                primary.romanizedLyric
            } else {
                fallback.romanizedLyric
            },
            hasOriginalSidecar = primary.hasOriginalSidecar || fallback.hasOriginalSidecar,
            hasTranslatedSidecar = primary.hasTranslatedSidecar || fallback.hasTranslatedSidecar,
            hasRomanizedSidecar = primary.hasRomanizedSidecar || fallback.hasRomanizedSidecar
        )
    }

    fun readBundle(
        lyricDirectories: List<DocumentFile>,
        forceRefresh: Boolean
    ): DownloadedLyricsBundle {
        return lyricDirectories.fold(DownloadedLyricsBundle(null, null, null)) { bundle, directory ->
            val entries = try {
                val children = if (forceRefresh) {
                    treeChildRegistry.refreshTreeChildren(context, directory)
                } else {
                    treeChildRegistry.cachedTreeChildren(
                        context = context,
                        parent = directory,
                        maxCacheAgeMs = TREE_CHILDREN_CACHE_VALIDATE_INTERVAL_MS
                    )
                }
                children.map { child -> child.toStoredEntry() }
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            mergeSidecarBundles(
                primary = bundle,
                fallback = resolveLyricsBundleFromEntries(
                    song = song,
                    candidateBaseNames = candidateBaseNames,
                    lyricEntries = entries,
                    readText = { reference -> readTextInternal(context, reference) }
                )
            )
        }
    }

    fun hasAnySidecar(bundle: DownloadedLyricsBundle): Boolean {
        return bundle.hasOriginalSidecar ||
            bundle.hasTranslatedSidecar ||
            bundle.hasRomanizedSidecar
    }

    fun toResult(bundle: DownloadedLyricsBundle): Map<LyricKind, Pair<String, Boolean>> {
        return buildMap {
            if (LyricKind.ORIGINAL !in alreadyRead && bundle.hasOriginalSidecar) {
                put(LyricKind.ORIGINAL, bundle.lyric.orEmpty() to true)
            }
            if (LyricKind.TRANSLATED !in alreadyRead && bundle.hasTranslatedSidecar) {
                put(LyricKind.TRANSLATED, bundle.translatedLyric.orEmpty() to true)
            }
            if (LyricKind.ROMANIZED !in alreadyRead && bundle.hasRomanizedSidecar) {
                put(LyricKind.ROMANIZED, bundle.romanizedLyric.orEmpty() to true)
            }
        }
    }

    val cachedDirectories = findLyricDirectories(forceRefresh = false)
    val cachedBundle = readBundle(cachedDirectories.directories, forceRefresh = false)
    if (hasAnySidecar(cachedBundle) && !cachedDirectories.cacheIncomplete) {
        return toResult(cachedBundle)
    }

    NPLogger.d(
        "ManagedDownloadLyricsPerf",
        "fast lyric negative cache refresh root=${root.tree.uri}, " +
            "directories=${cachedDirectories.directories.size}, " +
            "cacheIncomplete=${cachedDirectories.cacheIncomplete}, " +
            "source=${song.mediaUri}"
    )
    val refreshedDirectories = findLyricDirectories(forceRefresh = true)
    val refreshedBundle = readBundle(refreshedDirectories.directories, forceRefresh = true)
    if (!hasAnySidecar(refreshedBundle)) {
        NPLogger.d(
            "ManagedDownloadLyricsPerf",
            "fast lyric refresh miss root=${root.tree.uri}, " +
                "directories=${refreshedDirectories.directories.size}, " +
                "source=${song.mediaUri}"
        )
    }
    return toResult(refreshedBundle)
}

internal fun ManagedDownloadStorage.readLyricsFromNamedFiles(
    context: Context,
    directories: Collection<File>,
    candidateBaseNames: List<String>,
    alreadyRead: Set<LyricKind>
): Map<LyricKind, Pair<String, Boolean>> {
    fun read(kind: LyricKind): Pair<String, Boolean>? {
        if (kind in alreadyRead) return null
        val names = ManagedDownloadStorageNaming.buildLyricCandidateNames(
            songId = null,
            candidateBaseNames = candidateBaseNames,
            kind = when (kind) {
                LyricKind.ORIGINAL -> ManagedDownloadStorageNaming.LyricKind.ORIGINAL
                LyricKind.TRANSLATED -> ManagedDownloadStorageNaming.LyricKind.TRANSLATED
                LyricKind.ROMANIZED -> ManagedDownloadStorageNaming.LyricKind.ROMANIZED
            }
        )
        names.firstNotNullOfOrNull { name ->
            directories.asSequence()
                .map { directory -> File(directory, name) }
                .firstOrNull(File::isFile)
                ?.let { file ->
                    readTextInternal(context, file.absolutePath)
                        ?.let { content -> content to true }
                }
        }?.let { return it }
        return null
    }

    return buildMap {
        read(LyricKind.ORIGINAL)?.let { put(LyricKind.ORIGINAL, it) }
        read(LyricKind.TRANSLATED)?.let { put(LyricKind.TRANSLATED, it) }
        read(LyricKind.ROMANIZED)?.let { put(LyricKind.ROMANIZED, it) }
    }
}

internal fun ManagedDownloadStorage.buildManagedLyricBaseNames(
    song: SongItem,
    audioName: String? = null
): List<String> {
    val fileName = audioName
        ?: normalizeManagedAudioFileName(song.localFileName)
        ?: normalizeManagedAudioFileName(song.localFilePath)
        ?: normalizeManagedAudioFileName(song.mediaUri)
    val fileBaseName = fileName
        ?.substringBeforeLast('.', fileName)
        ?.takeIf(String::isNotBlank)
    return buildList {
        addAll(candidateManagedDownloadBaseNames(song, settings.fileNameTemplate))
        fileBaseName?.let { addAll(candidateManagedDownloadBaseNames(it)) }
    }.distinct()
}

internal fun ManagedDownloadStorage.isPathInside(path: String, root: String): Boolean {
    val normalizedPath = path.trimEnd('/')
    val normalizedRoot = root.trimEnd('/')
    return normalizedPath == normalizedRoot || normalizedPath.startsWith("$normalizedRoot/")
}

internal fun ManagedDownloadStorage.scheduleSnapshotWarmup(
    context: Context,
    refreshSidecars: Boolean = false
) {
    val appContext = context.applicationContext
    val cacheKey = snapshotCacheStore.currentKey(appContext)
    synchronized(snapshotWarmupLock) {
        if (snapshotWarmupKey == cacheKey && snapshotWarmupJob?.isActive == true) {
            snapshotWarmupRefreshSidecars =
                snapshotWarmupRefreshSidecars || refreshSidecars
            return
        }
        snapshotWarmupKey = cacheKey
        snapshotWarmupRefreshSidecars = refreshSidecars
        snapshotWarmupJob = snapshotScope.launch {
            runCatching {
                // 即使已有持久化索引, 也先验证 SAF 授权, 防止权限失效后继续展示旧目录
                resolveRootBlocking(appContext)
                val cachedBeforeBuild = snapshotCacheStore.cachedSnapshot(
                    context = appContext,
                    restorePersisted = false
                )
                val snapshot = if (cachedBeforeBuild == null) {
                    restoreFastIndexPreviewBlocking(appContext)
                    buildDownloadLibrarySnapshotBlocking(
                        context = appContext,
                        forceRefresh = true
                    )
                } else {
                    cachedBeforeBuild
                }
                val shouldRefreshSidecars = synchronized(snapshotWarmupLock) {
                    snapshotWarmupRefreshSidecars
                }
                if (shouldRefreshSidecars) {
                    refreshDownloadSidecarSnapshotBlocking(
                        context = appContext,
                        snapshot = snapshot,
                        respectThrottle = true,
                        refreshCovers = false
                    )
                } else if (cachedBeforeBuild == null) {
                    notifyLyricsRefresh()
                }
            }.onFailure { error ->
                NPLogger.w(TAG, "后台预热下载歌词索引失败: ${error.message}")
            }
            synchronized(snapshotWarmupLock) {
                if (snapshotWarmupKey == cacheKey) {
                    snapshotWarmupJob = null
                    snapshotWarmupRefreshSidecars = false
                }
            }
        }
    }
}

internal fun ManagedDownloadStorage.isLocalStorageReference(reference: String): Boolean {
    return reference.startsWith("/") ||
        reference.startsWith("content:", ignoreCase = true) ||
        reference.startsWith("file:", ignoreCase = true)
}

internal suspend fun ManagedDownloadStorage.resolveRoot(context: Context, directoryUriString: String?): RootHandle? = withContext(Dispatchers.IO) {
    resolveRootBlocking(context, directoryUriString)
}
