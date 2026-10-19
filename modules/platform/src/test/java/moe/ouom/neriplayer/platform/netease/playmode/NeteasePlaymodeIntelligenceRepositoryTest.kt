package moe.ouom.neriplayer.platform.netease.playmode

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心动模式数据层仓库级单测。
 *
 * [moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient] 是 final class 且
 * `getPlaymodeIntelligenceListCancellable` 为阻塞的 suspend 函数，mockito 无法直接替身，
 * 因此这里注入 [NeteasePlaymodeIntelligenceApi] 假实现（测试接缝）。
 *
 * 覆盖重点：任务书 §2.6 的三条红线——
 *  1. 必须先用 [NeteasePlaymodeIntelligenceApi.likedPlaylistId] 拿到**真实**红心歌单 id，
 *     `playlistId = 0` 只是匿名探测占位值，两道守卫（api 实现层与仓库层）都不得放行；
 *  2. 未登录 / 无红心歌单 / 接口异常一律静默降级返回 null，绝不抛给 UI；
 *  3. 30s 短窗口缓存只防连点打爆接口，取消必须照常向上传播。
 */
class NeteasePlaymodeIntelligenceRepositoryTest {

    // ---------- 前置守卫：playlistId = 0 两道堵死 ----------

    @Test
    fun `未登录时不发起任何请求`() = runTest {
        val api = FakePlaymodeIntelligenceApi(hasLogin = false, userId = 0L)
        val repository = repository(api)

        val result = repository.fetchNextSong(SONG_ID)

        assertNull(result)
        assertFalse(api.isLoggedIn)
        assertTrue("未登录不得查询红心歌单", api.likedPlaylistIdRequests.isEmpty())
        assertTrue("未登录不得请求心动模式", api.loadIntelligenceRequests.isEmpty())
    }

    @Test
    fun `登录态取不到用户 id 时直接降级且不请求心动模式`() = runTest {
        // 未登录 / 会话失效时生产实现会把 userId 收敛成 0
        val api = FakePlaymodeIntelligenceApi(hasLogin = true, userId = 0L)
        val repository = repository(api)

        val result = repository.fetchNextSong(SONG_ID)

        assertNull(result)
        assertTrue(api.likedPlaylistIdRequests.isEmpty())
        assertTrue(api.loadIntelligenceRequests.isEmpty())
    }

    @Test
    fun `拿不到红心歌单 id 时不得用 0 占位去请求`() = runTest {
        val api = FakePlaymodeIntelligenceApi(userId = USER_ID, likedPlaylistId = null)
        val repository = repository(api)

        val result = repository.fetchNextSong(SONG_ID)

        assertNull(result)
        assertEquals(listOf(USER_ID), api.likedPlaylistIdRequests)
        assertTrue("没有真实红心歌单 id 就不得请求接口", api.loadIntelligenceRequests.isEmpty())
    }

    @Test
    fun `红心歌单 id 为 0 或负数时同样不得请求`() = runTest {
        // 第一道守卫在 NeteaseClientPlaymodeIntelligenceApi 里，这里是仓库层的第二道
        listOf(0L, -1L).forEach { invalidId ->
            val api = FakePlaymodeIntelligenceApi(userId = USER_ID, likedPlaylistId = invalidId)
            val repository = repository(api)

            val result = repository.fetchNextSong(SONG_ID)

            assertNull("playlistId=$invalidId 必须被拒绝", result)
            assertTrue(api.loadIntelligenceRequests.isEmpty())
        }
    }

    @Test
    fun `非正数的当前歌曲 id 不触达数据层`() = runTest {
        val api = FakePlaymodeIntelligenceApi(intelligence = { INTELLIGENCE_JSON })
        val repository = repository(api)

        listOf(0L, -7L).forEach { invalidSongId ->
            assertNull(repository.fetchNextSong(invalidSongId))
        }

        assertTrue(api.likedPlaylistIdRequests.isEmpty())
        assertTrue(api.loadIntelligenceRequests.isEmpty())
    }

    // ---------- 正常路径 ----------

    @Test
    fun `正常路径返回官方推荐歌曲并按 netease 字段映射`() = runTest {
        val api = FakePlaymodeIntelligenceApi(intelligence = { INTELLIGENCE_JSON })
        val repository = repository(api)

        val song = repository.fetchNextSong(SONG_ID)

        assertTrue(song is SongItem)
        song as SongItem
        assertEquals(SONG_ID_ECHO, song.id)
        assertEquals("Demo Song", song.name)
        assertEquals("Artist A / Artist B", song.artist)
        assertEquals("Demo Album", song.album)
        assertEquals(99L, song.albumId)
        assertEquals(253000L, song.durationMs)
        assertEquals("https://example.test/cover.jpg", song.coverUrl)
        assertEquals("netease", song.channelId)
        assertEquals("1901371647", song.audioId)
        assertEquals(listOf("Artist A", "Artist B"), song.neteaseArtists?.map { it.name })

        // 请求参数必须来自真实红心歌单 id，且 startMusicId 与 songId 一致
        assertEquals(
            listOf(IntelligenceRequest(SONG_ID, PLAYLIST_ID, SONG_ID)),
            api.loadIntelligenceRequests
        )
    }

    @Test
    fun `包装形状 data songs 与直接数组同样可用`() = runTest {
        val api = FakePlaymodeIntelligenceApi(
            intelligence = { """{"code":200,"data":{"songs":$WRAPPED_SONGS}}""" }
        )
        val repository = repository(api)

        val song = repository.fetchNextSong(SONG_ID)

        assertEquals(42L, song?.id)
        assertEquals("Wrapped", song?.name)
    }

    @Test
    fun `nextSong 是 fetchNextSong 的别名并复用同一份缓存`() = runTest {
        val api = FakePlaymodeIntelligenceApi(intelligence = { INTELLIGENCE_JSON })
        val repository = repository(api)

        val viaFetch = repository.fetchNextSong(SONG_ID)
        val viaNextSong = repository.nextSong(SONG_ID)

        assertEquals(viaFetch, viaNextSong)
        assertEquals(1, api.loadIntelligenceRequests.size)
    }

    @Test
    fun `登录态查询委托给 api 的 hasLogin`() {
        val repository = repository(
            FakePlaymodeIntelligenceApi(
                hasLogin = false,
                intelligence = { INTELLIGENCE_JSON }
            )
        )

        assertFalse(repository.isLoggedIn())
        assertFalse(repository.isOfficialAvailable())
    }

    // ---------- 静默降级 ----------

    @Test
    fun `接口返回未登录 code 301 时静默降级且不视为错误`() = runTest {
        val api = FakePlaymodeIntelligenceApi(
            intelligence = { """{"code":301,"message":null}""" }
        )
        val repository = repository(api)

        assertNull(repository.fetchNextSong(SONG_ID))
        assertEquals(1, api.loadIntelligenceRequests.size)
    }

    @Test
    fun `空 data 与业务错误码都静默降级`() = runTest {
        listOf(
            """{"code":200,"data":[]}""",
            """{"code":200}""",
            """{"code":400,"message":"bad request"}""",
            """{"code":500,"message":"internal server error"}""",
            "not a json at all",
            ""
        ).forEach { raw ->
            val api = FakePlaymodeIntelligenceApi(intelligence = { raw })
            val repository = repository(api)

            assertNull("raw=$raw 必须降级", repository.fetchNextSong(SONG_ID))
        }
    }

    @Test
    fun `失败结果不入缓存因此不会污染下一次尝试`() = runTest {
        var now = 0L
        val api = FakePlaymodeIntelligenceApi(
            intelligence = { """{"code":301,"message":null}""" }
        )
        val repository = repository(api) { now }

        repository.fetchNextSong(SONG_ID)
        val second = repository.fetchNextSong(SONG_ID)

        assertNull(second)
        assertEquals(2, api.loadIntelligenceRequests.size)
    }

    // ---------- 异常与取消 ----------

    @Test
    fun `网络异常被收敛为 null 而不是抛给调用方`() = runTest {
        val api = FakePlaymodeIntelligenceApi(
            intelligence = { throw IOException("interface.music.163.com 连接失败") }
        )
        val repository = repository(api)

        assertNull(repository.fetchNextSong(SONG_ID))
        assertEquals(1, api.loadIntelligenceRequests.size)
    }

    @Test
    fun `非 IO 的业务异常同样收敛为 null`() = runTest {
        val api = FakePlaymodeIntelligenceApi(
            intelligence = { throw IllegalStateException("cookie 解析失败") }
        )
        val repository = repository(api)

        assertNull(repository.fetchNextSong(SONG_ID))
    }

    @Test
    fun `取消异常必须向上传播不能被吞掉`() = runTest {
        val api = FakePlaymodeIntelligenceApi(
            intelligence = { throw CancellationException("切歌取消") }
        )
        val repository = repository(api)

        val failure = runCatching { repository.fetchNextSong(SONG_ID) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals("切歌取消", failure?.message)
    }

    // ---------- 30s 缓存 ----------

    @Test
    fun `同一首歌在缓存有效期内只请求一次`() = runTest {
        var now = 0L
        val api = FakePlaymodeIntelligenceApi(intelligence = { INTELLIGENCE_JSON })
        val repository = repository(api) { now }

        val first = repository.fetchNextSong(SONG_ID)
        now = 15_000L
        val second = repository.fetchNextSong(SONG_ID)

        assertEquals(first, second)
        assertEquals(1, api.loadIntelligenceRequests.size)
    }

    @Test
    fun `缓存到达 30 秒边界仍算命中超过 1 毫秒才重新请求`() = runTest {
        var now = 0L
        val api = FakePlaymodeIntelligenceApi(intelligence = { INTELLIGENCE_JSON })
        val repository = repository(api) { now }

        repository.fetchNextSong(SONG_ID)
        now = CACHE_TTL_MS
        repository.fetchNextSong(SONG_ID)
        assertEquals("ttl 边界仍算命中", 1, api.loadIntelligenceRequests.size)

        now = CACHE_TTL_MS + 1L
        repository.fetchNextSong(SONG_ID)
        assertEquals(2, api.loadIntelligenceRequests.size)
    }

    @Test
    fun `不同歌曲各走一次请求互不复用`() = runTest {
        val api = FakePlaymodeIntelligenceApi(intelligence = { INTELLIGENCE_JSON })
        val repository = repository(api)

        repository.fetchNextSong(101L)
        repository.fetchNextSong(102L)
        repository.fetchNextSong(101L)

        assertEquals(
            listOf(
                IntelligenceRequest(101L, PLAYLIST_ID, 101L),
                IntelligenceRequest(102L, PLAYLIST_ID, 102L)
            ),
            api.loadIntelligenceRequests
        )
    }

    @Test
    fun `缓存条数超过上限后最旧的条目被淘汰`() = runTest {
        var now = 0L
        val api = FakePlaymodeIntelligenceApi(intelligence = { INTELLIGENCE_JSON })
        val repository = repository(api) { now }

        // 先灌满 MAX_CACHE_ENTRIES 条，再多请求第 9 条把第 1 条挤出去
        for (songId in 1L..QUEUE_LENGTH + 1L) {
            repository.fetchNextSong(songId)
            now += 1_000L
        }
        assertEquals(QUEUE_LENGTH + 1, api.loadIntelligenceRequests.size)

        // 最后一首仍在缓存内，必须命中
        now += 1_000L
        repository.fetchNextSong(QUEUE_LENGTH + 1L)
        assertEquals("最新的条目应当仍在缓存", QUEUE_LENGTH + 1, api.loadIntelligenceRequests.size)

        // 最先插入的第 1 首已被淘汰，必须重新请求
        now += 1_000L
        repository.fetchNextSong(1L)
        assertEquals(
            "最旧的条目应当已被淘汰",
            QUEUE_LENGTH + 2,
            api.loadIntelligenceRequests.size
        )
    }

    // ---------- helpers ----------

    private fun repository(
        api: NeteasePlaymodeIntelligenceApi,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ) = NeteasePlaymodeIntelligenceRepository(api = api, nowMs = nowMs)

    private class FakePlaymodeIntelligenceApi(
        private val hasLogin: Boolean = true,
        private val userId: Long = USER_ID,
        private val likedPlaylistId: Long? = PLAYLIST_ID,
        // 未注入时一旦真的发出请求就立刻炸，用来钉住「守卫必须先于请求」
        private val intelligence: suspend () -> String = { error("intelligence 不得被调用") },
    ) : NeteasePlaymodeIntelligenceApi {

        val isLoggedIn: Boolean get() = hasLogin
        val likedPlaylistIdRequests = mutableListOf<Long>()
        val loadIntelligenceRequests = mutableListOf<IntelligenceRequest>()

        override fun hasLogin(): Boolean = hasLogin

        override fun currentUserId(): Long = userId

        override fun likedPlaylistId(userId: Long): Long? {
            likedPlaylistIdRequests += userId
            return likedPlaylistId
        }

        override suspend fun loadIntelligence(
            songId: Long,
            playlistId: Long,
            startMusicId: Long,
        ): String {
            loadIntelligenceRequests += IntelligenceRequest(songId, playlistId, startMusicId)
            return intelligence()
        }
    }

    private data class IntelligenceRequest(
        val songId: Long,
        val playlistId: Long,
        val startMusicId: Long,
    )

    private companion object {
        const val USER_ID = 1234L
        const val PLAYLIST_ID = 8801L
        const val SONG_ID = 1901371647L
        const val SONG_ID_ECHO = 1901371647L
        const val CACHE_TTL_MS = 30_000L
        const val QUEUE_LENGTH = 8

        val INTELLIGENCE_JSON = """
            {"code":200,"data":[
              {
                "id": 1901371647,
                "name": "Demo Song",
                "dt": 253000,
                "ar": [
                  {"id": 1, "name": "Artist A"},
                  {"id": 2, "name": "Artist B"}
                ],
                "al": {
                  "id": 99,
                  "name": "Demo Album",
                  "picUrl": "http://example.test/cover.jpg"
                }
              }
            ]}
        """.trimIndent()

        val WRAPPED_SONGS = """
            [
              {"id": 42, "name": "Wrapped", "dt": 1000, "ar": [], "al": {"id": 7, "name": "A"}}
            ]
        """.trimIndent()
    }
}
