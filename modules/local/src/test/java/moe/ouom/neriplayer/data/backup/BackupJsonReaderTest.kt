package moe.ouom.neriplayer.data.backup

import java.io.StringReader
import java.io.FilterReader
import java.io.IOException
import java.io.StringWriter
import java.io.Reader
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupJsonReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun multibyteStatisticsRecordIsRejectedBeforeEncodingOrFullConsumption() = runBlocking {
        val directory = temporary.newFolder()
        var stagedPrefix = false
        val pieces = sequence {
            yield("{\"version\":\"2.3\",\"playbackStats\":[" to 1)
            repeat(300) { index ->
                if (index != 0) yield("," to 1)
                yield("{\"identityKey\":\"netease:$index\",\"id\":$index}" to 1)
            }
            stagedPrefix = directory.walkTopDown().any { it.isFile }
            yield(",{\"identityKey\":\"netease:999\",\"id\":999,\"name\":\"" to 1)
            yield("中" to (8 * 1024 * 1024))
            yield("\"}]}" to 1)
        }.iterator()
        val input = RepeatedPiecesReader(pieces)
        val failure = runCatching { BackupJsonReader(FileSyncPlaybackDatasetStore(directory)).read(input).use { } }.exceptionOrNull()
        assertTrue("Expected an early byte budget IOException, got $failure", failure is IOException)
        assertTrue("The accepted prefix must have reached the real disk sink", stagedPrefix)
        assertTrue("The oversized record was fully consumed", input.consumed < 8L * 1024 * 1024)
        assertTrue(directory.walkTopDown().none { it.isFile })
    }

    @Test fun statisticsRecordByteBudgetPreservesExactEmojiAndMixedTextBoundariesForReadingAndWriting() = runBlocking {
        val records = (1..2).map { id -> """{"identityKey":"netease:$id","id":$id,"name":"🎵🎵中aé"}""" }
        val bytes = records.first().toByteArray(Charsets.UTF_8).size.toLong()
        val limits = BackupJsonLimits(maxStatisticsRecordBytes = bytes)
        for (field in listOf("playbackStats", "playbackStat\\u0042uckets")) {
            val json = "{\"version\":\"2.3\",\"$field\":[${records.joinToString(",")}]}"
            val directory = temporary.newFolder()
            val input = object : FilterReader(StringReader(json)) {
                override fun read(buffer: CharArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 1))
            }
            BackupJsonReader(FileSyncPlaybackDatasetStore(directory), limits = limits).read(input).use { content ->
                val names = if (field == "playbackStats") content.statistics.openTracks().use { it.nextPage().map { row -> row.name } }
                else content.statistics.openBuckets().use { it.nextPage().map { row -> row.name } }
                assertEquals(listOf("🎵🎵中aé", "🎵🎵中aé"), names)
            }
            val output = StringWriter()
            output.withBackupJsonBudget(limits) { }.use { writer -> json.forEach { writer.write(it.code) } }
            assertEquals(json, output.toString())
            val tooSmall = limits.copy(maxStatisticsRecordBytes = bytes - 1)
            assertRejectedMetadata(json, tooSmall)
            val rejected = StringWriter()
            val failure = runCatching { rejected.withBackupJsonBudget(tooSmall) { }.use { it.write(json) } }.exceptionOrNull()
            assertTrue("Expected the export guard to reject the same byte boundary, got $failure", failure is IOException)
            assertEquals("", rejected.toString())
            assertTrue(directory.walkTopDown().none { it.isFile })
        }
    }

    @Test fun aStatisticsRecordWithSeveralLegalLargeTokensIsRejectedBeforeFullConsumption() = runBlocking {
        val directory = temporary.newFolder()
        var stagedPrefix = false
        val pieces = sequence {
            yield("{\"version\":\"2.3\",\"playbackStats\":[" to 1)
            repeat(300) { index ->
                if (index != 0) yield("," to 1)
                yield("{\"identityKey\":\"netease:$index\",\"id\":$index}" to 1)
            }
            stagedPrefix = directory.walkTopDown().any { it.isFile }
            yield(",{\"identityKey\":\"netease:999\",\"id\":999" to 1)
            for (field in listOf("name", "artist", "album", "coverUrl")) {
                yield(",\"$field\":\"" to 1)
                yield("x" to (8 * 1024 * 1024))
                yield("\"" to 1)
            }
            yield("}]}" to 1)
        }.iterator()
        val input = RepeatedPiecesReader(pieces)
        val failure = runCatching { BackupJsonReader(FileSyncPlaybackDatasetStore(directory)).read(input).use { } }.exceptionOrNull()
        assertTrue("Expected an early record budget IOException, got $failure", failure is IOException)
        assertTrue("The accepted prefix must have reached the real disk sink", stagedPrefix)
        assertTrue("The oversized record was fully consumed", input.consumed < 4L * 8 * 1024 * 1024)
        assertTrue(directory.walkTopDown().none { it.isFile })
    }

    @Test fun defaultStatisticsRecordObjectBudgetStopsShardAllocationBeforeFullConsumption() = runBlocking {
        val directory = temporary.newFolder()
        var stagedPrefix = false
        val pieces = sequence {
            yield("{\"version\":\"2.3\",\"playbackStats\":[" to 1)
            repeat(300) { index ->
                if (index != 0) yield("," to 1)
                yield("{\"identityKey\":\"netease:$index\",\"id\":$index}" to 1)
            }
            stagedPrefix = directory.walkTopDown().any { it.isFile }
            yield(",{\"identityKey\":\"netease:999\",\"id\":999,\"counterShards\":[{}" to 1)
            yield(",{}" to 2_999_999)
            yield("]}]}" to 1)
        }.iterator()
        val input = RepeatedPiecesReader(pieces)
        val failure = runCatching { BackupJsonReader(FileSyncPlaybackDatasetStore(directory)).read(input).use { } }.exceptionOrNull()
        assertTrue("Expected an early object budget IOException, got $failure", failure is IOException)
        assertTrue("The accepted prefix must have reached the real disk sink", stagedPrefix)
        assertTrue("The oversized record was fully consumed", input.consumed < 9_000_000L)
        assertTrue(directory.walkTopDown().none { it.isFile })
    }

    @Test fun statisticsRecordCharacterAndNestedObjectBudgetsAreSymmetricAndResetForEachRecord() = runBlocking {
        val records = (1..2).map { id ->
            """{"identityKey":"netease:$id","id":$id,"counterShards":[{"deviceId":"a","playCount":1},{"deviceId":"b","playCount":2}]}"""
        }
        val exactLimits = BackupJsonLimits(maxStatisticsRecordCharacters = records.first().length.toLong(), maxStatisticsRecordObjects = 3)
        for (field in listOf("playback\\u0053tats", "playbackStatBuckets")) {
            val json = "{\"version\":\"2.3\",\"$field\":[${records.joinToString(",")}]}"
            val directory = temporary.newFolder()
            BackupJsonReader(FileSyncPlaybackDatasetStore(directory), limits = exactLimits).read(StringReader(json)).use { content ->
                if (field == "playbackStatBuckets") content.statistics.openBuckets().use { cursor ->
                    val page = cursor.nextPage()
                    assertEquals(listOf(1L, 2L), page.map { it.id })
                    assertTrue(page.all { it.counterShards.map { shard -> shard.playCount } == listOf(1, 2) })
                } else content.statistics.openTracks().use { cursor ->
                    val page = cursor.nextPage()
                    assertEquals(listOf(1L, 2L), page.map { it.id })
                    assertTrue(page.all { it.counterShards.map { shard -> shard.playCount } == listOf(1, 2) })
                }
            }
            val output = StringWriter()
            output.withBackupJsonBudget(exactLimits) { }.use { it.write(json) }
            assertEquals(json, output.toString())
            for (limits in listOf(exactLimits.copy(maxStatisticsRecordCharacters = exactLimits.maxStatisticsRecordCharacters - 1),
                exactLimits.copy(maxStatisticsRecordObjects = 2))) {
                assertRejectedMetadata(json, limits)
                val rejected = StringWriter()
                val failure = runCatching { rejected.withBackupJsonBudget(limits) { }.use { it.write(json) } }.exceptionOrNull()
                assertTrue("Expected the export guard to reject the same record, got $failure", failure is IOException)
                assertEquals("", rejected.toString())
            }
            assertTrue(directory.walkTopDown().none { it.isFile })
        }
    }

    @Test fun giantStatisticsRecordsReachTheRealDiskSinkWithoutAccumulatingARecordCountPage() = runBlocking {
        val nameCharacters = 8 * 1024 * 1024
        val pieces = sequence {
            yield("{\"version\":\"2.3\",\"playbackStats\":[" to 1)
            repeat(16) { index ->
                if (index != 0) yield("," to 1)
                val identity = "netease:${(index + 1).toString().padStart(2, '0')}"
                yield("{\"identityKey\":\"$identity\",\"id\":${index + 1},\"name\":\"" to 1)
                yield(('a' + index).toString() to nameCharacters)
                yield("\"}" to 1)
            }
            yield("]}" to 1)
        }.iterator()
        val input = object : Reader() {
            var current = ""
            var remaining = 0
            var position = 0
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                var count = 0
                while (count < length) {
                    if (remaining == 0) {
                        if (!pieces.hasNext()) break
                        val piece = pieces.next()
                        current = piece.first
                        remaining = piece.second
                        position = 0
                    }
                    buffer[offset + count] = current[position]
                    count++
                    position++
                    if (position == current.length) {
                        remaining--
                        position = 0
                    }
                }
                return if (count == 0) -1 else count
            }
            override fun close() = Unit
        }
        val directory = temporary.newFolder()
        BackupJsonReader(FileSyncPlaybackDatasetStore(directory)).read(input).use { content ->
            var records = 0
            content.statistics.openTracks().use { cursor ->
                while (true) {
                    val page = cursor.nextPage()
                    if (page.isEmpty()) break
                    assertEquals(1, page.size)
                    val track = page.single()
                    assertEquals((records + 1).toLong(), track.id)
                    assertEquals("netease:${(records + 1).toString().padStart(2, '0')}", track.identityKey)
                    assertEquals(nameCharacters, track.name.length)
                    assertTrue(track.name.all { it == 'a' + records })
                    records++
                }
            }
            assertEquals(16, records)
        }
        assertTrue(directory.walkTopDown().none { it.isFile })
    }

    @Test fun defaultRootNameBudgetStopsAggregateKeysEvenWhenEveryTokenFits() = runBlocking {
        val file = temporary.newFile()
        file.bufferedWriter().use { writer ->
            writer.write("{\"")
            val block = "k".repeat(1024)
            repeat(9 * 1024) { writer.write(block) }
            writer.write("\":0,\"")
            val secondBlock = "m".repeat(1024)
            repeat(9 * 1024) { writer.write(secondBlock) }
            writer.write("\":1}")
        }
        val directory = temporary.newFolder()
        val input = object : FilterReader(file.reader()) {
            var consumed = 0L
            override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it > 0) consumed += it }
        }
        val failure = runCatching { BackupJsonReader(FileSyncPlaybackDatasetStore(directory)).read(input).use { } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(input.consumed < file.length())
        assertTrue(directory.walkTopDown().none { it.isFile })
    }

    @Test fun defaultRootFieldBudgetStopsUnknownNamesWithoutKeepingTheirUnboundedValidationSet() = runBlocking {
        var generatedUnknown = 0
        val pieces = sequence {
            yield("{\"version\":\"2.3\",\"playbackStats\":[")
            repeat(300) { index ->
                if (index != 0) yield(",")
                yield("{\"identityKey\":\"netease:$index\",\"id\":$index}")
            }
            yield("]")
            repeat(65_537) { index ->
                generatedUnknown++
                yield(",\"unknown$index\":0")
            }
            yield("}")
        }.iterator()
        val input = object : Reader() {
            var current = ""
            var position = 0
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                var written = 0
                val limit = minOf(length, 16)
                while (written < limit) {
                    if (position == current.length) {
                        if (!pieces.hasNext()) break
                        current = pieces.next()
                        position = 0
                    }
                    val count = minOf(limit - written, current.length - position)
                    current.toCharArray(buffer, offset + written, position, position + count)
                    written += count
                    position += count
                }
                return if (written == 0) -1 else written
            }
            override fun close() = Unit
        }
        val directory = temporary.newFolder()
        val failure = runCatching { BackupJsonReader(FileSyncPlaybackDatasetStore(directory)).read(input).use { } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(generatedUnknown < 65_537)
        assertTrue(directory.walkTopDown().none { it.isFile })
    }

    @Test fun defaultMetadataBudgetRejectsLargeHistoryBeforeFullConsumptionAndCleansStagedStatistics() = runBlocking {
        val file = temporary.newFile()
        file.bufferedWriter().use { writer ->
            writer.write("{\"version\":\"2.3\",\"playbackStats\":[")
            repeat(300) { index ->
                if (index != 0) writer.write(",")
                writer.write("{\"identityKey\":\"netease:$index\",\"id\":$index}")
            }
            writer.write("],\"recentPlays\":[")
            val name = "n".repeat(128)
            val lyric = "l".repeat(384)
            repeat(40_000) { index ->
                if (index != 0) writer.write(",")
                writer.write("{\"songId\":$index,\"song\":{\"id\":$index,\"name\":\"$name\",\"originalLyric\":\"$lyric\"},\"playedAt\":$index}")
            }
            writer.write("]}")
        }
        assertTrue(file.length() > 20L * 1024 * 1024)
        val directory = temporary.newFolder()
        val input = object : FilterReader(file.reader()) {
            var consumed = 0L
            override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it > 0) consumed += it }
        }
        val failure = runCatching { BackupJsonReader(FileSyncPlaybackDatasetStore(directory)).read(input).use { } }.exceptionOrNull()
        assertTrue("Expected metadata budget rejection, got $failure", failure is IOException)
        assertTrue(input.consumed < file.length())
        assertTrue(directory.walkTopDown().none { it.isFile })
    }

    @Test fun allLegacyVersionsKeepNullableCollectionsAndEveryLyricRepresentation() = runBlocking {
        val song = SyncSong(id = 7, matchedLyric = "匹配\r\n\u0000\"\\", matchedTranslatedLyric = "",
            matchedRomanizedLyric = "romaji", originalLyric = "原词 🎵", originalTranslatedLyric = null,
            originalRomanizedLyric = "original", lyricSyncEdited = null)
        for (version in listOf("2.0", "2.1", "2.2", "2.3")) {
            val json = """{"version":"$version","playlists":null,"recentPlays":null,"lyricOverrides":null,"legacyLyricCandidates":[${Gson().toJson(song)}],"timestamp":null,"playbackStatsClearedAt":null,"exportDate":null,"${"unknown".repeat(100)}":{"nested":[null,false]}}"""
            BackupJsonReader(FileSyncPlaybackDatasetStore(temporary.newFolder())).read(StringReader(json)).use {
                assertEquals(version, it.data.version)
                assertEquals(null, it.data.playlists)
                assertEquals(null, it.data.recentPlays)
                assertEquals(null, it.data.lyricOverrides)
                assertEquals(null, it.data.exportDate)
                assertEquals(0L, it.data.playbackStatsClearedAt)
                assertEquals(song, it.data.legacyLyricCandidates.orEmpty().single())
            }
        }
    }

    @Test fun prettyPrintedMetadataDoesNotSpendItsBudgetOnUnretainedWhitespace() = runBlocking {
        val json = """
            {
              "version" : "2.3" ,
              "playlists" : [
              ] ,
              "recentPlays" : null
            }
        """.trimIndent()
        BackupJsonReader(FileSyncPlaybackDatasetStore(temporary.newFolder()), limits = BackupJsonLimits(maxMetadataCharacters = 11))
            .read(StringReader(json)).use {
                assertEquals("2.3", it.data.version)
                assertEquals(emptyList<Any>(), it.data.playlists)
                assertEquals(null, it.data.recentPlays)
            }
    }

    @Test fun guardedIoEntriesCountOnlyWrittenSlicesAndRejectBeforePublishingAnOversizedChunk() {
        val limits = BackupJsonLimits(maxMetadataCharacters = 5)
        val expected = """{"version":"2.3"}"""
        val output = StringWriter()
        output.withBackupJsonBudget(limits) { }.use { writer ->
            writer.write('{'.code)
            writer.write("x\"version\":y".toCharArray(), 1, 10)
            writer.write("x\"2.3\"}y", 1, 6)
        }
        assertEquals(expected, output.toString())
        StringReader(expected).withBackupJsonBudget(limits) { }.use { reader ->
            val result = StringBuilder()
            while (true) {
                val character = reader.read()
                if (character < 0) break
                result.append(character.toChar())
            }
            assertEquals(expected, result.toString())
        }
        val rejected = StringWriter()
        val failure = runCatching {
            rejected.withBackupJsonBudget(BackupJsonLimits(maxMetadataCharacters = 4)) { }.use { it.write(expected) }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("", rejected.toString())
    }

    @Test fun retainedMetadataCharacterBudgetRejectsAfterStagedStatisticsWithoutPublishing() = runBlocking {
        val tracks = (1..300).joinToString(",") { "{\"identityKey\":\"netease:$it\",\"id\":$it}" }
        val json = "{\"version\":\"2.3\",\"playbackStats\":[$tracks],\"recentPlays\":[{\"song\":{\"name\":\"${"x".repeat(128)}\"}}]}"
        assertRejectedMetadata(json, BackupJsonLimits(maxMetadataCharacters = 64))
    }

    @Test fun escapedRootMetadataNamesCannotBypassTheCharacterBudget() = runBlocking {
        val json = "{\"version\":\"2.3\",\"recent\\u0050lays\":[{\"song\":{\"name\":\"${"x".repeat(128)}\"}}]}"
        assertRejectedMetadata(json, BackupJsonLimits(maxMetadataCharacters = 64))
    }

    @Test fun metadataObjectBudgetIncludesSongsNestedInsideAPlaylist() = runBlocking {
        val json = """{"playlists":[{"id":9,"songs":[{"id":1},{"id":2}]}]}"""
        val directory = temporary.newFolder()
        BackupJsonReader(FileSyncPlaybackDatasetStore(directory), limits = BackupJsonLimits(maxMetadataObjects = 3))
            .read(StringReader(json)).use { assertEquals(listOf(1L, 2L), it.data.playlists.orEmpty().single().songs.map { song -> song.id }) }
        assertRejectedMetadata(json, BackupJsonLimits(maxMetadataObjects = 2))
    }

    @Test fun nullableMetadataValuesUseTheirExactCharacterBoundaryAndUnknownValuesStaySkipped() = runBlocking {
        val json = """{"unknown":{"large":"${"ignored".repeat(100)}"},"version":"2.3","playlists":[],"recentPlays":null}"""
        val directory = temporary.newFolder()
        BackupJsonReader(FileSyncPlaybackDatasetStore(directory), limits = BackupJsonLimits(maxMetadataCharacters = 11))
            .read(StringReader(json)).use {
                assertEquals("2.3", it.data.version)
                assertEquals(null, it.data.recentPlays)
            }
        assertRejectedMetadata(json, BackupJsonLimits(maxMetadataCharacters = 10))
    }

    @Test fun oversizedNamesStringsAndScalarsStopInputBeforeTheEntireTokenIsRead() = runBlocking {
        val oversized = "x".repeat(256)
        for (json in listOf("{\"$oversized\":0}", "{\"unknown\":\"$oversized\"}", "{\"unknown\":${"1".repeat(256)}}")) {
            val directory = temporary.newFolder()
            val input = object : FilterReader(StringReader(json)) {
                var consumed = 0
                override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, minOf(length, 16)).also { if (it > 0) consumed += it }
            }
            val failure = runCatching {
                BackupJsonReader(FileSyncPlaybackDatasetStore(directory), limits = BackupJsonLimits(maxTokenCharacters = 32))
                    .read(input).use { }
            }.exceptionOrNull()
            assertTrue("Expected a budget IOException, got $failure", failure is IOException)
            assertTrue("The oversized token was fully consumed", input.consumed < json.length)
            assertTrue(directory.walkTopDown().none { it.isFile })
        }
    }

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

    private suspend fun assertRejectedMetadata(json: String, limits: BackupJsonLimits) {
        val directory = temporary.newFolder()
        val failure = runCatching {
            BackupJsonReader(FileSyncPlaybackDatasetStore(directory), limits = limits).read(StringReader(json)).use { }
        }.exceptionOrNull()
        assertTrue("Expected a budget IOException, got $failure", failure is IOException)
        assertTrue(directory.walkTopDown().none { it.isFile })
    }

    private class RepeatedPiecesReader(private val pieces: Iterator<Pair<String, Int>>) : Reader() {
        private var current = ""
        private var remaining = 0
        private var position = 0
        var consumed = 0L
            private set

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            var count = 0
            while (count < length) {
                if (remaining == 0) {
                    if (!pieces.hasNext()) break
                    val piece = pieces.next()
                    current = piece.first
                    remaining = piece.second
                    position = 0
                }
                buffer[offset + count] = current[position]
                count++
                position++
                if (position == current.length) {
                    remaining--
                    position = 0
                }
            }
            consumed += count
            return if (count == 0) -1 else count
        }

        override fun close() = Unit
    }
}
