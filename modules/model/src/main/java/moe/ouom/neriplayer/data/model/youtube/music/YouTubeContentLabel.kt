package moe.ouom.neriplayer.data.model.youtube.music

enum class YouTubeContentLabel(val explicit: Boolean, val musicVideo: Boolean, val cacheCode: Char) {
    EXPLICIT(true, false, 'e'),
    MUSIC_VIDEO(false, true, 'v'),
    EXPLICIT_MUSIC_VIDEO(true, true, 'b');

    companion object {
        fun fromName(name: String?): YouTubeContentLabel? = entries.firstOrNull { it.name == name }

        fun from(explicit: Boolean, musicVideo: Boolean): YouTubeContentLabel? = when {
            explicit && musicVideo -> EXPLICIT_MUSIC_VIDEO
            explicit -> EXPLICIT
            musicVideo -> MUSIC_VIDEO
            else -> null
        }
    }
}
