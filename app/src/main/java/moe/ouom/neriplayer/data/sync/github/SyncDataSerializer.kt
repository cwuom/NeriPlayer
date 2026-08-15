package moe.ouom.neriplayer.data.sync.github

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.sync.github/SyncDataSerializer
 * Created: 2025/1/8
 */

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import moe.ouom.neriplayer.data.sync.model.SyncAction
import moe.ouom.neriplayer.data.sync.model.SyncData
import moe.ouom.neriplayer.data.sync.model.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.sync.model.SyncLogEntry
import moe.ouom.neriplayer.data.sync.model.SyncPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncRecentPlay
import moe.ouom.neriplayer.data.sync.model.SyncSong
import moe.ouom.neriplayer.data.sync.model.compactedSyncCausalTokens
import moe.ouom.neriplayer.data.sync.model.expandedLegacyCompatibleSyncCausalTokens
import moe.ouom.neriplayer.data.sync.model.requireCausalTokenRangeCapacity
import moe.ouom.neriplayer.data.sync.model.sanitizeLocalCoverUrls
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 共享快照的写入能力
 *
 * LEGACY_SAFE 是默认值, 因为旧 Android 和旧 Desktop 会忽略 counterEnd,
 * 但不会理解它代表的完整区间。RANGE_V1 只能在显式能力协商后使用。
 */
enum class SyncSerializationCompatibility {
    LEGACY_SAFE,
    RANGE_V1
}

/**
 * 同步数据序列化工具
 *
 * 写路径使用原始字节, 避免 Base64 扩容并让二进制传输保持二进制:
 * - 非省流 (backup.json) : UTF-8 编码的 JSON 文本字节
 * - 省流 (backup-raw.bin) : 原始 GZIP(ProtoBuf) 字节
 *
 * 读路径按内容自动识别 (read-both) , 三种在野格式都不失败:
 * - 以 GZIP 魔数 0x1F 0x8B 开头 -> 直接解压 -> ProtoBuf (为将来 write-raw 预留)
 * - 文本且首个有效字节为 '{' -> JSON (旧/新 backup.json / 旧 WebDAV JSON)
 * - 其余文本 -> Base64(GZIP(ProtoBuf)) -> Base64 解码 -> 解压 -> ProtoBuf (旧 backup.bin)
 */
@OptIn(ExperimentalSerializationApi::class)
object SyncDataSerializer {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }
    private val protoBuf = ProtoBuf
    private const val MAX_JSON_BYTES = 8 * 1024 * 1024
    // 省流/二进制上限: 同时覆盖新的原始 GZIP 字节与历史 Base64 文本
    private const val MAX_COMPRESSED_BYTES = 12 * 1024 * 1024
    private const val MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024

    /**
     * 序列化数据为上传字节 (同时用于体积统计)
     * @param data 同步数据
     * @param useDataSaver 是否使用省流模式
     * @return useDataSaver=true 时为原始 GZIP(ProtoBuf) 字节; 否则为 UTF-8 JSON 字节
     */
    fun serialize(
        data: SyncData,
        useDataSaver: Boolean,
        compatibility: SyncSerializationCompatibility = SyncSerializationCompatibility.LEGACY_SAFE
    ): ByteArray {
        val compactedData = data.sanitizeLocalCoverUrls().compactCausalTokenRanges()
        ensureCausalTokenCapacity(compactedData)
        val sanitizedData = when (compatibility) {
            SyncSerializationCompatibility.LEGACY_SAFE -> compactedData.expandCausalTokenRanges()
            SyncSerializationCompatibility.RANGE_V1 -> compactedData
        }
        val content = if (useDataSaver) {
            val protoBytes = protoBuf.encodeToByteArray(sanitizedData)
            require(protoBytes.size <= MAX_DECOMPRESSED_BYTES) {
                "Sync data is too large to upload"
            }
            compress(protoBytes)
        } else {
            serializeJson(sanitizedData).toByteArray(Charsets.UTF_8)
        }
        ensureUploadContentSize(content, useDataSaver)
        return content
    }

    private fun ensureCausalTokenCapacity(data: SyncData) {
        data.playlists.forEach { playlist ->
            playlist.songs.forEach { song ->
                requireCausalTokenRangeCapacity(song.syncMembershipTokens)
            }
        }
        data.favoritePlaylists.forEach { playlist ->
            playlist.songs.forEach { song ->
                requireCausalTokenRangeCapacity(song.syncMembershipTokens)
            }
        }
        data.recentPlays.forEach { recentPlay ->
            requireCausalTokenRangeCapacity(recentPlay.song.syncMembershipTokens)
        }
        data.playlistSongDeletions.forEach { deletion ->
            requireCausalTokenRangeCapacity(deletion.removedMembershipTokens)
        }
    }

    private fun SyncData.compactCausalTokenRanges(): SyncData {
        return copy(
            playlists = playlists.map { playlist ->
                playlist.copy(
                    songs = playlist.songs.map { song ->
                        song.copy(
                            syncMembershipTokens = song.syncMembershipTokens.compactedSyncCausalTokens()
                        )
                    }
                )
            },
            favoritePlaylists = favoritePlaylists.map { playlist ->
                playlist.copy(
                    songs = playlist.songs.map { song ->
                        song.copy(
                            syncMembershipTokens = song.syncMembershipTokens.compactedSyncCausalTokens()
                        )
                    }
                )
            },
            recentPlays = recentPlays.map { recentPlay ->
                recentPlay.copy(
                    song = recentPlay.song.copy(
                        syncMembershipTokens = recentPlay.song.syncMembershipTokens
                            .compactedSyncCausalTokens()
                    )
                )
            },
            playlistSongDeletions = playlistSongDeletions.map { deletion ->
                deletion.copy(
                    removedMembershipTokens = deletion.removedMembershipTokens
                        .compactedSyncCausalTokens()
                )
            }.let { SyncPlaylistDeletionPolicy.limitDeletions(it) }
        )
    }

    private fun SyncData.expandCausalTokenRanges(): SyncData {
        return copy(
            playlists = playlists.map { playlist ->
                playlist.copy(
                    songs = playlist.songs.map { song ->
                        song.copy(
                            syncMembershipTokens = song.syncMembershipTokens
                                .expandedLegacyCompatibleSyncCausalTokens()
                        )
                    }
                )
            },
            favoritePlaylists = favoritePlaylists.map { playlist ->
                playlist.copy(
                    songs = playlist.songs.map { song ->
                        song.copy(
                            syncMembershipTokens = song.syncMembershipTokens
                                .expandedLegacyCompatibleSyncCausalTokens()
                        )
                    }
                )
            },
            recentPlays = recentPlays.map { recentPlay ->
                recentPlay.copy(
                    song = recentPlay.song.copy(
                        syncMembershipTokens = recentPlay.song.syncMembershipTokens
                            .expandedLegacyCompatibleSyncCausalTokens()
                    )
                )
            },
            playlistSongDeletions = playlistSongDeletions.map { deletion ->
                deletion.copy(
                    removedMembershipTokens = deletion.removedMembershipTokens
                        .expandedLegacyCompatibleSyncCausalTokens()
                )
            }
        )
    }

    private fun ensureUploadContentSize(content: ByteArray, useDataSaver: Boolean) {
        val maxBytes = if (useDataSaver) MAX_COMPRESSED_BYTES else MAX_JSON_BYTES
        require(content.size <= maxBytes) {
            "Sync data is too large to upload"
        }
    }

    /**
     * 反序列化数据 (按内容自动识别格式, read-both)
     * 兼容三种在野格式, 确保读旧备份与新备份都不失败:
     * 1. 原始 GZIP(ProtoBuf) 字节 (新 raw 格式, 魔数 0x1F 0x8B 开头)
     * 2. JSON 文本 (旧 backup.json / 旧 WebDAV, 首个有效字节为 '{')
     * 3. 旧 Base64(GZIP(ProtoBuf)) 文本 (旧 backup.bin)
     */
    fun deserialize(content: ByteArray): SyncData {
        ensureRemoteContentSize(content)
        if (looksLikeGzip(content)) {
            return decodeGzipProto(content)
        }
        val text = content.toString(Charsets.UTF_8)
        return if (looksLikeJson(content)) {
            deserializeJson(text)
        } else {
            // 旧 backup.bin: 先 Base64 解码得到 GZIP 字节, 再解压
            decodeGzipProto(decodeLegacyBase64(text))
        }
    }

    private fun decodeLegacyBase64(text: String): ByteArray {
        val compact = text.filterNot(Char::isWhitespace)
        require(
            compact.isNotEmpty() &&
                compact.length % 4 == 0 &&
                compact.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        ) { "Invalid legacy Base64 sync data" }
        return Base64.getDecoder().decode(compact)
    }

    /**
     * JSON序列化
     */
    private fun serializeJson(data: SyncData): String = json.encodeToString(data)

    /**
     * JSON反序列化
     */
    private fun deserializeJson(content: String): SyncData {
        val normalizedContent = content.removePrefix("\uFEFF")
        require(normalizedContent.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
            "JSON sync data is too large"
        }
        return json.decodeFromString(normalizedContent)
    }

    /**
     * GZIP(ProtoBuf) 原始字节 -> SyncData (含旧/错误字段编号 schema 的兼容回退)
     */
    private fun decodeGzipProto(gzipBytes: ByteArray): SyncData {
        require(gzipBytes.size <= MAX_COMPRESSED_BYTES) {
            "Compressed sync data is too large"
        }
        val protoBytes = decompress(gzipBytes)
        return runCatching { protoBuf.decodeFromByteArray<SyncData>(protoBytes) }
            .getOrElse { original ->
                // 兼容旧/错误字段编号的 schema
                val legacy = runCatching { protoBuf.decodeFromByteArray<LegacySyncData>(protoBytes) }
                    .getOrElse { throw original }
                legacy.toCurrent()
            }
    }

    /** 原始 GZIP 字节以魔数 0x1F 0x8B 开头, 用于区分新 raw 格式与历史文本格式 */
    private fun looksLikeGzip(bytes: ByteArray): Boolean {
        return bytes.size >= 2 &&
            bytes[0] == 0x1F.toByte() &&
            bytes[1] == 0x8B.toByte()
    }

    /** 跳过前导 UTF-8 BOM 与空白后, 首个有效字节为 '{' 即视为 JSON 对象 */
    private fun looksLikeJson(bytes: ByteArray): Boolean {
        var i = 0
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            i = 3
        }
        while (i < bytes.size) {
            when (bytes[i]) {
                ' '.code.toByte(),
                '\n'.code.toByte(),
                '\r'.code.toByte(),
                '\t'.code.toByte() -> i++
                '{'.code.toByte() -> return true
                else -> return false
            }
        }
        return false
    }

    /**
     * GZIP压缩
     */
    private fun compress(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val level = if (data.size >= BEST_COMPRESSION_THRESHOLD_BYTES) {
            Deflater.BEST_COMPRESSION
        } else {
            Deflater.DEFAULT_COMPRESSION
        }
        TunedGzipOutputStream(outputStream, level).use { gzip ->
            gzip.write(data)
        }
        return outputStream.toByteArray()
    }

    private class TunedGzipOutputStream(
        outputStream: OutputStream,
        level: Int
    ) : GZIPOutputStream(outputStream) {
        init {
            def.setLevel(level)
        }
    }

    /**
     * GZIP解压
     */
    private fun decompress(data: ByteArray): ByteArray {
        val inputStream = ByteArrayInputStream(data)
        val outputStream = ByteArrayOutputStream()
        GZIPInputStream(inputStream).use { gzip ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = gzip.read(buffer)
                if (read == -1) break
                total += read
                require(total <= MAX_DECOMPRESSED_BYTES) { "Decompressed sync data is too large" }
                outputStream.write(buffer, 0, read)
            }
        }
        return outputStream.toByteArray()
    }

    /**
     * 远端内容大小上限校验 (按内容自动选择 JSON / 二进制上限)
     */
    fun ensureRemoteContentSize(content: ByteArray) {
        val maxBytes = if (looksLikeJson(content)) MAX_JSON_BYTES else MAX_COMPRESSED_BYTES
        require(content.size <= maxBytes) { "Remote sync data is too large" }
    }

    /**
     * 获取数据大小 (用于统计) , 返回实际上传的原始字节数
     */
    fun getDataSize(data: SyncData, useDataSaver: Boolean): Int {
        return serialize(data, useDataSaver).size
    }

    /**
     * 计算压缩率
     */
    @Suppress("unused")
    fun getCompressionRatio(data: SyncData): Float {
        val jsonSize = serialize(data, false).size
        val compressedSize = serialize(data, true).size
        return if (jsonSize > 0) {
            (1 - compressedSize.toFloat() / jsonSize) * 100
        } else {
            0f
        }
    }

    /**
     * 获取文件名 (根据格式)
     */
    fun getFileName(useDataSaver: Boolean): String {
        return if (useDataSaver) RAW_BINARY_FILE_NAME else JSON_FILE_NAME
    }

    /** 返回只读兼容文件，上传始终使用 getFileName 返回的当前文件名 */
    internal fun getReadFallbackFileNames(useDataSaver: Boolean): List<String> {
        return if (useDataSaver) {
            listOf(LEGACY_BINARY_FILE_NAME, JSON_FILE_NAME)
        } else {
            listOf(RAW_BINARY_FILE_NAME, LEGACY_BINARY_FILE_NAME)
        }
    }

    /**
     * 判断文件名是否为二进制格式
     */
    fun isBinaryFileName(fileName: String): Boolean {
        return fileName.endsWith(".bin")
    }

    private const val RAW_BINARY_FILE_NAME = "backup-raw.bin"
    private const val LEGACY_BINARY_FILE_NAME = "backup.bin"
    private const val JSON_FILE_NAME = "backup.json"
    private const val BEST_COMPRESSION_THRESHOLD_BYTES = 256 * 1024

    /**
     * 兼容旧/错误字段编号的 schema (mediaUri 插入到 addedAt 之前的版本)
     */
    @Serializable
    private data class LegacySyncData(
        @ProtoNumber(1) val version: String = "2.0",
        // 回退路径同样按 proto3 语义补默认值, 避免二次抛出 MissingFieldException
        @ProtoNumber(2) val deviceId: String = "",
        @ProtoNumber(3) val deviceName: String = "",
        @ProtoNumber(4) val lastModified: Long = System.currentTimeMillis(),
        @ProtoNumber(5) val playlists: List<LegacySyncPlaylist> = emptyList(),
        @ProtoNumber(6) val favoritePlaylists: List<LegacySyncFavoritePlaylist> = emptyList(),
        @ProtoNumber(7) val recentPlays: List<LegacySyncRecentPlay> = emptyList(),
        @ProtoNumber(8) val syncLog: List<LegacySyncLogEntry> = emptyList()
    ) {
        fun toCurrent(): SyncData = SyncData(
            version = version,
            deviceId = deviceId,
            deviceName = deviceName,
            lastModified = lastModified,
            playlists = playlists.map { it.toCurrent() },
            favoritePlaylists = favoritePlaylists.map { it.toCurrent() },
            recentPlays = recentPlays.map { it.toCurrent() },
            syncLog = syncLog.map { it.toCurrent() }
        )
    }

    @Serializable
    private data class LegacySyncPlaylist(
        @ProtoNumber(1) val id: Long = 0L,
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val songs: List<LegacySyncSong> = emptyList(),
        @ProtoNumber(4) val createdAt: Long = 0L,
        @ProtoNumber(5) val modifiedAt: Long = 0L,
        @ProtoNumber(6) val isDeleted: Boolean = false
    ) {
        fun toCurrent(): SyncPlaylist = SyncPlaylist(
            id = id,
            name = name,
            songs = songs.map { it.toCurrent() },
            createdAt = createdAt,
            modifiedAt = modifiedAt,
            isDeleted = isDeleted
        )
    }

    @Serializable
    private data class LegacySyncSong(
        @ProtoNumber(1) val id: Long = 0L,
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val artist: String = "",
        @ProtoNumber(4) val album: String = "",
        @ProtoNumber(5) val albumId: Long = 0L,
        @ProtoNumber(6) val durationMs: Long = 0L,
        @ProtoNumber(7) val coverUrl: String? = null,
        @ProtoNumber(8) val addedAt: Long = 0L,
        @ProtoNumber(9) val matchedLyric: String? = null,
        @ProtoNumber(10) val matchedTranslatedLyric: String? = null,
        @ProtoNumber(11) val matchedLyricSource: String? = null,
        @ProtoNumber(12) val matchedSongId: String? = null,
        @ProtoNumber(13) val userLyricOffsetMs: Long = 0L,
        @ProtoNumber(14) val customCoverUrl: String? = null,
        @ProtoNumber(15) val customName: String? = null,
        @ProtoNumber(16) val customArtist: String? = null,
        @ProtoNumber(17) val originalName: String? = null,
        @ProtoNumber(18) val originalArtist: String? = null,
        @ProtoNumber(19) val originalCoverUrl: String? = null,
        @ProtoNumber(20) val originalLyric: String? = null,
        @ProtoNumber(21) val originalTranslatedLyric: String? = null
    ) {
        fun toCurrent(): SyncSong = SyncSong(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = albumId,
            durationMs = durationMs,
            coverUrl = coverUrl,
            mediaUri = null,
            addedAt = addedAt,
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            matchedLyricSource = matchedLyricSource,
            matchedSongId = matchedSongId,
            userLyricOffsetMs = userLyricOffsetMs,
            customCoverUrl = customCoverUrl,
            customName = customName,
            customArtist = customArtist,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalLyric,
            originalTranslatedLyric = originalTranslatedLyric,
            channelId = null,
            audioId = null,
            subAudioId = null,
            playlistContextId = null
        )
    }

    @Serializable
    private data class LegacySyncRecentPlay(
        @ProtoNumber(1) val songId: Long = 0L,
        @ProtoNumber(2) val song: LegacySyncSong = LegacySyncSong(),
        @ProtoNumber(3) val playedAt: Long = 0L,
        @ProtoNumber(4) val deviceId: String = ""
    ) {
        fun toCurrent(): SyncRecentPlay = SyncRecentPlay(
            songId = songId,
            song = song.toCurrent(),
            playedAt = playedAt,
            deviceId = deviceId
        )
    }

    @Serializable
    private data class LegacySyncFavoritePlaylist(
        @ProtoNumber(1) val id: Long = 0L,
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val coverUrl: String? = null,
        @ProtoNumber(4) val trackCount: Int = 0,
        @ProtoNumber(5) val source: String = "",
        @ProtoNumber(6) val songs: List<LegacySyncSong> = emptyList(),
        @ProtoNumber(7) val addedTime: Long = 0L
    ) {
        fun toCurrent(): SyncFavoritePlaylist = SyncFavoritePlaylist(
            id = id,
            name = name,
            coverUrl = coverUrl,
            trackCount = trackCount,
            source = source,
            songs = songs.map { it.toCurrent() },
            addedTime = addedTime,
            modifiedAt = addedTime,
            isDeleted = false,
            sortOrder = addedTime
        )
    }

    @Serializable
    private data class LegacySyncLogEntry(
        @ProtoNumber(1) val timestamp: Long = 0L,
        @ProtoNumber(2) val deviceId: String = "",
        @ProtoNumber(3) val action: SyncAction = SyncAction.CREATE_PLAYLIST,
        @ProtoNumber(4) val playlistId: Long? = null,
        @ProtoNumber(5) val songId: Long? = null,
        @ProtoNumber(6) val details: String? = null
    ) {
        fun toCurrent(): SyncLogEntry = SyncLogEntry(
            timestamp = timestamp,
            deviceId = deviceId,
            action = action,
            playlistId = playlistId,
            songId = songId,
            details = details
        )
    }
}
