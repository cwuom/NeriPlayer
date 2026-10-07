package moe.ouom.neriplayer.util.media

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageSourceDetectionTest {

    @Test
    fun `content file resource and absolute path sources are local`() {
        assertTrue(isLocalImageSource("content://media/external/images/media/1"))
        assertTrue(isLocalImageSource("file:///sdcard/Music/cover.jpg"))
        assertTrue(isLocalImageSource("android.resource://moe.ouom.neriplayer/drawable/cover"))
        assertTrue(isLocalImageSource("/storage/emulated/0/Music/cover.png"))
    }

    @Test
    fun `scheme matching ignores case surrounding whitespace and the source type`() {
        assertTrue(isLocalImageSource("  CONTENT://media/external/images/media/1  "))
        assertTrue(isLocalImageSource(File("/storage/emulated/0/Music/cover.png")))
    }

    @Test
    fun `remote relative and missing sources are not local`() {
        assertFalse(isLocalImageSource(null))
        assertFalse(isLocalImageSource(""))
        assertFalse(isLocalImageSource("https://example.invalid/cover.jpg"))
        assertFalse(isLocalImageSource("covers/cover.jpg"))
    }
}
