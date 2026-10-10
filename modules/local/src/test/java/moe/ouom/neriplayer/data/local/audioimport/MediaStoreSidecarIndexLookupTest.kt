package moe.ouom.neriplayer.data.local.audioimport

import android.content.Context
import android.net.Uri
import moe.ouom.neriplayer.data.local.media.LocalKnownSidecarReferences
import moe.ouom.neriplayer.data.local.media.NearbyLyricReferences
import moe.ouom.neriplayer.data.model.download.DownloadLibraryEntry
import moe.ouom.neriplayer.data.model.download.DownloadLibrarySnapshot
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class MediaStoreSidecarIndexLookupTest {
    @Test
    fun `each lyric name is looked up in root lyrics then nested lyrics then the folder`() = withPlainUriDecoding {
        val references = resolver("Music").resolveSidecarReferences(
            directIndex = mapOf(
                "song.lrc" to "direct-original",
                "song_trans.lrc" to "direct-translated",
                "song_romanized.txt" to "direct-romanized"
            ),
            nestedIndex = mapOf("song_trans.lrc" to "nested-translated"),
            rootLyricsIndex = mapOf("song.lrc" to "root-original"),
            displayName = "Song.MP3",
            baseName = "Song",
            metadataIndex = mapOf("song.mp3" to "metadata")
        )

        assertEquals(
            LocalKnownSidecarReferences(
                lyrics = NearbyLyricReferences(
                    original = "root-original",
                    translated = "nested-translated",
                    romanized = "direct-romanized"
                ),
                metadata = "metadata"
            ),
            references
        )
    }

    @Test
    fun `missing lyric and metadata sidecars resolve to empty references`() = withPlainUriDecoding {
        val references = resolver("Music").resolveSidecarReferences(
            directIndex = mapOf("other.lrc" to "direct-other"),
            nestedIndex = emptyMap(),
            rootLyricsIndex = emptyMap(),
            displayName = "song.flac",
            baseName = "song",
            metadataIndex = mapOf("other.flac" to "metadata")
        )

        assertEquals(
            LocalKnownSidecarReferences(lyrics = NearbyLyricReferences(null, null, null)),
            references
        )
    }

    @Test
    fun `document metadata index keeps the best non directory sidecar per audio name`() = withPlainUriDecoding {
        val index = resolver("Music").buildDocumentMetadataIndex(
            listOf(
                document("song.mp3.npmeta (2).json", "content://tree/b-numbered"),
                document("Song.mp3.npmeta.json", "content://tree/z-exact"),
                document("song.mp3.npmeta.json", "content://tree/a-exact"),
                document("SONG.mp3.npmeta.json", "content://tree/y-exact"),
                document("song.mp3.npmeta (3).json", "content://tree/0-numbered"),
                document("demo.mp3.npmeta.json", "content://tree/folder", isDirectory = true),
                document("live.flac.npmeta.pending.json", "content://tree/live-pending"),
                document("cover.jpg", "content://tree/cover")
            )
        )

        assertEquals(
            mapOf(
                "song.mp3" to "content://tree/a-exact",
                "live.flac" to "content://tree/live-pending"
            ),
            index
        )
    }

    @Test
    fun `rows outside the selected folder have no sidecar directory`() = withPlainUriDecoding {
        val resolver = resolver("Music/Album")

        assertNull(resolver.directoryFor("Music"))
        assertNull(resolver.directoryFor("Podcasts/Album"))
        assertNull(resolver.resolve(relativePath = "Podcasts/Album", displayName = "song.mp3"))
        assertNull(resolver.resolveAudioReference(relativePath = "Music", displayName = "song.mp3"))
        assertNull(resolver.resolveNearbyCoverReference(relativePath = "Music", displayName = "song.mp3"))
    }

    @Test
    fun `an unreadable selected folder yields an empty sidecar index`() = withPlainUriDecoding {
        val resolver = resolver("Music/Album")
        val empty = MediaStoreSidecarResolver.DirectorySidecarIndex(
            directIndex = emptyMap(),
            lyricsIndex = emptyMap(),
            coverIndex = emptyMap(),
            metadataIndex = emptyMap(),
            audioIndex = emptyMap()
        )

        assertEquals(empty, resolver.directoryFor(null))
        assertEquals(
            LocalKnownSidecarReferences(lyrics = NearbyLyricReferences(null, null, null)),
            resolver.resolve(relativePath = "Music/Album", displayName = "song.mp3")
        )
        assertNull(resolver.resolveAudioReference(relativePath = null, displayName = "song.mp3"))
        assertNull(resolver.resolveNearbyCoverReference(relativePath = null, displayName = "song.mp3"))
    }

    @Test
    fun `missing subfolders are remembered as absent`() = withPlainUriDecoding {
        val resolver = resolver("Music/Album")

        assertNull(resolver.directoryFor("Music/Album/Disc 1"))
        assertTrue(resolver.directoryCache.containsKey("disc 1"))
        assertNull(resolver.directoryCache["disc 1"])
    }

    @Test
    fun `managed sidecar index without a library snapshot withholds managed rows`() {
        val index = ManagedMediaStoreSidecarIndex(snapshot = null, treeDocumentId = null)

        assertTrue(index.audioByName.isEmpty())
        assertNull(index.resolve(relativePath = "Music/NeriPlayer", displayName = "song.mp3"))
        assertEquals(
            ManagedDownloadCandidatePublication.WITHHELD,
            index.publicationGate.evaluate(isInsideManagedRoot = true, displayName = "song.mp3")
        )
    }

    @Test
    fun `managed sidecar index keys downloaded audio by lower case name`() {
        val song = LibraryEntry(name = "Song.MP3")
        val writing = LibraryEntry(name = "writing.flac", isPendingAudioWrite = true)
        val index = ManagedMediaStoreSidecarIndex(
            snapshot = LibrarySnapshot(audioEntries = listOf(song, writing)),
            treeDocumentId = "primary:Music/NeriPlayer"
        )

        assertEquals(mapOf("song.mp3" to song, "writing.flac" to writing), index.audioByName)
        assertEquals(
            ManagedDownloadCandidatePublication.NON_MANAGED,
            index.publicationGate.evaluate(isInsideManagedRoot = false, displayName = "elsewhere.mp3")
        )
        assertEquals(
            ManagedDownloadCandidatePublication.WITHHELD,
            index.publicationGate.evaluate(
                isInsideManagedRoot = false,
                displayName = "copy.flac",
                candidateReferences = listOf(writing.reference)
            )
        )
    }

    private fun resolver(selectedRelativePath: String) = MediaStoreSidecarResolver(
        context = mock(Context::class.java),
        folderUri = mock(Uri::class.java),
        selectedRelativePath = selectedRelativePath
    )

    private fun document(name: String, reference: String, isDirectory: Boolean = false): QueriedFolderChild {
        val uri = mock(Uri::class.java)
        doReturn(reference).`when`(uri).toString()
        return QueriedFolderChild(
            documentUri = uri,
            displayName = name,
            mimeType = if (isDirectory) "vnd.android.document/directory" else "application/json",
            isDirectory = isDirectory
        )
    }

    private fun withPlainUriDecoding(block: () -> Unit) {
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uri ->
            uri.`when`<String> { Uri.decode(anyString()) }.thenAnswer { it.getArgument<String>(0) }
            block()
        }
    }

    private data class LibraryEntry(
        override val name: String,
        override val reference: String = "content://tree/document/$name",
        override val mediaUri: String = reference,
        override val localFilePath: String? = null,
        override val sizeBytes: Long = 1L,
        override val lastModifiedMs: Long = 1L,
        override val sizeKnown: Boolean = true,
        override val isDirectory: Boolean = false,
        override val isPendingAudioWrite: Boolean = false,
        override val logicalName: String = name,
        override val extension: String = name.substringAfterLast('.', ""),
        override val nameWithoutExtension: String = name.substringBeforeLast('.'),
        override val playbackUri: String = mediaUri,
        override val displayName: String = name
    ) : DownloadLibraryEntry

    private data class LibrarySnapshot(
        override val audioEntries: List<DownloadLibraryEntry>,
        override val audioEntriesByLookupKey: Map<String, DownloadLibraryEntry> = emptyMap(),
        override val metadataEntriesByAudioName: Map<String, DownloadLibraryEntry> = emptyMap(),
        override val metadataByAudioName: Map<String, DownloadedAudioMetadata> = emptyMap(),
        override val coverEntriesByName: Map<String, DownloadLibraryEntry> = emptyMap(),
        override val lyricEntriesByName: Map<String, DownloadLibraryEntry> = emptyMap(),
        override val knownReferences: Set<String> = emptySet(),
        override val rootEntriesComplete: Boolean = true,
        override val sidecarEntriesComplete: Boolean = true,
        override val pendingAudioEntries: List<DownloadLibraryEntry> = emptyList(),
        override val pendingMetadataByAudioName: Map<String, DownloadedAudioMetadata> = emptyMap(),
        override val metadataEntriesByCanonicalAudioName: Map<String, DownloadLibraryEntry> = emptyMap(),
        override val metadataByCanonicalAudioName: Map<String, DownloadedAudioMetadata> = emptyMap(),
        override val pendingMetadataByCanonicalAudioName: Map<String, DownloadedAudioMetadata> = emptyMap(),
        override val metadataByDeclaredAudioName: Map<String, DownloadedAudioMetadata> = emptyMap()
    ) : DownloadLibrarySnapshot {
        override fun metadataForAudioEntry(audio: DownloadLibraryEntry): DownloadedAudioMetadata? = null
    }
}
