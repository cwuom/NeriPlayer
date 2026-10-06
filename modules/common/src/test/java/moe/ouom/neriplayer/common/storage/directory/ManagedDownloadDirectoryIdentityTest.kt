package moe.ouom.neriplayer.common.storage.directory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadDirectoryIdentityTest {

    private val identity = ManagedDownloadDirectoryIdentity
    private val authority = "com.android.externalstorage.documents"
    private val treeUri = "content://$authority/tree/primary%3AMusic%2FNeri"

    @Test
    fun `blank directory uris normalise to null and others are trimmed`() {
        assertNull(identity.normalizeDirectoryUri(null))
        assertNull(identity.normalizeDirectoryUri(" \n "))
        assertEquals(treeUri, identity.normalizeDirectoryUri("  $treeUri \n"))
    }

    @Test
    fun `configured document and tree uris collapse to the tree uri`() {
        assertEquals(
            treeUri,
            identity.normalizeConfiguredDirectoryUri("$treeUri/document/primary%3AMusic%2FNeri%2FAlbum?mode=1#top")
        )
        assertEquals(
            treeUri,
            identity.normalizeConfiguredDirectoryUri("content://$authority/document/primary%3AMusic%2FNeri/")
        )
        assertEquals(
            "content://$authority/root/primary",
            identity.normalizeConfiguredDirectoryUri("content://$authority/root/primary")
        )
        assertEquals("/storage/emulated/0/Music", identity.normalizeConfiguredDirectoryUri("/storage/emulated/0/Music/"))
        assertNull(identity.normalizeConfiguredDirectoryUri("#fragment-only"))
    }

    @Test
    fun `identities decode the document id and keep the uri kind`() {
        assertEquals("tree:$authority:primary:Music/Neri", identity.directoryIdentity("$treeUri/"))
        assertEquals(
            "tree:$authority:primary:Music/Neri",
            identity.directoryIdentity("content://$authority/document/primary%3AMusic%2FNeri")
        )
        assertEquals("document::primary:Music", identity.directoryIdentity("primary/document/primary%3AMusic"))
        assertEquals("/storage/emulated/0/Music", identity.directoryIdentity("/storage/emulated/0/Music"))
        assertNull(identity.directoryIdentity(" "))
    }

    @Test
    fun `equivalent directories share an identity`() {
        assertTrue(
            identity.areEquivalentDirectoryUris(treeUri, "content://$authority/document/primary%3AMusic%2FNeri?x=1")
        )
        assertTrue(identity.areEquivalentDirectoryUris(null, " "))
        assertFalse(identity.areEquivalentDirectoryUris(null, treeUri))
        assertFalse(identity.areEquivalentDirectoryUris(treeUri, null))
        assertFalse(identity.areEquivalentDirectoryUris(treeUri, "content://$authority/tree/primary%3AMusic"))
    }

    @Test
    fun `encoded document ids stop at the next path, query or fragment delimiter`() {
        assertEquals(
            "primary%3AMusic",
            identity.extractEncodedDirectoryDocumentId("content://a/tree/primary%3AMusic/document/x", "/tree/")
        )
        assertEquals("primary%3AMusic", identity.extractEncodedDirectoryDocumentId("content://a/tree/primary%3AMusic?x", "/tree/"))
        assertEquals("primary%3AMusic", identity.extractEncodedDirectoryDocumentId("content://a/tree/primary%3AMusic#x", "/tree/"))
        assertNull(identity.extractEncodedDirectoryDocumentId("content://a/tree//x", "/tree/"))
        assertNull(identity.extractEncodedDirectoryDocumentId("content://a/document/x", "/tree/"))
    }

    @Test
    fun `decoded document ids keep malformed escapes encoded`() {
        assertEquals("primary:My Music", identity.extractDirectoryDocumentId("content://a/tree/primary%3AMy%20Music", "/tree/"))
        assertEquals("primary%zzMusic", identity.extractDirectoryDocumentId("content://a/tree/primary%zzMusic", "/tree/"))
        assertNull(identity.extractDirectoryDocumentId("content://a/root/x", "/tree/"))
    }

    @Test
    fun `authorities end at the first path, query or fragment delimiter`() {
        assertEquals("a.b", identity.extractDirectoryAuthority("content://a.b/tree/x"))
        assertEquals("a.b", identity.extractDirectoryAuthority("content://a.b?x"))
        assertEquals("a.b", identity.extractDirectoryAuthority("content://a.b"))
        assertEquals("", identity.extractDirectoryAuthority("/storage/emulated/0"))
    }
}
