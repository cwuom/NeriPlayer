package moe.ouom.neriplayer.data.backup

import java.io.StringReader
import java.io.FilterReader
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupJsonReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun streamedStatisticsAboveTenMiBAreRestorableWithoutFullLists() = runBlocking {
        val file = temporary.newFile()
        file.bufferedWriter().use { writer ->
            writer.write("{\"version\":\"2.3\",\"playlists\":[],\"playbackStats\":[")
            repeat(18000) { i ->
                if (i != 0) writer.write(",")
                writer.write("{\"identityKey\":\"netease:$i\",\"id\":${i + 1},\"name\":\"${"synthetic ".repeat(64)}\",\"playCount\":2}")
            }
            writer.write("],\"playbackStatBuckets\":[],\"playbackStatsClearedAt\":77}")
        }
        assertTrue(file.length() > 10L * 1024 * 1024)
        val store = FileSyncPlaybackDatasetStore(temporary.newFolder())
        file.reader().use { BackupJsonReader(store).read(it) }.use {
            assertTrue(it.data.playbackStats.orEmpty().isEmpty())
            assertEquals(77L, it.data.playbackStatsClearedAt)
            var count = 0
            it.statistics.openTracks().use { cursor ->
                while (true) {
                    val page = cursor.nextPage()
                    if (page.isEmpty()) break
                    assertTrue(page.size <= 256)
                    count += page.size
                }
            }
            assertEquals(18000, count)
        }
    }

    @Test fun metadataPreviewSkipsStatisticsAndPreservesLegacyNullableFields() = runBlocking {
        val directory = temporary.newFolder()
        val reader = BackupJsonReader(FileSyncPlaybackDatasetStore(directory))
        val data = reader.readMetadata(StringReader("""{"playbackStats":[{"id":1}],"playbackStatBuckets":null,"recentPlays":null,"playlists":[],"version":"2.2","exportDate":null,"unknown":{"nested":[1,2,3]}}"""))
        assertEquals("2.2", data.version)
        assertTrue(data.playbackStats.orEmpty().isEmpty())
        assertEquals(null, data.recentPlays)
        assertEquals(null, data.exportDate)
        assertTrue(directory.walkTopDown().filter { it.isFile }.none())
        reader.read(StringReader("""{"playlists":[],"playbackStats":null,"playbackStatBuckets":null}""")).use {
            assertEquals("2.0", it.data.version)
            assertTrue(it.statistics.openTracks().use { cursor -> cursor.nextPage().isEmpty() })
        }
    }

    @Test fun duplicateFieldsTrailingDataAndFutureVersionsDoNotPublishStatistics() = runBlocking {
        for (json in listOf(
            """{"playbackStats":[],"playbackStats":[]}""",
            """{"playbackStats":[]} {}""",
            """{"version":"2.4","playbackStats":[]}""",
            """{"version":"3.0","playbackStats":[]}""",
            """{"playbackStats":[null]}""",
            """{"playbackStats":[],/*comment*/"playlists":[]}"""
        )) {
            val directory = temporary.newFolder()
            assertTrue(runCatching { BackupJsonReader(FileSyncPlaybackDatasetStore(directory)).read(StringReader(json)) }.isFailure)
            assertTrue(directory.walkTopDown().filter { it.isFile }.none())
        }
    }

    @Test fun interruptionAfterAStagedPageClosesTemporaryFiles() = runBlocking {
        for (failure in listOf(IOException("read failed"), CancellationException("cancelled"))) {
            val directory = temporary.newFolder()
            val json = "{\"playbackStats\":[" + (1..300).joinToString(",") {
                "{\"identityKey\":\"netease:$it\",\"id\":$it,\"name\":\"${"song ".repeat(32)}\"}"
            } + "]}"
            val input = object : FilterReader(StringReader(json)) {
                var consumed = 0
                override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                    if (consumed > json.length * 9 / 10) throw failure
                    return super.read(buffer, offset, minOf(length, 512)).also { if (it > 0) consumed += it }
                }
            }
            assertSame(failure, runCatching { BackupJsonReader(FileSyncPlaybackDatasetStore(directory)).read(input) }.exceptionOrNull())
            assertTrue(directory.walkTopDown().filter { it.isFile }.none())
        }
    }

    @Test fun sourceOwnershipSurvivesReaderCompletionAndIsReleasedByContentClose() = runBlocking {
        val directory = temporary.newFolder()
        val content = BackupJsonReader(FileSyncPlaybackDatasetStore(directory)).read(StringReader(
            """{"playbackStatBuckets":[{"dayStartAt":0,"identityKey":"netease:1","id":1,"playCount":2}],"playbackStats":[]}"""
        ))
        assertFalse(directory.walkTopDown().filter { it.isFile }.none())
        content.statistics.openBuckets().use { assertEquals(2, it.nextPage().single().playCount) }
        content.close()
        assertTrue(directory.walkTopDown().filter { it.isFile }.none())
    }

    @Test fun truncatedInputFailsWithoutLeavingStagedStatistics() = runBlocking {
        val directory = temporary.newFolder()
        val reader = BackupJsonReader(FileSyncPlaybackDatasetStore(directory))
        assertTrue(runCatching {
            reader.read(StringReader("{\"playlists\":[],\"playbackStats\":[{\"identityKey\":\"netease:1\",\"id\":1}"))
        }.isFailure)
        assertTrue(directory.walkTopDown().filter { it.isFile }.none())
    }
}
