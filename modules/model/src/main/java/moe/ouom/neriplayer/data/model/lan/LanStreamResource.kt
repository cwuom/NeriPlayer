package moe.ouom.neriplayer.data.model.lan

import kotlinx.serialization.Serializable

/** Identity of one immutable representation served by the host for the lifetime of a room */
@Serializable
data class LanStreamResource(
    val mediaId: String,
    val mimeType: String,
    val byteLength: Long? = null,
    val presentation: LanFileResource? = null,
    val qualityKey: String? = null
) {
    fun valid(): Boolean = ID.matches(mediaId) &&
        (mimeType.startsWith("audio/") || mimeType == "application/octet-stream") && mimeType.length <= 128 &&
        (byteLength == null || byteLength > 0) &&
        (qualityKey == null || qualityKey.length in 1..64)

    private companion object {
        val ID = Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
    }
}
