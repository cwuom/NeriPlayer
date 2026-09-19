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

internal fun preserveAudioPublicationReceipt(previous: String?, incoming: String): String {
    val old = previous?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return incoming
    val next = JSONObject(incoming)
    val receipt = old.optJSONObject(PUBLICATION_RECEIPT_KEY) ?: return incoming
    if (old.optString("stableKey").isNotBlank() && old.optString("stableKey") == next.optString("stableKey") &&
        publicationOwner(old).isNotBlank() && publicationOwner(old) == publicationOwner(next)
    ) next.put(PUBLICATION_RECEIPT_KEY, receipt)
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
        val receipt = readAudioPublicationMetadata(context, root, audio.name) ?: return
        val currentEntry = findMetadataForAudioBlocking(context, audio, root) ?: throw IOException("正式音频缺少metadata")
        val current = readTextInternal(context, currentEntry.reference) ?: throw IOException("正式metadata不可读")
        val content = preserveAudioPublicationReceipt(receipt.toString(), current)
        if (content == current) return
        val written = writeRootText(context, root, "${audio.name}$METADATA_SUFFIX", content)
            ?: throw IOException("正式发布凭据保存失败")
        if (readTextInternal(context, written.reference) != content) throw IOException("正式发布凭据读回失败")
        val metadata = parseDownloadedAudioMetadataJson(content)
        if (metadata == null || !updateSnapshotCacheAfterMetadataWrite(context, written, metadata)) {
            invalidateSnapshotCache(context)
        }
    } catch (error: Throwable) {
        invalidateSnapshotCache(context)
        throw error
    }
}

internal fun ManagedDownloadStorage.readAudioPublicationMetadata(context: Context, root: RootHandle, audioName: String): JSONObject? {
    val temporaryRoot = when (root) {
        is RootHandle.FileRoot -> resolveTemporaryRoot(context, root, create = false)
        is RootHandle.TreeRoot -> {
            val cached = treeChildRegistry.peekTreeChildrenIncludingIncomplete(root.tree)
                ?.firstOrNull { it.isDirectory && it.name == DOWNLOAD_TEMPORARY_DIR_NAME }
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
    val roots = listOfNotNull(temporaryRoot, root)
    roots.forEach { candidateRoot ->
        listOf("$audioName$PENDING_METADATA_SUFFIX", "$audioName$METADATA_SUFFIX").forEach { name ->
            val reference = when (candidateRoot) {
                is RootHandle.FileRoot -> File(candidateRoot.dir, name).takeIf(File::isFile)?.absolutePath
                is RootHandle.TreeRoot -> findPublicationMetadataReference(context, candidateRoot.tree, name)
            }
            if (reference != null) {
                val content = readTextInternal(context, reference)
                    ?: throw IOException("已找到的发布元信息暂时不可读: $name")
                val metadata = try {
                    JSONObject(content)
                } catch (error: JSONException) {
                    throw IOException("已找到的发布元信息无法解析: $name", error)
                }
                if (metadata.has(PUBLICATION_RECEIPT_KEY)) {
                    if (metadata.optJSONObject(PUBLICATION_RECEIPT_KEY) == null) {
                        throw IOException("已找到的发布凭据格式无效: $name")
                    }
                    return metadata
                }
            }
        }
    }
    return null
}

private fun ManagedDownloadStorage.findPublicationMetadataReference(context: Context, parent: DocumentFile, name: String): String? {
    val known = treeChildRegistry.peekTreeChildrenIncludingIncomplete(parent)?.firstOrNull { it.name == name }
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
