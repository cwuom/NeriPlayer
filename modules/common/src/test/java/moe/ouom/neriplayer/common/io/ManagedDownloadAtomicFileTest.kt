package moe.ouom.neriplayer.common.io

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDownloadAtomicFileTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `writes create missing parents and replace content without leaving temp files`() {
        val target = File(temporary.newFolder("downloads"), "Artist/Album/song.npmeta.json")

        ManagedDownloadAtomicFile.writeTextAtomically(target, """{"version":1}""")
        ManagedDownloadAtomicFile.writeTextAtomically(target, """{"title":"夜に駆ける"}""")

        assertEquals("""{"title":"夜に駆ける"}""", target.readText(Charsets.UTF_8))
        assertEquals(listOf("song.npmeta.json"), target.parentFile?.list()?.toList())
    }

    @Test
    fun `a rejected replacement removes the temp file and keeps the existing target`() {
        val root = temporary.newFolder("downloads")
        val target = File(root, "song.npmeta.json").apply { mkdir() }
        File(target, "keep.txt").writeText("keep")

        val error = runCatching { ManagedDownloadAtomicFile.writeTextAtomically(target, "new") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(listOf("song.npmeta.json"), root.list()?.toList())
        assertEquals("keep", File(target, "keep.txt").readText())
    }

    @Test
    fun `a parent that is not a directory fails before any temp file is created`() {
        val notADirectory = temporary.newFile("not-a-directory")

        val error = runCatching {
            ManagedDownloadAtomicFile.writeTextAtomically(File(notADirectory, "song.npmeta.json"), "x")
        }.exceptionOrNull()

        assertTrue(error is FileNotFoundException)
        assertTrue(notADirectory.isFile)
        assertEquals(listOf("not-a-directory"), temporary.root.list()?.toList())
    }
}
