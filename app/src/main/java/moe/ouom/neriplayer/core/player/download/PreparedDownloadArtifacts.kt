package moe.ouom.neriplayer.core.player.download

import java.io.File
import java.io.FileInputStream
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import org.json.JSONObject
import java.security.MessageDigest

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

internal object PreparedDownloadArtifactsStore {
    private const val MANIFEST_VERSION = 1
    private const val MANIFEST_PREFIX = "npdl_sidecar_manifest_"
    private const val MANIFEST_SUFFIX = ".json"

    fun persist(context: android.content.Context, artifacts: PreparedDownloadArtifacts): PreparedDownloadArtifacts {
        val manifest = manifestFile(context, artifacts.songKey)
        val payload = JSONObject().apply {
            put("version", MANIFEST_VERSION)
            put("songKey", artifacts.songKey)
            put("attemptId", artifacts.attemptId ?: JSONObject.NULL)
            put("audioTargetName", artifacts.audioTargetName ?: JSONObject.NULL)
            put("audioReference", artifacts.audioReference ?: JSONObject.NULL)
            put("expectedLyric", artifacts.expectedLyric)
            put("expectedTranslatedLyric", artifacts.expectedTranslatedLyric)
            put("expectedRomanizedLyric", artifacts.expectedRomanizedLyric)
            put("embeddedTagState", artifacts.embeddedTagState.name)
            putArtifact("lyric", artifacts.lyric)
            putArtifact("translatedLyric", artifacts.translatedLyric)
            putArtifact("romanizedLyric", artifacts.romanizedLyric)
            putBinaryArtifact("cover", artifacts.cover)
        }
        ManagedDownloadAtomicFile.writeTextAtomically(manifest, payload.toString())
        return artifacts.copy(manifestFile = manifest)
    }

    fun restore(context: android.content.Context, songKey: String): PreparedDownloadArtifacts? {
        val manifest = manifestFile(context, songKey)
        if (!manifest.isFile) return null
        val restored = runCatching {
            val root = JSONObject(manifest.readText(Charsets.UTF_8))
            require(root.optInt("version") == MANIFEST_VERSION)
            require(root.optString("songKey") == songKey)
            val attemptId = root.optLong("attemptId", Long.MIN_VALUE)
                .takeUnless { it == Long.MIN_VALUE }
            val lyric = root.artifact("lyric")
            val translated = root.artifact("translatedLyric")
            val romanized = root.artifact("romanizedLyric")
            val cover = root.binaryArtifact("cover")
            PreparedDownloadArtifacts(
                songKey = songKey,
                attemptId = attemptId,
                audioTargetName = root.optString("audioTargetName")
                    .takeIf(String::isNotBlank),
                audioReference = root.optString("audioReference")
                    .takeIf(String::isNotBlank),
                lyric = lyric,
                translatedLyric = translated,
                romanizedLyric = romanized,
                cover = cover,
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
        if (restored == null || !restored.isValid()) {
            runCatching { manifest.delete() }
            restored?.cleanup()
            return null
        }
        return restored
    }

    fun list(context: android.content.Context): List<PreparedDownloadArtifacts> {
        val directory = File(
            context.filesDir,
            moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
        )
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file ->
                file.isFile && file.name.startsWith(MANIFEST_PREFIX) &&
                    file.name.endsWith(MANIFEST_SUFFIX)
            }
            .mapNotNull { manifest -> restoreManifest(manifest) }
            .toList()
    }

    fun delete(context: android.content.Context, songKey: String) {
        runCatching { manifestFile(context, songKey).delete() }
    }

    private fun PreparedDownloadArtifacts.isValid(): Boolean {
        return (audioTargetName?.isNotBlank() == true ||
            audioReference?.isNotBlank() == true) &&
            listOfNotNull(lyric, translatedLyric, romanizedLyric)
                .all { artifact -> artifact.file.isFile && sha256(artifact.file) == artifact.sha256 } &&
            (cover == null || (
                cover.file.isFile &&
                    cover.file.length() == cover.size &&
                    sha256(cover.file) == cover.sha256
                ))
    }

    private fun JSONObject.putArtifact(name: String, artifact: PreparedTextArtifact?) {
        if (artifact == null) return
        put(name, JSONObject().apply {
            put("file", artifact.file.absolutePath)
            put("sha256", artifact.sha256)
            put("targetName", artifact.targetName)
        })
    }

    private fun JSONObject.putBinaryArtifact(name: String, artifact: PreparedBinaryArtifact?) {
        if (artifact == null) return
        put(name, JSONObject().apply {
            put("file", artifact.file.absolutePath)
            put("size", artifact.size)
            put("sha256", artifact.sha256)
            put("mimeType", artifact.mimeType)
            put("targetName", artifact.targetName)
        })
    }

    private fun JSONObject.artifact(name: String): PreparedTextArtifact? {
        val value = optJSONObject(name) ?: return null
        val file = File(value.optString("file"))
        val hash = value.optString("sha256").takeIf(String::isNotBlank) ?: return null
        val targetName = value.optString("targetName").takeIf(String::isNotBlank) ?: return null
        return PreparedTextArtifact(file, hash, targetName)
    }

    private fun JSONObject.binaryArtifact(name: String): PreparedBinaryArtifact? {
        val value = optJSONObject(name) ?: return null
        val file = File(value.optString("file"))
        val size = value.optLong("size", -1L)
        val hash = value.optString("sha256").takeIf(String::isNotBlank) ?: return null
        val mimeType = value.optString("mimeType").takeIf(String::isNotBlank) ?: return null
        val targetName = value.optString("targetName").takeIf(String::isNotBlank) ?: return null
        return PreparedBinaryArtifact(file, size, hash, mimeType, targetName)
    }

    private fun JSONObject.readExpectedFlag(name: String, fallback: Boolean): Boolean {
        return if (has(name)) optBoolean(name) else fallback
    }

    private fun manifestFile(context: android.content.Context, songKey: String): File {
        val directory = File(
            context.filesDir,
            moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
        )
        directory.mkdirs()
        val hash = java.lang.Long.toHexString(songKey.hashCode().toLong() and 0xffffffffL)
        return File(directory, "$MANIFEST_PREFIX$hash$MANIFEST_SUFFIX")
    }

    private fun restoreManifest(manifest: File): PreparedDownloadArtifacts? {
        val restored = runCatching {
            val root = JSONObject(manifest.readText(Charsets.UTF_8))
            require(root.optInt("version") == MANIFEST_VERSION)
            val songKey = root.optString("songKey").takeIf(String::isNotBlank)
                ?: return@runCatching null
            val attemptId = root.optLong("attemptId", Long.MIN_VALUE)
                .takeUnless { it == Long.MIN_VALUE }
            val lyric = root.artifact("lyric")
            val translated = root.artifact("translatedLyric")
            val romanized = root.artifact("romanizedLyric")
            PreparedDownloadArtifacts(
                songKey = songKey,
                attemptId = attemptId,
                audioTargetName = root.optString("audioTargetName")
                    .takeIf(String::isNotBlank),
                audioReference = root.optString("audioReference")
                    .takeIf(String::isNotBlank),
                lyric = lyric,
                translatedLyric = translated,
                romanizedLyric = romanized,
                cover = root.binaryArtifact("cover"),
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
        if (restored == null || !restored.isValid()) {
            runCatching { manifest.delete() }
            restored?.cleanup()
            return null
        }
        return restored
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
}
