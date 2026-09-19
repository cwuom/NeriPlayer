package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.catalog.buildDownloadedSongCatalogIndex
import moe.ouom.neriplayer.core.download.manager.batch.BatchDownloadPreflightProbe
import moe.ouom.neriplayer.core.download.manager.batch.findFastCompletedBatchSongKeys
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.ManagedLibraryItemEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadBatchPreflightPerformanceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val providerUri get() = DocumentsContract.buildDocumentUri(DownloadPreflightTestProvider.AUTHORITY, "stats")

    @Test
    fun eightHundredFiftyCatalogAndArtifactHitsHaveOneBoundedPreflightBudget() = runBlocking {
        withFixture(List(850) { "present" }) { songs, catalog ->
            call("reset", "1")
            val probe = BatchDownloadPreflightProbe { reference ->
                ManagedDownloadReferenceLookup.inspect(context, reference)
            }
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val firstCompleted = GlobalDownloadManager.findFastCompletedBatchSongKeys(
                context, songs, catalogIndex = catalog, preflightProbe = probe
            )
            val firstStats = call("stats")
            val secondCompleted = GlobalDownloadManager.findFastCompletedBatchSongKeys(
                context, songs, alreadyCompletedSongKeys = firstCompleted,
                catalogIndex = catalog, preflightProbe = probe
            )
            val finalStats = call("stats")
            val metrics = JSONObject()
                .put("songs", songs.size)
                .put("elapsedNs", SystemClock.elapsedRealtimeNanos() - startedNs)
                .put("firstCompleted", firstCompleted.size)
                .put("secondCompleted", secondCompleted.size)
                .put("firstQueries", firstStats.getInt("queries"))
                .put("queries", finalStats.getInt("queries"))
                .put("opens", finalStats.getInt("opens"))
                .put("maxReferenceQueries", finalStats.getInt("maxReferenceQueries"))
            Log.i("DownloadPreflightBenchmark", metrics.toString())
            println("DOWNLOAD_PREFLIGHT_BENCHMARK=$metrics")
            assertTrue("readable fixture must produce real completed evidence: $metrics",
                firstCompleted.isNotEmpty())
            assertTrue("preflight must leave the remaining songs for normal preparation: $metrics",
                firstCompleted.size < songs.size)
            assertTrue("two startup preflight calls share at most one 64-reference budget: $metrics",
                finalStats.getInt("queries") <= 64)
            assertEquals("catalog positives must not be probed again by the artifact fallback: $metrics",
                1, finalStats.getInt("maxReferenceQueries"))
            assertEquals(firstStats.getInt("queries"), finalStats.getInt("queries"))
        }
    }

    @Test
    fun catalogPositivesDoNotProbeTheSameArtifactAgain() = runBlocking {
        withFixture(List(3) { "present" }) { songs, catalog ->
            call("reset")
            assertEquals(songs.map(SongItem::stableKey).toSet(),
                GlobalDownloadManager.findFastCompletedBatchSongKeys(context, songs, catalogIndex = catalog))
            val stats = call("stats")
            assertEquals(3, stats.getInt("queries"))
            assertEquals(3, stats.getInt("opens"))
            assertEquals(1, stats.getInt("maxReferenceQueries"))
        }
    }

    @Test
    fun finalizedArtifactCanSupplyPositiveEvidenceWithoutCatalog() = runBlocking {
        withFixture(listOf("present")) { songs, _ ->
            call("reset")
            assertEquals(setOf(songs.single().stableKey()),
                GlobalDownloadManager.findFastCompletedBatchSongKeys(
                    context, songs, catalogIndex = buildDownloadedSongCatalogIndex(emptyList())))
            assertEquals(1, call("stats").getInt("queries"))
        }
    }

    @Test
    fun unknownAndMissingReferencesNeverBecomeInitiallyCompleted() = runBlocking {
        withFixture(listOf("permission", "failure", "missing", "present")) { songs, catalog ->
            call("reset")
            val completed = GlobalDownloadManager.findFastCompletedBatchSongKeys(
                context, songs, catalogIndex = catalog
            )
            assertFalse(songs[0].stableKey() in completed)
            assertFalse(songs[1].stableKey() in completed)
            assertFalse(songs[2].stableKey() in completed)
            assertEquals(setOf(songs[3].stableKey()), completed)
        }
    }

    private suspend fun withFixture(
        modes: List<String>,
        block: suspend (List<SongItem>, moe.ouom.neriplayer.core.download.catalog.DownloadedSongCatalogIndex) -> Unit
    ) {
        val runId = UUID.randomUUID().toString()
        val firstId = SystemClock.elapsedRealtimeNanos()
        val songs = modes.mapIndexed { index, _ -> SongItem(
            id = firstId + index, name = "preflight-$runId-$index", artist = "fixture",
            album = "netease", albumId = 0L, durationMs = 1_000L, coverUrl = null
        ) }
        val references = modes.mapIndexed { index, mode ->
            DocumentsContract.buildDocumentUri(DownloadPreflightTestProvider.AUTHORITY,
                "$mode-$runId-$index.mp3").toString()
        }
        val catalog = buildDownloadedSongCatalogIndex(songs.mapIndexed { index, song ->
            DownloadedSong(
                id = song.id, name = song.name, artist = song.artist, album = song.album,
                filePath = references[index], fileSize = 1L, downloadTime = 1L,
                stableKey = song.stableKey(), durationMs = song.durationMs
            )
        })
        assertTrue(songs.all { catalog.find(it) != null })
        val db = NeriUserDataDatabase.getInstance(context)
        val rootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
        val snapshotCache = ManagedDownloadStorage.snapshotCacheStore
        val cacheKey = snapshotCache.currentKey(context)
        val previousSnapshot = snapshotCache.cachedSnapshot(context, restorePersisted = true)
        db.withTransaction {
            // 仅清理上次测试进程崩溃遗留的本测试 authority，不接触用户引用
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM managed_library_item WHERE audio_reference LIKE ?",
                arrayOf("content://${DownloadPreflightTestProvider.AUTHORITY}/%")
            )
            songs.forEachIndexed { index, song ->
                db.managedDownloadArtifactDao().upsert(ManagedLibraryItemEntity(
                    rootKey = rootKey, stableKey = song.stableKey(), artifactId = "$runId-$index",
                    state = "FINALIZED", audioReference = references[index], fileSize = 1L
                ))
            }
        }
        try {
            val snapshot = previousSnapshot ?: ManagedDownloadStorage.emptyDownloadLibrarySnapshot()
            snapshotCache.putSnapshot(context, cacheKey, snapshot.copy(
                knownReferences = snapshot.knownReferences + references,
                rootEntriesComplete = true
            ))
            val presentReference = references.zip(modes).firstOrNull { it.second == "present" }?.first
            if (presentReference != null) {
                assertEquals(ManagedDownloadReferenceLookup.Result.Present,
                    ManagedDownloadReferenceLookup.inspect(context, presentReference))
            }
            assertEquals(songs.size, songs.chunked(400).sumOf { page ->
                db.managedDownloadArtifactDao().findAllByRootKeyAndStableKeys(
                    rootKey, page.map(SongItem::stableKey)).size
            })
            block(songs, catalog)
        } finally {
            db.withTransaction {
                songs.forEach { db.managedDownloadArtifactDao().delete(rootKey, it.stableKey()) }
            }
            snapshotCache.invalidate(context)
            if (previousSnapshot != null) snapshotCache.putSnapshot(context, cacheKey, previousSnapshot)
            call("cleanup")
        }
    }

    private fun call(method: String, argument: String? = null): Bundle =
        checkNotNull(context.contentResolver.call(providerUri, method, argument, null))
}
