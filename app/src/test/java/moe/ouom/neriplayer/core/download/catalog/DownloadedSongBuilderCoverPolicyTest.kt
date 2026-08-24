package moe.ouom.neriplayer.core.download.catalog

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.policy.shouldInspectDownloadedAudioDetails
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongBuilderCoverPolicyTest {

    @Test
    fun `restored remote cover does not fall back to a stale indexed sidecar`() {
        val originalCover = "https://example.com/original-cover.jpg"

        assertFalse(
            shouldUseIndexedDownloadedCoverFallback(
                ManagedDownloadStorage.DownloadedAudioMetadata(
                    coverUrl = originalCover,
                    originalCoverUrl = originalCover,
                    customCoverUrl = null,
                    coverPath = null
                )
            )
        )
    }

    @Test
    fun `ordinary downloads retain indexed cover fallback`() {
        assertTrue(
            shouldUseIndexedDownloadedCoverFallback(
                ManagedDownloadStorage.DownloadedAudioMetadata(
                    coverUrl = "https://example.com/cover.jpg"
                )
            )
        )
    }

    @Test
    fun `local cover references remain distinguishable from remote metadata urls`() {
        assertTrue(isResolvableLocalReference("content://downloads/Covers/song.jpg"))
        assertTrue(isResolvableLocalReference("file:///data/user/0/app/song.jpg"))
        assertTrue(isResolvableLocalReference("file:/data/user/0/app/song.jpg"))
        assertTrue(isResolvableLocalReference("/storage/emulated/0/song.jpg"))
        assertFalse(isResolvableLocalReference("https://example.com/song.jpg"))
    }

    @Test
    fun `slow metadata cover fallback is disabled for fast catalog hydration`() {
        assertFalse(
            shouldInspectDownloadedAudioDetails(
                allowSlowLocalInspection = false,
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    name = "Song",
                    artist = "Artist",
                    durationMs = 1_000L
                ),
                coverReference = null,
                needsLocalLyricFallback = false
            )
        )
    }

    @Test
    fun `only accessible typed evidence is accepted as a managed reference candidate`() {
        assertTrue(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.Accessible
            )
        )
        assertFalse(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.Missing
            )
        )
        assertFalse(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.PermissionLost
            )
        )
        assertFalse(
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.AccessResult.ProviderFailure(
                    IllegalStateException("provider unavailable")
                )
            )
        )
    }
}
