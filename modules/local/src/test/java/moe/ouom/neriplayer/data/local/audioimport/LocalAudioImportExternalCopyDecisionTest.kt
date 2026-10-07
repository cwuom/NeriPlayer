package moe.ouom.neriplayer.data.local.audioimport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalAudioImportExternalCopyDecisionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing, directory and empty targets are always copied again`() {
        val missing = File(temporaryFolder.root, "missing.flac")
        val directory = temporaryFolder.newFolder("song.flac")
        val empty = temporaryFolder.newFile("empty.flac")

        assertTrue(LocalAudioImportManager.shouldCopyExternalAudio(missing, expectedBytes = 4))
        assertTrue(LocalAudioImportManager.shouldCopyExternalAudio(directory, expectedBytes = 4))
        assertTrue(LocalAudioImportManager.shouldCopyExternalAudio(empty, expectedBytes = null))
    }

    @Test
    fun `existing targets are copied again only when their size disagrees with the source`() {
        val target = temporaryFolder.newFile("track.flac").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        assertFalse(LocalAudioImportManager.shouldCopyExternalAudio(target, expectedBytes = 4))
        assertFalse(LocalAudioImportManager.shouldCopyExternalAudio(target, expectedBytes = null))
        assertTrue(LocalAudioImportManager.shouldCopyExternalAudio(target, expectedBytes = 5))
    }

    @Test
    fun `copies are complete only when bytes arrived and match a known size`() {
        assertFalse(LocalAudioImportManager.isExternalAudioCopySizeComplete(expectedBytes = null, copiedBytes = 0))
        assertTrue(LocalAudioImportManager.isExternalAudioCopySizeComplete(expectedBytes = null, copiedBytes = 3))
        assertTrue(LocalAudioImportManager.isExternalAudioCopySizeComplete(expectedBytes = 3, copiedBytes = 3))
        assertFalse(LocalAudioImportManager.isExternalAudioCopySizeComplete(expectedBytes = 4, copiedBytes = 3))
    }
}
