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

    @Test
    fun `remote lookup keeps SAF reference when playback uses MediaStore URI`() {
        val sourceStableKey = "569212134|netease|"
        val safReference =
            "content://com.android.externalstorage.documents/tree/primary%3Aneriplayer-download/" +
                "document/primary%3Aneriplayer-download%2Ftrack.flac"
        val expected = storedEntry(
            name = "春に落ちて - 鹿乃 - 春に落ちて - netease.flac",
            reference = safReference,
            mediaUri = safReference
        )
        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = listOf(expected),
            metadataEntries = emptyList(),
            metadataByAudioName = mapOf(
                expected.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    stableKey = sourceStableKey
                )
            ),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )
        val song = SongItem(
            id = 1_007_996_349_999_556_163L,
            name = "春に落ちて",
            artist = "鹿乃",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 120_000L,
            coverUrl = null,
            mediaUri = "content://media/external_primary/audio/media/60942",
            channelId = "netease",
            audioId = "569212134",
            sourceStableKey = sourceStableKey,
            customName = "编辑后的标题",
            customArtist = "编辑后的歌手"
        )

        val result = ManagedDownloadStorageLookup.findAudioEntry(
            snapshot = snapshot,
            song = song,
            fileNameTemplate = null
        )

        assertEquals(expected, result?.entry)
        assertEquals(safReference, result?.entry?.reference)
        assertEquals("stableKey", result?.hitType)
    }

    @Test
    fun `filename lookup accepts clean and historical source prefixed albums`() {
        val song = SongItem(
            id = 123L,
            name = "茫",
            artist = "李润祺",
            album = "Netease茫",
            albumId = 456L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "123"
        )
        val baseNames = candidateManagedDownloadBaseNames(song)
        val cleanEntry = storedEntry(
            name = "茫 - 李润祺 - 茫 - netease.flac",
            reference = "/music/clean.flac",
            mediaUri = "/music/clean.flac"
        )
        val historicalEntry = storedEntry(
            name = "茫 - 李润祺 - Netease茫 - netease.flac",
            reference = "/music/historical.flac",
            mediaUri = "/music/historical.flac"
        )

        assertEquals(
            cleanEntry,
            ManagedDownloadStorageLookup.findAudioEntry(listOf(cleanEntry), baseNames)
        )
        assertEquals(
            historicalEntry,
            ManagedDownloadStorageLookup.findAudioEntry(listOf(historicalEntry), baseNames)
        )
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
            localFilePath = reference.takeIf { it.startsWith('/') },
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
    }
}
