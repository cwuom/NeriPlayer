package moe.ouom.neriplayer.core.download

import java.io.File
import java.io.IOException
import moe.ouom.neriplayer.core.download.storage.operation.content.promoteFileTargetWithoutReplacement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDownloadZeroBytePromotionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `empty pending audio is retained without creating a final file`() {
        val pending = temporaryFolder.newFile("song.mp3.npdl_pending.test.pending")
        val target = File(temporaryFolder.root, "song.mp3")

        assertThrows(IOException::class.java) {
            ManagedDownloadStorage.promoteFileTargetWithoutReplacement(
                pending = pending,
                target = target,
                displayName = target.name
            )
        }

        assertTrue(pending.isFile)
        assertFalse(target.exists())
    }
}
