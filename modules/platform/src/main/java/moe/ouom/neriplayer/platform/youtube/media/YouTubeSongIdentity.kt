package moe.ouom.neriplayer.platform.youtube.media

import moe.ouom.neriplayer.platform.youtube.api.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.model.SongItem

fun isYouTubeMusicSong(song: SongItem): Boolean = extractYouTubeMusicVideoId(song.mediaUri) != null
