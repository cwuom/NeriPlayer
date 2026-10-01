package moe.ouom.neriplayer.data.sync.mapping

import moe.ouom.neriplayer.data.sync.CoverUrlMapper

internal fun sanitizeCoverUrlForSync(
    coverUrl: String?,
    mapper: CoverUrlMapper? = null
): String? {
    val normalizedUrl = coverUrl?.trim().orEmpty()
    val directUrl = moe.ouom.neriplayer.data.sync.policy.sanitizeCoverUrlForSync(normalizedUrl)
    if (directUrl != null) return directUrl
    return mapper?.getSyncableNetworkUrl(normalizedUrl)
}
