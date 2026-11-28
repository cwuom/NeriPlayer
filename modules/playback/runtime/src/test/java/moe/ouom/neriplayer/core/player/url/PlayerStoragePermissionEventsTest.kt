package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.core.player.testing.locatePlayerProjectFile
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.StoragePermissionFallback
import moe.ouom.neriplayer.data.model.playback.StoragePermissionScope
import moe.ouom.neriplayer.data.model.playback.storage.PlayerLocalPlaybackResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStoragePermissionEventsTest {

    @Test
    fun `local permission event keeps the lost reference and drops blank values`() {
        val reference = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fa.flac"
        assertEquals(
            PlayerEvent.StoragePermissionLost(StoragePermissionScope.LOCAL_MEDIA, StoragePermissionFallback.SKIPPED, reference),
            localStoragePermissionLostEvent(" $reference ", StoragePermissionFallback.SKIPPED)
        )
        assertEquals(null, localStoragePermissionLostEvent("  ", StoragePermissionFallback.STREAMING).reference)
    }

    @Test
    fun `download directory permission event always reports the streaming fallback`() {
        assertEquals(
            PlayerEvent.StoragePermissionLost(StoragePermissionScope.DOWNLOAD_DIRECTORY, StoragePermissionFallback.STREAMING),
            downloadDirectoryPermissionLostEvent()
        )
    }

    @Test
    fun `only lost grants are treated as permission failures`() {
        assertTrue(PlayerLocalPlaybackResolution.PermissionLost("denied").isPermissionLost())
        assertFalse(PlayerLocalPlaybackResolution.TemporarilyUnavailable("busy").isPermissionLost())
        assertFalse(PlayerLocalPlaybackResolution.Missing.isPermissionLost())
    }

    @Test
    fun `indexed downloads fall back to streaming only after a lost grant`() {
        val urlSource = locatePlayerProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/url/PlayerManagerUrlExtensions.kt"
        ).readText()
        val indexedBody = urlSource.substringAfter("private suspend fun PlayerManager.checkLocalCache(")
            .substringBefore("val durationMs =")
        val permissionLostBranch = indexedBody.substringAfter("is LocalPlaybackReferenceResolution.PermissionLost ->")
            .substringBefore("is LocalPlaybackReferenceResolution.TemporarilyUnavailable ->")
        val temporaryBranch = indexedBody.substringAfter("is LocalPlaybackReferenceResolution.TemporarilyUnavailable ->")
        val localBranch = urlSource.substringAfter("if (isLocalSong(song)) {")
            .substringBefore("val localResult = checkLocalCache")

        assertTrue(permissionLostBranch.contains("return reportIndexedPermissionLost("))
        assertTrue(temporaryBranch.contains("return SongUrlResult.Failure"))
        assertTrue(localBranch.contains("localResolution.isPermissionLost()"))
        assertTrue(localBranch.contains("reportLocalPermissionLostFallback(localResolution"))
        assertTrue(localBranch.contains("return reportLocalPlaybackFailure("))
    }
}
