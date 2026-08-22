package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.data.model.stableKey
import org.json.JSONObject

class DownloadExecutionOperationStore(
    private val directoryProvider: (Context) -> File = { context ->
        File(context.filesDir, DIRECTORY_NAME)
    }
) {
    fun save(
        context: Context,
        request: DownloadExecutionRequest
    ) {
        saveTo(directoryProvider(context), request)
    }

    fun read(
        context: Context,
        operationId: String
    ): DownloadExecutionRequest? {
        return readFrom(directoryProvider(context), operationId)
    }

    fun remove(
        context: Context,
        operationId: String
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val file = File(directoryProvider(context), fileName(normalizedId))
        runCatching { if (file.exists()) file.delete() }
    }

    fun markStopped(
        context: Context,
        operationId: String
    ) {
        updateState(directoryProvider(context), operationId, STOPPED_STATE)
    }

    fun isStopped(
        context: Context,
        operationId: String
    ): Boolean {
        return isStoppedIn(directoryProvider(context), operationId)
    }

    fun stoppedSongKeys(context: Context): Set<String> {
        return stoppedSongKeysIn(directoryProvider(context))
    }

    internal fun isStoppedIn(
        directory: File,
        operationId: String
    ): Boolean {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
        val file = File(directory, fileName(normalizedId))
        return runCatching {
            if (!file.isFile) return false
            JSONObject(file.readText(Charsets.UTF_8)).optString(STATE_KEY) == STOPPED_STATE
        }.getOrDefault(false)
    }

    internal fun stoppedSongKeysIn(directory: File): Set<String> {
        return directory.listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith(OPERATION_FILE_PREFIX) &&
                    file.name.endsWith(OPERATION_FILE_SUFFIX)
            }
            ?.mapNotNull { file ->
                val operationId = file.name
                    .removePrefix(OPERATION_FILE_PREFIX)
                    .removeSuffix(OPERATION_FILE_SUFFIX)
                    .let(::normalizeDownloadOperationId)
                    ?: return@mapNotNull null
                if (!isStoppedIn(directory, operationId)) return@mapNotNull null
                readFrom(directory, operationId)?.song?.stableKey()
            }
            ?.toSet()
            ?: emptySet()
    }

    fun findOperationIdForSong(
        context: Context,
        songKey: String
    ): String? {
        return findOperationIdForSongIn(directoryProvider(context), songKey)
    }

    internal fun findOperationIdForSongIn(
        directory: File,
        songKey: String
    ): String? {
        val normalizedSongKey = songKey.trim().takeIf(String::isNotEmpty) ?: return null
        return directory.listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith(OPERATION_FILE_PREFIX) &&
                    file.name.endsWith(OPERATION_FILE_SUFFIX)
            }
            ?.mapNotNull { file ->
                val operationId = file.name
                    .removePrefix(OPERATION_FILE_PREFIX)
                    .removeSuffix(OPERATION_FILE_SUFFIX)
                    .let(::normalizeDownloadOperationId)
                    ?: return@mapNotNull null
                readFrom(directory, operationId)
                    ?.takeIf { request -> request.song.stableKey() == normalizedSongKey }
                    ?.operationId
            }
            ?.firstOrNull()
    }

    internal fun saveTo(
        directory: File,
        request: DownloadExecutionRequest
    ) {
        val payload = JSONObject().apply {
            put("version", PAYLOAD_VERSION)
            put("operationId", request.operationId)
            put("preserveStaging", request.preserveStaging)
            put(STATE_KEY, ACTIVE_STATE)
            put(
                "song",
                ManagedDownloadStorageJsonCodec.workingResumeMetadataToJson(
                    song = request.song,
                    operationId = request.operationId
                )
            )
            put("sourceStableKey", request.song.sourceStableKey)
        }.toString()
        ManagedDownloadAtomicFile.writeTextAtomically(
            target = File(directory, fileName(request.operationId)),
            content = payload
        )
    }

    internal fun readFrom(
        directory: File,
        operationId: String
    ): DownloadExecutionRequest? {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return null
        val file = File(directory, fileName(normalizedId))
        val root = runCatching {
            if (!file.isFile) return null
            JSONObject(file.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (root.optInt("version") != PAYLOAD_VERSION) return null
        if (root.optString("operationId") != normalizedId) return null
        val songJson = root.optJSONObject("song") ?: return null
        val parsedSong = runCatching {
            ManagedDownloadStorageJsonCodec.workingResumeMetadataSongFromJson(
                songJson.toString()
            )
        }.getOrNull() ?: return null
        val song = parsedSong.copy(
            sourceStableKey = root.optString("sourceStableKey")
                .takeIf(String::isNotBlank)
        )
        return runCatching {
            DownloadExecutionRequest(
                operationId = normalizedId,
                song = song,
                preserveStaging = root.optBoolean("preserveStaging", false)
            )
        }.getOrNull()
    }

    private fun updateState(
        directory: File,
        operationId: String,
        state: String
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val file = File(directory, fileName(normalizedId))
        runCatching {
            if (!file.isFile) return
            val payload = JSONObject(file.readText(Charsets.UTF_8))
            payload.put(STATE_KEY, state)
            ManagedDownloadAtomicFile.writeTextAtomically(
                target = file,
                content = payload.toString()
            )
        }
    }

    companion object {
        internal const val DIRECTORY_NAME = "download_execution_operations"
        private const val PAYLOAD_VERSION = 1
        private const val STATE_KEY = "executionState"
        private const val ACTIVE_STATE = "ACTIVE"
        private const val STOPPED_STATE = "STOPPED"
        private const val OPERATION_FILE_PREFIX = "operation_"
        private const val OPERATION_FILE_SUFFIX = ".json"

        internal fun fileName(operationId: String): String {
            return OPERATION_FILE_PREFIX + operationId + OPERATION_FILE_SUFFIX
        }
    }
}
