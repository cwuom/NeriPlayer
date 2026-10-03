package moe.ouom.neriplayer.platform.netease.api.request

internal fun buildNeteaseFollowedArtistsParams(offset: Int, limit: Int): Map<String, String> {
    require(offset >= 0) { "Offset must not be negative" }
    require(limit > 0) { "Limit must be positive" }
    return mapOf("offset" to offset.toString(), "limit" to limit.toString(), "total" to "true")
}
