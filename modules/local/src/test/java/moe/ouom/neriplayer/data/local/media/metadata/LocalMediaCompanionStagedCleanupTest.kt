package moe.ouom.neriplayer.data.local.media.metadata

import android.system.Os
import android.system.StructStat
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalMediaCompanionStagedCleanupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a staged companion that is still the recorded object is deleted`() {
        val staged = temporaryFolder.newFile(".Night Drive.lrc.companion-1.tmp")

        val cleaned = withStatIdentity { os ->
            cleanupCompanionStagedFile(entry(staged, stagedIdentity = UNSTUBBED_IDENTITY)).also {
                os.verify { Os.stat(staged.absolutePath) }
            }
        }

        assertTrue(cleaned)
        assertFalse(staged.exists())
    }

    @Test
    fun `a staged companion replaced by another object is kept`() {
        val staged = temporaryFolder.newFile(".Night Drive.lrc.companion-2.tmp")

        val cleaned = withStatIdentity { cleanupCompanionStagedFile(entry(staged, stagedIdentity = "41:42")) }

        assertFalse(cleaned)
        assertTrue(staged.exists())
    }

    @Test
    fun `entries without an existing staged file need no identity check`() {
        val missing = File(temporaryFolder.root, ".Night Drive.lrc.companion-3.tmp")

        withStatIdentity { os ->
            assertTrue(cleanupCompanionStagedFile(entry(stagedFile = null, stagedIdentity = null)))
            assertTrue(cleanupCompanionStagedFile(entry(missing, stagedIdentity = UNSTUBBED_IDENTITY)))
            os.verifyNoInteractions()
        }
    }

    private fun <T> withStatIdentity(block: (MockedStatic<Os>) -> T): T =
        mockStatic(Os::class.java).use { os ->
            os.`when`<StructStat> { Os.stat(anyString()) }.thenAnswer { mock(StructStat::class.java) }
            block(os)
        }

    private fun entry(stagedFile: File?, stagedIdentity: String?) = LocalMediaCompanionRecoveryEntry(
        reference = "/music/Night Drive.lrc",
        backupFile = null,
        originalSha256 = null,
        expectedSha256 = null,
        originalLastModifiedMs = null,
        createdByTransaction = true,
        stagedFile = stagedFile,
        stagedIdentity = stagedIdentity
    )

    private companion object {
        // A mocked StructStat keeps st_dev and st_ino at zero
        const val UNSTUBBED_IDENTITY = "0:0"
    }
}
