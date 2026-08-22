package moe.ouom.neriplayer.core.player.download

import java.io.File
import java.io.FileInputStream
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal data class PreparedTextArtifact(
    val file: File,
    val sha256: String,
    val targetName: String
) {
    fun readText(): String? {
        return runCatching { file.takeIf(File::isFile)?.readText(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }
}

internal data class PreparedBinaryArtifact(
    val file: File,
    val size: Long,
    val sha256: String,
    val mimeType: String,
    val targetName: String
)

internal enum class EmbeddedTagPreparationState {
    NOT_REQUESTED,
    PREPARED_ON_TEMP_AUDIO,
    UNSUPPORTED,
    RETRYABLE_FAILURE
}

internal data class PreparedDownloadArtifacts(
    val songKey: String,
    val attemptId: Long?,
    val operationId: String? = null,
    val audioTargetName: String? = null,
    val audioReference: String? = null,
    val lyric: PreparedTextArtifact? = null,
    val translatedLyric: PreparedTextArtifact? = null,
    val romanizedLyric: PreparedTextArtifact? = null,
    val cover: PreparedBinaryArtifact? = null,
    val expectedLyric: Boolean = lyric != null,
    val expectedTranslatedLyric: Boolean = translatedLyric != null,
    val expectedRomanizedLyric: Boolean = romanizedLyric != null,
    val embeddedTagState: EmbeddedTagPreparationState =
        EmbeddedTagPreparationState.NOT_REQUESTED,
    val manifestFile: File? = null
) {
    internal val stagedFilePaths: Set<String>
        get() = listOfNotNull(
            lyric?.file,
            translatedLyric?.file,
            romanizedLyric?.file,
            cover?.file,
            manifestFile
        ).mapTo(linkedSetOf(), File::getAbsolutePath)

    fun cleanup() {
        listOfNotNull(
            lyric?.file,
            translatedLyric?.file,
            romanizedLyric?.file,
            cover?.file,
            manifestFile
        ).distinct().forEach { file ->
            runCatching {
                if (file.exists() && !file.delete()) {
                    file.deleteOnExit()
                }
            }
        }
    }
}

private sealed interface PreparedArtifactValidation {
    data object Valid : PreparedArtifactValidation
    data class Stale(val reason: String) : PreparedArtifactValidation
}

internal object PreparedDownloadArtifactsStore {
    private const val MANIFEST_VERSION = 2
    private const val LEGACY_MANIFEST_VERSION = 1
    private const val MANIFEST_PREFIX = "npdl_sidecar_manifest_"
    private const val MANIFEST_SUFFIX = ".json"
    private const val OPERATION_MANIFEST_NAME = "operation.json"

    fun persist(context: android.content.Context, artifacts: PreparedDownloadArtifacts): PreparedDownloadArtifacts {
        val operationId = artifacts.operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()
        val operationDirectory = operationDirectory(context, operationId)
        val normalized = artifacts.copy(
            operationId = operationId,
            lyric = artifacts.lyric?.let { moveArtifactIntoOperation(it, operationDirectory) },
            translatedLyric = artifacts.translatedLyric?.let {
                moveArtifactIntoOperation(it, operationDirectory)
            },
            romanizedLyric = artifacts.romanizedLyric?.let {
                moveArtifactIntoOperation(it, operationDirectory)
            },
            cover = artifacts.cover?.let { moveArtifactIntoOperation(it, operationDirectory) }
        )
        val manifest = File(operationDirectory, OPERATION_MANIFEST_NAME)
        val payload = JSONObject().apply {
            put("version", MANIFEST_VERSION)
            put("songKey", normalized.songKey)
            put("operationId", operationId)
            put("attemptId", normalized.attemptId ?: JSONObject.NULL)
            put("audioTargetName", normalized.audioTargetName ?: JSONObject.NULL)
            put("audioReference", normalized.audioReference ?: JSONObject.NULL)
            put("expectedLyric", normalized.expectedLyric)
            put("expectedTranslatedLyric", normalized.expectedTranslatedLyric)
            put("expectedRomanizedLyric", normalized.expectedRomanizedLyric)
            put("embeddedTagState", normalized.embeddedTagState.name)
            putArtifact("lyric", normalized.lyric, operationDirectory)
            putArtifact("translatedLyric", normalized.translatedLyric, operationDirectory)
            putArtifact("romanizedLyric", normalized.romanizedLyric, operationDirectory)
            putBinaryArtifact("cover", normalized.cover, operationDirectory)
        }
        operationDirectory.mkdirs()
        ManagedDownloadAtomicFile.writeTextAtomically(manifest, payload.toString())
        return normalized.copy(manifestFile = manifest)
    }

    fun restore(context: android.content.Context, songKey: String): PreparedDownloadArtifacts? {
        val operationCandidate = runCatching {
            operationDirectories(context).asSequence()
                .mapNotNull { directory -> restoreManifest(File(directory, OPERATION_MANIFEST_NAME)) }
                .firstOrNull { it.songKey == songKey }
        }.getOrNull()
        return operationCandidate ?: runCatching {
            restoreManifest(legacyManifestFile(context, songKey))
                ?: legacyManifestCandidates(context)
                    .asSequence()
                    .mapNotNull { manifest -> restoreManifest(manifest) }
                    .firstOrNull { it.songKey == songKey }
        }.getOrNull()
    }

    fun list(context: android.content.Context): List<PreparedDownloadArtifacts> {
        val directory = runCatching {
            File(
                context.filesDir,
                moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
            )
        }.getOrNull() ?: return emptyList()
        val operationManifests = runCatching {
            operationDirectories(context).asSequence()
                .map { directory -> File(directory, OPERATION_MANIFEST_NAME) }
        }.getOrElse { emptySequence() }
        val legacyManifests = runCatching {
            directory.listFiles().orEmpty().asSequence()
                .filter { file ->
                    runCatching {
                        file.isFile && file.name.startsWith(MANIFEST_PREFIX) &&
                            file.name.endsWith(MANIFEST_SUFFIX)
                    }.getOrDefault(false)
                }
        }.getOrElse { emptySequence() }
        return (operationManifests + legacyManifests)
            .mapNotNull { manifest -> restoreManifest(manifest) }
            .toList()
    }

    fun delete(context: android.content.Context, songKey: String) {
        runCatching {
            operationDirectories(context).forEach { directory ->
                val manifest = File(directory, OPERATION_MANIFEST_NAME)
                val restored = restoreManifest(manifest, validate = false)
                if (restored?.songKey == songKey) {
                    directory.deleteRecursively()
                }
            }
            legacyManifestFile(context, songKey).delete()
        }
    }

    private fun PreparedDownloadArtifacts.validate(): PreparedArtifactValidation {
        if (audioTargetName.isNullOrBlank() && audioReference.isNullOrBlank()) {
            return PreparedArtifactValidation.Stale("missing audio target")
        }
        listOfNotNull(lyric, translatedLyric, romanizedLyric).forEach { artifact ->
            validateTextArtifact(artifact).let { result ->
                if (result !is PreparedArtifactValidation.Valid) return result
            }
        }
        cover?.let { artifact ->
            validateBinaryArtifact(artifact).let { result ->
                if (result !is PreparedArtifactValidation.Valid) return result
            }
        }
        return PreparedArtifactValidation.Valid
    }

    private fun validateTextArtifact(
        artifact: PreparedTextArtifact
    ): PreparedArtifactValidation {
        return try {
            if (!artifact.file.isFile) {
                PreparedArtifactValidation.Stale("missing text sidecar")
            } else if (sha256(artifact.file) != artifact.sha256) {
                PreparedArtifactValidation.Stale("text sidecar hash mismatch")
            } else {
                PreparedArtifactValidation.Valid
            }
        } catch (error: Exception) {
            PreparedArtifactValidation.Stale(
                "text sidecar validation failed: ${error.javaClass.simpleName}"
            )
        }
    }

    private fun validateBinaryArtifact(
        artifact: PreparedBinaryArtifact
    ): PreparedArtifactValidation {
        return try {
            if (!artifact.file.isFile) {
                PreparedArtifactValidation.Stale("missing binary sidecar")
            } else if (artifact.file.length() != artifact.size) {
                PreparedArtifactValidation.Stale("binary sidecar size mismatch")
            } else if (sha256(artifact.file) != artifact.sha256) {
                PreparedArtifactValidation.Stale("binary sidecar hash mismatch")
            } else {
                PreparedArtifactValidation.Valid
            }
        } catch (error: Exception) {
            PreparedArtifactValidation.Stale(
                "binary sidecar validation failed: ${error.javaClass.simpleName}"
            )
        }
    }

    private fun JSONObject.putArtifact(
        name: String,
        artifact: PreparedTextArtifact?,
        operationDirectory: File
    ) {
        if (artifact == null) return
        put(name, JSONObject().apply {
            put("path", artifact.file.relativeTo(operationDirectory).path)
            put("sha256", artifact.sha256)
            put("targetName", artifact.targetName)
        })
    }

    private fun JSONObject.putBinaryArtifact(
        name: String,
        artifact: PreparedBinaryArtifact?,
        operationDirectory: File
    ) {
        if (artifact == null) return
        put(name, JSONObject().apply {
            put("path", artifact.file.relativeTo(operationDirectory).path)
            put("size", artifact.size)
            put("sha256", artifact.sha256)
            put("mimeType", artifact.mimeType)
            put("targetName", artifact.targetName)
        })
    }

    private fun JSONObject.artifact(name: String, operationDirectory: File): PreparedTextArtifact? {
        val value = optJSONObject(name) ?: return null
        val file = resolveArtifactFile(value, operationDirectory)
        val hash = value.optString("sha256").takeIf(String::isNotBlank) ?: return null
        val targetName = value.optString("targetName").takeIf(String::isNotBlank) ?: return null
        return PreparedTextArtifact(file, hash, targetName)
    }

    private fun JSONObject.binaryArtifact(
        name: String,
        operationDirectory: File
    ): PreparedBinaryArtifact? {
        val value = optJSONObject(name) ?: return null
        val file = resolveArtifactFile(value, operationDirectory)
        val size = value.optLong("size", -1L)
        val hash = value.optString("sha256").takeIf(String::isNotBlank) ?: return null
        val mimeType = value.optString("mimeType").takeIf(String::isNotBlank) ?: return null
        val targetName = value.optString("targetName").takeIf(String::isNotBlank) ?: return null
        return PreparedBinaryArtifact(file, size, hash, mimeType, targetName)
    }

    private fun JSONObject.readExpectedFlag(name: String, fallback: Boolean): Boolean {
        return if (has(name)) optBoolean(name) else fallback
    }

    private fun legacyManifestFile(context: android.content.Context, songKey: String): File {
        val directory = File(
            context.filesDir,
            moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
        )
        directory.mkdirs()
        val hash = sha256Text(songKey)
        return File(directory, "$MANIFEST_PREFIX$hash$MANIFEST_SUFFIX")
    }

    private fun operationDirectory(context: android.content.Context, operationId: String): File {
        val safeId = operationId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(
            context.filesDir,
            "${moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME}/$safeId"
        )
    }

    private fun operationDirectories(context: android.content.Context): List<File> {
        val root = File(
            context.filesDir,
            moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
        )
        return root.listFiles().orEmpty().filter(File::isDirectory)
    }

    private fun legacyManifestCandidates(context: android.content.Context): List<File> {
        val root = File(
            context.filesDir,
            moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
        )
        return root.listFiles().orEmpty().filter { file ->
            file.isFile && file.name.startsWith(MANIFEST_PREFIX) &&
                file.name.endsWith(MANIFEST_SUFFIX)
        }
    }

    private fun restoreManifest(
        manifest: File,
        validate: Boolean = true
    ): PreparedDownloadArtifacts? {
        if (!manifest.isFile) return null
        val restored = runCatching {
            val root = JSONObject(manifest.readText(Charsets.UTF_8))
            val version = root.optInt("version")
            require(version == MANIFEST_VERSION || version == LEGACY_MANIFEST_VERSION)
            val songKey = root.optString("songKey").takeIf(String::isNotBlank)
                ?: return@runCatching null
            val operationDirectory = manifest.parentFile ?: return@runCatching null
            val attemptId = root.optLong("attemptId", Long.MIN_VALUE)
                .takeUnless { it == Long.MIN_VALUE }
            val lyric = root.artifact("lyric", operationDirectory)
            val translated = root.artifact("translatedLyric", operationDirectory)
            val romanized = root.artifact("romanizedLyric", operationDirectory)
            PreparedDownloadArtifacts(
                songKey = songKey,
                attemptId = attemptId,
                operationId = root.optString("operationId")
                    .takeIf(String::isNotBlank)
                    ?: operationDirectory.name.takeIf { version == MANIFEST_VERSION },
                audioTargetName = root.optString("audioTargetName")
                    .takeIf(String::isNotBlank),
                audioReference = root.optString("audioReference")
                    .takeIf(String::isNotBlank),
                lyric = lyric,
                translatedLyric = translated,
                romanizedLyric = romanized,
                cover = root.binaryArtifact("cover", operationDirectory),
                expectedLyric = root.readExpectedFlag("expectedLyric", lyric != null),
                expectedTranslatedLyric = root.readExpectedFlag(
                    "expectedTranslatedLyric",
                    translated != null
                ),
                expectedRomanizedLyric = root.readExpectedFlag(
                    "expectedRomanizedLyric",
                    romanized != null
                ),
                embeddedTagState = runCatching {
                    EmbeddedTagPreparationState.valueOf(root.optString("embeddedTagState"))
                }.getOrDefault(EmbeddedTagPreparationState.NOT_REQUESTED),
                manifestFile = manifest
            )
        }.getOrNull()
        if (!validate) return restored
        if (restored == null || restored.validate() !is PreparedArtifactValidation.Valid) {
            runCatching { manifest.delete() }
            restored?.cleanup()
            if (restored != null) {
                runCatching { manifest.parentFile?.delete() }
            }
            return null
        }
        return restored
    }

    private fun resolveArtifactFile(value: JSONObject, operationDirectory: File): File {
        val relativePath = value.optString("path").takeIf(String::isNotBlank)
        if (relativePath != null) {
            val candidate = File(operationDirectory, relativePath)
            val basePath = runCatching { operationDirectory.canonicalPath }.getOrNull()
            val candidatePath = runCatching { candidate.canonicalPath }.getOrNull()
            if (basePath != null && candidatePath != null &&
                (candidatePath == basePath || candidatePath.startsWith("$basePath${File.separator}"))
            ) return candidate
        }
        return File(value.optString("file"))
    }

    private fun moveArtifactIntoOperation(
        artifact: PreparedTextArtifact,
        operationDirectory: File
    ): PreparedTextArtifact {
        val target = moveFileIntoOperation(artifact.file, operationDirectory)
        return artifact.copy(file = target)
    }

    private fun moveArtifactIntoOperation(
        artifact: PreparedBinaryArtifact,
        operationDirectory: File
    ): PreparedBinaryArtifact {
        val target = moveFileIntoOperation(artifact.file, operationDirectory)
        return artifact.copy(file = target)
    }

    private fun moveFileIntoOperation(source: File, operationDirectory: File): File {
        operationDirectory.mkdirs()
        val target = File(operationDirectory, source.name)
        if (source.absolutePath == target.absolutePath || !source.exists()) {
            return target
        }
        if (!source.renameTo(target)) {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            runCatching { source.delete() }
        }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sha256Text(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(value.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
