package moe.ouom.neriplayer.api.bilibili.model.playback

data class BiliAudioStreamInfo(
    val id: Int?,           // 30250(杜比) / 30251(Hi-Res) / 30280(192k)
    val mimeType: String,   // audio/eac3, audio/flac, audio/mp4 等
    val bitrateKbps: Int,   // 估算 kbps
    val qualityTag: String?,// "dolby" / "hires" / null
    val url: String,
    val candidateUrls: List<String> = listOf(url)
)
