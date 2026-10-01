package moe.ouom.neriplayer.platform.bilibili.skip

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipDraft
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipInterval
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipRule
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTarget
import moe.ouom.neriplayer.data.model.bilibili.skip.MAX_BILI_VIDEO_SKIP_DRAFT_TEXT_LENGTH
import moe.ouom.neriplayer.platform.bilibili.skip.policy.intervalsForBiliVideoSkipCid
import moe.ouom.neriplayer.platform.bilibili.skip.policy.intervalsForBiliVideoSkipPlayback
import moe.ouom.neriplayer.platform.bilibili.skip.policy.normalizeBiliVideoSkipDrafts
import moe.ouom.neriplayer.platform.bilibili.skip.policy.normalizeBiliVideoSkipIntervals
import moe.ouom.neriplayer.platform.bilibili.skip.policy.normalizeBiliVideoSkipRules
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipSnapshot
import moe.ouom.neriplayer.platform.bilibili.skip.storage.BiliVideoSkipStore
import moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation

@Serializable
private data class BiliVideoSkipRulesDocument(
    val version: Int = 1,
    val rules: List<BiliVideoSkipRule> = emptyList()
)

@Serializable
private data class BiliVideoSkipDraftsDocument(
    val version: Int = 1,
    val drafts: List<BiliVideoSkipDraft> = emptyList()
)

class BiliVideoSkipRepository(
    private val store: BiliVideoSkipStore,
    legacyDirectory: File,
    private val readSyncMutationVersion: () -> Long,
    private val onRulesChanged: () -> Unit,
    private val scheduleLegacyCleanup: (reason: String) -> Unit
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()
    private val draftsMutex = Mutex()
    private val draftsStateLock = Any()
    private val draftsPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rulesFile = File(legacyDirectory, RULES_FILE_NAME)
    private val draftsFile = File(legacyDirectory, DRAFTS_FILE_NAME)
    private val initialSnapshot = runBlocking(Dispatchers.IO) {
        loadInitialSnapshot()
    }
    private val _rules = MutableStateFlow(initialSnapshot.rules)
    private val _drafts = MutableStateFlow(initialSnapshot.drafts)
    private var draftsStateVersion = 0L
    private var draftPersistJob: Job? = null

    val rules: StateFlow<List<BiliVideoSkipRule>> = _rules.asStateFlow()
    val drafts: StateFlow<List<BiliVideoSkipDraft>> = _drafts.asStateFlow()

    fun snapshot(): List<BiliVideoSkipRule> = _rules.value

    fun intervalsFor(target: BiliVideoSkipTarget): List<BiliVideoSkipInterval> {
        val normalizedTarget = target.normalizedOrNull() ?: return emptyList()
        return _rules.value.firstOrNull { rule ->
            !rule.isDeleted && rule.target == normalizedTarget
        }?.intervals.orEmpty()
    }

    fun intervalsForCid(cid: Long): List<BiliVideoSkipInterval> {
        return intervalsForBiliVideoSkipCid(_rules.value, cid)
    }

    fun intervalsForPlayback(
        target: BiliVideoSkipTarget?,
        fallbackCid: Long?,
        fallbackBvid: String? = null
    ): List<BiliVideoSkipInterval> {
        return intervalsForBiliVideoSkipPlayback(
            rules = _rules.value,
            target = target,
            fallbackCid = fallbackCid,
            fallbackBvid = fallbackBvid
        )
    }

    fun draftFor(target: BiliVideoSkipTarget): BiliVideoSkipDraft? {
        val normalizedTarget = target.normalizedOrNull() ?: return null
        return _drafts.value.firstOrNull { draft -> draft.target == normalizedTarget }
    }

    fun saveDraft(target: BiliVideoSkipTarget, startText: String, endText: String) {
        val normalizedTarget = target.normalizedOrNull() ?: return
        val normalizedStartText = startText.trim().take(MAX_BILI_VIDEO_SKIP_DRAFT_TEXT_LENGTH)
        val normalizedEndText = endText.trim().take(MAX_BILI_VIDEO_SKIP_DRAFT_TEXT_LENGTH)
        synchronized(draftsStateLock) {
            val currentDrafts = _drafts.value
            val previous = currentDrafts.firstOrNull { draft -> draft.target == normalizedTarget }
            val updatedDrafts = if (normalizedStartText.isEmpty() && normalizedEndText.isEmpty()) {
                currentDrafts.filterNot { draft -> draft.target == normalizedTarget }
            } else {
                normalizeBiliVideoSkipDrafts(
                    currentDrafts.filterNot { draft -> draft.target == normalizedTarget } +
                        BiliVideoSkipDraft(
                            target = normalizedTarget,
                            startText = normalizedStartText,
                            endText = normalizedEndText,
                            modifiedAt = nextModifiedAt(previous?.modifiedAt ?: 0L)
                        )
                )
            }
            if (currentDrafts == updatedDrafts) return

            _drafts.value = updatedDrafts
            draftsStateVersion += 1L
            val stateVersion = draftsStateVersion
            draftPersistJob?.cancel()
            draftPersistJob = draftsPersistenceScope.launch {
                persistDraftsIfCurrent(stateVersion)
            }
        }
    }

    suspend fun replaceIntervals(
        target: BiliVideoSkipTarget,
        intervals: Iterable<BiliVideoSkipInterval>,
        durationMs: Long = 0L
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedTarget = requireNotNull(target.normalizedOrNull()) {
            "Bili video skip target must contain a BVID and CID"
        }
        val normalizedIntervals = normalizeBiliVideoSkipIntervals(intervals, durationMs)
        mutex.withLock {
            val previous = _rules.value.firstOrNull { it.target == normalizedTarget }
            val deleted = normalizedIntervals.isEmpty()
            val hasSameSnapshot =
                previous != null && previous.isDeleted == deleted &&
                    previous.intervals == normalizedIntervals
            if (hasSameSnapshot) {
                return@withLock false
            }
            if (previous == null && deleted) {
                return@withLock false
            }

            val updatedRule = BiliVideoSkipRule(
                target = normalizedTarget,
                intervals = normalizedIntervals,
                modifiedAt = nextModifiedAt(previous),
                isDeleted = deleted
            )
            val updatedRules = normalizeBiliVideoSkipRules(
                _rules.value.filterNot { it.target == normalizedTarget } + updatedRule
            )
            store.replaceRules(updatedRules)
            _rules.value = updatedRules
            onRulesChanged()
            true
        }
    }

    suspend fun replaceFromSyncIfUnchanged(
        rules: Iterable<BiliVideoSkipRule>,
        expectedMutationVersion: Long
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (readSyncMutationVersion() != expectedMutationVersion) {
                return@withLock false
            }
            val normalizedRules = normalizeBiliVideoSkipRules(rules)
            if (_rules.value == normalizedRules) return@withLock true
            store.replaceRules(normalizedRules)
            _rules.value = normalizedRules
            true
        }
    }

    private suspend fun loadInitialSnapshot(): BiliVideoSkipSnapshot {
        val roomPrimary = runCatching { store.isPrimary() }
            .onFailure { error ->
                NPLogger.w(TAG, "Failed to read Bili skip Room marker", error)
            }
            .getOrDefault(false)
        if (roomPrimary) {
            scheduleLegacyCleanup("bili-skip-room-load")
            return store.readIfPrimary()
                ?: BiliVideoSkipSnapshot(emptyList(), emptyList())
        }

        val legacyRules = readLegacyRulesOrNull()
        val legacyDrafts = readLegacyDraftsOrNull()
        val shouldImportLegacyFiles = legacyRules != null &&
            legacyDrafts != null &&
            (rulesFile.exists() || draftsFile.exists())
        if (shouldImportLegacyFiles) {
            runCatching {
                store.replaceAll(legacyRules, legacyDrafts)
                scheduleLegacyCleanup("bili-skip-import")
            }.onFailure { error ->
                NPLogger.w(TAG, "Failed to import Bili skip JSON into Room", error)
            }
        }
        return BiliVideoSkipSnapshot(
            rules = legacyRules.orEmpty(),
            drafts = legacyDrafts.orEmpty()
        )
    }

    private fun readLegacyRulesOrNull(): List<BiliVideoSkipRule>? {
        if (!rulesFile.exists()) return emptyList()
        return runCatching {
            val document = json.decodeFromString<BiliVideoSkipRulesDocument>(
                rulesFile.readText(Charsets.UTF_8)
            )
            normalizeBiliVideoSkipRules(document.rules)
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read Bili video skip rules", error)
        }.getOrNull()
    }

    private fun readLegacyDraftsOrNull(): List<BiliVideoSkipDraft>? {
        if (!draftsFile.exists()) return emptyList()
        return runCatching {
            val document = json.decodeFromString<BiliVideoSkipDraftsDocument>(
                draftsFile.readText(Charsets.UTF_8)
            )
            normalizeBiliVideoSkipDrafts(document.drafts)
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read Bili video skip drafts", error)
        }.getOrNull()
    }

    private suspend fun persistDraftsIfCurrent(expectedStateVersion: Long) {
        runCatchingNonCancellation {
            draftsMutex.withLock {
                val snapshot = synchronized(draftsStateLock) {
                    _drafts.value.takeIf { draftsStateVersion == expectedStateVersion }
                } ?: return@withLock
                store.replaceDrafts(snapshot)
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to persist Bili video skip drafts", error)
        }
    }

    private fun nextModifiedAt(previous: BiliVideoSkipRule?): Long {
        return nextModifiedAt(previous?.modifiedAt ?: 0L)
    }

    private fun nextModifiedAt(previousModifiedAt: Long): Long {
        val nextAfterPrevious = if (previousModifiedAt < Long.MAX_VALUE) {
            previousModifiedAt + 1L
        } else {
            Long.MAX_VALUE
        }
        return maxOf(System.currentTimeMillis(), nextAfterPrevious)
    }

    companion object {
        const val TAG = "BiliVideoSkipRepo"
        const val RULES_FILE_NAME = "bili_video_skip_rules.json"
        const val DRAFTS_FILE_NAME = "bili_video_skip_drafts.json"

    }
}
