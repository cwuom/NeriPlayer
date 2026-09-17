package moe.ouom.neriplayer.core.download.storage.operation.content

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.policy.isDurableCoreArtifactState
import moe.ouom.neriplayer.core.download.storage.operation.isMigrationReferenceBoundToRoot
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.composeSnapshot
import moe.ouom.neriplayer.core.download.storage.operation.listChildren
import moe.ouom.neriplayer.core.download.storage.operation.listSubdirectoryEntries
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.FastIndexReadResult
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_MANIFEST_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_INDEX_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import moe.ouom.neriplayer.core.download.storage.directory.ManagedDownloadDirectoryIdentity
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.metadata.resolveCreatedAtConfidence
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndex
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutationResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexShardReadResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexShardStorage
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexShardWriteResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryIndexEntry
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import java.io.File
import java.io.IOException
import java.util.Locale
import org.json.JSONObject
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle


internal fun ManagedDownloadStorage.isMissingReplacementBackup(
    context: Context,
    targetRoot: RootHandle,
    backup: StoredEntry
): Boolean {
    val normalized = backup.reference.trim()
    if (!isMigrationReferenceBoundToRoot(targetRoot, normalized)) return false
    return when (inspectStorageReference(context, normalized)) {
        ManagedDownloadReferenceIo.AccessResult.Missing -> true
        else -> false
    }
}

internal fun ManagedDownloadStorage.hasManagedDownloadPathHint(song: SongItem): Boolean {
    return listOfNotNull(song.localFilePath, song.mediaUri).any { reference ->
        val raw = reference.trim().replace('\\', '/')
        val decoded = runCatching { Uri.decode(raw) }.getOrNull() ?: raw
        val normalized = "/${decoded.trim('/')}/"
            .replace("//", "/")
            .lowercase()
        normalized.contains("/neriplayer-download/")
    }
}

internal fun ManagedDownloadStorage.isMediaStoreSongWithinManagedRoot(
    context: Context,
    songReferences: List<Uri>,
    treeDocumentId: String?
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return false
    }
    val mediaStoreReference = songReferences.firstOrNull { reference ->
        reference.authority.equals("media", ignoreCase = true)
    } ?: return false
    val relativePath = try {
        context.contentResolver.query(
            mediaStoreReference,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getString(index)
            } else {
                null
            }
        }
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    }
    return isManagedDownloadRelativePath(
        relativePath = relativePath,
        treeDocumentId = treeDocumentId
    )
}

internal fun ManagedDownloadStorage.isDocumentWithinManagedTree(
    context: Context,
    songReference: Uri,
    treeDocumentId: String
): Boolean {
    return try {
        DocumentsContract.findDocumentPath(
            context.contentResolver,
            songReference
        )?.path?.any { documentId -> documentId == treeDocumentId } == true
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

internal fun ManagedDownloadStorage.normalizeCoverReference(reference: String?): String? {
    val raw = reference?.trim()?.takeIf(String::isNotBlank) ?: return null
    val uri = runCatching { raw.toUri() }.getOrNull()
    if (uri?.scheme?.equals("file", ignoreCase = true) != true) {
        return raw
    }
    val path = uri.path?.takeIf(String::isNotBlank) ?: return raw
    return runCatching { File(path).canonicalPath }.getOrElse { path }
}

internal fun ManagedDownloadStorage.pendingArtifactLogicalName(entry: StoredEntry): String? {
    return if (entry.isPendingAudioWrite) {
        entry.logicalName
    } else {
        ManagedDownloadTreeNaming.metadataAudioName(entry.name)
    }
}

internal fun ManagedDownloadStorage.isDurableCoreMetadata(metadata: DownloadedAudioMetadata): Boolean {
    if (metadata.downloadFinalized == true) {
        return true
    }
    return isDurableCoreArtifactState(
        metadata.artifactState
            ?.trim()
            ?.uppercase(Locale.ROOT)
    )
}

internal fun ManagedDownloadStorage.storageReferenceForInspection(entry: StoredEntry): String {
    return resolveStoredEntryPlaybackUri(entry, allowPending = true)
        ?: entry.reference
}

internal fun ManagedDownloadStorage.missingFastIndexManifestResult(
    stableKey: String
): ManagedLibraryFastIndexMutationResult.Failed {
    val shard = stableKey.takeIf(String::isNotBlank)
        ?.let(ManagedLibraryFastIndex::shardFor)
        .orEmpty()
    return ManagedLibraryFastIndexMutationResult.Failed(
        shard = shard,
        error = IOException("managed library manifest is unavailable")
    )
}

internal fun ManagedDownloadStorage.fastIndexRootIdentity(root: RootHandle): String {
    return when (root) {
        is RootHandle.FileRoot -> {
            val path = runCatching { root.dir.canonicalPath }
                .getOrElse { root.dir.absolutePath }
            "file:$path"
        }
        is RootHandle.TreeRoot -> {
            val rawUri = root.tree.uri.toString()
            val identity = ManagedDownloadDirectoryIdentity.directoryIdentity(rawUri)
                ?: rawUri
            "tree:$identity"
        }
    }
}

internal suspend fun ManagedDownloadStorage.ensureManagedLibraryManifestForRoot(
    context: Context,
    root: RootHandle,
    rootIdentity: String = fastIndexRootIdentity(root)
): String {
    return fastIndexManifestLocks.withLock(
        rootIdentity = rootIdentity,
        shard = FAST_INDEX_MANIFEST_LOCK_SHARD
    ) {
        ensureManagedLibraryManifestBlocking(context, root)
    }
}

internal suspend fun ManagedDownloadStorage.readManagedLibraryIdForRoot(
    context: Context,
    root: RootHandle,
    rootIdentity: String = fastIndexRootIdentity(root)
): String? {
    return fastIndexManifestLocks.withLock(
        rootIdentity = rootIdentity,
        shard = FAST_INDEX_MANIFEST_LOCK_SHARD
    ) {
        readManagedLibraryIdBlocking(context, root)
    }
}

internal fun ManagedDownloadStorage.fastIndexShardStorage(
    context: Context,
    root: RootHandle,
    expectedRootIdentity: String
): ManagedLibraryFastIndexShardStorage {
    return object : ManagedLibraryFastIndexShardStorage {
        override suspend fun readShard(
            rootIdentity: String,
            shard: String
        ): ManagedLibraryFastIndexShardReadResult {
            if (
                rootIdentity != expectedRootIdentity ||
                    fastIndexRootIdentity(root) != expectedRootIdentity
            ) {
                return ManagedLibraryFastIndexShardReadResult.Unavailable(
                    IllegalStateException("fast index root changed during read")
                )
            }
            return readFastIndexShardBlocking(context, root, shard)
        }

        override suspend fun writeShard(
            rootIdentity: String,
            shard: String,
            payload: String
        ): ManagedLibraryFastIndexShardWriteResult {
            if (
                rootIdentity != expectedRootIdentity ||
                    fastIndexRootIdentity(root) != expectedRootIdentity
            ) {
                return ManagedLibraryFastIndexShardWriteResult.Unavailable(
                    IllegalStateException("fast index root changed during write")
                )
            }
            return writeFastIndexShardBlocking(
                context = context,
                root = root,
                shard = shard,
                payload = payload
            )
        }
    }
}

internal fun ManagedDownloadStorage.readFastIndexShardBlocking(
    context: Context,
    root: RootHandle,
    shard: String
): ManagedLibraryFastIndexShardReadResult {
    val displayName = "shard-$shard.json"
    return try {
        val entries = when (root) {
            is RootHandle.FileRoot -> {
                val indexDirectory = File(root.dir, MANAGED_LIBRARY_INDEX_DIR_NAME)
                if (!indexDirectory.exists()) {
                    return ManagedLibraryFastIndexShardReadResult.Missing
                }
                if (!indexDirectory.isDirectory) {
                    return ManagedLibraryFastIndexShardReadResult.Unavailable(
                        IOException("fast index path is not a directory")
                    )
                }
                val target = File(indexDirectory, displayName)
                if (!target.exists()) {
                    return ManagedLibraryFastIndexShardReadResult.Missing
                }
                listOf(ManagedDownloadStoredEntryMapper.fromFile(target))
            }
            is RootHandle.TreeRoot -> {
                val refresh = treeDirectories.refreshSubdirectoryEntries(
                    context = context,
                    root = root,
                    subdirectory = MANAGED_LIBRARY_INDEX_DIR_NAME
                )
                if (!refresh.isComplete) {
                    return ManagedLibraryFastIndexShardReadResult.Unavailable(
                        IOException("fast index SAF enumeration is incomplete")
                    )
                }
                refresh.entries.filter { entry -> entry.name == displayName }
            }
        }
        if (entries.isEmpty()) {
            return ManagedLibraryFastIndexShardReadResult.Missing
        }
        if (entries.size != 1 || entries.single().isDirectory) {
            return ManagedLibraryFastIndexShardReadResult.Unavailable(
                IOException("fast index target shard is ambiguous: $displayName")
            )
        }
        val payload = when (root) {
            is RootHandle.FileRoot -> File(entries.single().reference).readText(Charsets.UTF_8)
            is RootHandle.TreeRoot -> readTextInternal(context, entries.single().reference)
                ?: return ManagedLibraryFastIndexShardReadResult.Unavailable(
                    IOException("fast index target shard cannot be read: $displayName")
                )
        }
        ManagedLibraryFastIndexShardReadResult.Found(payload)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ManagedLibraryFastIndexShardReadResult.Unavailable(error)
    }
}

internal fun ManagedDownloadStorage.writeFastIndexShardBlocking(
    context: Context,
    root: RootHandle,
    shard: String,
    payload: String
): ManagedLibraryFastIndexShardWriteResult {
    val displayName = "shard-$shard.json"
    return try {
        val verifiedPayload = when (root) {
            is RootHandle.FileRoot -> {
                val indexDirectory = File(root.dir, MANAGED_LIBRARY_INDEX_DIR_NAME)
                if (indexDirectory.exists() && !indexDirectory.isDirectory) {
                    return ManagedLibraryFastIndexShardWriteResult.Unavailable(
                        IOException("fast index path is not a directory")
                    )
                }
                treeDirectories.ensureManagedMediaScanIsolation(
                    MANAGED_LIBRARY_INDEX_DIR_NAME,
                    indexDirectory
                )
                val target = File(indexDirectory, displayName)
                ManagedDownloadAtomicFile.writeTextAtomically(target, payload)
                target.readText(Charsets.UTF_8)
            }
            is RootHandle.TreeRoot -> {
                val entry = commitWriter.writeSubdirectoryBytes(
                    context = context,
                    root = root,
                    subdirectory = MANAGED_LIBRARY_INDEX_DIR_NAME,
                    displayName = displayName,
                    bytes = payload.toByteArray(Charsets.UTF_8),
                    mimeType = "application/json"
                ) ?: return ManagedLibraryFastIndexShardWriteResult.Unavailable(
                    IOException("fast index target shard was not written: $displayName")
                )
                readTextInternal(context, entry.reference)
            }
        }
        if (verifiedPayload != payload) {
            return ManagedLibraryFastIndexShardWriteResult.Unavailable(
                IOException("fast index target shard verification failed: $displayName")
            )
        }
        ManagedLibraryFastIndexShardWriteResult.Written
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ManagedLibraryFastIndexShardWriteResult.Unavailable(error)
    }
}

internal fun ManagedDownloadStorage.readFastIndexWithRootEntriesBlocking(
    context: Context
): FastIndexReadResult? {
    val root = resolveRootBlocking(context)
    val rootEntries = listChildren(context, root)
        .filterNot(StoredEntry::isDirectory)
        .map { entry ->
            ManagedLibraryFastIndex.RootEntry(
                name = entry.name,
                reference = entry.reference
            )
        }
    val libraryId = rootEntries
        .firstOrNull { entry -> entry.name == MANAGED_LIBRARY_MANIFEST_FILE_NAME }
        ?.let { entry -> readTextInternal(context, entry.reference) }
        ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
        ?.optString("libraryId")
        ?.takeIf(String::isNotBlank)
        ?: return null
    val entries = listSubdirectoryEntries(context, root, MANAGED_LIBRARY_INDEX_DIR_NAME)
        .asSequence()
        .filter { entry -> entry.name.startsWith("shard-") && entry.name.endsWith(".json") }
        .mapNotNull { entry ->
            readTextInternal(context, entry.reference)
                ?.let(ManagedLibraryFastIndex::decode)
        }
        .filter { shard -> shard.libraryId == libraryId }
        .flatMap { shard -> shard.entries.asSequence() }
        .toList()
    return FastIndexReadResult(entries = entries, rootEntries = rootEntries)
}

internal fun ManagedDownloadStorage.restoreFastIndexPreviewBlocking(context: Context): Boolean {
    val index = runCatching { readFastIndexWithRootEntriesBlocking(context) }
        .getOrElse { error ->
            NPLogger.w(TAG, "读取 Managed SAF fast index 失败，回退完整重建: ${error.message}")
            return false
        }
        ?: return false
    val entries = index.entries
    if (entries.isEmpty()) return false
    val currentReferences = ManagedLibraryFastIndex.joinAudioReferences(
        entries,
        index.rootEntries
    )
    val audioEntries = entries.mapNotNull { entry ->
        val currentReference = currentReferences[entry.audioName]
            ?: return@mapNotNull null
        StoredEntry(
            name = entry.audioName,
            reference = currentReference,
            mediaUri = currentReference,
            localFilePath = currentReference.takeIf { it.startsWith("/") },
            sizeBytes = 0L,
            lastModifiedMs = entry.updatedAtMs,
            sizeKnown = false,
            isDirectory = false
        )
    }
    if (audioEntries.isEmpty()) return false
    val entriesByAudioName = entries.associateBy(ManagedLibraryIndexEntry::audioName)
    val metadataByAudioName = audioEntries.associate { audio ->
        val entry = entriesByAudioName.getValue(audio.name)
        entry.audioName to DownloadedAudioMetadata(
            stableKey = entry.stableKey,
            songId = entry.songId,
            album = entry.album,
            name = entry.title,
            artist = entry.artist,
            mediaUri = entry.mediaUri,
            channelId = entry.channelId,
            audioId = entry.audioId,
            subAudioId = entry.subAudioId,
            playlistContextId = entry.playlistContextId,
            durationMs = entry.durationMs ?: 0L,
            coverPath = entry.coverPath,
            downloadTimeMs = entry.downloadTimeMs,
            downloadFinalized = entry.state in setOf("FINALIZED", "COMPLETE"),
            metadataEmbeddingState = entry.metadataEmbeddingState,
            createdAtMs = entry.logicalCreatedAtMs ?: entry.updatedAtMs,
            createdAtSource = entry.createdAtSource ?: "INDEX_PREVIEW",
            createdAtConfidence = entry.createdAtConfidence
                ?: entry.createdAtSource?.let(::resolveCreatedAtConfidence)
                ?: "INFERRED",
            artifactId = entry.artifactId,
            artifactState = entry.state,
            audioFileName = entry.audioName
        )
    }
    val cacheKey = snapshotCacheStore.currentKey(context)
    snapshotCacheStore.putSnapshot(
        context = context,
        cacheKey = cacheKey,
        snapshot = composeSnapshot(
            audioEntries = audioEntries,
            metadataEntries = emptyList(),
            metadataByAudioName = metadataByAudioName,
            coverEntries = emptyList(),
            lyricEntries = emptyList(),
            rootEntriesComplete = false,
            sidecarEntriesComplete = false
        )
    )
    NPLogger.d(TAG, "使用 Managed SAF fast index 发布预览: entries=${entries.size}")
    return true
}
