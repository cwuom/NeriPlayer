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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File

data class FavoritePlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val source: String,
    val songs: List<SongItem>,
    val addedTime: Long = System.currentTimeMillis(),
    val sortOrder: Long = addedTime,
    val modifiedAt: Long = addedTime,
    val isDeleted: Boolean = false
)

class FavoritePlaylistRepository private constructor(private val context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "favorite_playlists.json")

    private val _snapshots = MutableStateFlow<List<FavoritePlaylist>>(emptyList())
    private val _favorites = MutableStateFlow<List<FavoritePlaylist>>(emptyList())
    val favorites: StateFlow<List<FavoritePlaylist>> = _favorites

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
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
        publish(list, triggerSync = false)
    }

    private fun saveToDisk(triggerSync: Boolean = true) {
        runCatching {
            val json = gson.toJson(_snapshots.value)
            val parent = file.parentFile ?: context.filesDir
            val tmp = File(parent, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }
        if (triggerSync) {
            triggerAutoSync()
        }
    }

    private fun publish(favorites: List<FavoritePlaylist>, triggerSync: Boolean = true) {
        val normalized = favorites
            .groupBy { "${it.id}_${it.source}" }
            .map { (_, snapshots) ->
                snapshots.maxByOrNull { maxOf(it.modifiedAt, it.addedTime) }!!
                    .normalizeSortOrder()
            }
            .sortedWith(compareByDescending<FavoritePlaylist> { it.sortOrder }.thenByDescending {
                maxOf(it.modifiedAt, it.addedTime)
            })
        _snapshots.value = normalized
        _favorites.value = normalized
            .filterNot(FavoritePlaylist::isDeleted)
            .sortedWith(compareByDescending<FavoritePlaylist> { it.sortOrder }.thenByDescending {
                maxOf(it.modifiedAt, it.addedTime)
            })
        saveToDisk(triggerSync)
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
            val storage = SecureTokenStorage(context)
            storage.markSyncMutation()
            if (!storage.isAutoSyncEnabled()) {
                return
            }
            GitHubSyncWorker.scheduleDelayedSync(context, triggerByUserAction = false)
        } catch (e: Exception) {
            NPLogger.e("FavoritePlaylistRepo", "Failed to schedule sync", e)
        }
    }

    suspend fun addFavorite(
        id: Long,
        name: String,
        coverUrl: String?,
        trackCount: Int,
        source: String,
        songs: List<SongItem>
    ) {
        withContext(Dispatchers.IO) {
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

    suspend fun removeFavorite(id: Long, source: String) {
        withContext(Dispatchers.IO) {
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
                sortOrder = existing.normalizeSortOrder().sortOrder,
                modifiedAt = System.currentTimeMillis(),
                isDeleted = true
            )
            publish(list)
        }
    }

    suspend fun updateFavoriteMeta(
        id: Long,
        name: String,
        coverUrl: String?,
        trackCount: Int,
        source: String,
        songs: List<SongItem>
    ) {
        withContext(Dispatchers.IO) {
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
                songs = mergedSongs,
                sortOrder = existing.normalizeSortOrder().sortOrder,
                modifiedAt = System.currentTimeMillis(),
                isDeleted = false
            )
            publish(list)
        }
    }

    suspend fun reorderFavorites(newOrder: List<String>) {
        withContext(Dispatchers.IO) {
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

    suspend fun replaceFavoritesFromSync(favorites: List<FavoritePlaylist>) {
        withContext(Dispatchers.IO) {
            publish(favorites, triggerSync = false)
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
