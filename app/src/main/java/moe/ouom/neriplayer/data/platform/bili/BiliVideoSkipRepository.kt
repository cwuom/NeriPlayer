package moe.ouom.neriplayer.data.platform.bili

import android.content.Context
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.BiliVideoSkipRoomSnapshot
import moe.ouom.neriplayer.data.local.database.store.BiliVideoSkipRoomStore
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import java.io.File

const val MAX_BILI_VIDEO_SKIP_INTERVALS = 100
const val MAX_BILI_VIDEO_SKIP_RULES = 2_000
private const val MAX_BILI_VIDEO_SKIP_DRAFT_TEXT_LENGTH = 128

@Serializable
data class BiliVideoSkipTarget(
    val bvid: String,
    val cid: Long
) {
    internal fun normalizedOrNull(): BiliVideoSkipTarget? {
        val normalizedBvid = bvid.trim()
        return if (normalizedBvid.isEmpty() || cid <= 0L) {
            null
        } else {
            copy(bvid = normalizedBvid)
        }
    }

    internal fun stableKey(): String = "$bvid|$cid"
}

@Serializable
data class BiliVideoSkipInterval(
    val startMs: Long,
    val endMs: Long
)

@Serializable
data class BiliVideoSkipRule(
    val target: BiliVideoSkipTarget,
    val intervals: List<BiliVideoSkipInterval> = emptyList(),
    val modifiedAt: Long = 0L,
    val isDeleted: Boolean = false
)

@Serializable
data class BiliVideoSkipDraft(
    val target: BiliVideoSkipTarget,
    val startText: String = "",
    val endText: String = "",
    val modifiedAt: Long = 0L
)

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

internal fun normalizeBiliVideoSkipIntervals(
    intervals: Iterable<BiliVideoSkipInterval>,
    durationMs: Long = 0L
): List<BiliVideoSkipInterval> {
    val maxEndMs = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
    val sorted = intervals.asSequence()
        .map { interval ->
            BiliVideoSkipInterval(
                startMs = interval.startMs.coerceAtLeast(0L),
                endMs = interval.endMs.coerceAtMost(maxEndMs)
            )
        }
        .filter { interval -> interval.endMs > interval.startMs }
        .sortedWith(compareBy<BiliVideoSkipInterval> { it.startMs }.thenBy { it.endMs })
        .take(MAX_BILI_VIDEO_SKIP_INTERVALS)
        .toList()
    if (sorted.isEmpty()) return emptyList()

    val normalized = ArrayList<BiliVideoSkipInterval>(sorted.size)
    sorted.forEach { candidate ->
        val previous = normalized.lastOrNull()
        if (previous != null && candidate.startMs <= previous.endMs) {
            normalized[normalized.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, candidate.endMs))
        } else {
            normalized += candidate
        }
    }
    return normalized
}

internal fun normalizeBiliVideoSkipRules(
    rules: Iterable<BiliVideoSkipRule>
): List<BiliVideoSkipRule> {
    val normalizedByTarget = linkedMapOf<String, BiliVideoSkipRule>()
    rules.forEach { rule ->
        val target = rule.target.normalizedOrNull() ?: return@forEach
        val normalized = rule.copy(
            target = target,
            intervals = if (rule.isDeleted) {
                emptyList()
            } else {
                normalizeBiliVideoSkipIntervals(rule.intervals)
            },
            modifiedAt = rule.modifiedAt.coerceAtLeast(0L)
        )
        if (!normalized.isDeleted && normalized.intervals.isEmpty()) return@forEach

        val key = target.stableKey()
        val existing = normalizedByTarget[key]
        normalizedByTarget[key] = when {
            existing == null -> normalized
            existing.modifiedAt > normalized.modifiedAt -> existing
            normalized.modifiedAt > existing.modifiedAt -> normalized
            existing.isDeleted && !normalized.isDeleted -> normalized
            !existing.isDeleted && normalized.isDeleted -> existing
            existing.isDeleted -> existing
            else -> existing.copy(
                intervals = normalizeBiliVideoSkipIntervals(existing.intervals + normalized.intervals)
            )
        }
    }
    return normalizedByTarget.values
        .sortedWith(compareBy<BiliVideoSkipRule> { it.target.bvid }.thenBy { it.target.cid })
        .take(MAX_BILI_VIDEO_SKIP_RULES)
}

internal fun intervalsForBiliVideoSkipCid(
    rules: Iterable<BiliVideoSkipRule>,
    cid: Long
): List<BiliVideoSkipInterval> {
    if (cid <= 0L) return emptyList()
    val matchingRule = rules.asSequence()
        .filter { rule -> rule.target.cid == cid }
        .singleOrNull()
    return matchingRule
        ?.takeUnless { it.isDeleted }
        ?.intervals
        .orEmpty()
}

internal fun intervalsForBiliVideoSkipPlayback(
    rules: Iterable<BiliVideoSkipRule>,
    target: BiliVideoSkipTarget?,
    fallbackCid: Long?,
    fallbackBvid: String? = null
): List<BiliVideoSkipInterval> {
    val normalizedTarget = target?.normalizedOrNull()
    if (normalizedTarget != null) {
        val exactRule = rules.firstOrNull { rule -> rule.target == normalizedTarget }
        if (exactRule != null) {
            return if (exactRule.isDeleted) emptyList() else exactRule.intervals
        }
        return emptyList()
    }
    val cidIntervals = intervalsForBiliVideoSkipCid(rules = rules, cid = fallbackCid ?: 0L)
    if (cidIntervals.isNotEmpty() || fallbackCid != null) {
        return cidIntervals
    }
    val normalizedBvid = fallbackBvid?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val matchingRule = rules.asSequence()
        .filter { rule -> rule.target.bvid == normalizedBvid }
        .singleOrNull()
    return matchingRule
        ?.takeUnless { it.isDeleted }
        ?.intervals
        .orEmpty()
}

internal fun normalizeBiliVideoSkipDrafts(
    drafts: Iterable<BiliVideoSkipDraft>
): List<BiliVideoSkipDraft> {
    val normalizedByTarget = linkedMapOf<String, BiliVideoSkipDraft>()
    drafts.forEach { draft ->
        val target = draft.target.normalizedOrNull() ?: return@forEach
        val normalizedStartText = draft.startText.trim().take(MAX_BILI_VIDEO_SKIP_DRAFT_TEXT_LENGTH)
        val normalizedEndText = draft.endText.trim().take(MAX_BILI_VIDEO_SKIP_DRAFT_TEXT_LENGTH)
        if (normalizedStartText.isEmpty() && normalizedEndText.isEmpty()) return@forEach

        val normalized = draft.copy(
            target = target,
            startText = normalizedStartText,
            endText = normalizedEndText,
            modifiedAt = draft.modifiedAt.coerceAtLeast(0L)
        )
        val key = target.stableKey()
        val existing = normalizedByTarget[key]
        if (existing == null || normalized.modifiedAt >= existing.modifiedAt) {
            normalizedByTarget[key] = normalized
        }
    }
    return normalizedByTarget.values
        .sortedWith(compareBy<BiliVideoSkipDraft> { it.target.bvid }.thenBy { it.target.cid })
        .take(MAX_BILI_VIDEO_SKIP_RULES)
}

class BiliVideoSkipRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val storage = SecureTokenStorage(appContext)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()
    private val draftsMutex = Mutex()
    private val draftsStateLock = Any()
    private val draftsPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val roomStore = BiliVideoSkipRoomStore(
        NeriUserDataDatabase.getInstance(appContext)
    )
    private val rulesFile = File(appContext.filesDir, RULES_FILE_NAME)
    private val draftsFile = File(appContext.filesDir, DRAFTS_FILE_NAME)
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
            if (
                previous != null &&
                    previous.isDeleted == deleted &&
                    previous.intervals == normalizedIntervals
            ) {
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
            roomStore.replaceRules(updatedRules)
            _rules.value = updatedRules
            markMutationAndScheduleSync()
            true
        }
    }

    suspend fun replaceFromSyncIfUnchanged(
        rules: Iterable<BiliVideoSkipRule>,
        expectedMutationVersion: Long
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (storage.getSyncMutationVersion() != expectedMutationVersion) {
                return@withLock false
            }
            val normalizedRules = normalizeBiliVideoSkipRules(rules)
            if (_rules.value == normalizedRules) return@withLock true
            roomStore.replaceRules(normalizedRules)
            _rules.value = normalizedRules
            true
        }
    }

    private suspend fun loadInitialSnapshot(): BiliVideoSkipRoomSnapshot {
        val roomPrimary = runCatching { roomStore.isRoomPrimary() }
            .onFailure { error ->
                NPLogger.w(TAG, "Failed to read Bili skip Room marker", error)
            }
            .getOrDefault(false)
        if (roomPrimary) {
            return roomStore.readIfRoomPrimary()
                ?: BiliVideoSkipRoomSnapshot(emptyList(), emptyList())
        }

        val legacyRules = readLegacyRulesOrNull()
        val legacyDrafts = readLegacyDraftsOrNull()
        if (
            legacyRules != null &&
            legacyDrafts != null &&
            (rulesFile.exists() || draftsFile.exists())
        ) {
            runCatching {
                roomStore.replaceAll(legacyRules, legacyDrafts)
            }.onFailure { error ->
                NPLogger.w(TAG, "Failed to import Bili skip JSON into Room", error)
            }
        }
        return BiliVideoSkipRoomSnapshot(
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
        try {
            draftsMutex.withLock {
                val snapshot = synchronized(draftsStateLock) {
                    _drafts.value.takeIf { draftsStateVersion == expectedStateVersion }
                } ?: return@withLock
                roomStore.replaceDrafts(snapshot)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
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

    private fun markMutationAndScheduleSync() {
        storage.markSyncMutation()
        GitHubSyncWorker.scheduleDelayedSync(
            appContext,
            triggerByUserAction = false,
            markMutation = false
        )
        WebDavSyncWorker.scheduleDelayedSync(
            appContext,
            triggerByUserAction = false,
            markMutation = false
        )
    }

    companion object {
        const val TAG = "BiliVideoSkipRepo"
        const val RULES_FILE_NAME = "bili_video_skip_rules.json"
        const val DRAFTS_FILE_NAME = "bili_video_skip_drafts.json"

        @Volatile
        private var instance: BiliVideoSkipRepository? = null

        fun getInstance(context: Context): BiliVideoSkipRepository {
            return instance ?: synchronized(this) {
                instance ?: BiliVideoSkipRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
