package moe.ouom.neriplayer.core.download.manager.catalog

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat

private const val MISSING_REFERENCE_PROBE_WORKERS = 8

internal suspend fun findConfirmedMissingDownloadedSongs(
    context: Context,
    songs: Collection<DownloadedSong>
): List<DownloadedSong> {
    val backend = SafStorageBackend(context.applicationContext ?: context)
    return findConfirmedMissingDownloadedSongs(songs) { reference ->
        statDownloadedSongReference(reference, backend)
    }
}

internal suspend fun findConfirmedMissingDownloadedSongs(
    songs: Collection<DownloadedSong>,
    probeReference: suspend (String) -> StorageLookupResult<StorageStat>
): List<DownloadedSong> = coroutineScope {
    val snapshot = songs.toList()
    if (snapshot.isEmpty()) return@coroutineScope emptyList()
    val nextIndex = AtomicInteger()
    val confirmedMissing = BooleanArray(snapshot.size)
    List(minOf(MISSING_REFERENCE_PROBE_WORKERS, snapshot.size)) {
        async(Dispatchers.IO) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val index = nextIndex.getAndIncrement()
                if (index >= snapshot.size) break
                val song = snapshot[index]
                // 两个字段可能指向独立副本，任一引用仍存在或不可确认时都要保留
                val references = listOfNotNull(song.filePath, song.mediaUri)
                    .filter(String::isNotEmpty)
                    .distinct()
                confirmedMissing[index] = references.isNotEmpty() && references.all { reference ->
                    currentCoroutineContext().ensureActive()
                    try {
                        probeReference(reference) == StorageLookupResult.Missing
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }
    }.awaitAll()
    currentCoroutineContext().ensureActive()
    snapshot.filterIndexed { index, _ -> confirmedMissing[index] }
}

internal suspend fun statDownloadedSongReference(
    context: Context,
    reference: String
): StorageLookupResult<StorageStat> {
    return statDownloadedSongReference(
        reference,
        SafStorageBackend(context.applicationContext ?: context)
    )
}

private suspend fun statDownloadedSongReference(
    reference: String,
    backend: SafStorageBackend
): StorageLookupResult<StorageStat> {
    return try {
        val file = File(reference)
        if (file.isAbsolute) {
            return statDownloadedSongFile(file)
        }
        val uri = URI(reference)
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "content" -> {
                if (uri.isOpaque || uri.rawAuthority.isNullOrBlank()) {
                    StorageLookupResult.Unsupported("invalid content reference")
                } else {
                    // 保留完整 authority 和 opaque document id，只查询元信息，不打开音频
                    backend.stat(StorageReference.SafRef(reference.toUri()))
                }
            }
            "file" -> statDownloadedSongFile(File(uri))
            else -> StorageLookupResult.Unsupported("downloaded song reference")
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: SecurityException) {
        StorageLookupResult.PermissionLost
    } catch (_: URISyntaxException) {
        StorageLookupResult.Unsupported("invalid downloaded song reference")
    } catch (_: IllegalArgumentException) {
        StorageLookupResult.Unsupported("invalid downloaded song reference")
    } catch (error: Exception) {
        StorageLookupResult.ProviderFailure(error)
    }
}

private suspend fun statDownloadedSongFile(file: File): StorageLookupResult<StorageStat> =
    withContext(Dispatchers.IO) {
        try {
            // exists() 会把权限和 I/O 失败也折叠成 false，只有明确的缺失异常可以裁剪
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            StorageLookupResult.Found(
                StorageStat(
                    reference = StorageReference.FileRef(file.path),
                    displayName = file.name,
                    sizeBytes = attributes.size(),
                    lastModifiedMs = attributes.lastModifiedTime().toMillis(),
                    isDirectory = attributes.isDirectory
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: NoSuchFileException) {
            StorageLookupResult.Missing
        } catch (_: AccessDeniedException) {
            StorageLookupResult.PermissionLost
        } catch (_: SecurityException) {
            StorageLookupResult.PermissionLost
        } catch (error: Exception) {
            StorageLookupResult.ProviderFailure(error)
        }
    }
