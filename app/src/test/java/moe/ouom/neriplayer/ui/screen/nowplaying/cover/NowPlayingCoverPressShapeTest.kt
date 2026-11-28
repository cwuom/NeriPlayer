package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.theme.CleanDesignTokens
import moe.ouom.neriplayer.ui.theme.CleanPressIndication
import moe.ouom.neriplayer.ui.theme.LocalNeriDesign
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class NowPlayingCoverPressShapeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var hostView: View
    private val cleanIndication = CleanPressIndication(mutableStateOf(Color.Black))

    @Test
    fun `clean press shrinks the rounded outline with the artwork`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            // 与 NeriTheme 一致: Clean 下全局 LocalIndication 是按压下沉
            CompositionLocalProvider(
                LocalNeriDesign provides CleanDesignTokens,
                LocalIndication provides cleanIndication
            ) {
                MaterialTheme {
                    hostView = LocalView.current
                    Box(Modifier.size(HostSize).background(Color.White)) {
                        NowPlayingCoverFrame(
                            surfaceColor = Color.Transparent,
                            onTap = {},
                            onLongPress = {},
                            modifier = Modifier.padding(FrameInset).size(FrameSize).testTag(FrameTag)
                        ) {
                            Box(Modifier.fillMaxSize().background(Color.Red))
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SettleMs)

        val idle = capture()
        assertTrue("常态下圆角起点附近应是封面", idle.isArtworkAt(KinkProbeX, KinkProbeY))

        composeRule.onNodeWithTag(FrameTag).performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(SettleMs)
        val pressed = capture()

        // 内收 3px 的直边和满尺寸圆角相交时, 这里会残留一块封面, 角被削成折线
        assertFalse("按压时圆角必须随封面一起缩小", pressed.isArtworkAt(KinkProbeX, KinkProbeY))
        assertTrue("缩小后的圆角内部仍应是封面", pressed.isArtworkAt(ArcInsideProbe, ArcInsideProbe))
        assertTrue("缩小后的直边内侧仍应是封面", pressed.isArtworkAt(KinkProbeX, FrameSizePx / 2))

        composeRule.onNodeWithTag(FrameTag).performTouchInput { cancel() }
    }

    private fun capture(): Bitmap = composeRule.runOnUiThread {
        Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            hostView.draw(Canvas(bitmap))
        }
    }

    private fun Bitmap.isArtworkAt(frameX: Int, frameY: Int): Boolean {
        val pixel = getPixel(FrameInsetPx + frameX, FrameInsetPx + frameY)
        return android.graphics.Color.red(pixel) > 200 &&
            android.graphics.Color.green(pixel) < 80 &&
            android.graphics.Color.blue(pixel) < 80
    }

    private companion object {
        const val FrameTag = "cover_frame"
        val HostSize = 240.dp
        val FrameInset = 20.dp
        val FrameSize = 200.dp

        // mdpi: 1dp = 1px; 200px 的封面按下后缩到 0.97, 每边内收 3px
        const val FrameInsetPx = 20
        const val FrameSizePx = 200

        // 未缩放的 24px 圆角在 (4, 14) 内侧, 缩放后的圆角 (圆心约 26.3, 半径约 23.3) 在其外侧
        const val KinkProbeX = 4
        const val KinkProbeY = 14
        const val ArcInsideProbe = 12
        const val SettleMs = 600L
    }
}
