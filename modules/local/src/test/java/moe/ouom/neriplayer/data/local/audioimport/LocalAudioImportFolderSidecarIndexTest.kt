package moe.ouom.neriplayer.data.local.audioimport

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

class LocalAudioImportFolderSidecarIndexTest {

    @Test
    fun `provider cover documents are indexed once under their lowercase name`() {
        val index = with(LocalAudioImportManager) {
            buildSafCoverIndex(
                listOf(
                    document("content://tree/album-dir", "Album.jpg", isFile = false),
                    document("content://tree/cover-upper", "Cover.JPG"),
                    document("content://tree/cover-lower", "cover.jpg"),
                    document("content://tree/lyrics", "Night Drive.lrc"),
                    document("content://tree/audio", "Night Drive.flac"),
                    document("content://tree/unnamed", null),
                    document("content://tree/folder", "folder.webp")
                )
            )
        }

        assertEquals(
            mapOf("cover.jpg" to "content://tree/cover-upper", "folder.webp" to "content://tree/folder"),
            index
        )
    }

    @Test
    fun `provider sidecars are indexed by lowercase name without folders or audio`() {
        val index = with(LocalAudioImportManager) {
            buildSafDocumentNameIndex(
                listOf(
                    document("content://tree/lyrics", "Night Drive.LRC"),
                    document("content://tree/cover", "Cover.jpg"),
                    document("content://tree/meta", "Night Drive.flac.npmeta.json"),
                    document("content://tree/audio", "Night Drive.flac"),
                    document("content://tree/lyrics-dir", "Lyrics.txt", isFile = false),
                    document("content://tree/unnamed", null)
                )
            )
        }

        assertEquals(
            mapOf(
                "night drive.lrc" to "content://tree/lyrics",
                "cover.jpg" to "content://tree/cover",
                "night drive.flac.npmeta.json" to "content://tree/meta"
            ),
            index
        )
    }

    @Test
    fun `provider documents removed or renamed while indexing are left out`() {
        fun documents() = listOf(
            document("content://tree/removed", "front.png", null),
            document("content://tree/renamed", "back.jpg", "  "),
            document("content://tree/cover", "cover.jpg")
        )
        val expected = mapOf("cover.jpg" to "content://tree/cover")

        with(LocalAudioImportManager) {
            assertEquals(expected, buildSafCoverIndex(documents()))
            assertEquals(expected, buildSafDocumentNameIndex(documents()))
        }
    }

    @Test
    fun `metadata indexes prefer the canonical sidecar, then the lowest copy number, then the smallest uri`() {
        val sidecars = listOf(
            "content://tree/drive-copy" to "Night Drive.flac.npmeta (1).json",
            "content://tree/cover" to "cover.jpg",
            "content://tree/drive" to "Night Drive.flac.npmeta.json",
            "content://tree/drive-pending" to "Night Drive.flac.npmeta.pending.json",
            "content://tree/rain-z" to "Rain.mp3.npmeta.json",
            "content://tree/rain-a" to "RAIN.mp3.npmeta.json",
            "content://tree/rain-m" to "rain.MP3.npmeta.json"
        )
        val expected = mapOf("night drive.flac" to "content://tree/drive", "rain.mp3" to "content://tree/rain-a")

        val queried = sidecars.map { (uri, name) -> queriedChild(uri, name, isDirectory = false) } +
            queriedChild("content://tree/dir", "Album.npmeta.json", isDirectory = true)
        val documents = sidecars.map { (uri, name) -> document(uri, name) } +
            document("content://tree/gone", "Gone.flac.npmeta.json", null) +
            document("content://tree/dir", "Album.npmeta.json", isFile = false)

        with(LocalAudioImportManager) {
            assertEquals(expected, buildDocumentMetadataIndex(queried))
            assertEquals(expected, buildSafDocumentMetadataIndex(documents))
        }
    }

    private fun document(
        uri: String,
        name: String?,
        vararg laterNames: String?,
        isFile: Boolean = true
    ): DocumentFile = mock(DocumentFile::class.java).also { document ->
        doReturn(isFile).`when`(document).isFile
        doReturn(name, *laterNames).`when`(document).name
        doReturn(uri(uri)).`when`(document).uri
    }

    private fun queriedChild(uri: String, name: String, isDirectory: Boolean) = QueriedFolderChild(
        documentUri = uri(uri),
        displayName = name,
        mimeType = if (isDirectory) "vnd.android.document/directory" else "application/octet-stream",
        isDirectory = isDirectory
    )

    private fun uri(value: String): Uri = mock(Uri::class.java).also { uri ->
        doReturn(value).`when`(uri).toString()
    }
}
