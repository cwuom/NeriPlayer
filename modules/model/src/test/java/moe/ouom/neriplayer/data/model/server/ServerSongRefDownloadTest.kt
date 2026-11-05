package moe.ouom.neriplayer.data.model.server

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerSongRefDownloadTest {
    private val ref = ServerSongRef("9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "track / + ? & 中文")

    @Test fun downloadReferencePreservesTheOriginalIdWithoutCredentials() {
        val uri = URI(ref.resourceUrl("download"))
        assertEquals("https", uri.scheme)
        assertEquals(ServerSongRef.RESOURCE_HOST, uri.host)
        assertEquals(null, uri.rawQuery)
        assertEquals(null, uri.userInfo)
        assertEquals("download", uri.path.split('/')[3])
        assertEquals(ref, ServerSongRef.reference(ref.profileId, uri.path.substringAfterLast('/')))
        assertFalse(uri.toString().contains(ref.songId))
    }

    @Test fun downloadAndPlaybackRemainSeparateResourcesForTheSameIdentity() {
        assertEquals(ref.resourceUrl("stream"), ref.resourceUrl())
        assertNotEquals(ref.resourceUrl(), ref.resourceUrl("download"))
        assertNotEquals(ref.resourceUrl("download"), ref.copy(
            profileId = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc792").resourceUrl("download"))
        assertEquals(ref, ServerSongRef.fromAudioId(ref.audioId))
        assertEquals(ref, ServerSongRef.fromMediaUri(ref.mediaUri))
    }

    @Test fun arbitraryResourceKindsRemainRejected() {
        for (kind in listOf("../download", "download?format=raw", "getUser", "")) {
            assertThrows(IllegalArgumentException::class.java) { ref.resourceUrl(kind) }
        }
    }
}
