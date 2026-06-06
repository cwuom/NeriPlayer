package moe.ouom.neriplayer.ui.viewmodel

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
 * File: moe.ouom.neriplayer.ui.viewmodel/DownloadManagerViewModel
 * Updated: 2026/3/23
 */


import android.app.Application
import androidx.lifecycle.AndroidViewModel
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.GlobalDownloadManager

class DownloadManagerViewModel(application: Application) : AndroidViewModel(application) {

    val downloadedSongs = GlobalDownloadManager.downloadedSongs
    val isRefreshing = GlobalDownloadManager.isRefreshing

    fun refreshDownloadedSongs() {
        val appContext = getApplication<Application>()
        GlobalDownloadManager.scanLocalFiles(appContext, forceRefresh = true)
    }

    fun deleteDownloadedSong(song: DownloadedSong) {
        val appContext = getApplication<Application>()
        GlobalDownloadManager.deleteDownloadedSong(appContext, song)
    }

    fun deleteDownloadedSongs(songs: List<DownloadedSong>) {
        val appContext = getApplication<Application>()
        GlobalDownloadManager.deleteDownloadedSongs(appContext, songs)
    }

    fun playDownloadedSong(song: DownloadedSong) {
        val appContext = getApplication<Application>()
        GlobalDownloadManager.playDownloadedSong(appContext, song)
    }
}
