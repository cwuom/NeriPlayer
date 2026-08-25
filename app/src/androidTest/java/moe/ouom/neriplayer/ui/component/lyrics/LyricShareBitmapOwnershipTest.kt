package moe.ouom.neriplayer.ui.component.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricShareBitmapOwnershipTest {
    @Test
    fun copiedCoverBitmapDoesNotAliasCoilBitmap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xFF336699.toInt())
        val copied = copyDrawableToOwnedBitmap(
            drawable = BitmapDrawable(context.resources, source),
            width = 8,
            height = 8,
            config = Bitmap.Config.ARGB_8888
        )

        try {
            assertNotSame(source, copied)
            assertFalse(source.isRecycled)
            assertEquals(source.getPixel(0, 0), copied.getPixel(0, 0))

            copied.recycle()

            assertFalse(source.isRecycled)
        } finally {
            if (!source.isRecycled) {
                source.recycle()
            }
            if (!copied.isRecycled) {
                copied.recycle()
            }
        }
    }
}
