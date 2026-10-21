package moe.ouom.neriplayer.data.local.media

import android.content.ContentValues
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.playback.LocalPlaybackReferenceResolution
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class LocalMediaStorePlaybackReferenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mediaStoreAudioWithLocalChannelAndNamedAlbumRemainsPlayable() = runBlocking {
        withMediaStoreAudio { uri, displayName ->
            assertPlayable(localSong(uri, displayName))
        }
    }

    @Test
    fun renamingMediaStoreAudioPreservesThePlayableReference() = runBlocking {
        withMediaStoreAudio { uri, displayName ->
            val song = localSong(uri, displayName)
            val renamed = "renamed-$displayName"
            assertEquals(
                1,
                context.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, renamed)
                    },
                    null,
                    null
                )
            )
            requireNotNull(
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(renamed, cursor.getString(0))
            }

            assertPlayable(song.copy(localFileName = renamed))
        }
    }

    private suspend fun assertPlayable(song: SongItem) {
        val uri = requireNotNull(song.mediaUri)
        assertEquals(
            ManagedDownloadReferenceLookup.Result.Present,
            ManagedDownloadReferenceLookup.inspect(context, song.mediaUri)
        )
        assertEquals(
            LocalPlaybackReferenceResolution.Playable(uri),
            AudioDownloadManager.resolvePermittedLocalPlayback(context, song, song.mediaUri)
        )
        val player = MediaPlayer()
        try {
            player.setDataSource(context, Uri.parse(uri))
            player.prepare()
            assertTrue("the resolved MediaStore audio must decode", player.duration > 0)
        } finally {
            player.release()
        }
    }

    private suspend fun withMediaStoreAudio(block: suspend (Uri, String) -> Unit) {
        val displayName = "neriplayer-playback-${UUID.randomUUID()}.wav"
        val resolver = context.contentResolver
        val uri = requireNotNull(
            resolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/NeriPlayer-tests")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        )
        try {
            requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(silentWav()) }
            assertEquals(
                1,
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            )
            block(uri, displayName)
        } finally {
            assertEquals("remove only the test-owned MediaStore row", 1, resolver.delete(uri, null, null))
        }
    }

    private fun localSong(uri: Uri, displayName: String): SongItem {
        val id = UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE
        return SongItem(
            id = id,
            name = "MediaStore title",
            artist = "MediaStore artist",
            album = "Named local album",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = uri.toString(),
            localFileName = displayName,
            channelId = "local",
            audioId = id.toString()
        )
    }

    private fun silentWav(): ByteArray {
        val sampleRate = 8_000
        val pcm = ByteArray(sampleRate * 2)
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
            put(pcm)
        }.array()
    }
}
