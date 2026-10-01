package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class EditableMediaExtensionTest(
    private val description: String,
    private val fileName: String?,
    private val filePath: String?,
    private val pathSegment: String?,
    private val mediaUri: String?,
    private val expected: String
) {
    @Test
    fun preservesLiteralFileNamesAndUsesOnlyTheLeafExtension() {
        val song = SongItem(
            id = 1L, name = "Intro", artist = "artist", album = "album",
            albumId = 0L, durationMs = 1000L, coverUrl = null,
            localFileName = fileName, localFilePath = filePath, mediaUri = mediaUri
        )
        assertEquals(description, expected, LocalMediaSupport.resolveEditableMediaExtension(song, pathSegment))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<String?>> = listOf(
            arrayOf("hash in logical download name", "Intro - ラブリーサマーちゃん - #ラブリーミュージック - netease.mp3", null, "primary:Music/.tmp/Intro - #album.mp3.npdl_pending.owner.pending", null, "mp3"),
            arrayOf("question mark in local name", "Who? - #1.M4A", null, null, null, "m4a"),
            arrayOf("literal percent in local name", "100%23 - #album.FLAC", null, null, null, "flac"),
            arrayOf("hash in local path", null, "/music/.tmp/#album/Intro.mp3", null, null, "mp3"),
            arrayOf("decoded document id", null, null, "primary:Music/.tmp/Who? - #album.m4a", null, "m4a"),
            arrayOf("opaque document id in dotted directory", null, null, "primary:Music/.tmp/opaque", null, "bin"),
            arrayOf("dotted local directory without file extension", null, "/music.v2/opaque", null, null, "bin"),
            arrayOf("actual URI query and fragment", null, null, null, "content://example/document/song.mp3?token=value.m4a#cover.png", "mp3"),
            arrayOf("invalid suffix falls back to source", "song.invalid suffix", null, "song.ogg", null, "ogg"),
            arrayOf("missing references", null, null, null, null, "bin")
        )
    }
}
