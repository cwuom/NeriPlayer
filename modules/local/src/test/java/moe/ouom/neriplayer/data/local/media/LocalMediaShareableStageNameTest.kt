package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class LocalMediaShareableStageNameTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `stage names hash the source identity and keep its extension`() {
        val source = temporaryFolder.newFile("Night Drive.flac").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(1_700_000_000_000L)
        }

        assertEquals(
            sha256("${source.absolutePath}|3|${source.lastModified()}") + ".flac",
            LocalMediaSupport.shareableStageFileName(source)
        )
    }

    @Test
    fun `sources without an extension get a bare hashed name`() {
        val source = temporaryFolder.newFile("README").apply { writeBytes(byteArrayOf(9)) }

        assertEquals(
            sha256("${source.absolutePath}|1|${source.lastModified()}"),
            LocalMediaSupport.shareableStageFileName(source)
        )
    }

    @Test
    fun `changing the source contents changes the stage name`() {
        val source = temporaryFolder.newFile("song.mp3").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(1_700_000_000_000L)
        }
        val before = LocalMediaSupport.shareableStageFileName(source)

        source.writeBytes(byteArrayOf(1, 2))
        source.setLastModified(1_700_000_000_000L)

        assertNotEquals(before, LocalMediaSupport.shareableStageFileName(source))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
