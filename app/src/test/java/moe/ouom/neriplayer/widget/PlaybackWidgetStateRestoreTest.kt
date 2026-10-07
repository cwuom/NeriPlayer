package moe.ouom.neriplayer.widget

import android.content.Context
import android.content.SharedPreferences
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.presentation.widget.PLAYBACK_WIDGET_PROGRESS_MAX
import moe.ouom.neriplayer.core.player.presentation.widget.PlaybackWidgetState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class PlaybackWidgetStateRestoreTest {

    @Test
    fun `widget without a saved title restores the idle state`() {
        val state = PlaybackWidgetUpdater.readState(context(emptyMap()))

        assertEquals(
            PlaybackWidgetState(
                title = APP_NAME,
                subtitle = IDLE_SUBTITLE,
                status = READY,
                positionMs = 0L,
                elapsedText = "0:00",
                durationText = "0:00",
                progress = 0,
                hasSong = false,
                isPlaying = false,
                isFavorite = false,
                canToggleFavorite = false,
                isFloatingLyricsEnabled = false,
                artworkReady = false
            ),
            state
        )
    }

    @Test
    fun `saved song restores as paused with clamped position and progress`() {
        val state = PlaybackWidgetUpdater.readState(
            context(
                mapOf(
                    "title" to "Song",
                    "subtitle" to "Artist",
                    "position_ms" to -5_000L,
                    "elapsed_text" to "1:05",
                    "duration_text" to "3:30",
                    "progress" to 5_000,
                    "has_song" to true,
                    "is_playing" to true,
                    "is_favorite" to true,
                    "can_toggle_favorite" to true,
                    "floating_lyrics_enabled" to true,
                    "artwork_ready" to true
                )
            )
        )

        assertEquals(
            PlaybackWidgetState(
                title = "Song",
                subtitle = "Artist",
                status = PAUSED,
                positionMs = 0L,
                elapsedText = "1:05",
                durationText = "3:30",
                progress = PLAYBACK_WIDGET_PROGRESS_MAX,
                hasSong = true,
                isPlaying = false,
                isFavorite = true,
                canToggleFavorite = true,
                isFloatingLyricsEnabled = true,
                artworkReady = true
            ),
            state
        )
    }

    @Test
    fun `partially saved entry fills missing text with idle defaults`() {
        val state = PlaybackWidgetUpdater.readState(
            context(mapOf("title" to "Song", "position_ms" to 42_000L, "progress" to -3))
        )

        assertEquals("Song", state.title)
        assertEquals(IDLE_SUBTITLE, state.subtitle)
        assertEquals(READY, state.status)
        assertEquals(42_000L, state.positionMs)
        assertEquals("0:00", state.elapsedText)
        assertEquals("0:00", state.durationText)
        assertEquals(0, state.progress)
        assertEquals(false, state.hasSong)
    }

    private fun context(values: Map<String, Any>): Context {
        val preferences = mock(SharedPreferences::class.java)
        `when`(preferences.contains(anyString())).thenAnswer { it.getArgument<String>(0) in values }
        `when`(preferences.getString(anyString(), nullable(String::class.java))).thenAnswer {
            values[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1)
        }
        `when`(preferences.getBoolean(anyString(), anyBoolean())).thenAnswer {
            values[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1)
        }
        `when`(preferences.getLong(anyString(), anyLong())).thenAnswer {
            values[it.getArgument<String>(0)] as? Long ?: it.getArgument<Long>(1)
        }
        `when`(preferences.getInt(anyString(), anyInt())).thenAnswer {
            values[it.getArgument<String>(0)] as? Int ?: it.getArgument<Int>(1)
        }

        val context = mock(Context::class.java)
        `when`(context.getSharedPreferences("neriplayer_playback_widget", Context.MODE_PRIVATE))
            .thenReturn(preferences)
        `when`(context.getString(CoreCommonR.string.app_name)).thenReturn(APP_NAME)
        `when`(context.getString(CoreCommonR.string.widget_playback_idle_subtitle)).thenReturn(IDLE_SUBTITLE)
        `when`(context.getString(CoreCommonR.string.widget_playback_ready)).thenReturn(READY)
        `when`(context.getString(CoreCommonR.string.widget_playback_paused)).thenReturn(PAUSED)
        return context
    }

    private companion object {
        const val APP_NAME = "NeriPlayer"
        const val IDLE_SUBTITLE = "Nothing is playing"
        const val READY = "Ready"
        const val PAUSED = "Paused"
    }
}
