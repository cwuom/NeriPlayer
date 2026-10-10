package moe.ouom.neriplayer.core.player.resolver.netease

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseAutoBiliMatchScoringTest {

    private val sunny = song(name = "晴天", artist = "周杰伦", durationMs = 269_000L)

    @Test
    fun `cache key falls back from stream id to quality tag to inferred quality`() {
        assertEquals(
            "bili-auto-BV1_x_y-42-id-30280",
            buildNeteaseAutoBiliCacheKey(" BV1 x/y ", 42L, stream(id = 30280))
        )
        assertEquals(
            "bili-auto-BV1abc-42-tag-dolby_atmos",
            buildNeteaseAutoBiliCacheKey("BV1abc", 42L, stream(qualityTag = " Dolby Atmos "))
        )
        assertEquals(
            "bili-auto-BV1abc-42-quality-high-200",
            buildNeteaseAutoBiliCacheKey("BV1abc", 42L, stream(qualityTag = "   "))
        )
        assertEquals(
            "bili-auto-unknown-7-quality-lossless-900",
            buildNeteaseAutoBiliCacheKey("%%%", 7L, stream(mimeType = "audio/flac", bitrateKbps = 900))
        )
    }

    @Test
    fun `exact title and artist hits dominate and duration closeness adds a bonus`() {
        assertEquals(
            55 + 25 + 30,
            scoreNeteaseAutoBiliText(sunny, "【无损】周杰伦 - 晴天 Hi-Res", "音乐搬运工", durationSec = 270)
        )
        assertEquals(
            55 + 25 + 12,
            scoreNeteaseAutoBiliText(sunny, "晴天 钢琴版", "周杰伦官方", durationSec = 300)
        )
        assertEquals(0, scoreNeteaseAutoBiliText(sunny, "Sunny Day (cover)", "someone", durationSec = 0))
    }

    @Test
    fun `original metadata is preferred over the localized song fields`() {
        val lemon = song(
            name = "柠檬",
            artist = "Kenshi Yonezu",
            durationMs = 255_000L,
            originalName = "Lemon",
            originalArtist = "米津玄師"
        )

        assertEquals(
            55 + 25 + 22,
            scoreNeteaseAutoBiliText(lemon, "Lemon 米津玄師 MV", "uploader", durationSec = 265)
        )
    }

    @Test
    fun `blank artist and unknown duration contribute nothing`() {
        val intro = song(name = "Intro", artist = "  ", durationMs = 0L)

        assertEquals(55, scoreNeteaseAutoBiliText(intro, "Intro", "any", durationSec = 100))
    }

    @Test
    fun `title tokens give partial credit when the full title is missing`() {
        val loveStory = song(name = "Love Story", artist = "Taylor Swift", durationMs = 235_000L)
        val shortTokens = song(name = "A B", artist = "X", durationMs = 0L)

        assertEquals(35, scoreNeteaseAutoBiliText(loveStory, "story of love", "fan", durationSec = 0))
        assertEquals(18, scoreNeteaseAutoBiliText(loveStory, "love me tender", "fan", durationSec = 0))
        assertEquals(0, scoreNeteaseAutoBiliText(shortTokens, "xyz", "x", durationSec = 0))
    }

    @Test
    fun `far longer uploads are penalised while other mismatches are neutral`() {
        val loveStory = song(name = "Love Story", artist = "Taylor Swift", durationMs = 235_000L)

        assertEquals(-15, scoreNeteaseAutoBiliText(loveStory, "unrelated", "fan", durationSec = 600))
        assertEquals(0, scoreNeteaseAutoBiliText(loveStory, "unrelated", "fan", durationSec = 300))
    }

    private fun song(
        name: String,
        artist: String,
        durationMs: Long,
        originalName: String? = null,
        originalArtist: String? = null
    ) = SongItem(
        id = 1L,
        name = name,
        artist = artist,
        album = "",
        albumId = 0L,
        durationMs = durationMs,
        coverUrl = null,
        originalName = originalName,
        originalArtist = originalArtist
    )

    private fun stream(
        id: Int? = null,
        qualityTag: String? = null,
        mimeType: String = "audio/mp4",
        bitrateKbps: Int = 200
    ) = BiliAudioStreamInfo(
        id = id,
        mimeType = mimeType,
        bitrateKbps = bitrateKbps,
        qualityTag = qualityTag,
        url = "https://upos.example.bilivideo.com/audio.m4s"
    )
}
