package moe.ouom.neriplayer.core.download.storage.recovery

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class TerminalTemporaryWriteCleanupRootType(
    private val persistedValue: String
) {
    FILE("file"),
    TREE("tree");

    companion object {
        fun fromPersistedValue(value: String): TerminalTemporaryWriteCleanupRootType? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }

    fun asPersistedValue(): String = persistedValue
}

internal data class TerminalTemporaryWriteCleanupRoot(
    val type: TerminalTemporaryWriteCleanupRootType,
    val identity: String
)

internal data class TerminalTemporaryWriteCleanupTarget(
    val displayName: String,
    val temporaryWriteOwnerName: String? = null
)

internal data class TerminalTemporaryWriteCleanupJournalEntry(
    val root: TerminalTemporaryWriteCleanupRoot,
    val targets: List<TerminalTemporaryWriteCleanupTarget>,
    val generationId: String
) {
    val targetNames: List<String>
        get() = targets.map(TerminalTemporaryWriteCleanupTarget::displayName).distinct()
}

/**
 * 待提交音频改名期间保留持久所有权记录
 *
 * 准备记录不能直接参与临时文件清理，只有改名成功后才会转成终态记录
 */
internal data class TerminalTemporaryWriteCleanupFinalizationPreparation(
    val root: TerminalTemporaryWriteCleanupRoot,
    val pendingAudioName: String,
    val finalAudioName: String,
    val expectedOperationId: String?,
    val expectedFinalizationToken: String? = null,
    val targets: List<TerminalTemporaryWriteCleanupTarget>,
    val generationId: String
) {
    val targetNames: List<String>
        get() = targets.map(TerminalTemporaryWriteCleanupTarget::displayName).distinct()
}

internal sealed interface TerminalTemporaryWriteCleanupJournalSnapshot {
    data class Available(
        val entries: List<TerminalTemporaryWriteCleanupJournalEntry>
    ) : TerminalTemporaryWriteCleanupJournalSnapshot

    data class Unavailable(
        val reason: String
    ) : TerminalTemporaryWriteCleanupJournalSnapshot
}

internal sealed interface TerminalTemporaryWriteCleanupPreparationSnapshot {
    data class Available(
        val entries: List<TerminalTemporaryWriteCleanupFinalizationPreparation>
    ) : TerminalTemporaryWriteCleanupPreparationSnapshot

    data class Unavailable(
        val reason: String
    ) : TerminalTemporaryWriteCleanupPreparationSnapshot
}

internal interface TerminalTemporaryWriteCleanupJournalStore {
    fun read(): String?

    fun write(payload: String?): Boolean
}

internal class TerminalTemporaryWriteCleanupJournal(
    private val store: TerminalTemporaryWriteCleanupJournalStore
) {
    private val lock = Any()

    fun enqueue(
        root: TerminalTemporaryWriteCleanupRoot,
        targetNames: Collection<String>
    ): Boolean = enqueueTargets(
        root = root,
        targets = targetNames.map { targetName ->
            TerminalTemporaryWriteCleanupTarget(displayName = targetName)
        }
    )

    fun enqueueTargets(
        root: TerminalTemporaryWriteCleanupRoot,
        targets: Collection<TerminalTemporaryWriteCleanupTarget>
    ): Boolean = synchronized(lock) {
        val normalizedRoot = root.normalizedOrNull() ?: return@synchronized false
        val normalizedTargets = targets
            .mapNotNull { target -> target.normalizedOrNull() }
            .distinct()
            .sortedWith(terminalTemporaryWriteCleanupTargetComparator)
        if (normalizedTargets.isEmpty()) {
            return@synchronized true
        }
        val current = readStateLocked()
            ?: return@synchronized false
        val updated = current.copy(
            terminalEntries = mergeTerminalEntries(
                entries = current.terminalEntries,
                root = normalizedRoot,
                targets = normalizedTargets
            )
        )
        store.write(encodeOrNull(updated))
    }

    fun prepareFinalization(
        root: TerminalTemporaryWriteCleanupRoot,
        pendingAudioName: String,
        finalAudioName: String,
        expectedOperationId: String?,
        targetNames: Collection<String>,
        expectedFinalizationToken: String? = null
    ): TerminalTemporaryWriteCleanupFinalizationPreparation? {
        return prepareFinalizationTargets(
            root = root,
            pendingAudioName = pendingAudioName,
            finalAudioName = finalAudioName,
            expectedOperationId = expectedOperationId,
            targets = targetNames.map { targetName ->
                TerminalTemporaryWriteCleanupTarget(displayName = targetName)
            },
            expectedFinalizationToken = expectedFinalizationToken
        )
    }

    fun prepareFinalizationTargets(
        root: TerminalTemporaryWriteCleanupRoot,
        pendingAudioName: String,
        finalAudioName: String,
        expectedOperationId: String?,
        targets: Collection<TerminalTemporaryWriteCleanupTarget>,
        expectedFinalizationToken: String? = null
    ): TerminalTemporaryWriteCleanupFinalizationPreparation? = synchronized(lock) {
        val normalizedRoot = root.normalizedOrNull() ?: return@synchronized null
        val normalizedPendingAudioName = normalizeTargetName(pendingAudioName)
            ?: return@synchronized null
        val normalizedFinalAudioName = normalizeTargetName(finalAudioName)
            ?: return@synchronized null
        val normalizedTargets = targets
            .mapNotNull { target -> target.normalizedOrNull() }
            .distinct()
            .sortedWith(terminalTemporaryWriteCleanupTargetComparator)
        if (normalizedTargets.isEmpty()) {
            return@synchronized null
        }
        val current = readStateLocked()
            ?: return@synchronized null
        val preparation = TerminalTemporaryWriteCleanupFinalizationPreparation(
            root = normalizedRoot,
            pendingAudioName = normalizedPendingAudioName,
            finalAudioName = normalizedFinalAudioName,
            expectedOperationId = expectedOperationId
                ?.trim()
                ?.takeIf(String::isNotBlank),
            expectedFinalizationToken = expectedFinalizationToken
                ?.trim()
                ?.takeIf(String::isNotBlank),
            targets = normalizedTargets,
            generationId = UUID.randomUUID().toString()
        )
        val updated = current.copy(
            preparations = (current.preparations.filterNot { candidate ->
                candidate.root == preparation.root &&
                    candidate.pendingAudioName == preparation.pendingAudioName
            } + preparation).sortedBy { candidate -> candidate.sortKey() }
        )
        if (store.write(encodeOrNull(updated))) preparation else null
    }

    fun snapshot(): TerminalTemporaryWriteCleanupJournalSnapshot = synchronized(lock) {
        val entries = readStateLocked()
            ?: return@synchronized TerminalTemporaryWriteCleanupJournalSnapshot.Unavailable(
                "invalid or unreadable cleanup journal"
            )
        TerminalTemporaryWriteCleanupJournalSnapshot.Available(entries.terminalEntries)
    }

    fun preparationSnapshot(): TerminalTemporaryWriteCleanupPreparationSnapshot = synchronized(lock) {
        val entries = readStateLocked()
            ?: return@synchronized TerminalTemporaryWriteCleanupPreparationSnapshot.Unavailable(
                "invalid or unreadable cleanup journal"
            )
        TerminalTemporaryWriteCleanupPreparationSnapshot.Available(entries.preparations)
    }

    fun consume(entry: TerminalTemporaryWriteCleanupJournalEntry): Boolean = synchronized(lock) {
        val normalizedRoot = entry.root.normalizedOrNull() ?: return@synchronized false
        if (entry.targets.mapNotNull { target -> target.normalizedOrNull() }
                .isEmpty()
        ) {
            return@synchronized false
        }
        val current = readStateLocked()
            ?: return@synchronized false
        val currentEntry = current.terminalEntries.firstOrNull { candidate ->
            candidate.root == normalizedRoot
        } ?: return@synchronized true
        if (currentEntry.generationId != entry.generationId) {
            return@synchronized false
        }
        val updated = current.copy(
            terminalEntries = current.terminalEntries.filterNot { candidate ->
                candidate.root == normalizedRoot
            }
        )
        store.write(encodeOrNull(updated))
    }

    /** 并发入队只刷新代次且目标集合未变时返回当前记录
     * 调用方消费前必须重新校验存储，本方法不修改日志，新增目标仍受 consume 的代次检查保护
     */
    fun currentEntryIfTargetsMatch(
        entry: TerminalTemporaryWriteCleanupJournalEntry
    ): TerminalTemporaryWriteCleanupJournalEntry? = synchronized(lock) {
        val normalizedRoot = entry.root.normalizedOrNull() ?: return@synchronized null
        val normalizedTargets = entry.targets
            .mapNotNull { target -> target.normalizedOrNull() }
            .distinct()
            .sortedWith(terminalTemporaryWriteCleanupTargetComparator)
        if (normalizedTargets.isEmpty()) {
            return@synchronized null
        }
        val current = readStateLocked() ?: return@synchronized null
        current.terminalEntries.firstOrNull { candidate ->
            candidate.root == normalizedRoot && candidate.targets == normalizedTargets
        }
    }

    fun completeFinalization(
        preparation: TerminalTemporaryWriteCleanupFinalizationPreparation
    ): Boolean = synchronized(lock) {
        val normalizedPreparation = preparation.normalizedOrNull() ?: return@synchronized false
        val current = readStateLocked()
            ?: return@synchronized false
        val currentPreparation = current.preparations.firstOrNull { candidate ->
            candidate.root == normalizedPreparation.root &&
                candidate.pendingAudioName == normalizedPreparation.pendingAudioName
        } ?: return@synchronized false
        if (currentPreparation.generationId != normalizedPreparation.generationId) {
            return@synchronized false
        }
        val updated = current.copy(
            terminalEntries = mergeTerminalEntries(
                entries = current.terminalEntries,
                root = currentPreparation.root,
                targets = currentPreparation.targets
            ),
            preparations = current.preparations.filterNot { candidate ->
                candidate.root == currentPreparation.root &&
                    candidate.pendingAudioName == currentPreparation.pendingAudioName
            }
        )
        store.write(encodeOrNull(updated))
    }

    private fun readStateLocked(): JournalState? {
        val read = runCatching(store::read)
        if (read.isFailure) {
            return null
        }
        val payload = read.getOrNull()
        if (payload.isNullOrBlank()) {
            return JournalState()
        }
        return decode(payload)
    }

    private fun mergeTerminalEntries(
        entries: List<TerminalTemporaryWriteCleanupJournalEntry>,
        root: TerminalTemporaryWriteCleanupRoot,
        targets: Collection<TerminalTemporaryWriteCleanupTarget>
    ): List<TerminalTemporaryWriteCleanupJournalEntry> {
        val existing = entries.firstOrNull { entry -> entry.root == root }
        val mergedTargets = (existing?.targets.orEmpty() + targets)
            .mapNotNull { target -> target.normalizedOrNull() }
            .distinct()
            .sortedWith(terminalTemporaryWriteCleanupTargetComparator)
        return (entries.filterNot { entry -> entry.root == root } +
            TerminalTemporaryWriteCleanupJournalEntry(
                root = root,
                targets = mergedTargets,
                generationId = UUID.randomUUID().toString()
            )).sortedBy { entry ->
            "${entry.root.type.asPersistedValue()}:${entry.root.identity}"
        }
    }

    private fun encodeOrNull(state: JournalState): String? {
        return state.takeIf { current ->
            current.terminalEntries.isNotEmpty() || current.preparations.isNotEmpty()
        }?.let(::encode)
    }

    private fun encode(state: JournalState): String {
        val recordArray = JSONArray()
        state.terminalEntries.forEach { entry ->
            recordArray.put(
                JSONObject()
                    .put(ROOT_TYPE_KEY, entry.root.type.asPersistedValue())
                    .put(ROOT_IDENTITY_KEY, entry.root.identity)
                    .put(TARGET_NAMES_KEY, encodeLegacyTargetNames(entry.targets))
                    .put(TARGETS_KEY, encodeTargets(entry.targets))
                    .put(GENERATION_ID_KEY, entry.generationId)
            )
        }
        val preparationArray = JSONArray()
        state.preparations.forEach { preparation ->
            preparationArray.put(
                JSONObject()
                    .put(ROOT_TYPE_KEY, preparation.root.type.asPersistedValue())
                    .put(ROOT_IDENTITY_KEY, preparation.root.identity)
                    .put(PENDING_AUDIO_NAME_KEY, preparation.pendingAudioName)
                    .put(FINAL_AUDIO_NAME_KEY, preparation.finalAudioName)
                    .put(EXPECTED_OPERATION_ID_KEY, preparation.expectedOperationId)
                    .put(
                        EXPECTED_FINALIZATION_TOKEN_KEY,
                        preparation.expectedFinalizationToken
                    )
                    .put(TARGET_NAMES_KEY, encodeLegacyTargetNames(preparation.targets))
                    .put(TARGETS_KEY, encodeTargets(preparation.targets))
                    .put(GENERATION_ID_KEY, preparation.generationId)
            )
        }
        return JSONObject()
            .put(VERSION_KEY, FORMAT_VERSION)
            .put(RECORDS_KEY, recordArray)
            .put(PREPARATIONS_KEY, preparationArray)
            .toString()
    }

    private fun decode(payload: String): JournalState? {
        val document = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (document.optInt(VERSION_KEY, -1) != FORMAT_VERSION) {
            return null
        }
        val recordArray = document.optJSONArray(RECORDS_KEY) ?: return null
        val preparationArray = document.optJSONArray(PREPARATIONS_KEY) ?: JSONArray()
        val entries = mutableListOf<TerminalTemporaryWriteCleanupJournalEntry>()
        for (index in 0 until recordArray.length()) {
            val record = recordArray.optJSONObject(index) ?: return null
            val rootType = TerminalTemporaryWriteCleanupRootType.fromPersistedValue(
                record.optString(ROOT_TYPE_KEY)
            ) ?: return null
            val root = TerminalTemporaryWriteCleanupRoot(
                type = rootType,
                identity = record.optString(ROOT_IDENTITY_KEY)
            ).normalizedOrNull() ?: return null
            if (entries.any { entry -> entry.root == root }) {
                return null
            }
            val targets = decodeTargets(record) ?: return null
            if (targets.isEmpty()) {
                return null
            }
            val generationId = record.optString(GENERATION_ID_KEY)
                .trim()
                .takeIf(String::isNotBlank)
                ?: LEGACY_GENERATION_ID
            entries += TerminalTemporaryWriteCleanupJournalEntry(
                root = root,
                targets = targets,
                generationId = generationId
            )
        }
        val preparations = mutableListOf<TerminalTemporaryWriteCleanupFinalizationPreparation>()
        for (index in 0 until preparationArray.length()) {
            val record = preparationArray.optJSONObject(index) ?: return null
            val rootType = TerminalTemporaryWriteCleanupRootType.fromPersistedValue(
                record.optString(ROOT_TYPE_KEY)
            ) ?: return null
            val root = TerminalTemporaryWriteCleanupRoot(
                type = rootType,
                identity = record.optString(ROOT_IDENTITY_KEY)
            ).normalizedOrNull() ?: return null
            val targets = decodeTargets(record) ?: return null
            val preparation = TerminalTemporaryWriteCleanupFinalizationPreparation(
                root = root,
                pendingAudioName = record.optString(PENDING_AUDIO_NAME_KEY),
                finalAudioName = record.optString(FINAL_AUDIO_NAME_KEY),
                expectedOperationId = record.optString(EXPECTED_OPERATION_ID_KEY)
                    .trim()
                    .takeIf(String::isNotBlank),
                expectedFinalizationToken = record
                    .optString(EXPECTED_FINALIZATION_TOKEN_KEY)
                    .trim()
                    .takeIf(String::isNotBlank),
                targets = targets,
                generationId = record.optString(GENERATION_ID_KEY)
            ).normalizedOrNull() ?: return null
            if (preparations.any { candidate ->
                    candidate.root == preparation.root &&
                        candidate.pendingAudioName == preparation.pendingAudioName
                }
            ) {
                return null
            }
            preparations += preparation
        }
        return JournalState(
            terminalEntries = entries.sortedBy { entry -> entry.sortKey() },
            preparations = preparations.sortedBy { preparation -> preparation.sortKey() }
        )
    }

    private fun TerminalTemporaryWriteCleanupRoot.normalizedOrNull(): TerminalTemporaryWriteCleanupRoot? {
        val normalizedIdentity = identity.trim().takeIf(String::isNotBlank) ?: return null
        return copy(identity = normalizedIdentity)
    }

    private fun TerminalTemporaryWriteCleanupFinalizationPreparation.normalizedOrNull():
        TerminalTemporaryWriteCleanupFinalizationPreparation? {
        val normalizedRoot = root.normalizedOrNull() ?: return null
        val normalizedPendingAudioName = normalizeTargetName(pendingAudioName) ?: return null
        val normalizedFinalAudioName = normalizeTargetName(finalAudioName) ?: return null
        val normalizedTargets = targets
            .mapNotNull { target -> target.normalizedOrNull() }
            .distinct()
            .sortedWith(terminalTemporaryWriteCleanupTargetComparator)
        val normalizedGenerationId = generationId.trim().takeIf(String::isNotBlank) ?: return null
        if (normalizedTargets.isEmpty()) {
            return null
        }
        return copy(
            root = normalizedRoot,
            pendingAudioName = normalizedPendingAudioName,
            finalAudioName = normalizedFinalAudioName,
            expectedOperationId = expectedOperationId
                ?.trim()
                ?.takeIf(String::isNotBlank),
            expectedFinalizationToken = expectedFinalizationToken
                ?.trim()
                ?.takeIf(String::isNotBlank),
            targets = normalizedTargets,
            generationId = normalizedGenerationId
        )
    }

    private fun TerminalTemporaryWriteCleanupTarget.normalizedOrNull():
        TerminalTemporaryWriteCleanupTarget? {
        val normalizedDisplayName = normalizeTargetName(displayName) ?: return null
        return copy(
            displayName = normalizedDisplayName,
            temporaryWriteOwnerName = temporaryWriteOwnerName
                ?.trim()
                ?.takeIf(String::isNotBlank)
        )
    }

    private fun encodeLegacyTargetNames(
        targets: Collection<TerminalTemporaryWriteCleanupTarget>
    ): JSONArray {
        return JSONArray().apply {
            targets.map(TerminalTemporaryWriteCleanupTarget::displayName)
                .distinct()
                .forEach(::put)
        }
    }

    private fun encodeTargets(
        targets: Collection<TerminalTemporaryWriteCleanupTarget>
    ): JSONArray {
        return JSONArray().apply {
            targets.forEach { target ->
                put(
                    JSONObject()
                        .put(DISPLAY_NAME_KEY, target.displayName)
                        .put(TEMPORARY_WRITE_OWNER_NAME_KEY, target.temporaryWriteOwnerName)
                )
            }
        }
    }

    private fun decodeTargets(record: JSONObject): List<TerminalTemporaryWriteCleanupTarget>? {
        val encodedTargets = record.optJSONArray(TARGETS_KEY)
        if (encodedTargets == null) {
            val legacyTargetNames = record.optJSONArray(TARGET_NAMES_KEY) ?: return null
            return buildList {
                for (index in 0 until legacyTargetNames.length()) {
                    val target = TerminalTemporaryWriteCleanupTarget(
                        displayName = legacyTargetNames.optString(index)
                    ).normalizedOrNull() ?: return null
                    add(target)
                }
            }.distinct().sortedWith(terminalTemporaryWriteCleanupTargetComparator)
        }
        return buildList {
            for (index in 0 until encodedTargets.length()) {
                val encodedTarget = encodedTargets.optJSONObject(index) ?: return null
                val target = TerminalTemporaryWriteCleanupTarget(
                    displayName = encodedTarget.optString(DISPLAY_NAME_KEY),
                    temporaryWriteOwnerName = encodedTarget
                        .optString(TEMPORARY_WRITE_OWNER_NAME_KEY)
                        .trim()
                        .takeIf(String::isNotBlank)
                ).normalizedOrNull() ?: return null
                add(target)
            }
        }.distinct().sortedWith(terminalTemporaryWriteCleanupTargetComparator)
    }

    private fun TerminalTemporaryWriteCleanupJournalEntry.sortKey(): String {
        return "${root.type.asPersistedValue()}:${root.identity}"
    }

    private fun TerminalTemporaryWriteCleanupFinalizationPreparation.sortKey(): String {
        return "${root.type.asPersistedValue()}:${root.identity}:$pendingAudioName"
    }

    private fun normalizeTargetName(rawName: String): String? {
        val name = rawName.trim().takeIf(String::isNotBlank) ?: return null
        if (name == "." || name == ".." || '/' in name || '\\' in name) {
            return null
        }
        return name
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val VERSION_KEY = "version"
        const val RECORDS_KEY = "records"
        const val ROOT_TYPE_KEY = "rootType"
        const val ROOT_IDENTITY_KEY = "rootIdentity"
        const val TARGET_NAMES_KEY = "targetNames"
        const val TARGETS_KEY = "targets"
        const val DISPLAY_NAME_KEY = "displayName"
        const val TEMPORARY_WRITE_OWNER_NAME_KEY = "temporaryWriteOwnerName"
        const val GENERATION_ID_KEY = "generationId"
        const val PREPARATIONS_KEY = "preparations"
        const val PENDING_AUDIO_NAME_KEY = "pendingAudioName"
        const val FINAL_AUDIO_NAME_KEY = "finalAudioName"
        const val EXPECTED_OPERATION_ID_KEY = "expectedOperationId"
        const val EXPECTED_FINALIZATION_TOKEN_KEY = "expectedFinalizationToken"
        const val LEGACY_GENERATION_ID = "legacy"
    }

    private val terminalTemporaryWriteCleanupTargetComparator = compareBy<
        TerminalTemporaryWriteCleanupTarget
    > { target -> target.displayName }.thenBy { target -> target.temporaryWriteOwnerName.orEmpty() }

    private data class JournalState(
        val terminalEntries: List<TerminalTemporaryWriteCleanupJournalEntry> = emptyList(),
        val preparations: List<TerminalTemporaryWriteCleanupFinalizationPreparation> = emptyList()
    )
}

internal object PersistentTerminalTemporaryWriteCleanupJournal {
    private val lock = Any()

    fun enqueue(
        context: Context,
        root: TerminalTemporaryWriteCleanupRoot,
        targetNames: Collection<String>
    ): Boolean = synchronized(lock) {
        runCatching {
            journal(context).enqueue(root, targetNames)
        }.getOrDefault(false)
    }

    fun enqueueTargets(
        context: Context,
        root: TerminalTemporaryWriteCleanupRoot,
        targets: Collection<TerminalTemporaryWriteCleanupTarget>
    ): Boolean = synchronized(lock) {
        runCatching {
            journal(context).enqueueTargets(root, targets)
        }.getOrDefault(false)
    }

    fun snapshot(context: Context): TerminalTemporaryWriteCleanupJournalSnapshot = synchronized(lock) {
        runCatching {
            journal(context).snapshot()
        }.getOrElse { error ->
            TerminalTemporaryWriteCleanupJournalSnapshot.Unavailable(
                "unable to read cleanup journal: ${error.javaClass.simpleName}"
            )
        }
    }

    fun consume(
        context: Context,
        entry: TerminalTemporaryWriteCleanupJournalEntry
    ): Boolean = synchronized(lock) {
        runCatching {
            journal(context).consume(entry)
        }.getOrDefault(false)
    }

    fun currentEntryIfTargetsMatch(
        context: Context,
        entry: TerminalTemporaryWriteCleanupJournalEntry
    ): TerminalTemporaryWriteCleanupJournalEntry? = synchronized(lock) {
        runCatching {
            journal(context).currentEntryIfTargetsMatch(entry)
        }.getOrNull()
    }

    fun prepareFinalization(
        context: Context,
        root: TerminalTemporaryWriteCleanupRoot,
        pendingAudioName: String,
        finalAudioName: String,
        expectedOperationId: String?,
        targetNames: Collection<String>,
        expectedFinalizationToken: String? = null
    ): TerminalTemporaryWriteCleanupFinalizationPreparation? = synchronized(lock) {
        runCatching {
            journal(context).prepareFinalization(
                root = root,
                pendingAudioName = pendingAudioName,
                finalAudioName = finalAudioName,
                expectedOperationId = expectedOperationId,
                targetNames = targetNames,
                expectedFinalizationToken = expectedFinalizationToken
            )
        }.getOrNull()
    }

    fun prepareFinalizationTargets(
        context: Context,
        root: TerminalTemporaryWriteCleanupRoot,
        pendingAudioName: String,
        finalAudioName: String,
        expectedOperationId: String?,
        targets: Collection<TerminalTemporaryWriteCleanupTarget>,
        expectedFinalizationToken: String? = null
    ): TerminalTemporaryWriteCleanupFinalizationPreparation? = synchronized(lock) {
        runCatching {
            journal(context).prepareFinalizationTargets(
                root = root,
                pendingAudioName = pendingAudioName,
                finalAudioName = finalAudioName,
                expectedOperationId = expectedOperationId,
                targets = targets,
                expectedFinalizationToken = expectedFinalizationToken
            )
        }.getOrNull()
    }

    fun preparationSnapshot(
        context: Context
    ): TerminalTemporaryWriteCleanupPreparationSnapshot = synchronized(lock) {
        runCatching {
            journal(context).preparationSnapshot()
        }.getOrElse { error ->
            TerminalTemporaryWriteCleanupPreparationSnapshot.Unavailable(
                "unable to read cleanup journal: ${error.javaClass.simpleName}"
            )
        }
    }

    fun completeFinalization(
        context: Context,
        preparation: TerminalTemporaryWriteCleanupFinalizationPreparation
    ): Boolean = synchronized(lock) {
        runCatching {
            journal(context).completeFinalization(preparation)
        }.getOrDefault(false)
    }

    private fun journal(context: Context): TerminalTemporaryWriteCleanupJournal {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        return TerminalTemporaryWriteCleanupJournal(
            SharedPreferencesTerminalTemporaryWriteCleanupJournalStore(preferences)
        )
    }

    private const val PREFERENCES_NAME = "terminal_temporary_write_cleanup_v1"
}

private class SharedPreferencesTerminalTemporaryWriteCleanupJournalStore(
    private val preferences: SharedPreferences
) : TerminalTemporaryWriteCleanupJournalStore {
    override fun read(): String? = preferences.getString(PAYLOAD_KEY, null)

    @SuppressLint("UseKtx")
    override fun write(payload: String?): Boolean {
        val editor = preferences.edit()
        if (payload == null) {
            editor.remove(PAYLOAD_KEY)
        } else {
            editor.putString(PAYLOAD_KEY, payload)
        }
        return editor.commit()
    }

    private companion object {
        const val PAYLOAD_KEY = "payload"
    }
}
