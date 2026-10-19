package moe.ouom.neriplayer.data.local.audioimport

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.id3FirstFrameOffset
import moe.ouom.neriplayer.data.local.media.id3FrameHeaderSize
import moe.ouom.neriplayer.data.local.media.id3FrameId
import moe.ouom.neriplayer.data.local.media.id3FrameSize
import moe.ouom.neriplayer.data.local.media.id3HasExtendedHeader
import moe.ouom.neriplayer.data.local.media.isNeteaseManagedSourceStableKey
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.local.media.mergeContainerMetadata
import moe.ouom.neriplayer.data.local.media.normalizeLocalAlbumIdentity
import moe.ouom.neriplayer.data.local.media.parseId3Metadata
import moe.ouom.neriplayer.data.local.media.parseId3v1Metadata
import moe.ouom.neriplayer.data.local.media.readSynchsafeInt
import moe.ouom.neriplayer.data.model.SongItem

private const val ID3_SCAN_HEADER_BYTES = 10
private const val ID3_SCAN_MAX_BYTES = 64 * 1024
private const val ID3_SCAN_MAX_FRAMES = 256
private const val ID3V1_BYTES = 128
private val ID3_IDENTITY_FRAMES = setOf("TIT2", "TPE1", "TALB", "TT2", "TP1", "TAL")

internal fun isRawId3IdentityCandidate(song: SongItem): Boolean {
    val name = song.localFileName ?: song.localFilePath ?: return false
    return name.substringAfterLast('.', "").equals("mp3", ignoreCase = true)
}

internal fun LocalAudioImportManager.hydrateLocalSongRawId3Identity(
    song: SongItem,
    metadata: LocalMediaSupport.ContainerMetadata?,
    originalSnapshot: SongItem
): SongItem {
    if (metadata == null) return song
    val title = normalizeQuickImportedMetadata(metadata.title)
    val artist = normalizeQuickImportedMetadata(metadata.artist)
    val album = normalizeQuickImportedMetadata(metadata.album)
    return song.copy(
        name = firstMeaningfulMetadataValue(song.customName, title) ?: song.name,
        artist = firstMeaningfulMetadataValue(song.customArtist, artist) ?: song.artist,
        album = album?.let {
            normalizeLocalAlbumIdentity(it, usesFallbackAlbum = false,
                stripManagedSourcePrefix = isNeteaseManagedSourceStableKey(song.sourceStableKey))
        } ?: song.album,
        originalName = if (title != null) {
            resolveImportedOriginalText(song.originalName, originalSnapshot.name, title, song.customName)
        } else song.originalName,
        originalArtist = if (artist != null) {
            resolveImportedOriginalText(song.originalArtist, originalSnapshot.artist, artist, song.customArtist)
        } else song.originalArtist
    )
}

internal fun LocalAudioImportManager.resolveImportedOriginalText(
    original: String?,
    quickValue: String,
    detailedValue: String?,
    customValue: String?
): String? {
    val retained = normalizeQuickImportedMetadata(original)
    if (customValue != null) return retained ?: normalizeQuickImportedMetadata(quickValue)
    if (retained != null && retained != normalizeQuickImportedMetadata(quickValue)) return retained
    return normalizeQuickImportedMetadata(detailedValue) ?: retained
}

internal fun readRawId3Identity(context: Context, song: SongItem): LocalMediaSupport.ContainerMetadata? {
    if (!isRawId3IdentityCandidate(song)) return null
    val file = song.localFilePath?.takeUnless { it.startsWith("content://", ignoreCase = true) }
        ?.let(::File)?.takeIf(File::isFile)
    if (file != null) {
        try {
            return file.inputStream().use { readId3Identity(it, allowSeek = true) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 绝对路径不可读时仍可使用 MediaStore 授权的描述符
        }
    }
    val uri = song.localMediaUri() ?: return null
    return try {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: FileNotFoundException) {
            // 部分 provider 只支持 asset 流，描述符不可用不代表音频不可读
            null
        }
        if (descriptor != null) {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { readId3Identity(it, allowSeek = true) }
        } else {
            context.contentResolver.openInputStream(uri)?.use { readId3Identity(it, allowSeek = false) }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.d(LocalAudioImportManager.TAG, "raw ID3 identity unavailable for $uri: ${error.message}")
        null
    }
}

internal fun readId3Identity(input: InputStream, allowSeek: Boolean): LocalMediaSupport.ContainerMetadata? {
    val header = readId3Header(input, allowSeek)
    val trailer = (input as? FileInputStream)?.takeIf { allowSeek }?.let { fileInput ->
        try {
            val size = fileInput.channel.size()
            if (size < ID3V1_BYTES) return@let null
            fileInput.channel.position(size - ID3V1_BYTES)
            LocalMediaSupport.parseId3v1Metadata(readAtMost(fileInput, ID3V1_BYTES))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
    return LocalMediaSupport.mergeContainerMetadata(header, trailer)
}

private fun readId3Header(input: InputStream, allowSeek: Boolean): LocalMediaSupport.ContainerMetadata? {
    val reader = Id3IdentityReader(input, allowSeek)
    val header = reader.read(ID3_SCAN_HEADER_BYTES)
    if (header.size != ID3_SCAN_HEADER_BYTES || header.toString(Charsets.US_ASCII).take(3) != "ID3") return null
    val version = header[3].toInt() and 0xFF
    val flags = header[5].toInt() and 0xFF
    if (version !in 2..4 || (flags and (if (version == 2) 0xC0 else 0x80)) != 0) return null
    val tagSize = header.readSynchsafeInt(6)
    if (tagSize <= 0) return null
    val limit = ID3_SCAN_HEADER_BYTES + tagSize
    val hasExtendedHeader = id3HasExtendedHeader(header, version)
    val prefix = if (hasExtendedHeader) header + reader.read(4) else header
    if (hasExtendedHeader && prefix.size != ID3_SCAN_HEADER_BYTES + 4) return null
    var offset = id3FirstFrameOffset(prefix, version, limit) ?: return null
    if (!reader.skip(offset - prefix.size)) return null
    val frameHeaderSize = id3FrameHeaderSize(version)
    val plainHeader = header.copyOf().apply { this[5] = 0 }
    var metadata: LocalMediaSupport.ContainerMetadata? = null
    repeat(ID3_SCAN_MAX_FRAMES) {
        if (offset + frameHeaderSize > limit) return metadata
        val frameHeader = reader.read(frameHeaderSize)
        if (frameHeader.size != frameHeaderSize) return metadata
        val frameId = id3FrameId(frameHeader, 0, version) ?: return metadata
        val frameSize = id3FrameSize(frameHeader, 0, version)
        offset += frameHeaderSize
        if (frameSize <= 0 || frameSize > limit - offset) return metadata
        // 格式标记会改变负载布局，首轮只解码不需要额外变换的身份帧
        val plainIdentity = frameId in ID3_IDENTITY_FRAMES && (version == 2 || frameHeader[9] == 0.toByte())
        if (plainIdentity && frameSize <= reader.remainingBytes) {
            val payload = reader.read(frameSize)
            if (payload.size != frameSize) return metadata
            metadata = LocalMediaSupport.mergeContainerMetadata(
                metadata,
                LocalMediaSupport.parseId3Metadata(plainHeader + frameHeader + payload)
            )
        } else if (!reader.skip(frameSize)) {
            return metadata
        }
        offset += frameSize
        if (metadata?.let { it.title != null && it.artist != null && it.album != null } == true) return metadata
    }
    return metadata
}

private class Id3IdentityReader(private val input: InputStream, allowSeek: Boolean) {
    var remainingBytes: Int = ID3_SCAN_MAX_BYTES
        private set
    private val seekableChannel = (input as? FileInputStream)?.takeIf { allowSeek }?.channel?.takeIf { channel ->
        try {
            channel.size()
            channel.position()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    fun read(size: Int): ByteArray {
        if (size > remainingBytes) return byteArrayOf()
        return readAtMost(input, size).also { remainingBytes -= it.size }
    }

    fun skip(size: Int): Boolean {
        if (size < 0) return false
        val channel = seekableChannel
        if (channel != null) {
            val target = channel.position() + size
            if (target > channel.size()) return false
            channel.position(target)
            return true
        }
        // 不可寻址的 provider 流也受同一个读取预算限制
        return size <= remainingBytes && read(size).size == size
    }
}

private fun readAtMost(input: InputStream, size: Int): ByteArray {
    val bytes = ByteArray(size)
    var total = 0
    while (total < size) {
        val count = input.read(bytes, total, size - total)
        if (count <= 0) break
        total += count
    }
    return bytes.copyOf(total)
}
