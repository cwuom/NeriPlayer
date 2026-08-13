package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadStorageLookup
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadStorageLookupTest {

    @Test
    fun `audio lookup accepts numbered duplicate suffix`() {
        val expected = storedEntry(
            name = "Artist - Song (1).flac",
            reference = "/music/Artist - Song (1).flac",
            mediaUri = "/music/Artist - Song (1).flac"
        )
        val entries = listOf(
            storedEntry(
                name = "Artist - Other.flac",
                reference = "/music/Artist - Other.flac",
                mediaUri = "/music/Artist - Other.flac"
            ),
            expected
        )

        assertEquals(
            expected,
            ManagedDownloadStorageLookup.findAudioEntry(
                audioEntries = entries,
                baseNames = listOf("Artist - Song")
            )
        )
    }

    @Test
    fun `local SAF playback reference wins over retained remote identity`() {
        val localMediaUri =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2Fmy/" +
                "document/primary%3AMusic%2Fmy%2Fnetease%20-%20%E8%8C%B6%E5%A4%AA%20-%20" +
                "%E3%81%A0%E3%82%93%E3%81%94%E5%A4%A7%E5%AE%B6%E6%97%8F.flac"
        val expected = storedEntry(
            name = "netease - 茶太 - だんご大家族.flac",
            reference = localMediaUri,
            mediaUri = localMediaUri
        )
        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = listOf(expected),
            metadataEntries = emptyList(),
            metadataByAudioName = emptyMap(),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )
        val song = SongItem(
            id = 5_364_584_910_320_485_668L,
            name = "だんご大家族",
            artist = "茶太",
            album = "Neteaseメグメル/だんご大家族",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            mediaUri = localMediaUri,
            channelId = "netease",
            audioId = "5364584910320485668"
        )

        val result = ManagedDownloadStorageLookup.findAudioEntry(
            snapshot = snapshot,
            song = song,
            fileNameTemplate = null
        )

        assertEquals(expected, result?.entry)
        assertEquals("localReference", result?.hitType)
    }

    private fun storedEntry(
        name: String,
        reference: String,
        mediaUri: String
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = mediaUri,
            localFilePath = reference,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
    }
}
