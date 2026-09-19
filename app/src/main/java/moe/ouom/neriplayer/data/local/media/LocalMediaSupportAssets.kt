package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.FilePathCacheHit
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChildrenCacheEntry
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DirectoryFileIndex
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChild
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChildrenQueryResult
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.LyricKind
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File
import java.text.Normalizer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale

internal fun LocalMediaSupport.rememberDocumentChildrenCacheEntryLocked(
    cacheKey: String,
    children: List<DocumentChild>,
    cachedAtMs: Long,
    isComplete: Boolean
) {
    if (!isDocumentChildrenCacheSizeAllowed(children.size)) {
        documentChildrenCache.remove(cacheKey)
        return
    }
    documentChildrenCache[cacheKey] = DocumentChildrenCacheEntry(
        children = children,
        cachedAtMs = cachedAtMs,
        isComplete = isComplete
    )
    while (!isDocumentChildrenCacheTotalWithinBudget(documentChildrenCacheTotalChildren())) {
        val victim = documentChildrenCache.entries
            .firstOrNull { entry -> entry.key != cacheKey }
            ?.key
            ?: break
        documentChildrenCache.remove(victim)
    }
    if (!isDocumentChildrenCacheTotalWithinBudget(documentChildrenCacheTotalChildren())) {
        documentChildrenCache.remove(cacheKey)
    }
}

internal fun LocalMediaSupport.documentChildrenCacheTotalChildren(): Int {
    return documentChildrenCache.values.sumOf { entry -> entry.children.size }
}

internal fun LocalMediaSupport.queryDocumentChildrenForMutation(
    context: Context,
    baseUri: Uri,
    parentDocumentId: String?
): List<DocumentChild>? {
    val resolvedParentId = parentDocumentId?.takeIf(String::isNotBlank) ?: return null
    repeat(SAF_CHILDREN_QUERY_RETRY_COUNT) { attempt ->
        val result = queryDocumentChildrenUncached(
            context = context,
            baseUri = baseUri,
            parentDocumentId = resolvedParentId,
            maxChildren = null
        ) ?: return@repeat
        val stabilized = stabilizeDocumentChildrenRefresh(
            baseUri = baseUri,
            parentDocumentId = resolvedParentId,
            result = result
        )
        cacheDocumentChildren(
            baseUri = baseUri,
            parentDocumentId = resolvedParentId,
            children = stabilized.children,
            isComplete = stabilized.isComplete
        )
        if (stabilized.isComplete) {
            return stabilized.children
        }
        if (attempt + 1 < SAF_CHILDREN_QUERY_RETRY_COUNT) {
            SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[attempt + 1])
        }
    }
    return null
}

internal fun LocalMediaSupport.ensureDocumentSidecarDirectoryForMutation(
    context: Context,
    baseUri: Uri,
    parentDocumentId: String,
    directoryName: String,
    existingChildren: List<DocumentChild>? = null
): DocumentChild? {
    val children = existingChildren ?: queryDocumentChildrenForMutation(
        context = context,
        baseUri = baseUri,
        parentDocumentId = parentDocumentId
    ) ?: return null
    val knownChildren = (children + cachedDocumentChildren(baseUri, parentDocumentId))
        .distinctBy(DocumentChild::uri)
    findExactManagedSidecarDirectory(knownChildren, directoryName)?.let { return it }
    val refreshedChildren = queryDocumentChildrenForMutation(
        context = context,
        baseUri = baseUri,
        parentDocumentId = parentDocumentId
    ) ?: return null
    val refreshedKnownChildren = (
        refreshedChildren + cachedDocumentChildren(baseUri, parentDocumentId)
        ).distinctBy(DocumentChild::uri)
    findExactManagedSidecarDirectory(refreshedKnownChildren, directoryName)?.let { return it }
    findCanonicalExternalStorageChildForMutation(
        context = context,
        baseUri = baseUri,
        parentDocumentId = parentDocumentId,
        displayName = directoryName,
        isDirectory = true
    )?.let { canonical ->
        rememberDocumentChild(baseUri, parentDocumentId, canonical)
        return canonical
    }
    val parentUri = buildDocumentReferenceUri(baseUri, parentDocumentId)
    NPLogger.d(
        TAG,
        "create local SAF sidecar directory: name=$directoryName, parent=$parentDocumentId, " +
            "known=${refreshedKnownChildren.joinToString { child -> child.displayName }}"
    )
    val createdUri = try {
        DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            directoryName
        )
    } catch (error: SecurityException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(TAG, "create sidecar directory failed for $parentUri: ${error.message}")
        null
    } ?: return null
    // DocumentsProvider 可能在 createDocument 返回后延迟刷新 children 查询。
    // 返回 URI 已经是 provider 确认的目标，优先使用它避免误判为创建失败。
    val resolved = documentChildFromCreatedUri(
        context = context,
        uri = createdUri,
        isDirectory = true
    )
        ?: queryDocumentChildrenForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentDocumentId
        ).orEmpty().let { children ->
            findExactManagedSidecarDirectory(children, directoryName)
        }
        ?: return null
    rememberDocumentChild(baseUri, parentDocumentId, resolved)
    return resolved
}

internal fun LocalMediaSupport.createDocumentSidecarForMutation(
    context: Context,
    baseUri: Uri,
    parentDocumentId: String,
    mimeType: String,
    displayName: String,
    existingChildren: List<DocumentChild>? = null
): DocumentChild? {
    val currentChildren = existingChildren ?: queryDocumentChildrenForMutation(
        context = context,
        baseUri = baseUri,
        parentDocumentId = parentDocumentId
    ) ?: return null
    val knownChildren = (currentChildren + cachedDocumentChildren(baseUri, parentDocumentId))
        .distinctBy(DocumentChild::uri)
    findExactDocumentSidecarChild(knownChildren, displayName)?.let { return it }
    val refreshedChildren = queryDocumentChildrenForMutation(
        context = context,
        baseUri = baseUri,
        parentDocumentId = parentDocumentId
    ) ?: return null
    val refreshedKnownChildren = (
        refreshedChildren + cachedDocumentChildren(baseUri, parentDocumentId)
        ).distinctBy(DocumentChild::uri)
    findExactDocumentSidecarChild(refreshedKnownChildren, displayName)?.let { return it }
    findCanonicalExternalStorageChildForMutation(
        context = context,
        baseUri = baseUri,
        parentDocumentId = parentDocumentId,
        displayName = displayName,
        isDirectory = false
    )?.let { canonical ->
        rememberDocumentChild(baseUri, parentDocumentId, canonical)
        return canonical
    }
    val parentUri = buildDocumentReferenceUri(baseUri, parentDocumentId)
    NPLogger.d(
        TAG,
        "create local SAF sidecar file: name=$displayName, parent=$parentDocumentId, " +
            "known=${refreshedKnownChildren.joinToString { child -> child.displayName }}"
    )
    val createdUri = try {
        DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            ManagedDownloadStorage.documentCreateMimeType(
                desiredName = displayName,
                mimeType = mimeType
            ),
            displayName
        )
    } catch (error: SecurityException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(TAG, "create sidecar document failed for $parentUri: ${error.message}")
        null
    } ?: return null
    // 同上，先消费 createDocument 的返回值，再把刷新查询作为兼容回退。
    val resolved = documentChildFromCreatedUri(
        context = context,
        uri = createdUri,
        isDirectory = false
    )
        ?.copy(createdByCurrentMutation = true)
        ?: queryDocumentChildrenForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentDocumentId
        ).orEmpty().let { children ->
            findExactDocumentSidecarChild(children, displayName)
        }
        ?: return null
    rememberDocumentChild(baseUri, parentDocumentId, resolved)
    return resolved
}

internal fun LocalMediaSupport.documentChildFromUri(
    context: Context,
    uri: Uri,
    isDirectory: Boolean
): DocumentChild? {
    val documentId = try {
        DocumentsContract.getDocumentId(uri)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    }?.takeIf(String::isNotBlank) ?: return null
    val document = try {
        DocumentFile.fromSingleUri(context, uri)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    }
    val actualName = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    } ?: document?.name ?: return null
    return DocumentChild(
        documentId = documentId,
        displayName = actualName,
        isDirectory = document?.isDirectory ?: isDirectory,
        uri = uri.toString()
    )
}

internal fun LocalMediaSupport.documentChildFromCreatedUri(
    context: Context,
    uri: Uri,
    isDirectory: Boolean
): DocumentChild? {
    // createDocument 返回的 URI 可能对应 provider 改写后的名字, 立即查询实际条目
    return documentChildFromUri(
        context = context,
        uri = uri,
        isDirectory = isDirectory
    )
}

internal fun LocalMediaSupport.findCanonicalExternalStorageChildForMutation(
    context: Context,
    baseUri: Uri,
    parentDocumentId: String,
    displayName: String,
    isDirectory: Boolean
): DocumentChild? {
    // documentId 是 Provider 的 opaque 身份, 不能从父 ID 和文件名反推
    return null
}

internal fun LocalMediaSupport.findExactDocumentSidecarChild(
    children: Collection<DocumentChild>,
    canonicalName: String
): DocumentChild? {
    return children.asSequence()
        .filterNot(DocumentChild::isDirectory)
        .firstOrNull { child ->
            canonicalSafName(child.displayName) == canonicalSafName(canonicalName)
        }
}

internal fun LocalMediaSupport.findDocumentSidecarChild(
    children: Collection<DocumentChild>,
    canonicalName: String
): DocumentChild? {
    return children.asSequence()
        .filterNot(DocumentChild::isDirectory)
        .filter { child -> sidecarNameMatches(child.displayName, canonicalName) }
        .minWithOrNull(
            compareBy(
                { if (canonicalSafName(it.displayName) == canonicalSafName(canonicalName)) 0 else 1 },
                { numberedSidecarNameOrdinal(it.displayName, canonicalName) },
                DocumentChild::displayName
            )
        )
}

internal fun LocalMediaSupport.canonicalSafName(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
}

internal fun LocalMediaSupport.queryDocumentChildren(
    context: Context,
    baseUri: Uri,
    parentDocumentId: String?
): List<DocumentChild> {
    val resolvedParentId = parentDocumentId?.takeIf { it.isNotBlank() } ?: return emptyList()
    return try {
        run read@{
            val cacheKey = documentParentCacheKey(baseUri, resolvedParentId)
            synchronized(documentChildrenCache) {
                documentChildrenCache[cacheKey]?.let { cached ->
                    if (cached.isFresh(System.currentTimeMillis())) {
                        return@read cached.children
                    }
                    documentChildrenCache.remove(cacheKey)
                }
            }
            val children = queryDocumentChildrenUncached(
                context = context,
                baseUri = baseUri,
                parentDocumentId = resolvedParentId
            ) ?: return@read emptyList()
            val stabilized = stabilizeDocumentChildrenRefresh(
                baseUri = baseUri,
                parentDocumentId = resolvedParentId,
                result = children
            )
            cacheDocumentChildren(
                baseUri = baseUri,
                parentDocumentId = resolvedParentId,
                children = stabilized.children,
                isComplete = stabilized.isComplete
            )
            stabilized.children
        }
    } catch (error: SecurityException) {
        invalidateSafReadCaches()
        val failure = classifySafReadFailure(
            baseUri = baseUri,
            attemptedDocumentId = resolvedParentId,
            error = error
        )
        NPLogger.w(
            TAG,
            "SAF 只读目录查询失败: kind=${failure::class.simpleName}, " +
                "base=$baseUri, parent=$resolvedParentId, message=${error.message}"
        )
        emptyList()
    }
}

internal fun LocalMediaSupport.classifySafReadFailure(
    baseUri: Uri,
    attemptedDocumentId: String,
    error: SecurityException
): SafAccessResult<Nothing> {
    val treeUri = if (DocumentsContract.isTreeUri(baseUri)) {
        baseUri
    } else {
        runCatching {
            DocumentsContract.buildTreeDocumentUri(
                baseUri.authority ?: return@runCatching baseUri,
                attemptedDocumentId
            )
        }.getOrDefault(baseUri)
    }
    val message = error.message.orEmpty()
    return if (message.contains("not a descendant", ignoreCase = true)) {
        SafAccessResult.OutOfScope(treeUri, attemptedDocumentId, error)
    } else {
        SafAccessResult.PermissionLost(treeUri, error)
    }
}

internal fun LocalMediaSupport.queryDocumentChildrenUncached(
    context: Context,
    baseUri: Uri,
    parentDocumentId: String?,
    maxChildren: Int? = DOCUMENT_CHILDREN_CACHE_MAX_CHILDREN
): DocumentChildrenQueryResult? {
    val queriedChildren = queryDocumentChildrenDirect(
        context = context,
        baseUri = baseUri,
        parentDocumentId = parentDocumentId,
        maxChildren = maxChildren
    )
    if (queriedChildren != null) return queriedChildren
    val resolvedParentId = parentDocumentId?.takeIf(String::isNotBlank) ?: return null
    return DocumentChildrenQueryResult(
        children = listDocumentChildrenWithDocumentFile(
            context = context,
            baseUri = baseUri,
            parentDocumentId = resolvedParentId,
            maxChildren = maxChildren
        ),
        isComplete = false
    )
}

internal fun LocalMediaSupport.queryDocumentChildrenDirect(
    context: Context,
    baseUri: Uri,
    parentDocumentId: String?,
    maxChildren: Int? = DOCUMENT_CHILDREN_CACHE_MAX_CHILDREN
): DocumentChildrenQueryResult? {
    val resolvedParentId = parentDocumentId?.takeIf(String::isNotBlank) ?: return null
    val childrenUri = try {
        if (DocumentsContract.isTreeUri(baseUri)) {
            DocumentsContract.buildChildDocumentsUriUsingTree(baseUri, resolvedParentId)
        } else {
            DocumentsContract.buildChildDocumentsUri(
                baseUri.authority ?: return null,
                resolvedParentId
            )
        }
    } catch (error: SecurityException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(TAG, "build document children uri failed for $baseUri: ${error.message}")
        null
    } ?: return null
    val queriedChildren = try {
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                return@use null
            }
            var truncated = false
            val children = buildList {
                while (cursor.moveToNext()) {
                    if (hasReachedDocumentChildrenQueryLimit(size, maxChildren)) {
                        // 巨型目录只保留有界预览，后续按需路径仍可继续查询
                        truncated = true
                        break
                    }
                    val childId = cursor.getString(idIndex)
                    if (childId.isNullOrBlank()) continue
                    val childName = cursor.getString(nameIndex)
                    if (childName.isNullOrBlank()) continue
                    val mimeType = cursor.getString(mimeIndex).orEmpty()
                    add(
                        DocumentChild(
                            documentId = childId,
                            displayName = childName,
                            isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                            uri = buildDocumentReferenceUri(baseUri, childId).toString()
                        )
                    )
                }
            }
            val extras = cursor.extras
            val loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
            val providerError = extras?.getString(DocumentsContract.EXTRA_ERROR)
            DocumentChildrenQueryResult(
                children = children,
                isComplete = !loading && providerError.isNullOrBlank() && !truncated
            )
        }
    } catch (error: SecurityException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(TAG, "query document children failed for $baseUri: ${error.message}")
        null
    }
    return queriedChildren
}

internal fun LocalMediaSupport.stabilizeDocumentChildrenRefresh(
    baseUri: Uri,
    parentDocumentId: String,
    result: DocumentChildrenQueryResult
): DocumentChildrenQueryResult {
    val cacheKey = documentParentCacheKey(baseUri, parentDocumentId)
    val previous = synchronized(documentChildrenCache) {
        documentChildrenCache[cacheKey]?.children.orEmpty()
    }
    if (!result.isComplete) {
        consecutiveEmptyDocumentRefreshes.remove(cacheKey)
        return DocumentChildrenQueryResult(
            children = mergeDocumentChildren(previous, result.children),
            isComplete = false
        )
    }
    if (result.children.isNotEmpty() || previous.isEmpty()) {
        consecutiveEmptyDocumentRefreshes.remove(cacheKey)
        return result
    }
    val count = consecutiveEmptyDocumentRefreshes.merge(cacheKey, 1) { current, _ -> current + 1 }
        ?: 1
    trimConsecutiveEmptyDocumentRefreshes(cacheKey)
    if (count < EMPTY_DOCUMENT_REFRESH_CONFIRMATION_COUNT) {
        return DocumentChildrenQueryResult(
            children = previous,
            isComplete = false
        )
    }
    consecutiveEmptyDocumentRefreshes.remove(cacheKey)
    return result
}

internal fun LocalMediaSupport.trimConsecutiveEmptyDocumentRefreshes(keepKey: String) {
    val excess = consecutiveEmptyDocumentRefreshes.size -
        CONSECUTIVE_EMPTY_REFRESH_CACHE_LIMIT
    if (excess <= 0) return
    var removed = 0
    consecutiveEmptyDocumentRefreshes.keys.forEach { key ->
        if (removed >= excess) return@forEach
        if (key == keepKey) return@forEach
        if (consecutiveEmptyDocumentRefreshes.remove(key) != null) {
            removed++
        }
    }
}

internal fun LocalMediaSupport.mergeDocumentChildren(
    previous: Collection<DocumentChild>,
    refreshed: Collection<DocumentChild>
): List<DocumentChild> {
    val childrenByUri = LinkedHashMap<String, DocumentChild>()
    previous.forEach { child -> childrenByUri[child.uri] = child }
    refreshed.forEach { child -> childrenByUri[child.uri] = child }
    return childrenByUri.values.toList()
}

internal fun LocalMediaSupport.cacheDocumentChildren(
    baseUri: Uri,
    parentDocumentId: String?,
    children: List<DocumentChild>,
    isComplete: Boolean = true
) {
    val resolvedParentId = parentDocumentId?.takeIf(String::isNotBlank) ?: return
    val cacheKey = documentParentCacheKey(baseUri, resolvedParentId)
    synchronized(documentChildrenCache) {
        val oldEntry = documentChildrenCache[cacheKey]
        val mergedChildren = if (isComplete) {
            children
        } else {
            mergeDocumentChildren(oldEntry?.children.orEmpty(), children)
        }
        if (!isDocumentChildrenCacheSizeAllowed(mergedChildren.size)) {
            // 巨型目录只保留本轮结果，避免把整棵目录长期复制到内存
            documentChildrenCache.remove(cacheKey)
            return
        }
        rememberDocumentChildrenCacheEntryLocked(
            cacheKey = cacheKey,
            children = mergedChildren,
            cachedAtMs = System.currentTimeMillis(),
            isComplete = isComplete
        )
    }
}

internal fun LocalMediaSupport.listDocumentChildrenWithDocumentFile(
    context: Context,
    baseUri: Uri,
    parentDocumentId: String,
    maxChildren: Int? = DOCUMENT_CHILDREN_CACHE_MAX_CHILDREN
): List<DocumentChild> {
    val parentUri = try {
        buildDocumentReferenceUri(baseUri, parentDocumentId)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    } ?: return emptyList()
    val parent = try {
        // buildDocumentReferenceUri 返回子文档 ID 对应的文档 URI
        // 某些 Provider 对这个 URI 调用 fromTreeUri 会错误探测父路径
        if (DocumentsContract.isTreeUri(parentUri)) {
            DocumentFile.fromTreeUri(context, parentUri)
        } else {
            DocumentFile.fromSingleUri(context, parentUri)
        }
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    } ?: return emptyList()
    return try {
        val children = parent.listFiles().asSequence().let { sequence ->
            maxChildren?.let(sequence::take) ?: sequence
        }
        children.mapNotNull { child ->
            val name = child.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val documentId = try {
                DocumentsContract.getDocumentId(child.uri)
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            }?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            DocumentChild(
                documentId = documentId,
                displayName = name,
                isDirectory = child.isDirectory,
                uri = child.uri.toString()
            )
        }.toList()
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun hasReachedDocumentChildrenQueryLimit(
    currentSize: Int,
    maxChildren: Int?
): Boolean = maxChildren != null && currentSize >= maxChildren

internal fun LocalMediaSupport.buildDocumentReferenceUri(baseUri: Uri, documentId: String): Uri {
    return if (DocumentsContract.isTreeUri(baseUri)) {
        DocumentsContract.buildDocumentUriUsingTree(baseUri, documentId)
    } else {
        DocumentsContract.buildDocumentUri(
            baseUri.authority ?: error("Document URI has no authority: $baseUri"),
            documentId
        )
    }
}

internal fun LocalMediaSupport.resolveLocalLyricContentByPriority(
    sidecarContent: String?,
    embeddedContent: String?,
    metadataFallback: String?
): String? {
    return sidecarContent ?: embeddedContent?.takeIf(String::isNotBlank)
        ?: metadataFallback
}

internal fun LocalMediaSupport.findFirstLyricSidecar(
    searchDirectories: List<File>,
    fileNames: List<String>
): File? {
    return searchDirectories.asSequence()
        .flatMap { directory -> fileNames.asSequence().map { File(directory, it) } }
        .firstOrNull(File::isFile)
}

internal fun LocalMediaSupport.lyricSidecarNames(
    baseName: String,
    kind: LyricKind,
    extensions: List<String>
): List<String> {
    val prefixes = when (kind) {
        LyricKind.ORIGINAL -> listOf(baseName)
        LyricKind.TRANSLATED -> listOf("${baseName}_trans")
        LyricKind.ROMANIZED -> listOf(
            "${baseName}_roma",
            "${baseName}_romalrc",
            "${baseName}_romanized"
        )
    }
    return buildList {
        prefixes.forEach { prefix ->
            extensions.forEach { extension ->
                add("$prefix.$extension")
            }
            if ("lrc" in extensions) {
                add("$prefix.lrc.txt")
            }
        }
    }
}

internal fun LocalMediaSupport.findNearbyCoverUncached(parent: File, baseName: String): File? {
    findCoverSidecarInDirectory(parent, baseName)?.let { return it }

    val coverDir = findCoversDirectory(parent)
    if (coverDir != null) {
        findCoverSidecarInDirectory(coverDir, baseName)?.let { return it }
    }

    findDirectoryCover(parent)?.let { return it }

    return null
}

internal fun LocalMediaSupport.findCoverSidecarInDirectory(directory: File, baseName: String): File? {
    // 常用封面名先直接探测，避免大目录里每首歌都触发一次完整遍历
    imageExtensions.forEach { extension ->
        File(directory, "$baseName.$extension")
            .takeIf(File::isFile)
            ?.let { return it }
    }
    val children = directoryFileIndex(directory).files
    return children
        .filter { child ->
            child.isFile &&
            imageExtensions.any { extension ->
                coverSidecarNameMatches(child.name, baseName, extension)
            }
        }
        .minByOrNull { child -> child.name }
        ?.takeIf(File::isFile)
}

internal fun LocalMediaSupport.findCoversDirectory(parent: File): File? {
    val canonical = File(parent, "Covers")
    if (canonical.isDirectory) return canonical
    // 大小写目录名都兼容，刚创建的旁车目录可以马上被发现
    File(parent, "covers").takeIf(File::isDirectory)?.let { return it }
    return directoryFileIndex(parent).directories.firstOrNull { child ->
        child.isDirectory && child.name.equals("Covers", ignoreCase = true)
    }
}

internal fun LocalMediaSupport.directoryFileIndex(directory: File): DirectoryFileIndex {
    val cacheKey = directory.absolutePath
    val directoryLastModifiedMs = directory.lastModified()
    val nowElapsedMs = SystemClock.elapsedRealtime()
    synchronized(directoryFileIndexCache) {
        directoryFileIndexCache[cacheKey]?.let { cached ->
            val fresh = cached.directoryLastModifiedMs == directoryLastModifiedMs &&
                nowElapsedMs - cached.cachedAtElapsedMs <= DIRECTORY_FILE_INDEX_CACHE_TTL_MS
            if (fresh) return cached
        }
    }

    // 列举失败通常是暂时的权限或文件系统问题，空结果不能写进缓存
    // 这里只为封面和 Covers 目录建立索引，不把同目录的音频对象复制到内存
    val listedChildren = directory.listFiles { child ->
        child.isDirectory && child.name.equals("Covers", ignoreCase = true) ||
            child.isFile && imageExtensions.any { extension ->
                child.name.substringAfterLast('.', "")
                    .equals(extension, ignoreCase = true)
            }
    }?.take(DIRECTORY_FILE_INDEX_MAX_CHILDREN)?.toList()
        ?: return DirectoryFileIndex(
            directoryLastModifiedMs = directoryLastModifiedMs,
            cachedAtElapsedMs = nowElapsedMs,
            files = emptyList(),
            directories = emptyList()
        )
    val indexedFiles = ArrayList<File>()
    val indexedDirectories = ArrayList<File>()
    listedChildren.forEach { child ->
        if (child.isDirectory) {
            indexedDirectories += child
        } else if (child.isFile) {
            indexedFiles += child
        }
    }
    val entry = DirectoryFileIndex(
        directoryLastModifiedMs = directoryLastModifiedMs,
        cachedAtElapsedMs = nowElapsedMs,
        files = indexedFiles,
        directories = indexedDirectories
    )
    // 大目录只缓存封面候选，避免每首歌再次遍历整个目录
    synchronized(directoryFileIndexCache) {
        directoryFileIndexCache[cacheKey] = entry
    }
    return entry
}

internal fun LocalMediaSupport.findDirectoryCover(parent: File): File? {
    val cacheKey = directoryCoverLookupKey(parent)
    cachedDirectoryCover(cacheKey)?.let { hit ->
        return hit.path?.let(::File)?.takeIf { it.exists() }
    }

    val cover = coverFileNames.firstNotNullOfOrNull { candidate ->
        imageExtensions.firstNotNullOfOrNull { ext ->
            File(parent, "$candidate.$ext").takeIf { it.exists() }
        }
    }
    rememberDirectoryCover(cacheKey, cover)
    return cover
}

internal fun LocalMediaSupport.nearbyCoverLookupKey(file: File, parent: File, baseName: String): String {
    return "${parent.absolutePath}|${parent.lastModified()}|${file.length()}|$baseName"
}

internal fun LocalMediaSupport.directoryCoverLookupKey(parent: File): String {
    return "${parent.absolutePath}|${parent.lastModified()}"
}

internal fun LocalMediaSupport.cachedNearbyCover(cacheKey: String): FilePathCacheHit? {
    synchronized(nearbyCoverLookupCache) {
        return nearbyCoverLookupCache[cacheKey]?.let(::FilePathCacheHit)
    }
}

internal fun LocalMediaSupport.rememberNearbyCover(cacheKey: String, cover: File?) {
    val coverPath = cover?.absolutePath ?: return
    synchronized(nearbyCoverLookupCache) {
        nearbyCoverLookupCache[cacheKey] = coverPath
    }
}

internal fun LocalMediaSupport.cachedDirectoryCover(cacheKey: String): FilePathCacheHit? {
    synchronized(directoryCoverLookupCache) {
        return directoryCoverLookupCache[cacheKey]?.let(::FilePathCacheHit)
    }
}

internal fun LocalMediaSupport.rememberDirectoryCover(cacheKey: String, cover: File?) {
    val coverPath = cover?.absolutePath ?: return
    synchronized(directoryCoverLookupCache) {
        directoryCoverLookupCache[cacheKey] = coverPath
    }
}

internal fun LocalMediaSupport.parseIndexedMetadata(value: String?): Int? {
    val raw = value?.substringBefore('/')?.trim().orEmpty()
    return raw.toIntOrNull()
}

internal fun LocalMediaSupport.pickReadableLocalTitle(
    sourceUri: Uri,
    fallbackTitle: String,
    vararg candidates: String?
): String? {
    return candidates.firstNotNullOfOrNull { candidate ->
        candidate
            ?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    it.takeMeaningfulLocalMetadata() != null &&
                    isReadableLocalTitleCandidate(it, sourceUri, fallbackTitle)
            }
    }
}

internal fun LocalMediaSupport.isReadableLocalTitleCandidate(
    candidate: String,
    sourceUri: Uri,
    fallbackTitle: String
): Boolean {
    val normalized = candidate.trim()
    if (normalized.isBlank()) return false
    if (normalized.startsWith("content://", ignoreCase = true)) return false
    if (normalized.startsWith("file://", ignoreCase = true)) return false
    return normalized != sourceUri.lastPathSegment || normalized == fallbackTitle
}

internal fun LocalMediaSupport.computeStableSongId(source: String): Long {
    return stableKey(source).take(16).toULong(16).toLong()
}

internal fun LocalMediaSupport.stableKey(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal fun LocalMediaSupport.directFilePath(uri: Uri): String? {
    val path = when {
        uri.scheme.equals("file", ignoreCase = true) -> uri.path
        uri.scheme.isNullOrBlank() && !uri.path.isNullOrBlank() && uri.path!!.startsWith("/") -> uri.path
        else -> null
    } ?: return null
    return path.takeIf { File(it).exists() }
}

internal fun LocalMediaSupport.detectBomCharset(bytes: ByteArray): Pair<Charset, Int>? {
    return when {
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> StandardCharsets.UTF_8 to 3

        bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte() -> StandardCharsets.UTF_16LE to 2

        bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte() -> StandardCharsets.UTF_16BE to 2

        else -> null
    }
}

internal fun LocalMediaSupport.decodeId3TextFrame(frameData: ByteArray): String? {
    if (frameData.isEmpty()) return null
    val content = frameData.copyOfRange(1, frameData.size)
    val charset = when (frameData[0].toInt() and 0xFF) {
        1 -> StandardCharsets.UTF_16
        2 -> StandardCharsets.UTF_16BE
        3 -> StandardCharsets.UTF_8
        else -> StandardCharsets.ISO_8859_1
    }
    return content.toString(charset)
        .normalizeDecodedText()
        .trim(NUL_CHAR, ' ')
        .takeIf { it.isNotBlank() }
}

internal fun LocalMediaSupport.scoreDecodedText(text: String): Int {
    val replacementPenalty = text.count { it == REPLACEMENT_CHAR } * 200
    val nulPenalty = text.count { it == NUL_CHAR } * 200
    val controlPenalty = text.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' } * 40
    val blankPenalty = if (text.isBlank()) 200 else 0
    val lyricBonus = if (text.contains('[') && text.contains(']')) 20 else 0
    val latinLetterDigitBonus = text.count(Char::isAsciiLetterOrDigit) * 2
    val cjkBonus = text.count(Char::isCjkUnifiedIdeograph) * 4
    return 1000 - replacementPenalty - nulPenalty - controlPenalty - blankPenalty +
        lyricBonus + latinLetterDigitBonus + cjkBonus
}
