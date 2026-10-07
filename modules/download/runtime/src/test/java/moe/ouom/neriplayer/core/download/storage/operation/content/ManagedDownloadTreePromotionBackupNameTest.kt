package moe.ouom.neriplayer.core.download.storage.operation.content

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadTreePromotionBackupNameTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `direct and uuid tagged backups of the target are recognised case insensitively`() {
        listOf(
            ".Song.flac.backup",
            ".song.FLAC.BACKUP",
            ".Song.flac.$uuid.backup",
            ".song.flac.$uuid.Backup"
        ).forEach { name ->
            assertTrue(name, isBackupOfSong(name))
        }
    }

    @Test
    fun `visible files, other targets and malformed identifiers are not backups`() {
        listOf(
            "Song.flac.backup",
            ".Song.flac.tmp",
            ".Song.backup",
            "..$uuid.backup",
            ".Other.flac.$uuid.backup",
            ".Song.flac.not-a-uuid.backup"
        ).forEach { name ->
            assertFalse(name, isBackupOfSong(name))
        }
    }

    private fun isBackupOfSong(actualName: String): Boolean =
        ManagedDownloadStorage.isTreePromotionBackupName(actualName, targetName = "Song.flac")
}
