package moe.ouom.neriplayer.core.download.storage.snapshot

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.database.entity.DownloadSnapshotEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 锁定旧 Room 快照的本地播放引用回退规则 */
class ManagedDownloadSnapshotRoomMapperCompatTest {
    @Test
    fun `empty media uri falls back to a local file reference`() {
        val restored = restore(
            reference = "/storage/emulated/0/NeriPlayer-Download/song.mp3",
            mediaUri = ""
        )

        assertEquals(
            "/storage/emulated/0/NeriPlayer-Download/song.mp3",
            restored.mediaUri
        )
        assertEquals(
            "/storage/emulated/0/NeriPlayer-Download/song.mp3",
            ManagedDownloadStorage.resolveStoredEntryPlaybackUri(restored)
        )
    }

    @Test
    fun `remote media uri cannot hide a local reference`() {
        val restored = restore(
            reference = "content://com.android.externalstorage.documents/document/primary%3Asong.mp3",
            mediaUri = "https://music.example/song.mp3"
        )

        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3Asong.mp3",
            restored.mediaUri
        )
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3Asong.mp3",
            ManagedDownloadStorage.resolveStoredEntryPlaybackUri(restored)
        )
    }

    @Test
    fun `remote-only entry is not exposed as local playback`() {
        val restored = restore(
            reference = "https://music.example/song.mp3",
            mediaUri = "https://music.example/song.mp3"
        )

        assertNull(ManagedDownloadStorage.resolveStoredEntryPlaybackUri(restored))
    }

    private fun restore(reference: String, mediaUri: String): ManagedDownloadStorage.StoredEntry {
        val entity = DownloadSnapshotEntryEntity(
            rootKey = "root",
            bucket = ManagedDownloadSnapshotRoomMapper.BUCKET_AUDIO,
            entryKey = reference,
            displayPosition = 0,
            name = "song.mp3",
            reference = reference,
            mediaUri = mediaUri,
            localFilePath = reference.takeIf { it.startsWith("/") },
            sizeBytes = 1L,
            lastModifiedMs = 1L,
            isDirectory = false
        )
        return ManagedDownloadSnapshotRoomMapper.toSnapshot(
            audioEntries = listOf(entity),
            metadataEntries = emptyList(),
            metadata = emptyList(),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        ).audioEntries.single()
    }
}
