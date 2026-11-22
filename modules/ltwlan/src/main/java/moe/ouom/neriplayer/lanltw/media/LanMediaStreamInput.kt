package moe.ouom.neriplayer.lanltw.media

import java.io.InputStream

/**
 * the stream starts at the requested byte offset and its length is the actual number of bytes
 * available within the requested limit, or null if unavailable until EOF
 * closing the stream releases its underlying data source
 */
data class LanMediaStreamInput(val stream: InputStream, val byteLength: Long?)
