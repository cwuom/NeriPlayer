package moe.ouom.neriplayer.core.player.privileged.coloros

import java.security.MessageDigest

data class ColorOsRusRow(
    val version: String,
    val xml: String,
    val hasMd5: Boolean = false,
    val md5: String? = null,
    val hasIsOpen: Boolean = false,
    val isOpen: String? = null
)

interface ColorOsConfigBackend : AutoCloseable {
    val uid: Int
    suspend fun read(): ColorOsRusRow?
    suspend fun compareAndSet(expected: ColorOsRusRow, replacement: ColorOsRusRow): Int
    suspend fun requestReload()
}

data class ColorOsSystemUiAsset(
    val versionCode: Long,
    val lastUpdateTime: Long,
    val xml: String
) {
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest("$versionCode\n$lastUpdateTime\n$xml".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

enum class ColorOsConfigStatus {
    READY, ENABLED, APPLIED_RELOAD_REQUESTED, RESTORED_RELOAD_REQUESTED,
    NOT_CONFIGURED, UNSUPPORTED, PERMISSION_DENIED, CONFLICT, WRITE_OUTCOME_UNKNOWN,
    READBACK_MISMATCH, RELOAD_FAILED, ERROR
}

enum class ColorOsAccessMode { ROOT, SHIZUKU }

data class ColorOsConfigResult(
    val status: ColorOsConfigStatus,
    val uid: Int? = null,
    val canRestore: Boolean = false,
    val detailCode: String? = null
)

class ColorOsConfigException(
    val status: ColorOsConfigStatus,
    val detailCode: String
) : Exception(detailCode)
