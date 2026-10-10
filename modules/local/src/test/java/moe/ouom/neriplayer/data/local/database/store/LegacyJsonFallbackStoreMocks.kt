package moe.ouom.neriplayer.data.local.database.store

import kotlinx.coroutines.runBlocking
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/*
 * Store mocks whose legacy fallback commit still writes the snapshot and then flips the marker
 * through the mocked markLegacyJsonPrimary, so tests can keep stubbing and verifying the marker.
 */

internal fun mockPlayHistoryRoomStore(): PlayHistoryRoomStore =
    mock(PlayHistoryRoomStore::class.java).also { room ->
        runBlocking {
            doAnswer { invocation ->
                invocation.getArgument<() -> Unit>(0)()
                runBlocking { room.markLegacyJsonPrimary() }
            }.`when`(room).commitLegacyFallback(any() ?: {})
        }
    }

internal fun mockPlaylistUsageRoomStore(): PlaylistUsageRoomStore =
    mock(PlaylistUsageRoomStore::class.java).also { room ->
        runBlocking {
            doAnswer { invocation ->
                invocation.getArgument<() -> Unit>(0)()
                runBlocking { room.markLegacyJsonPrimary() }
            }.`when`(room).commitLegacyFallback(any() ?: {})
        }
    }

internal fun mockLocalPlaylistPlaybackRoomStore(): LocalPlaylistPlaybackRoomStore =
    mock(LocalPlaylistPlaybackRoomStore::class.java).also { room ->
        runBlocking {
            doAnswer { invocation ->
                invocation.getArgument<() -> Unit>(0)()
                runBlocking { room.markLegacyJsonPrimary() }
            }.`when`(room).commitLegacyFallback(any() ?: {})
        }
    }

internal fun mockLocalPlaylistRoomStore(): LocalPlaylistRoomStore =
    mock(LocalPlaylistRoomStore::class.java).also { room ->
        runBlocking {
            doAnswer { invocation ->
                invocation.getArgument<() -> Unit>(1)()
                runBlocking { room.markLegacyJsonPrimary(invocation.getArgument(0)) }
            }.`when`(room).commitLegacyFallback(anyString(), any() ?: {})
        }
    }
