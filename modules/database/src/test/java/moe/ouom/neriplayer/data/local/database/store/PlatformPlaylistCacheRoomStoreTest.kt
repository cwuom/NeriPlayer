package moe.ouom.neriplayer.data.local.database.store

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.PlatformPlaylistCacheDao
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackArtistEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings

class PlatformPlaylistCacheRoomStoreTest {
    private val dao = InMemoryPlatformPlaylistCacheDao()
    private val sqlite = ScriptedSqlite()
    private val database: NeriUserDataDatabase = mock(
        NeriUserDataDatabase::class.java,
        withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS)
    )
    private val store = PlatformPlaylistCacheRoomStore(database)

    init {
        doReturn(Executor { it.run() }).`when`(database).transactionExecutor
        @Suppress("DEPRECATION")
        run {
            doAnswer { null }.`when`(database).beginTransaction()
            doAnswer { null }.`when`(database).setTransactionSuccessful()
            doAnswer { null }.`when`(database).endTransaction()
        }
        doReturn(dao).`when`(database).platformPlaylistCacheDao()
        val helper = mock(SupportSQLiteOpenHelper::class.java)
        doReturn(sqlite.database).`when`(helper).readableDatabase
        doReturn(helper).`when`(database).openHelper
    }

    private val record = PlatformPlaylistCacheRecord(
        platform = "netease", cacheKey = "daily", sourceId = 7L, title = "Daily", hasMore = true, savedAtMs = 100L,
        tracks = listOf(
            PlatformPlaylistCacheTrackRecord(itemId = 1L, name = "A", artist = "X / Y", artists = listOf(
                PlatformPlaylistCacheArtistRecord(10L, "X"), PlatformPlaylistCacheArtistRecord(11L, "Y")
            )),
            PlatformPlaylistCacheTrackRecord(itemKey = "b", name = "B", artist = "Z", durationMs = 3_000L)
        )
    )

    @Test fun `a replaced cache reads back with tracks and their artists in order`() = runTest {
        store.replace(record)

        assertEquals(record, store.read("netease", "daily"))
        assertEquals(listOf(0 to 0, 0 to 1), dao.artists.map { it.trackPosition to it.artistPosition })
        assertNull(store.read("netease", "missing"))
    }

    @Test fun `replace if newer keeps a cache saved later than the incoming copy`() = runTest {
        store.replace(record)

        store.replaceIfNewer(record.copy(title = "stale", savedAtMs = 99L, tracks = emptyList()))
        assertEquals(record, store.read("netease", "daily"))

        val refreshed = record.copy(title = "fresh", savedAtMs = 100L, tracks = record.tracks.take(1))
        store.replaceIfNewer(refreshed)
        assertEquals(refreshed, store.read("netease", "daily"))

        store.replaceIfNewer(record.copy(cacheKey = "new", savedAtMs = 1L))
        assertEquals(1L, store.read("netease", "new")?.savedAtMs)
    }

    @Test fun `storage stats split measured pages by payload share`() {
        sqlite.counts = listOf("bili" to 1L, "netease" to 2L)
        sqlite.payloads = listOf(listOf("netease" to 100L, "bili" to 40L), listOf("netease" to 50L), listOf("netease" to 10L))
        sqlite.pageBytes = 8_192L

        val stats = store.storageStats(listOf("netease", "bili", "netease", "youtube"))

        assertEquals(listOf("netease", "bili", "youtube"), stats.keys.toList())
        assertEquals(PlatformPlaylistCacheStorageStats(2, 6_554L), stats["netease"])
        assertEquals(PlatformPlaylistCacheStorageStats(1, 1_638L), stats["bili"])
        assertEquals(PlatformPlaylistCacheStorageStats(0, 0L), stats["youtube"])
    }

    @Test fun `storage stats estimate whole pages when dbstat is unavailable or empty`() {
        sqlite.counts = listOf("netease" to 3L)
        sqlite.payloads = listOf(listOf("netease" to 160L, "bili" to 40L), emptyList(), emptyList())
        sqlite.pageBytes = null

        assertEquals(PlatformPlaylistCacheStorageStats(3, 3_277L), store.storageStats(listOf("netease"))["netease"])

        sqlite.pageBytes = 0L
        sqlite.payloads = listOf(listOf("netease" to 5_000L), emptyList(), emptyList())
        assertEquals(PlatformPlaylistCacheStorageStats(3, 8_192L), store.storageStats(listOf("netease"))["netease"])
    }

    @Test fun `storage stats report nothing allocated without payload`() {
        sqlite.counts = listOf("netease" to 1L)
        sqlite.payloads = listOf(listOf("netease" to 0L), emptyList(), emptyList())

        sqlite.pageBytes = 4_096L
        assertEquals(PlatformPlaylistCacheStorageStats(1, 0L), store.storageStats(listOf("netease"))["netease"])
        sqlite.pageBytes = null
        assertEquals(PlatformPlaylistCacheStorageStats(1, 0L), store.storageStats(listOf("netease"))["netease"])
        assertEquals(emptyMap<String, PlatformPlaylistCacheStorageStats>(), store.storageStats(emptyList()))
    }

    /** Answers the store's size queries by the table they read; dbstat is missing when [pageBytes] is null. */
    private class ScriptedSqlite {
        var counts = emptyList<Pair<String, Long>>()
        var payloads = listOf(emptyList<Pair<String, Long>>(), emptyList(), emptyList())
        var pageBytes: Long? = null

        val database: SupportSQLiteDatabase = mock(SupportSQLiteDatabase::class.java) { invocation ->
            val sql = invocation.arguments.firstOrNull() as? String ?: return@mock null
            when {
                "dbstat" in sql -> pageBytes?.let { cursor(listOf(listOf<Any>(it))) } ?: throw IllegalStateException("no dbstat")
                "page_size" in sql -> cursor(listOf(listOf<Any>(4_096L)))
                "COUNT(*)" in sql -> rows(counts)
                "platform_playlist_cache_track_artist" in sql -> rows(payloads[2])
                "platform_playlist_cache_track" in sql -> rows(payloads[1])
                else -> rows(payloads[0])
            }
        }

        private fun rows(values: List<Pair<String, Long>>) = cursor(values.map { (platform, value) -> listOf<Any>(platform, value) })

        private fun cursor(rows: List<List<Any>>): Cursor {
            var index = -1
            return mock(Cursor::class.java) { invocation ->
                when (invocation.method.name) {
                    "moveToNext" -> ++index < rows.size
                    "moveToFirst" -> { index = 0; rows.isNotEmpty() }
                    "getString" -> rows[index][invocation.getArgument(0)] as String
                    "getLong" -> rows[index][invocation.getArgument(0)] as Long
                    else -> null
                }
            }
        }
    }

    private class InMemoryPlatformPlaylistCacheDao : PlatformPlaylistCacheDao {
        val caches = linkedMapOf<Pair<String, String>, PlatformPlaylistCacheEntity>()
        val tracks = mutableListOf<PlatformPlaylistCacheTrackEntity>()
        val artists = mutableListOf<PlatformPlaylistCacheTrackArtistEntity>()

        override suspend fun getCache(platform: String, cacheKey: String) = caches[platform to cacheKey]
        override suspend fun getTracks(platform: String, cacheKey: String) =
            tracks.filter { it.platform == platform && it.cacheKey == cacheKey }.sortedBy { it.position }
        override suspend fun getArtists(platform: String, cacheKey: String) =
            artists.filter { it.platform == platform && it.cacheKey == cacheKey }
                .sortedWith(compareBy({ it.trackPosition }, { it.artistPosition }))
        override suspend fun upsertCache(cache: PlatformPlaylistCacheEntity) { caches[cache.platform to cache.cacheKey] = cache }
        override suspend fun insertTracks(tracks: List<PlatformPlaylistCacheTrackEntity>) { this.tracks += tracks }
        override suspend fun insertArtists(artists: List<PlatformPlaylistCacheTrackArtistEntity>) { this.artists += artists }
        override suspend fun deleteArtists(platform: String, cacheKey: String) {
            artists.removeAll { it.platform == platform && it.cacheKey == cacheKey }
        }
        override suspend fun deleteTracks(platform: String, cacheKey: String) {
            tracks.removeAll { it.platform == platform && it.cacheKey == cacheKey }
        }
        override suspend fun deleteCache(platform: String, cacheKey: String) { caches.remove(platform to cacheKey) }
        override suspend fun deleteArtistsForPlatforms(platforms: List<String>) { artists.removeAll { it.platform in platforms } }
        override suspend fun deleteTracksForPlatforms(platforms: List<String>) { tracks.removeAll { it.platform in platforms } }
        override suspend fun deleteCachesForPlatforms(platforms: List<String>) { caches.keys.removeAll { it.first in platforms } }
        override suspend fun countCachesForPlatforms(platforms: List<String>) = caches.keys.count { it.first in platforms }
    }
}
