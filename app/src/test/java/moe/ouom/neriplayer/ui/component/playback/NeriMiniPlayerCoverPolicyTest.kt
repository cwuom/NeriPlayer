package moe.ouom.neriplayer.ui.component.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NeriMiniPlayerCoverPolicyTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val requested = MiniPlayerCoverFrame(
        coverUrl = "https://img/next.jpg",
        identityKey = "song:next",
        requestToken = "req-next"
    )
    private val retained = MiniPlayerCoverFrame(
        coverUrl = "https://img/prev.jpg",
        identityKey = "song:prev",
        requestToken = "req-prev"
    )

    @Test
    fun `displayed cover url ignores blank urls and waits for the request to load`() {
        assertEquals(
            "https://img/prev.jpg",
            resolveMiniPlayerDisplayedCoverUrl("  ", " https://img/prev.jpg ", requestSucceeded = false)
        )
        assertNull(resolveMiniPlayerDisplayedCoverUrl(null, "https://img/prev.jpg", false, clearDelayElapsed = true))
        assertNull(resolveMiniPlayerDisplayedCoverUrl(null, "", requestSucceeded = false))
        assertEquals(
            "https://img/prev.jpg",
            resolveMiniPlayerDisplayedCoverUrl("https://img/next.jpg", "https://img/prev.jpg", false)
        )
        assertEquals(
            "https://img/next.jpg",
            resolveMiniPlayerDisplayedCoverUrl(" https://img/next.jpg", "https://img/prev.jpg", true)
        )
        assertEquals(
            "https://img/next.jpg",
            resolveMiniPlayerDisplayedCoverUrl("https://img/next.jpg", "https://img/next.jpg ", false)
        )
        assertNull(resolveMiniPlayerDisplayedCoverUrl("https://img/next.jpg", null, false))
    }

    @Test
    fun `cover identity prefers a non blank identity key over the url`() {
        assertEquals("song:1", miniPlayerCoverIdentityKey(" song:1 ", "https://img/a.jpg"))
        assertEquals("https://img/a.jpg", miniPlayerCoverIdentityKey("  ", " https://img/a.jpg "))
        assertEquals("https://img/a.jpg", miniPlayerCoverIdentityKey(null, "https://img/a.jpg"))
        assertNull(miniPlayerCoverIdentityKey(null, " "))
        assertNull(miniPlayerCoverIdentityKey(null, null))
    }

    @Test
    fun `completed frame commits against the latest request when one exists`() {
        assertTrue(shouldCommitMiniPlayerCoverFrame(requested, requested.copy(), retained, "other"))
        assertFalse(shouldCommitMiniPlayerCoverFrame(retained, requested, retained, null))
    }

    @Test
    fun `retained frame commits only while it still matches the current identity`() {
        assertFalse(shouldCommitMiniPlayerCoverFrame(retained, null, null, null))
        assertFalse(shouldCommitMiniPlayerCoverFrame(retained, null, requested, null))
        assertTrue(shouldCommitMiniPlayerCoverFrame(retained, null, retained, null))
        assertTrue(shouldCommitMiniPlayerCoverFrame(retained, null, retained, "  "))
        assertTrue(shouldCommitMiniPlayerCoverFrame(retained, null, retained, " song:prev "))
        assertFalse(shouldCommitMiniPlayerCoverFrame(retained, null, retained, "song:next"))
    }

    @Test
    fun `visible frame is empty without a song or after the retained cover is cleared`() {
        assertNull(resolveMiniPlayerVisibleCoverFrame(requested, null, null, retained, hasCurrentSong = false))
        assertNull(resolveMiniPlayerVisibleCoverFrame(requested, null, null, retained, clearRetainedFrame = true))
        assertNull(resolveMiniPlayerVisibleCoverFrame(null, null, null, null))
    }

    @Test
    fun `decoded retained frame stays visible even if its request later failed`() {
        val decoded = retained.copy(decodedBitmap = ImageBitmap(1, 1))

        assertSame(
            decoded,
            resolveMiniPlayerVisibleCoverFrame(requested, null, decoded, retained, failedFrame = retained)
        )
    }

    @Test
    fun `undecoded retained frame yields to the request once it fails`() {
        assertSame(retained, resolveMiniPlayerVisibleCoverFrame(requested, null, null, retained))
        assertSame(
            requested,
            resolveMiniPlayerVisibleCoverFrame(requested, null, null, retained, failedFrame = retained)
        )
        assertNull(
            resolveMiniPlayerVisibleCoverFrame(requested, null, null, null, failedFrame = requested)
        )
    }

    @Test
    fun `undecoded retained frame for the same cover adopts the newer request token`() {
        val sameCoverOldToken = requested.copy(requestToken = "req-old")

        assertSame(
            requested,
            resolveMiniPlayerVisibleCoverFrame(requested, sameCoverOldToken, null, null)
        )
        assertNull(
            resolveMiniPlayerVisibleCoverFrame(
                requested,
                sameCoverOldToken,
                null,
                null,
                failedFrame = requested
            )
        )
    }

    @Test
    fun `base font size accepts positive sp sizes only`() {
        assertEquals(14f, resolveMiniPlayerBaseFontSizeSp(14.sp), 0f)
        assertEquals(16f, resolveMiniPlayerBaseFontSizeSp(1.2.em), 0f)
        assertEquals(16f, resolveMiniPlayerBaseFontSizeSp(0.sp), 0f)
        assertEquals(16f, resolveMiniPlayerBaseFontSizeSp(TextUnit.Unspecified), 0f)
    }

    @Test
    fun `large bitmap covers are downscaled to the mini player size`() {
        val source = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)

        val bitmap = resolveMiniPlayerCoverBitmap(BitmapDrawable(context.resources, source))

        assertEquals(128, bitmap?.width)
        assertEquals(64, bitmap?.height)
    }

    @Test
    fun `small bitmap covers are copied so the source can be recycled`() {
        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)

        val bitmap = resolveMiniPlayerCoverBitmap(BitmapDrawable(context.resources, source))
        source.recycle()

        assertEquals(64, bitmap?.width)
        assertEquals(32, bitmap?.height)
        assertNull(
            resolveMiniPlayerCoverBitmap(BitmapDrawable(context.resources, source))
        )
    }

    @Test
    fun `non bitmap drawables are rasterized at the maximum cover size`() {
        val bitmap = resolveMiniPlayerCoverBitmap(ColorDrawable(Color.RED))

        assertEquals(128, bitmap?.width)
        assertEquals(128, bitmap?.height)
    }

    @Test
    fun `line height ratio uses sp metrics and falls back otherwise`() {
        assertEquals(1.25f, TextStyle(fontSize = 16.sp, lineHeight = 20.sp).miniPlayerLineHeightEm(), 0f)
        assertEquals(1.5f, TextStyle(fontSize = 16.sp, lineHeight = 1.2.em).miniPlayerLineHeightEm(), 0f)
        assertEquals(1.5f, TextStyle(fontSize = 1.em, lineHeight = 20.sp).miniPlayerLineHeightEm(), 0f)
        assertEquals(1.5f, TextStyle(fontSize = 16.sp).miniPlayerLineHeightEm(), 0f)
        assertEquals(1.5f, TextStyle(fontSize = 0.sp, lineHeight = 20.sp).miniPlayerLineHeightEm(), 0f)
        assertEquals(1.5f, TextStyle(fontSize = 16.sp, lineHeight = 0.sp).miniPlayerLineHeightEm(), 0f)
        assertEquals(1.5f, TextStyle(fontSize = TextUnit.Unspecified).miniPlayerLineHeightEm(), 0f)
    }
}
