package moe.ouom.neriplayer.core.startup.app

import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicCoverReference
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCoverCacheAliasesTest {
    private val source = SubsonicProfile("00000000-0000-0000-0000-000000000001", "Source",
        "https://music.example:4533/music/", "fixture-user")
    private val local = source.copy(id = "00000000-0000-0000-0000-000000000002", label = "Local")
    private val cover = SubsonicCoverReference.create(source, "cover / 中文")
    private val legacy = ServerSongRef(local.id, "cover / 中文").resourceUrl("cover")

    @Test
    fun `a real cover maps to enabled local account aliases without using the remote UUID`() {
        assertEquals(listOf(legacy), serverCoverCacheAliases(cover, listOf(local)))
        val duplicate = local.copy(id = "00000000-0000-0000-0000-000000000003")
        assertEquals(listOf(legacy, ServerSongRef(duplicate.id, "cover / 中文").resourceUrl("cover")),
            serverCoverCacheAliases(cover, listOf(local, duplicate)))
    }

    @Test
    fun `versioned cover playlist and blur keys replace repeated URLs while preserving the representation`() {
        val requests = listOf(
            "nowplaying-cover|$cover|1|12|300|data=$cover" to "nowplaying-cover|$legacy|1|12|300|data=$legacy",
            "playlist-row-cover|subsonic:track:7|$cover" to "playlist-row-cover|subsonic:track:7|$legacy",
            "nowplaying-blur:$cover:0.75:revision:song-key:$cover" to "nowplaying-blur:$legacy:0.75:revision:song-key:$legacy",
            "subsonic:track:7|data=$cover" to "subsonic:track:7|data=$legacy"
        )
        for ((current, expected) in requests) {
            assertEquals(listOf(expected), serverCoverCacheAliases(current, listOf(local)))
        }
    }

    @Test
    fun `unloaded disabled and different logical accounts never borrow another image cache`() {
        assertTrue(serverCoverCacheAliases(cover, emptyList()).isEmpty())
        for (profile in listOf(local.copy(enabled = false), local.copy(username = "other-user"),
            local.copy(baseUrl = "https://music.example/music/"), local.copy(baseUrl = "https://music.example:4533/other/"),
            local.copy(baseUrl = "http://music.example:4533/music/"))) {
            assertTrue(serverCoverCacheAliases(cover, listOf(profile)).isEmpty())
        }
    }

    @Test
    fun `unmarked malformed and unrelated image keys keep exact-key lookup only`() {
        for (key in listOf("https://images.example/art", legacy, cover.substringBefore('#'),
            cover + ":unsupported", cover.replace("getCoverArt.view", "stream.view"),
            cover.replace("size=600", "size=300"), cover.replace("#neri-cover=v1:", "&token=private#neri-cover=v1:"),
            "unrecognized-prefix|$cover", "nowplaying-cover|https://images.example/art|1|2|3")) {
            assertTrue(key, serverCoverCacheAliases(key, listOf(local)).isEmpty())
        }
    }
}
