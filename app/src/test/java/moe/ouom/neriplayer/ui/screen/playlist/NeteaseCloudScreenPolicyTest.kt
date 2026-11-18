package moe.ouom.neriplayer.ui.screen.playlist

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: app/src/test/java/moe/ouom/neriplayer/ui/screen/playlist/NeteaseCloudScreenPolicyTest
 */

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCloudScreenPolicyTest {

    @Test
    fun `cloud cover uses the newest track that has artwork`() {
        val tracks = listOf(
            song(id = 1L, coverUrl = "   "),
            song(id = 2L, coverUrl = "https://cover.test/newest.jpg?param=300y300"),
            song(id = 3L, coverUrl = "https://cover.test/older.jpg?param=300y300")
        )

        assertEquals(
            "https://cover.test/newest.jpg?param=300y300",
            resolveNeteaseCloudCoverUrl(tracks)
        )
    }

    @Test
    fun `cloud cover falls back to null without artwork`() {
        assertNull(resolveNeteaseCloudCoverUrl(listOf(song(id = 1L), song(id = 2L))))
        assertNull(resolveNeteaseCloudCoverUrl(emptyList()))
    }

    @Test
    fun `selected cloud songs follow the loaded list order`() {
        val tracks = listOf(song(id = 10L), song(id = 11L), song(id = 12L))

        assertEquals(
            listOf(11L, 12L),
            selectNeteaseCloudSongs(tracks, setOf(12L, 11L)).map { it.id }
        )
        assertEquals(emptyList<SongItem>(), selectNeteaseCloudSongs(tracks, emptySet()))
    }

    @Test
    fun `select all uses every loaded cloud song id`() {
        assertEquals(
            setOf(10L, 11L),
            neteaseCloudSongIds(listOf(song(id = 10L), song(id = 11L)))
        )
        assertEquals(emptySet<Long>(), neteaseCloudSongIds(emptyList()))
    }

    @Test
    fun `playback and export stay disabled while the cloud list is a prefix`() {
        assertTrue(canUseNeteaseCloudTracks(hasTracks = true, incomplete = false))
        assertFalse(canUseNeteaseCloudTracks(hasTracks = true, incomplete = true))
        assertFalse(canUseNeteaseCloudTracks(hasTracks = false, incomplete = false))
        assertFalse(canUseNeteaseCloudTracks(hasTracks = false, incomplete = true))
    }

    private fun song(id: Long, coverUrl: String? = null): SongItem = SongItem(
        id = id,
        name = "cloud-song-$id",
        artist = "artist",
        album = "NeteaseAlbum",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = coverUrl
    )
}
