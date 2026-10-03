package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPlaybackRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlayHistoryRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlaylistUsageRoomStore
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlayBucket
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackBucket
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncRepositoryFallbackRoomTest {
    @Test
    fun historyFallbackRequiresDurableMarkerAcrossRetriesAndReopen() = runTest {
        Fixture().use { fixture ->
            val store = PlayHistoryRoomStore(fixture.database)
            val original = listOf(PlayedEntry(id = 1L, name = "original", artist = "artist", album = "netease",
                albumId = 1L, durationMs = 100L, coverUrl = null, playedAt = 100L))
            val replacement = listOf(original.single().copy(name = "remote", playedAt = 200))
            store.replaceAll(original)
            val repository = fixture.history(store)
            val epoch = SecureTokenStorage(fixture.context).getSyncMutationVersion()
            fixture.rejectWrites("play_history", PlayHistoryRoomStore.CUTOVER_STATE_METADATA_KEY)
            repeat(2) {
                assertNotNull(runCatching { repository.updateHistoryIfUnchanged(replacement, epoch) }.exceptionOrNull())
                assertEquals(original, repository.historyFlow.value)
                assertEquals(original, store.readIfRoomPrimary())
                assertEquals(original, fixture.history(store).historyFlow.value)
            }
            fixture.allowFallbackMarker()
            assertTrue(repository.updateHistoryIfUnchanged(replacement, epoch))
            assertEquals(replacement, repository.historyFlow.value)
            assertNull(store.readIfRoomPrimary())
            assertEquals(replacement, fixture.history(store).historyFlow.value)
            assertEquals(epoch, SecureTokenStorage(fixture.context).getSyncMutationVersion())
        }
    }

    @Test
    fun usageFallbackRequiresDurableMarkerAcrossRetriesAndReopen() = runTest {
        Fixture().use { fixture ->
            val store = PlaylistUsageRoomStore(fixture.database)
            val original = listOf(UsageEntry(1, "original", null, 3, "netease", 100, 1))
            val replacement = listOf(SyncPlaylistUsageStat("netease:1", "netease", 1, name = "remote", trackCount = 3, lastOpenedAt = 200, firstOpenedAt = 100, openCount = 4))
            store.replaceAll(original)
            val repository = fixture.usage(store)
            fixture.rejectWrites("playlist_usage", PlaylistUsageRoomStore.CUTOVER_STATE_METADATA_KEY)
            repeat(2) {
                assertNotNull(runCatching { repository.applyMergedStatsAndPersist(replacement) }.exceptionOrNull())
                assertEquals(original, repository.frequentPlaylistsFlow.value)
                assertEquals(original, store.readIfRoomPrimary())
                assertEquals(original, fixture.usage(store).frequentPlaylistsFlow.value)
            }
            fixture.allowFallbackMarker()
            repository.applyMergedStatsAndPersist(replacement)
            assertEquals("remote", repository.frequentPlaylistsFlow.value.single().name)
            assertEquals(4, repository.frequentPlaylistsFlow.value.single().openCount)
            assertNull(store.readIfRoomPrimary())
            assertEquals(repository.frequentPlaylistsFlow.value, fixture.usage(store).frequentPlaylistsFlow.value)
        }
    }

    @Test
    fun localPlaybackFallbackRetainsCountersAndBucketsAcrossRetriesAndReopen() = runTest {
        Fixture().use { fixture ->
            val store = LocalPlaylistPlaybackRoomStore(fixture.database)
            val day = 86_400_000L
            val original = listOf(LocalPlaylistPlaybackStat(
                playlistId = 1, totalPlayCount = 1, firstPlayedAt = day + 100, lastPlayedAt = day + 100,
                dailyPlayBuckets = listOf(LocalPlaylistPlayBucket(day, 1, day + 100, day + 100))
            ))
            val remote = listOf(SyncLocalPlaylistPlaybackStat(1, 4, day + 200, day + 100))
            val buckets = listOf(SyncLocalPlaylistPlaybackBucket(day, 1, 4, day + 200, day + 100))
            store.replaceAll(original)
            val repository = fixture.localPlayback(store)
            fixture.rejectWrites("local_playlist_playback_stat", LocalPlaylistPlaybackRoomStore.CUTOVER_STATE_METADATA_KEY)
            repeat(2) {
                assertNotNull(runCatching { repository.applyMergedStats(remote, buckets) }.exceptionOrNull())
                assertEquals(original, repository.statsFlow.value)
                assertEquals(original, store.readIfRoomPrimary())
                assertEquals(original, fixture.localPlayback(store).statsFlow.value)
            }
            fixture.allowFallbackMarker()
            repository.applyMergedStats(remote, buckets)
            assertEquals(4L, repository.statsFlow.value.single().totalPlayCount)
            assertEquals(4L, repository.statsFlow.value.single().dailyPlayBuckets.single().playCount)
            assertNull(store.readIfRoomPrimary())
            assertEquals(repository.statsFlow.value, fixture.localPlayback(store).statsFlow.value)
        }
    }

    private class Fixture : Closeable {
        private val base = ApplicationProvider.getApplicationContext<Context>()
        private val name = "sync-fallback-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, name).also { check(it.mkdirs()) }
        private val preferences = mutableSetOf<String>()
        private val scopes = mutableListOf<CoroutineScope>()
        val context: Context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getNoBackupFilesDir(): File = File(directory, "no-backup").also { check(it.isDirectory || it.mkdirs()) }
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
                val scoped = "$name-$key"
                preferences += scoped
                return super.getSharedPreferences(scoped, mode)
            }
        }
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        private val sqlite: SupportSQLiteDatabase get() = database.openHelper.writableDatabase

        fun rejectWrites(table: String, markerKey: String) {
            sqlite.execSQL("CREATE TRIGGER reject_sync_write BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, 'injected Room write failure'); END")
            sqlite.execSQL("CREATE TRIGGER reject_sync_marker BEFORE INSERT ON migration_metadata WHEN NEW.key = '$markerKey' AND NEW.value = 'legacy_json' BEGIN SELECT RAISE(ABORT, 'injected marker failure'); END")
        }
        fun allowFallbackMarker() = sqlite.execSQL("DROP TRIGGER reject_sync_marker")

        fun history(store: PlayHistoryRoomStore): PlayHistoryRepository {
            val constructor = PlayHistoryRepository::class.java.getDeclaredConstructor(Context::class.java, PlayHistoryRoomStore::class.java)
            constructor.isAccessible = true
            return constructor.newInstance(context, store).also { keepScope(it, "scope") }
        }
        fun usage(store: PlaylistUsageRoomStore): PlaylistUsageRepository = PlaylistUsageRepository(context, store)
            .also { keepScope(it, "scope") }
        fun localPlayback(store: LocalPlaylistPlaybackRoomStore): LocalPlaylistPlaybackStatsRepository {
            val constructor = LocalPlaylistPlaybackStatsRepository::class.java.getDeclaredConstructor(Context::class.java, LocalPlaylistPlaybackRoomStore::class.java)
            constructor.isAccessible = true
            return constructor.newInstance(context, store)
        }
        private fun keepScope(repository: Any, fieldName: String) {
            scopes += repository.javaClass.getDeclaredField(fieldName).also { it.isAccessible = true }.get(repository) as CoroutineScope
        }
        override fun close() {
            scopes.forEach { it.cancel() }
            database.close()
            preferences.forEach { base.deleteSharedPreferences(it) }
            check(directory.deleteRecursively())
        }
    }
}
