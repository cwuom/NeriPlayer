package moe.ouom.neriplayer.data

import android.content.Context
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.LanguageManager

object LocalFilesPlaylist {
    const val SYSTEM_ID = -1002L

    private const val CANONICAL_ZH_NAME = "本地文件"
    private const val CANONICAL_EN_NAME = "Local Files"

    fun currentName(context: Context): String {
        val localizedContext = LanguageManager.applyLanguage(context)
        return localizedContext.getString(R.string.local_files)
    }

    fun candidateNames(context: Context? = null): Set<String> {
        return buildSystemPlaylistCandidateNames(
            canonicalChineseName = CANONICAL_ZH_NAME,
            canonicalEnglishName = CANONICAL_EN_NAME,
            localizedName = context?.let(::currentName) ?: CANONICAL_ZH_NAME
        )
    }

    fun matches(name: String?, context: Context? = null): Boolean {
        if (name.isNullOrBlank()) return false
        return candidateNames(context).any { it.equals(name, ignoreCase = true) }
    }

    fun firstOrNull(playlists: List<LocalPlaylist>, context: Context? = null): LocalPlaylist? {
        return playlists.firstOrNull { it.id == SYSTEM_ID || matches(it.name, context) }
    }

    fun isSystemPlaylist(playlist: LocalPlaylist, context: Context): Boolean {
        return playlist.id == SYSTEM_ID || matches(playlist.name, context)
    }

    fun merge(playlists: List<LocalPlaylist>, context: Context): LocalPlaylist {
        return LocalPlaylist(
            id = SYSTEM_ID,
            name = currentName(context),
            songs = playlists
                .flatMap { it.songs }
                .distinctBy { it.identity() }
                .toMutableList(),
            modifiedAt = playlists.maxOfOrNull { it.modifiedAt } ?: System.currentTimeMillis(),
            customCoverUrl = playlists.lastOrNull { !it.customCoverUrl.isNullOrBlank() }?.customCoverUrl
        )
    }
}
