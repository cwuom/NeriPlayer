package moe.ouom.neriplayer.ui.playback.visual

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28], application = Application::class)
class NowPlayingBlurPreloadSubscriptionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun queuePreloadingSubscribesOnlyWhileRemoteCoverBlurIsEnabled() {
        val queue = MutableStateFlow(emptyList<SongItem>())
        val remoteCover = NowPlayingOverlayCover(
            url = "https://example.invalid/current.jpg",
            songKey = "current-song",
            song = null,
            assetRefreshKey = 0
        )
        val localCover = remoteCover.copy(
            url = "android.resource://android/drawable/ic_media_play"
        )
        val background = NowPlayingOverlayBackground(
            blurEnabled = false,
            blurAmount = 0f,
            blurDarken = 0f,
            dynamicEnabled = false,
            offlineMode = true
        )
        val request = mutableStateOf(NowPlayingBackdropRequest(remoteCover, queue, background))
        composeRule.setContent {
            Box(Modifier.size(96.dp)) {
                NowPlayingBackdrop(request.value)
            }
        }
        assertQueueSubscriptions(queue, expected = 0)

        composeRule.runOnIdle {
            request.value = request.value.copy(
                cover = localCover,
                background = background.copy(blurEnabled = true)
            )
        }
        assertQueueSubscriptions(queue, expected = 0)

        composeRule.runOnIdle { request.value = request.value.copy(cover = remoteCover) }
        assertQueueSubscriptions(queue, expected = 1)

        composeRule.runOnIdle { request.value = request.value.copy(cover = localCover) }
        assertQueueSubscriptions(queue, expected = 0)

        composeRule.runOnIdle { request.value = request.value.copy(cover = remoteCover) }
        assertQueueSubscriptions(queue, expected = 1)

        composeRule.runOnIdle { request.value = request.value.copy(background = background) }
        assertQueueSubscriptions(queue, expected = 0)
    }

    private fun assertQueueSubscriptions(queue: MutableStateFlow<List<SongItem>>, expected: Int) {
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals("邻曲预加载的实际队列订阅数", expected, queue.subscriptionCount.value)
        }
    }
}
