package moe.ouom.neriplayer.data.local.media.curation

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.model.SongItem

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_STORAGE_PREFIX = "/storage/emulated/0/"
private const val PRIMARY_VOLUME_ID = "primary"
private val removableStorageRegex = Regex("""^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})/""")
private val folderArtworkNames = listOf("cover", "folder", "front", "album")
private val folderArtworkExtensions = listOf("jpg", "jpeg", "png", "webp")

/** 外部存储文档 ID，例如 `primary:Music/Album` */
data class StorageDocumentPath(val volumeId: String, val relativePath: String) {
    val documentId: String get() = "$volumeId:$relativePath"

    fun isInside(treeDocumentId: String): Boolean {
        val treeVolume = treeDocumentId.substringBefore(':')
        val treePath = treeDocumentId.substringAfter(':', "").trim('/')
        if (!treeVolume.equals(volumeId, ignoreCase = true)) return false
        return treePath.isEmpty() || relativePath == treePath || relativePath.startsWith("$treePath/")
    }
}

/**
 * 查找歌曲所在目录的 cover/folder/front/album 图片。
 *
 * 分区存储下应用只有读取音频的权限时看不到同目录的图片，全局扫描得到的歌曲也没有可遍历的文档树。
 * 这里先复用现有的侧载与同目录查找，再在用户授权过的文件夹（SAF 树）里按路径定位，不申请图片权限。
 */
object FolderArtworkLocator {
    private val directoryCache = object : LinkedHashMap<String, String?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean = size > 256
    }

    fun storagePathOf(absoluteDirectory: String): StorageDocumentPath? {
        val normalized = absoluteDirectory.trimEnd('/') + "/"
        if (normalized.startsWith(PRIMARY_STORAGE_PREFIX)) {
            return StorageDocumentPath(PRIMARY_VOLUME_ID, normalized.removePrefix(PRIMARY_STORAGE_PREFIX).trim('/'))
        }
        val removable = removableStorageRegex.find(normalized) ?: return null
        return StorageDocumentPath(
            volumeId = removable.groupValues[1].uppercase(Locale.ROOT),
            relativePath = normalized.removePrefix(removable.value).trim('/')
        )
    }

    fun isFolderArtworkName(displayName: String): Boolean {
        val base = displayName.substringBeforeLast('.', "").lowercase(Locale.ROOT)
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return base in folderArtworkNames && extension in folderArtworkExtensions
    }

    /** 同一目录有多张候选时按 cover > folder > front > album 的约定取第一张 */
    fun pickFolderArtwork(displayNames: Collection<String>): String? {
        return displayNames.filter(::isFolderArtworkName).minWithOrNull(
            compareBy<String> { folderArtworkNames.indexOf(it.substringBeforeLast('.').lowercase(Locale.ROOT)) }
                .thenBy { folderArtworkExtensions.indexOf(it.substringAfterLast('.').lowercase(Locale.ROOT)) }
        )
    }

    suspend fun find(context: Context, song: SongItem): String? = withContext(Dispatchers.IO) {
        LocalMediaSupport.resolveNearbyCoverUri(context, song)?.let { return@withContext it }
        val directory = songDirectory(context, song) ?: return@withContext null
        val storagePath = storagePathOf(directory) ?: return@withContext null
        val tree = grantedTreeFor(context, storagePath) ?: return@withContext null
        val cacheKey = "$tree|${storagePath.documentId}"
        synchronized(directoryCache) {
            if (directoryCache.containsKey(cacheKey)) return@withContext directoryCache[cacheKey]
        }
        val found = runCatching { findInTree(context, tree, storagePath) }.getOrNull()
        synchronized(directoryCache) { directoryCache[cacheKey] = found }
        found
    }

    /**
     * 返回每首歌所在、但还不在任何已授权文件夹内的目录（与 [songs] 一一对应，无需授权时为 null），
     * 供界面统计并引导授权。已授权列表只读取一次，避免大曲库逐首查询系统权限表
     */
    suspend fun directoriesNeedingAccess(
        context: Context,
        songs: List<SongItem>
    ): List<StorageDocumentPath?> = withContext(Dispatchers.IO) {
        val grantedTrees = grantedTreeDocumentIds(context)
        val decided = HashMap<String, StorageDocumentPath?>()
        songs.map { song ->
            val directory = songDirectory(context, song) ?: return@map null
            decided.getOrPut(directory) {
                storagePathOf(directory)?.takeIf { path -> grantedTrees.none(path::isInside) }
            }
        }
    }

    /** 多个目录的最近公共上级，用来让系统文件夹选择器直接定位，授权一次覆盖整批歌曲 */
    fun commonAncestor(paths: Collection<StorageDocumentPath>): StorageDocumentPath? {
        val first = paths.firstOrNull() ?: return null
        if (paths.any { !it.volumeId.equals(first.volumeId, ignoreCase = true) }) return null
        var common = first.relativePath.split('/').filter(String::isNotEmpty)
        for (path in paths) {
            val segments = path.relativePath.split('/').filter(String::isNotEmpty)
            common = common.zip(segments).takeWhile { (left, right) -> left == right }.map { it.first }
        }
        return StorageDocumentPath(first.volumeId, common.joinToString("/"))
    }

    fun documentUriFor(path: StorageDocumentPath): Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, path.documentId)

    fun clearCache() {
        synchronized(directoryCache) { directoryCache.clear() }
    }

    private fun songDirectory(context: Context, song: SongItem): String? {
        val path = song.localFilePath
            ?.takeIf { it.startsWith("/") }
            ?: song.localMediaUri()?.let { uri -> runCatching { LocalMediaSupport.resolveLocalFile(context, uri) }.getOrNull()?.absolutePath }
            ?: return null
        return File(path).parent
    }

    /** 歌曲目录位于可写授权文件夹内时，写入会走文件夹授权，不需要再向系统申请修改媒体文件 */
    suspend fun isInsideWritableTree(context: Context, song: SongItem): Boolean = withContext(Dispatchers.IO) {
        val directory = songDirectory(context, song) ?: return@withContext false
        val path = storagePathOf(directory) ?: return@withContext false
        grantedTrees(context, requireWrite = true).any { (_, documentId) -> path.isInside(documentId) }
    }

    private fun grantedTrees(context: Context, requireWrite: Boolean = false): List<Pair<Uri, String>> {
        return context.contentResolver.persistedUriPermissions.mapNotNull { permission ->
            val uri = permission.uri
            val permitted = if (requireWrite) permission.isWritePermission else permission.isReadPermission
            if (!permitted || uri.authority != EXTERNAL_STORAGE_AUTHORITY) return@mapNotNull null
            runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()?.let { uri to it }
        }
    }

    private fun grantedTreeDocumentIds(context: Context): List<String> = grantedTrees(context).map { it.second }

    private fun grantedTreeFor(context: Context, path: StorageDocumentPath): Uri? {
        return grantedTrees(context).firstOrNull { (_, documentId) -> path.isInside(documentId) }?.first
    }

    private fun findInTree(context: Context, tree: Uri, path: StorageDocumentPath): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, path.documentId)
        val names = HashMap<String, String>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1) ?: continue
                if (isFolderArtworkName(displayName)) names[displayName] = documentId
            }
        }
        val picked = pickFolderArtwork(names.keys) ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, names.getValue(picked)).toString()
    }
}
