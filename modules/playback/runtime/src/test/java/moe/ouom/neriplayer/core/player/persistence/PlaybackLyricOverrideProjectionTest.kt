package moe.ouom.neriplayer.core.player.persistence

import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsTracker
import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaybackLyricOverrideProjectionTest {
    @Test
    fun `a bilibili compatibility edit cannot be replaced by its baseline as a fabricated local version`() {
        val local = song().copy(album = "Bilibili", channelId = "bilibili", audioId = "BV1synthetic",
            subAudioId = "1", lyricSyncEdited = false)
        val remote = SyncSong(id = local.id, album = local.album, channelId = local.channelId,
            audioId = local.audioId, subAudioId = local.subAudioId,
            matchedLyric = "edit", matchedTranslatedLyric = "translation", matchedRomanizedLyric = "romanized",
            originalLyric = "origin", originalTranslatedLyric = "baseline translation",
            originalRomanizedLyric = "baseline romanized", lyricSyncEdited = true, lyricSyncRevision = 1)

        val restored = PlaybackLyricOverrideProjection(listOf(remote)).song(local)

        assertEquals("edit", restored.matchedLyric)
        assertEquals("translation", restored.matchedTranslatedLyric)
        assertEquals("romanized", restored.matchedRomanizedLyric)
        assertEquals("origin", restored.originalLyric)
        assertEquals("baseline translation", restored.originalTranslatedLyric)
        assertEquals("baseline romanized", restored.originalRomanizedLyric)
        assertEquals(true, restored.lyricSyncEdited)
        assertEquals(1L, restored.lyricSyncRevision)
        assertSame(restored, PlaybackLyricOverrideProjection(listOf(remote)).song(restored))
    }

    @Test
    fun `unconfirmed local numeric versions cannot block committed remote edits or resets`() {
        for (revision in listOf(999L, Long.MAX_VALUE)) {
            val local = song().copy(matchedLyric = "legacy edit", originalLyric = "local baseline",
                lyricSyncRevision = revision)
            val edited = PlaybackLyricOverrideProjection(listOf(edit())).song(local)
            assertEquals("edit", edited.matchedLyric)
            assertEquals(true, edited.lyricSyncEdited)
            assertEquals(20L, edited.lyricSyncRevision)
            val cleared = PlaybackLyricOverrideProjection(listOf(reset())).song(local)
            assertEquals("local baseline", cleared.matchedLyric)
            assertEquals(false, cleared.lyricSyncEdited)
            assertEquals(20L, cleared.lyricSyncRevision)
        }
    }

    @Test
    fun `legacy recovery becomes a preserved edit without overriding a committed reset`() {
        val legacy = edit().copy(lyricSyncEdited = null, lyricSyncRevision = 0)
        val recovered = PlaybackLyricOverrideProjection(listOf(legacy)).song(song())
        assertEquals("edit", recovered.matchedLyric)
        assertEquals("translation", recovered.matchedTranslatedLyric)
        assertEquals("romanized", recovered.matchedRomanizedLyric)
        assertEquals(true, recovered.lyricSyncEdited)
        assertEquals(1L, recovered.lyricSyncRevision)
        val cached = song().copy(matchedLyric = "current network cache", lyricSyncEdited = false)
        val restoredCache = PlaybackLyricOverrideProjection(listOf(legacy)).song(cached)
        assertEquals("edit", restoredCache.matchedLyric)
        assertEquals("current network cache", restoredCache.originalLyric)
        assertEquals(true, restoredCache.lyricSyncEdited)
        assertEquals(1L, restoredCache.lyricSyncRevision)
        val resetSong = song().copy(lyricSyncEdited = false, lyricSyncRevision = 21)
        assertSame(resetSong, PlaybackLyricOverrideProjection(listOf(legacy)).song(resetSong))
        val projectedReset = PlaybackLyricOverrideProjection(listOf(legacy, reset())).song(song())
        assertNull(projectedReset.matchedLyric)
        assertEquals(false, projectedReset.lyricSyncEdited)
        assertEquals(20L, projectedReset.lyricSyncRevision)
    }

    @Test
    fun `publishing projected lyrics keeps the active listening segment and emits no sync statistics`() {
        var elapsed = 0L
        val tracker = PlaybackStatsTracker(AppQueueSongIdentity::stableKey, nowElapsedMs = { elapsed })
        val cached = song().copy(lyricSyncEdited = false)
        assertNull(tracker.onSongChanged(cached, 7))
        assertNull(tracker.onPlayingChanged(true))
        elapsed = 6000
        val projected = PlaybackLyricOverrideProjection(listOf(edit())).song(cached)
        assertNull(tracker.onSongChanged(projected, 7))
        val periodic = tracker.flushPeriodic()!!
        assertEquals(6000L, periodic.listenedMs)
        assertEquals(false, periodic.scheduleSync)
        assertEquals(0, periodic.playCountIncrement)
    }
    @Test
    fun `remote edit changes only lyric state and preserves playback and local cache fields`() {
        val cached = song().copy(matchedLyric = "cache", matchedTranslatedLyric = "cache translation",
            matchedRomanizedLyric = "cache romanized", lyricSyncEdited = false,
            mediaUri = "https://media", streamUrl = "https://stream", userLyricOffsetMs = 50)
        val projected = PlaybackLyricOverrideProjection(listOf(edit())).song(cached)
        assertEquals(cached.copy(matchedLyric = "edit", matchedTranslatedLyric = "translation",
            matchedRomanizedLyric = "romanized", originalLyric = "cache",
            originalTranslatedLyric = "cache translation", originalRomanizedLyric = "cache romanized",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "7",
            lyricSyncEdited = true, lyricSyncRevision = 20), projected)
    }

    @Test
    fun `remote reset restores all cached baselines instead of the stale user edit`() {
        val stale = song().copy(matchedLyric = "old edit", matchedTranslatedLyric = "old translation",
            matchedRomanizedLyric = "old romanized", originalLyric = "base",
            originalTranslatedLyric = "base translation", originalRomanizedLyric = "base romanized",
            lyricSyncEdited = true, lyricSyncRevision = 10)
        val restored = PlaybackLyricOverrideProjection(listOf(reset())).song(stale)
        assertEquals("base", restored.matchedLyric)
        assertEquals("base translation", restored.matchedTranslatedLyric)
        assertEquals("base romanized", restored.matchedRomanizedLyric)
        assertEquals(false, restored.lyricSyncEdited)
        assertEquals(20L, restored.lyricSyncRevision)
    }

    @Test
    fun `reset without known baseline never treats an unknown legacy edit as cache`() {
        val legacy = song().copy(matchedLyric = "unknown edit", matchedRomanizedLyric = "unknown romanized")
        val restored = PlaybackLyricOverrideProjection(listOf(reset())).song(legacy)
        assertNull(restored.matchedLyric)
        assertNull(restored.matchedRomanizedLyric)
        assertEquals(false, restored.lyricSyncEdited)
    }

    @Test
    fun `same revision reset wins and lower remote revision cannot replace a newer local edit`() {
        val stale = song().copy(matchedLyric = "edit", lyricSyncEdited = true, lyricSyncRevision = 20)
        val projection = PlaybackLyricOverrideProjection(listOf(reset()))
        assertEquals(false, projection.song(stale).lyricSyncEdited)
        val newer = stale.copy(lyricSyncRevision = 21)
        assertSame(newer, projection.song(newer))
        val localReset = stale.copy(matchedLyric = null, lyricSyncEdited = false)
        assertSame(localReset, PlaybackLyricOverrideProjection(listOf(edit())).song(localReset))
    }

    @Test
    fun `same revision edits converge deterministically with the sync merge rule`() {
        val local = song().copy(matchedLyric = "a", matchedTranslatedLyric = "translation",
            matchedRomanizedLyric = "romanized", matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "7", lyricSyncEdited = true, lyricSyncRevision = 20)
        val remote = edit().copy(matchedLyric = "z")
        assertEquals("z", PlaybackLyricOverrideProjection(listOf(remote)).song(local).matchedLyric)
        assertSame(local, PlaybackLyricOverrideProjection(listOf(remote.copy(matchedLyric = "0"))).song(local))
    }

    @Test
    fun `projection keeps duplicate occurrences order and reuses unchanged lists`() {
        val untouched = song().copy(id = 2)
        val stale = song()
        val playlist = listOf(untouched, stale, stale)
        val projection = PlaybackLyricOverrideProjection(listOf(edit()))
        val result = projection.playlist(playlist)
        assertEquals(listOf(2L, 1L, 1L), result.map { it.id })
        assertSame(untouched, result[0])
        assertEquals("edit", result[1].matchedLyric)
        assertEquals("edit", result[2].matchedLyric)
        assertSame(result, projection.playlist(result))
        assertSame(playlist, PlaybackLyricOverrideProjection(emptyList()).playlist(playlist))
        assertSame(stale, PlaybackLyricOverrideProjection(emptyList()).song(stale))
    }

    @Test
    fun `restored queue and shuffle restore receive reset without changing transport state`() {
        val stale = song().copy(matchedLyric = "old", lyricSyncEdited = true, lyricSyncRevision = 10)
        val snapshot = RestoredPlayerStateSnapshot(listOf(stale), 0, "https://media", 2, true,
            listOf(stale), 0, 1234, true, 1, 0)
        val projection = PlaybackLyricOverrideProjection(listOf(reset()))
        val restored = projection.restoredSnapshot(snapshot)
        assertEquals(snapshot.copy(playlist = restored.playlist, shuffleRestorePlaylist = restored.shuffleRestorePlaylist), restored)
        assertEquals(false, restored.playlist.single().lyricSyncEdited)
        assertEquals(20L, restored.shuffleRestorePlaylist!!.single().lyricSyncRevision)
        assertSame(restored, projection.restoredSnapshot(restored))
    }

    @Test
    fun `filtered refresh includes standalone current active and shuffle identities and rereads new songs`() = runTest {
        var targets = Targets(song().copy(id = 4), listOf(song()), listOf(song().copy(id = 3)))
        val requested = mutableListOf<Set<String>>()
        var published: Targets? = null
        applyFilteredPlaybackLyricOverrides(
            readTargets = { targets },
            identityKeys = { playbackLyricIdentityKeys(it.current, it.active, it.shuffle) },
            readOverrides = { keys ->
                requested += keys
                if (requested.size == 1) targets = targets.copy(active = targets.active + song().copy(id = 2))
                (1L..4L).map { edit().copy(id = it) }.filter { "${it.id}|netease|" in keys }
            },
            applyIfCurrent = { expected, projection ->
                if (expected !== targets) false else {
                    published = targets.copy(current = targets.current?.let(projection::song),
                        active = projection.playlist(targets.active), shuffle = targets.shuffle?.let(projection::playlist))
                    true
                }
            }
        )
        assertEquals(listOf(setOf("1|netease|", "3|netease|", "4|netease|"),
            setOf("1|netease|", "2|netease|", "3|netease|", "4|netease|")), requested)
        val result = checkNotNull(published)
        assertEquals("edit", result.current?.matchedLyric)
        assertTrue(result.active.all { it.matchedLyric == "edit" })
        assertEquals("edit", result.shuffle?.single()?.matchedLyric)
    }

    @Test
    fun `same identities changing after capture are rechecked without rereading the registry`() = runTest {
        var targets = Targets(null, listOf(song(), song().copy(id = 2)), null)
        var reads = 0
        var attempts = 0
        var published: List<SongItem>? = null
        applyFilteredPlaybackLyricOverrides(
            readTargets = { targets },
            identityKeys = { playbackLyricIdentityKeys(it.current, it.active, it.shuffle) },
            readOverrides = { reads++; listOf(edit(), edit().copy(id = 2)) },
            applyIfCurrent = { expected, projection ->
                attempts++
                if (attempts == 1) {
                    targets = targets.copy(active = targets.active.reversed())
                    false
                } else {
                    assertSame(targets, expected)
                    published = projection.playlist(expected.active)
                    true
                }
            }
        )
        assertEquals(1, reads)
        assertEquals(2, attempts)
        assertEquals(listOf(2L, 1L), checkNotNull(published).map { it.id })
    }

    @Test
    fun `new identity racing final application causes another filtered read and cancellation never publishes`() = runTest {
        var targets = Targets(null, listOf(song()), null)
        var reads = 0
        var attempts = 0
        applyFilteredPlaybackLyricOverrides(
            readTargets = { targets },
            identityKeys = { playbackLyricIdentityKeys(it.current, it.active, it.shuffle) },
            readOverrides = { keys -> reads++; keys.map { edit().copy(id = it.substringBefore('|').toLong()) } },
            applyIfCurrent = { _, _ ->
                if (++attempts == 1) {
                    targets = targets.copy(shuffle = listOf(song().copy(id = 2)))
                    false
                } else true
            }
        )
        assertEquals(2, reads)
        val cancellation = CancellationException("cancel scan")
        val result = runCatching {
            applyFilteredPlaybackLyricOverrides(readTargets = { targets },
                identityKeys = { playbackLyricIdentityKeys(it.current, it.active, it.shuffle) },
                readOverrides = { throw cancellation },
                applyIfCurrent = { _, _ -> error("cancelled lookup must not publish") })
        }
        assertSame(cancellation, result.exceptionOrNull())
    }

    private data class Targets(val current: SongItem?, val active: List<SongItem>, val shuffle: List<SongItem>?)

    private fun song() = SongItem(1, "title", "artist", "netease", 0, 1000, "https://cover")
    private fun edit() = SyncSong(id = 1, album = "netease", matchedLyric = "edit",
        matchedTranslatedLyric = "translation", matchedRomanizedLyric = "romanized",
        matchedLyricSource = "CLOUD_MUSIC", matchedSongId = "7", lyricSyncEdited = true, lyricSyncRevision = 20)
    private fun reset() = SyncSong(id = 1, album = "netease", lyricSyncEdited = false, lyricSyncRevision = 20)
}
