package moe.ouom.neriplayer.data.sync.policy

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoverUrlProjectionTest {
    @Test
    fun `an unbound projection preserves existing remote references`() {
        SyncCoverUrlProjection.bind()
        try {
            for (reference in listOf("https://cover.example/art", "https://neri-server.invalid/v1/profile/cover/id", "opaque")) {
                assertEquals(reference, sanitizeCoverUrlForSync(" $reference "))
            }
            assertNull(sanitizeCoverUrlForSync(null))
            assertNull(sanitizeCoverUrlForSync(" "))
        } finally {
            SyncCoverUrlProjection.bind()
        }
    }

    @Test
    fun `preparation completes before a synchronous projection reads the loaded mapping`() = runBlocking {
        val legacy = "https://neri-server.invalid/v1/profile/cover/id"
        val portable = "https://music.example/rest/getCoverArt.view?id=cover&size=600#neri-cover=v1:" + "a".repeat(64)
        var loaded = false
        SyncCoverUrlProjection.bind(
            prepare = { yield(); loaded = true },
            project = { if (loaded && it == legacy) portable else it }
        )
        try {
            assertEquals(legacy, sanitizeCoverUrlForSync(legacy))
            SyncCoverUrlProjection.prepare()
            assertTrue(loaded)
            assertEquals(portable, sanitizeCoverUrlForSync(legacy))
        } finally {
            SyncCoverUrlProjection.bind()
        }
    }

    @Test
    fun `local inputs never enter the projection and local projected outputs remain excluded`() {
        val visited = mutableListOf<String>()
        SyncCoverUrlProjection.bind(project = { visited += it; " content://private/cover " })
        try {
            for (reference in listOf("/storage/cover", "file:/private/cover", "content://media/cover", "android.resource://app/cover")) {
                assertNull(sanitizeCoverUrlForSync(reference))
            }
            assertNull(sanitizeCoverUrlForSync(null))
            assertNull(sanitizeCoverUrlForSync(" "))
            assertTrue(visited.isEmpty())
            assertNull(sanitizeCoverUrlForSync(" https://cover.example/art "))
            assertEquals(listOf("https://cover.example/art"), visited)
        } finally {
            SyncCoverUrlProjection.bind()
        }
    }

    @Test
    fun `a projection can omit an unavailable reference without replacing it with a local path`() {
        for (projected in listOf(null, " ", " /data/private/cover ", "file:/private/cover")) {
            SyncCoverUrlProjection.bind(project = { projected })
            try {
                assertNull(sanitizeCoverUrlForSync("https://neri-server.invalid/v1/profile/cover/id"))
            } finally {
                SyncCoverUrlProjection.bind()
            }
        }
    }
}
