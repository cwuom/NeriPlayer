package moe.ouom.neriplayer.data.playlist.favorite

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.playlist.favorite/FavoritePlaylistRepository
 * Updated: 2026/3/23
 */


import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.FavoritePlaylistRoomStore
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.writeTextAtomically
import java.io.File

const val FAVORITE_SOURCE_NETEASE_ARTIST = "neteaseArtist"
private const val TAG = "FavoritePlaylistRepo"

data class FavoritePlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val source: String,
    val browseId: String? = null,
    val playlistId: String? = null,
    val subtitle: String? = null,
    val songs: List<SongItem>,
    val addedTime: Long = System.currentTimeMillis(),
    val sortOrder: Long = addedTime,
    val modifiedAt: Long = addedTime,
    val isDeleted: Boolean = false
)

class FavoritePlaylistRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val file = File(context.filesDir, "favorite_playlists.json")
    private val mutex = Mutex()
    private val persistenceMutex = Mutex()
    private val roomStore = FavoritePlaylistRoomStore(
        NeriUserDataDatabase.getInstance(context.applicationContext)
    )
    @Volatile
    private var roomStorageEnabled = true
    private val syncStorage by lazy { SecureTokenStorage(context) }

    private val initialSnapshots = load()
    private val _snapshots = MutableStateFlow(initialSnapshots)
    private val _favorites = MutableStateFlow(visibleFavorites(initialSnapshots))
    private var persistedSnapshots = initialSnapshots
    val favorites: StateFlow<List<FavoritePlaylist>> = _favorites

    init {
        publishInMemory(initialSnapshots)
    }

    private fun load(): List<FavoritePlaylist> {
        val roomFavorites = runCatching {
            runBlocking { roomStore.readIfRoomPrimary() }
        }.onFailure { error ->
            roomStorageEnabled = false
            NPLogger.e(TAG, "读取 Room 收藏歌单失败，回退到 JSON", error)
        }.getOrNull()
        if (roomFavorites != null) {
            return normalize(roomFavorites)
        }

        val list = try {
            if (!file.exists()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<FavoritePlaylist>>() {}.type
                gson.fromJson<List<FavoritePlaylist>>(file.readText(), type).orEmpty()
            }
        } catch (_: Exception) {
            emptyList()
        }
        val normalized = normalize(list)
        runCatching {
            runBlocking {
                roomStore.importLegacyAndPromote(normalized)
            }
            roomStorageEnabled = true
        }.onFailure { error ->
            roomStorageEnabled = false
            NPLogger.e(TAG, "将收藏歌单 JSON 导入 Room 失败", error)
        }
        return normalized
    }

    private fun saveToDisk(favorites: List<FavoritePlaylist>): Boolean {
        return runCatching {
            file.writeTextAtomically(gson.toJson(favorites))
        }.onFailure { error ->
            NPLogger.e(TAG, "保存收藏歌单失败", error)
        }.isSuccess
    }

    private fun publishInMemory(
        favorites: List<FavoritePlaylist>,
    ) {
        val normalized = normalize(favorites)
        _snapshots.value = normalized
        _favorites.value = visibleFavorites(normalized)
    }

    private fun normalize(favorites: List<FavoritePlaylist>): List<FavoritePlaylist> {
        return favorites
            .groupBy { it.id to it.source }
            .map { (_, snapshots) ->
                snapshots.maxByOrNull { maxOf(it.modifiedAt, it.addedTime) }!!
                    .normalizeSortOrder()
            }
            .sortedWith(compareByDescending<FavoritePlaylist> { it.sortOrder }.thenByDescending {
                maxOf(it.modifiedAt, it.addedTime)
            })
    }

    private fun visibleFavorites(
        favorites: List<FavoritePlaylist>
    ): List<FavoritePlaylist> {
        return favorites
            .filterNot(FavoritePlaylist::isDeleted)
            .sortedWith(compareByDescending<FavoritePlaylist> { it.sortOrder }.thenByDescending {
                maxOf(it.modifiedAt, it.addedTime)
            })
    }

    private suspend fun publish(
        favorites: List<FavoritePlaylist>,
        triggerSync: Boolean = true,
        persist: Boolean = true
    ) {
        val normalized = normalize(favorites)
        publishInMemory(normalized)
        if (!persist) return

        val persisted = persist(normalized)
        if (triggerSync) {
            if (persisted) {
                syncStorage.markSyncMutation()
                triggerAutoSync()
            } else {
                NPLogger.w(TAG, "收藏歌单未成功落盘，跳过自动同步")
            }
        }
    }

    private suspend fun persist(
        favorites: List<FavoritePlaylist>
    ): Boolean {
        return persistenceMutex.withLock {
            if (roomStorageEnabled) {
                val roomSucceeded = runCatching {
                    roomStore.writeIncremental(
                        previous = persistedSnapshots,
                        next = favorites
                    )
                }.onFailure { error ->
                    roomStorageEnabled = false
                    NPLogger.e(TAG, "写入 Room 收藏歌单失败，回退到 JSON", error)
                }.isSuccess
                if (roomSucceeded) {
                    persistedSnapshots = favorites
                    return@withLock true
                }
            }

            val legacySucceeded = saveToDisk(favorites)
            if (legacySucceeded) {
                runCatching { roomStore.markLegacyJsonPrimary() }
                    .onFailure { error ->
                        NPLogger.e(TAG, "标记收藏歌单 JSON 回退状态失败", error)
                    }
            }
            persistedSnapshots = favorites
            legacySucceeded
        }
    }

    private fun FavoritePlaylist.normalizeSortOrder(): FavoritePlaylist {
        val resolvedSortOrder = sortOrder.takeIf { it > 0L }
            ?: addedTime.takeIf { it > 0L }
            ?: modifiedAt.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        return if (resolvedSortOrder == sortOrder) this else copy(sortOrder = resolvedSortOrder)
    }

    private fun triggerAutoSync() {
        try {
            GitHubSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false)
            WebDavSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false)
        } catch (e: Exception) {
            NPLogger.e(TAG, "Failed to schedule sync", e)
        }
    }

    suspend fun addFavorite(
        id: Long,
        name: String,
        coverUrl: String?,
        trackCount: Int,
        source: String,
        browseId: String? = null,
        playlistId: String? = null,
        subtitle: String? = null,
        songs: List<SongItem>
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
            val list = _snapshots.value.toMutableList()
            val existingIndex = list.indexOfFirst { it.id == id && it.source == source }
            val existing = list.getOrNull(existingIndex)

            val mergedSongs = buildList {
                addAll(existing?.takeUnless { it.isDeleted }?.songs.orEmpty())
                addAll(songs)
            }.distinctBy { it.identity() }

            val now = System.currentTimeMillis()
            val merged = FavoritePlaylist(
                id = id,
                name = name,
                coverUrl = coverUrl ?: existing?.coverUrl,
                trackCount = maxOf(trackCount, existing?.trackCount ?: 0, mergedSongs.size),
                source = source,
                browseId = browseId?.takeIf { it.isNotBlank() } ?: existing?.browseId,
                playlistId = playlistId?.takeIf { it.isNotBlank() } ?: existing?.playlistId,
                subtitle = subtitle?.takeIf { it.isNotBlank() } ?: existing?.subtitle,
                songs = mergedSongs.ifEmpty { existing?.songs.orEmpty() },
                addedTime = existing?.takeUnless { it.isDeleted }?.addedTime ?: now,
                sortOrder = existing?.takeUnless { it.isDeleted }?.normalizeSortOrder()?.sortOrder ?: now,
                modifiedAt = now,
                isDeleted = false
            )

            if (existingIndex >= 0) {
                list[existingIndex] = merged
            } else {
                list += merged
            }

            publish(list)
            }
        }
    }

    suspend fun removeFavorite(id: Long, source: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
            val list = _snapshots.value.toMutableList()
            val existingIndex = list.indexOfFirst { it.id == id && it.source == source }
            if (existingIndex == -1) {
                return@withContext
            }

            val existing = list[existingIndex]
            if (existing.isDeleted) {
                return@withContext
            }

            list[existingIndex] = existing.copy(
                songs = emptyList(),
                trackCount = 0,
                coverUrl = existing.coverUrl,
                browseId = existing.browseId,
                playlistId = existing.playlistId,
                subtitle = existing.subtitle,
                sortOrder = existing.normalizeSortOrder().sortOrder,
                modifiedAt = System.currentTimeMillis(),
                isDeleted = true
            )
            publish(list)
            }
        }
    }

    suspend fun updateFavoriteMeta(
        id: Long,
        name: String,
        coverUrl: String?,
        trackCount: Int,
        source: String,
        browseId: String? = null,
        playlistId: String? = null,
        subtitle: String? = null,
        songs: List<SongItem>
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
            val list = _snapshots.value.toMutableList()
            val existingIndex = list.indexOfFirst { it.id == id && it.source == source }
            if (existingIndex == -1) return@withContext

            val existing = list[existingIndex]
            if (existing.isDeleted) return@withContext

            val mergedSongs = songs.ifEmpty { existing.songs }
            val resolvedName = name.ifBlank { existing.name }
            val resolvedCover = coverUrl ?: existing.coverUrl
            val resolvedTrackCount = maxOf(trackCount, mergedSongs.size, existing.trackCount)

            list[existingIndex] = existing.copy(
                name = resolvedName,
                coverUrl = resolvedCover,
                trackCount = resolvedTrackCount,
                browseId = browseId?.takeIf { it.isNotBlank() } ?: existing.browseId,
                playlistId = playlistId?.takeIf { it.isNotBlank() } ?: existing.playlistId,
                subtitle = subtitle?.takeIf { it.isNotBlank() } ?: existing.subtitle,
                songs = mergedSongs,
                sortOrder = existing.normalizeSortOrder().sortOrder,
                modifiedAt = System.currentTimeMillis(),
                isDeleted = false
            )
            publish(list)
            }
        }
    }

    suspend fun reorderFavorites(newOrder: List<String>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
            val currentVisible = _favorites.value
            if (currentVisible.isEmpty()) return@withContext

            val orderedKeys = newOrder.distinct()
            val visibleByKey = currentVisible.associateBy { "${it.source}:${it.id}" }
            val reorderedVisible = buildList {
                orderedKeys.mapNotNullTo(this) { visibleByKey[it] }
                currentVisible.filterTo(this) { favorite ->
                    "${favorite.source}:${favorite.id}" !in orderedKeys
                }
            }
            if (reorderedVisible.isEmpty()) return@withContext

            val now = System.currentTimeMillis()
            val reorderedByKey = reorderedVisible.mapIndexed { index, favorite ->
                val key = "${favorite.source}:${favorite.id}"
                key to favorite.copy(
                    sortOrder = now + (reorderedVisible.size - index).toLong(),
                    modifiedAt = now
                )
            }.toMap()

            val updated = _snapshots.value.map { snapshot ->
                reorderedByKey["${snapshot.source}:${snapshot.id}"] ?: snapshot
            }
            publish(updated)
            }
        }
    }

    suspend fun replaceFavoritesFromSync(favorites: List<FavoritePlaylist>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                publish(favorites, triggerSync = false)
            }
        }
    }

    suspend fun replaceFavoritesFromSyncIfUnchanged(
        favorites: List<FavoritePlaylist>,
        expectedMutationVersion: Long
    ): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (syncStorage.getSyncMutationVersion() != expectedMutationVersion) {
                    return@withLock false
                }
                publish(favorites, triggerSync = false)
                true
            }
        }
    }

    fun isFavorite(id: Long, source: String): Boolean {
        return _favorites.value.any { it.id == id && it.source == source }
    }

    fun getFavorite(id: Long, source: String): FavoritePlaylist? {
        return _favorites.value.firstOrNull { it.id == id && it.source == source }
    }

    fun getSyncSnapshots(): List<FavoritePlaylist> {
        return _snapshots.value
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: FavoritePlaylistRepository? = null

        fun getInstance(context: Context): FavoritePlaylistRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FavoritePlaylistRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
