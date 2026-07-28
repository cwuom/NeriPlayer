package moe.ouom.neriplayer.data.sync.github

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.security.MessageDigest

internal data class GitHubReleaseSyncManifest(
    @SerializedName("formatVersion")
    val formatVersion: Int,
    @SerializedName("assetId")
    val assetId: Long,
    @SerializedName("assetName")
    val assetName: String,
    @SerializedName("contentSize")
    val contentSize: Long,
    @SerializedName("contentSha256")
    val contentSha256: String
) {
    fun validate() {
        require(formatVersion == FORMAT_VERSION) {
            "Unsupported GitHub sync manifest version: $formatVersion"
        }
        require(assetId > 0L) { "GitHub sync manifest has no asset id" }
        require(assetName.isNotBlank()) { "GitHub sync manifest has no asset name" }
        require(contentSize > 0L) { "GitHub sync manifest has invalid content size" }
        require(contentSha256.matches(SHA256_REGEX)) {
            "GitHub sync manifest has invalid SHA-256"
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")

        fun create(assetId: Long, assetName: String, content: ByteArray): GitHubReleaseSyncManifest {
            return GitHubReleaseSyncManifest(
                formatVersion = FORMAT_VERSION,
                assetId = assetId,
                assetName = assetName,
                contentSize = content.size.toLong(),
                contentSha256 = sha256(content)
            )
        }

        fun parse(json: String, gson: Gson): GitHubReleaseSyncManifest {
            val manifest = gson.fromJson(json, GitHubReleaseSyncManifest::class.java)
                ?: throw IllegalArgumentException("Empty GitHub sync manifest")
            manifest.validate()
            return manifest
        }

        fun sha256(content: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(content)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
