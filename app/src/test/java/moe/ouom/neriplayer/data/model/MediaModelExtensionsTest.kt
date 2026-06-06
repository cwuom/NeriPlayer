package moe.ouom.neriplayer.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaModelExtensionsTest {

    @Test
    fun `resolveDisplayCoverUrl prefers local cover over remote fallback on main thread`() {
        assertEquals(
            "content://covers/song.jpg",
            resolveDisplayCoverUrl(
                customCoverUrl = null,
                currentCoverUrl = "https://example.com/song.jpg",
                localCoverUrl = "content://covers/song.jpg",
                onMainThread = true
            )
        )
    }

    @Test
    fun `resolveDisplayCoverUrl keeps custom override above local and remote covers`() {
        assertEquals(
            "content://covers/custom.jpg",
            resolveDisplayCoverUrl(
                customCoverUrl = "content://covers/custom.jpg",
                currentCoverUrl = "https://example.com/song.jpg",
                localCoverUrl = "content://covers/song.jpg",
                onMainThread = true
            )
        )
    }

    @Test
    fun `resolveDisplayCoverUrl falls back to remote cover when local cover is unavailable`() {
        assertEquals(
            "https://example.com/song.jpg",
            resolveDisplayCoverUrl(
                customCoverUrl = null,
                currentCoverUrl = "https://example.com/song.jpg",
                localCoverUrl = null,
                onMainThread = true
            )
        )
    }
}
