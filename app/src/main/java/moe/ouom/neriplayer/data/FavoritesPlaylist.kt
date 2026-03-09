package moe.ouom.neriplayer.data

import android.content.Context
import moe.ouom.neriplayer.R

object FavoritesPlaylist {
    const val SYSTEM_ID = -1001L

    private const val LEGACY_ZH_NAME = "我喜欢的音乐"
    private const val LEGACY_EN_NAME = "My Favorite Music"

    fun currentName(context: Context): String = context.getString(R.string.favorite_my_music)

    fun candidateNames(context: Context? = null): Set<String> {
        return buildSet {
            add(LEGACY_ZH_NAME)
            add(LEGACY_EN_NAME)
            context?.let { add(currentName(it)) }
        }
    }

    fun matches(name: String?, context: Context? = null): Boolean {
        if (name.isNullOrBlank()) return false
        return name in candidateNames(context)
    }

    fun firstOrNull(playlists: List<LocalPlaylist>, context: Context? = null): LocalPlaylist? {
        return playlists.firstOrNull { it.id == SYSTEM_ID || matches(it.name, context) }
    }

    fun isSystemPlaylist(playlist: LocalPlaylist, context: Context): Boolean {
        return playlist.id == SYSTEM_ID || matches(playlist.name, context)
    }

    fun normalize(playlists: List<LocalPlaylist>, context: Context): List<LocalPlaylist> {
        val favorites = playlists.filter { isSystemPlaylist(it, context) }
        val others = playlists.filterNot { isSystemPlaylist(it, context) }

        val mergedFavorites = LocalPlaylist(
            id = SYSTEM_ID,
            name = currentName(context),
            songs = favorites
                .flatMap { it.songs }
                .distinctBy { it.identity() }
                .toMutableList(),
            modifiedAt = favorites.maxOfOrNull { it.modifiedAt } ?: System.currentTimeMillis(),
            customCoverUrl = favorites.lastOrNull { !it.customCoverUrl.isNullOrBlank() }?.customCoverUrl
        )

        return buildList {
            add(mergedFavorites)
            addAll(others)
        }
    }
}
