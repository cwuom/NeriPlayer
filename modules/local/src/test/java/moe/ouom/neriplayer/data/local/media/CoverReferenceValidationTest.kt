package moe.ouom.neriplayer.data.local.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverReferenceValidationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `empty and media store album references are invalid while remote ones are usable`() {
        assertEquals(CoverReferenceValidation.INVALID, validate(" "))
        assertEquals(CoverReferenceValidation.INVALID, validate("content://media/external/audio/albumart/4"))
        assertEquals(CoverReferenceValidation.USABLE, validate("HTTPS://img.example.com/a.jpg"))
        assertEquals(CoverReferenceValidation.USABLE, validate("http://img.example.com/a.jpg"))
    }

    @Test
    fun `file references must point at a decodable image`() {
        val image = imageFile("cover.png")
        val text = tempFolder.newFile("notes.png").apply { writeText("not an image") }

        assertEquals(CoverReferenceValidation.USABLE, validate(Uri.fromFile(image).toString()))
        assertEquals(CoverReferenceValidation.INVALID, validate(Uri.fromFile(text).toString()))
        assertEquals(CoverReferenceValidation.INVALID, validate("file:///missing/cover.png"))
        assertEquals(CoverReferenceValidation.INVALID, validate("file:"))
    }

    @Test
    fun `schemeless references are only checked when they are absolute paths`() {
        val image = imageFile("absolute.png")

        assertEquals(CoverReferenceValidation.USABLE, validate(image.absolutePath))
        assertEquals(CoverReferenceValidation.INVALID, validate("relative/cover.png"))
    }

    @Test
    fun `unopenable content references are invalid and other schemes are trusted`() {
        assertEquals(
            CoverReferenceValidation.INVALID,
            validate("content://com.example.covers/missing.png")
        )
        assertEquals(CoverReferenceValidation.USABLE, validate("neri-cover://kept"))
    }

    @Test
    fun `preferred media references choose content uris then local paths then the raw uri`() {
        assertEquals(
            "content://media/external/audio/media/1",
            preferredLocalMediaReference("/music/a.mp3", "content://media/external/audio/media/1")
        )
        assertEquals("/music/a.mp3", preferredLocalMediaReference("/music/a.mp3", "file:///music/b.mp3"))
        assertEquals(
            "content://media/external/audio/media/2",
            preferredLocalMediaReference("content://media/external/audio/media/2", "file:///music/b.mp3")
        )
        assertEquals("file:///music/b.mp3", preferredLocalMediaReference(" ", "file:///music/b.mp3"))
        assertEquals(null, preferredLocalMediaReference(null, " "))
    }

    @Test
    fun `editable cover mime types prefer image bytes over the reference name`() {
        val png = imageFile("named.gif").readBytes()
        val unknown = byteArrayOf(1, 2, 3)

        assertEquals("image/png", LocalMediaSupport.resolveEditableCoverMimeType(context, "file:///covers/named.gif", png))
        assertEquals("image/gif", LocalMediaSupport.resolveEditableCoverMimeType(context, "file:///covers/named.gif", unknown))
        assertEquals("image/png", LocalMediaSupport.resolveEditableCoverMimeType(context, "/covers/plain.PNG", unknown))
        assertEquals("image/jpeg", LocalMediaSupport.resolveEditableCoverMimeType(context, "file:///covers/notes.txt", unknown))
        assertEquals(
            "image/jpeg",
            LocalMediaSupport.resolveEditableCoverMimeType(context, "content://com.example.covers/7", unknown)
        )
    }

    @Test
    fun `editable cover mime types use image types declared by the provider`() {
        Robolectric.setupContentProvider(TypedCoverProvider::class.java, TYPED_COVER_AUTHORITY)
        val unknown = byteArrayOf(1, 2, 3)

        assertEquals(
            "image/webp",
            LocalMediaSupport.resolveEditableCoverMimeType(context, "content://$TYPED_COVER_AUTHORITY/webp", unknown)
        )
        assertEquals(
            "image/jpeg",
            LocalMediaSupport.resolveEditableCoverMimeType(context, "content://$TYPED_COVER_AUTHORITY/jpg", unknown)
        )
        assertEquals(
            "image/gif",
            LocalMediaSupport.resolveEditableCoverMimeType(context, "content://$TYPED_COVER_AUTHORITY/text/cover.gif", unknown)
        )
        assertEquals(
            "image/png",
            LocalMediaSupport.resolveEditableCoverMimeType(context, "content://$TYPED_COVER_AUTHORITY/broken/cover.png", unknown)
        )
    }

    private fun validate(reference: String): CoverReferenceValidation =
        validateCoverReference(context, Uri.parse(reference))

    private fun imageFile(name: String): File = tempFolder.newFile(name).also { file ->
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", file)
    }

    class TypedCoverProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String? = when (uri.pathSegments.first()) {
            "webp" -> " image/webp ; q=1"
            "jpg" -> "IMAGE/JPG"
            "text" -> "text/plain"
            else -> throw SecurityException("type unavailable")
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }

    private companion object {
        const val TYPED_COVER_AUTHORITY = "moe.ouom.neriplayer.test.covers"
    }
}
