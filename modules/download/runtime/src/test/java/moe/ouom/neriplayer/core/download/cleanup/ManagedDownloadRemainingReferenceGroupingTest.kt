package moe.ouom.neriplayer.core.download.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadRemainingReferenceGroupingTest {
    @Test
    fun `remaining references stay grouped under the identities that requested them`() {
        val requestedReferencesByIdentity = linkedMapOf(
            "song-a" to setOf("/music/a.flac", "/music/Covers/a.jpg"),
            "song-b" to setOf("/music/b.flac"),
            "song-c" to setOf("/music/c.flac", "/music/Lyrics/c.lrc")
        )

        val grouped = groupRemainingManagedReferencesByIdentity(
            requestedReferencesByIdentity = requestedReferencesByIdentity,
            remainingReferences = setOf("/music/Covers/a.jpg", "/music/Lyrics/c.lrc", "/music/unrelated.flac")
        )

        assertEquals(
            mapOf("song-a" to setOf("/music/Covers/a.jpg"), "song-c" to setOf("/music/Lyrics/c.lrc")),
            grouped
        )
    }

    @Test
    fun `nothing is grouped without requests or remaining references`() {
        assertEquals(
            emptyMap<String, Set<String>>(),
            groupRemainingManagedReferencesByIdentity(emptyMap(), setOf("/music/a.flac"))
        )
        assertEquals(
            emptyMap<String, Set<String>>(),
            groupRemainingManagedReferencesByIdentity(mapOf("song-a" to setOf("/music/a.flac")), emptySet())
        )
    }
}
