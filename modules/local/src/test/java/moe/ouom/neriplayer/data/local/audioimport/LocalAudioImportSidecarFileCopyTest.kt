package moe.ouom.neriplayer.data.local.audioimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalAudioImportSidecarFileCopyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `existing sidecars are copied into missing nested targets`() {
        val source = temporaryFolder.newFile("song.lrc").apply { writeText(LYRICS) }
        val target = File(temporaryFolder.root, "library/Night Drive/song.lrc")

        LocalAudioImportManager.copyIfExists(source, target)

        assertEquals(LYRICS, target.readText())
        assertEquals(LYRICS, source.readText())
    }

    @Test
    fun `missing sources and existing targets are left untouched`() {
        val target = File(temporaryFolder.root, "copied.lrc")
        LocalAudioImportManager.copyIfExists(File(temporaryFolder.root, "missing.lrc"), target)
        assertFalse(target.exists())

        val source = temporaryFolder.newFile("song.lrc").apply { writeText(LYRICS) }
        val existing = temporaryFolder.newFile("existing.lrc").apply { writeText("[00:00.00]kept") }
        LocalAudioImportManager.copyIfExists(source, existing)
        assertEquals("[00:00.00]kept", existing.readText())
    }

    @Test
    fun `copy failures are contained without creating the target`() {
        val source = temporaryFolder.newFile("song.lrc").apply { writeText(LYRICS) }
        val blocker = temporaryFolder.newFile("library")
        val target = File(blocker, "song.lrc")

        LocalAudioImportManager.copyIfExists(source, target)

        assertFalse(target.exists())
        assertTrue(blocker.isFile)
        assertEquals(0L, blocker.length())
    }

    private companion object {
        const val LYRICS = "[00:01.00]Night Drive\n"
    }
}
