package moe.ouom.neriplayer.common.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLogReferenceTest {
    @Test
    fun `local document keeps its folder identity without credentials or query`() {
        assertEquals(
            "content://documents.example/tree/primary:Music/document/primary:Music/song.flac",
            playbackLogReference("content://user:password@documents.example/tree/primary%3AMusic/document/primary%3AMusic/song.flac?token=secret#private")
        )
    }

    @Test
    fun `remote playback references hide signed paths and authentication`() {
        assertEquals(
            "https://cdn.example/<redacted>",
            playbackLogReference("https://user:password@cdn.example/secret-token/song?signature=private#secret")
        )
    }

    @Test
    fun `protocol relative network references never masquerade as local paths`() {
        val result = playbackLogReference("//user:password@cdn.example/song?token=secret#private")
        assertFalse(result.contains("password"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("private"))
    }

    @Test
    fun `file references remain identifiable and missing references are explicit`() {
        assertEquals("none", playbackLogReference(null))
        assertEquals("none", playbackLogReference(" "))
        assertEquals("/storage/Music/song.flac", playbackLogReference("/storage/Music/song.flac"))
        assertEquals("file:///storage/Music/song.flac", playbackLogReference("file:///storage/Music/song.flac?secret=token"))
        assertEquals("invalid", playbackLogReference("content://host/invalid space?token=private"))
    }

    @Test
    fun `log references cannot inject lines or exceed the diagnostic budget`() {
        val result = playbackLogReference("/storage/Music/song\nforged\rline/" + "x".repeat(2_000))
        assertTrue(result.length <= 768)
        assertFalse(result.contains('\n'))
        assertFalse(result.contains('\r'))
    }
}
