package moe.ouom.neriplayer.core.player.persistence

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerPersistenceReferenceTest {
    @Test
    fun `metadata and sidecar writes route local references through the managed completion gate`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/persistence/" +
                "PlayerManagerPersistenceExtensions.kt"
        ).readText()
        val embeddedWrite = source.substringAfter("private suspend fun PlayerManager.writeLocalEditableMetadata")
            .substringBefore("private suspend fun PlayerManager.resolveLocalMetadataWriteReference")
        val referenceResolver = source.substringAfter(
            "private suspend fun PlayerManager.resolveLocalMetadataWriteReference"
        ).substringBefore("private suspend fun PlayerManager.resolveLocalSidecarWriteSong")
        val sidecarResolver = source.substringAfter(
            "private suspend fun PlayerManager.resolveLocalSidecarWriteSong"
        ).substringBefore("private fun PlayerManager.showLocalEditableMetadataWriteFeedback")

        assertTrue(
            embeddedWrite.contains("?: return LocalMediaMetadataWriteOutcome.NOT_WRITABLE")
        )
        assertFalse(referenceResolver.contains("return directReference"))
        assertTrue(referenceResolver.contains("resolvePermittedLocalPlaybackUri("))
        assertTrue(sidecarResolver.contains("?: return null"))
        assertFalse(sidecarResolver.contains("?: return song"))
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
