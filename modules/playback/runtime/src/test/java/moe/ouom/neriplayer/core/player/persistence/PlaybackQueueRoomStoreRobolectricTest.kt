package moe.ouom.neriplayer.core.player.persistence

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.PLAYBACK_QUEUE_MAIN
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playback.PersistedPlaybackState
import moe.ouom.neriplayer.data.model.playback.PersistedSongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackQueueRoomStoreRobolectricTest {
    private lateinit var database: NeriUserDataDatabase
    private lateinit var store: PlaybackQueueRoomStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        store = PlaybackQueueRoomStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `snapshot is only read once room is the primary store`() = runBlocking {
        assertFalse(store.isRoomPrimary())
        assertNull(store.readSnapshotIfRoomPrimary())

        store.clear(now = 10L)

        assertTrue(store.isRoomPrimary())
        assertNull(store.readIfRoomPrimary())
    }

    @Test
    fun `shuffled queue round trips with its restore playlist`() = runBlocking {
        val state = PersistedState(
            playlist = listOf(song(1L, MusicPlatform.QQ_MUSIC), song(2L, null)),
            index = 1,
            mediaUrl = "https://example.test/2",
            positionMs = 3_000L,
            shouldResumePlayback = true,
            repeatMode = 2,
            shuffleEnabled = true,
            shuffleRestorePlaylist = listOf(song(2L, null), song(1L, MusicPlatform.QQ_MUSIC)),
            shuffleRestoreIndex = 0
        )

        store.replaceSnapshot(state, now = 20L)
        val snapshot = store.readSnapshotIfRoomPrimary()

        assertEquals(state, snapshot?.state)
        assertEquals(20L, snapshot?.updatedAt)
    }

    @Test
    fun `playback updates keep the shuffle restore index only while shuffle stays on`() = runBlocking {
        store.replaceSnapshot(
            PersistedState(
                playlist = listOf(song(1L, null)),
                index = 0,
                shuffleEnabled = true,
                shuffleRestorePlaylist = listOf(song(1L, null)),
                shuffleRestoreIndex = 0
            ),
            now = 30L
        )

        store.updatePlaybackState(PersistedPlaybackState(index = 0, positionMs = 500L, shuffleEnabled = true), now = 31L)
        val stillShuffled = store.readIfRoomPrimary()
        store.updatePlaybackState(PersistedPlaybackState(index = 0, shuffleEnabled = false), now = 32L)
        val unshuffled = store.readIfRoomPrimary()

        assertEquals(0, stillShuffled?.shuffleRestoreIndex)
        assertEquals(500L, stillShuffled?.positionMs)
        assertNull(unshuffled?.shuffleRestoreIndex)
        assertNull(unshuffled?.shuffleRestorePlaylist)
    }

    @Test
    fun `playback update on an empty store creates a fresh state`() = runBlocking {
        store.updatePlaybackState(PersistedPlaybackState(index = 3, shuffleEnabled = true), now = 40L)

        val state = store.readIfRoomPrimary()

        assertEquals(3, state?.index)
        assertNull(state?.shuffleRestoreIndex)
        assertEquals(emptyList<PersistedSongItem>(), state?.playlist)
    }

    @Test
    fun `unknown stored lyric source is dropped and legacy cutover hides the snapshot`() = runBlocking {
        store.replaceSnapshot(PersistedState(playlist = listOf(song(5L, MusicPlatform.CLOUD_MUSIC)), index = 0), now = 50L)
        val dao = database.playbackQueueDao()
        dao.upsertSongs(dao.getSongs(PLAYBACK_QUEUE_MAIN).map { it.copy(matchedLyricSource = "RETIRED_SOURCE") })

        assertNull(store.readIfRoomPrimary()?.playlist?.single()?.matchedLyricSource)

        store.markLegacyJsonPrimary(now = 51L)

        assertFalse(store.isRoomPrimary())
        assertNull(store.readIfRoomPrimary())
    }

    @Test
    fun `restored state prefers the newer of room and legacy snapshots`() {
        val room = PlaybackQueueRoomSnapshot(PersistedState(playlist = listOf(song(1L, null)), index = 0), updatedAt = 100L)
        val older = PlaybackQueueLegacySnapshot(PersistedState(playlist = listOf(song(2L, null)), index = 0), updatedAt = 99L)
        val sameAge = older.copy(updatedAt = 100L)

        assertEquals(older.state, selectRestoredPlaybackState(roomPrimary = false, roomSnapshot = room, legacySnapshot = older))
        assertNull(selectRestoredPlaybackState(roomPrimary = true, roomSnapshot = null, legacySnapshot = null))
        assertEquals(room.state, selectRestoredPlaybackState(roomPrimary = true, roomSnapshot = room, legacySnapshot = null))
        assertEquals(room.state, selectRestoredPlaybackState(roomPrimary = true, roomSnapshot = room, legacySnapshot = older))
        assertEquals(sameAge.state, selectRestoredPlaybackState(roomPrimary = true, roomSnapshot = room, legacySnapshot = sameAge))
    }

    private fun song(id: Long, lyricSource: MusicPlatform?) = PersistedSongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 120_000L,
        coverUrl = null,
        matchedLyric = "[00:00.00]line",
        matchedLyricSource = lyricSource,
        matchedSongId = lyricSource?.let { "remote-$id" }
    )
}
