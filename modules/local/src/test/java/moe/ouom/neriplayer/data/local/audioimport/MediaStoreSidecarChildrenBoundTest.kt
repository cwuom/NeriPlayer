package moe.ouom.neriplayer.data.local.audioimport

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class MediaStoreSidecarChildrenBoundTest {
    @Test
    fun `nothing is cached for a non positive budget or an empty directory`() {
        assertTrue(boundMediaStoreSidecarChildrenForCache(listOf(child("a.mp3")), maxEntries = 0).isEmpty())
        assertTrue(boundMediaStoreSidecarChildrenForCache(emptyList(), maxEntries = 4).isEmpty())
    }

    @Test
    fun `directories within the budget are cached unchanged`() {
        val children = listOf(child("a.mp3"), child("notes.pdf"))

        assertSame(children, boundMediaStoreSidecarChildrenForCache(children, maxEntries = 2))
    }

    @Test
    fun `folders and audio fill the budget before any sidecar`() {
        val children = listOf(
            child("cover.jpg"),
            child("Disc 1", isDirectory = true),
            child("a.mp3"),
            child("notes.pdf"),
            child("b.flac")
        )

        assertEquals(
            listOf("Disc 1", "a.mp3"),
            boundMediaStoreSidecarChildrenForCache(children, maxEntries = 2).map { it.displayName }
        )
    }

    @Test
    fun `sidecars take the remaining budget before other files`() {
        val children = listOf(
            child("notes.pdf"),
            child("track", mimeType = "Audio/ogg"),
            child("readme.md"),
            child("cover.JPG"),
            child("song.lrc"),
            child("song.npmeta.json")
        )

        assertEquals(
            listOf("track", "cover.JPG", "song.lrc"),
            boundMediaStoreSidecarChildrenForCache(children, maxEntries = 3).map { it.displayName }
        )
    }

    @Test
    fun `other files fill whatever budget is left in their original order`() {
        val children = listOf(
            child("notes.pdf"),
            child("a.mp3"),
            child("readme.md"),
            child("song.lrc"),
            child("blob.bin")
        )

        assertEquals(
            listOf("a.mp3", "song.lrc", "notes.pdf", "readme.md"),
            boundMediaStoreSidecarChildrenForCache(children, maxEntries = 4).map { it.displayName }
        )
    }

    private fun child(
        name: String,
        isDirectory: Boolean = false,
        mimeType: String = if (isDirectory) "vnd.android.document/directory" else "application/octet-stream"
    ) = QueriedFolderChild(
        documentUri = mock(Uri::class.java),
        displayName = name,
        mimeType = mimeType,
        isDirectory = isDirectory
    )
}
