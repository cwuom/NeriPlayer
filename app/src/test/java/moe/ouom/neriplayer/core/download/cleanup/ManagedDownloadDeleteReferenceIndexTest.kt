package moe.ouom.neriplayer.core.download.cleanup

import org.junit.Assert.*
import org.junit.Test

class ManagedDownloadDeleteReferenceIndexTest {
    @Test fun `invalid raw references cannot collide with parsed document keys`() {
        val index = ManagedDownloadDeleteReferenceIndex(listOf("content://provider/document/id"))
        assertNull(index.resolve("provider\u0000id"))
        val emptyId = ManagedDownloadDeleteReferenceIndex(listOf("content://provider/tree/root/document/"))
        assertNull(emptyId.resolve("content://provider/document/"))
        val malformed = ManagedDownloadDeleteReferenceIndex(listOf("content://provider/tree/root/document/broken%zz"))
        assertNull(malformed.resolve("content://provider/document/broken%zz"))
    }

    @Test fun `single and tree document uris resolve only exact authority and opaque id`() {
        val enumerated = "content://provider/tree/root/document/opaque%2Fone%2Btwo"
        val direct = "content://provider/document/opaque%2Fone%2Btwo"
        val index = ManagedDownloadDeleteReferenceIndex(listOf(enumerated), mapOf(direct to setOf("owner")))
        assertEquals(enumerated, index.resolve(direct))
        assertEquals(setOf("owner"), index.ownersByReference[enumerated])
        assertNull(index.resolve("content://other/document/opaque%2Fone%2Btwo"))
        assertNull(index.resolve("content://provider/document/opaque%2Fone%20two"))
        assertNull(index.resolve("content://provider/tree/root/document/unknown"))
        assertNull(index.resolve("content://provider/tree/root"))
    }

    @Test fun `file uri resolves only when exact path was enumerated`() {
        val index = ManagedDownloadDeleteReferenceIndex(listOf("/library/a b.mp3"))
        assertEquals("/library/a b.mp3", index.resolve("file:///library/a%20b.mp3"))
        assertNull(index.resolve("/library/a.mp3"))
    }
}
