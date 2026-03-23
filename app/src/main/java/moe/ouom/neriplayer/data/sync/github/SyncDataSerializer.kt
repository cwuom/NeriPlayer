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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 同步数据序列化工具
 * 支持两种格式：
 * 1. JSON格式（兼容旧版本）-> backup.json
 * 2. ProtoBuf + GZIP压缩（省流模式）-> backup.bin
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

    /**
     * 序列化数据为字符串（用于上传）
     * @param data 同步数据
     * @param useDataSaver 是否使用省流模式
     * @return Base64编码的字符串
     */
    fun serialize(data: SyncData, useDataSaver: Boolean): String {
        return if (useDataSaver) {
            serializeCompressed(data)
        } else {
            serializeJson(data)
        }
    }

    /**
     * 序列化数据为字节数组（用于计算大小）
     * @param data 同步数据
     * @param useDataSaver 是否使用省流模式
     * @return 原始字节数组
     */
    fun serializeToBytes(data: SyncData, useDataSaver: Boolean): ByteArray {
        return if (useDataSaver) {
            val protoBytes = protoBuf.encodeToByteArray(data)
            compress(protoBytes)
        } else {
            serializeJson(data).toByteArray()
        }
    }

    /**
     * 反序列化数据（根据文件名自动检测格式）
     * @param content Base64编码的字符串
     * @param isBinaryFormat 是否为二进制格式（通过文件名判断）
     * @return 同步数据
     */
    fun deserialize(content: String, isBinaryFormat: Boolean): SyncData {
        return if (isBinaryFormat) {
            deserializeCompressed(content)
        } else {
            deserializeJson(content)
        }
    }

    /**
     * JSON序列化
     */
    private fun serializeJson(data: SyncData): String = json.encodeToString(data)

    /**
     * JSON反序列化
     */
    private fun deserializeJson(content: String): SyncData = json.decodeFromString(content)

    /**
     * ProtoBuf + GZIP压缩序列化
     */
    private fun serializeCompressed(data: SyncData): String {
        // ProtoBuf序列化
        val protoBytes = protoBuf.encodeToByteArray(data)

        // GZIP压缩
        val compressedBytes = compress(protoBytes)

        // Base64编码（直接编码，无前缀）
        return android.util.Base64.encodeToString(
            compressedBytes,
            android.util.Base64.NO_WRAP
        )
    }

    /**
     * ProtoBuf + GZIP解压反序列化
     */
    private fun deserializeCompressed(content: String): SyncData {
        // Base64解码
        val compressedBytes = android.util.Base64.decode(content, android.util.Base64.NO_WRAP)

        // GZIP解压
        val protoBytes = decompress(compressedBytes)

        // ProtoBuf反序列化
        return runCatching { protoBuf.decodeFromByteArray<SyncData>(protoBytes) }
            .getOrElse { original ->
                // 兼容旧/错误字段编号的 schema
                val legacy = runCatching { protoBuf.decodeFromByteArray<LegacySyncData>(protoBytes) }.getOrElse { throw original }
                legacy.toCurrent()
            }
    }

    /**
     * GZIP压缩
     */
    private fun compress(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzip ->
            gzip.write(data)
        }
        return outputStream.toByteArray()
    }

    /**
     * GZIP解压
     */
    private fun decompress(data: ByteArray): ByteArray {
        val inputStream = ByteArrayInputStream(data)
        val outputStream = ByteArrayOutputStream()
        GZIPInputStream(inputStream).use { gzip ->
            gzip.copyTo(outputStream)
        }
        return outputStream.toByteArray()
    }

    /**
     * 获取数据大小（用于统计）
     */
    fun getDataSize(data: SyncData, useDataSaver: Boolean): Int {
        return serializeToBytes(data, useDataSaver).size
    }

    /**
     * 计算压缩率
     */
    fun getCompressionRatio(data: SyncData): Float {
        val jsonSize = serializeToBytes(data, false).size
        val compressedSize = serializeToBytes(data, true).size
        return if (jsonSize > 0) {
            (1 - compressedSize.toFloat() / jsonSize) * 100
        } else {
            0f
        }
    }

    /**
     * 获取文件名（根据格式）
     */
    fun getFileName(useDataSaver: Boolean): String {
        return if (useDataSaver) "backup.bin" else "backup.json"
    }

    /**
     * 判断文件名是否为二进制格式
     */
    fun isBinaryFileName(fileName: String): Boolean {
        return fileName.endsWith(".bin")
    }

    /**
     * 兼容旧/错误字段编号的 schema（mediaUri 插入到 addedAt 之前的版本）
     */
    @Serializable
    private data class LegacySyncData(
        @ProtoNumber(1) val version: String = "2.0",
        @ProtoNumber(2) val deviceId: String,
        @ProtoNumber(3) val deviceName: String,
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
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val songs: List<LegacySyncSong>,
        @ProtoNumber(4) val createdAt: Long,
        @ProtoNumber(5) val modifiedAt: Long,
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
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val artist: String,
        @ProtoNumber(4) val album: String,
        @ProtoNumber(5) val albumId: Long,
        @ProtoNumber(6) val durationMs: Long,
        @ProtoNumber(7) val coverUrl: String?,
        @ProtoNumber(8) val addedAt: Long = System.currentTimeMillis(),
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
            originalTranslatedLyric = originalTranslatedLyric
        )
    }

    @Serializable
    private data class LegacySyncRecentPlay(
        @ProtoNumber(1) val songId: Long,
        @ProtoNumber(2) val song: LegacySyncSong,
        @ProtoNumber(3) val playedAt: Long,
        @ProtoNumber(4) val deviceId: String
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
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val coverUrl: String?,
        @ProtoNumber(4) val trackCount: Int,
        @ProtoNumber(5) val source: String,
        @ProtoNumber(6) val songs: List<LegacySyncSong>,
        @ProtoNumber(7) val addedTime: Long
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
        @ProtoNumber(1) val timestamp: Long,
        @ProtoNumber(2) val deviceId: String,
        @ProtoNumber(3) val action: SyncAction,
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
