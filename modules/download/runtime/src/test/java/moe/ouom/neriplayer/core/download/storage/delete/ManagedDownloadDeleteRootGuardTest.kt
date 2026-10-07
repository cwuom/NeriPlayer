package moe.ouom.neriplayer.core.download.storage.delete

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDownloadDeleteRootGuardTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val guard = ManagedDownloadDeleteGuard

    @Test
    fun `file references are deletable only below a managed root`() {
        val root = tempFolder.newFolder("NeriPlayer")
        val nested = File(root, "Lyrics/song.lrc").path
        val outside = tempFolder.newFile("song.flac").path
        val traversal = File(root, "../song.flac").path

        assertTrue(guard.isFileReferenceUnderManagedRoot(nested, root.path))
        assertFalse(guard.isFileReferenceUnderManagedRoot(root.path, root.path))
        assertFalse(guard.isFileReferenceUnderManagedRoot(outside, root.path))
        assertFalse(guard.isFileReferenceUnderManagedRoot(traversal, root.path))
    }

    @Test
    fun `unresolvable file paths are never deletable`() {
        val root = tempFolder.newFolder("NeriPlayer")

        assertFalse(guard.isFileReferenceUnderManagedRoot(File(root, "bad\u0000name").path, root.path))
        assertFalse(guard.isFileReferenceUnderManagedRoot(File(root, "song.flac").path, "bad\u0000root"))
    }

    @Test
    fun `file references outside every root are refused and trusted ones are reported`() {
        val firstRoot = tempFolder.newFolder("first")
        val secondRoot = tempFolder.newFolder("second")
        val insideSecond = File(secondRoot, "song.flac").path
        val outside = tempFolder.newFile("stray.flac").path
        val reported = mutableListOf<String>()
        val report: (String) -> Unit = { reference -> reported += reference }
        val roots = listOf(firstRoot.path, secondRoot.path)

        assertTrue(guard.isReferenceAllowedForManagedDelete(insideSecond, emptySet(), roots, emptyList(), report))
        assertFalse(guard.isReferenceAllowedForManagedDelete(outside, setOf(outside), roots, emptyList(), report))
        assertFalse(guard.isReferenceAllowedForManagedDelete(outside, emptySet(), roots, emptyList(), report))
        assertFalse(guard.isReferenceAllowedForManagedDelete(outside, setOf(outside), roots, emptyList()))
        assertFalse(guard.isReferenceAllowedForManagedDelete(insideSecond, setOf(insideSecond), emptyList(), emptyList()))
        assertEquals(listOf(outside), reported)
    }

    @Test
    fun `document references need enumeration evidence regardless of the configured tree`() {
        val treeRoot = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val document = "$treeRoot/document/primary%3AMusic%2FNeriPlayer%2Fsong.flac"
        val reported = mutableListOf<String>()
        val report: (String) -> Unit = { reference -> reported += reference }

        assertTrue(guard.isReferenceAllowedForManagedDelete(document, setOf(document), emptyList(), listOf(treeRoot)))
        assertFalse(guard.isReferenceAllowedForManagedDelete(document, emptySet(), emptyList(), listOf(treeRoot), report))
        assertFalse(guard.isReferenceAllowedForManagedDelete("", setOf(""), listOf("/"), listOf(treeRoot)))
        assertEquals(emptyList<String>(), reported)
    }
}
