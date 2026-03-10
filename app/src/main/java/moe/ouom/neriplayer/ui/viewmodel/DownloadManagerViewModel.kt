package moe.ouom.neriplayer.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.LocalAudioImportManager
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

class DownloadManagerViewModel(application: Application) : AndroidViewModel(application) {

    val downloadedSongs = GlobalDownloadManager.downloadedSongs
    val isRefreshing = GlobalDownloadManager.isRefreshing
    private val playlistRepo = LocalPlaylistRepository.getInstance(application)

    fun refreshDownloadedSongs() {
        val appContext = getApplication<Application>()
        GlobalDownloadManager.scanLocalFiles(appContext)
    }

    fun deleteDownloadedSong(song: DownloadedSong) {
        val appContext = getApplication<Application>()
        GlobalDownloadManager.deleteDownloadedSong(appContext, song)
    }

    fun playDownloadedSong(context: Context, song: DownloadedSong) {
        GlobalDownloadManager.playDownloadedSong(context, song)
    }

    fun startBatchDownload(context: Context, songs: List<SongItem>) {
        GlobalDownloadManager.startBatchDownload(context, songs) { }
    }

    suspend fun scanLocalAudio(context: Context) =
        LocalAudioImportManager.scanDeviceSongs(context)

    suspend fun importLocalSongs(context: Context, songs: List<SongItem>): Long {
        if (songs.isEmpty()) return -1
        val targetName = context.getString(moe.ouom.neriplayer.R.string.local_files)
        val existing = playlistRepo.playlists.value.firstOrNull { it.name == targetName }
        val targetId = existing?.id ?: run {
            playlistRepo.createPlaylist(targetName)
            playlistRepo.playlists.value.firstOrNull { it.name == targetName }?.id
                ?: System.currentTimeMillis()
        }
        playlistRepo.addSongsToPlaylist(targetId, songs)
        return targetId
    }
}
