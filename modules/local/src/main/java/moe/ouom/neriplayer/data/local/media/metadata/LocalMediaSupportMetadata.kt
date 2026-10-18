package moe.ouom.neriplayer.data.local.media.metadata

import moe.ouom.neriplayer.data.local.media.*
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentLyricReferenceResolution
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.DocumentChild
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.LyricKind
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.io.readBytesLimited
import moe.ouom.neriplayer.common.io.isFileInsideDirectory
import java.io.File
import java.io.IOException
import androidx.core.net.toUri

internal fun LocalMediaSupport.logEditableMetadataWriteTiming(
    sourceUri: Uri?,
    startedAtMs: Long,
    outcome: LocalMediaMetadataWriteOutcome,
    mode: String
) {
    val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
    val message = "local metadata write finished: uri=$sourceUri, " +
        "mode=$mode, outcome=$outcome, elapsedMs=$elapsedMs"
    if (elapsedMs >= EDITABLE_METADATA_WRITE_BUDGET_MS) {
        if (outcome == LocalMediaMetadataWriteOutcome.SUCCESS ||
            outcome == LocalMediaMetadataWriteOutcome.SIDECAR_ONLY
        ) {
            NPLogger.i(TAG, "$message, overBudget=true")
        } else {
            NPLogger.w(TAG, "$message, overBudget=true")
        }
    } else {
        NPLogger.d(TAG, "$message, overBudget=false")
    }
}

/**
 * MediaStore 条目对应的 SAF 文档只是更好的写入路径；只读授权的文件夹会在探测时抛出拒绝，
 * 这时跳过镜像，继续使用 MediaStore 自身（可能已经通过系统授权获得写权限）
 */
private fun LocalMediaSupport.resolveWritableSafMirrorOrNull(context: Context, source: Uri): Uri? = try {
    resolveWritableLocalMediaUri(context, source)
} catch (error: SecurityException) {
    NPLogger.d(TAG, "skip read-only SAF mirror for $source: ${error.javaClass.simpleName}")
    null
}

internal fun LocalMediaSupport.editableLocalMediaUriCandidates(
    context: Context,
    song: SongItem
): List<Uri> {
    val directCandidates = song.localMediaUriCandidates()
    val safCandidates = directCandidates.asSequence()
        .filter(::isMediaStoreUri)
        .mapNotNull { source -> resolveWritableSafMirrorOrNull(context, source) }
        .toList()
    return (safCandidates + directCandidates)
        .distinctBy(Uri::toString)
        .sortedBy { uri ->
            when {
                uri.scheme.equals("file", ignoreCase = true) -> 0
                isMediaStoreUri(uri) -> 2
                else -> 1
            }
        }
}

internal fun LocalMediaSupport.resolveEditableSidecarFile(context: Context, sourceUri: Uri): File? {
    if (shouldUseDocumentSidecarMutation(sourceUri)) return null
    return runCatching { resolveLocalFile(context, sourceUri) }.getOrNull()
}

fun LocalMediaSupport.isStandaloneContentMetadataTarget(
    context: Context,
    sourceUri: Uri,
    localFile: File?
): Boolean {
    if (!sourceUri.scheme.equals("content", ignoreCase = true) || localFile != null) return false
    if (DocumentsContract.isDocumentUri(context, sourceUri) || DocumentsContract.isTreeUri(sourceUri)) {
        return false
    }
    if (isMediaStoreUri(sourceUri)) {
        val relativePath = queryContentInfo(context, sourceUri).relativePath
            ?.trim()?.trim('/')?.takeIf(String::isNotBlank) ?: return false
        // 目录查询失败不代表相邻文件不存在，匹配根仍需保持侧载事务；只读授权写不了侧载，按独立文件处理
        return resolveExternalStorageTreeUri(context, relativePath, requireWrite = true) == null
    }
    // 单文件授权没有相邻文件命名空间，已有 SAF 目录授权仍沿完整侧载事务处理
    return resolveLocalDocumentNavigation(context, sourceUri)?.parentDocumentId == null
}

internal fun LocalMediaSupport.writeLocalCoverSidecar(
    context: Context,
    sourceUri: Uri,
    file: File?,
    displayName: String,
    coverReference: String?,
    stableIdentityKey: String?,
    companionTransaction: LocalMediaCompanionTransaction? = null,
    parentChildrenForMutation: List<LocalMediaSupport.DocumentChild>? = null
): Boolean {
    val mutation = resolveEditableCoverMutation(
        writeCover = true,
        coverReference = coverReference
    )
    val localFile = file.takeUnless {
        shouldUseDocumentSidecarMutation(sourceUri)
    }
    if (localFile != null) {
        return writeLocalFileCoverSidecar(
            context = context,
            file = localFile,
            coverReference = coverReference,
            mutation = mutation,
            stableIdentityKey = stableIdentityKey,
            companionTransaction = companionTransaction
        )
    }
    if (shouldSkipLocalCoverSidecar(sourceUri.toString(), localFile)) {
        val navigation = try {
            resolveLocalDocumentNavigation(context, sourceUri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (navigation?.parentDocumentId.isNullOrBlank()) {
            return true
        }
    }
    if (!sourceUri.scheme.equals("content", ignoreCase = true)) {
        return false
    }
    return writeDocumentCoverSidecar(
        context = context,
        sourceUri = sourceUri,
        displayName = displayName,
        coverReference = coverReference,
        mutation = mutation,
        stableIdentityKey = stableIdentityKey,
        companionTransaction = companionTransaction,
        parentChildrenForMutation = parentChildrenForMutation
    )
}

internal fun LocalMediaSupport.writeLocalFileCoverSidecar(
    context: Context,
    file: File,
    coverReference: String?,
    mutation: EditableCoverMutation,
    stableIdentityKey: String?,
    companionTransaction: LocalMediaCompanionTransaction? = null
): Boolean {
    val parent = file.parentFile ?: return false
    val coverDirectory = findCoversDirectory(parent) ?: File(parent, "Covers")
    val baseName = file.nameWithoutExtension
    val existingSpecificFiles = imageExtensions
        .flatMap { extension ->
            localCoverSidecarNames(baseName, extension, stableIdentityKey)
                .map { name -> File(coverDirectory, name) }
        }
        .filter(File::isFile)
    val existingParentSpecificFiles = imageExtensions.map { extension ->
        File(parent, "$baseName.$extension")
    }.filter(File::isFile)
    if (mutation == EditableCoverMutation.CLEAR) {
        return (existingSpecificFiles + existingParentSpecificFiles).all { candidate ->
            !candidate.exists() || if (companionTransaction != null) {
                companionTransaction.deferDelete(candidate.absolutePath)
                true
            } else {
                candidate.delete()
            }
        }
    }
    val reference = coverReference?.trim()?.takeIf(String::isNotBlank) ?: return false
    val bytes = readEditableCoverBytes(context, reference) ?: return false
    val mimeType = resolveEditableCoverMimeType(context, reference, bytes)
    val extension = coverExtensionForMimeType(mimeType)
    val target = File(
        coverDirectory,
        localCoverSidecarName(baseName, extension, stableIdentityKey)
    )
    if (companionTransaction != null) {
        companionTransaction.write(target.absolutePath, bytes, created = !target.exists())
    } else if (!writeBytesFileAtomically(target, bytes)) return false
    (existingSpecificFiles + existingParentSpecificFiles).filter { it != target }.forEach { old ->
        if (old.exists()) {
            if (companionTransaction != null) {
                companionTransaction.deferDelete(old.absolutePath)
            } else if (!old.delete()) {
                return false
            }
        }
    }
    return target.isFile && target.length() == bytes.size.toLong()
}

internal fun LocalMediaSupport.writeDocumentCoverSidecar(
    context: Context,
    sourceUri: Uri,
    displayName: String,
    coverReference: String?,
    mutation: EditableCoverMutation,
    stableIdentityKey: String?,
    companionTransaction: LocalMediaCompanionTransaction? = null,
    parentChildrenForMutation: List<LocalMediaSupport.DocumentChild>? = null
): Boolean {
    val navigation = resolveLocalDocumentNavigation(context, sourceUri) ?: return false
    val parentId = navigation.parentDocumentId ?: return false
    val baseUri = navigation.treeUri ?: navigation.baseUri
    val audioBaseName = displayName.substringBeforeLast('.', displayName)
    val managedNames = managedCoverSidecarNames(
        baseName = audioBaseName,
        extensions = imageExtensions,
        stableIdentityKey = stableIdentityKey
    )
    if (mutation == EditableCoverMutation.CLEAR) {
        return withDocumentMutationLock(baseUri, parentId) {
            val parentChildren = parentChildrenForMutation ?: queryDocumentChildrenForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = parentId
            ) ?: return@withDocumentMutationLock false
            if (!documentChildrenContainSource(
                    parentChildren = parentChildren,
                    sourceUri = sourceUri,
                    displayName = displayName,
                    parentDocumentId = parentId
                )
            ) {
                return@withDocumentMutationLock false
            }
            val coversDirectory = findManagedSidecarDirectory(parentChildren, "Covers")
            if (managedNames.isEmpty()) return@withDocumentMutationLock true
            val parentSpecific = parentChildren.filter { child ->
                !child.isDirectory && child.displayName in managedNames
            }
            val coversChildren = coversDirectory?.let { directory ->
                queryDocumentChildrenForMutation(
                    context = context,
                    baseUri = baseUri,
                    parentDocumentId = directory.documentId
                ) ?: return@withDocumentMutationLock false
            }.orEmpty()
            val specific = coversChildren.filter { child ->
                !child.isDirectory && child.displayName in managedNames
            }
            val deleted = (specific + parentSpecific).distinctBy(DocumentChild::uri).all { child ->
                if (companionTransaction != null) {
                    companionTransaction.deferDelete(child.uri)
                    true
                } else {
                    deleteDocumentReference(context, child)
                }
            }
            if (deleted) {
                invalidateDocumentChildrenCache(baseUri, parentId)
                coversDirectory?.let { directory ->
                    invalidateDocumentChildrenCache(baseUri, directory.documentId)
                }
                clearCoverLookupCache()
            }
            deleted
        }
    }
    val reference = coverReference?.trim()?.takeIf(String::isNotBlank) ?: return false
    val bytes = readEditableCoverBytes(context, reference) ?: return false
    val mimeType = resolveEditableCoverMimeType(context, reference, bytes)
    val extension = coverExtensionForMimeType(mimeType)
    return withDocumentMutationLock(baseUri, parentId) {
        val parentChildren = parentChildrenForMutation ?: queryDocumentChildrenForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentId
        ) ?: return@withDocumentMutationLock false
        if (!documentChildrenContainSource(
                parentChildren = parentChildren,
                sourceUri = sourceUri,
                displayName = displayName,
                parentDocumentId = parentId
            )
        ) {
            return@withDocumentMutationLock false
        }
        val coversDirectory = findManagedSidecarDirectory(parentChildren, "Covers")
        val parentSpecific = parentChildren.filter { child ->
            !child.isDirectory && child.displayName in managedNames
        }
        val resolvedCoversDirectory = coversDirectory ?: ensureDocumentSidecarDirectoryForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentId,
            directoryName = "Covers",
            parentChildren
        ) ?: return@withDocumentMutationLock false
        val coversChildren = queryDocumentChildrenForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = resolvedCoversDirectory.documentId
        ) ?: return@withDocumentMutationLock false
        val targetName = localCoverSidecarName(audioBaseName, extension, stableIdentityKey)
        val specific = coversChildren.filter { child ->
            !child.isDirectory && child.displayName in managedNames
        }
        val targetChild = findExactDocumentSidecarChild(coversChildren, targetName)
            ?: createDocumentSidecarForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = resolvedCoversDirectory.documentId,
                mimeType = mimeType,
                displayName = targetName,
                coversChildren
            )
            ?: return@withDocumentMutationLock false
        val target = targetChild.uri
        if (targetChild.createdByCurrentMutation) {
            companionTransaction?.created(target)
        }
        if (companionTransaction != null) {
            companionTransaction.write(target, bytes, created = targetChild.createdByCurrentMutation)
        } else if (!writeBytesContent(context, target, bytes)) return@withDocumentMutationLock false
        (specific + parentSpecific).distinctBy(DocumentChild::uri)
            .filter { it.uri != target }
            .forEach { old ->
                if (companionTransaction != null) {
                    companionTransaction.deferDelete(old.uri)
                } else if (!deleteDocumentReference(context, old)) {
                    return@withDocumentMutationLock false
                }
            }
        invalidateDocumentChildrenCache(baseUri, parentId)
        invalidateDocumentChildrenCache(baseUri, resolvedCoversDirectory.documentId)
        clearCoverLookupCache()
        readBytesContentMatchesWithRetry(context, target, bytes)
    }
}

internal fun LocalMediaSupport.writeLocalLyricsSidecars(
    context: Context,
    sourceUri: Uri,
    file: File?,
    displayName: String,
    song: SongItem,
    knownReferences: NearbyLyricReferences? = null,
    companionTransaction: LocalMediaCompanionTransaction? = null,
    parentChildrenForMutation: List<LocalMediaSupport.DocumentChild>? = null,
    parentChildrenFresh: Boolean = false
): Boolean {
    val contents = listOf(
        LyricKind.ORIGINAL to (song.matchedLyric ?: song.originalLyric),
        LyricKind.TRANSLATED to
            (song.matchedTranslatedLyric ?: song.originalTranslatedLyric),
        LyricKind.ROMANIZED to
            (song.matchedRomanizedLyric ?: song.originalRomanizedLyric)
    )
    val localFile = file.takeUnless {
        shouldUseDocumentSidecarMutation(sourceUri)
    }
    if (localFile != null) {
        val nearby = knownReferences?.let { references ->
            NearbyLyricFiles(
                original = references.original?.let(::File),
                translated = references.translated?.let(::File),
                romanized = references.romanized?.let(::File)
            )
        } ?: findNearbyLyricFiles(localFile)
        val legacyRoot = File(LEGACY_DOWNLOAD_ROOT)
        val isLegacyDownload = runCatching {
            isFileInsideDirectory(localFile, legacyRoot)
        }.getOrDefault(false)
        val targetDirectory = resolveLocalLyricsTargetDirectory(
            file = localFile,
            nearby = nearby,
            legacyRoot = legacyRoot,
            isLegacyDownload = isLegacyDownload
        )
        fun isInLyricsDirectory(candidate: File): Boolean {
            return runCatching {
                val parent = candidate.canonicalFile.parentFile
                parent == targetDirectory.canonicalFile || parent == localFile.canonicalFile.parentFile
            }.getOrDefault(false)
        }
        val plans = contents.mapNotNull { (kind, content) ->
            val existing = when (kind) {
                LyricKind.ORIGINAL -> nearby.original
                LyricKind.TRANSLATED -> nearby.translated
                LyricKind.ROMANIZED -> nearby.romanized
            }?.takeIf(::isInLyricsDirectory)
            if (content == null) {
                // null 表示调用方没有修改这一种歌词
                return@mapNotNull null
            }
            if (content.isBlank() && existing == null) return@mapNotNull null
            val contentValue = content
            val target = existing ?: File(
                targetDirectory,
                lyricSidecarNames(
                    baseName = localFile.nameWithoutExtension,
                    kind = kind,
                    extensions = listOf("lrc")
                ).first()
            )
            val existed = target.isFile
            val previous = if (existed) readTextFile(target) else null
            if (existed && previous == null) return false
            Triple(target, contentValue, previous)
        }
        val written = plans.all { (target, content, _) ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            val success = if (companionTransaction != null) {
                companionTransaction.write(target.absolutePath, bytes, created = !target.exists())
            } else writeTextFileAtomically(target, content) && readTextFile(target) == content
            success
        }
        if (!written && companionTransaction == null) {
            plans.asReversed().forEach { (target, _, previous) ->
                if (previous == null) {
                    if (target.exists() && !target.delete()) {
                        NPLogger.w(TAG, "rollback lyric sidecar delete failed: ${target.absolutePath}")
                    }
                } else if (!writeTextFileAtomically(target, previous)) {
                    NPLogger.w(TAG, "rollback lyric sidecar restore failed: ${target.absolutePath}")
                }
            }
        }
        clearLyricsLookupCache()
        return written
    }

    val navigation = resolveLocalDocumentNavigation(context, sourceUri)
    val parentId = navigation?.parentDocumentId
    val writeDocumentSidecars = writeDocumentSidecars@{
        val rawReferences = knownReferences ?: findNearbyLyricReferences(
            context = context,
            uri = sourceUri,
            file = null,
            displayName = displayName
        )
        val existingReferences = rawReferences.copy(
            original = rawReferences.original?.takeUnless { reference ->
                shouldUseDocumentSidecarMutation(sourceUri) && reference.startsWith("/")
            },
            translated = rawReferences.translated?.takeUnless { reference ->
                shouldUseDocumentSidecarMutation(sourceUri) && reference.startsWith("/")
            },
            romanized = rawReferences.romanized?.takeUnless { reference ->
                shouldUseDocumentSidecarMutation(sourceUri) && reference.startsWith("/")
            }
        )
        val lyricResolution = ensureDocumentLyricReferences(
            context = context,
            uri = sourceUri,
            displayName = displayName,
            requiredKinds = contents.mapNotNullTo(mutableSetOf()) { (kind, content) ->
                kind.takeIf { content != null }
            },
            existing = existingReferences,
            parentChildrenForMutation = parentChildrenForMutation,
            createKinds = contents.mapNotNullTo(mutableSetOf()) { (kind, content) ->
                kind.takeIf { !content.isNullOrBlank() }
            },
            onCreated = { child -> companionTransaction?.created(child.uri) },
            parentChildrenFresh = parentChildrenFresh
        )
        if (!lyricResolution.lookupSucceeded) return@writeDocumentSidecars false
        val references = lyricResolution.references
        var invalidPlan = false
        val plans = contents.mapNotNull { (kind, content) ->
            if (content == null) {
                // null 表示调用方没有修改这一种歌词
                return@mapNotNull null
            }
            val contentValue = content
            val reference = when (kind) {
                LyricKind.ORIGINAL -> references.original
                LyricKind.TRANSLATED -> references.translated
                LyricKind.ROMANIZED -> references.romanized
            }
            if (reference == null) {
                if (content.isNotBlank()) invalidPlan = true
                return@mapNotNull null
            }
            val previous = readTextContent(context, reference)
            val existedBefore = reference !in lyricResolution.createdReferences
            if (existedBefore && previous == null) {
                invalidPlan = true
                return@mapNotNull null
            }
            Triple(reference, contentValue, previous to existedBefore)
        }
        if (invalidPlan) {
            if (companionTransaction == null) {
                lyricResolution.createdReferences.values.forEach { created ->
                    deleteDocumentReference(context, created)
                }
            }
            return@writeDocumentSidecars false
        }
        var written = true
        plans.forEach { (reference, content, _) ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            val success = if (companionTransaction != null) {
                companionTransaction.write(reference, bytes, created = reference in lyricResolution.createdReferences)
            } else writeTextContent(context, reference, content)
            if (!success) {
                written = false
                NPLogger.w(TAG, "SAF lyric variant write failed: reference=$reference")
            }
        }
        if (!written && companionTransaction == null) {
            plans.asReversed().forEach { (reference, _, previousState) ->
                val (previous, existedBefore) = previousState
                if (existedBefore) {
                    if (previous != null && !writeTextContent(context, reference, previous)) {
                        NPLogger.w(TAG, "rollback SAF lyric sidecar restore failed: $reference")
                    }
                } else if (
                    lyricResolution.createdReferences[reference]
                        ?.let { created -> !deleteDocumentReference(context, created) }
                        == true
                ) {
                    NPLogger.w(TAG, "rollback SAF lyric sidecar delete failed: $reference")
                }
            }
        }
        written
    }
    val written = if (navigation != null && parentId != null) {
        withDocumentMutationLock(navigation.treeUri ?: navigation.baseUri, parentId) {
            writeDocumentSidecars()
        }
    } else {
        writeDocumentSidecars()
    }
    clearLyricsLookupCache()
    return written
}

internal fun LocalMediaSupport.ensureDocumentLyricReferences(
    context: Context,
    uri: Uri,
    displayName: String,
    requiredKinds: Set<LyricKind>,
    existing: NearbyLyricReferences,
    parentChildrenForMutation: List<LocalMediaSupport.DocumentChild>? = null,
    createKinds: Set<LyricKind> = requiredKinds,
    onCreated: ((DocumentChild) -> Unit)? = null,
    parentChildrenFresh: Boolean = false
): DocumentLyricReferenceResolution {
    if (!uri.scheme.equals("content", ignoreCase = true) || requiredKinds.isEmpty()) {
        return DocumentLyricReferenceResolution(existing, emptyMap())
    }
    val unresolved = DocumentLyricReferenceResolution(
        NearbyLyricReferences(null, null, null), emptyMap(), lookupSucceeded = false
    )
    val navigation = resolveLocalDocumentNavigation(context, uri)
        ?: return unresolved
    val parentId = navigation.parentDocumentId
        ?: return unresolved
    val baseUri = navigation.treeUri ?: navigation.baseUri
    val audioBaseName = displayName.substringBeforeLast('.', displayName)
    return withDocumentMutationLock(baseUri, parentId) {
        val parentChildren = parentChildrenForMutation ?: queryDocumentChildrenForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = parentId
        ) ?: return@withDocumentMutationLock unresolved
        if (!documentChildrenContainSource(
                parentChildren = parentChildren,
                sourceUri = uri,
                displayName = displayName,
                parentDocumentId = parentId
            )
        ) {
            return@withDocumentMutationLock unresolved
        }
        val existingLyricsDirectory = findManagedSidecarDirectory(parentChildren, "Lyrics")
        val existingLyricsChildren = existingLyricsDirectory?.let { directory ->
            queryDocumentChildrenForMutation(context, baseUri, directory.documentId)
                ?: return@withDocumentMutationLock unresolved
        }.orEmpty()
        val nested = resolveDocumentLyricReferences(existingLyricsChildren, audioBaseName)
        val adjacent = resolveDocumentLyricReferences(parentChildren, audioBaseName)
        val resolved = NearbyLyricReferences(
            original = (nested.original ?: adjacent.original)
                .takeIf { LyricKind.ORIGINAL in requiredKinds || existing.original != null },
            translated = (nested.translated ?: adjacent.translated)
                .takeIf { LyricKind.TRANSLATED in requiredKinds || existing.translated != null },
            romanized = (nested.romanized ?: adjacent.romanized)
                .takeIf { LyricKind.ROMANIZED in requiredKinds || existing.romanized != null }
        )
        fun reference(kind: LyricKind): String? = when (kind) {
            LyricKind.ORIGINAL -> resolved.original
            LyricKind.TRANSLATED -> resolved.translated
            LyricKind.ROMANIZED -> resolved.romanized
        }
        val missingKinds = requiredKinds.filterTo(mutableSetOf()) { kind ->
            kind in createKinds && reference(kind) == null
        }
        if (missingKinds.isEmpty()) {
            return@withDocumentMutationLock DocumentLyricReferenceResolution(resolved, emptyMap())
        }
        // 旧快照不能证明相邻歌词仍不存在，创建前重新确认一次父目录
        if (parentChildrenForMutation != null && !parentChildrenFresh) {
            return@withDocumentMutationLock ensureDocumentLyricReferences(
                context, uri, displayName, requiredKinds, existing,
                parentChildrenForMutation = null, createKinds = createKinds, onCreated = onCreated
            )
        }
        val lyricsDirectory = existingLyricsDirectory ?: ensureDocumentSidecarDirectoryForMutation(
                context = context,
                baseUri = baseUri,
                parentDocumentId = parentId,
                directoryName = "Lyrics",
                parentChildren
            )
            ?: return@withDocumentMutationLock unresolved
        val targetChildren = if (existingLyricsDirectory != null) existingLyricsChildren else queryDocumentChildrenForMutation(
            context = context,
            baseUri = baseUri,
            parentDocumentId = lyricsDirectory.documentId
        ) ?: return@withDocumentMutationLock unresolved
        val createdReferences = linkedMapOf<String, DocumentChild>()
        fun ensure(kind: LyricKind, existingReference: String?): String? {
            val name = lyricSidecarNames(
                baseName = audioBaseName,
                kind = kind,
                extensions = listOf("lrc")
            ).first()
            if (existingReference != null || kind !in missingKinds) {
                return existingReference
            }
            findDocumentSidecarChild(targetChildren, name)?.uri?.let { return it }
            return createDocumentSidecarForMutation(
                    context = context,
                    baseUri = baseUri,
                    parentDocumentId = lyricsDirectory.documentId,
                    mimeType = "text/plain",
                    displayName = name,
                    targetChildren
                )?.also { child ->
                    if (child.createdByCurrentMutation) {
                        createdReferences[child.uri] = child
                        onCreated?.invoke(child)
                    }
                }
                ?.uri
        }
        DocumentLyricReferenceResolution(
            references = NearbyLyricReferences(
                original = ensure(LyricKind.ORIGINAL, resolved.original),
                translated = ensure(LyricKind.TRANSLATED, resolved.translated),
                romanized = ensure(LyricKind.ROMANIZED, resolved.romanized)
            ),
            createdReferences = createdReferences
        )
    }
}

internal fun LocalMediaSupport.writeTextFileAtomically(target: File, content: String): Boolean {
    val parent = target.parentFile ?: return false
    if (!parent.exists() && !parent.mkdirs()) return false
    val temporary = runCatching {
        File.createTempFile(".${target.name}.", ".tmp", parent)
    }.getOrNull() ?: return false
    return runCatching {
        java.io.FileOutputStream(temporary).use { it.write(content.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        java.nio.file.Files.move(temporary.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        target.isFile && readTextFile(target) == content
    }.onFailure {
        temporary.delete()
        NPLogger.w(TAG, "write lyric sidecar failed for ${target.absolutePath}: ${it.message}")
    }.getOrDefault(false)
}

internal fun LocalMediaSupport.writeBytesFileAtomically(target: File, bytes: ByteArray): Boolean {
    val parent = target.parentFile ?: return false
    if (!parent.exists() && !parent.mkdirs()) return false
    val temporary = runCatching {
        File.createTempFile(".${target.name}.", ".tmp", parent)
    }.getOrNull() ?: return false
    return runCatching {
        java.io.FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        java.nio.file.Files.move(temporary.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        target.isFile && target.inputStream().use { input ->
            input.readBytesLimited(MAX_EDITABLE_COVER_BYTES).contentEquals(bytes)
        }
    }.onFailure {
        temporary.delete()
        NPLogger.w(TAG, "write cover sidecar failed for ${target.absolutePath}: ${it.message}")
    }.getOrDefault(false)
}

internal fun LocalMediaSupport.writeTextContent(context: Context, reference: String, content: String): Boolean {
    val bytes = content.toByteArray(Charsets.UTF_8)
    var lastError: Throwable? = null
    for ((modeIndex, mode) in listOf("wt", "w").withIndex()) {
        val written = try {
            val output = context.contentResolver.openOutputStream(reference.toUri(), mode)
                ?: run {
                    lastError = IllegalStateException("provider returned no output stream")
                    null
                }
            if (output == null) {
                false
            } else {
                output.use {
                    it.write(bytes)
                    it.flush()
                }
                readTextContentMatchesWithRetry(context, reference, content)
            }
        } catch (error: SecurityException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastError = error
            false
        }
        if (written) {
            return true
        }
        if (lastError == null || lastError is IllegalStateException) {
            lastError = IOException("SAF lyric sidecar readback mismatch")
        }
        if (modeIndex + 1 < 2 && SAF_WRITE_READBACK_DELAYS_MS[modeIndex + 1] > 0L) {
            SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[modeIndex + 1])
        }
    }
    if (lastError == null) return true
    NPLogger.w(TAG, "write lyric sidecar failed for $reference: ${lastError.message}")
    return false
}

internal fun LocalMediaSupport.writeBytesContent(context: Context, reference: String, bytes: ByteArray): Boolean {
    var lastError: Throwable? = null
    for ((modeIndex, mode) in listOf("rwt", "wt", "w").withIndex()) {
        val written = try {
            val output = context.contentResolver.openOutputStream(reference.toUri(), mode)
                ?: run {
                    lastError = IllegalStateException("provider returned no output stream")
                    null
                }
            if (output == null) {
                false
            } else {
                output.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                }
                readBytesContentMatchesWithRetry(context, reference, bytes)
            }
        } catch (error: SecurityException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastError = error
            false
        }
        if (written) return true
        if (lastError == null || lastError is IllegalStateException) {
            lastError = IOException("SAF cover sidecar readback mismatch")
        }
        if (modeIndex + 1 < 3 && SAF_WRITE_READBACK_DELAYS_MS[modeIndex] > 0L) {
            SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[modeIndex])
        }
    }
    NPLogger.w(TAG, "write cover sidecar failed for $reference: ${lastError?.message}")
    return false
}

internal fun LocalMediaSupport.readTextContentMatchesWithRetry(
    context: Context,
    reference: String,
    expected: String
): Boolean {
    repeat(SAF_WRITE_READBACK_RETRY_COUNT) { attempt ->
        if (readTextContent(context, reference) == expected) return true
        if (attempt + 1 < SAF_WRITE_READBACK_RETRY_COUNT) {
            SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[attempt + 1])
        }
    }
    return false
}

internal fun LocalMediaSupport.readBytesContentMatchesWithRetry(
    context: Context,
    reference: String,
    expected: ByteArray
): Boolean {
    repeat(SAF_WRITE_READBACK_RETRY_COUNT) { attempt ->
        if (readBytesContent(context, reference)?.contentEquals(expected) == true) {
            return true
        }
        if (attempt + 1 < SAF_WRITE_READBACK_RETRY_COUNT) {
            SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[attempt + 1])
        }
    }
    return false
}

internal fun LocalMediaSupport.readBytesContent(context: Context, reference: String): ByteArray? {
    return try {
        if (reference.startsWith("/")) {
            File(reference).inputStream().use { input ->
                input.readBytesLimited(MAX_EDITABLE_COVER_BYTES)
            }
        } else {
            context.contentResolver.openInputStream(reference.toUri())?.use { input ->
                input.readBytesLimited(MAX_EDITABLE_COVER_BYTES)
            }
        }
    } catch (error: SecurityException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(TAG, "read cover sidecar failed for $reference: ${error.message}")
        null
    }
}

internal fun LocalMediaSupport.deleteDocumentReference(context: Context, child: DocumentChild): Boolean {
    val uri = runCatching { child.uri.toUri() }.getOrNull() ?: return false
    val actualDocumentId = runCatching {
        DocumentsContract.getDocumentId(uri)
    }.getOrNull() ?: return false
    if (child.documentId.isBlank() || actualDocumentId != child.documentId) {
        NPLogger.w(TAG, "拒绝删除来源不明的 SAF sidecar: ${child.uri}")
        return false
    }
    return try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (error: SecurityException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(TAG, "delete sidecar failed for ${child.uri}: ${error.message}")
        false
    }
}

internal fun LocalMediaSupport.inspectLyricsFromKnownReferences(
    context: Context,
    references: LocalKnownSidecarReferences
): DirectLocalLyricsInspection {
    val metadata = references.metadata
        ?.takeUnless(::isMediaStoreSidecarReference)
        ?.let { reference ->
            readTextContent(context, reference)?.let { raw ->
                parseLocalMetadataSidecar(reference, raw)
            }
        }

    fun read(reference: String?): String? {
        return reference
            ?.takeUnless(::isMediaStoreSidecarReference)
            ?.let { readTextContent(context, it) }
    }

    val original = read(references.lyrics.original)
    val translated = read(references.lyrics.translated)
    val romanized = read(references.lyrics.romanized)
    return DirectLocalLyricsInspection(
        original = original,
        translated = translated,
        romanized = romanized,
        metadataOriginal = metadata?.takeIf { it.hasLyricOverride }?.lyric,
        metadataTranslated = metadata
            ?.takeIf { it.hasTranslatedLyricOverride }
            ?.translatedLyric,
        metadataRomanized = metadata
            ?.takeIf { it.hasRomanizedLyricOverride }
            ?.romanizedLyric,
        hasOriginalSidecar = references.lyrics.original != null && original != null,
        hasTranslatedSidecar = references.lyrics.translated != null && translated != null,
        hasRomanizedSidecar = references.lyrics.romanized != null && romanized != null
    )
}

internal fun LocalMediaSupport.logLyricsInspection(
    song: SongItem,
    source: Uri?,
    stage: String,
    startedAt: Long,
    result: LocalLyricsScanMetadata
) {
    if (localLyricsPerfLogCount.getAndIncrement() >= LOCAL_LYRICS_PERF_LOG_LIMIT) {
        return
    }
    NPLogger.d(
        "LocalLyricsPerf",
        "song=${song.name}, stage=$stage, elapsed=" +
            "${SystemClock.elapsedRealtime() - startedAt}ms, " +
            "originalSidecar=${result.hasOriginalSidecar}, " +
            "translatedSidecar=${result.hasTranslatedSidecar}, " +
            "romanizedSidecar=${result.hasRomanizedSidecar}, " +
            "source=${source ?: song.localFilePath ?: song.mediaUri}"
    )
}
