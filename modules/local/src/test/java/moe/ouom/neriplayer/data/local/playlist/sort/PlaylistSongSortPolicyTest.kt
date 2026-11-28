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
 * File: moe.ouom.neriplayer.data.local.playlist.sort/PlaylistSongSortPolicyTest
 * Updated: 2026/11/21
 */

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSongSortPolicyTest {
    @Test
    fun `title sort uses natural order so track 2 precedes track 10`() {
        val songs = listOf(
            song(id = 3, name = "Track 10"),
            song(id = 1, name = "Track 2"),
            song(id = 2, name = "Track 1")
        )

        val sorted = sortPlaylistSongs(songs, PlaylistSongSortOrder(PlaylistSongSortMode.TITLE))

        assertEquals(listOf("Track 1", "Track 2", "Track 10"), sorted.map { it.name })
    }

    @Test
    fun `title sort is case insensitive and keeps equal titles deterministic`() {
        val songs = listOf(
            song(id = 2, name = "beta"),
            song(id = 3, name = "Alpha"),
            song(id = 1, name = "alpha")
        )

        val sorted = sortPlaylistSongs(songs, PlaylistSongSortOrder(PlaylistSongSortMode.TITLE))

        assertEquals(listOf("alpha", "Alpha", "beta"), sorted.map { it.name })
    }

    @Test
    fun `descending reverses the sorted order`() {
        val songs = listOf(
            song(id = 1, name = "A"),
            song(id = 2, name = "C"),
            song(id = 3, name = "B")
        )

        val sorted = sortPlaylistSongs(
            songs,
            PlaylistSongSortOrder(
                mode = PlaylistSongSortMode.TITLE,
                direction = PlaylistSongSortDirection.DESCENDING
            )
        )

        assertEquals(listOf("C", "B", "A"), sorted.map { it.name })
    }

    @Test
    fun `file name sort prefers the persisted local file name`() {
        val songs = listOf(
            song(id = 1, name = "Song B", localFileName = "01 - Song B.flac"),
            song(id = 2, name = "Song A", localFileName = "02 - Song A.flac"),
            song(id = 3, name = "Song C", localFileName = "10 - Song C.flac")
        )

        val sorted = sortPlaylistSongs(songs, PlaylistSongSortOrder(PlaylistSongSortMode.FILE_NAME))

        assertEquals(listOf(1L, 2L, 3L), sorted.map { it.id })
    }

    @Test
    fun `file name sort falls back to the path tail then the title`() {
        val songs = listOf(
            song(id = 1, name = "Remote Song"),
            song(id = 2, name = "Path Song", localFilePath = "/storage/emulated/0/Music/a-song.mp3"),
            song(id = 3, name = "Uri Song", mediaUri = "content://media/external/audio/0/zz-song.mp3")
        )

        val sorted = sortPlaylistSongs(songs, PlaylistSongSortOrder(PlaylistSongSortMode.FILE_NAME))

        // a-song.mp3 < Remote Song < zz-song.mp3
        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.id })
    }

    @Test
    fun `file name sort ignores remote media uris`() {
        val songs = listOf(
            song(id = 1, name = "Zeta", mediaUri = "ytmusic://video/zzz"),
            song(id = 2, name = "Alpha", mediaUri = "ytmusic://video/aaa"),
            song(id = 3, name = "Local", localFileName = "0-local.mp3")
        )

        val sorted = sortPlaylistSongs(songs, PlaylistSongSortOrder(PlaylistSongSortMode.FILE_NAME))

        // 远端歌曲按标题排序，不能被 ytmusic:// 里的视频 ID 带偏
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.id })
    }

    @Test
    fun `file name sort accepts absolute path media uris`() {
        val songs = listOf(
            song(id = 1, name = "A Song", mediaUri = "/storage/emulated/0/Music/z-track.mp3"),
            song(id = 2, name = "Z Song", mediaUri = "/storage/emulated/0/Music/a-track.mp3")
        )

        val sorted = sortPlaylistSongs(songs, PlaylistSongSortOrder(PlaylistSongSortMode.FILE_NAME))

        // 按文件名 a-track < z-track；退回标题排序会得到相反的 1 < 2
        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }

    @Test
    fun `file name reads android resource uris`() {
        val song = song(
            id = 1,
            name = "Ignored",
            mediaUri = "android.resource://com.example.app/raw/05-track"
        )

        assertEquals("05-track", playlistSongFileName(song))
    }

    @Test
    fun `sorting an empty or single song playlist keeps the input`() {
        assertEquals(emptyList<SongItem>(), sortPlaylistSongs(emptyList(), PlaylistSongSortOrder(PlaylistSongSortMode.TITLE)))
        val single = listOf(song(id = 1, name = "Only"))
        assertEquals(single, sortPlaylistSongs(single, PlaylistSongSortOrder(PlaylistSongSortMode.TITLE)))
    }

    private fun song(
        id: Long,
        name: String,
        localFileName: String? = null,
        localFilePath: String? = null,
        mediaUri: String? = null
    ) = SongItem(
        id = id,
        name = name,
        artist = "Artist $id",
        album = "Album",
        albumId = 0L,
        durationMs = 1000L,
        coverUrl = null,
        mediaUri = mediaUri,
        localFileName = localFileName,
        localFilePath = localFilePath
    )
}
