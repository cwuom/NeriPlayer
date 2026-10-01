package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadSidecarIncrementalTest {
    @Test
    fun `sidecar updates do not enumerate unrelated audio or metadata at any library size`() {
        for (size in listOf(0, 850, 5000)) {
            val audio = (0 until size).map { entry("song-$it.mp3") }
            val snapshot = ManagedDownloadSnapshotIndex.compose(
                audioEntries = audio,
                metadataEntries = emptyList(),
                metadataByAudioName = emptyMap(),
                coverEntries = emptyList(),
                lyricEntries = emptyList()
            )
            val guarded = snapshot.copy(audioEntries = object : AbstractList<ManagedDownloadStorage.StoredEntry>() {
                override val size: Int = audio.size
                override fun get(index: Int): ManagedDownloadStorage.StoredEntry =
                    error("sidecar update visited unrelated audio at $index of $size")
            })
            for (bucket in listOf(ManagedDownloadStorage.SnapshotEntryBucket.COVER, ManagedDownloadStorage.SnapshotEntryBucket.LYRIC)) {
                val sidecar = entry(if (bucket == ManagedDownloadStorage.SnapshotEntryBucket.COVER) "cover.jpg" else "lyric.lrc")
                val result = ManagedDownloadSnapshotIndex.applyStoredEntryWrite(guarded, sidecar, bucket)
                assertSame(guarded.audioEntries, result.audioEntries)
                assertSame(guarded.audioEntriesByStableKey, result.audioEntriesByStableKey)
                assertSame(guarded.metadataEntriesByAudioName, result.metadataEntriesByAudioName)
                assertEquals(size + 1, result.knownReferences.size)
                assertTrue(sidecar.reference in result.knownReferences)
            }
        }
    }

    @Test
    fun `sidecar replacement removes old references and preserves other buckets`() {
        val old = entry("cover.jpg")
        val lyric = entry("lyric.lrc")
        val before = ManagedDownloadSnapshotIndex.compose(emptyList(), emptyList(), emptyMap(), listOf(old), listOf(lyric))
        val replacement = old.copy(reference = "content://provider/opaque-new", mediaUri = "content://provider/opaque-new")
        val after = ManagedDownloadSnapshotIndex.applyStoredEntryWrite(before, replacement, ManagedDownloadStorage.SnapshotEntryBucket.COVER)
        val expected = ManagedDownloadSnapshotIndex.compose(emptyList(), emptyList(), emptyMap(), listOf(replacement), listOf(lyric))
        assertEquals(expected, after)
        assertSame(before.lyricEntriesByName, after.lyricEntriesByName)
    }

    private fun entry(name: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = "content://provider/$name",
        mediaUri = "content://provider/$name",
        localFilePath = null,
        sizeBytes = 128,
        lastModifiedMs = 1
    )
}
