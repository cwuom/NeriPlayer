package moe.ouom.neriplayer.core.download.execution

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicLong
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore

/** 清空任务栅栏持有的 operation 和歌曲身份快照 */
internal data class DownloadClearOwnership(
    val operationIds: Set<String> = emptySet(),
    val stableKeys: Set<String> = emptySet()
) {
    init {
        require(operationIds.none { it.isBlank() }) {
            "operationIds must not contain blank values"
        }
        require(stableKeys.none { it.isBlank() }) {
            "stableKeys must not contain blank values"
        }
    }
}

internal interface DownloadClearFenceStore {
    fun isActive(context: Context): Boolean

    fun activate(context: Context): Boolean

    fun clear(context: Context): Boolean
}

/** 清空下载任务与删除整个下载库使用不同的进程死亡恢复策略 */
internal enum class DownloadClearPurpose {
    TASK_PROGRESS,
    FULL_LIBRARY_DELETE
}

internal enum class DownloadClearFenceReleaseResult {
    RELEASED,
    SUPERSEDED,
    FAILED
}

internal object PersistentDownloadClearFenceStore : DownloadClearFenceStore {
    private val clearRequestEpoch = AtomicLong(0L)
    private val clearedRequestEpoch = AtomicLong(0L)
    @Volatile
    private var persistedEpoch = 0L
    @Volatile
    private var persistedEpochLoaded = false
    @Volatile
    private var requestedPurpose = DownloadClearPurpose.TASK_PROGRESS
    @Volatile
    private var requestedOwnership: DownloadClearOwnership? = null
    private val schedulingLock = Any()

    internal fun beginClear(
        purpose: DownloadClearPurpose = DownloadClearPurpose.TASK_PROGRESS,
        ownership: DownloadClearOwnership? = null
    ): Long {
        return synchronized(schedulingLock) {
            val currentEpoch = clearRequestEpoch.get()
            if (currentEpoch > clearedRequestEpoch.get()) {
                // 同一进程内已有清空请求时复用它，避免旧恢复任务被新 epoch
                // 覆盖后误清理另一轮的进度
                if (purpose == DownloadClearPurpose.FULL_LIBRARY_DELETE) {
                    requestedPurpose = purpose
                }
                if (ownership != null && requestedPurpose == DownloadClearPurpose.TASK_PROGRESS) {
                    requestedOwnership = ownership.normalized()
                }
                return@synchronized currentEpoch
            }
            requestedPurpose = purpose
            requestedOwnership = ownership?.normalized()
            clearRequestEpoch.incrementAndGet()
        }
    }

    /** 激活持久栅栏失败且确认未写入时，回收内存请求避免永久阻塞下载 */
    internal fun abandonUnpersistedRequestIfCurrent(
        context: Context,
        expectedEpoch: Long
    ): Boolean {
        return synchronized(schedulingLock) {
            if (clearRequestEpoch.get() != expectedEpoch ||
                clearedRequestEpoch.get() >= expectedEpoch ||
                isPersistedFenceActive(context) ||
                PersistentDownloadedSongDeleteIntentStore.hasPending(context)
            ) {
                return@synchronized false
            }
            clearedRequestEpoch.accumulateAndGet(expectedEpoch) { current, candidate ->
                maxOf(current, candidate)
            }
            requestedOwnership = null
            true
        }
    }

    internal fun activePurpose(context: Context): DownloadClearPurpose {
        return synchronized(schedulingLock) {
            try {
                val persistedPurpose = preferencesOrNull(context)?.getString(PURPOSE_KEY, null)
                    ?.let { value ->
                        runCatching { DownloadClearPurpose.valueOf(value) }.getOrNull()
                    }
                persistedPurpose
                    ?: PersistentDownloadedSongDeleteIntentStore.hasPending(context)
                        .takeIf { it }
                        ?.let { DownloadClearPurpose.FULL_LIBRARY_DELETE }
                    ?: DownloadClearPurpose.TASK_PROGRESS
            } catch (_: Throwable) {
                // 读取失败时保守保留 catalog，避免把已下载歌曲误判为已删除
                DownloadClearPurpose.TASK_PROGRESS
            }
        }
    }

    override fun isActive(context: Context): Boolean {
        return synchronized(schedulingLock) {
            hydratePersistedEpochLocked(context)
            isClearRequested() ||
                isPersistedFenceActive(context) ||
                PersistentDownloadedSongDeleteIntentStore.hasPending(context)
        }
    }

    /**
     * 返回 SharedPreferences 中真实存在的清空栅栏
     *
     * 全库删除会先落盘删除意图，再建立任务清空栅栏。调用方需要区分
     * 这两个持久状态，否则刚写入意图就会被误判为已有清空流程并等待自己
     */
    internal fun hasPersistedFence(context: Context): Boolean {
        return synchronized(schedulingLock) {
            hydratePersistedEpochLocked(context)
            isPersistedFenceActive(context)
        }
    }

    /** 只判断任务清空自身的内存/持久栅栏，不把全库删除 intent 混入 UI 生命周期 */
    internal fun isTaskClearActive(context: Context): Boolean {
        return synchronized(schedulingLock) {
            hydratePersistedEpochLocked(context)
            isClearRequested() || isPersistedFenceActive(context)
        }
    }

    /** 返回本轮持久清空开始时间，用于排除清空后的新 generation */
    internal fun requestedAtMs(context: Context): Long? {
        return synchronized(schedulingLock) {
            hydratePersistedEpochLocked(context)
            runCatching {
                preferencesOrNull(context)
                    ?.getLong(REQUESTED_AT_MS_KEY, 0L)
                    ?.takeIf { it > 0L }
            }.getOrNull()
        }
    }

    /** 返回单调清空代次，票据不能因持久栅栏释放而回到旧代次 */
    internal fun currentEpoch(context: Context? = null): Long = synchronized(schedulingLock) {
        context?.let(::hydratePersistedEpochLocked)
        maxOf(clearRequestEpoch.get(), persistedEpoch)
    }

    /** 返回 owner 快照是否已经完整落盘，未完成时继续阻断所有新发布 */
    internal fun isOwnershipCaptureComplete(context: Context): Boolean {
        return synchronized(schedulingLock) {
            hydratePersistedEpochLocked(context)
            isFenceActiveLocked(context) && isOwnershipCaptureCompleteLocked(context)
        }
    }

    /**
     * 判断一个具体 operation 是否被当前清空栅栏拥有
     *
     * TASK_PROGRESS 只阻断清空开始时捕获的 operation 或 stableKey，
     * 但没有持久 owner 快照的历史栅栏必须保守地阻断全部调度
     */
    internal fun isBlocked(
        context: Context,
        stableKey: String? = null,
        operationId: String? = null
    ): Boolean {
        return synchronized(schedulingLock) {
            hydratePersistedEpochLocked(context)
            if (!isFenceActiveLocked(context)) {
                return@synchronized false
            }
            val purpose = activePurposeLocked(context)
            if (purpose != DownloadClearPurpose.TASK_PROGRESS) {
                return@synchronized true
            }
            val ownership = readOwnershipLocked(context) ?: return@synchronized true
            val normalizedStableKey = stableKey.normalizeIdentity()
            val normalizedOperationId = operationId.normalizeIdentity()
            if (normalizedStableKey == null && normalizedOperationId == null) {
                // 没有身份就无法证明请求属于新代次，保守地阻断旧调用方
                return@synchronized true
            }
            if (!isOwnershipCaptureCompleteLocked(context)) {
                // 初始 owner 已经随栅栏落盘时，只阻断已知旧 owner。
                // 不同 stableKey 的新 generation 可以并行启动，后台捕获
                // 会继续把清空开始前的迟到 operation 合并进 owner 快照
                return@synchronized (
                    normalizedOperationId != null &&
                        normalizedOperationId in ownership.operationIds
                ) || (
                    normalizedStableKey != null &&
                        normalizedStableKey in ownership.stableKeys
                )
            }
            normalizedOperationId != null &&
                normalizedOperationId in ownership.operationIds ||
                normalizedStableKey != null &&
                normalizedStableKey in ownership.stableKeys
        }
    }

    internal fun <T> withSchedulingPermit(
        context: Context,
        onFenceActive: () -> T,
        stableKey: String? = null,
        operationId: String? = null,
        schedule: () -> T
    ): T {
        // 只在内存临界区内做一次身份判断，绝不把 Room、宿主或网络工作
        // 放进 schedulingLock。调用方须在实际写入点再次做 operation CAS 复核
        val blocked = isBlocked(
            context = context,
            stableKey = stableKey,
            operationId = operationId
        )
        return if (blocked) {
            onFenceActive()
        } else {
            schedule()
        }
    }

    override fun activate(context: Context): Boolean {
        return activate(context, requestedOwnership)
    }

    /** 激活带有清理 owner 快照的持久栅栏 */
    internal fun activate(
        context: Context,
        ownership: DownloadClearOwnership?
    ): Boolean {
        synchronized(schedulingLock) {
            hydratePersistedEpochLocked(context)
        }
        ensureClearRequested()
        return synchronized(schedulingLock) {
            try {
                val preferences = preferencesOrNull(context)
                    ?: return@synchronized false
                val persistedFenceWasActive = preferences.getBoolean(ACTIVE_KEY, false)
                val persistedPurpose = preferences.getString(PURPOSE_KEY, null)
                    ?.let { value ->
                        runCatching { DownloadClearPurpose.valueOf(value) }.getOrNull()
                    }
                if (persistedFenceWasActive) {
                    // 已落盘的 owner 是本轮清空边界。恢复重试不能用内存请求
                    // 覆盖它，否则会重置 requestedAt 或重新进入捕获阶段
                    val shouldUpgradeToFullLibraryDelete =
                        requestedPurpose == DownloadClearPurpose.FULL_LIBRARY_DELETE &&
                            persistedPurpose != DownloadClearPurpose.FULL_LIBRARY_DELETE
                    if (shouldUpgradeToFullLibraryDelete) {
                        val upgraded = commitFenceEdit(preferences) {
                            putString(
                                PURPOSE_KEY,
                                DownloadClearPurpose.FULL_LIBRARY_DELETE.name
                            )
                        }
                        if (upgraded) {
                            requestedPurpose = DownloadClearPurpose.FULL_LIBRARY_DELETE
                        }
                        return@synchronized upgraded
                    }
                    requestedPurpose = persistedPurpose ?: requestedPurpose
                    return@synchronized true
                }
                ownership?.let { requestedOwnership = it.normalized() }
                val effectivePurpose = requestedPurpose
                val effectiveOwnership = requestedOwnership
                val effectiveRequestedAtMs = System.currentTimeMillis()
                requestedPurpose = effectivePurpose
                val persisted = commitFenceEdit(preferences) {
                    putBoolean(ACTIVE_KEY, true)
                    putLong(EPOCH_KEY, clearRequestEpoch.get())
                    putLong(REQUESTED_AT_MS_KEY, effectiveRequestedAtMs)
                    putString(PURPOSE_KEY, effectivePurpose.name)
                    if (effectivePurpose == DownloadClearPurpose.TASK_PROGRESS &&
                        effectiveOwnership != null
                    ) {
                        putStringSet(
                            OWNER_OPERATION_IDS_KEY,
                            effectiveOwnership.operationIds
                        )
                        putStringSet(
                            OWNER_STABLE_KEYS_KEY,
                            effectiveOwnership.stableKeys
                        )
                        putBoolean(OWNER_CAPTURE_COMPLETE_KEY, false)
                    } else {
                        remove(OWNER_OPERATION_IDS_KEY)
                        remove(OWNER_STABLE_KEYS_KEY)
                        putBoolean(OWNER_CAPTURE_COMPLETE_KEY, false)
                    }
                }
                if (persisted) {
                    persistedEpoch = maxOf(persistedEpoch, clearRequestEpoch.get())
                    persistedEpochLoaded = true
                }
                persisted
            } catch (_: Throwable) {
                false
            }
        }
    }

    /** 在栅栏已落盘后补齐清空开始时捕获的 owner 集合 */
    internal fun setOwnership(
        context: Context,
        expectedEpoch: Long,
        ownership: DownloadClearOwnership
    ): Boolean {
        val normalizedOwnership = ownership.normalized()
        return synchronized(schedulingLock) {
            hydratePersistedEpochLocked(context)
            if (
                clearRequestEpoch.get() != expectedEpoch ||
                    clearedRequestEpoch.get() >= expectedEpoch ||
                    activePurposeLocked(context) != DownloadClearPurpose.TASK_PROGRESS ||
                    !isPersistedFenceActive(context)
            ) {
                return@synchronized false
            }
            val preferences = try {
                preferencesOrNull(context)
            } catch (_: Throwable) {
                null
            } ?: return@synchronized false
            val hasOperationOwners = preferences.contains(OWNER_OPERATION_IDS_KEY)
            val hasStableKeyOwners = preferences.contains(OWNER_STABLE_KEYS_KEY)
            if (hasOperationOwners != hasStableKeyOwners) {
                // 半写入的 owner 记录无法安全合并，保持 capture 未完成
                return@synchronized false
            }
            val persistedOwnership = readOwnershipLocked(context)
            if (hasOperationOwners && persistedOwnership == null) {
                // 已存在的 owner 无法读取时不能用新快照覆盖未知旧 owner
                return@synchronized false
            }
            val effectiveOwnership = DownloadClearOwnership(
                operationIds = persistedOwnership?.operationIds.orEmpty() +
                    normalizedOwnership.operationIds,
                stableKeys = persistedOwnership?.stableKeys.orEmpty() +
                    normalizedOwnership.stableKeys
            ).normalized()
            try {
                commitFenceEdit(preferences) {
                    putStringSet(
                        OWNER_OPERATION_IDS_KEY,
                        effectiveOwnership.operationIds
                    )
                    putStringSet(
                        OWNER_STABLE_KEYS_KEY,
                        effectiveOwnership.stableKeys
                    )
                    putBoolean(OWNER_CAPTURE_COMPLETE_KEY, true)
                }
            } catch (_: Throwable) {
                false
            }.also { persisted ->
                if (persisted) {
                    requestedOwnership = effectiveOwnership
                    requestedPurpose = DownloadClearPurpose.TASK_PROGRESS
                }
            }
        }
    }

    /** 读取当前任务清空 owner，返回 null 表示旧 fence 没有 owner 凭据 */
    internal fun ownership(context: Context): DownloadClearOwnership? {
        return synchronized(schedulingLock) {
            hydratePersistedEpochLocked(context)
            readOwnershipLocked(context)
        }
    }

    override fun clear(context: Context): Boolean {
        return clearIfCurrent(
            context = context,
            expectedEpoch = clearRequestEpoch.get()
        ) == DownloadClearFenceReleaseResult.RELEASED
    }

    internal fun clearIfCurrent(
        context: Context,
        expectedEpoch: Long
    ): DownloadClearFenceReleaseResult {
        return try {
            synchronized(schedulingLock) {
                hydratePersistedEpochLocked(context)
                if (clearRequestEpoch.get() != expectedEpoch) {
                    return@synchronized DownloadClearFenceReleaseResult.SUPERSEDED
                }
                if (
                    activePurposeLocked(context) == DownloadClearPurpose.TASK_PROGRESS &&
                        (isClearRequested() || isPersistedFenceActive(context)) &&
                        !isOwnershipCaptureCompleteLocked(context)
                ) {
                    // owner 捕获未确认前不能释放持久栅栏，否则旧 operation 可能
                    // 在下一次调度中逃过清空
                    return@synchronized DownloadClearFenceReleaseResult.FAILED
                }
                val cleared = preferencesOrNull(context)?.let { preferences ->
                    commitFenceEdit(preferences) {
                        remove(ACTIVE_KEY)
                        remove(REQUESTED_AT_MS_KEY)
                        remove(PURPOSE_KEY)
                        remove(OWNER_OPERATION_IDS_KEY)
                        remove(OWNER_STABLE_KEYS_KEY)
                        remove(OWNER_CAPTURE_COMPLETE_KEY)
                    }
                } == true
                if (cleared) {
                    clearedRequestEpoch.accumulateAndGet(expectedEpoch) { current, candidate ->
                        maxOf(current, candidate)
                    }
                    requestedOwnership = null
                    DownloadClearFenceReleaseResult.RELEASED
                } else {
                    DownloadClearFenceReleaseResult.FAILED
                }
            }
        } catch (_: Throwable) {
            DownloadClearFenceReleaseResult.FAILED
        }
    }

    private fun isClearRequested(): Boolean {
        return clearRequestEpoch.get() > clearedRequestEpoch.get()
    }

    private fun isFenceActiveLocked(context: Context): Boolean {
        return isClearRequested() ||
            isPersistedFenceActive(context) ||
            PersistentDownloadedSongDeleteIntentStore.hasPending(context)
    }

    private fun isOwnershipCaptureCompleteLocked(context: Context): Boolean {
        return runCatching {
            val preferences = preferencesOrNull(context) ?: return@runCatching false
            if (!preferences.getBoolean(OWNER_CAPTURE_COMPLETE_KEY, false)) {
                return@runCatching false
            }
            preferences.contains(OWNER_OPERATION_IDS_KEY) &&
                preferences.contains(OWNER_STABLE_KEYS_KEY) &&
                readOwnershipLocked(context) != null
        }.getOrDefault(false)
    }

    private fun activePurposeLocked(context: Context): DownloadClearPurpose {
        val persistedPurpose = runCatching {
            preferencesOrNull(context)?.getString(PURPOSE_KEY, null)
                ?.let { value -> runCatching { DownloadClearPurpose.valueOf(value) }.getOrNull() }
        }.getOrNull()
        return persistedPurpose
            ?: if (PersistentDownloadedSongDeleteIntentStore.hasPending(context)) {
                DownloadClearPurpose.FULL_LIBRARY_DELETE
            } else {
                requestedPurpose
            }
    }

    private fun readOwnershipLocked(context: Context): DownloadClearOwnership? {
        return try {
            val preferences = preferencesOrNull(context) ?: return null
            val hasOperationOwners = preferences.contains(OWNER_OPERATION_IDS_KEY)
            val hasStableKeyOwners = preferences.contains(OWNER_STABLE_KEYS_KEY)
            if (!hasOperationOwners && !hasStableKeyOwners) {
                return if (isClearRequested()) requestedOwnership?.normalized() else null
            }
            if (!hasOperationOwners || !hasStableKeyOwners) {
                // owner 快照必须成对存在，避免半写入的旧凭据误放行调度
                return null
            }
            DownloadClearOwnership(
                operationIds = preferences.getStringSet(OWNER_OPERATION_IDS_KEY, emptySet())
                    .orEmpty()
                    .mapNotNull(String::normalizeIdentity)
                    .toSet(),
                stableKeys = preferences.getStringSet(OWNER_STABLE_KEYS_KEY, emptySet())
                    .orEmpty()
                    .mapNotNull(String::normalizeIdentity)
                    .toSet()
            )
        } catch (_: Throwable) {
            // owner 读取失败时不能把未知状态当成空 owner
            null
        }
    }

    private fun ensureClearRequested() {
        while (true) {
            val requested = clearRequestEpoch.get()
            if (requested > clearedRequestEpoch.get()) {
                return
            }
            if (clearRequestEpoch.compareAndSet(requested, requested + 1L)) {
                return
            }
        }
    }

    private fun isPersistedFenceActive(context: Context): Boolean {
        return try {
            preferencesOrNull(context)?.getBoolean(ACTIVE_KEY, false) ?: false
        } catch (_: Throwable) {
            // 读不到栅栏时先停住下载，等存储恢复后再继续
            true
        }
    }

    /**
     * epoch 只需在本进程首次接触持久 fence 时读取一次。之后所有写入都由本对象
     * 完成并同步缓存，避免每个调度 ticket 都触发 SharedPreferences I/O
     */
    private fun hydratePersistedEpochLocked(context: Context) {
        if (persistedEpochLoaded) return
        val preferences = runCatching { preferencesOrNull(context) }.getOrNull()
        val loadedEpoch = runCatching {
            preferences?.getLong(EPOCH_KEY, 0L) ?: 0L
        }.getOrDefault(0L)
        persistedEpoch = maxOf(persistedEpoch, loadedEpoch)
        clearRequestEpoch.accumulateAndGet(persistedEpoch) { current, candidate ->
            maxOf(current, candidate)
        }
        val persistedFenceActive = runCatching {
            preferences?.getBoolean(ACTIVE_KEY, false) ?: false
        }.getOrDefault(true)
        val deleteIntentPending = runCatching {
            PersistentDownloadedSongDeleteIntentStore.hasPending(context)
        }.getOrDefault(true)
        if (!persistedFenceActive && !deleteIntentPending) {
            // 持久 epoch 已经没有待恢复凭据时，同步推进已清除代次，
            // 避免进程重启后把历史完成的清空误判为仍在进行
            clearedRequestEpoch.accumulateAndGet(persistedEpoch) {
                current, candidate -> maxOf(current, candidate)
            }
        }
        persistedEpochLoaded = true
    }

    @SuppressLint("UseKtx")
    private fun commitFenceEdit(
        preferences: SharedPreferences,
        mutation: SharedPreferences.Editor.() -> Unit
    ): Boolean {
        // commit 返回成功才算栅栏已经写入，可供崩溃后恢复
        val editor = preferences.edit()
        editor.mutation()
        return editor.commit()
    }

    private fun preferencesOrNull(context: Context): SharedPreferences? {
        return context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
    }

    private const val PREFERENCES_NAME = "download_clear_fence_v1"
    private const val ACTIVE_KEY = "active"
    private const val EPOCH_KEY = "epoch"
    private const val REQUESTED_AT_MS_KEY = "requested_at_ms"
    private const val PURPOSE_KEY = "purpose"
    private const val OWNER_OPERATION_IDS_KEY = "owner_operation_ids"
    private const val OWNER_STABLE_KEYS_KEY = "owner_stable_keys"
    private const val OWNER_CAPTURE_COMPLETE_KEY = "owner_capture_complete"
}

private fun DownloadClearOwnership.normalized(): DownloadClearOwnership {
    return DownloadClearOwnership(
        operationIds = operationIds.mapNotNull(String::normalizeIdentity).toSet(),
        stableKeys = stableKeys.mapNotNull(String::normalizeIdentity).toSet()
    )
}

private fun String?.normalizeIdentity(): String? {
    return this?.trim()?.takeIf(String::isNotBlank)
}
