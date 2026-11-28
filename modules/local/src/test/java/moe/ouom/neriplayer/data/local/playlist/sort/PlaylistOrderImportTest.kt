package moe.ouom.neriplayer.data.local.playlist.sort

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
 * File: moe.ouom.neriplayer.data.local.playlist.sort/PlaylistOrderImportTest
 * Updated: 2026/11/21
 */

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOrderImportTest {
    @Test
    fun `plain text keeps one song per line`() {
        val result = parsePlaylistOrderText(
            """
            晴天
            稻香
            七里香
            """.trimIndent()
        )

        assertEquals(
            listOf(
                PlaylistOrderEntry("晴天"),
                PlaylistOrderEntry("稻香"),
                PlaylistOrderEntry("七里香")
            ),
            result.entries
        )
        assertEquals(0, result.skippedLineCount)
    }

    @Test
    fun `index prefix and blank lines are ignored`() {
        val result = parsePlaylistOrderText(
            """
            1. 晴天
            2、稻香

            # 这是注释
            3) 七里香
            """.trimIndent()
        )

        assertEquals(listOf("晴天", "稻香", "七里香"), result.entries.map { it.primary })
    }

    @Test
    fun `csv with header keeps title and artist`() {
        val result = parsePlaylistOrderText(
            """
            歌名,歌手
            晴天,周杰伦
            "Hello, World",Adele
            """.trimIndent()
        )

        assertEquals(2, result.entries.size)
        assertEquals(PlaylistOrderEntry("晴天", "周杰伦"), result.entries[0])
        assertEquals(PlaylistOrderEntry("Hello, World", "Adele"), result.entries[1])
    }

    @Test
    fun `dash separator keeps both fields without assuming orientation`() {
        val result = parsePlaylistOrderText("晴天 - 周杰伦\n周杰伦 – 稻香")

        assertEquals(PlaylistOrderEntry("晴天", "周杰伦"), result.entries[0])
        assertEquals(PlaylistOrderEntry("周杰伦", "稻香"), result.entries[1])
    }

    @Test
    fun `matching accepts both title and artist orientations`() {
        val songs = listOf(
            song(id = 1, name = "晴天", artist = "周杰伦"),
            song(id = 2, name = "稻香", artist = "周杰伦")
        )

        val titleFirst = matchPlaylistOrder(
            songs,
            parsePlaylistOrderText("稻香 - 周杰伦\n晴天 - 周杰伦").entries
        )
        val artistFirst = matchPlaylistOrder(
            songs,
            parsePlaylistOrderText("周杰伦 - 稻香\n周杰伦 - 晴天").entries
        )

        assertEquals(listOf(2L, 1L), titleFirst.orderedSongs.map { it.id })
        assertEquals(listOf(2L, 1L), artistFirst.orderedSongs.map { it.id })
        assertEquals(2, titleFirst.matchedCount)
        assertEquals(2, artistFirst.matchedCount)
    }

    @Test
    fun `songs missing from the file are appended in their original order`() {
        val songs = listOf(
            song(id = 1, name = "A"),
            song(id = 2, name = "B"),
            song(id = 3, name = "C"),
            song(id = 4, name = "D")
        )

        val match = matchPlaylistOrder(songs, parsePlaylistOrderText("C\nA").entries)

        assertEquals(listOf(3L, 1L, 2L, 4L), match.orderedSongs.map { it.id })
        assertEquals(2, match.matchedCount)
        assertEquals(2, match.appendedCount)
        assertTrue(match.unmatchedEntries.isEmpty())
    }

    @Test
    fun `unmatched entries are reported without dropping songs`() {
        val songs = listOf(song(id = 1, name = "A"), song(id = 2, name = "B"))

        val match = matchPlaylistOrder(songs, parsePlaylistOrderText("A\n不存在的歌\nB").entries)

        assertEquals(listOf(1L, 2L), match.orderedSongs.map { it.id })
        assertEquals(2, match.matchedCount)
        assertEquals(listOf("不存在的歌"), match.unmatchedEntries)
    }

    @Test
    fun `matching ignores case full width characters and punctuation`() {
        val songs = listOf(song(id = 1, name = "晴天 (Live)", artist = "周杰伦"))

        val match = matchPlaylistOrder(songs, parsePlaylistOrderText("晴天（ｌｉｖｅ）").entries)

        assertEquals(listOf(1L), match.orderedSongs.map { it.id })
        assertEquals(1, match.matchedCount)
    }

    @Test
    fun `duplicate titles are matched to distinct songs`() {
        val songs = listOf(
            song(id = 1, name = "Intro", artist = "A"),
            song(id = 2, name = "Intro", artist = "B")
        )

        val match = matchPlaylistOrder(songs, parsePlaylistOrderText("Intro\nIntro").entries)

        assertEquals(listOf(1L, 2L), match.orderedSongs.map { it.id })
        assertEquals(2, match.matchedCount)
    }

    @Test
    fun `blank document produces no entries`() {
        val result = parsePlaylistOrderText("   \n\n")

        assertTrue(result.entries.isEmpty())
    }

    private fun song(id: Long, name: String, artist: String = "Artist $id") = SongItem(
        id = id,
        name = name,
        artist = artist,
        album = "Album",
        albumId = 0L,
        durationMs = 1000L,
        coverUrl = null
    )
}
