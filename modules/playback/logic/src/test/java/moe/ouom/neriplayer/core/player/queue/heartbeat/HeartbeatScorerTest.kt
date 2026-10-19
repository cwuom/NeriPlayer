package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地降级评分器测试（任务书 §5.1）。
 *
 * 覆盖：评分公式各维度、同歌手/同专辑加分、短音频降权、
 * 已在队列中的歌曲排除、当前歌曲自身排除、无曲风数据时正常降级。
 */
class HeartbeatScorerTest {

    private val current = song(id = 1, name = "当前", artist = "陈洁仪", album = "重译", albumId = 10)

    // ---- 基础评分 ----

    @Test
    fun `higher tier always outranks lower tier`() {
        val favorite = candidate(song(2, "收藏"), tier = HeartbeatCandidateTier.FAVORITE)
        val history = candidate(song(3, "历史"), tier = HeartbeatCandidateTier.HISTORY)

        assertTrue(
            "收藏层级分数必须高于历史层级",
            HeartbeatScorer.score(favorite, current) > HeartbeatScorer.score(history, current),
        )
    }

    @Test
    fun `tier base score decays monotonically`() {
        val scores = (HeartbeatCandidateTier.FAVORITE..HeartbeatCandidateTier.RANDOM).map { tier ->
            HeartbeatScorer.score(candidate(song(9, "x"), tier = tier), null)
        }
        assertEquals("四个层级分数应严格递减", scores.sortedDescending(), scores)
        assertEquals(scores.distinct().size, scores.size)
    }

    @Test
    fun `same artist candidate receives bonus`() {
        val sameArtist = candidate(song(2, "同歌手", artist = "陈洁仪"), tier = HeartbeatCandidateTier.FAVORITE)
        val otherArtist = candidate(song(3, "别歌手", artist = "别人"), tier = HeartbeatCandidateTier.FAVORITE)

        assertTrue(
            HeartbeatScorer.score(sameArtist, current) > HeartbeatScorer.score(otherArtist, current),
        )
    }

    @Test
    fun `same album candidate receives bonus`() {
        val sameAlbum = candidate(song(2, "同专辑", artist = "别人", album = "重译", albumId = 10), tier = HeartbeatCandidateTier.FAVORITE)
        val otherAlbum = candidate(song(3, "别专辑", artist = "别人", album = "其它", albumId = 11), tier = HeartbeatCandidateTier.FAVORITE)

        assertTrue(
            HeartbeatScorer.score(sameAlbum, current) > HeartbeatScorer.score(otherAlbum, current),
        )
    }

    @Test
    fun `album bonus applies when albumId matches even if album name differs`() {
        val byId = candidate(song(2, "同专辑", artist = "别人", album = "译名不同", albumId = 10), tier = HeartbeatCandidateTier.FAVORITE)
        val noMatch = candidate(song(3, "别专辑", artist = "别人", album = "其它", albumId = 11), tier = HeartbeatCandidateTier.FAVORITE)

        assertTrue(HeartbeatScorer.score(byId, current) > HeartbeatScorer.score(noMatch, current))
    }

    @Test
    fun `recently played candidate is penalized`() {
        val fresh = candidate(song(2, "新鲜"), tier = HeartbeatCandidateTier.FAVORITE, recentlyPlayed = false)
        val played = candidate(song(3, "刚播过"), tier = HeartbeatCandidateTier.FAVORITE, recentlyPlayed = true)

        assertTrue(HeartbeatScorer.score(fresh, current) > HeartbeatScorer.score(played, current))
    }

    @Test
    fun `very short tracks are penalized`() {
        val normal = candidate(song(2, "正常", durationMs = 200_000L), tier = HeartbeatCandidateTier.FAVORITE)
        val short = candidate(song(3, "短音频", durationMs = 15_000L), tier = HeartbeatCandidateTier.FAVORITE)

        assertTrue(HeartbeatScorer.score(normal, current) > HeartbeatScorer.score(short, current))
    }

    @Test
    fun `zero weight candidate scores zero`() {
        val zero = candidate(song(2, "零权重"), tier = HeartbeatCandidateTier.FAVORITE, weight = 0.0)
        assertEquals(0.0, HeartbeatScorer.score(zero, current), 0.0001)
    }

    // ---- 挑选行为 ----

    @Test
    fun `pickBest never returns the current song`() {
        val pool = poolOf(candidate(current, tier = HeartbeatCandidateTier.FAVORITE))
        assertNull(HeartbeatScorer.pickBest(pool, current, emptyList()))
    }

    @Test
    fun `pickBest excludes songs already in the queue`() {
        val queued = song(2, "已在队列")
        val pool = poolOf(
            candidate(queued, tier = HeartbeatCandidateTier.FAVORITE),
            candidate(song(3, "可用"), tier = HeartbeatCandidateTier.HISTORY),
        )

        val picked = HeartbeatScorer.pickBest(pool, current, listOf(queued))
        assertEquals(3L, picked?.id)
    }

    @Test
    fun `pickBest returns null for empty pool`() {
        assertNull(HeartbeatScorer.pickBest(HeartbeatCandidatePool.EMPTY, current, emptyList()))
    }

    @Test
    fun `pickBest prefers higher tier`() {
        val pool = poolOf(
            candidate(song(2, "历史"), tier = HeartbeatCandidateTier.HISTORY),
            candidate(song(3, "收藏"), tier = HeartbeatCandidateTier.FAVORITE),
        )

        assertEquals(3L, HeartbeatScorer.pickBest(pool, current, emptyList())?.id)
    }

    @Test
    fun `pickBest is stable when scores tie`() {
        val first = song(2, "先")
        val second = song(3, "后")
        val pool = poolOf(
            candidate(first, tier = HeartbeatCandidateTier.FAVORITE),
            candidate(second, tier = HeartbeatCandidateTier.FAVORITE),
        )

        // 同分时取候选池顺序靠前者，保证结果可复现
        assertEquals(first.id, HeartbeatScorer.pickBest(pool, current, emptyList())?.id)
        assertEquals(first.id, HeartbeatScorer.pickBest(pool, current, emptyList())?.id)
    }

    @Test
    fun `pickBest works without metadata such as genre or bpm`() {
        // 任务书要求：缺少曲风/BPM 等元数据时算法正常降级
        val plain = SongItem(
            id = 7L,
            name = "无元数据",
            artist = "",
            album = "",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
        )
        val pool = poolOf(candidate(plain, tier = HeartbeatCandidateTier.HISTORY))

        assertNotNull(HeartbeatScorer.pickBest(pool, current, emptyList()))
    }

    // ---- 身份键 ----

    @Test
    fun `identity key prefers sourceStableKey`() {
        val withKey = song(2, "x").copy(sourceStableKey = "local:/music/a.flac")
        assertEquals("local:/music/a.flac", HeartbeatScorer.identityKey(withKey))
    }

    @Test
    fun `identity key falls back to id then to name and artist`() {
        assertEquals(":42", HeartbeatScorer.identityKey(song(42, "x")))

        val noId = song(0, "Name").copy(artist = "Artist")
        assertEquals("name|artist", HeartbeatScorer.identityKey(noId))
    }

    // ---- 助手 ----

    private fun candidate(
        song: SongItem,
        tier: Int = HeartbeatCandidateTier.FAVORITE,
        weight: Double = 1.0,
        recentlyPlayed: Boolean = false,
    ) = HeartbeatCandidate(song = song, tier = tier, weight = weight, recentlyPlayed = recentlyPlayed)

    private fun poolOf(vararg candidates: HeartbeatCandidate): HeartbeatCandidatePool =
        HeartbeatCandidatePool(candidates.groupBy { it.tier })

    private fun song(
        id: Long,
        name: String,
        artist: String = "歌手$id",
        album: String = "专辑$id",
        albumId: Long = id,
        durationMs: Long = 180_000L,
    ) = SongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = null,
    )
}
