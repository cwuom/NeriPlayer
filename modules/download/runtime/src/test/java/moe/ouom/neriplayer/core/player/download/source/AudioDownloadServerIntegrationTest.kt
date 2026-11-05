package moe.ouom.neriplayer.core.player.download.source

import android.content.Context
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.core.download.host.DownloadCredentials
import moe.ouom.neriplayer.core.download.host.DownloadEnvironment
import moe.ouom.neriplayer.core.download.host.DownloadHostBindings
import moe.ouom.neriplayer.core.download.host.DownloadHostRegistry
import moe.ouom.neriplayer.core.download.host.DownloadHosts
import moe.ouom.neriplayer.core.download.host.DownloadLyricServices
import moe.ouom.neriplayer.core.download.host.DownloadSourceServices
import moe.ouom.neriplayer.core.download.host.DownloadedPlaybackHost
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.lyrics.AudioDownloadLyricsCoordinator
import moe.ouom.neriplayer.core.player.download.runtime.resolveDownloadSourceForAttempt
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.data.model.settings.download.DownloadAudioQualitySelection
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicDownloadSource
import moe.ouom.neriplayer.platform.subsonic.repository.SubsonicRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AudioDownloadServerIntegrationTest {
    private val registry = DownloadHosts::class.java.getDeclaredField("registry")
        .apply { isAccessible = true }.get(null)
    private val installed = DownloadHostRegistry::class.java.getDeclaredField("installed")
        .apply { isAccessible = true }
    private var previousHosts: DownloadHostBindings? = null
    private val sources = mock(DownloadSourceServices::class.java)
    private val repository = mock(SubsonicRepository::class.java)
    private val ref = ServerSongRef("00000000-0000-0000-0000-000000000001", "song:中文/1")
    private val song = SongItem(ref.numericId, "Song", "Artist", "Album", 0L, 60_000L, null,
        channelId = ServerSongRef.CHANNEL, audioId = ref.audioId, mediaUri = ref.mediaUri)

    @Before
    fun installHosts() {
        previousHosts = installed.get(registry) as DownloadHostBindings?
        `when`(sources.subsonicRepository).thenReturn(repository)
        DownloadHosts.install(DownloadHostBindings(
            mock(DownloadEnvironment::class.java), sources, mock(DownloadLyricServices::class.java),
            mock(DownloadCredentials::class.java), mock(DownloadedPlaybackHost::class.java)))
    }

    @After
    fun restoreHosts() { installed.set(registry, previousHosts) }

    @Test
    fun `server resolver forwards raw metadata and never falls through to another platform`() = runBlocking<Unit> {
        `when`(repository.download(song)).thenReturn(SubsonicDownloadSource(
            ref.resourceUrl("download"), "audio/aac", "aac", 4096L, 60_000L))

        val result = AudioDownloadManager.resolveDownloadSourceForAttempt(song,
            DownloadAudioQualitySelection("lossless", "high", "hires"), false, false,
            AudioDownloadManager.DownloadExecutionAttemptState())

        assertEquals(ref.resourceUrl("download"), result?.url)
        assertEquals("audio/aac", result?.mimeType)
        assertEquals("aac", result?.fileExtensionHint)
        assertEquals(4096L, result?.contentLength)
        assertEquals(60_000L, result?.durationMs)
        verify(sources, never()).neteaseClient
        verify(sources, never()).biliClient
        verify(sources, never()).youtubeMusicPlaybackRepository
    }

    @Test
    fun `server auth refusal stays typed and never becomes a netease request`() = runBlocking<Unit> {
        val error = SubsonicException.http(401, null)
        doAnswer { throw error }.`when`(repository).download(song)
        val received = runCatching {
            AudioDownloadManager.resolveDownloadSourceForAttempt(song,
                DownloadAudioQualitySelection("lossless", "high", "hires"), false, false,
                AudioDownloadManager.DownloadExecutionAttemptState())
        }.exceptionOrNull()

        assertEquals(error, received)
        verify(sources, never()).neteaseClient
    }

    @Test
    fun `server lyrics become standalone sidecars without querying netease`() = runBlocking<Unit> {
        `when`(repository.lyrics(song)).thenReturn(listOf(LyricEntry("server line", 1230L, 5000L)))

        val result = downloadLyrics(song)

        assertEquals("[00:01.23]server line", result.lyricContent)
        assertTrue(result.expectedLyric)
        assertFalse(result.expectedTranslatedLyric)
        assertNull(result.translatedLyricContent)
        verify(repository).lyrics(song)
        verify(sources, never()).neteaseClient
    }

    @Test
    fun `explicit server lyric clear avoids remote lyrics and retains translated and romanized edits`() = runBlocking<Unit> {
        val edited = song.copy(matchedLyric = "", matchedTranslatedLyric = "[00:01.00]translation",
            matchedRomanizedLyric = "[00:01.00]romanized", lyricSyncEdited = true)

        val result = downloadLyrics(edited)

        assertNull(result.lyricContent)
        assertFalse(result.expectedLyric)
        assertEquals(edited.matchedTranslatedLyric, result.translatedLyricContent)
        assertEquals(edited.matchedRomanizedLyric, result.romanizedLyricContent)
        verify(repository, never()).lyrics(edited)
    }

    @Test
    fun `temporary server lyric failure reaches post core recovery instead of becoming no lyrics`() = runBlocking<Unit> {
        val failure = SubsonicException.http(503, "1")
        doAnswer { throw failure }.`when`(repository).lyrics(song)

        assertEquals(failure, runCatching { downloadLyrics(song) }.exceptionOrNull())
    }

    @Test
    fun `persisted resume uses only the reversible server reference and opaque resource URL`() {
        val raw = ManagedDownloadStorageJsonCodec.workingResumeMetadataToJson(song,
            moe.ouom.neriplayer.core.download.ManagedDownloadStorage.WorkingResumeFingerprint(
                sourceUrl = ref.resourceUrl("download"), etag = "\"raw-version\"", expectedContentLength = 4096L),
            operationId = "server-operation").toString()
        val restored = ManagedDownloadStorageJsonCodec.workingResumeMetadataSongFromJson(raw)

        assertEquals(song.stableKey(), restored?.stableKey())
        assertEquals(ref, restored?.let(ServerSongRef::from))
        assertFalse(raw.contains("password", ignoreCase = true))
        assertFalse(raw.contains("Authorization", ignoreCase = true))
        assertFalse(raw.contains("?"))
        assertEquals(ref.resourceUrl("download"),
            ManagedDownloadStorageJsonCodec.workingResumeFingerprintFromJson(raw)?.sourceUrl)
    }

    private suspend fun downloadLyrics(input: SongItem): AudioDownloadManager.DownloadedSidecarReferences {
        val context = mock(Context::class.java)
        `when`(context.getString(R.string.download_lyrics_matched, input.name)).thenReturn("matched")
        return AudioDownloadLyricsCoordinator.download(context, input, input.stableKey(), "server",
            ensureNotCancelled = { _, _, _, _, _, _ -> },
            writeSidecar = { _, stage, _, _, _, _, _ -> "/lyrics/$stage.lrc" },
            rememberPartial = { _, _, _ -> })
    }
}
