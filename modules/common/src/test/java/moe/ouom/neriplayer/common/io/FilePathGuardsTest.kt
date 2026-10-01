package moe.ouom.neriplayer.common.io

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilePathGuardsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsDirectoryAndNestedFiles() {
        val directory = temporaryFolder.newFolder("music")
        val song = directory.resolve("album/song.flac")

        assertTrue(isFileInsideDirectory(directory, directory))
        assertTrue(isFileInsideDirectory(song, directory))
    }

    @Test
    fun rejectsSiblingDirectoryWithTheSamePrefix() {
        val directory = temporaryFolder.newFolder("music")
        val sibling = temporaryFolder.newFolder("music-backup")

        assertFalse(isFileInsideDirectory(sibling.resolve("song.flac"), directory))
    }

    @Test
    fun resolvesParentTraversalBeforeCheckingTheBoundary() {
        val directory = temporaryFolder.newFolder("music")
        directory.resolve("album").mkdir()

        assertTrue(isFileInsideDirectory(directory.resolve("album/../song.flac"), directory))
        assertFalse(isFileInsideDirectory(directory.resolve("album/../../song.flac"), directory))
    }

    @Test
    fun rejectsSymbolicLinksThatEscapeTheDirectory() {
        val directory = temporaryFolder.newFolder("music")
        val outside = temporaryFolder.newFolder("outside")
        val link = directory.resolve("linked-album")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertFalse(isFileInsideDirectory(link.resolve("song.flac"), directory))
    }

    @Test
    fun resolvesTheDirectoryWhenItIsASymbolicLink() {
        val directory = temporaryFolder.newFolder("music")
        val link = temporaryFolder.root.resolve("linked-music")
        Files.createSymbolicLink(link.toPath(), directory.toPath())

        assertTrue(isFileInsideDirectory(directory.resolve("song.flac"), link))
    }

    @Test
    fun fallsBackToAbsolutePathsWhenCanonicalizationFails() {
        val directory = temporaryFolder.newFolder("music").canonicalFile
        val unreadableDirectory = failingCanonicalFile(directory)
        val song = directory.resolve("song.flac")
        val unreadableSong = failingCanonicalFile(song)

        assertTrue(isFileInsideDirectory(unreadableSong, directory))
        assertTrue(isFileInsideDirectory(song, unreadableDirectory))
        assertTrue(isFileInsideDirectory(unreadableSong, unreadableDirectory))
        assertFalse(
            isFileInsideDirectory(
                failingCanonicalFile(requireNotNull(directory.parentFile).resolve("music-backup/song.flac")),
                unreadableDirectory
            )
        )
    }

    private fun failingCanonicalFile(file: File): File = object : File(file.absolutePath) {
        override fun getCanonicalFile(): File = throw IOException("canonical path unavailable")
    }
}
