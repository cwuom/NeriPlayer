package moe.ouom.neriplayer.data.platform

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheArtistRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheTrackRecord
import moe.ouom.neriplayer.data.platform.bili.BiliArchiveCacheRepository
import moe.ouom.neriplayer.data.platform.bili.BiliArchiveContentCache
import moe.ouom.neriplayer.data.platform.bili.BiliFavoriteFolderCacheRepository
import moe.ouom.neriplayer.data.platform.bili.BiliFavoriteFolderContentCache
import moe.ouom.neriplayer.data.platform.bili.CachedBiliArchiveVideo
import moe.ouom.neriplayer.data.platform.bili.CachedBiliFavoriteVideo
import moe.ouom.neriplayer.data.platform.bili.biliArchiveCacheFileName
import moe.ouom.neriplayer.data.platform.netease.CachedNeteaseArtist
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistDetail
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistHeader
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistTrack
import moe.ouom.neriplayer.data.platform.netease.NeteasePlaylistCacheRepository
import moe.ouom.neriplayer.data.platform.youtube.CachedYouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.data.platform.youtube.CachedYouTubeMusicPlaylistTrack
import moe.ouom.neriplayer.data.platform.youtube.YouTubeMusicPlaylistCacheRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformPlaylistCacheRoomMigrationTest {
    private val gson = Gson()

    @Test
    fun roomStoreRoundTripsHeaderTracksAndArtists() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = inMemoryDatabase(context)
        try {
            val store = PlatformPlaylistCacheRoomStore(database)
            val record = PlatformPlaylistCacheRecord(
                platform = "netease",
                cacheKey = "42",
                sourceId = 42L,
                title = "daily",
                coverUrl = "https://img.example/cover.jpg",
                playCount = 1000L,
                trackCount = 1,
                totalCount = 1,
                signaturePrimary = "sig",
                savedAtMs = 123L,
                tracks = listOf(
                    PlatformPlaylistCacheTrackRecord(
                        itemId = 7L,
                        name = "song",
                        artist = "artist",
                        album = "album",
                        albumId = 9L,
                        durationMs = 180_000L,
                        coverUrl = "https://img.example/song.jpg",
                        audioId = "7",
                        artists = listOf(
                            PlatformPlaylistCacheArtistRecord(id = 1L, name = "artist")
                        )
                    )
                )
            )

            store.replace(record)

            assertEquals(record, store.read("netease", "42"))
        } finally {
            database.close()
        }
    }

    @Test
    fun repositoriesImportLegacyJsonAndDeleteFiles() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = inMemoryDatabase(context)
        val root = File(context.cacheDir, "platform-cache-room-${System.nanoTime()}")
        try {
            val neteaseDir = File(root, "netease_playlist_cache").apply { mkdirs() }
            val biliFavoriteDir = File(root, "bili_favorite_cache").apply { mkdirs() }
            val biliArchiveDir = File(root, "bili_archive_cache").apply { mkdirs() }
            val youtubeDir = File(root, "youtube_music_playlist_cache").apply { mkdirs() }

            val neteaseCache = neteaseCache()
            val biliFavoriteCache = biliFavoriteCache()
            val biliArchiveCache = biliArchiveCache()
            val youtubeCache = youtubeCache()

            val neteaseFile = File(neteaseDir, "playlist_${neteaseCache.playlistId}.json")
            val biliFavoriteFile = File(biliFavoriteDir, "media_${biliFavoriteCache.mediaId}.json")
            val biliArchiveFile = File(
                biliArchiveDir,
                biliArchiveCacheFileName(biliArchiveCache.mediaId, biliArchiveCache.kind)
            )
            val youtubeFile = File(youtubeDir, "${sha256(youtubeCache.browseId)}.json")

            neteaseFile.writeText(gson.toJson(neteaseCache), Charsets.UTF_8)
            biliFavoriteFile.writeText(gson.toJson(biliFavoriteCache), Charsets.UTF_8)
            biliArchiveFile.writeText(gson.toJson(biliArchiveCache), Charsets.UTF_8)
            youtubeFile.writeText(gson.toJson(youtubeCache), Charsets.UTF_8)

            val neteaseRepo = NeteasePlaylistCacheRepository(context, database, neteaseDir)
            val biliFavoriteRepo = BiliFavoriteFolderCacheRepository(
                context,
                database,
                biliFavoriteDir
            )
            val biliArchiveRepo = BiliArchiveCacheRepository(context, database, biliArchiveDir)
            val youtubeRepo = YouTubeMusicPlaylistCacheRepository(context, database, youtubeDir)

            neteaseRepo.importLegacyCaches()
            biliFavoriteRepo.importLegacyCaches()
            biliArchiveRepo.importLegacyCaches()
            youtubeRepo.importLegacyCaches()

            assertFalse(neteaseFile.exists())
            assertFalse(biliFavoriteFile.exists())
            assertFalse(biliArchiveFile.exists())
            assertFalse(youtubeFile.exists())
            assertEquals(neteaseCache, neteaseRepo.read(neteaseCache.playlistId))
            assertEquals(biliFavoriteCache, biliFavoriteRepo.read(biliFavoriteCache.mediaId))
            assertEquals(
                biliArchiveCache,
                biliArchiveRepo.read(biliArchiveCache.mediaId, biliArchiveCache.kind)
            )
            assertEquals(youtubeCache, youtubeRepo.read(youtubeCache.browseId))
            assertNotNull(database.platformPlaylistCacheDao().getCache("netease", "42"))

            neteaseRepo.clear(neteaseCache.playlistId)
            biliFavoriteRepo.clear(biliFavoriteCache.mediaId)
            biliArchiveRepo.clear(biliArchiveCache.mediaId, biliArchiveCache.kind)
            youtubeRepo.clear(youtubeCache.browseId)

            assertNull(neteaseRepo.read(neteaseCache.playlistId))
            assertNull(biliFavoriteRepo.read(biliFavoriteCache.mediaId))
            assertNull(biliArchiveRepo.read(biliArchiveCache.mediaId, biliArchiveCache.kind))
            assertNull(youtubeRepo.read(youtubeCache.browseId))
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyImportDeletesStaleJsonWithoutOverwritingNewerRoomRecord() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = inMemoryDatabase(context)
        val root = File(context.cacheDir, "platform-cache-stale-${System.nanoTime()}")
        try {
            val neteaseDir = File(root, "netease_playlist_cache").apply { mkdirs() }
            val repo = NeteasePlaylistCacheRepository(context, database, neteaseDir)
            val newerCache = neteaseCache(
                playlistName = "new room cache",
                savedAtMs = 500L
            )
            val staleCache = neteaseCache(
                playlistName = "stale json cache",
                savedAtMs = 100L
            )

            repo.save(newerCache)
            val staleFile = File(neteaseDir, "playlist_${staleCache.playlistId}.json")
            neteaseDir.mkdirs()
            staleFile.writeText(gson.toJson(staleCache), Charsets.UTF_8)

            repo.importLegacyCaches()

            assertFalse(staleFile.exists())
            assertEquals(newerCache, repo.read(newerCache.playlistId))
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun roomFailuresKeepLegacyCachesReadableAndRetained() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "platform-cache-failure-${System.nanoTime()}")
        try {
            val neteaseDir = File(root, "netease_playlist_cache").apply { mkdirs() }
            val biliFavoriteDir = File(root, "bili_favorite_cache").apply { mkdirs() }
            val biliArchiveDir = File(root, "bili_archive_cache").apply { mkdirs() }
            val youtubeDir = File(root, "youtube_music_playlist_cache").apply { mkdirs() }
            val neteaseCache = neteaseCache()
            val biliFavoriteCache = biliFavoriteCache()
            val biliArchiveCache = biliArchiveCache()
            val youtubeCache = youtubeCache()
            val neteaseFile = File(neteaseDir, "playlist_${neteaseCache.playlistId}.json")
            val biliFavoriteFile = File(biliFavoriteDir, "media_${biliFavoriteCache.mediaId}.json")
            val biliArchiveFile = File(
                biliArchiveDir,
                biliArchiveCacheFileName(biliArchiveCache.mediaId, biliArchiveCache.kind)
            )
            val youtubeFile = File(youtubeDir, "${sha256(youtubeCache.browseId)}.json")
            val failingStore = FailingPlatformPlaylistCacheStore()

            neteaseFile.writeText(gson.toJson(neteaseCache), Charsets.UTF_8)
            biliFavoriteFile.writeText(gson.toJson(biliFavoriteCache), Charsets.UTF_8)
            biliArchiveFile.writeText(gson.toJson(biliArchiveCache), Charsets.UTF_8)
            youtubeFile.writeText(gson.toJson(youtubeCache), Charsets.UTF_8)

            val neteaseRepo = NeteasePlaylistCacheRepository(failingStore, neteaseDir)
            val biliFavoriteRepo = BiliFavoriteFolderCacheRepository(failingStore, biliFavoriteDir)
            val biliArchiveRepo = BiliArchiveCacheRepository(failingStore, biliArchiveDir)
            val youtubeRepo = YouTubeMusicPlaylistCacheRepository(failingStore, youtubeDir)

            assertEquals(neteaseCache, neteaseRepo.read(neteaseCache.playlistId))
            assertEquals(biliFavoriteCache, biliFavoriteRepo.read(biliFavoriteCache.mediaId))
            assertEquals(
                biliArchiveCache,
                biliArchiveRepo.read(biliArchiveCache.mediaId, biliArchiveCache.kind)
            )
            assertEquals(youtubeCache, youtubeRepo.read(youtubeCache.browseId))
            assertTrue(neteaseFile.exists())
            assertTrue(biliFavoriteFile.exists())
            assertTrue(biliArchiveFile.exists())
            assertTrue(youtubeFile.exists())

            neteaseRepo.importLegacyCaches()
            biliFavoriteRepo.importLegacyCaches()
            biliArchiveRepo.importLegacyCaches()
            youtubeRepo.importLegacyCaches()

            assertTrue(neteaseFile.exists())
            assertTrue(biliFavoriteFile.exists())
            assertTrue(biliArchiveFile.exists())
            assertTrue(youtubeFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearDeletesLegacyCacheWhenRoomClearFails() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "platform-cache-clear-${System.nanoTime()}")
        try {
            val cache = neteaseCache()
            val cacheDir = File(root, "netease_playlist_cache").apply { mkdirs() }
            val legacyFile = File(cacheDir, "playlist_${cache.playlistId}.json")
            legacyFile.writeText(gson.toJson(cache), Charsets.UTF_8)

            NeteasePlaylistCacheRepository(
                FailingPlatformPlaylistCacheStore(failRead = false, failWrite = false),
                cacheDir
            ).clear(cache.playlistId)

            assertFalse(legacyFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun inMemoryDatabase(context: Context): NeriUserDataDatabase {
        return Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    private fun neteaseCache(
        playlistName: String = "NetEase Mix",
        savedAtMs: Long = 100L
    ): CachedNeteasePlaylistDetail {
        return CachedNeteasePlaylistDetail(
            playlistId = 42L,
            header = CachedNeteasePlaylistHeader(
                id = 42L,
                name = playlistName,
                coverUrl = "https://img.example/netease.jpg",
                playCount = 88L,
                trackCount = 1
            ),
            recentTrackSignature = "netease-sig",
            tracks = listOf(
                CachedNeteasePlaylistTrack(
                    id = 7L,
                    name = "N Song",
                    artist = "N Artist",
                    album = "N Album",
                    albumId = 9L,
                    durationMs = 200_000L,
                    coverUrl = "https://img.example/n-song.jpg",
                    audioId = "7",
                    artists = listOf(CachedNeteaseArtist(id = 1L, name = "N Artist")),
                    addedAt = 11L
                )
            ),
            savedAtMs = savedAtMs
        )
    }

    private fun biliFavoriteCache(): BiliFavoriteFolderContentCache {
        return BiliFavoriteFolderContentCache(
            mediaId = 55L,
            latestPageSignature = "bili-fav-sig",
            totalCount = 1,
            videos = listOf(
                CachedBiliFavoriteVideo(
                    id = 10L,
                    bvid = "BV1fav",
                    title = "B Favorite",
                    uploader = "UP",
                    uploaderMid = 100L,
                    coverUrl = "https://img.example/fav.jpg",
                    durationSec = 33
                )
            ),
            savedAtMs = 200L
        )
    }

    private fun biliArchiveCache(): BiliArchiveContentCache {
        return BiliArchiveContentCache(
            mediaId = 66L,
            kind = "COLLECTION",
            totalCount = 1,
            hasMore = true,
            videos = listOf(
                CachedBiliArchiveVideo(
                    id = 11L,
                    bvid = "BV1arc",
                    title = "B Archive",
                    uploader = "UP2",
                    uploaderMid = 101L,
                    coverUrl = "https://img.example/archive.jpg",
                    durationSec = 44
                )
            ),
            savedAtMs = 300L
        )
    }

    private fun youtubeCache(): CachedYouTubeMusicPlaylistDetail {
        return CachedYouTubeMusicPlaylistDetail(
            browseId = "VLPL42",
            playlistId = "PL42",
            title = "YouTube Mix",
            subtitle = "mix",
            creatorName = "creator",
            coverUrl = "https://img.example/youtube.jpg",
            trackCount = 1,
            firstPageSignature = "yt-sig",
            tracks = listOf(
                CachedYouTubeMusicPlaylistTrack(
                    videoId = "video-1",
                    name = "YT Song",
                    artist = "YT Artist",
                    albumName = "YT Album",
                    durationMs = 240_000L,
                    coverUrl = "https://img.example/yt-song.jpg"
                )
            ),
            savedAtMs = 400L
        )
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class FailingPlatformPlaylistCacheStore(
        private val failRead: Boolean = true,
        private val failWrite: Boolean = true
    ) : PlatformPlaylistCacheStore {
        override suspend fun read(
            platform: String,
            cacheKey: String
        ): PlatformPlaylistCacheRecord? {
            if (failRead) {
                throw IllegalStateException("Room read failure")
            }
            return null
        }

        override suspend fun replace(record: PlatformPlaylistCacheRecord) {
            if (failWrite) {
                throw IllegalStateException("Room replace failure")
            }
        }

        override suspend fun replaceIfNewer(record: PlatformPlaylistCacheRecord) {
            if (failWrite) {
                throw IllegalStateException("Room import failure")
            }
        }

        override suspend fun clear(platform: String, cacheKey: String) {
            throw IllegalStateException("Room clear failure")
        }
    }
}
