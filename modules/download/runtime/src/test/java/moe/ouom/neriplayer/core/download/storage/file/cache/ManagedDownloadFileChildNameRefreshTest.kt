package moe.ouom.neriplayer.core.download.storage.file.cache

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDownloadFileChildNameRefreshTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a fresh complete listing is reused instead of rereading the directory`() {
        val directory = temporaryFolder.newFolder("music")
        File(directory, "Song.flac").createNewFile()
        val cache = ManagedDownloadFileChildNameCache(writeCacheValidateIntervalMs = Long.MAX_VALUE)

        assertEquals("Song (1).flac", cache.reserveUniqueName(directory, "Song.flac"))
        File(directory, "Other.flac").createNewFile()

        assertEquals("Other.flac", cache.reserveUniqueName(directory, "Other.flac"))
        assertEquals("Song (2).flac", cache.reserveUniqueName(directory, "Song.flac"))
    }

    @Test
    fun `a stale listing is reread from the directory`() {
        val directory = temporaryFolder.newFolder("music")
        File(directory, "Song.flac").createNewFile()
        val cache = ManagedDownloadFileChildNameCache(writeCacheValidateIntervalMs = Long.MIN_VALUE)

        assertEquals("Song (1).flac", cache.reserveUniqueName(directory, "Song.flac"))
        File(directory, "Other.flac").createNewFile()

        assertEquals("Other (1).flac", cache.reserveUniqueName(directory, "Other.flac"))
    }

    @Test
    fun `remembered names alone do not count as a complete listing`() {
        val directory = temporaryFolder.newFolder("music")
        File(directory, "Song.flac").createNewFile()
        val cache = ManagedDownloadFileChildNameCache(writeCacheValidateIntervalMs = Long.MAX_VALUE)
        cache.rememberName(directory, "Remembered.flac")

        assertEquals("Song (1).flac", cache.reserveUniqueName(directory, "Song.flac"))
        assertEquals("Remembered.flac", cache.reserveUniqueName(directory, "Remembered.flac"))
    }

    @Test
    fun `a missing directory starts from an empty listing`() {
        val directory = File(temporaryFolder.root, "missing")
        val cache = ManagedDownloadFileChildNameCache(writeCacheValidateIntervalMs = Long.MAX_VALUE)

        assertEquals("Song.flac", cache.reserveUniqueName(directory, "Song.flac"))
        assertEquals("Song (1).flac", cache.reserveUniqueName(directory, "Song.flac"))
    }
}
