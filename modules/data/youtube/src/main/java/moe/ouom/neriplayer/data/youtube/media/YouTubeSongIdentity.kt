package moe.ouom.neriplayer.data.youtube.media

import moe.ouom.neriplayer.api.youtube.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.model.SongItem

fun isYouTubeMusicSong(song: SongItem): Boolean = extractYouTubeMusicVideoId(song.mediaUri) != null
