package moe.ouom.neriplayer.core.player.url

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLocalPlaybackGateTest {
    @Test
    fun `local playback and fallback paths require the managed completion gate`() {
        val urlSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/url/" +
                "PlayerManagerUrlExtensions.kt"
        ).readText()
        val localPlayback = urlSource.substringAfter("if (isLocalSong(song)) {")
            .substringBefore("val localResult = checkLocalCache")
        val fallbackSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/resolver/netease/" +
                "PlayerManagerNeteaseLocalFallback.kt"
        ).readText()
        val prefetchSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/prefetch/" +
                "PlayerManagerGenericUrlPrefetch.kt"
        ).readText()

        assertTrue(localPlayback.contains("resolvePermittedLocalPlaybackUri("))
        assertTrue(fallbackSource.contains("internal suspend fun PlayerManager.tryResolveNeteaseMatchedLocalSource"))
        assertTrue(fallbackSource.contains("resolvePermittedLocalPlaybackUri("))
        assertTrue(urlSource.contains("consumeGenericUrlPrefetch(cacheKey, song)"))
        assertTrue(prefetchSource.contains("tryResolveNeteaseMatchedLocalSource(song)"))
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
