package moe.ouom.neriplayer.util.media

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapOwnershipTest {

    @Test
    fun retainedDisplayBitmapDoesNotAliasSourceBitmap() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xFF336699.toInt())
        val retained = copyBitmapForRetainedDisplay(source)
        assertNotNull(retained)

        try {
            val retainedBitmap = checkNotNull(retained)
            assertNotSame(source, retainedBitmap)
            assertEquals(source.getPixel(0, 0), retainedBitmap.getPixel(0, 0))

            retainedBitmap.recycle()
            assertFalse(source.isRecycled)
        } finally {
            if (!source.isRecycled) {
                source.recycle()
            }
            if (retained != null && !retained.isRecycled) {
                retained.recycle()
            }
        }
    }
}
