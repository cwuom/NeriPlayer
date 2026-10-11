package moe.ouom.neriplayer.ui.util

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FastCoverReferenceTest {

    private val cover = File.createTempFile("fast-cover", ".jpg").apply { writeText("cover") }

    @After
    fun deleteCover() {
        cover.delete()
    }

    @Test
    fun `remote and provider references are fast without touching storage`() {
        assertTrue(isFastCoverReference(" HTTPS://example.com/cover.jpg "))
        assertTrue(isFastCoverReference("http://example.com/cover.jpg"))
        assertTrue(isFastCoverReference("content://provider/cover"))
        assertTrue(isFastCoverReference("android.resource://moe.ouom.neriplayer/1"))
        assertFalse(isFastCoverReference("   "))
    }

    @Test
    fun `file references are fast only when the file exists`() {
        assertTrue(isFastCoverReference(cover.absolutePath))
        assertTrue(isFastCoverReference(cover.toURI().toString()))
        assertFalse(isFastCoverReference("file://${cover.absolutePath}.missing"))
        assertFalse(isFastCoverReference("${cover.absolutePath}.missing"))
        assertFalse(isFastCoverReference("cover.jpg"))
    }
}
