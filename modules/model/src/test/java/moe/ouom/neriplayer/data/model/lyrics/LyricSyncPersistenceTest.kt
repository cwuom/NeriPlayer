package moe.ouom.neriplayer.data.model.lyrics

import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LyricSyncPersistenceTest {

    @Test
    fun `persistence json round trips every field`() {
        val persistence = LyricSyncPersistence(
            revision = 3L,
            edited = true,
            romanized = "roma",
            originalRomanized = "original roma",
            source = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "42",
            userOffsetMs = -250L
        )

        assertEquals(persistence, readLyricSyncPersistence(persistence.toPersistenceJson()))
    }

    @Test
    fun `missing persistence json yields defaults while a null payload is rejected`() {
        assertEquals(LyricSyncPersistence(), readLyricSyncPersistence(null))
        assertThrows(IllegalArgumentException::class.java) { readLyricSyncPersistence("null") }
    }
}
