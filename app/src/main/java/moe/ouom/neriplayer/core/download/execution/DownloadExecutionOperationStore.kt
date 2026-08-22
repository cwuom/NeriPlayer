package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
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

    internal fun saveTo(
        directory: File,
        request: DownloadExecutionRequest
    ) {
        val payload = JSONObject().apply {
            put("version", PAYLOAD_VERSION)
            put("operationId", request.operationId)
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
                song = song
            )
        }.getOrNull()
    }

    companion object {
        internal const val DIRECTORY_NAME = "download_execution_operations"
        private const val PAYLOAD_VERSION = 1
        private const val OPERATION_FILE_PREFIX = "operation_"
        private const val OPERATION_FILE_SUFFIX = ".json"

        internal fun fileName(operationId: String): String {
            return OPERATION_FILE_PREFIX + operationId + OPERATION_FILE_SUFFIX
        }
    }
}
