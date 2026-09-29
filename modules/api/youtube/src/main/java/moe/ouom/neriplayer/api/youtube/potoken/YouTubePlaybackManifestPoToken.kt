package moe.ouom.neriplayer.api.youtube.potoken

import java.net.URI
import moe.ouom.neriplayer.api.youtube.challenge.extractStreamQueryParameter
import moe.ouom.neriplayer.api.youtube.challenge.replaceStreamQueryParameter

internal fun hasWebRemixManifestPoToken(manifestUrl: String): Boolean {
    if (manifestUrl.isBlank()) return false
    return hasManifestQueryPoToken(manifestUrl) || "/pot/" in manifestUrl
}

private fun hasManifestQueryPoToken(url: String): Boolean =
    !extractStreamQueryParameter(url, "pot").isNullOrBlank()

internal fun appendWebRemixManifestPoToken(manifestUrl: String, poToken: String): String {
    if (!shouldAppendManifestPoToken(manifestUrl, poToken)) return manifestUrl
    val pathUri = manifestPathUri(manifestUrl)
    return pathUri?.let { appendManifestPathPoToken(it, poToken) }
        ?: replaceStreamQueryParameter(manifestUrl, "pot", poToken)
}

private fun shouldAppendManifestPoToken(manifestUrl: String, poToken: String): Boolean =
    manifestUrl.isNotBlank() && poToken.isNotBlank() && !hasWebRemixManifestPoToken(manifestUrl)

private fun manifestPathUri(url: String): URI? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    return uri.takeIf(::isManifestPath)
}

private fun isManifestPath(uri: URI): Boolean =
    uri.rawPath?.contains("/api/manifest/") == true

private fun appendManifestPathPoToken(uri: URI, poToken: String): String {
    val rawPath = uri.rawPath.orEmpty().removeSuffix("/")
    return runCatching {
        URI(uri.scheme, uri.rawAuthority, "$rawPath/pot/$poToken", uri.rawQuery, uri.rawFragment)
            .toString()
    }.getOrElse { replaceStreamQueryParameter(uri.toString(), "pot", poToken) }
}

internal fun carryForwardWebRemixManifestPoToken(
    masterManifestUrl: String,
    playlistUrl: String
): String {
    if (!needsManifestTokenCarry(playlistUrl)) return playlistUrl
    val poTokenFromQuery = extractStreamQueryParameter(masterManifestUrl, "pot")
    if (!poTokenFromQuery.isNullOrBlank()) return replaceStreamQueryParameter(playlistUrl, "pot", poTokenFromQuery)
    return carryManifestPathToken(masterManifestUrl, playlistUrl)
}

private fun needsManifestTokenCarry(playlistUrl: String): Boolean =
    playlistUrl.isNotBlank() && !hasWebRemixManifestPoToken(playlistUrl)

private fun carryManifestPathToken(masterManifestUrl: String, playlistUrl: String): String {
    val poTokenFromPath = manifestPathPoToken(masterManifestUrl)
    return if (poTokenFromPath.isBlank()) playlistUrl
    else appendWebRemixManifestPoToken(playlistUrl, poTokenFromPath)
}

private fun manifestPathPoToken(url: String): String =
    Regex("/pot/([^/?#]+)").find(url)?.groupValues?.getOrNull(1).orEmpty()
