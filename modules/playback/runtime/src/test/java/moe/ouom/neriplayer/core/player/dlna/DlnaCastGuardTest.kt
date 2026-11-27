package moe.ouom.neriplayer.core.player.dlna

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.dlna.session.PlayerManagerDlnaAdapter
import moe.ouom.neriplayer.core.player.lan.LanPlaybackBinding
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DlnaCastGuardTest {

    @Test
    fun castingIsBlockedWhileListenTogetherOwnsPlayback() {
        val applicationField = PlayerManager::class.java.getDeclaredField("application").apply { isAccessible = true }
        val previousApplication = applicationField.get(null)
        val previousExternalAudio = LanPlaybackBinding.externalAudio
        try {
            applicationField.set(null, RuntimeEnvironment.getApplication())

            LanPlaybackBinding.externalAudio = false
            assertNull(PlayerManagerDlnaAdapter.castBlockedReason())

            // 一起听由独立的 LAN 引擎发声并消费播放命令，此时投送会和房间音频同时出声
            LanPlaybackBinding.externalAudio = true
            assertNotNull(PlayerManagerDlnaAdapter.castBlockedReason())
        } finally {
            LanPlaybackBinding.externalAudio = previousExternalAudio
            applicationField.set(null, previousApplication)
        }
    }
}
