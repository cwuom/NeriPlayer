package moe.ouom.neriplayer.data.platform.bili.skip

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.platform.bili.skip.model.BiliVideoSkipDraft
import moe.ouom.neriplayer.data.platform.bili.skip.model.BiliVideoSkipInterval
import moe.ouom.neriplayer.data.platform.bili.skip.model.BiliVideoSkipRule
import moe.ouom.neriplayer.data.platform.bili.skip.model.BiliVideoSkipTarget
import moe.ouom.neriplayer.data.platform.bili.skip.model.BiliVideoSkipSnapshot
import moe.ouom.neriplayer.data.platform.bili.skip.storage.BiliVideoSkipStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BiliVideoSkipRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val target = BiliVideoSkipTarget("BV1saved", 42L)
    private val interval = BiliVideoSkipInterval(1_000L, 2_000L)
    private val rule = BiliVideoSkipRule(target, listOf(interval), modifiedAt = 10L)

    @Test
    fun primaryStoreWinsOverLegacyFiles() {
        File(temporaryFolder.root, BiliVideoSkipRepository.RULES_FILE_NAME).writeText("not-json")
        val store = RecordingStore(BiliVideoSkipSnapshot(listOf(rule), emptyList()))
        val cleanupReasons = mutableListOf<String>()

        val repository = repository(store, cleanupReasons = cleanupReasons)

        assertEquals(listOf(rule), repository.snapshot())
        assertEquals(listOf("bili-skip-room-load"), cleanupReasons)
        assertTrue(store.ruleWrites.isEmpty())
    }

    @Test
    fun legacyRulesAndDraftsImportTogetherBeforeCleanup() {
        val rulesFile = File(temporaryFolder.root, BiliVideoSkipRepository.RULES_FILE_NAME)
        rulesFile.writeText(
            """{"rules":[{"target":{"bvid":" BV1saved ","cid":42},"intervals":[{"startMs":-1000,"endMs":2000}],"modifiedAt":10}]}"""
        )
        File(temporaryFolder.root, BiliVideoSkipRepository.DRAFTS_FILE_NAME).writeText(
            """{"drafts":[{"target":{"bvid":"BV1saved","cid":42},"startText":" 00:01 ","endText":"00:02","modifiedAt":11}]}"""
        )
        val store = RecordingStore(primary = false)
        val cleanupReasons = mutableListOf<String>()

        val repository = repository(store, cleanupReasons = cleanupReasons)

        assertEquals(listOf(rule.copy(intervals = listOf(BiliVideoSkipInterval(0L, 2_000L)))), repository.snapshot())
        assertEquals(BiliVideoSkipDraft(target, "00:01", "00:02", 11L), repository.draftFor(target))
        assertEquals(store.snapshot.rules, repository.snapshot())
        assertEquals(listOf("bili-skip-import"), cleanupReasons)
        assertTrue(store.primary)
        assertTrue(rulesFile.exists())
    }

    @Test
    fun successfulEditSchedulesSyncAfterPersistenceAndIgnoresNoOp() = runBlocking {
        val store = RecordingStore()
        var syncCount = 0
        val repository = repository(store, onRulesChanged = {
            assertEquals(1, store.ruleWrites.size)
            syncCount++
        })

        assertTrue(repository.replaceIntervals(target, listOf(interval)))
        assertFalse(repository.replaceIntervals(target, listOf(interval)))

        assertEquals(listOf(interval), repository.intervalsFor(target))
        assertEquals(1, syncCount)
        assertEquals(1, store.ruleWrites.size)
    }

    @Test
    fun cancelledPersistenceKeepsVisibleSnapshotAndDoesNotScheduleSync() = runBlocking {
        val cancelled = CancellationException("store write cancelled")
        val store = RecordingStore().apply { writeFailure = cancelled }
        var syncCount = 0
        val repository = repository(store, onRulesChanged = { syncCount++ })

        val result = runCatching { repository.replaceIntervals(target, listOf(interval)) }

        val failure = requireNotNull(result.exceptionOrNull())
        assertTrue(failure is CancellationException)
        assertEquals(cancelled.message, failure.message)
        // 跨调度器恢复堆栈时，协程可能复制异常并保留原始 cause
        assertSame(cancelled, generateSequence(failure) { it.cause }.last())
        assertTrue(repository.snapshot().isEmpty())
        assertEquals(0, syncCount)
    }

    @Test
    fun syncChecksCurrentMutationVersionWithoutSchedulingAnotherSync() = runBlocking {
        val store = RecordingStore()
        var mutationVersion = 9L
        var syncCount = 0
        val repository = repository(
            store,
            readSyncMutationVersion = { mutationVersion },
            onRulesChanged = { syncCount++ }
        )

        assertFalse(repository.replaceFromSyncIfUnchanged(listOf(rule), 8L))
        assertTrue(store.ruleWrites.isEmpty())
        mutationVersion = 10L
        assertTrue(repository.replaceFromSyncIfUnchanged(listOf(rule), 10L))
        assertEquals(listOf(rule), repository.snapshot())
        assertEquals(0, syncCount)
    }

    @Test
    fun newerDraftCancelsPendingWriteAndPersistsLatestText() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val latestPersisted = CompletableDeferred<List<BiliVideoSkipDraft>>()
        val store = object : RecordingStore() {
            override suspend fun replaceDrafts(drafts: List<BiliVideoSkipDraft>, now: Long) {
                if (drafts.single().startText == "first") {
                    firstStarted.complete(Unit)
                    try {
                        withTimeout(5_000L) { awaitCancellation() }
                    } finally {
                        firstCancelled.complete(Unit)
                    }
                } else {
                    super.replaceDrafts(drafts, now)
                    latestPersisted.complete(drafts)
                }
            }
        }
        val repository = repository(store)

        repository.saveDraft(target, "first", "")
        withTimeout(5_000L) { firstStarted.await() }
        repository.saveDraft(target, "latest", "end")
        val persisted = withTimeout(5_000L) { latestPersisted.await() }

        assertTrue(firstCancelled.isCompleted)
        assertEquals("latest", persisted.single().startText)
        assertEquals(repository.drafts.value, persisted)
    }

    private fun repository(
        store: BiliVideoSkipStore,
        readSyncMutationVersion: () -> Long = { 0L },
        onRulesChanged: () -> Unit = {},
        cleanupReasons: MutableList<String> = mutableListOf()
    ) = BiliVideoSkipRepository(
        store = store,
        legacyDirectory = temporaryFolder.root,
        readSyncMutationVersion = readSyncMutationVersion,
        onRulesChanged = onRulesChanged,
        scheduleLegacyCleanup = { cleanupReasons += it }
    )

    private open class RecordingStore(
        var snapshot: BiliVideoSkipSnapshot = BiliVideoSkipSnapshot(emptyList(), emptyList()),
        var primary: Boolean = true
    ) : BiliVideoSkipStore {
        val ruleWrites = mutableListOf<List<BiliVideoSkipRule>>()
        var writeFailure: Throwable? = null

        override suspend fun isPrimary(): Boolean = primary

        override suspend fun readIfPrimary(): BiliVideoSkipSnapshot? = snapshot.takeIf { primary }

        override suspend fun replaceAll(
            rules: List<BiliVideoSkipRule>,
            drafts: List<BiliVideoSkipDraft>,
            now: Long
        ) {
            snapshot = BiliVideoSkipSnapshot(rules, drafts)
            primary = true
        }

        override suspend fun replaceRules(rules: List<BiliVideoSkipRule>, now: Long) {
            writeFailure?.let { throw it }
            ruleWrites += rules
            snapshot = snapshot.copy(rules = rules)
        }

        override suspend fun replaceDrafts(drafts: List<BiliVideoSkipDraft>, now: Long) {
            snapshot = snapshot.copy(drafts = drafts)
        }
    }
}
