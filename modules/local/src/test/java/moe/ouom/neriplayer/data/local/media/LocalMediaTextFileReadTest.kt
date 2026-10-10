package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalMediaTextFileReadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `small lyric files are decoded as text`() {
        val lyric = temporaryFolder.newFile("song.lrc").apply { writeText("[00:01.00]你好\n[00:02.00]world\n") }

        assertEquals("[00:01.00]你好\n[00:02.00]world\n", LocalMediaSupport.readTextFileImpl(lyric))
    }

    @Test
    fun `missing and oversized lyric files are not read`() {
        val oversized = temporaryFolder.newFile("huge.lrc").apply {
            writeBytes(ByteArray((MAX_LOCAL_LYRIC_BYTES + 1).toInt()) { 'a'.code.toByte() })
        }

        assertNull(LocalMediaSupport.readTextFileImpl(File(temporaryFolder.root, "missing.lrc")))
        assertNull(LocalMediaSupport.readTextFileImpl(oversized))
    }
}
