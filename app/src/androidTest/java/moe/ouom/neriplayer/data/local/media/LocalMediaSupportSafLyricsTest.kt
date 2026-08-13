package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMediaSupportSafLyricsTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun inspectContentDocumentReadsLyricsDirectorySidecarsForOpaqueDocumentIds() {
        val audioUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )

        val details = LocalMediaSupport.inspect(targetContext, audioUri)

        assertEquals(
            "[00:00.10]original from Lyrics",
            details.lyricContent
        )
        assertEquals(
            "[00:00.10]translated from Lyrics",
            details.translatedLyricContent
        )
        assertEquals(
            "[00:00.10]romanized from Lyrics",
            details.romanizedLyricContent
        )
        assertNotNull(details.lyricPath)
        assertEquals("content", Uri.parse(details.lyricPath).scheme)
    }

    @Test
    fun inspectPlainDocumentReadsLyricsDirectorySidecars() {
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )

        val details = LocalMediaSupport.inspect(targetContext, audioUri)

        assertEquals(
            "[00:00.10]original from Lyrics",
            details.lyricContent
        )
        assertEquals(
            "[00:00.10]translated from Lyrics",
            details.translatedLyricContent
        )
        assertEquals(
            "[00:00.10]romanized from Lyrics",
            details.romanizedLyricContent
        )
        assertNotNull(details.lyricPath)
    }

    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        Issue339LyricsTestDocumentProvider.AUTHORITY,
        Issue339LyricsTestDocumentProvider.ROOT_ID
    )
}
