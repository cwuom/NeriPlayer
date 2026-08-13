package moe.ouom.neriplayer.data.platform.netease

import java.security.MessageDigest

private const val NETEASE_RADAR_PUBLIC_CACHE_CONTEXT = "public-v1"
private const val NETEASE_RADAR_ACCOUNT_CACHE_CONTEXT_PREFIX = "account-sha256-v1:"
private const val NETEASE_RADAR_CACHE_CONTEXT_DOMAIN = "neriplayer-radar-cache-v1:"
private const val NETEASE_RADAR_PLAYLIST_CACHE_KEY_PREFIX = "radar-v1/"

internal fun neteaseRadarCacheContext(cookies: Map<String, String>): String {
    val musicU = cookies["MUSIC_U"]?.trim().orEmpty()
    if (musicU.isEmpty()) return NETEASE_RADAR_PUBLIC_CACHE_CONTEXT

    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(
            "$NETEASE_RADAR_CACHE_CONTEXT_DOMAIN$musicU"
                .toByteArray(Charsets.UTF_8)
        )
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "$NETEASE_RADAR_ACCOUNT_CACHE_CONTEXT_PREFIX$fingerprint"
}

internal fun neteaseRadarPlaylistCacheKey(
    playlistId: Long,
    radarCacheContext: String?
): String {
    val context = radarCacheContext?.trim().orEmpty()
    if (context.isEmpty()) return playlistId.toString()
    return "$NETEASE_RADAR_PLAYLIST_CACHE_KEY_PREFIX$playlistId/$context"
}
