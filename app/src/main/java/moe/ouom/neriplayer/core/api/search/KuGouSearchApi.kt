package moe.ouom.neriplayer.core.api.search

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.core.api.search/KuGouSearchApi
 * Created: 2025/07/05
 */

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.ouom.neriplayer.core.player.PlayerManager
import top.ghhccghk.multiplatform.kugouapi.KuGouClient
import java.io.IOException

class KuGouSearchApi(private val kugouClient: KuGouClient) : SearchApi {

    override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> {
        return withContext(Dispatchers.IO) {
            kugouClient.auth.registerDev()
            val response = kugouClient.search.search(
                keywords = keyword,
                page = page,
            )

            if (response.status != 200) {
                return@withContext emptyList()
            }

            val data = response.body["data"]?.jsonObject ?: return@withContext emptyList()
            Log.d("Kugou",data.toString())
            val info = data["lists"]?.jsonArray ?: return@withContext emptyList()

            info.mapNotNull { item ->
                val song = item.jsonObject
                val hash = song["FileHash"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val songName = song["OriSongName"]?.jsonPrimitive?.content ?: "Unknown"
                val singer = song["SingerName"]?.jsonPrimitive?.content ?: "Unknown"
                val albumName = song["AlbumName"]?.jsonPrimitive?.content
                val coverUrl = song["Image"]?.jsonPrimitive?.content?.replace("/{size}/", "/")
                val duration = song["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

                SongSearchInfo(
                    id = hash,
                    songName = songName,
                    singer = singer,
                    duration = formatDuration(duration),
                    source = MusicPlatform.KUGOU,
                    albumName = "${PlayerManager.KuGou_SOURCE_TAG}$albumName",
                    coverUrl = coverUrl
                )
            }
        }
    }

    override suspend fun getSongInfo(id: String): SongDetails {
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val infoDeferred = async { kugouClient.song.getPrivilegeLite(id) }
                val lyricDeferred = async { searchAndFetchLyric(id) }

                val infoResponse = infoDeferred.await()
                if (infoResponse.status != 200) throw IOException("Failed to fetch song info for $id")

                val data = infoResponse.body["data"]?.jsonArray?.get(0)?.jsonObject
                    ?: throw IOException("Empty response for $id")

                Log.d("Kugou", "getSongInfo $data")

                val songName = data["name"]?.jsonPrimitive?.content?: "Unknown"
                val singer = data["singername"]?.jsonPrimitive?.content ?: "Unknown"
                val info  = data["info"]?.jsonObject



                val album = data["albumname"]?.jsonPrimitive?.content ?: ""
                val coverUrl = info?.get("image")?.jsonPrimitive?.content?.replace("/{size}/", "/")

                val lyric = lyricDeferred.await()


                Log.d("Kugou","Lyric ${lyric.toString()}")

                SongDetails(
                    id = id,
                    songName = songName.removePrefix("$singer - ").trim(),
                    singer = singer,
                    album = "${PlayerManager.KuGou_SOURCE_TAG}$album",
                    coverUrl = coverUrl,
                    lyric = lyric,
                    translatedLyric = null
                )
            }
        }
    }

    private suspend fun searchAndFetchLyric(hash: String): String? {
        val searchResponse = kugouClient.search.searchLyric(hash = hash)
        Log.d("Kugou","searchResponseLyric ${searchResponse.body}")
        if (searchResponse.status != 200) return null

        val candidates = searchResponse.body["candidates"]?.jsonArray
            ?: searchResponse.body["info"]?.jsonArray
            ?: return null


        val candidate = candidates.firstOrNull()?.jsonObject ?: return null
        val id = candidate["id"]?.jsonPrimitive?.content ?: return null
        val accessKey = candidate["accesskey"]?.jsonPrimitive?.content ?: return null

        val lyricResponse = kugouClient.song.getLyric(id = id, accessKey = accessKey, decode = true, fmt = "lrc")
        if (lyricResponse.status != 200) return null

        return lyricResponse.body["decodeContent"]?.jsonPrimitive?.content
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }
}
