package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playbackVisualKey
import moe.ouom.neriplayer.data.model.remoteDownloadIdentityOrNull
import moe.ouom.neriplayer.data.model.stableKey
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理 core 提交后的短期播放桥接和未完成 sidecar
 *
 * 这个注册表只保存内存中的过渡引用，真正的最终文件和恢复凭据仍由持久层负责
 * 根目录代次变化时引用会失效，避免切换目录后播放入口拿到旧 URI
 */
internal class AudioDownloadReferenceRegistry(
    private val retentionMs: Long,
    private val maxEntries: Int
) {
    private data class CompletedAudioReference(
        val audio: ManagedDownloadStorage.StoredEntry,
        val committedAtMs: Long,
        val songLookupKeys: Set<String>,
        val rootGeneration: Long
    )

    private val completedAudioReferencesBySongKey =
        ConcurrentHashMap<String, CompletedAudioReference>()
    private val completedAudioReferencesByReference =
        ConcurrentHashMap<String, CompletedAudioReference>()
    private val partialSidecarReferencesBySongKey =
        ConcurrentHashMap<String, AudioDownloadManager.DownloadedSidecarReferences>()

    /** 多张别名必须一起替换，否则首播可能读到旧 URI */
    private val mutationLock = Any()

    fun consumeCompletedAudioReference(
        songKey: String
    ): ManagedDownloadStorage.StoredEntry? = synchronized(mutationLock) {
        val current = completedAudioReferencesBySongKey[songKey] ?: return@synchronized null
        if (isExpired(songKey, current)) {
            return@synchronized null
        }
        removeAliases(current)
        current.audio
    }

    fun releaseCompletedAudioReference(
        songKey: String,
        expectedAudio: ManagedDownloadStorage.StoredEntry? = null,
        retainForPlayback: Boolean = false
    ) {
        synchronized(mutationLock) {
            val current = completedAudioReferencesBySongKey[songKey] ?: return
            if (isExpired(songKey, current)) {
                return
            }
            if (!retainForPlayback && (expectedAudio == null || current.audio == expectedAudio)) {
                removeAliases(current)
            }
        }
    }

    /** 迁移或切换下载根后，主动丢弃仍指向旧目录的内存桥接引用 */
    fun invalidateCompletedAudioReference(song: SongItem) {
        synchronized(mutationLock) {
            val matches = buildSet {
                songLookupKeys(song).forEach { key ->
                    completedAudioReferencesBySongKey[key]?.let(::add)
                }
                listOfNotNull(song.localFilePath, song.mediaUri).forEach { reference ->
                    referenceKeys(reference).forEach { key ->
                        completedAudioReferencesByReference[key]?.let(::add)
                    }
                }
            }
            matches.forEach(::removeAliases)
        }
    }

    fun peekCompletedAudioReference(
        songKey: String
    ): ManagedDownloadStorage.StoredEntry? = synchronized(mutationLock) {
        completedAudioReferencesBySongKey[songKey]
            ?.takeUnless { isExpired(songKey, it) }
            ?.audio
    }

    fun peekCompletedAudioReferenceByRawReference(
        reference: String?
    ): ManagedDownloadStorage.StoredEntry? {
        val candidates = listOfNotNull(
            reference?.trim()?.takeIf(String::isNotBlank),
            safeToPlayableUri(reference)
        ).distinct()
        if (candidates.isEmpty()) {
            return null
        }
        return synchronized(mutationLock) {
            candidates.firstNotNullOfOrNull { key ->
                val current = completedAudioReferencesByReference[key]
                    ?: return@firstNotNullOfOrNull null
                current.takeUnless { isExpired(key, it) }?.audio
            }
        }
    }

    /** 允许播放列表使用刚提交的 URI，即使歌曲身份字段尚未同步 */
    fun peekCompletedAudioReference(
        song: SongItem
    ): ManagedDownloadStorage.StoredEntry? = synchronized(mutationLock) {
        songLookupKeys(song).forEach { key ->
            completedAudioReferencesBySongKey[key]?.let { current ->
                if (!isExpired(key, current)) {
                    return@synchronized current.audio
                }
            }
        }
        val references = listOfNotNull(song.localFilePath, song.mediaUri)
            .flatMap { reference ->
                listOfNotNull(
                    reference.trim().takeIf(String::isNotBlank),
                    safeToPlayableUri(reference)
                )
            }
            .distinct()
        references.firstNotNullOfOrNull { reference ->
            val current = completedAudioReferencesByReference[reference]
                ?: return@firstNotNullOfOrNull null
            current.takeUnless { isExpired(reference, it) }?.audio
        }
    }

    fun consumePartialSidecarReferences(
        songKey: String
    ): AudioDownloadManager.DownloadedSidecarReferences? =
        partialSidecarReferencesBySongKey.remove(songKey)

    fun peekPartialSidecarReferences(
        songKey: String
    ): AudioDownloadManager.DownloadedSidecarReferences? =
        partialSidecarReferencesBySongKey[songKey]

    fun rememberCompletedAudioReference(
        songKey: String,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) {
        rememberCompletedAudioReference(setOf(songKey), storedAudio)
    }

    /** 下载回调与播放队列可能使用不同版本的歌曲身份，同时保存兼容别名 */
    fun rememberCompletedAudioReference(
        song: SongItem,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) {
        rememberCompletedAudioReference(songLookupKeys(song), storedAudio)
    }

    fun rememberPartialSidecarReferences(
        songKey: String,
        sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences
    ) {
        if (sidecarReferences.isEmpty) {
            return
        }
        partialSidecarReferencesBySongKey.compute(songKey) { _, existing ->
            AudioDownloadSidecarPolicy.mergeDownloadedSidecarReferences(
                existing,
                sidecarReferences
            ).takeUnless(AudioDownloadManager.DownloadedSidecarReferences::isEmpty)
        }
    }

    fun clearCompletedAudioReference(songKey: String) {
        synchronized(mutationLock) {
            completedAudioReferencesBySongKey.remove(songKey)
                ?.let(::removeAliases)
        }
    }

    fun clearPartialSidecarReferences(songKey: String) {
        partialSidecarReferencesBySongKey.remove(songKey)
    }

    private fun rememberCompletedAudioReference(
        songLookupKeys: Set<String>,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) {
        val normalizedKeys = songLookupKeys
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        if (normalizedKeys.isEmpty()) {
            return
        }
        synchronized(mutationLock) {
            prune(System.currentTimeMillis())
            val completed = CompletedAudioReference(
                audio = storedAudio,
                committedAtMs = System.currentTimeMillis(),
                songLookupKeys = normalizedKeys,
                rootGeneration = LocalStorageRootGeneration.current()
            )
            normalizedKeys.forEach { key ->
                completedAudioReferencesBySongKey[key]
                    ?.let(::removeAliases)
            }
            normalizedKeys.forEach { key ->
                completedAudioReferencesBySongKey[key] = completed
            }
            referenceKeys(storedAudio).forEach { key ->
                completedAudioReferencesByReference[key] = completed
            }
        }
    }

    private fun prune(nowMs: Long) {
        val uniqueReferences = completedAudioReferencesBySongKey.values.distinct()
        uniqueReferences
            .filter { nowMs - it.committedAtMs >= retentionMs }
            .forEach(::removeAliases)
        val remaining = completedAudioReferencesBySongKey.values.distinct()
        if (remaining.size <= maxEntries) {
            return
        }
        remaining
            .sortedBy(CompletedAudioReference::committedAtMs)
            .take(remaining.size - maxEntries)
            .forEach(::removeAliases)
    }

    private fun songLookupKeys(song: SongItem): Set<String> {
        return buildSet {
            song.playbackVisualKey()
                .takeIf(String::isNotBlank)
                ?.let(::add)
            song.stableKey().takeIf(String::isNotBlank)?.let(::add)
            song.remoteDownloadIdentityOrNull()
                ?.stableKey()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
            song.sourceStableKey
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
            // 保留旧版本由原始字段生成的身份，兼容升级后的历史队列
            SongIdentity(song.id, song.album, song.mediaUri)
                .stableKey()
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }
    }

    private fun referenceKeys(
        audio: ManagedDownloadStorage.StoredEntry
    ): Set<String> {
        return listOfNotNull(
            audio.reference,
            audio.mediaUri,
            audio.localFilePath,
            safeToPlayableUri(audio.reference),
            safeToPlayableUri(audio.mediaUri)
        ).map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
    }

    private fun referenceKeys(reference: String?): Set<String> {
        return listOfNotNull(
            reference?.trim()?.takeIf(String::isNotBlank),
            safeToPlayableUri(reference)
        ).map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
    }

    private fun safeToPlayableUri(reference: String?): String? {
        return runCatching {
            ManagedDownloadStorage.toPlayableUri(reference)
        }.getOrNull()
    }

    private fun isExpired(
        lookupKey: String,
        current: CompletedAudioReference
    ): Boolean {
        val generationChanged = shouldInvalidateCompletedAudioReferenceForRoot(
            referenceRootGeneration = current.rootGeneration,
            currentRootGeneration = LocalStorageRootGeneration.current()
        )
        val expiredByAge = System.currentTimeMillis() - current.committedAtMs >= retentionMs
        if (!generationChanged && !expiredByAge) {
            return false
        }
        if (completedAudioReferencesBySongKey.remove(lookupKey, current)) {
            removeAliases(current)
        } else {
            completedAudioReferencesByReference.remove(lookupKey, current)
        }
        return true
    }

    private fun removeAliases(current: CompletedAudioReference) {
        current.songLookupKeys.forEach { key ->
            completedAudioReferencesBySongKey.remove(key, current)
        }
        referenceKeys(current.audio).forEach { key ->
            completedAudioReferencesByReference.remove(key, current)
        }
    }
}
