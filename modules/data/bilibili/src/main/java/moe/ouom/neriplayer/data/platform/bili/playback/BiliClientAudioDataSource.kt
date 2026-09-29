package moe.ouom.neriplayer.data.platform.bili.playback

import moe.ouom.neriplayer.api.bilibili.client.BiliClient
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.model.bilibili.playback.PlayOptions

/**
 * 适配器: 用 BiliClient 作为音频数据源, 接到 BiliPlaybackRepository
 */
class BiliClientAudioDataSource(
    override val client: BiliClient
) : BiliAudioDataSource {
    override suspend fun fetchAudioStreams(
        bvid: String,
        cid: Long
    ): List<BiliAudioStreamInfo> {
        return client.getAllAudioStreams(
            bvid = bvid,
            cid = cid,
            opts = PlayOptions()
        )
    }
}
