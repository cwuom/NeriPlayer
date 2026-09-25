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

        assertTrue(urlSource.contains("resolvePermittedLocalPlayback("))
        assertTrue(urlSource.contains("resolvePermittedLocalPlaybackWithRetry("))
        assertTrue(urlSource.contains("resolveIndexedLocalPlaybackWithRetry(context, song)"))
        assertTrue(urlSource.contains("shouldRetryLocalPlaybackResolution"))
        assertTrue(
            localPlayback.contains("localResolution is LocalPlaybackReferenceResolution.Missing")
        )
        assertTrue(urlSource.contains("resolveIndexedLocalPlaybackReference(context, song)"))
        assertTrue(urlSource.contains("remote fallback is blocked"))
        assertTrue(
            urlSource.indexOf("val localResult = checkLocalCache(song, sideEffects)") <
                urlSource.indexOf("shouldUseDirectStreamShortcut(")
        )
        assertTrue(fallbackSource.contains("internal suspend fun PlayerManager.tryResolveNeteaseMatchedLocalSource"))
        assertTrue(fallbackSource.contains("resolvePermittedLocalPlaybackUri("))
        assertTrue(urlSource.contains("consumeGenericUrlPrefetch(cacheKey, song)"))
        assertTrue(prefetchSource.contains("tryResolveNeteaseMatchedLocalSource(song)"))

        val downloadSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadManager.kt"
        ).readText()
        val playbackSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadPlaybackCoordinator.kt"
        ).readText()
        assertTrue(playbackSource.contains("peekPendingDownloadedAudio(song)"))
        assertTrue(playbackSource.contains("metadataForAudioEntry(snapshot,"))
        val localUriBody = playbackSource.substringAfter("fun getLocalPlaybackUri")
            .substringBefore("suspend fun resolvePermittedLocalPlaybackUri")
        val indexedLookupBody = playbackSource.substringAfter("fun mayHaveIndexedLocalDownload")
            .substringBefore("fun hasLocalDownload")
        val clearCompletedReferenceBody = downloadSource
            .substringAfter("private fun clearCompletedAudioReference")
            .substringBefore("private fun clearPartialSidecarReferences")
        assertTrue(localUriBody.contains("resolveRecentlyCommittedAudioReference("))
        assertTrue(indexedLookupBody.contains("peekCompletedAudioReference(song)"))
        assertTrue(
            clearCompletedReferenceBody.contains(
                "completedAudioReferenceRegistry.clearCompletedAudioReference"
            )
        )
    }

    @Test
    fun `migration file-not-found recovery invalidates stale bridge before refresh`() {
        val lifecycleSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/lifecycle/" +
                "PlayerManagerLifecycleExtensions.kt"
        ).readText()
        val errorBody = lifecycleSource.substringAfter("override fun onPlayerError")
            .substringBefore("override fun onPlaybackStateChanged")

        assertTrue(errorBody.contains("invalidateCompletedAudioReference"))
        assertTrue(errorBody.contains("shouldRecoverMissingLocalPlayback"))
        assertTrue(errorBody.contains("currentUrl = currentUrl"))
        assertTrue(errorBody.contains("allowLocalSongRecovery = isLocalFileMissingRecovery"))
        assertTrue(
            errorBody.contains("!isLocalFileMissingRecovery &&\n" +
                "                    shouldResumeAfterRecovery")
        )
    }

    @Test
    fun `catalog ready miss still probes the durable snapshot before NotIndexed`() {
        val playbackSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadPlaybackCoordinator.kt"
        ).readText()
        val indexedLookupBody = playbackSource.substringAfter(
            "fun mayHaveIndexedLocalDownload"
        ).substringBefore("fun hasLocalDownload")
        val durableLookupBody = playbackSource.substringAfter(
            "private fun findDurableCachedManagedAudio"
        ).substringBefore("private fun canUseReadableManagedAudioForPlayback")
        val durableProbeIndex = indexedLookupBody.indexOf(
            "findDurableCachedManagedAudio("
        )
        val catalogReadyIndex = indexedLookupBody.indexOf(
            "val catalogReady = GlobalDownloadManager.isDownloadedSongCatalogReady()"
        )
        assertTrue("durable snapshot lookup must be present", durableProbeIndex >= 0)
        assertTrue(
            "catalogReady state must gate one durable lookup",
            catalogReadyIndex >= 0 && durableProbeIndex > catalogReadyIndex
        )
        assertTrue(indexedLookupBody.contains("restorePersisted = catalogReady"))
        assertTrue(durableLookupBody.contains("restorePersisted = restorePersisted"))
        assertTrue(durableLookupBody.contains("cachedDownloadLibrarySnapshot"))
        assertTrue(!durableLookupBody.contains("scanLocalFiles"))

        val indexedPlaybackBody = playbackSource.substringAfter(
            "internal fun resolveIndexedLocalPlaybackReference"
        ).substringBefore("private fun isRecentManagedPlaybackReference")
        assertTrue(indexedPlaybackBody.contains("durableCachedAudio?.audio"))
        assertTrue(indexedPlaybackBody.contains("restorePersisted = false"))
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate)
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
