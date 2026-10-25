package moe.ouom.neriplayer.data.local.media.external

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.embeddedCoverCacheLookupKeysImpl
import moe.ouom.neriplayer.data.local.media.embeddedCoverFile
import moe.ouom.neriplayer.data.local.media.invalidateSongAssetCachesImpl
import moe.ouom.neriplayer.data.local.media.localMediaUriCandidates
import moe.ouom.neriplayer.data.local.media.resolveInspectableLocalMedia
import moe.ouom.neriplayer.data.model.SongItem

private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

/** 与 file_paths.xml 中受控目录保持一致；只有这些目录能通过 FileProvider 交给外部编辑器 */
private const val MANAGED_MEDIA_DIRECTORY = "NeriPlayer"

private const val FALLBACK_AUDIO_MIME_TYPE = "audio/*"

/**
 * 可以交给外部标签编辑器（如 Lyrico）读写的本地音频目标。
 *
 * [grantWrite] 只表示当前应用确实能授出写权限：受控目录里的文件与拿到写授权的 content
 * 引用为 true，MediaStore 条目通常为 false，由外部编辑器自行向系统申请写授权。
 */
data class LocalMediaExternalTagEditTarget(
    val uri: Uri,
    val mimeType: String,
    val grantWrite: Boolean
)

/** 交给外部编辑器前的解析结果 */
sealed interface LocalMediaExternalTagEditResolution {
    data class Editable(
        val target: LocalMediaExternalTagEditTarget
    ) : LocalMediaExternalTagEditResolution

    /** 没有能写回原文件的引用，外部编辑器即使保存也改不到这里索引的文件 */
    data object Unavailable : LocalMediaExternalTagEditResolution
}

/** 外部编辑器写回文件后，按文件内容刷新本地歌曲的结果 */
enum class LocalSongFileRefreshResult {
    SUCCESS,

    /** 调用方给的歌曲已经不是本地歌曲 */
    NOT_LOCAL_SONG,

    /** 文件已经无法读取：被删除、权限失效或标签无法解析 */
    UNAVAILABLE,

    /** 读取成功但写回队列与歌单时失败 */
    FAILED
}

/**
 * 把本地歌曲的文件引用解析成外部标签编辑器可以写的 URI。
 *
 * 优先级与播放、分享一致（[localMediaUriCandidates]）：MediaStore 条目直接交出原 URI，
 * 受控目录里的文件通过 FileProvider 交出可写 URI，SAF 文档只有在持有可转授的写权限时才交出。
 * 无法交出的歌曲不会退化成缓存副本，避免用户在外部应用里改动后原文件没有任何变化。
 */
object LocalMediaExternalTagEdit {
    fun resolve(context: Context, song: SongItem): LocalMediaExternalTagEditResolution {
        if (!LocalSongSupport.isLocalSong(song, context)) {
            return LocalMediaExternalTagEditResolution.Unavailable
        }
        song.localMediaUriCandidates().forEach { candidate ->
            val target = when {
                candidate.scheme.equals("content", ignoreCase = true) ->
                    resolveContentTarget(context, candidate)
                else -> resolveFileTarget(context, candidate)
            } ?: return@forEach
            return LocalMediaExternalTagEditResolution.Editable(target)
        }
        return LocalMediaExternalTagEditResolution.Unavailable
    }

    private fun resolveContentTarget(
        context: Context,
        uri: Uri
    ): LocalMediaExternalTagEditTarget? {
        if (!isReadable(context, uri)) return null
        // 与外部编辑器保存时的第一步一致：能在这里以 rw 打开，才能保证文件真的可写
        val writesInPlace = isWritable(context, uri)
        if (uri.authority == MediaStore.AUTHORITY) {
            // Android 11 起 MediaStore 条目可以由外部编辑器自己申请系统写授权；
            // Android 10 没有可转交的申请入口，交出去只会得到无法保存的编辑页
            if (!writesInPlace && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) return null
        } else if (!writesInPlace) {
            // 其他 content 引用拿不到可转授的写权限就无处保存，不能退化成只读编辑
            return null
        }
        return LocalMediaExternalTagEditTarget(
            uri = uri,
            mimeType = context.contentResolver.getType(uri).asAudioMimeType(),
            grantWrite = writesInPlace
        )
    }

    private fun resolveFileTarget(
        context: Context,
        uri: Uri
    ): LocalMediaExternalTagEditTarget? {
        val path = when {
            uri.scheme.equals("file", ignoreCase = true) -> uri.path
            uri.scheme.isNullOrBlank() -> uri.toString()
            else -> null
        } ?: return null
        val file = File(path)
        if (!file.isFile || !file.canRead() || !file.canWrite()) return null
        if (!isInsideManagedMediaRoot(context, file)) return null
        val authority = context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX
        val sharedUri = runCatching {
            FileProvider.getUriForFile(context, authority, file)
        }.getOrNull() ?: return null
        return LocalMediaExternalTagEditTarget(
            uri = sharedUri,
            mimeType = runCatching {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            }.getOrNull().asAudioMimeType(),
            grantWrite = true
        )
    }

    private fun isReadable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun isWritable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
    }.getOrDefault(false)

    private fun isInsideManagedMediaRoot(context: Context, file: File): Boolean {
        val targetPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        return managedMediaRoots(context).any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
            val normalizedRoot = rootPath.trimEnd(File.separatorChar)
            targetPath == normalizedRoot || targetPath.startsWith(normalizedRoot + File.separatorChar)
        }
    }

    private fun managedMediaRoots(context: Context): List<File> = listOfNotNull(
        File(context.filesDir, MANAGED_MEDIA_DIRECTORY),
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?.let { directory -> File(directory, MANAGED_MEDIA_DIRECTORY) },
        // 下载目录可能被宿主改到别处，解析失败时只保留上面两个受控目录
        runCatching { LocalMediaSupport.downloadDirectory(context) }.getOrNull()
    ).distinctBy { directory -> directory.absolutePath }

    private fun String?.asAudioMimeType(): String =
        this?.takeIf { it.startsWith("audio/", ignoreCase = true) } ?: FALLBACK_AUDIO_MIME_TYPE
}

/**
 * 外部编辑器写回文件后，按旧文件内容建立的封面与歌词缓存会遮住新标签。
 *
 * 查找缓存按歌曲键清理即可，内嵌封面却是按文件名落盘的，必须把这份缓存文件一起删掉；
 * 否则重新读取时会命中修改前的图片，文件里换了封面也看不到变化。
 */
internal fun LocalMediaSupport.invalidateExternalTagEditCachesImpl(
    context: Context,
    song: SongItem
) {
    invalidateSongAssetCachesImpl(song)
    val sourceKeys = linkedSetOf<String>()
    sourceKeys += embeddedCoverCacheLookupKeysImpl(song)
    song.localMediaUriCandidates().forEach { source ->
        sourceKeys += source.toString()
        source.path?.takeIf(String::isNotBlank)?.let(sourceKeys::add)
        runCatching { resolveInspectableLocalMedia(context, source) }
            .getOrNull()
            ?.resolvedPath
            ?.takeIf(String::isNotBlank)
            ?.let(sourceKeys::add)
    }
    sourceKeys
        .filter(String::isNotBlank)
        .flatMap { key -> listOf(key, "$key#taglib") }
        .distinct()
        .forEach { key -> runCatching { embeddedCoverFile(context, key).delete() } }
}
