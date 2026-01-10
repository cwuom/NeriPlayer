package moe.ouom.neriplayer.data.github

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
 * File: moe.ouom.neriplayer.data.github/SyncDataSerializer
 * Created: 2025/1/8
 */

import com.google.gson.Gson
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
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

    private val gson = Gson()
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
    private fun serializeJson(data: SyncData): String {
        return gson.toJson(data)
    }

    /**
     * JSON反序列化
     */
    private fun deserializeJson(content: String): SyncData {
        return gson.fromJson(content, SyncData::class.java)
    }

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
        return protoBuf.decodeFromByteArray(protoBytes)
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
}
