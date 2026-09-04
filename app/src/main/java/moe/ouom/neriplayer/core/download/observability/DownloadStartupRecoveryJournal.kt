package moe.ouom.neriplayer.core.download.observability

import android.content.Context
import android.content.SharedPreferences
import moe.ouom.neriplayer.core.logging.NPLogger

/**
 * 记录跨进程的启动边界，仅用于诊断和恢复现场，不替代 Room 下载队列
 */
internal data class DownloadStartupRecoveryJournalRecord(
    val generation: Long,
    val phase: DownloadStartupDeadlineTracker.Phase,
    val recordedAtWallMs: Long,
    val t0ToT1Ns: Long?,
    val t1ToT2Ns: Long?,
    val t0ToT2Ns: Long?,
    val withinDeadline: Boolean?,
    val blockedReason: String?
)

internal object DownloadStartupRecoveryJournalCodec {
    private const val GENERATION_KEY = "generation"
    private const val PHASE_KEY = "phase"
    private const val RECORDED_AT_WALL_MS_KEY = "recorded_at_wall_ms"
    private const val T0_TO_T1_NS_KEY = "t0_to_t1_ns"
    private const val T1_TO_T2_NS_KEY = "t1_to_t2_ns"
    private const val T0_TO_T2_NS_KEY = "t0_to_t2_ns"
    private const val WITHIN_DEADLINE_KEY = "within_deadline"
    private const val BLOCKED_REASON_KEY = "blocked_reason"
    private const val MAX_REASON_LENGTH = 256

    fun fromSnapshot(
        snapshot: DownloadStartupDeadlineTracker.Snapshot,
        recordedAtWallMs: Long
    ): DownloadStartupRecoveryJournalRecord {
        return DownloadStartupRecoveryJournalRecord(
            generation = snapshot.generation,
            phase = snapshot.phase,
            recordedAtWallMs = recordedAtWallMs.coerceAtLeast(0L),
            t0ToT1Ns = snapshot.t0ToT1Ns,
            t1ToT2Ns = snapshot.t1ToT2Ns,
            t0ToT2Ns = snapshot.t0ToT2Ns,
            withinDeadline = snapshot.withinDeadline,
            blockedReason = snapshot.blockedReason
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.take(MAX_REASON_LENGTH)
        )
    }

    fun encode(record: DownloadStartupRecoveryJournalRecord): Map<String, Any?> {
        return buildMap {
            put(GENERATION_KEY, record.generation)
            put(PHASE_KEY, record.phase.name)
            put(RECORDED_AT_WALL_MS_KEY, record.recordedAtWallMs)
            record.t0ToT1Ns?.let { put(T0_TO_T1_NS_KEY, it) }
            record.t1ToT2Ns?.let { put(T1_TO_T2_NS_KEY, it) }
            record.t0ToT2Ns?.let { put(T0_TO_T2_NS_KEY, it) }
            record.withinDeadline?.let { put(WITHIN_DEADLINE_KEY, it) }
            record.blockedReason?.let { put(BLOCKED_REASON_KEY, it) }
        }
    }

    fun decode(values: Map<String, *>): DownloadStartupRecoveryJournalRecord? {
        val generation = values.longValue(GENERATION_KEY) ?: return null
        val phaseName = values[PHASE_KEY] as? String ?: return null
        val phase = runCatching {
            DownloadStartupDeadlineTracker.Phase.valueOf(phaseName)
        }.getOrNull() ?: return null
        val recordedAtWallMs = values.longValue(RECORDED_AT_WALL_MS_KEY) ?: return null
        if (generation <= 0L || recordedAtWallMs < 0L) return null

        val durations = listOf(
            T0_TO_T1_NS_KEY,
            T1_TO_T2_NS_KEY,
            T0_TO_T2_NS_KEY
        ).map { key -> values.longValue(key) }
        if (durations.any { value -> value != null && value < 0L }) return null

        val withinDeadline = when (val value = values[WITHIN_DEADLINE_KEY]) {
            null -> null
            is Boolean -> value
            else -> return null
        }
        val blockedReason = when (val value = values[BLOCKED_REASON_KEY]) {
            null -> null
            is String -> value.trim().takeIf(String::isNotBlank)?.take(MAX_REASON_LENGTH)
            else -> return null
        }
        return DownloadStartupRecoveryJournalRecord(
            generation = generation,
            phase = phase,
            recordedAtWallMs = recordedAtWallMs,
            t0ToT1Ns = durations[0],
            t1ToT2Ns = durations[1],
            t0ToT2Ns = durations[2],
            withinDeadline = withinDeadline,
            blockedReason = blockedReason
        )
    }

    private fun Map<String, *>.longValue(key: String): Long? {
        return when (val value = this[key]) {
            null -> null
            is Number -> value.toLong()
            else -> null
        }
    }
}

/**
 * SharedPreferences 只保留最近一次边界，写入采用 apply，不阻塞首屏和传输线程
 */
internal object DownloadStartupRecoveryJournal {
    private const val TAG = "DownloadStartupJournal"
    private const val PREFERENCES_NAME = "download_startup_recovery_journal"
    private val writeLock = Any()
    private var lastPersistedGeneration = 0L
    private var lastPersistedPhaseRank = -1

    fun install(context: Context) {
        val appContext = context.applicationContext
        synchronized(writeLock) {
            read(appContext)?.let { record ->
                lastPersistedGeneration = record.generation
                lastPersistedPhaseRank = phaseRank(record.phase)
            }
            DownloadStartupTrace.installObserver { snapshot ->
                persist(appContext, snapshot)
            }
        }
    }

    fun read(context: Context): DownloadStartupRecoveryJournalRecord? {
        val preferences = preferencesOrNull(context) ?: return null
        return runCatching {
            DownloadStartupRecoveryJournalCodec.decode(preferences.all)
        }.onFailure { error ->
            NPLogger.d(TAG, "读取启动恢复诊断凭据失败: ${error.message}")
        }.getOrNull()
    }

    private fun persist(
        context: Context,
        snapshot: DownloadStartupDeadlineTracker.Snapshot
    ) {
        val preferences = preferencesOrNull(context) ?: return
        val record = DownloadStartupRecoveryJournalCodec.fromSnapshot(
            snapshot = snapshot,
            recordedAtWallMs = System.currentTimeMillis()
        )
        synchronized(writeLock) {
            val currentPhaseRank = phaseRank(record.phase)
            if (
                record.generation < lastPersistedGeneration ||
                record.generation == lastPersistedGeneration &&
                    currentPhaseRank <= lastPersistedPhaseRank
            ) {
                return
            }
            runCatching {
                val editor = preferences.edit().clear()
                DownloadStartupRecoveryJournalCodec.encode(record).forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Long -> editor.putLong(key, value)
                        is String -> editor.putString(key, value)
                        null -> Unit
                    }
                }
                editor.apply()
                lastPersistedGeneration = record.generation
                lastPersistedPhaseRank = currentPhaseRank
            }.onFailure { error ->
                NPLogger.d(TAG, "写入启动恢复诊断凭据失败: ${error.message}")
            }
        }
    }

    private fun phaseRank(phase: DownloadStartupDeadlineTracker.Phase): Int {
        return when (phase) {
            DownloadStartupDeadlineTracker.Phase.IDLE -> 0
            DownloadStartupDeadlineTracker.Phase.INTENT_RECORDED -> 1
            DownloadStartupDeadlineTracker.Phase.QUEUE_READY -> 2
            DownloadStartupDeadlineTracker.Phase.TRANSFER_STARTED -> 3
            DownloadStartupDeadlineTracker.Phase.BLOCKED -> 4
        }
    }

    private fun preferencesOrNull(context: Context): SharedPreferences? {
        return runCatching {
            context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
            )
        }.onFailure { error ->
            NPLogger.d(TAG, "打开启动恢复诊断凭据失败: ${error.message}")
        }.getOrNull()
    }
}
