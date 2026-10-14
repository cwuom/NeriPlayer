package moe.ouom.neriplayer.data.local.media.curation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderArtworkLocatorTest {

    @Test
    fun `absolute directories map to external storage document paths`() {
        assertEquals(
            StorageDocumentPath("primary", "Music/Album"),
            FolderArtworkLocator.storagePathOf("/storage/emulated/0/Music/Album")
        )
        assertEquals(
            StorageDocumentPath("1A2B-3C4D", "Music"),
            FolderArtworkLocator.storagePathOf("/storage/1a2b-3c4d/Music/")
        )
        assertEquals(StorageDocumentPath("primary", ""), FolderArtworkLocator.storagePathOf("/storage/emulated/0"))
        assertNull(FolderArtworkLocator.storagePathOf("/data/user/0/app/files"))
    }

    @Test
    fun `granted trees cover their own folder and descendants only`() {
        val path = StorageDocumentPath("primary", "Music/Album/Disc 1")

        assertTrue(path.isInside("primary:Music"))
        assertTrue(path.isInside("primary:Music/Album/Disc 1"))
        assertTrue(path.isInside("primary:"))
        assertFalse(path.isInside("primary:Music/Alb"))
        assertFalse(path.isInside("primary:Movies"))
        assertFalse(path.isInside("1A2B-3C4D:Music"))
    }

    @Test
    fun `folder artwork follows cover folder front album priority`() {
        assertEquals(
            "cover.jpg",
            FolderArtworkLocator.pickFolderArtwork(listOf("front.png", "Folder.jpg", "cover.jpg", "song.jpg"))
        )
        assertEquals("Folder.JPG", FolderArtworkLocator.pickFolderArtwork(listOf("Folder.JPG", "album.png")))
        assertEquals("cover.jpg", FolderArtworkLocator.pickFolderArtwork(listOf("cover.webp", "cover.jpg")))
        assertNull(FolderArtworkLocator.pickFolderArtwork(listOf("01 song.jpg", "cover.txt", "cover")))
    }

    @Test
    fun `common ancestor lets one grant cover a whole batch`() {
        val ancestor = FolderArtworkLocator.commonAncestor(
            listOf(
                StorageDocumentPath("primary", "Music/Jay/叶惠美"),
                StorageDocumentPath("primary", "Music/Jay/范特西"),
                StorageDocumentPath("primary", "Music/Jay")
            )
        )

        assertEquals(StorageDocumentPath("primary", "Music/Jay"), ancestor)
        assertEquals(
            StorageDocumentPath("primary", ""),
            FolderArtworkLocator.commonAncestor(
                listOf(StorageDocumentPath("primary", "Music"), StorageDocumentPath("primary", "Download"))
            )
        )
        assertNull(
            FolderArtworkLocator.commonAncestor(
                listOf(StorageDocumentPath("primary", "Music"), StorageDocumentPath("1A2B-3C4D", "Music"))
            )
        )
        assertNull(FolderArtworkLocator.commonAncestor(emptyList()))
    }
}
