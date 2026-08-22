package moe.ouom.neriplayer.core.download.index

import java.io.File
import java.security.MessageDigest
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import org.json.JSONArray
import org.json.JSONObject

internal data class ManagedLibraryIndexEntry(
    val stableKey: String,
    val artifactId: String,
    val audioName: String,
    val audioReference: String,
    val metadataName: String?,
    val state: String,
    val downloadTimeMs: Long?,
    val updatedAtMs: Long,
    val songId: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val mediaUri: String? = null,
    val channelId: String? = null,
    val audioId: String? = null,
    val subAudioId: String? = null,
    val playlistContextId: String? = null,
    val durationMs: Long? = null,
    val coverPath: String? = null
)

internal object ManagedLibraryFastIndex {
    const val FORMAT_VERSION = 1
    const val DEFAULT_SHARD_COUNT = 32

    fun shardFor(stableKey: String, shardCount: Int = DEFAULT_SHARD_COUNT): String {
        val normalizedCount = shardCount.coerceAtLeast(1)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stableKey.toByteArray(Charsets.UTF_8))
        val value = ((digest[0].toInt() and 0xff) shl 8) or (digest[1].toInt() and 0xff)
        return (value % normalizedCount).toString().padStart(2, '0')
    }

    fun encode(
        libraryId: String,
        shard: String,
        entries: List<ManagedLibraryIndexEntry>,
        generatedAtMs: Long
    ): String {
        val body = JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("libraryId", libraryId)
            put("shard", shard)
            put("generatedAtMs", generatedAtMs)
            put(
                "entries",
                JSONArray().also { array ->
                    entries.sortedBy(ManagedLibraryIndexEntry::stableKey).forEach { entry ->
                        array.put(entry.toJson())
                    }
                }
            )
        }
        return body.put("checksum", sha256(body.toString())).toString()
    }

    fun decode(raw: String): ManagedLibraryIndexShard? {
        return runCatching {
            val root = JSONObject(raw)
            require(root.optInt("formatVersion") == FORMAT_VERSION)
            val checksum = root.optString("checksum").takeIf(String::isNotBlank) ?: return null
            val body = JSONObject(root.toString()).apply { remove("checksum") }
            require(sha256(body.toString()) == checksum)
            val libraryId = root.optString("libraryId").takeIf(String::isNotBlank) ?: return null
            val shard = root.optString("shard").takeIf(String::isNotBlank) ?: return null
            val entries = buildList {
                val array = root.optJSONArray("entries") ?: JSONArray()
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toEntry()?.let(::add)
                }
            }
            ManagedLibraryIndexShard(
                libraryId = libraryId,
                shard = shard,
                generatedAtMs = root.optLong("generatedAtMs"),
                entries = entries
            )
        }.getOrNull()
    }

    fun writeAtomically(
        file: File,
        libraryId: String,
        shard: String,
        entries: List<ManagedLibraryIndexEntry>,
        generatedAtMs: Long
    ) {
        file.parentFile?.mkdirs()
        ManagedDownloadAtomicFile.writeTextAtomically(
            file,
            encode(libraryId, shard, entries, generatedAtMs)
        )
    }

    private fun ManagedLibraryIndexEntry.toJson(): JSONObject {
        return JSONObject().apply {
            put("stableKey", stableKey)
            put("artifactId", artifactId)
            put("audioName", audioName)
            put("audioReference", audioReference)
            put("metadataName", metadataName)
            put("state", state)
            put("downloadTimeMs", downloadTimeMs)
            put("updatedAtMs", updatedAtMs)
            put("songId", songId)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("mediaUri", mediaUri)
            put("channelId", channelId)
            put("audioId", audioId)
            put("subAudioId", subAudioId)
            put("playlistContextId", playlistContextId)
            put("durationMs", durationMs)
            put("coverPath", coverPath)
        }
    }

    private fun JSONObject.toEntry(): ManagedLibraryIndexEntry? {
        val stableKey = optString("stableKey").takeIf(String::isNotBlank) ?: return null
        val artifactId = optString("artifactId").takeIf(String::isNotBlank) ?: return null
        val audioName = optString("audioName").takeIf(String::isNotBlank) ?: return null
        val audioReference = optString("audioReference").takeIf(String::isNotBlank) ?: return null
        return ManagedLibraryIndexEntry(
            stableKey = stableKey,
            artifactId = artifactId,
            audioName = audioName,
            audioReference = audioReference,
            metadataName = optString("metadataName").takeIf(String::isNotBlank),
            state = optString("state").takeIf(String::isNotBlank) ?: "CORE_COMMITTED",
            downloadTimeMs = optLong("downloadTimeMs")
                .takeIf { has("downloadTimeMs") && it > 0L },
            updatedAtMs = optLong("updatedAtMs"),
            songId = optLong("songId").takeIf { has("songId") && it > 0L },
            title = optString("title").takeIf(String::isNotBlank),
            artist = optString("artist").takeIf(String::isNotBlank),
            album = optString("album").takeIf(String::isNotBlank),
            mediaUri = optString("mediaUri").takeIf(String::isNotBlank),
            channelId = optString("channelId").takeIf(String::isNotBlank),
            audioId = optString("audioId").takeIf(String::isNotBlank),
            subAudioId = optString("subAudioId").takeIf(String::isNotBlank),
            playlistContextId = optString("playlistContextId").takeIf(String::isNotBlank),
            durationMs = optLong("durationMs").takeIf { has("durationMs") && it > 0L },
            coverPath = optString("coverPath").takeIf(String::isNotBlank)
        )
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal data class ManagedLibraryIndexShard(
    val libraryId: String,
    val shard: String,
    val generatedAtMs: Long,
    val entries: List<ManagedLibraryIndexEntry>
)
