package moe.ouom.neriplayer.ui.screen.playlist.curation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.media.curation.CommittedEmbeddedCover
import moe.ouom.neriplayer.data.local.media.curation.EmbeddedCoverSizePreset
import moe.ouom.neriplayer.data.local.media.curation.LocalEmbeddedAssetState
import moe.ouom.neriplayer.data.local.media.curation.PreparedEmbeddedCover
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricTimelineFit
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalCurationCoordinatorTest {

    @Test
    fun `analysis builds proposals, auto selects confident ones and reaches review`() = runTest {
        val ports = FakePorts(
            assets = mapOf(
                "missing" to assets(),
                "complete" to assets(lyricBytes = 50, coverBytes = 500)
            ),
            matches = mapOf("missing" to listOf(ranked("m", coverUrl = "https://img/m.jpg")))
        )
        val coordinator = coordinator(ports)

        assertTrue(coordinator.start(listOf(song("missing"), song("complete"), song("unreadable")), options()))
        advanceUntilIdle()

        val session = requireNotNull(coordinator.session.value)
        assertEquals(LocalCurationStage.REVIEW, session.stage)
        val missing = session.item("missing")
        assertEquals(LocalCurationItemPhase.READY, missing.phase)
        assertEquals(LocalCurationLyricOrigin.ONLINE, missing.lyric?.origin)
        assertEquals(LocalCurationCoverOrigin.ONLINE, missing.cover?.origin)
        assertTrue(missing.applyLyric)
        assertTrue(missing.applyCover)
        assertEquals(LocalCurationItemPhase.COMPLETE, session.item("complete").phase)
        assertEquals(LocalCurationItemPhase.UNSUPPORTED, session.item("unreadable").phase)
        assertEquals(listOf("missing"), ports.matchedTitles)
    }

    @Test
    fun `folder artwork wins over online artwork and existing lyrics skip the network`() = runTest {
        val ports = FakePorts(
            assets = mapOf("song" to assets()),
            currentLyrics = mapOf("song" to LocalCurrentLyrics("[00:01.00]mine", null, null)),
            folderCovers = mapOf("song" to "file:///music/cover.jpg"),
            matches = mapOf("song" to listOf(ranked("m", coverUrl = "https://img/m.jpg")))
        )
        val coordinator = coordinator(ports)

        coordinator.start(listOf(song("song")), options())
        advanceUntilIdle()

        val item = requireNotNull(coordinator.session.value).item("song")
        assertEquals(LocalCurationLyricOrigin.EXISTING, item.lyric?.origin)
        assertEquals(LocalCurationCoverOrigin.FOLDER, item.cover?.origin)
        assertTrue(ports.matchedTitles.isEmpty())
    }

    @Test
    fun `album songs sharing a folder cover prepare it once`() = runTest {
        val folder = "file:///album/cover.jpg"
        val ports = FakePorts(
            assets = mapOf("a" to assets(lyricBytes = 1), "b" to assets(lyricBytes = 1)),
            folderCovers = mapOf("a" to folder, "b" to folder)
        )
        val coordinator = coordinator(ports)

        coordinator.start(listOf(song("a"), song("b")), options())
        advanceUntilIdle()

        assertEquals(listOf(folder), ports.preparedReferences)
        assertNotNull(requireNotNull(coordinator.session.value).item("b").cover)
    }

    @Test
    fun `low confidence matches are proposed but not selected`() = runTest {
        val ports = FakePorts(
            assets = mapOf("song" to assets(coverBytes = 10)),
            matches = mapOf("song" to listOf(ranked("m", confidence = EditableLyricMatchConfidence.LOW)))
        )
        val coordinator = coordinator(ports)

        coordinator.start(listOf(song("song")), options())
        advanceUntilIdle()

        val item = requireNotNull(coordinator.session.value).item("song")
        assertNotNull(item.lyric)
        assertFalse(item.applyLyric)
        assertFalse(coordinator.apply())
    }

    @Test
    fun `apply writes selected songs with the playing one last and reports the result`() = runTest {
        val ports = FakePorts(
            assets = mapOf("a" to assets(coverBytes = 1), "b" to assets(coverBytes = 1)),
            matches = mapOf("a" to listOf(ranked("a")), "b" to listOf(ranked("b"))),
            playingKey = "a"
        )
        val coordinator = coordinator(ports)
        coordinator.start(listOf(song("a"), song("b")), options())
        advanceUntilIdle()

        assertTrue(coordinator.apply())
        advanceUntilIdle()

        val session = requireNotNull(coordinator.session.value)
        assertEquals(LocalCurationStage.FINISHED, session.stage)
        assertEquals(listOf("b", "a"), ports.appliedKeys)
        assertEquals(2, session.appliedCount)
        assertEquals(2 to 0, ports.finished)
    }

    @Test
    fun `failed writes can be retried without rewriting successful songs`() = runTest {
        val ports = FakePorts(
            assets = mapOf("ok" to assets(coverBytes = 1), "bad" to assets(coverBytes = 1)),
            matches = mapOf("ok" to listOf(ranked("ok")), "bad" to listOf(ranked("bad"))),
            failingKeys = mutableSetOf("bad")
        )
        val coordinator = coordinator(ports)
        coordinator.start(listOf(song("ok"), song("bad")), options())
        advanceUntilIdle()
        coordinator.apply()
        advanceUntilIdle()

        val failed = requireNotNull(coordinator.session.value).item("bad")
        assertEquals(LocalCurationItemPhase.FAILED, failed.phase)
        assertEquals(LocalCurationFailure.LYRICS_WRITE, failed.failure)

        ports.failingKeys.clear()
        ports.appliedKeys.clear()
        assertTrue(coordinator.retryFailed())
        advanceUntilIdle()

        assertEquals(listOf("bad"), ports.appliedKeys)
        assertEquals(2, requireNotNull(coordinator.session.value).appliedCount)
    }

    @Test
    fun `cancelling analysis keeps finished results available for review`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ports = FakePorts(
            assets = mapOf("fast" to assets(coverBytes = 1), "slow" to assets(coverBytes = 1)),
            matches = mapOf("fast" to listOf(ranked("fast")), "slow" to listOf(ranked("slow"))),
            matchGate = mapOf("slow" to gate)
        )
        val coordinator = coordinator(ports)
        coordinator.start(listOf(song("fast"), song("slow")), options())
        runCurrent()

        coordinator.cancel()
        advanceUntilIdle()

        val session = requireNotNull(coordinator.session.value)
        assertEquals(LocalCurationStage.REVIEW, session.stage)
        assertTrue(session.cancelled)
        assertEquals(LocalCurationItemPhase.READY, session.item("fast").phase)
        assertEquals(LocalCurationItemPhase.QUEUED, session.item("slow").phase)
    }

    @Test
    fun `only one session runs at a time and dismiss clears a finished one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ports = FakePorts(
            assets = mapOf("a" to assets()),
            matchGate = mapOf("a" to gate)
        )
        val coordinator = coordinator(ports)

        assertTrue(coordinator.start(listOf(song("a")), options()))
        runCurrent()
        assertFalse(coordinator.start(listOf(song("a")), options()))
        coordinator.dismiss()
        assertNotNull(coordinator.session.value)

        gate.complete(Unit)
        advanceUntilIdle()
        coordinator.dismiss()
        advanceUntilIdle()

        assertNull(coordinator.session.value)
        assertTrue(ports.stagingCleared >= 2)
    }

    @Test
    fun `selection toggles only affect available proposals during review`() = runTest {
        val ports = FakePorts(
            assets = mapOf("a" to assets(coverBytes = 1)),
            matches = mapOf("a" to listOf(ranked("a")))
        )
        val coordinator = coordinator(ports)
        coordinator.start(listOf(song("a")), options())
        advanceUntilIdle()

        coordinator.setCoverSelected("a", true)
        coordinator.setLyricSelected("a", false)
        val item = requireNotNull(coordinator.session.value).item("a")
        assertFalse(item.applyCover)
        assertFalse(item.applyLyric)

        coordinator.setAllSelected(true)
        assertTrue(requireNotNull(coordinator.session.value).item("a").applyLyric)
    }

    @Test
    fun `alternatives can replace the proposed lyric and the previous one stays switchable`() = runTest {
        val ports = FakePorts(
            assets = mapOf("song" to assets(coverBytes = 1)),
            matches = mapOf(
                "song" to listOf(
                    ranked("first", confidence = EditableLyricMatchConfidence.LOW),
                    ranked("second"),
                    ranked("third")
                )
            )
        )
        val coordinator = coordinator(ports)
        coordinator.start(listOf(song("song")), options())
        advanceUntilIdle()
        val before = requireNotNull(coordinator.session.value).item("song")
        assertEquals("first", before.lyric?.matchedTitle)
        assertEquals(listOf("second", "third"), before.lyricAlternatives.map { it.matchedTitle })
        assertFalse(before.applyLyric)

        coordinator.chooseLyricAlternative("song", 1)

        val after = requireNotNull(coordinator.session.value).item("song")
        assertEquals("third", after.lyric?.matchedTitle)
        assertEquals(listOf("first", "second"), after.lyricAlternatives.map { it.matchedTitle })
        assertTrue(after.applyLyric)
        coordinator.chooseLyricAlternative("song", 9)
        assertEquals(after, requireNotNull(coordinator.session.value).item("song"))
    }

    @Test
    fun `online artwork follows the chosen lyric candidate`() = runTest {
        val ports = FakePorts(
            assets = mapOf("song" to assets()),
            matches = mapOf(
                "song" to listOf(
                    ranked("first", coverUrl = "https://img/first.jpg"),
                    ranked("second", coverUrl = "https://img/second.jpg"),
                    ranked("third")
                )
            )
        )
        val coordinator = coordinator(ports)
        coordinator.start(listOf(song("song")), options())
        advanceUntilIdle()
        assertEquals(staged("https://img/first.jpg"), coordinator.session.value?.item("song")?.cover?.prepared?.reference)

        coordinator.chooseLyricAlternative("song", 0)
        advanceUntilIdle()
        val second = requireNotNull(coordinator.session.value).item("song")
        assertEquals("second", second.lyric?.matchedTitle)
        assertEquals(staged("https://img/second.jpg"), second.cover?.prepared?.reference)
        assertTrue(second.applyCover)

        coordinator.chooseLyricAlternative("song", second.lyricAlternatives.indexOfFirst { it.matchedTitle == "third" })
        advanceUntilIdle()
        val third = requireNotNull(coordinator.session.value).item("song")
        assertNull(third.cover)
        assertFalse(third.applyCover)

        coordinator.chooseLyricAlternative("song", third.lyricAlternatives.indexOfFirst { it.matchedTitle == "first" })
        advanceUntilIdle()
        val back = requireNotNull(coordinator.session.value).item("song")
        assertEquals(staged("https://img/first.jpg"), back.cover?.prepared?.reference)
        assertEquals(1, ports.preparedReferences.count { it == "https://img/first.jpg" })
    }

    @Test
    fun `folder artwork stays when another lyric candidate is chosen`() = runTest {
        val ports = FakePorts(
            assets = mapOf("song" to assets()),
            folderCovers = mapOf("song" to "file:///music/cover.jpg"),
            matches = mapOf(
                "song" to listOf(
                    ranked("first", coverUrl = "https://img/first.jpg"),
                    ranked("second", coverUrl = "https://img/second.jpg")
                )
            )
        )
        val coordinator = coordinator(ports)
        coordinator.start(listOf(song("song")), options())
        advanceUntilIdle()

        coordinator.chooseLyricAlternative("song", 0)
        advanceUntilIdle()

        val item = requireNotNull(coordinator.session.value).item("song")
        assertEquals("second", item.lyric?.matchedTitle)
        assertEquals(LocalCurationCoverOrigin.FOLDER, item.cover?.origin)
        assertTrue(item.applyCover)
        assertFalse("https://img/second.jpg" in ports.preparedReferences)
    }

    @Test
    fun `covers committed only for failed songs are discarded after the batch`() = runTest {
        val existing = committed("file:///existing/cover.jpg")
        val ports = FakePorts(
            assets = listOf("lonely", "ok", "sharedFail", "sharedOk", "oldFail").associateWith { assets() },
            folderCovers = mapOf(
                "lonely" to "file:///lonely.jpg",
                "ok" to "file:///ok.jpg",
                "sharedFail" to "file:///shared.jpg",
                "sharedOk" to "file:///shared.jpg",
                "oldFail" to "file:///existing/cover.jpg"
            ),
            failingKeys = mutableSetOf("lonely", "sharedFail", "oldFail"),
            existingCovers = setOf(existing)
        )
        val coordinator = coordinator(ports)
        coordinator.start(
            listOf("lonely", "ok", "sharedFail", "sharedOk", "oldFail").map(::song),
            options().copy(lyricMode = LocalCurationLyricMode.SKIP)
        )
        advanceUntilIdle()
        var stageAtDiscard: LocalCurationStage? = null
        ports.onDiscard = { stageAtDiscard = coordinator.session.value?.stage }

        coordinator.apply()
        advanceUntilIdle()

        assertEquals(listOf(committed("file:///lonely.jpg")), ports.discardedCovers)
        assertEquals(LocalCurationStage.APPLYING, stageAtDiscard)
        assertEquals(LocalCurationStage.FINISHED, coordinator.session.value?.stage)
        assertEquals(committed("file:///ok.jpg"), ports.appliedCovers["ok"])
        assertEquals(committed("file:///shared.jpg"), ports.appliedCovers["sharedOk"])
    }

    @Test
    fun `options used to start a session become the next default`() = runTest {
        val ports = FakePorts(assets = mapOf("a" to assets()))
        val coordinator = coordinator(ports)
        assertEquals(LocalCurationOptions(), coordinator.lastOptions())

        val custom = options().copy(coverSize = EmbeddedCoverSizePreset.ORIGINAL, useOnlineCover = false)
        coordinator.start(listOf(song("a")), custom)
        advanceUntilIdle()

        assertEquals(custom, coordinator.lastOptions())
    }

    @Test
    fun `a large library is analyzed and applied completely`() = runTest {
        val keys = (0 until 3_000).map { "song-$it" }
        val ports = FakePorts(
            assets = keys.associateWith { assets(coverBytes = 1) },
            matches = keys.associateWith { listOf(ranked(it)) }
        )
        val coordinator = LocalCurationCoordinator(ports = ports, scope = this)

        val startedAt = System.nanoTime()
        coordinator.start(keys.map(::song), options().copy(searchParallelism = 10, writeParallelism = 10))
        advanceUntilIdle()
        coordinator.apply()
        advanceUntilIdle()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        val session = requireNotNull(coordinator.session.value)
        assertEquals(LocalCurationStage.FINISHED, session.stage)
        assertEquals(3_000, session.appliedCount)
        assertEquals(3_000, ports.appliedKeys.size)
        println("local curation 3000 songs: analyze+apply ${elapsedMs}ms")
    }

    @Test
    fun `writes run concurrently up to the configured limit and the playing song goes last alone`() = runTest {
        val keys = (0 until 8).map { "s$it" }
        val ports = FakePorts(
            assets = keys.associateWith { assets(coverBytes = 1) },
            matches = keys.associateWith { listOf(ranked(it)) },
            playingKey = "s0",
            applyDelayMs = 100L
        )
        val coordinator = coordinator(ports)
        coordinator.start(keys.map(::song), options().copy(writeParallelism = 3))
        advanceUntilIdle()

        coordinator.apply()
        advanceUntilIdle()

        assertEquals(3, ports.maxConcurrentApplies)
        assertEquals("s0", ports.appliedKeys.last())
        assertEquals(8, requireNotNull(coordinator.session.value).appliedCount)
    }

    @Test
    fun `searches run concurrently up to the configured limit`() = runTest {
        val keys = (0 until 6).map { "s$it" }
        val ports = FakePorts(
            assets = keys.associateWith { assets(coverBytes = 1) },
            matches = keys.associateWith { listOf(ranked(it)) },
            matchDelayMs = 100L
        )
        val coordinator = coordinator(ports)

        coordinator.start(keys.map(::song), options().copy(searchParallelism = 4))
        advanceUntilIdle()

        assertEquals(4, ports.maxConcurrentMatches)
    }

    @Test
    fun `out of range parallelism is clamped before it is used or saved`() = runTest {
        val ports = FakePorts(assets = mapOf("a" to assets()))
        val coordinator = coordinator(ports)

        coordinator.start(listOf(song("a")), options().copy(searchParallelism = 99, writeParallelism = 0))
        advanceUntilIdle()

        val saved = requireNotNull(ports.savedOptions)
        assertEquals(16, saved.searchParallelism)
        assertEquals(1, saved.writeParallelism)
        assertEquals(saved, requireNotNull(coordinator.session.value).options)
    }

    private fun TestScope.coordinator(ports: FakePorts) =
        LocalCurationCoordinator(ports = ports, scope = this)

    private fun options() = LocalCurationOptions(
        lyricMode = LocalCurationLyricMode.FILL_MISSING,
        coverMode = LocalCurationCoverMode.FILL_MISSING,
        coverSize = EmbeddedCoverSizePreset.COMPACT,
        searchParallelism = 2,
        writeParallelism = 2
    )

    private fun LocalCurationSession.item(key: String) = items.first { it.key == key }

    private fun staged(reference: String) = "file:///staged/${reference.hashCode()}.jpg"

    private fun committed(reference: String) = "file:///committed/${reference.hashCode()}.jpg"

    private fun assets(lyricBytes: Int = 0, coverBytes: Int = 0) = LocalEmbeddedAssetState(
        embeddedLyricBytes = lyricBytes,
        embeddedCoverBytes = coverBytes,
        fileSizeBytes = 1_000L,
        audioExtension = "mp3"
    )

    private fun song(key: String) = SongItem(
        id = key.hashCode().toLong(),
        name = key,
        artist = "Artist",
        album = "",
        albumId = 0L,
        durationMs = 200_000L,
        coverUrl = null,
        localFilePath = "/music/$key.mp3"
    )

    private fun ranked(
        id: String,
        confidence: EditableLyricMatchConfidence = EditableLyricMatchConfidence.HIGH,
        coverUrl: String? = null
    ) = RankedEditableLyricMatch(
        candidate = EditableLyricMatchCandidate(
            id = id,
            source = EditableLyricMatchSource.CLOUD_MUSIC,
            title = id,
            artist = "Artist",
            lyrics = "[00:01.00]$id",
            coverUrl = coverUrl
        ),
        score = 100,
        durationDeltaMs = 0L,
        confidence = confidence,
        timelineFit = EditableLyricTimelineFit.FITS
    )

    private class FakePorts(
        private val assets: Map<String, LocalEmbeddedAssetState> = emptyMap(),
        private val currentLyrics: Map<String, LocalCurrentLyrics> = emptyMap(),
        private val folderCovers: Map<String, String> = emptyMap(),
        private val matches: Map<String, List<RankedEditableLyricMatch>> = emptyMap(),
        private val matchGate: Map<String, CompletableDeferred<Unit>> = emptyMap(),
        private val playingKey: String? = null,
        val failingKeys: MutableSet<String> = mutableSetOf(),
        private val applyDelayMs: Long = 0L,
        private val matchDelayMs: Long = 0L,
        private val existingCovers: Set<String> = emptySet()
    ) : LocalCurationPorts {
        val committedCovers = mutableSetOf<String>()
        val discardedCovers = mutableListOf<String>()
        var onDiscard: () -> Unit = {}
        val appliedCovers = mutableMapOf<String, String?>()
        val matchedTitles = mutableListOf<String>()
        val preparedReferences = mutableListOf<String>()
        val appliedKeys = mutableListOf<String>()
        private var concurrentApplies = 0
        var maxConcurrentApplies = 0
        private var concurrentMatches = 0
        var maxConcurrentMatches = 0
        var finished: Pair<Int, Int>? = null
        var stagingCleared = 0

        override fun songKey(song: SongItem): String = song.name
        override fun currentSongKey(): String? = playingKey
        override suspend fun preferWordTimedLyrics(): Boolean = false
        override suspend fun inspect(song: SongItem) = assets[song.name]
        override suspend fun currentLyrics(song: SongItem) = currentLyrics[song.name]
        override suspend fun folderCover(song: SongItem) = folderCovers[song.name]

        override suspend fun matchLyrics(request: EditableLyricMatchRequest): List<RankedEditableLyricMatch> {
            concurrentMatches++
            maxConcurrentMatches = maxOf(maxConcurrentMatches, concurrentMatches)
            try {
                matchGate[request.trackName]?.await()
                if (matchDelayMs > 0L) delay(matchDelayMs)
                matchedTitles += request.trackName
                return matches[request.trackName].orEmpty()
            } finally {
                concurrentMatches--
            }
        }

        override suspend fun prepareCover(reference: String, preset: EmbeddedCoverSizePreset): PreparedEmbeddedCover {
            preparedReferences += reference
            return PreparedEmbeddedCover("file:///staged/${reference.hashCode()}.jpg", 1_000, 500, 500)
        }

        override suspend fun commitCover(prepared: PreparedEmbeddedCover): CommittedEmbeddedCover {
            val reference = prepared.reference.replace("staged", "committed")
            return CommittedEmbeddedCover(reference, newlyCreated = committedCovers.add(reference) && reference !in existingCovers)
        }

        override suspend fun discardCovers(references: Collection<String>) {
            onDiscard()
            discardedCovers += references
        }

        override suspend fun apply(
            song: SongItem,
            lyric: LocalCurationLyricProposal?,
            coverReference: String?
        ): LocalCurationFailure? {
            concurrentApplies++
            maxConcurrentApplies = maxOf(maxConcurrentApplies, concurrentApplies)
            try {
                if (applyDelayMs > 0L) delay(applyDelayMs)
                if (song.name in failingKeys) return LocalCurationFailure.LYRICS_WRITE
                appliedKeys += song.name
                appliedCovers[song.name] = coverReference
                return null
            } finally {
                concurrentApplies--
            }
        }

        override suspend fun resetSessionCaches() {
            stagingCleared++
        }

        override fun notifyFinished(applied: Int, failed: Int) {
            finished = applied to failed
        }

        var savedOptions: LocalCurationOptions? = null
        override fun loadOptions(): LocalCurationOptions? = savedOptions
        override fun saveOptions(options: LocalCurationOptions) {
            savedOptions = options
        }
    }
}
