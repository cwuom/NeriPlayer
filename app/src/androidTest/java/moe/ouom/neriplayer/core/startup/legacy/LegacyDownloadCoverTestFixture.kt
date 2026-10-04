package moe.ouom.neriplayer.core.startup.legacy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

internal fun writeLegacyCoverPng(file: File, width: Int = 4_500, height: Int = 4_500) {
    require(width > 0 && height > 0)
    val header = ByteArrayOutputStream(13).also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(width)
            output.writeInt(height)
            output.writeByte(8)
            output.writeByte(2)
            output.writeByte(0)
            output.writeByte(0)
            output.writeByte(0)
        }
    }.toByteArray()
    val compressed = ByteArrayOutputStream().also { bytes ->
        DeflaterOutputStream(bytes).use { output ->
            // 单行包含无滤波标记和黑色 RGB 像素，重复写入避免分配完整位图
            val scanline = ByteArray(1 + width * 3)
            repeat(height) { output.write(scanline) }
        }
    }.toByteArray()
    DataOutputStream(file.outputStream().buffered()).use { output ->
        output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        output.writeLegacyPngChunk("IHDR", header)
        output.writeLegacyPngChunk("IDAT", compressed)
        output.writeLegacyPngChunk("IEND", byteArrayOf())
    }
}

private fun DataOutputStream.writeLegacyPngChunk(name: String, data: ByteArray) {
    val type = name.toByteArray(Charsets.US_ASCII)
    val checksum = CRC32().apply {
        update(type)
        update(data)
    }
    writeInt(data.size)
    write(type)
    write(data)
    writeInt(checksum.value.toInt())
}
