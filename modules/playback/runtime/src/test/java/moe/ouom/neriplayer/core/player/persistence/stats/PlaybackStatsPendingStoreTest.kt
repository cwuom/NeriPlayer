package moe.ouom.neriplayer.core.player.persistence.stats

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.common.io.writeTextAtomically
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsOwner
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsPendingWrites
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsSnapshot
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsTracker
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsWritePort
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStatsPendingStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `six hundred deltas spill while the first database write is hung and replay in full`() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = FilePlaybackStatsPendingStore(directory)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val accepted = mutableListOf<PlaybackStatsSnapshot>()
        val port = port { snapshot -> gate.await(); accepted += snapshot }
        val pending = PlaybackStatsPendingWrites(store, scope)
        var now = 0L
        val owner = PlaybackStatsOwner(backgroundScope, port,
            PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }), pending)
        try {
            owner.onSongChanged(song(), 42, true)
            owner.onPlayingChanged(true, "start", true)
            repeat(600) {
                now += 15_000
                owner.flushPeriodic(true)
                runCurrent()
                assertTrue(pending.canCollect)
            }
            assertEquals(600, directory.listFiles().orEmpty().count { it.extension == "delta" })
            assertTrue(accepted.isEmpty())
            gate.complete(Unit)
            runCurrent()
            assertEquals(600, accepted.size)
            assertEquals(600, accepted.map { it.eventId }.toSet().size)
            assertEquals(9_000_000L, accepted.sumOf { it.listenedMs })
            assertTrue(accepted.all { it.localPlaylistId == 42L && it.song.matchedLyric == null })
            assertFalse(pending.hasPendingWork())
        } finally { scope.cancel(); runCurrent(); store.close() }
    }

    @Test
    fun `reopening preserves fifo timestamps clear fence nullable fields and statistics identity`() {
        val directory = temporaryFolder.newFolder()
        val first = sample("old", 1_700_000_000_000L, song().copy(customName = "", mediaUri = null))
        val second = sample("new", 1_700_086_400_000L, song().copy(sourceStableKey = "9|netease|", mediaUri = ""))
        FilePlaybackStatsPendingStore(directory).use { it.append(first); it.append(second) }
        FilePlaybackStatsPendingStore(directory).use {
            assertEquivalent(first, requireNotNull(it.first()))
            it.acknowledge(first.eventId)
            assertEquivalent(second, requireNotNull(it.first()))
            it.acknowledge(second.eventId)
            assertNull(it.first())
        }
        FilePlaybackStatsPendingStore(directory).use { assertNull(it.first()) }
    }

    @Test
    fun `an uncertain append cursor is retried with the original event without another frame`() {
        val directory = temporaryFolder.newFolder()
        var fail = true
        val store = FilePlaybackStatsPendingStore(directory, writeCursor = { file, text ->
            file.writeTextAtomically(text)
            if (fail && text.contains("\"tail\":1")) { fail = false; throw IOException("commit acknowledgement unavailable") }
        })
        store.use {
            val sample = sample("one")
            assertThrows(IOException::class.java) { it.append(sample) }
            it.append(sample)
            assertEquals(1, directory.listFiles().orEmpty().count { file -> file.extension == "delta" })
            assertEquivalent(sample, requireNotNull(it.first()))
            assertThrows(IOException::class.java) { it.append(sample.copy(listenedMs = 99)) }
            it.acknowledge(sample.eventId)
            assertNull(it.first())
        }
    }

    @Test
    fun `a complete frame is not confirmed until retry successfully syncs it`() {
        val directory = temporaryFolder.newFolder()
        var fail = true
        var syncs = 0
        FilePlaybackStatsPendingStore(directory, syncFrame = { file ->
            syncs++
            if (fail) throw IOException("synthetic sync failure")
            RandomAccessFile(file, "rw").use { it.fd.sync() }
        }).use { store ->
            val event = sample("one")
            assertThrows(IOException::class.java) { store.append(event) }
            assertEquals(0, directory.listFiles().orEmpty().count { it.extension == "delta" })
            fail = false
            store.append(event)
            assertTrue(syncs >= 2)
            assertEquivalent(event, requireNotNull(store.first()))
            store.acknowledge(event.eventId)
        }
    }

    @Test
    fun `invalid frame version length checksum and truncation preserve the bad head`() {
        val mutations: List<(ByteArray) -> ByteArray> = listOf(
            { bytes -> bytes.apply { ByteBuffer.wrap(this).putInt(4, 2) } },
            { bytes -> bytes.apply { ByteBuffer.wrap(this).putInt(8, Int.MAX_VALUE) } },
            { bytes -> bytes.apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() } },
            { bytes -> bytes.copyOf(bytes.size - 1) }
        )
        for (mutate in mutations) {
            val directory = temporaryFolder.newFolder()
            FilePlaybackStatsPendingStore(directory).use { it.append(sample("one")) }
            val file = directory.listFiles().orEmpty().single { it.extension == "delta" }
            val damaged = mutate(file.readBytes())
            file.writeBytes(damaged)
            FilePlaybackStatsPendingStore(directory).use { store ->
                assertThrows(IOException::class.java) { store.first() }
                assertTrue(file.readBytes().contentEquals(damaged))
            }
        }
    }

    @Test
    fun `statistics payload excludes lyrics but matches the actual repository projection`() {
        val variants = listOf(song(), song().copy(sourceStableKey = "123|netease|", mediaUri = null),
            song().copy(album = "Bilibili", channelId = "bilibili", audioId = "BVtest", subAudioId = "2"),
            song().copy(album = "Local", localFilePath = "/music/a.flac", sourceStableKey = null))
        for (original in variants) {
            val directory = temporaryFolder.newFolder()
            FilePlaybackStatsPendingStore(directory).use { store ->
                store.append(sample("identity", song = original))
                val restored = requireNotNull(store.first()).song
                assertEquals(projection(original), projection(restored))
                assertNull(restored.matchedLyric)
                assertNull(restored.originalLyric)
                assertNull(restored.matchedTranslatedLyric)
                assertNull(restored.matchedRomanizedLyric)
            }
        }
    }

    @Test
    fun `oversized metadata fails without a published frame or a replacement of prior events`() {
        val directory = temporaryFolder.newFolder()
        FilePlaybackStatsPendingStore(directory).use { store ->
            val event = sample("first")
            store.append(event)
            assertThrows(IOException::class.java) { store.append(sample("large", song = song().copy(name = "x".repeat(1024 * 1024)))) }
            assertEquivalent(event, requireNotNull(store.first()))
            assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "delta" })
        }
    }

    @Test
    fun `acknowledgement failure before and after cursor commit never skips or repeats a later head`() {
        for (commitBeforeFailure in listOf(false, true)) {
            val directory = temporaryFolder.newFolder()
            var fail = false
            FilePlaybackStatsPendingStore(directory, writeCursor = { file, text ->
                if (fail && text.contains("\"head\":1")) {
                    fail = false
                    if (commitBeforeFailure) file.writeTextAtomically(text)
                    throw IOException("uncertain acknowledgement")
                }
                file.writeTextAtomically(text)
            }).use { store ->
                store.append(sample("first"))
                store.append(sample("second"))
                fail = true
                assertThrows(IOException::class.java) { store.acknowledge("first") }
                assertEquals(if (commitBeforeFailure) "second" else "first", store.first()?.eventId)
                if (!commitBeforeFailure) store.acknowledge("first")
                assertEquals("second", store.first()?.eventId)
                store.acknowledge("second")
                assertNull(store.first())
            }
        }
    }

    @Test
    fun `more than the receipt window remains behind a database-accepted head whose disk ack fails`() = runTest {
        val directory = temporaryFolder.newFolder()
        var blocked = true
        val store = FilePlaybackStatsPendingStore(directory, writeCursor = { file, text ->
            if (blocked && text.contains("\"head\":1")) throw IOException("head acknowledgement unavailable")
            file.writeTextAtomically(text)
        })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val attempted = mutableListOf<String>()
        val accepted = linkedMapOf<String, PlaybackStatsSnapshot>()
        val port = port { attempted += it.eventId; accepted.putIfAbsent(it.eventId, it) }
        val pending = PlaybackStatsPendingWrites(store, scope)
        try {
            pending.activate(port)
            repeat(300) { index -> pending.enqueue(sample("event-$index", 1_700_000_000_000L + index * 86_400_000L)); runCurrent() }
            assertEquals(setOf("event-0"), attempted.toSet())
            assertEquals(listOf("event-0"), accepted.keys.toList())
            blocked = false
            pending.flush(port)
            assertEquals((0 until 300).map { "event-$it" }, accepted.keys.toList())
            assertEquals(4_500_000L, accepted.values.sumOf { it.listenedMs })
            assertNull(store.first())
        } finally { scope.cancel(); runCurrent(); store.close() }
    }

    @Test
    fun `a partial unpublished frame in a halted process cannot block a durable prefix`() {
        val directory = temporaryFolder.newFolder()
        val log = temporaryFolder.newFile()
        val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", childClassPath(), PlaybackStatsPendingStoreProcess::class.java.name, directory.absolutePath)
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue("journal child did not exit", child.waitFor(15, TimeUnit.SECONDS))
            assertEquals(log.readText(), 73, child.exitValue())
            assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "tmp" })
            val unrelated = File(directory, ".npst-v2-frame-00000000-0000-0000-0000-000000000000.tmp").apply { writeText("future") }
            FilePlaybackStatsPendingStore(directory).use { store ->
                assertEquals("prefix", store.first()?.eventId)
                assertEquals(listOf(unrelated), directory.listFiles().orEmpty().filter { it.extension == "tmp" })
                store.acknowledge("prefix")
                assertNull(store.first())
                store.append(sample("after-restart"))
                assertEquals("after-restart", store.first()?.eventId)
            }
        } finally {
            if (child.isAlive) child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `second same-process store refuses a live journal and a closed owner can reopen it`() {
        val directory = temporaryFolder.newFolder()
        val first = FilePlaybackStatsPendingStore(directory)
        val second = FilePlaybackStatsPendingStore(directory)
        try {
            first.append(sample("first"))
            assertThrows(IOException::class.java) { second.first() }
            first.close()
            first.close()
            assertEquals("first", second.first()?.eventId)
        } finally { first.close(); second.close() }
    }

    @Test
    fun `a blocked real frame write does not block callbacks and only sampled handoffs replay`() {
        val directory = temporaryFolder.newFolder()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "statistics-callback") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = FilePlaybackStatsPendingStore(directory, writeFrame = { file, bytes ->
            assertFalse(Thread.currentThread().name == "statistics-callback")
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
            file.writeBytes(bytes)
        })
        val accepted = mutableListOf<PlaybackStatsSnapshot>()
        val port = port { synchronized(accepted) { accepted += it } }
        val pending = PlaybackStatsPendingWrites(store, scope)
        var now = 0L
        val owner = PlaybackStatsOwner(scope, port,
            PlaybackStatsTracker(songKey = { it.id.toString() }, nowElapsedMs = { now }), pending)
        try {
            owner.onSongChanged(song(), 42, true)
            owner.onPlayingChanged(true, "start", true)
            now = 15_000
            owner.flushPeriodic(true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            callbacks.submit {
                repeat(600) { now += 15_000; owner.flushPeriodic(true) }
            }.get(2, TimeUnit.SECONDS)
            assertFalse(pending.canCollect)
            release.countDown()
            kotlinx.coroutines.runBlocking { pending.flush(port) }
            assertEquals(32, accepted.size)
            assertTrue(accepted.all { it.song.matchedLyric == null })
        } finally {
            release.countDown()
            callbacks.shutdownNow()
            assertTrue(callbacks.awaitTermination(5, TimeUnit.SECONDS))
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun `corrupt cursor and symlinked journal paths cannot silently reset or change stored events`() {
        val directory = temporaryFolder.newFolder()
        FilePlaybackStatsPendingStore(directory).use { it.append(sample("first")) }
        val cursor = File(directory, "cursor.json")
        val trusted = cursor.readText()
        cursor.writeText(trusted.replace("\"tail\":1", "\"tail\":2"))
        FilePlaybackStatsPendingStore(directory).use { assertThrows(IOException::class.java) { it.first() } }
        cursor.writeText(trusted)
        val frame = directory.listFiles().orEmpty().single { it.extension == "delta" }
        val foreign = temporaryFolder.newFile().apply { writeText("foreign") }
        val originalFrame = frame.readBytes()
        check(frame.delete())
        Files.createSymbolicLink(frame.toPath(), foreign.toPath())
        try {
            FilePlaybackStatsPendingStore(directory).use { assertThrows(IOException::class.java) { it.first() } }
            assertEquals("foreign", foreign.readText())
        } finally { Files.delete(frame.toPath()); frame.writeBytes(originalFrame) }
        FilePlaybackStatsPendingStore(directory).use { assertEquals("first", it.first()?.eventId) }
    }

    private fun childClassPath(): String {
        val entries = linkedSetOf<String>()
        entries += requireNotNull(System.getProperty("java.class.path")).split(File.pathSeparator).filter(String::isNotBlank)
        generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>().forEach { loader ->
            loader.urLs.filter { it.protocol == "file" }.forEach { entries += File(it.toURI()).absolutePath }
        }
        return entries.joinToString(File.pathSeparator)
    }

    private fun assertEquivalent(expected: PlaybackStatsSnapshot, actual: PlaybackStatsSnapshot) {
        assertEquals(expected.copy(song = actual.song), actual)
        assertEquals(projection(expected.song), projection(actual.song))
    }

    private fun projection(song: SongItem): Any? = Class.forName("moe.ouom.neriplayer.data.stats.PlaybackStatsRepositoryKt")
        .getMethod("toStatisticsMetadata", SongItem::class.java).invoke(null, song)

    private fun sample(id: String, time: Long = 1_700_000_000_000L, song: SongItem = song()) =
        PlaybackStatsSnapshot(song, 15_000, 1, true, 42, id, time, 1_600_000_000_000L)

    private fun song() = SongItem(7, "name", "artist", "Netease", 9, 60_000, "cover", mediaUri = "uri",
        matchedLyric = "synthetic lyrics".repeat(4096), originalLyric = "baseline",
        matchedTranslatedLyric = "translation", matchedRomanizedLyric = "romanization",
        customName = "custom", customArtist = "custom artist", customCoverUrl = "custom cover",
        localFileName = "a.flac", localFilePath = "/music/a.flac", channelId = "netease", audioId = "7")

    private fun port(record: suspend (PlaybackStatsSnapshot) -> Unit) = object : PlaybackStatsWritePort {
        override suspend fun record(snapshot: PlaybackStatsSnapshot) = record.invoke(snapshot)
        override fun hasPendingWrites() = false
        override suspend fun flushPendingWrites() = Unit
    }
}

object PlaybackStatsPendingStoreProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = File(args.single())
        val song = SongItem(1, "synthetic", "artist", "album", 1, 60_000, null)
        FilePlaybackStatsPendingStore(directory).use {
            it.append(PlaybackStatsSnapshot(song, 15_000, 0, false, eventId = "prefix"))
        }
        FilePlaybackStatsPendingStore(directory, writeFrame = { file, bytes ->
            file.outputStream().use { it.write(bytes, 0, 8); it.flush() }
            Runtime.getRuntime().halt(73)
        }).append(PlaybackStatsSnapshot(song, 15_000, 0, false, eventId = "unfinished"))
        error("the child must exit inside its unfinished append")
    }
}
