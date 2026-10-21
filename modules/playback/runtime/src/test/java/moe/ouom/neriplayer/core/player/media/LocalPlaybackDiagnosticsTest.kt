package moe.ouom.neriplayer.core.player.media

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.util.Log
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocalPlaybackDiagnosticsTest {
    private val context = mock(Context::class.java)
    private val resolver = mock(ContentResolver::class.java)
    private val reference = "content://provider.example/document/song.flac?token=secret#private"

    @Test
    fun `provider denial reports the failed stage without exception secrets or extra probes`() {
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.openAssetFileDescriptor(Uri.parse(reference), "r"))
            .thenThrow(SecurityException("token=secret password=private"))
        ShadowLog.clear()

        assertEquals(reference, LocalPlaybackMediaResolver.source(song(), context))

        val traces = ShadowLog.getLogs().filter { it.msg.contains("LocalPlaybackTrace") }
        assertTrue(traces.any { it.type == Log.WARN && it.msg.contains("stage=provider_probe") && it.msg.contains("java.lang.SecurityException") })
        assertTrue(traces.any { it.type == Log.WARN && it.msg.contains("id=42") && it.msg.contains("result=no_readable_source") })
        assertFalse(traces.any { it.msg.contains("secret") || it.msg.contains("private") })
        verify(resolver, times(1)).openAssetFileDescriptor(Uri.parse(reference), "r")
    }

    @Test
    fun `readable source records the selected reference without changing it`() {
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.openAssetFileDescriptor(Uri.parse(reference), "r"))
            .thenReturn(mock(AssetFileDescriptor::class.java))
        ShadowLog.clear()

        assertEquals(reference, LocalPlaybackMediaResolver.source(song(), context))

        val traces = ShadowLog.getLogs().filter { it.msg.contains("LocalPlaybackTrace") }
        assertTrue(traces.any { it.msg.contains("stage=source") && it.msg.contains("id=42") && it.msg.contains("result=readable") })
        assertFalse(traces.any { it.msg.contains("secret") || it.msg.contains("private") })
        verify(resolver, times(1)).openAssetFileDescriptor(Uri.parse(reference), "r")
    }

    private fun song() = SongItem(
        id = 42L, name = "Local song", artist = "Artist", album = "Named album", albumId = 0L,
        durationMs = 1_000L, coverUrl = null, mediaUri = reference, channelId = "local"
    )
}
