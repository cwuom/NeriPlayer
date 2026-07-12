package moe.ouom.neriplayer.util

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo

object SearchManager {
    suspend fun search(
        keyword: String,
        platform: MusicPlatform
    ): List<SongSearchInfo> {
        return moe.ouom.neriplayer.core.api.search.SearchManager.search(keyword, platform)
    }

    suspend fun findBestSearchCandidate(
        songName: String,
        songArtist: String
    ): SongSearchInfo? {
        return moe.ouom.neriplayer.core.api.search.SearchManager.findBestSearchCandidate(
            songName = songName,
            songArtist = songArtist
        )
    }
}
