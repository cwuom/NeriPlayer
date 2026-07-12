package moe.ouom.neriplayer.util

import java.io.InputStream
import moe.ouom.neriplayer.util.io.readBytesCompat as readBytesCompatImpl
import moe.ouom.neriplayer.util.io.readBytesLimited as readBytesLimitedImpl

fun InputStream.readBytesCompat(bufferSize: Int = 8 * 1024): ByteArray {
    return this.readBytesCompatImpl(bufferSize)
}

fun InputStream.readBytesLimited(
    maxBytes: Long,
    bufferSize: Int = 8 * 1024
): ByteArray {
    return this.readBytesLimitedImpl(maxBytes, bufferSize)
}
