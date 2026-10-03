package moe.ouom.neriplayer.core.player.persistence

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import java.lang.reflect.Type
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.model.playback.PersistedPlaybackState
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.core.player.persistence.withPlaybackState
import moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation
import moe.ouom.neriplayer.common.io.writeTextAtomically

private fun <T> Gson.readJson(file: File, type: Type): T {
    file.inputStream().bufferedReader().use { reader ->
        return fromJson(reader, type)
    }
}

internal class PlaybackQueueLegacyStore(
    private val stateFile: File,
    private val playbackStateFile: File,
    private val gson: Gson
) {
    fun read(): PersistedState? {
        if (!stateFile.exists()) {
            return null
        }
        val type = object : TypeToken<PersistedState>() {}.type
        val legacyData: PersistedState = gson.readJson(stateFile, type)
        val playbackState = playbackStateFile.takeIf(File::exists)?.runCatching {
            gson.readJson<PersistedPlaybackState>(
                this,
                PersistedPlaybackState::class.java
            )
        }?.getOrNull()
        return playbackState?.let(legacyData::withPlaybackState) ?: legacyData
    }

    fun write(
        state: PersistedState,
        playbackState: PersistedPlaybackState
    ) {
        runCatching { playbackStateFile.delete() }
        stateFile.writeTextAtomically(gson.toJson(state))
        playbackStateFile.writeTextAtomically(gson.toJson(playbackState))
    }

    fun lastModified(): Long {
        return maxOf(
            stateFile.takeIf(File::exists)?.lastModified() ?: 0L,
            playbackStateFile.takeIf(File::exists)?.lastModified() ?: 0L
        )
    }

    fun clear() {
        deleteIfPresent(stateFile)
        deleteIfPresent(playbackStateFile)
    }

    private fun deleteIfPresent(file: File) {
        if (!file.delete() && file.exists()) {
            throw IOException("Failed to remove obsolete playback snapshot: ${file.name}")
        }
    }
}

internal enum class PlaybackQueuePersistTarget {
    ROOM,
    LEGACY_JSON,
    NONE
}

internal suspend fun persistPlaybackQueueWithRoomFallback(
    roomStore: PlaybackQueueStateStore,
    legacyStore: PlaybackQueueLegacyStore,
    queueState: PersistedState?,
    playbackState: PersistedPlaybackState,
    shouldWriteQueueState: Boolean,
    shouldWritePlaybackState: Boolean,
    onRoomFailure: (Throwable) -> Unit = {},
    queueStateProvider: (() -> PersistedState)? = null
): PlaybackQueuePersistTarget {
    currentCoroutineContext().ensureActive()
    if (!shouldWriteQueueState && !shouldWritePlaybackState) {
        return PlaybackQueuePersistTarget.NONE
    }
    val now = System.currentTimeMillis()
    if (queueState == null && queueStateProvider == null) {
        return runCatchingNonCancellation {
            roomStore.clear(now)
            currentCoroutineContext().ensureActive()
            legacyStore.clear()
            PlaybackQueuePersistTarget.ROOM
        }.getOrElse { error ->
            currentCoroutineContext().ensureActive()
            onRoomFailure(error)
            legacyStore.write(
                PersistedState(playlist = emptyList(), index = -1)
                    .withPlaybackState(playbackState),
                playbackState
            )
            runCatchingNonCancellation { roomStore.markLegacyJsonPrimary(now) }
                .onFailure { markerError ->
                    onRoomFailure(markerError)
                }.getOrThrow()
            PlaybackQueuePersistTarget.LEGACY_JSON
        }
    }

    val resolvedQueueState by lazy(LazyThreadSafetyMode.NONE) {
        checkNotNull(queueStateProvider?.invoke() ?: queueState)
    }
    return runCatchingNonCancellation {
        if (shouldWriteQueueState) {
            roomStore.replaceSnapshot(resolvedQueueState, now)
        }
        if (shouldWritePlaybackState && !shouldWriteQueueState) {
            roomStore.updatePlaybackState(playbackState, now)
        }
        currentCoroutineContext().ensureActive()
        // 回切成功后清除旧 JSON，避免相同时间戳或时钟回拨让旧快照再次胜出
        legacyStore.clear()
        PlaybackQueuePersistTarget.ROOM
    }.getOrElse { error ->
        currentCoroutineContext().ensureActive()
        onRoomFailure(error)
        legacyStore.write(resolvedQueueState, playbackState)
        runCatchingNonCancellation { roomStore.markLegacyJsonPrimary(now) }
            .onFailure { markerError ->
                onRoomFailure(markerError)
            }.getOrThrow()
        PlaybackQueuePersistTarget.LEGACY_JSON
    }
}
