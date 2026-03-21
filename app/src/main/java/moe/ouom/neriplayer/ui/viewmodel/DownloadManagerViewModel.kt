package moe.ouom.neriplayer.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

class DownloadManagerViewModel(application: Application) : AndroidViewModel(application) {

    val downloadedSongs = GlobalDownloadManager.downloadedSongs
    val isRefreshing = GlobalDownloadManager.isRefreshing

    fun refreshDownloadedSongs() {
        val appContext = getApplication<Application>()
        GlobalDownloadManager.scanLocalFiles(appContext)
    }

    fun deleteDownloadedSong(song: DownloadedSong) {
        val appContext = getApplication<Application>()
        GlobalDownloadManager.deleteDownloadedSong(appContext, song)
    }

    fun playDownloadedSong(song: DownloadedSong) {
        GlobalDownloadManager.playDownloadedSong(song)
    }

    fun startBatchDownload(context: Context, songs: List<SongItem>) {
        GlobalDownloadManager.startBatchDownload(context, songs)
    }
}
