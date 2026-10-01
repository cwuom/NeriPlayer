package moe.ouom.neriplayer.data.model.youtube.playback

enum class YouTubePlaybackSourcePreference(
    val storageValue: String
) {
    Automatic("automatic"),
    VisionOs("visionos"),
    AndroidVr("android_vr"),
    WebRemix("web_remix"),
    TvHtml5("tv_html5"),
    WebCreator("web_creator")
}
