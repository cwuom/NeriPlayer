package moe.ouom.neriplayer.core.player.debug

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerDebugNamesTest {

    @Test
    fun `playback states have readable names`() {
        assertEquals(
            listOf("IDLE", "BUFFERING", "READY", "ENDED", "UNKNOWN(99)"),
            listOf(Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED, 99)
                .map(::playbackStateName)
        )
    }

    @Test
    fun `play when ready change reasons have readable names`() {
        assertEquals(
            listOf(
                "USER_REQUEST",
                "AUDIO_FOCUS_LOSS",
                "AUDIO_BECOMING_NOISY",
                "REMOTE",
                "END_OF_MEDIA_ITEM",
                "SUPPRESSED_TOO_LONG",
                "UNKNOWN(0)"
            ),
            listOf(
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG,
                0
            ).map(::playWhenReadyChangeReasonName)
        )
    }

    @Test
    fun `playback suppression reasons keep the legacy audio route name`() {
        assertEquals(
            listOf(
                "NONE",
                "TRANSIENT_AUDIO_FOCUS_LOSS",
                "UNSUITABLE_AUDIO_ROUTE",
                "UNSUITABLE_AUDIO_OUTPUT",
                "SCRUBBING",
                "UNKNOWN(9)"
            ),
            listOf(
                Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
                2,
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT,
                Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING,
                9
            ).map(::playbackSuppressionReasonName)
        )
    }
}
