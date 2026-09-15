package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.writeTextAtomically
import org.json.JSONArray
import org.json.JSONObject

/**
 * 持久化全选删除的最小意图，使进程在 catalog 尚未落盘时也能继续删除
 *
 * 这里只保存稳定引用而不是完整歌曲对象，避免在主线程写入大 payload
 */
internal data class DownloadedSongDeleteIntent(
    val rootKey: String,
    val requestedAtMs: Long,
    val targets: List<DownloadedSongDeleteTarget>
) {
    fun resolveSongs(catalog: Collection<DownloadedSong>): List<DownloadedSong> {
        if (targets.isEmpty() || catalog.isEmpty()) return emptyList()
        val targetIdentities = targets.mapTo(hashSetOf()) { it.deletionIdentity }
        val targetStableKeys = targets.mapNotNullTo(hashSetOf()) { it.stableKey }
        return catalog.filter { song ->
            val identity = song.deletionIdentity().trim()
            identity in targetIdentities ||
                song.stableKey?.trim()?.takeIf(String::isNotBlank) in targetStableKeys
        }
    }
}

internal data class DownloadedSongDeleteTarget(
    val deletionIdentity: String,
    val stableKey: String?
)

/**
 * 原子文件是全选删除的 crash recovery intent。文件删除失败时保留它，
 * 下次启动会再次尝试，绝不把未确认的物理删除当作成功
 */
internal object PersistentDownloadedSongDeleteIntentStore {
    private const val TAG = "DownloadedSongDeleteIntent"
    private const val FILE_NAME = "downloaded_song_delete_intent_v1.json"
    private const val VERSION = 1

    private val lock = Any()

    fun begin(
        context: Context,
        rootKey: String,
        songs: Collection<DownloadedSong>
    ): Boolean {
        val normalizedRootKey = rootKey.trim().takeIf(String::isNotBlank) ?: return false
        val targets = songs.mapNotNull { song ->
            val identity = song.deletionIdentity().trim()
                .takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            DownloadedSongDeleteTarget(
                deletionIdentity = identity,
                stableKey = song.stableKey?.trim()?.takeIf(String::isNotBlank)
            )
        }.distinctBy { target ->
            target.deletionIdentity to target.stableKey
        }
        if (targets.isEmpty()) return false
        val payload = JSONObject().apply {
            put("version", VERSION)
            put("rootKey", normalizedRootKey)
            put("requestedAtMs", System.currentTimeMillis())
            put("targets", JSONArray().apply {
                targets.forEach { target ->
                    put(JSONObject().apply {
                        put("deletionIdentity", target.deletionIdentity)
                        put("stableKey", target.stableKey)
                    })
                }
            })
        }.toString()
        return synchronized(lock) {
            runCatching {
                intentFile(context).writeTextAtomically(payload)
                true
            }.onFailure { error ->
                NPLogger.e(TAG, "写入全选删除恢复意图失败: ${error.message}", error)
            }.getOrDefault(false)
        }
    }

    fun read(context: Context): DownloadedSongDeleteIntent? {
        return synchronized(lock) {
            runCatching {
                val file = intentFile(context)
                if (!file.isFile) return@runCatching null
                val root = JSONObject(file.readText(Charsets.UTF_8))
                if (root.optInt("version", -1) != VERSION) {
                    NPLogger.w(TAG, "忽略未知版本的全选删除恢复意图: ${file.name}")
                    return@runCatching null
                }
                val rootKey = root.optString("rootKey")
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?: return@runCatching null
                val targetsJson = root.optJSONArray("targets")
                    ?: return@runCatching null
                val targets = buildList(targetsJson.length()) {
                    for (index in 0 until targetsJson.length()) {
                        val item = targetsJson.optJSONObject(index) ?: continue
                        val identity = item.optString("deletionIdentity")
                            .trim()
                            .takeIf(String::isNotBlank)
                            ?: continue
                        add(
                            DownloadedSongDeleteTarget(
                                deletionIdentity = identity,
                                stableKey = item.optString("stableKey")
                                    .trim()
                                    .takeIf(String::isNotBlank)
                            )
                        )
                    }
                }.distinctBy { target -> target.deletionIdentity to target.stableKey }
                if (targets.isEmpty()) return@runCatching null
                DownloadedSongDeleteIntent(
                    rootKey = rootKey,
                    requestedAtMs = root.optLong("requestedAtMs", 0L),
                    targets = targets
                )
            }.onFailure { error ->
                NPLogger.w(TAG, "读取全选删除恢复意图失败，保留文件等待下次重试: ${error.message}")
            }.getOrNull()
        }
    }

    fun hasPending(context: Context): Boolean {
        return synchronized(lock) {
            runCatching { intentFile(context).isFile }
                .onFailure { error ->
                    NPLogger.w(TAG, "检查全选删除恢复意图失败，保守保留栅栏: ${error.message}")
                }
                .getOrDefault(true)
        }
    }

    fun clear(context: Context): Boolean {
        return synchronized(lock) {
            runCatching {
                val file = intentFile(context)
                !file.exists() || file.delete() || !file.exists()
            }
                .onFailure { error ->
                    NPLogger.w(TAG, "清理全选删除恢复意图失败，保留文件: ${error.message}")
                }
                .getOrDefault(false)
        }
    }

    private fun intentFile(context: Context): File {
        return File(context.applicationContext.filesDir, FILE_NAME)
    }
}
