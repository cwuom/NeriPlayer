package moe.ouom.neriplayer.core.download.storage.backend

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TEMPORARY_WRITE_JOURNAL_VERSION = 1
private const val TEMPORARY_WRITE_JOURNAL_FILE = "managed_temporary_writes_v1.json"

internal data class ManagedTemporaryWriteJournalEntry(
    val uri: String,
    val parentUri: String,
    val targetPrefix: String,
    val requestedDisplayName: String,
    val actualDisplayName: String?,
    val createdAtMs: Long
)

internal object PersistentManagedTemporaryWriteJournal {
    private const val TAG = "ManagedTempWriteJournal"
    private val lock = Any()

    fun recordCreated(
        context: Context,
        temporaryUri: Uri,
        target: StorageTarget.SafTarget,
        requestedDisplayName: String
    ): Boolean = synchronized(lock) {
        val current = readLocked(context) ?: return@synchronized false
        val uri = temporaryUri.toString()
        val entry = ManagedTemporaryWriteJournalEntry(
            uri = uri,
            parentUri = target.parent.uri.toString(),
            targetPrefix = ManagedTemporaryWriteArtifacts.targetNamePrefix(target),
            requestedDisplayName = requestedDisplayName,
            actualDisplayName = null,
            createdAtMs = System.currentTimeMillis()
        )
        writeLocked(context, current.filterNot { it.uri == uri } + entry)
    }

    fun recordActualDisplayName(
        context: Context,
        temporaryUri: Uri,
        requestedDisplayName: String,
        actualDisplayName: String
    ): Boolean = synchronized(lock) {
        val current = readLocked(context) ?: return@synchronized false
        val uri = temporaryUri.toString()
        var found = false
        val updated = current.map { entry ->
            if (entry.uri == uri && entry.requestedDisplayName == requestedDisplayName) {
                found = true
                entry.copy(actualDisplayName = actualDisplayName)
            } else {
                entry
            }
        }
        found && writeLocked(context, updated)
    }

    fun reconcileCreatedUri(
        context: Context,
        temporaryUri: Uri,
        requestedDisplayName: String
    ): Boolean {
        val entry = synchronized(lock) {
            readLocked(context)?.firstOrNull { candidate ->
                candidate.uri == temporaryUri.toString() &&
                    candidate.requestedDisplayName == requestedDisplayName
            }
        } ?: return true
        return reconcileEntry(context, entry)
    }

    fun completeCreatedUri(
        context: Context,
        temporaryUri: Uri,
        requestedDisplayName: String
    ): Boolean {
        val entry = synchronized(lock) {
            readLocked(context)?.firstOrNull { candidate ->
                candidate.uri == temporaryUri.toString() &&
                    candidate.requestedDisplayName == requestedDisplayName
            }
        } ?: return true
        return remove(context, entry)
    }

    fun recover(context: Context): Int {
        val entries = synchronized(lock) {
            readLocked(context)
        } ?: return 0
        return entries.count { entry -> reconcileEntry(context, entry) }
    }

    internal fun snapshotForTest(context: Context): List<ManagedTemporaryWriteJournalEntry>? =
        synchronized(lock) { readLocked(context) }

    private fun reconcileEntry(context: Context, entry: ManagedTemporaryWriteJournalEntry): Boolean {
        if (ManagedTemporaryWriteArtifacts.isActiveSafWrite(
                parentUri = entry.parentUri,
                displayName = entry.requestedDisplayName
            )
        ) {
            return false
        }
        val uri = runCatching { entry.uri.toUri() }.getOrNull() ?: return false
        return when (val query = queryDisplayName(context, uri)) {
            TemporaryDocumentQuery.Missing -> remove(context, entry)
            is TemporaryDocumentQuery.Found -> {
                val actualWriteToken = ManagedTemporaryWriteArtifacts.managedWriteToken(
                    query.displayName
                )
                val expectedWriteToken = ManagedTemporaryWriteArtifacts.managedWriteToken(
                    entry.requestedDisplayName
                )
                when {
                    expectedWriteToken != null && actualWriteToken == expectedWriteToken -> {
                        val deleted = runCatching {
                            DocumentsContract.deleteDocument(context.contentResolver, uri)
                        }.onFailure { error ->
                            NPLogger.w(
                                TAG,
                                "删除中断的 SAF 临时文件失败，保留恢复凭据: ${entry.uri}",
                                error
                            )
                        }.getOrDefault(false)
                        deleted && queryDisplayName(context, uri) == TemporaryDocumentQuery.Missing &&
                            remove(context, entry)
                    }
                    actualWriteToken != null -> {
                        NPLogger.e(
                            TAG,
                            "SAF 临时 URI 已指向其他 owner，保留凭据且不删除: ${entry.uri}"
                        )
                        false
                    }
                    else -> remove(context, entry)
                }
            }
            is TemporaryDocumentQuery.Unavailable -> {
                NPLogger.w(
                    TAG,
                    "无法确认 SAF 临时文件状态，保留恢复凭据: ${entry.uri}",
                    query.error
                )
                false
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): TemporaryDocumentQuery {
        return try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            ) ?: return TemporaryDocumentQuery.Unavailable(
                IllegalStateException("provider returned null cursor")
            )
            cursor.use {
                if (!it.moveToFirst()) return TemporaryDocumentQuery.Missing
                val index = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val displayName = index.takeIf { value -> value >= 0 }
                    ?.let(it::getString)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: return TemporaryDocumentQuery.Unavailable(
                        IllegalStateException("provider omitted display name")
                    )
                TemporaryDocumentQuery.Found(displayName)
            }
        } catch (error: Throwable) {
            if (isMissingDocumentQueryFailure(error)) {
                TemporaryDocumentQuery.Missing
            } else {
                TemporaryDocumentQuery.Unavailable(error)
            }
        }
    }

    internal fun isMissingDocumentQueryFailure(error: Throwable): Boolean {
        return ManagedDownloadReferenceIo.isMissingDocumentFailure(error)
    }

    private fun remove(
        context: Context,
        expected: ManagedTemporaryWriteJournalEntry
    ): Boolean = synchronized(lock) {
        val current = readLocked(context) ?: return@synchronized false
        val updated = current.filterNot { entry -> sameOwner(entry, expected) }
        if (updated.size == current.size) true else writeLocked(context, updated)
    }

    private fun sameOwner(
        current: ManagedTemporaryWriteJournalEntry,
        expected: ManagedTemporaryWriteJournalEntry
    ): Boolean {
        return current.uri == expected.uri &&
            current.parentUri == expected.parentUri &&
            current.targetPrefix == expected.targetPrefix &&
            current.requestedDisplayName == expected.requestedDisplayName &&
            current.createdAtMs == expected.createdAtMs
    }

    private fun readLocked(context: Context): List<ManagedTemporaryWriteJournalEntry>? {
        val file = journalFile(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.optInt("version", -1) == TEMPORARY_WRITE_JOURNAL_VERSION)
            val entries = root.getJSONArray("entries")
            buildList(entries.length()) {
                for (index in 0 until entries.length()) {
                    val item = entries.getJSONObject(index)
                    add(
                        ManagedTemporaryWriteJournalEntry(
                            uri = item.getString("uri"),
                            parentUri = item.getString("parentUri"),
                            targetPrefix = item.getString("targetPrefix"),
                            requestedDisplayName = item.getString("requestedDisplayName"),
                            actualDisplayName = if (item.isNull("actualDisplayName")) {
                                null
                            } else {
                                item.optString("actualDisplayName")
                                    .trim()
                                    .takeIf(String::isNotBlank)
                            },
                            createdAtMs = item.optLong("createdAtMs", 0L)
                        )
                    )
                }
            }.distinctBy(ManagedTemporaryWriteJournalEntry::uri)
        }.onFailure { error ->
            NPLogger.e(TAG, "读取 SAF 临时文件恢复凭据失败，拒绝覆盖: ${file.name}", error)
        }.getOrNull()
    }

    private fun writeLocked(
        context: Context,
        entries: List<ManagedTemporaryWriteJournalEntry>
    ): Boolean = runCatching {
        val file = journalFile(context)
        if (entries.isEmpty()) {
            return@runCatching !file.exists() || file.delete() || !file.exists()
        }
        val body = JSONObject().apply {
            put("version", TEMPORARY_WRITE_JOURNAL_VERSION)
            put("entries", JSONArray().apply {
                entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("uri", entry.uri)
                        put("parentUri", entry.parentUri)
                        put("targetPrefix", entry.targetPrefix)
                        put("requestedDisplayName", entry.requestedDisplayName)
                        put("actualDisplayName", entry.actualDisplayName ?: JSONObject.NULL)
                        put("createdAtMs", entry.createdAtMs)
                    })
                }
            })
        }
        ManagedDownloadAtomicFile.writeTextAtomically(file, body.toString())
        true
    }.onFailure { error ->
        NPLogger.e(TAG, "写入 SAF 临时文件恢复凭据失败", error)
    }.getOrDefault(false)

    private fun journalFile(context: Context): File {
        val applicationContext = runCatching { context.applicationContext }.getOrNull() ?: context
        val filesDirectory = runCatching { applicationContext.filesDir }.getOrNull()
        return if (filesDirectory != null) {
            File(filesDirectory, TEMPORARY_WRITE_JOURNAL_FILE)
        } else {
            File(
                System.getProperty("java.io.tmpdir"),
                "$TEMPORARY_WRITE_JOURNAL_FILE.${System.identityHashCode(context)}"
            )
        }
    }

    private sealed interface TemporaryDocumentQuery {
        data object Missing : TemporaryDocumentQuery
        data class Found(val displayName: String) : TemporaryDocumentQuery
        data class Unavailable(val error: Throwable) : TemporaryDocumentQuery
    }
}
