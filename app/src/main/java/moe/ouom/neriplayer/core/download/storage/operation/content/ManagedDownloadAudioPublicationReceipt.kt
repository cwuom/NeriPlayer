package moe.ouom.neriplayer.core.download.storage.operation.content

import android.content.Context
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.storage.PENDING_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_TEMPORARY_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.findExistingTemporaryTreeDirectory
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.findMetadataForAudioBlocking
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.resolveTemporaryRoot
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle as RootHandle
import org.json.JSONException
import org.json.JSONObject

private const val PUBLICATION_RECEIPT_KEY = "audioPublicationReceipt"
private const val PUBLICATION_PENDING_KEY = "audioPublicationPending"

internal fun ManagedDownloadStorage.markAudioPublicationPending(
    context: Context,
    root: RootHandle,
    pending: StoredEntry,
    finalName: String = pending.logicalName
) {
    try {
        val temporaryRoot = publicationTemporaryRoot(context, root)
        val current = readPublicationMetadataFile(context, root, "$finalName$METADATA_SUFFIX")
        val source = temporaryRoot?.let {
            readPublicationMetadataFile(context, it, "${pending.logicalName}$PENDING_METADATA_SUFFIX")
                ?: readPublicationMetadataFile(context, it, "${pending.logicalName}$METADATA_SUFFIX")
        } ?: readPublicationMetadataFile(context, root, "${pending.logicalName}$PENDING_METADATA_SUFFIX")
            ?: current?.takeIf { metadata ->
                val receiptMatches = metadata.optJSONObject(PUBLICATION_RECEIPT_KEY)?.let { receipt ->
                    receipt.optString("sourceName") == pending.name &&
                        samePublicationReference(receipt.optString("sourceReference"), pending.reference)
                } == true
                val legacyReferenceMatches = metadata.optString("mediaUri")
                    .takeIf(String::isNotBlank)
                    ?.let { samePublicationReference(it, pending.reference) } == true
                receiptMatches || legacyReferenceMatches
            }
            ?: throw IOException("发布标记缺少 pending 身份凭据: ${pending.logicalName}")
        val metadata = current ?: source
        if (!samePublicationOwner(source, metadata)) {
            throw IOException("正式元信息属于另一下载，拒绝设置发布标记: $finalName")
        }
        metadata.put(PUBLICATION_PENDING_KEY, true)
        writePublicationMetadata(context, root, finalName, metadata.toString())
    } catch (error: Throwable) {
        invalidateSnapshotCache(context)
        throw error
    }
}

internal fun ManagedDownloadStorage.recordAudioPublicationTarget(
    context: Context,
    root: RootHandle,
    pending: StoredEntry,
    targetReference: String,
    targetFileIdentity: String? = null,
    targetName: String = pending.logicalName
) {
    val metadataEntry = findMetadataForAudioBlocking(context, pending, root)
        ?: throw IOException("发布前缺少音频身份凭据: ${pending.logicalName}")
    val raw = readTextInternal(context, metadataEntry.reference)
        ?: throw IOException("发布前音频身份凭据不可读: ${pending.logicalName}")
    val metadata = JSONObject(raw)
    if (metadata.optString("stableKey").isBlank() || publicationOwner(metadata).isBlank()) {
        throw IOException("发布前音频身份不完整: ${pending.logicalName}")
    }
    metadata.put(PUBLICATION_RECEIPT_KEY, JSONObject()
        .put("sourceName", pending.name)
        .put("sourceReference", pending.reference)
        .put("targetName", targetName)
        .put("targetReference", targetReference)
        .put("sha256", publicationInput(context, pending.reference).use(::publicationDigest))
        .put("fileIdentity", targetFileIdentity))
    val content = metadata.toString()
    val temporaryRoot = resolveTemporaryRoot(context, root, create = true)
        ?: throw IOException("发布前无法保存目标凭据")
    val written = writeRootText(context, temporaryRoot, "$targetName$PENDING_METADATA_SUFFIX", content)
        ?: throw IOException("发布前无法保存目标凭据")
    if (readTextInternal(context, written.reference) != content) {
        throw IOException("发布目标凭据读回不一致: ${pending.logicalName}")
    }
}

internal fun ManagedDownloadStorage.isVerifiedAudioPublicationTarget(
    context: Context,
    root: RootHandle,
    pendingName: String,
    finalName: String,
    targetReference: String,
    pendingReference: String? = null,
    requireCompleteTarget: Boolean = true
): Boolean = runCatching {
    val metadata = readAudioPublicationMetadata(context, root, finalName) ?: return@runCatching false
    val receipt = metadata.optJSONObject(PUBLICATION_RECEIPT_KEY) ?: return@runCatching false
    val sourceReference = receipt.optString("sourceReference")
    if (receipt.optString("sourceName") != pendingName || receipt.optString("targetName") != finalName ||
        !samePublicationReference(receipt.optString("targetReference"), targetReference) ||
        pendingReference != null && !samePublicationReference(sourceReference, pendingReference)
    ) return@runCatching false
    val source = StoredEntry(pendingName, sourceReference, sourceReference, sourceReference.takeIf { it.startsWith("/") }, 0L, 0L)
    val currentEntry = findMetadataForAudioBlocking(context, source, root) ?: return@runCatching false
    val current = JSONObject(readTextInternal(context, currentEntry.reference) ?: return@runCatching false)
    if (metadata.optString("stableKey").isBlank() || metadata.optString("stableKey") != current.optString("stableKey") ||
        publicationOwner(metadata).isBlank() || publicationOwner(metadata) != publicationOwner(current)
    ) return@runCatching false
    if (targetReference.startsWith("/")) {
        if (Files.isSymbolicLink(File(targetReference).toPath())) return@runCatching false
        if (receipt.optString("fileIdentity") != publicationFileIdentity(targetReference)) return@runCatching false
    }
    val expectedDigest = receipt.optString("sha256")
    if (expectedDigest.length != 64) return@runCatching false
    val sourcePresent = when (ManagedDownloadReferenceIo.inspect(context, sourceReference)) {
        ManagedDownloadReferenceIo.AccessResult.Accessible -> true
        ManagedDownloadReferenceIo.AccessResult.Missing -> false
        else -> return@runCatching false
    }
    if (!sourcePresent && !requireCompleteTarget) return@runCatching false
    if (sourcePresent && publicationInput(context, sourceReference).use(::publicationDigest) != expectedDigest) return@runCatching false
    !requireCompleteTarget || publicationInput(context, targetReference).use(::publicationDigest) == expectedDigest

}.getOrDefault(false)

internal fun preserveAudioPublicationReceipt(
    previous: String?,
    incoming: String,
    allowPublicationCompletion: Boolean = false
): String {
    val old = previous?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return incoming
    val next = JSONObject(incoming)
    if (!samePublicationOwner(old, next)) return incoming
    if (old.has(PUBLICATION_PENDING_KEY) &&
        (!next.has(PUBLICATION_PENDING_KEY) || old.getBoolean(PUBLICATION_PENDING_KEY) && !allowPublicationCompletion)
    ) {
        next.put(PUBLICATION_PENDING_KEY, old.getBoolean(PUBLICATION_PENDING_KEY))
    }
    old.optJSONObject(PUBLICATION_RECEIPT_KEY)?.let { next.put(PUBLICATION_RECEIPT_KEY, it) }
    return next.toString()
}

internal fun ManagedDownloadStorage.resumeAudioPublicationCopy(
    context: Context,
    root: RootHandle,
    pendingName: String,
    finalName: String,
    targetReference: String,
    pendingReference: String
): Boolean {
    if (!isVerifiedAudioPublicationTarget(
            context, root, pendingName, finalName, targetReference, pendingReference,
            requireCompleteTarget = false
        )
    ) return false
    if (targetReference.startsWith("/")) {
        val receipt = readAudioPublicationMetadata(context, root, finalName)
            ?.optJSONObject(PUBLICATION_RECEIPT_KEY) ?: return false
        // 打开后确认 inode 再截断，避免检查与打开之间被换成用户文件
        val descriptor = Os.open(targetReference, OsConstants.O_WRONLY or OsConstants.O_NOFOLLOW, 0)
        FileOutputStream(descriptor).use { output ->
            val identity = Os.fstat(descriptor).let { "${it.st_dev}:${it.st_ino}" }
            if (receipt.optString("fileIdentity") != identity) throw IOException("恢复发布目标身份已变化: $finalName")
            publicationInput(context, pendingReference).use { source ->
                Os.ftruncate(descriptor, 0L)
                source.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
            }
            output.fd.sync()
            if (publicationFileIdentity(targetReference) != identity) throw IOException("恢复发布目标被替换: $finalName")
        }
    } else {
        publicationInput(context, pendingReference).use { source ->
            val output = context.contentResolver.openOutputStream(targetReference.toUri(), "wt")
                ?: throw IOException("恢复发布目标不可写: $finalName")
            output.use { source.copyTo(it, STREAM_COPY_BUFFER_SIZE_BYTES) }
        }
    }
    return isVerifiedAudioPublicationTarget(context, root, pendingName, finalName, targetReference, pendingReference)
}

internal fun ManagedDownloadStorage.sealAudioPublicationReceipt(context: Context, root: RootHandle, audio: StoredEntry) {
    try {
        val publication = readAudioPublicationMetadata(context, root, audio.name) ?: return
        if (!publication.has(PUBLICATION_RECEIPT_KEY) && !publication.optBoolean(PUBLICATION_PENDING_KEY)) return
        val currentEntry = findMetadataForAudioBlocking(context, audio, root) ?: throw IOException("正式音频缺少metadata")
        val current = readTextInternal(context, currentEntry.reference) ?: throw IOException("正式metadata不可读")
        val metadata = JSONObject(current)
        if (!samePublicationOwner(publication, metadata)) throw IOException("正式发布元信息身份已变化")
        if (publication.optBoolean(PUBLICATION_PENDING_KEY) || metadata.optBoolean(PUBLICATION_PENDING_KEY)) {
            val receipt = publication.optJSONObject(PUBLICATION_RECEIPT_KEY)
                ?: throw IOException("发布尚未完成，缺少完整音频凭据")
            if (!isVerifiedAudioPublicationTarget(
                    context, root, receipt.optString("sourceName"), audio.name, audio.reference,
                    receipt.optString("sourceReference")
                )
            ) throw IOException("发布音频尚未通过完整性校验")
            metadata.put(PUBLICATION_PENDING_KEY, false)
        }
        val content = preserveAudioPublicationReceipt(publication.toString(), metadata.toString(), allowPublicationCompletion = true)
        if (content == current) return
        writePublicationMetadata(context, root, audio.name, content)
    } catch (error: Throwable) {
        invalidateSnapshotCache(context)
        throw error
    }
}

internal fun ManagedDownloadStorage.readAudioPublicationMetadata(context: Context, root: RootHandle, audioName: String): JSONObject? {
    val temporaryRoot = publicationTemporaryRoot(context, root)
    val roots = listOfNotNull(temporaryRoot, root)
    var markerOnly: JSONObject? = null
    for (candidateRoot in roots) {
        for (name in listOf("$audioName$PENDING_METADATA_SUFFIX", "$audioName$METADATA_SUFFIX")) {
            val metadata = readPublicationMetadataFile(context, candidateRoot, name) ?: continue
            if (metadata.has(PUBLICATION_RECEIPT_KEY) || metadata.has(PUBLICATION_PENDING_KEY)) {
                if (candidateRoot != root) {
                    val formal = readPublicationMetadataFile(context, root, "$audioName$METADATA_SUFFIX")
                    if (formal != null && samePublicationOwner(metadata, formal) && formal.has(PUBLICATION_PENDING_KEY)) {
                        // 正式标记是封存结果，清理失败残留的临时凭据不能重新隐藏成品
                        metadata.put(PUBLICATION_PENDING_KEY, formal.getBoolean(PUBLICATION_PENDING_KEY))
                    }
                }
                if (metadata.has(PUBLICATION_RECEIPT_KEY)) return metadata
                if (markerOnly == null || candidateRoot == root && name == "$audioName$METADATA_SUFFIX") {
                    markerOnly = metadata
                }
            }
        }
    }
    return markerOnly
}

private fun ManagedDownloadStorage.publicationTemporaryRoot(context: Context, root: RootHandle): RootHandle? =
    when (root) {
        is RootHandle.FileRoot -> resolveTemporaryRoot(context, root, create = false)
        is RootHandle.TreeRoot -> {
            val cached = treeChildRegistry.peekTreeChildIncludingIncomplete(
                root.tree,
                DOWNLOAD_TEMPORARY_DIR_NAME
            )?.takeIf { it.isDirectory }
            val directory = if (cached != null) {
                treeChildRegistry.toDocumentFile(context, root.tree, cached)
                    ?: throw IOException("已知的发布暂存目录暂时不可访问")
            } else {
                val (found, complete) = findExistingTemporaryTreeDirectory(context, root, forceRefresh = false)
                if (found == null && !complete) throw IOException("无法完整确认发布暂存目录是否存在")
                found
            }
            directory?.let(RootHandle::TreeRoot)
        }
    }

private fun ManagedDownloadStorage.readPublicationMetadataFile(context: Context, root: RootHandle, name: String): JSONObject? {
    val reference = when (root) {
        is RootHandle.FileRoot -> File(root.dir, name).let { file ->
            if (file.exists() && !file.isFile) throw IOException("发布元信息不是文件: $name")
            file.takeIf(File::isFile)?.absolutePath
        }
        is RootHandle.TreeRoot -> findPublicationMetadataReference(context, root.tree, name)
    } ?: return null
    val content = readTextInternal(context, reference)
        ?: throw IOException("已找到的发布元信息暂时不可读: $name")
    val metadata = try {
        JSONObject(content)
    } catch (error: JSONException) {
        throw IOException("已找到的发布元信息无法解析: $name", error)
    }
    if (metadata.has(PUBLICATION_RECEIPT_KEY) && metadata.optJSONObject(PUBLICATION_RECEIPT_KEY) == null) {
        throw IOException("已找到的发布凭据格式无效: $name")
    }
    if (metadata.has(PUBLICATION_PENDING_KEY) && metadata.opt(PUBLICATION_PENDING_KEY) !is Boolean) {
        throw IOException("已找到的发布标记格式无效: $name")
    }
    return metadata
}

private fun ManagedDownloadStorage.writePublicationMetadata(context: Context, root: RootHandle, audioName: String, content: String) {
    val written = writeRootText(context, root, "$audioName$METADATA_SUFFIX", content)
        ?: throw IOException("正式发布凭据保存失败")
    if (readTextInternal(context, written.reference) != content) throw IOException("正式发布凭据读回失败")
    val metadata = parseDownloadedAudioMetadataJson(content)
    if (metadata == null || !updateSnapshotCacheAfterMetadataWrite(context, written, metadata)) {
        invalidateSnapshotCache(context)
    }
}

private fun ManagedDownloadStorage.findPublicationMetadataReference(context: Context, parent: DocumentFile, name: String): String? {
    val known = treeChildRegistry.peekTreeChildIncludingIncomplete(parent, name)
    val child = known ?: run {
        val cached = treeChildRegistry.cachedTreeChildrenIfFresh(parent, TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS)
        val refresh = cached?.let { ManagedDownloadTreeChildRegistry.TreeChildrenRefresh(it.toList(), isComplete = true) }
            ?: treeChildRegistry.refreshTreeChildrenWithStatus(context, parent)
        val found = refresh.children.firstOrNull { it.name == name }
        if (found == null && !refresh.isComplete) throw IOException("无法完整确认发布元信息是否存在: $name")
        found
    }
    if (child?.isDirectory == true) throw IOException("发布元信息不是文件: $name")
    return child?.documentUri?.toString()
}

internal fun publicationFileIdentity(reference: String): String = Os.stat(reference).let { "${it.st_dev}:${it.st_ino}" }

private fun publicationOwner(metadata: JSONObject): String = metadata.optString("operationId").takeIf(String::isNotBlank)
    ?: metadata.optString("terminalTemporaryWriteCleanupToken").takeIf(String::isNotBlank)
    ?: metadata.optString("stableKey").takeIf(String::isNotBlank)?.let { "legacy:$it" }.orEmpty()

private fun samePublicationOwner(first: JSONObject, second: JSONObject): Boolean =
    first.optString("stableKey").isNotBlank() && first.optString("stableKey") == second.optString("stableKey") &&
        publicationOwner(first).isNotBlank() && publicationOwner(first) == publicationOwner(second)

internal fun samePublicationReference(first: String, second: String): Boolean {
    if (first == second) return first.isNotBlank()
    if (first.startsWith("/") || second.startsWith("/")) return false
    return runCatching {
        val firstUri = first.toUri()
        val secondUri = second.toUri()
        firstUri.authority == secondUri.authority &&
            DocumentsContract.getDocumentId(firstUri) == DocumentsContract.getDocumentId(secondUri)
    }.getOrDefault(false)
}

private fun publicationInput(context: Context, reference: String): InputStream =
    if (reference.startsWith("/")) File(reference).inputStream()
    else context.contentResolver.openInputStream(reference.toUri()) ?: throw IOException("发布目标不可读: $reference")

private fun publicationDigest(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            val single = input.read()
            if (single < 0) break
            digest.update(single.toByte())
        } else digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
