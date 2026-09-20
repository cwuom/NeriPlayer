package moe.ouom.neriplayer.core.download.storage.operation.content

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.boundManagedDownloadFileName
import moe.ouom.neriplayer.core.download.policy.ManagedDownloadSizePolicy
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.findMetadataForAudioBlocking
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.isPendingAudioPromotionFinalNameCandidate
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.readTemporaryDirectoryEntries
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.resolveTemporaryRoot
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.SnapshotEntryBucket
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_MANIFEST_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitIo
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.StorageWriteResult
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeMutationLocks
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.json.JSONObject
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle

private val audioCommitLocks = Array(64) { Mutex() }
private val audioPublicationLocks = Array(64) { Mutex() }
private val audioNamePreparationLocks = Array(64) { Any() }

internal fun ManagedDownloadStorage.ensureManagedLibraryManifestBlocking(
    context: Context,
    root: RootHandle
): String {
    readManagedLibraryIdBlocking(context, root)?.let { existing ->
        return existing
    }
    val libraryId = UUID.randomUUID().toString()
    val payload = JSONObject().apply {
        put("schemaVersion", 1)
        put("libraryId", libraryId)
        put("layoutVersion", 1)
        put("createdAtMs", System.currentTimeMillis())
        put("indexFormatVersion", 1)
    }.toString()
    val written = writeRootText(
        context = context,
        root = root,
        displayName = MANAGED_LIBRARY_MANIFEST_FILE_NAME,
        content = payload
    )
    if (written == null) {
        throw IOException("无法写入 Managed SAF root manifest")
    }
    return libraryId
}

internal fun ManagedDownloadStorage.readManagedLibraryIdBlocking(
    context: Context,
    root: RootHandle
): String? {
    return findManagedLibraryManifestEntry(
        context = context,
        root = root
    )
        ?.let { entry -> readTextInternal(context, entry.reference) }
        ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
        ?.optString("libraryId")
        ?.takeIf(String::isNotBlank)
}

internal fun ManagedDownloadStorage.findManagedLibraryManifestEntry(
    context: Context,
    root: RootHandle
): StoredEntry? {
    return when (root) {
        is RootHandle.FileRoot -> {
            File(root.dir, MANAGED_LIBRARY_MANIFEST_FILE_NAME)
                .takeIf { it.isFile }
                ?.toStoredEntry()
        }

        is RootHandle.TreeRoot -> {
            treeChildRegistry.cachedTreeChild(
                context = context,
                parent = root.tree,
                childName = MANAGED_LIBRARY_MANIFEST_FILE_NAME,
                // manifest 是应用自己的稳定文件, 在写入窗口内无需重复查询根目录
                maxCacheAgeMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
            )?.toStoredEntry()
        }
    }
}

internal fun ManagedDownloadStorage.deletePendingAudioMetadataBlocking(
    context: Context,
    root: RootHandle,
    audioName: String
): Boolean {
    val rootEntries = when (root) {
        is RootHandle.FileRoot -> {
            root.dir.listFiles()?.map(ManagedDownloadStoredEntryMapper::fromFile)
                ?: return false
        }
        is RootHandle.TreeRoot -> {
            val refresh = treeChildRegistry.peekTreeChildren(root.tree)?.let { children ->
                ManagedDownloadTreeChildRegistry.TreeChildrenRefresh(
                    children = children.toList(),
                    isComplete = true
                )
            } ?: treeChildRegistry.refreshTreeChildrenWithStatus(
                context = context,
                parent = root.tree
            )
            if (!refresh.isComplete) {
                return false
            }
            refresh.children.map(ManagedDownloadStoredEntryMapper::fromTreeChild)
        }
    }
    val temporary = readTemporaryDirectoryEntries(
        context = context,
        root = root,
        forceRefresh = true,
        rootAlreadyRefreshed = true
    )
    if (!temporary.isComplete) return false
    val entries = rootEntries + temporary.entries
    val pendingNames = pendingMetadataEntryNames(
        audioName = audioName,
        candidateNames = entries.filterNot(StoredEntry::isDirectory).map(StoredEntry::name)
    ).toSet()
    val pendingEntries = entries.filter { entry -> entry.name in pendingNames }
    if (pendingEntries.isEmpty()) {
        return true
    }
    val references = pendingEntries.mapTo(linkedSetOf(), StoredEntry::reference)
    val deletedReferences = deleteReferencesInternal(
        context = context,
        references = references,
        allowedRoot = root,
        trustedReferences = references.toSet(),
        invalidateSnapshot = false
    )
    if (deletedReferences.isNotEmpty()) {
        forgetDeletedReferencesFromCaches(deletedReferences)
        snapshotCacheStore.updateAfterDelete(context, deletedReferences.toSet())
    }
    return deletedReferences.containsAll(references)
}

internal suspend fun ManagedDownloadStorage.saveAudioFromTempBlocking(
    context: Context,
    tempFile: File,
    fileName: String,
    mimeType: String?,
    expectedSizeBytes: Long?,
    transferSizeVerified: Boolean,
    seedMetadataJson: String?,
    pendingMetadataJson: String?
): StoredEntry {
    val actualSizeBytes = tempFile.length().coerceAtLeast(0L)
    if (actualSizeBytes <= 0L) {
        throw IOException("下载文件为空: ${tempFile.name}")
    }
    if (shouldRejectTransferSize(
            expectedSizeBytes = expectedSizeBytes,
            actualSizeBytes = actualSizeBytes,
            transferSizeVerified = transferSizeVerified
        )
    ) {
        throw IOException("下载文件大小不匹配: $actualSizeBytes/$expectedSizeBytes")
    }
    if (
        transferSizeVerified &&
        expectedSizeBytes != null &&
        !ManagedDownloadSizePolicy.isTransferSizeComplete(
            expectedSizeBytes = expectedSizeBytes,
            actualSizeBytes = actualSizeBytes
        )
    ) {
        NPLogger.d(
            TAG,
            "提交阶段忽略传输期长度提示: file=${tempFile.name}, " +
                "actual=$actualSizeBytes, expected=$expectedSizeBytes, " +
                "transferAlreadyVerified=$transferSizeVerified"
        )
    }
    val boundedFileName = boundManagedDownloadFileName(fileName)
    val root = resolveRootBlocking(context)
    val rootIdentity = when (root) {
        is RootHandle.FileRoot -> root.dir.absolutePath
        is RootHandle.TreeRoot -> root.tree.uri.toString()
    }
    val seedMetadata = seedMetadataJson?.let(::parseDownloadedAudioMetadataJson)
    val owner = seedMetadata?.operationId?.takeIf(String::isNotBlank) ?: boundedFileName
    val lockIndex = ("$rootIdentity|$owner".hashCode() and Int.MAX_VALUE) % audioCommitLocks.size
    return audioCommitLocks[lockIndex].withLock {
        val snapshot = snapshotCacheStore.cachedSnapshot(context, restorePersisted = false)
            ?.takeIf { it.rootEntriesComplete }
            ?: buildDownloadLibrarySnapshotBlocking(context, forceRefresh = true)
        if (!snapshot.rootEntriesComplete) throw IOException("无法完整核查下载目录，暂停提交")
        val existingAudio = findExistingAudioForCommit(context, snapshot, seedMetadata, tempFile)
        val storedEntry = if (existingAudio != null) {
            if (existingAudio.isPendingAudioWrite) {
                writeSeedMetadataAfterAudioCommit(context, root, existingAudio.logicalName, seedMetadataJson)
            }
            existingAudio
        } else when (root) {
            is RootHandle.FileRoot -> {
                val temporaryRoot = resolveTemporaryRoot(
                    context = context,
                    root = root,
                    create = true
                ) as? RootHandle.FileRoot
                    ?: throw IOException("无法准备下载 .tmp 目录")
                val finalName = prepareAudioNameAndMetadata(context, root, snapshot, boundedFileName, pendingMetadataJson ?: seedMetadataJson)
                val pendingName = buildPendingAudioWriteName(finalName)
                val pendingTarget = File(temporaryRoot.dir, pendingName)
                val audioEntry = try {
                    val writeResult = FileStorageBackend(temporaryRoot.dir).writeRecoverable(
                        target = StorageTarget.FileTarget(pendingName)
                    ) { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                        }
                    }
                    val stored = when (writeResult) {
                        is StorageWriteResult.Written -> {
                            writeResult.stat.toStoredEntryForBackend(temporaryRoot.dir)
                        }
                        StorageWriteResult.Missing -> throw IOException(
                            "pending 音频写入目标不存在: $pendingName"
                        )
                        StorageWriteResult.OutOfScope -> throw IOException(
                            "pending 音频写入目标越界: $pendingName"
                        )
                        StorageWriteResult.PermissionLost -> throw SecurityException(
                            "pending 音频写入权限丢失: $pendingName"
                        )
                        is StorageWriteResult.ProviderFailure -> throw IOException(
                            "pending 音频写入失败: $pendingName",
                            writeResult.error
                        )
                        is StorageWriteResult.Unsupported -> throw IOException(
                            "pending 音频不支持写入: $pendingName (${writeResult.operation})"
                        )
                    }
                    verifyFileCommittedLength(
                        target = pendingTarget,
                        expectedSizeBytes = actualSizeBytes,
                        description = pendingTarget.name
                    )
                    stored.copy(sizeBytes = actualSizeBytes)
                } catch (error: Throwable) {
                    deletePendingFileAndConfirm(pendingTarget)?.let { cleanupError ->
                        error.addSuppressed(
                            IOException(
                                "pending 音频写入失败后清理未确认: $pendingName",
                                cleanupError
                            )
                        )
                    }
                    treeChildRegistry.forgetFileChildName(root.dir, finalName)
                    throw error
                }
                writeSeedMetadataAfterAudioCommit(
                    context = context,
                    root = root,
                    audioName = finalName,
                    seedMetadataJson = seedMetadataJson
                )
                audioEntry
            }

            is RootHandle.TreeRoot -> {
                val temporaryRoot = resolveTemporaryRoot(
                    context = context,
                    root = root,
                    create = true
                ) as? RootHandle.TreeRoot
                    ?: throw IOException("无法准备下载 .tmp 目录")
                val finalName = prepareAudioNameAndMetadata(context, root, snapshot, boundedFileName, pendingMetadataJson ?: seedMetadataJson)
                val createdPendingName = buildPendingAudioWriteName(finalName)
                val audioEntry = try {
                    val entry = writeSafFileThroughBackend(
                        context = context,
                        parent = temporaryRoot.tree,
                        displayName = createdPendingName,
                        mimeType = mimeTypeFromName(finalName, mimeType),
                        expectedSizeBytes = actualSizeBytes,
                        sourceFile = tempFile
                    )
                    treeChildRegistry.rememberTreeChild(temporaryRoot.tree, entry)
                    entry
                } catch (error: Throwable) {
                    treeChildRegistry.forgetTreeChildName(
                        temporaryRoot.tree,
                        createdPendingName
                    )
                    treeChildRegistry.forgetTreeChildName(root.tree, finalName)
                    throw error
                }
                writeSeedMetadataAfterAudioCommit(
                    context = context,
                    root = root,
                    audioName = audioEntry.logicalName,
                    seedMetadataJson = seedMetadataJson
                )
                audioEntry
            }
        }
        if (tempFile.exists() && !tempFile.delete()) {
            NPLogger.w(TAG, "删除下载临时文件失败: ${tempFile.name}")
        }
        if (!updateSnapshotCacheAfterStoredEntryWrite(context, storedEntry, SnapshotEntryBucket.AUDIO)) {
            invalidateSnapshotCache(context)
        }
        seedMetadataJson?.takeUnless { existingAudio != null && !existingAudio.isPendingAudioWrite }
            ?.let { retargetAudioMetadata(it, storedEntry.logicalName) }
            ?.let(::parseDownloadedAudioMetadataJson)
            ?.let { metadata ->
                val metadataEntry = findMetadataForAudioBlocking(context, storedEntry)
                if (metadataEntry == null || !updateSnapshotCacheAfterMetadataWrite(context, metadataEntry, metadata)) {
                    invalidateSnapshotCache(context)
                }
            }
        storedEntry
    }
}

private fun ManagedDownloadStorage.findExistingAudioForCommit(
    context: Context,
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
    expected: ManagedDownloadStorage.DownloadedAudioMetadata?,
    tempFile: File
): StoredEntry? {
    val stableKey = expected?.stableKey?.takeIf(String::isNotBlank) ?: return null
    val operationId = expected.operationId?.takeIf(String::isNotBlank) ?: return null
    val candidates = (snapshot.pendingAudioEntries + snapshot.audioEntriesByStableKey[stableKey].orEmpty())
        .distinctBy(StoredEntry::reference)
        .filter { entry ->
            val metadata = metadataForAudioEntry(snapshot, entry)
            metadata?.stableKey == stableKey && metadata.operationId == operationId
        }
    candidates.forEach { entry ->
        val sameBytes = openCommittedAudioInput(context, entry).use { committed ->
            tempFile.inputStream().use { downloaded -> equalAudioStreams(committed, downloaded) }
        }
        if (sameBytes) return entry
    }
    if (candidates.isNotEmpty()) {
        throw IOException("下载 operation 已有不同内容的音频，保留原凭据: $operationId")
    }
    return null
}

private fun ManagedDownloadStorage.prepareAudioNameAndMetadata(
    context: Context,
    root: RootHandle,
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
    desiredName: String,
    pendingMetadataJson: String?
): String {
    val identity = when (root) {
        is RootHandle.FileRoot -> root.dir.absolutePath
        is RootHandle.TreeRoot -> root.tree.uri.toString()
    }
    val lock = audioNamePreparationLocks[(identity.hashCode() and Int.MAX_VALUE) % audioNamePreparationLocks.size]
    return synchronized(lock) {
        // 完整快照包含 .tmp；新写入由 registry 预留，避免每首都重新枚举整个目录
        val temporaryRoot = resolveTemporaryRoot(context, root, create = false)
        val cachedTemporaryEntries = (temporaryRoot as? RootHandle.TreeRoot)?.let {
            treeChildRegistry.peekTreeChildren(it.tree)?.map(ManagedDownloadStoredEntryMapper::fromTreeChild)
        }
        val temporaryEntries = cachedTemporaryEntries ?: readTemporaryDirectoryEntries(
            context, root, forceRefresh = false
        ).let { listing ->
            if (!listing.isComplete) throw IOException("无法完整核查 pending 凭据，暂停提交")
            listing.entries
        }
        val expectedOwner = pendingMetadataJson?.let(::parseDownloadedAudioMetadataJson)
        val pendingAudioNames = temporaryEntries.filter(StoredEntry::isPendingAudioWrite).mapTo(hashSetOf(), StoredEntry::logicalName)
        val ownedReservation = temporaryEntries.firstOrNull { entry ->
            val audioName = ManagedDownloadTreeNaming.metadataAudioName(entry.name) ?: return@firstOrNull false
            if (audioName in pendingAudioNames || !ManagedDownloadTreeNaming.isPendingMetadataName(entry.name, audioName) ||
                !isPendingAudioPromotionFinalNameCandidate(desiredName, audioName) || expectedOwner?.operationId.isNullOrBlank()
            ) return@firstOrNull false
            val owner = readTextInternal(context, entry.reference)?.let(::parseDownloadedAudioMetadataJson)
            owner?.operationId == expectedOwner.operationId && owner.stableKey == expectedOwner.stableKey
        }?.let { ManagedDownloadTreeNaming.metadataAudioName(it.name) }
        if (ownedReservation != null) {
            val occupied = when (root) {
                is RootHandle.FileRoot -> File(root.dir, ownedReservation).exists()
                is RootHandle.TreeRoot -> treeChildRegistry.cachedTreeChildren(context, root.tree, TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS)
                    .any { ManagedDownloadTreeNaming.isExactTreeStoredName(it.name, ownedReservation) }
            }
            if (occupied) throw IOException("同一 operation 预留名称被占用，保留元信息等待恢复: $ownedReservation")
            when (root) {
                is RootHandle.FileRoot -> treeChildRegistry.rememberFileChildName(root.dir, ownedReservation)
                is RootHandle.TreeRoot -> treeChildRegistry.rememberTreeChildName(root.tree, ownedReservation)
            }
            writeCollisionPendingMetadata(context, root, ownedReservation, pendingMetadataJson)
            return@synchronized ownedReservation
        }
        val reservedNames = snapshot.pendingAudioEntries.map(StoredEntry::logicalName) +
            snapshot.metadataEntriesByAudioName.keys + temporaryEntries.mapNotNull { entry ->
                if (entry.isPendingAudioWrite) entry.logicalName
                else ManagedDownloadTreeNaming.metadataAudioName(entry.name)
            }
        val availableName = ManagedDownloadStorageNaming.createUniqueAudioName(reservedNames, desiredName)
        val finalName = when (root) {
            is RootHandle.FileRoot -> treeChildRegistry.reserveUniqueFileChildName(root.dir, availableName)
            is RootHandle.TreeRoot -> treeChildRegistry.reserveUniqueTreeChildName(context, root.tree, availableName)
        }
        writeCollisionPendingMetadata(context, root, finalName, pendingMetadataJson)
        finalName
    }
}

private fun openCommittedAudioInput(context: Context, entry: StoredEntry): InputStream {
    return if (entry.reference.startsWith("/")) {
        File(entry.reference).inputStream()
    } else {
        context.contentResolver.openInputStream(entry.reference.toUri())
            ?: throw IOException("下载 operation 的已提交音频暂时不可读取: ${entry.name}")
    }
}

private fun equalAudioStreams(first: InputStream, second: InputStream): Boolean {
    val firstBuffer = ByteArray(64 * 1024)
    val secondBuffer = ByteArray(firstBuffer.size)
    while (true) {
        val count = first.read(firstBuffer)
        if (count < 0) return second.read() < 0
        if (count == 0) {
            val single = first.read()
            if (single != second.read()) return false
            if (single < 0) return true
            continue
        }
        var read = 0
        while (read < count) {
            val current = second.read(secondBuffer, read, count - read)
            if (current < 0) return false
            if (current == 0) {
                val single = second.read()
                if (single < 0) return false
                secondBuffer[read++] = single.toByte()
            } else {
                read += current
            }
        }
        for (index in 0 until count) if (firstBuffer[index] != secondBuffer[index]) return false
    }
}

private fun retargetAudioMetadata(content: String, audioName: String): String =
    JSONObject(content).put("audioFileName", audioName).toString()

internal fun ManagedDownloadStorage.promoteFileTargetWithoutReplacement(
    pending: File,
    target: File,
    displayName: String,
    onTargetCreated: ((FileDescriptor) -> Unit)? = null,
    verifyCommittedTarget: ((File) -> Boolean)? = null
) {
    if (target.exists()) throw IOException("下载目标已存在，保留 pending 文件: $displayName")
    try {
        // CREATE_NEW/O_EXCL 把占位和打开文件合成一个动作，不能用 ATOMIC_MOVE 覆盖后到的文件
        if (onTargetCreated != null) {
            val descriptor = Os.open(target.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            FileOutputStream(descriptor).use { output ->
                val identity = Os.fstat(descriptor).let { "${it.st_dev}:${it.st_ino}" }
                try {
                    onTargetCreated(descriptor)
                } catch (error: Throwable) {
                    // 凭据未保存时只清理本次独占创建的 inode，避免留下无法恢复的空目标
                    if (runCatching { publicationFileIdentity(target.absolutePath) }.getOrNull() == identity) {
                        if (!target.delete() && target.exists()) {
                            error.addSuppressed(IOException("发布凭据失败后无法清理新目标: $displayName"))
                        }
                    }
                    throw error
                }
                pending.inputStream().use { it.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES) }
                output.fd.sync()
                if (publicationFileIdentity(target.absolutePath) != identity) throw IOException("发布目标身份发生变化: $displayName")
            }
        } else {
            FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                pending.inputStream().use { it.copyTo(Channels.newOutputStream(output), STREAM_COPY_BUFFER_SIZE_BYTES) }
                output.force(true)
            }
        }
        val identical = verifyCommittedTarget?.invoke(target)
            ?: pending.inputStream().use { source -> target.inputStream().use { equalAudioStreams(source, it) } }
        if (!identical) throw IOException("发布目标内容未确认，保留 pending 文件: $displayName")
        deletePendingFileAndConfirm(pending)?.let { throw IOException("发布完成但 pending 清理未确认: $displayName", it) }
    } catch (error: FileAlreadyExistsException) {
        throw IOException("下载目标已存在，保留 pending 文件: $displayName", error)
    } catch (error: Exception) {
        throw IOException("无法提交下载文件，保留恢复凭据: $displayName", error)
    }
}

internal fun ManagedDownloadStorage.deletePendingFileAndConfirm(pending: File): Throwable? {
    return try {
        when {
            !pending.exists() -> null
            !pending.isFile -> IllegalStateException(
                "pending 音频不是普通文件: ${pending.name}"
            )
            !pending.delete() && pending.exists() -> IllegalStateException(
                "pending 音频删除未确认: ${pending.name}"
            )
            pending.exists() -> IllegalStateException(
                "pending 音频删除后仍存在: ${pending.name}"
            )
            else -> null
        }
    } catch (error: Throwable) {
        error
    }
}

internal suspend fun ManagedDownloadStorage.writeSafFileThroughBackend(
    context: Context,
    parent: DocumentFile,
    displayName: String,
    mimeType: String,
    expectedSizeBytes: Long,
    sourceFile: File
): StoredEntry {
    val backend = SafStorageBackend(context)
    val result = backend.writeRecoverable(
        target = StorageTarget.SafTarget(
            parent = StorageReference.SafRef(parent.uri),
            displayName = displayName,
            mimeType = mimeType
        )
    ) { output ->
        sourceFile.inputStream().use { input ->
            input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
        }
    }
    val stat = when (result) {
        is StorageWriteResult.Written -> result.stat
        StorageWriteResult.Missing -> throw IOException("SAF 目标不存在: $displayName")
        StorageWriteResult.OutOfScope -> throw IOException("SAF 目标越界: $displayName")
        StorageWriteResult.PermissionLost -> throw SecurityException("SAF 写入权限丢失: $displayName")
        is StorageWriteResult.ProviderFailure -> throw IOException(
            "SAF 写入失败: $displayName",
            result.error
        )
        is StorageWriteResult.Unsupported -> throw IOException(
            "SAF 不支持写入: $displayName (${result.operation})"
        )
    }
    val verifiedSize = stat.sizeBytes ?: when (val measured = backend.read(stat.reference) { input ->
        ManagedDownloadCommitIo.countInputStreamBytes(
            input,
            STREAM_COPY_BUFFER_SIZE_BYTES
        )
    }) {
        is StorageLookupResult.Found -> measured.value
        StorageLookupResult.Missing -> throw IOException(
            "SAF 写入目标在读回时不存在: $displayName"
        )
        StorageLookupResult.PermissionLost -> throw SecurityException(
            "SAF 写入目标读回权限丢失: $displayName"
        )
        is StorageLookupResult.ProviderFailure -> throw IOException(
            "SAF 写入目标读回失败: $displayName",
            measured.error
        )
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.Unsupported -> throw IOException(
            "SAF 写入目标不可读: $displayName"
        )
    }
    if (verifiedSize != expectedSizeBytes) {
        throw IOException(
            "SAF 写入大小不匹配: $displayName, expected=$expectedSizeBytes, " +
                "actual=$verifiedSize"
        )
    }
    return stat.toStoredEntryForBackend(fileRoot = null)
        .copy(sizeBytes = verifiedSize)
}

internal suspend fun ManagedDownloadStorage.promotePendingAudio(
    context: Context,
    root: RootHandle,
    audio: StoredEntry,
    finalAudioName: String = audio.logicalName
): StoredEntry? {
    if (!audio.isPendingAudioWrite) return audio
    val finalName = finalAudioName.takeIf(String::isNotBlank)
        ?.takeIf { candidate ->
            isPendingAudioPromotionFinalNameCandidate(
                requestedName = audio.logicalName,
                candidateName = candidate
            )
        }
        ?: return null
    return when (root) {
        is RootHandle.FileRoot -> {
            val pendingFile = File(audio.reference)
            val pendingRoot = pendingFile.parentFile
                ?.takeIf { it.isDirectory }
                ?: root.dir
            val target = File(root.dir, finalName)
            val lock = audioPublicationLocks[(target.absolutePath.hashCode() and Int.MAX_VALUE) % audioPublicationLocks.size]
            lock.withLock {
                if (target.exists()) {
                    if (!isVerifiedAudioPublicationTarget(context, root, audio.name, finalName, target.absolutePath, audio.reference) &&
                        !resumeAudioPublicationCopy(context, root, audio.name, finalName, target.absolutePath, audio.reference)
                    ) {
                        throw IOException("下载目标缺少可验证的发布凭据，保留目标和 pending: $finalName")
                    }
                    deletePendingFileAndConfirm(pendingFile)?.let { throw IOException("已确认发布目标但 pending 清理失败", it) }
                    target.toStoredEntry()
                } else if (!pendingFile.isFile) {
                    null
                } else {
                    markAudioPublicationPending(context, root, audio, finalName)
                    promoteFileTargetWithoutReplacement(
                        pending = File(pendingRoot, audio.name), target = target, displayName = finalName,
                        onTargetCreated = { descriptor ->
                            val identity = Os.fstat(descriptor).let { "${it.st_dev}:${it.st_ino}" }
                            recordAudioPublicationTarget(context, root, audio, target.absolutePath, identity, finalName)
                        },
                        verifyCommittedTarget = { isVerifiedAudioPublicationTarget(context, root, audio.name, finalName, it.absolutePath, audio.reference) }
                    )
                    target.toStoredEntry()
                }
            }
        }

        is RootHandle.TreeRoot -> audioPublicationLocks[
            ("${root.tree.uri}|${ManagedDownloadTreeNaming.canonicalLookupName(finalName)}"
                .hashCode() and Int.MAX_VALUE) % audioPublicationLocks.size
        ].withLock {
            val pendingUri = audio.reference.toUri()
            val pendingBackend = SafStorageBackend(context)
            val initialPendingReference = StorageReference.SafRef(pendingUri)
            val pendingStat = pendingBackend.stat(initialPendingReference)
            val rootChildren = treeChildRegistry.peekTreeChildren(root.tree)
                ?: treeChildRegistry.cachedTreeChildren(
                    context = context,
                    parent = root.tree,
                    maxCacheAgeMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
                )
            val pendingIsDirectRootChild = treeChildRegistry.peekTreeChildByReference(
                parent = root.tree,
                reference = pendingUri.toString()
            )?.isDirectory == false
            val exactNamedTarget = treeChildRegistry.peekTreeChild(root.tree, finalName)
                ?.takeIf { child -> !child.isDirectory }
            val exactTargetCandidates = exactNamedTarget?.let(::listOf) ?: rootChildren.filter { child ->
                !child.isDirectory &&
                    ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
            }
            val hasPromotionBackup = exactTargetCandidates.isNotEmpty() && rootChildren.any { child ->
                isTreePromotionBackupName(child.name, finalName)
            }
            if (exactTargetCandidates.size == 1 && !hasPromotionBackup) {
                val expectedRecoverySizeBytes = when (pendingStat) {
                    is StorageLookupResult.Found -> {
                        pendingStat.value
                            .takeUnless(StorageStat::isDirectory)
                            ?.let {
                                resolveCurrentTreePendingAudioSize(
                                    backend = pendingBackend,
                                    reference = initialPendingReference,
                                    reportedSizeBytes = pendingStat.value.sizeBytes,
                                    description = audio.name
                                )
                            }
                    }

                    StorageLookupResult.Missing -> audio.sizeBytes.takeIf { it > 0L }
                    StorageLookupResult.PermissionLost,
                    is StorageLookupResult.ProviderFailure,
                    StorageLookupResult.OutOfScope,
                    is StorageLookupResult.Unsupported -> null
                }
                if (expectedRecoverySizeBytes != null) {
                    val recoveryPendingUri = (pendingStat as? StorageLookupResult.Found)
                        ?.value
                        ?.takeUnless(StorageStat::isDirectory)
                        ?.let { pendingUri }
                    val recoveryPendingParent = if (pendingIsDirectRootChild) {
                        root.tree
                    } else {
                        (resolveTemporaryRoot(context, root, create = false)
                            as? RootHandle.TreeRoot)?.tree
                    }
                    val recovered = ManagedDownloadTreeMutationLocks.withLock(root.tree.uri) {
                        val refreshed = treeChildRegistry.treeChildrenForWrite(
                            context,
                            root.tree
                        )
                        reconcileExistingTreePromotionTargetLocked(
                            context = context,
                            root = root,
                            refresh = refreshed,
                            targetUri = exactTargetCandidates.single().documentUri,
                            pendingUri = recoveryPendingUri,
                            pendingName = audio.name,
                            pendingParent = recoveryPendingParent,
                            finalName = finalName,
                            expectedSizeBytes = expectedRecoverySizeBytes,
                            fallbackLastModifiedMs = System.currentTimeMillis()
                        )
                    }
                    if (recovered != null) {
                        return recovered
                    }
                }
            }
            val pending = when (pendingStat) {
                is StorageLookupResult.Found -> pendingStat.value
                    .takeUnless(StorageStat::isDirectory)
                    ?.let {
                        if (pendingIsDirectRootChild) {
                            resolvePendingTreeDocument(
                                context = context,
                                parent = root.tree,
                                uri = pendingUri
                            )
                        } else {
                            resolvePendingTemporaryTreeDocument(
                                context = context,
                                root = root,
                                pendingUri = pendingUri,
                                pendingName = audio.name
                            )
                        }
                    }
                StorageLookupResult.Missing -> null
                StorageLookupResult.PermissionLost -> throw SecurityException(
                    "SAF pending 音频权限丢失: ${audio.name}"
                )
                is StorageLookupResult.ProviderFailure -> throw pendingStat.error
                StorageLookupResult.OutOfScope,
                is StorageLookupResult.Unsupported -> null
            }
                ?: treeChildRegistry.cachedTreeChildren(
                    context = context,
                    parent = root.tree,
                    maxCacheAgeMs = 0L
                ).firstOrNull { child ->
                    !child.isDirectory && child.name == audio.name
                }?.let { child ->
                    treeChildRegistry.toDocumentFile(context, root.tree, child)
                }
                ?: return null
            val pendingReference = StorageReference.SafRef(pending.uri)
            val pendingSizeStat = if (pending.uri == pendingUri) {
                pendingStat
            } else {
                pendingBackend.stat(pendingReference)
            }
            val pendingReportedSizeBytes = (pendingSizeStat as? StorageLookupResult.Found)
                ?.value
                ?.sizeBytes
            val committedAtMs = System.currentTimeMillis()
            // 标签回写会改变长度，提升必须重新核查当前 pending 内容
            val expectedSizeBytes = resolveCurrentTreePendingAudioSize(
                    backend = pendingBackend,
                    reference = pendingReference,
                    reportedSizeBytes = pendingReportedSizeBytes,
                    description = audio.name
                )
            val treePending = if (pendingIsDirectRootChild) {
                treeChildRegistry.toTreeDocumentFile(
                    context = context,
                    parent = root.tree,
                    child = pending
                )
            } else {
                pending
            }
            val renamedDocument = if (pendingIsDirectRootChild) {
                renameTreeDocumentWithoutReplacing(
                    context = context,
                    parent = root.tree,
                    document = treePending,
                    finalName = finalName
                )
            } else {
                null
            }
            if (renamedDocument != null) {
                val renamedTarget = treeChildRegistry.toTreeDocumentFileOrEnumerated(
                    context = context,
                    parent = root.tree,
                    child = renamedDocument
                ) ?: renamedDocument
                return verifiedTreeStoredEntry(
                    context = context,
                    target = renamedTarget,
                    expectedName = finalName,
                    expectedSizeBytes = expectedSizeBytes,
                    fallbackLastModifiedMs = committedAtMs,
                    description = finalName
                ).also {
                    treeChildRegistry.forgetTreeChildName(root.tree, audio.name)
                    treeChildRegistry.rememberTreeChild(root.tree, it)
                }
            }
            val resolvedPendingAudio = audio.copy(
                reference = pending.uri.toString(),
                mediaUri = pending.uri.toString(),
                localFilePath = null,
                sizeBytes = expectedSizeBytes,
                sizeKnown = true
            )
            markAudioPublicationPending(context, root, resolvedPendingAudio, finalName)
            copyPendingTreeAudioWithoutReplacing(
                context = context,
                root = root,
                pending = treePending ?: pending,
                pendingName = audio.name,
                finalName = finalName,
                expectedSizeBytes = expectedSizeBytes,
                fallbackLastModifiedMs = committedAtMs,
                pendingParent = if (pendingIsDirectRootChild) {
                    root.tree
                } else {
                    (resolveTemporaryRoot(context, root, create = false)
                        as? RootHandle.TreeRoot)?.tree
                }
            )
        }
    }
}

internal fun ManagedDownloadStorage.resolvePendingTemporaryTreeDocument(
    context: Context,
    root: RootHandle.TreeRoot,
    pendingUri: android.net.Uri,
    pendingName: String
): DocumentFile? {
    val temporaryRoot = resolveTemporaryRoot(
        context = context,
        root = root,
        create = false
    ) as? RootHandle.TreeRoot ?: return null
    treeChildRegistry.peekTreeChildByReference(
        parent = temporaryRoot.tree,
        reference = pendingUri.toString(),
        includeIncomplete = true
    )?.takeIf { child -> !child.isDirectory }
        ?.let { child ->
            return treeChildRegistry.toDocumentFile(
                context = context,
                parent = temporaryRoot.tree,
                child = child
            )
        }
    treeChildRegistry.peekTreeChildIncludingIncomplete(temporaryRoot.tree, pendingName)
        ?.takeIf { child -> !child.isDirectory && sameTreeDocument(child.documentUri, pendingUri) }
        ?.let { child ->
            return treeChildRegistry.toDocumentFile(
                context = context,
                parent = temporaryRoot.tree,
                child = child
            )
        }
    val cached = treeChildRegistry.cachedTreeChildren(
        context = context,
        parent = temporaryRoot.tree,
        maxCacheAgeMs = 0L
    )
    val child = cached.firstOrNull { candidate ->
        !candidate.isDirectory &&
            candidate.name == pendingName &&
            sameTreeDocument(candidate.documentUri, pendingUri)
    } ?: cached.firstOrNull { candidate ->
        !candidate.isDirectory && sameTreeDocument(candidate.documentUri, pendingUri)
    } ?: return null
    return treeChildRegistry.toDocumentFile(
        context = context,
        parent = temporaryRoot.tree,
        child = child
    )
}

internal suspend fun ManagedDownloadStorage.resolveCurrentTreePendingAudioSize(
    backend: SafStorageBackend,
    reference: StorageReference.SafRef,
    reportedSizeBytes: Long?,
    description: String
): Long {
    val countedSizeBytes = when (val read = backend.read(reference) { input ->
        ManagedDownloadCommitIo.countInputStreamBytes(
            input,
            STREAM_COPY_BUFFER_SIZE_BYTES
        )
    }) {
        is StorageLookupResult.Found -> read.value
        StorageLookupResult.Missing -> throw IOException(
            "SAF pending 音频在提升前不存在: $description"
        )
        StorageLookupResult.PermissionLost -> throw SecurityException(
            "SAF pending 音频提升前权限丢失: $description"
        )
        is StorageLookupResult.ProviderFailure -> throw IOException(
            "SAF pending 音频提升前读回失败: $description",
            read.error
        )
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.Unsupported -> throw IOException(
            "SAF pending 音频提升前不可读: $description"
        )
    }
    val expectedSizeBytes = resolvePendingTreeAudioPromotionExpectedSize(
        reportedSizeBytes = reportedSizeBytes,
        countedSizeBytes = countedSizeBytes
    ) ?: throw IOException("SAF pending 音频提升前为空: $description")
    if (reportedSizeBytes != null && reportedSizeBytes != countedSizeBytes) {
        NPLogger.w(
            TAG,
            "SAF pending 音频报告大小与读回大小不一致，使用读回值: " +
                "$description, reported=$reportedSizeBytes, counted=$countedSizeBytes"
        )
    }
    return expectedSizeBytes
}

internal suspend fun ManagedDownloadStorage.demotePublishedTreeAudioToTemporary(
    context: Context,
    root: RootHandle.TreeRoot,
    audio: StoredEntry,
    pendingName: String,
    temporaryRoot: RootHandle.TreeRoot
): StoredEntry? {
    val sourceUri = runCatching { audio.reference.toUri() }.getOrNull() ?: return null
    val backend = SafStorageBackend(context)
    val copied = backend.read(StorageReference.SafRef(sourceUri)) { input ->
        val result = backend.writeRecoverable(
            target = StorageTarget.SafTarget(
                parent = StorageReference.SafRef(temporaryRoot.tree.uri),
                displayName = pendingName,
                mimeType = mimeTypeFromName(pendingName, null)
            )
        ) { output ->
            input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
        }
        when (result) {
            is StorageWriteResult.Written -> result.stat.toStoredEntryForBackend(null)
            StorageWriteResult.Missing -> throw IOException(
                "SAF 已发布音频复制源不存在: ${audio.name}"
            )
            StorageWriteResult.OutOfScope -> throw IOException(
                "SAF 已发布音频复制目标越界: ${audio.name}"
            )
            StorageWriteResult.PermissionLost -> throw SecurityException(
                "SAF 已发布音频复制权限丢失: ${audio.name}"
            )
            is StorageWriteResult.ProviderFailure -> throw IOException(
                "SAF 已发布音频复制失败: ${audio.name}",
                result.error
            )
            is StorageWriteResult.Unsupported -> throw IOException(
                "SAF 已发布音频复制不支持: ${audio.name}"
            )
        }
    }
    val copiedEntry = when (copied) {
        is StorageLookupResult.Found -> copied.value
        StorageLookupResult.Missing -> return null
        StorageLookupResult.PermissionLost -> throw SecurityException(
            "SAF 已发布音频读权限丢失: ${audio.name}"
        )
        is StorageLookupResult.ProviderFailure -> throw IOException(
            "SAF 已发布音频读取失败: ${audio.name}",
            copied.error
        )
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.Unsupported -> return null
    }
    val deleted = deleteTrustedReference(
        context,
        TrustedManagedRef(
            reference = StorageReference.SafRef(sourceUri),
            externalReference = sourceUri.toString()
        )
    ).isConfirmedStorageMutation()
    if (!deleted) {
        deleteTrustedReference(
            context,
            TrustedManagedRef(
                reference = StorageReference.SafRef(copiedEntry.reference.toUri()),
                externalReference = copiedEntry.reference
            )
        )
        throw IOException("SAF 已发布音频回退后源清理未确认: ${audio.name}")
    }
    treeChildRegistry.forgetTreeChildName(root.tree, audio.name)
    treeChildRegistry.rememberTreeChild(temporaryRoot.tree, copiedEntry)
    return copiedEntry
}

internal fun ManagedDownloadStorage.reconcileExistingTreePromotionTargetLocked(
    context: Context,
    root: RootHandle.TreeRoot,
    refresh: ManagedDownloadTreeChildRegistry.TreeChildrenRefresh,
    targetUri: Uri,
    pendingUri: Uri?,
    pendingName: String,
    pendingParent: DocumentFile?,
    finalName: String,
    expectedSizeBytes: Long,
    fallbackLastModifiedMs: Long
): StoredEntry? {
    if (!refresh.isComplete) {
        NPLogger.w(
            TAG,
            "SAF 提升恢复跳过不完整目录枚举，保留 pending 音频: $finalName"
        )
        return null
    }
    val exactTargets = refresh.children.filter { child ->
        ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
    }
    if (exactTargets.size != 1) {
        return null
    }
    val exactTarget = exactTargets.single()
    if (
        exactTarget.isDirectory ||
            !sameTreeDocument(exactTarget.documentUri, targetUri) ||
            refresh.children.any { child ->
                isTreePromotionBackupName(child.name, finalName)
            }
    ) {
        return null
    }
    val sameDocument = pendingUri != null && samePublicationReference(pendingUri.toString(), targetUri.toString())
    val verifiedPublication = !sameDocument && isVerifiedAudioPublicationTarget(
        context, root, pendingName, finalName, targetUri.toString(), pendingUri?.toString()
    )
    if (!sameDocument && !verifiedPublication &&
        (pendingUri == null || !resumeAudioPublicationCopy(context, root, pendingName, finalName, targetUri.toString(), pendingUri.toString()))
    ) {
        NPLogger.w(TAG, "SAF 目标缺少可验证发布凭据，保留目标和 pending: $finalName")
        return null
    }
    val target = resolveNewTreePromotionDocument(
        context = context,
        parent = root.tree,
        uri = exactTarget.documentUri
    ) ?: return null
    val entry = try {
        // 旧引用可能仍带标签写入前的长度，只有强凭据已确认目标时才能以实际内容为准
        val verifiedSizeBytes = if (pendingUri == null && verifiedPublication) {
            val input = context.contentResolver.openInputStream(targetUri)
                ?: throw IOException("已确认发布目标暂时不可读: $finalName")
            input.use { ManagedDownloadCommitIo.countInputStreamBytes(it, STREAM_COPY_BUFFER_SIZE_BYTES) }
                .takeIf { it > 0L } ?: throw IOException("已确认发布目标为空: $finalName")
        } else expectedSizeBytes
        verifiedTreeStoredEntry(
            context = context,
            target = target,
            expectedName = finalName,
            expectedSizeBytes = verifiedSizeBytes,
            fallbackLastModifiedMs = fallbackLastModifiedMs,
            description = finalName
        )
    } catch (error: SecurityException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "SAF 提升恢复目标校验失败，保留目标和 pending: " +
                "name=$finalName, error=${error.message}",
            error
        )
        return null
    }
    if (pendingUri != null && !sameTreeDocument(pendingUri, target.uri)) {
        val pendingDeleted = deleteTrustedReference(
            context,
            TrustedManagedRef(
                reference = StorageReference.SafRef(pendingUri),
                externalReference = pendingUri.toString()
            )
        ).isConfirmedStorageMutation()
        if (pendingDeleted) {
            treeChildRegistry.forgetTreeChildName(
                pendingParent ?: root.tree,
                pendingName
            )
        } else {
            NPLogger.w(
                TAG,
                "SAF 提升恢复目标已确认但 pending 清理未确认，保留下次重试: " +
                    "name=$pendingName"
            )
        }
    }
    treeChildRegistry.rememberTreeChild(root.tree, entry)
    NPLogger.d(
        TAG,
        "SAF 提升复用了已提交目标，跳过重复复制: name=$finalName"
    )
    return entry
}

private data class PreparedTreeAudioPublication(
    val created: DocumentFile? = null,
    val recovered: StoredEntry? = null
)

internal suspend fun ManagedDownloadStorage.copyPendingTreeAudioWithoutReplacing(
    context: Context,
    root: RootHandle.TreeRoot,
    pending: DocumentFile,
    pendingName: String,
    finalName: String,
    expectedSizeBytes: Long,
    fallbackLastModifiedMs: Long,
    pendingParent: DocumentFile? = null
): StoredEntry? {
    val backend = SafStorageBackend(context)
    val copied = backend.read(StorageReference.SafRef(pending.uri)) { source ->
        val prepared = ManagedDownloadTreeMutationLocks.withLock(root.tree.uri) {
            fun refreshAndReconcileExistingTarget(): StoredEntry? {
                val refreshed = treeChildRegistry.treeChildrenForWrite(context, root.tree)
                val exactTarget = refreshed.children
                    .filter { child ->
                        ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
                    }
                    .singleOrNull()
                    ?: return null
                return reconcileExistingTreePromotionTargetLocked(
                    context = context,
                    root = root,
                    refresh = refreshed,
                    targetUri = exactTarget.documentUri,
                    pendingUri = pending.uri,
                    pendingName = pendingName,
                    pendingParent = pendingParent,
                    finalName = finalName,
                    expectedSizeBytes = expectedSizeBytes,
                    fallbackLastModifiedMs = fallbackLastModifiedMs
                )
            }

            val beforeCreate = treeChildRegistry.peekTreeChildren(root.tree)?.let { children ->
                ManagedDownloadTreeChildRegistry.TreeChildrenRefresh(
                    children = children.toList(),
                    isComplete = true
                )
            } ?: treeChildRegistry.treeChildrenForWrite(context, root.tree)
            val recovered = beforeCreate.children
                .filter { child ->
                    ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, finalName)
                }
                .singleOrNull()
                ?.let { existingTarget ->
                    reconcileExistingTreePromotionTargetLocked(
                        context = context,
                        root = root,
                        refresh = beforeCreate,
                        targetUri = existingTarget.documentUri,
                        pendingUri = pending.uri,
                        pendingName = pendingName,
                        pendingParent = pendingParent,
                        finalName = finalName,
                        expectedSizeBytes = expectedSizeBytes,
                        fallbackLastModifiedMs = fallbackLastModifiedMs
                    )
                }
            if (recovered != null) {
                return@withLock PreparedTreeAudioPublication(recovered = recovered)
            }
            if (!canCreateTreePromotionTargetWithoutReplacing(
                    enumerationComplete = beforeCreate.isComplete,
                    existingNames = beforeCreate.children.map(QueriedTreeChild::name),
                    targetName = finalName
                )
            ) {
                NPLogger.w(
                    TAG,
                    "SAF 提升目标不可安全创建，保留 pending 音频: $finalName"
                )
                return@withLock null
            }
            val existingDocumentUris = beforeCreate.children.map(QueriedTreeChild::documentUri)
            val createdUri = try {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    root.tree.uri,
                    ManagedDownloadTreeNaming.documentCreateMimeType(
                        finalName,
                        mimeTypeFromName(finalName, null)
                    ),
                    finalName
                )
            } catch (error: SecurityException) {
                throw error
            } catch (error: UnsupportedOperationException) {
                NPLogger.w(TAG, "SAF 不支持无覆写提升创建: $finalName", error)
                return@withLock null
            } catch (error: Throwable) {
                throw IOException("SAF 无覆写提升创建失败: $finalName", error)
            }
            if (createdUri == null) {
                val recoveredAfterCollision = refreshAndReconcileExistingTarget()
                return@withLock recoveredAfterCollision?.let { recoveredEntry ->
                    PreparedTreeAudioPublication(recovered = recoveredEntry)
                }
            }
            val created = resolveNewTreePromotionDocument(
                context = context,
                parent = root.tree,
                uri = createdUri
            )
            if (created == null) {
                if (existingDocumentUris.none { uri -> sameTreeDocument(uri, createdUri) }) {
                    discardNewTreePromotionTarget(context, root.tree, finalName, createdUri)
                }
                return@withLock null
            }
            if (existingDocumentUris.any { uri -> sameTreeDocument(uri, created.uri) }) {
                NPLogger.w(TAG, "SAF 提升创建返回已有文件，保留 pending 音频: $finalName")
                return@withLock null
            }
            if (created.isDirectory) {
                discardNewTreePromotionTarget(context, root.tree, finalName, created.uri)
                NPLogger.w(TAG, "SAF 提升创建了目录而非音频文件: $finalName")
                return@withLock null
            }
            if (!ManagedDownloadTreeNaming.isExactTreeStoredName(created.name, finalName)) {
                discardNewTreePromotionTarget(context, root.tree, created.name ?: finalName, created.uri)
                val recoveredAfterCollision = refreshAndReconcileExistingTarget()
                if (recoveredAfterCollision != null) {
                    return@withLock PreparedTreeAudioPublication(
                        recovered = recoveredAfterCollision
                    )
                }
                NPLogger.w(
                    TAG,
                    "SAF 提升创建返回非目标名称，保留 pending 音频: " +
                        "expected=$finalName, actual=${created.name}"
                )
                return@withLock null
            }
            // createDocument 返回新文档身份，且上面已经核对 Provider 实际保存的名称
            // 同一目标由 publication lock 串行，避免在每首歌发布时再次枚举整个根目录
            PreparedTreeAudioPublication(created = created)
        } ?: return@read null
        prepared.recovered?.let { return@read it }
        val created = requireNotNull(prepared.created)
        // 目标身份已在目录锁内确认，同目标由 publication lock 串行
        // 大文件复制和完整哈希不应阻塞同目录中其它歌曲的写入
        try {
            recordAudioPublicationTarget(
                context, root,
                StoredEntry(pendingName, pending.uri.toString(), pending.uri.toString(), null, expectedSizeBytes, fallbackLastModifiedMs),
                created.uri.toString(), targetName = finalName
            )
            val output = context.contentResolver.openOutputStream(created.uri, "w")
                ?: throw IOException("SAF final 音频不可写: $finalName")
            output.use { target ->
                val copiedBytes = source.copyTo(target, STREAM_COPY_BUFFER_SIZE_BYTES)
                if (copiedBytes != expectedSizeBytes) {
                    throw IOException(
                        "SAF pending 音频复制长度不匹配: $pendingName, " +
                            "expected=$expectedSizeBytes, actual=$copiedBytes"
                    )
                }
            }
            val entry = verifiedTreeStoredEntry(
                context = context,
                target = created,
                expectedName = finalName,
                expectedSizeBytes = expectedSizeBytes,
                fallbackLastModifiedMs = fallbackLastModifiedMs,
                description = finalName
            )
            if (!isVerifiedAudioPublicationTarget(context, root, pendingName, finalName, created.uri.toString(), pending.uri.toString())) {
                throw IOException("SAF 发布目标内容凭据校验失败: $finalName")
            }
            val pendingDeleted = deleteTrustedReference(
                context,
                TrustedManagedRef(
                    reference = StorageReference.SafRef(pending.uri),
                    externalReference = pending.uri.toString()
                )
            ).isConfirmedStorageMutation()
            if (pendingDeleted) {
                treeChildRegistry.forgetTreeChildName(
                    pendingParent ?: root.tree,
                    pendingName
                )
            } else {
                NPLogger.w(TAG, "音频已提升但 pending 文件清理失败: $pendingName")
            }
            treeChildRegistry.rememberTreeChild(root.tree, entry)
            entry
        } catch (error: Throwable) {
            discardNewTreePromotionTarget(context, root.tree, finalName, created.uri)
            throw error
        }
    }
    return when (copied) {
        is StorageLookupResult.Found -> copied.value
        StorageLookupResult.Missing -> throw IOException(
            "SAF pending 音频在提升时不存在: $pendingName"
        )
        StorageLookupResult.PermissionLost -> throw SecurityException(
            "SAF pending 音频提升时权限丢失: $pendingName"
        )
        is StorageLookupResult.ProviderFailure -> {
            if (copied.error is SecurityException) {
                throw copied.error
            }
            throw IOException("SAF pending 音频提升时读取失败: $pendingName", copied.error)
        }
        StorageLookupResult.OutOfScope,
        is StorageLookupResult.Unsupported -> throw IOException(
            "SAF pending 音频提升时不可读: $pendingName"
        )
    }
}

internal fun ManagedDownloadStorage.discardNewTreePromotionTarget(
    context: Context,
    parent: DocumentFile,
    childName: String,
    uri: Uri
) {
    val deleted = deleteTrustedReference(
        context,
        TrustedManagedRef(
            reference = StorageReference.SafRef(uri),
            externalReference = uri.toString()
        )
    ).isConfirmedStorageMutation()
    if (!deleted) {
        NPLogger.w(TAG, "SAF 提升临时目标清理失败: $childName")
    }
    treeChildRegistry.forgetTreeChildName(parent, childName)
    invalidateSnapshotCache(context)
}

internal fun ManagedDownloadStorage.isTreePromotionBackupName(actualName: String, targetName: String): Boolean {
    val directBackupName = ".${targetName}.backup"
    if (ManagedDownloadTreeNaming.isExactTreeStoredName(actualName, directBackupName)) {
        return true
    }
    if (!actualName.startsWith('.') || !actualName.endsWith(".backup", ignoreCase = true)) {
        return false
    }
    val baseWithIdentifier = actualName
        .drop(1)
        .dropLast(".backup".length)
    val separatorIndex = baseWithIdentifier.lastIndexOf('.')
    if (separatorIndex <= 0) {
        return false
    }
    val backedUpName = baseWithIdentifier.substring(0, separatorIndex)
    if (!ManagedDownloadTreeNaming.isExactTreeStoredName(backedUpName, targetName)) {
        return false
    }
    val identifier = baseWithIdentifier.substring(separatorIndex + 1)
    return runCatching { UUID.fromString(identifier) }.isSuccess
}

internal fun ManagedDownloadStorage.sameTreeDocument(first: Uri, second: Uri): Boolean {
    if (first == second) return true
    val firstId = treeDocumentIdOrNull(first)
    val secondId = treeDocumentIdOrNull(second)
    return firstId != null && firstId == secondId
}

internal fun ManagedDownloadStorage.treeDocumentIdOrNull(uri: Uri): String? {
    return try {
        DocumentsContract.getDocumentId(uri)
    } catch (error: SecurityException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

internal fun ManagedDownloadStorage.writeSeedMetadataAfterAudioCommit(
    context: Context,
    root: RootHandle,
    audioName: String,
    seedMetadataJson: String?
) {
    val incoming = seedMetadataJson?.takeIf(String::isNotBlank)
        ?.let { retargetAudioMetadata(it, audioName) } ?: return
    val content = preserveAudioPublicationReceipt(readAudioPublicationMetadata(context, root, audioName)?.toString(), incoming)
    val temporaryRoot = resolveTemporaryRoot(context, root, create = true)
        ?: throw IOException("无法保存 core pending 元信息")
    val pendingWritten = writeRootText(
        context = context,
        root = temporaryRoot,
        displayName = "$audioName$PENDING_METADATA_SUFFIX",
        content = content
    ) ?: throw IOException("无法确认 core pending 元信息: $audioName")
    if (readTextInternal(context, pendingWritten.reference) != content) {
        throw IOException("core pending 元信息读回不一致: $audioName")
    }
    try {
        writeRootText(
            context = context,
            root = root,
            displayName = "$audioName$METADATA_SUFFIX",
            content = content
        )
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "音频已提交但 seed metadata 写入失败，保留音频等待收尾重试: " +
                "audio=$audioName, error=${error.message}",
            error
        )
    }
}

internal fun ManagedDownloadStorage.writeCollisionPendingMetadata(
    context: Context,
    root: RootHandle,
    actualAudioName: String,
    pendingMetadataJson: String?
) {
    val content = pendingMetadataJson?.takeIf(String::isNotBlank)
        ?.let { retargetAudioMetadata(it, actualAudioName) } ?: return
    val temporaryRoot = resolveTemporaryRoot(
        context = context,
        root = root,
        create = true
    ) ?: throw IOException("无法准备下载 .tmp 目录")
    val metadataEntry = writeRootText(
        context = context,
        root = temporaryRoot,
        displayName = "$actualAudioName$PENDING_METADATA_SUFFIX",
        content = content
    )
    if (metadataEntry == null || readTextInternal(context, metadataEntry.reference) != content) {
        throw IOException(
            "无法为冲突后的下载音频写入 pending metadata: $actualAudioName"
        )
    }
}

internal fun ManagedDownloadStorage.saveLyricTextBlocking(context: Context, displayName: String, content: String): String? {
    return writeSubdirectoryBytesBlocking(
        context = context,
        subdirectory = LYRIC_SUBDIRECTORY,
        displayName = displayName,
        bytes = content.toByteArray(Charsets.UTF_8),
        mimeType = mimeTypeFromName(displayName, null)
    )?.reference
}
