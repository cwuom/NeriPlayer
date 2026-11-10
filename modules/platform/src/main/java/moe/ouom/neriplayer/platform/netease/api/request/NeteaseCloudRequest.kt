package moe.ouom.neriplayer.platform.netease.api.request

internal fun buildNeteaseCloudParams(offset: Int, limit: Int): Map<String, String> {
    require(offset >= 0) { "Offset must not be negative" }
    require(limit in 1..1000) { "Limit must be in 1..1000" }
    return mapOf("offset" to offset.toString(), "limit" to limit.toString())
}
