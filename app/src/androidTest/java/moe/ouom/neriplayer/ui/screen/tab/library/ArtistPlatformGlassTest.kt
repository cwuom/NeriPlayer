package moe.ouom.neriplayer.ui.screen.tab.library

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSceneLayer
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.tab.FollowedArtistImportUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ArtistPlatformGlassTest {
    @get:Rule
    val compose = createComposeRule()

    private val context by lazy {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        target.createConfigurationContext(Configuration(target.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
    }

    @Before
    fun requireUnlockedEmulator() {
        assumeComposeHostAvailable()
    }

    @Test
    fun platformHeaderTransmitsBackdropAndBlursItWhenEnhancedBlurIsEnabled() {
        var enhancedBlur by mutableStateOf(false)
        var capturedBackdrop: AdvancedGlassBackdrop? = null
        val blurStates = mutableListOf<Boolean>()

        setChineseContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "7D5260"
            ) {
                AdvancedGlassSceneLayer(
                    controller = AdvancedGlassController(
                        sdkInt = Build.VERSION.SDK_INT,
                        advancedBlurEnabled = true,
                        enhancedAdvancedBlurEnabled = enhancedBlur,
                        backendReady = true
                    ),
                    modifier = Modifier.fillMaxSize(),
                    background = {
                        Row(Modifier.fillMaxSize()) {
                            repeat(STRIPE_COUNT) { index ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(if (index % 2 == 0) Color.Black else Color.White)
                                )
                            }
                        }
                    },
                    content = {
                        val backdrop = requireNotNull(LocalAdvancedGlassBackdrops.current).background
                        SideEffect { capturedBackdrop = backdrop }
                        LaunchedEffect(backdrop) {
                            snapshotFlow { backdrop.hasActiveBlur }.collect { blurStates += it }
                        }
                        Box(Modifier.fillMaxWidth().testTag(HEADER_TAG)) {
                            FavoriteArtistPlatformHeader(
                                selected = FavoriteArtistPlatform.NETEASE,
                                count = 3,
                                onSelect = {},
                                importState = FollowedArtistImportUiState(),
                                onImport = {},
                                offlineMode = false,
                                editMode = false
                            )
                        }
                    }
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText(context.getString(CoreCommonR.string.library_artist_following))
            .assertIsDisplayed()
        compose.runOnIdle {
            assertFalse("关闭进阶模糊时仍安装了模糊背景", requireNotNull(capturedBackdrop).hasActiveBlur)
            assertEquals(listOf(false), blurStates)
        }
        val translucentImage = compose.onNodeWithTag(HEADER_TAG).captureToImage()
        saveScreenshot(translucentImage, "artist-platform-translucent.png")
        val translucentContrast = stripeContrast(translucentImage)
        assertTrue(
            "半透明卡片没有透出背景或缺少半透明图层: contrast=$translucentContrast",
            translucentContrast in 0.60f..0.85f
        )

        compose.runOnIdle { enhancedBlur = true }
        compose.waitUntil(timeoutMillis = 5_000) { capturedBackdrop?.hasActiveBlur == true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("开启进阶模糊后没有实际模糊背景", requireNotNull(capturedBackdrop).hasActiveBlur)
            assertFalse("未记录关闭时的真实背景状态", blurStates.first())
            assertTrue("未记录开启时的真实背景状态", blurStates.last())
        }
        val glassImage = compose.onNodeWithTag(HEADER_TAG).captureToImage()
        saveScreenshot(glassImage, "artist-platform-glass.png")
        val glassContrast = stripeContrast(glassImage)
        assertTrue(
            "开启进阶模糊没有降低背景条纹对比度: before=$translucentContrast after=$glassContrast",
            glassContrast < translucentContrast * 0.65f
        )
    }

    private fun stripeContrast(image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        val sampleY = with(compose.density) { 14.dp.roundToPx() }
        val horizontalInset = with(compose.density) { 32.dp.toPx() }
        val blackLuminance = mutableListOf<Float>()
        val whiteLuminance = mutableListOf<Float>()
        // 卡片顶部内边距不含文字和按钮，避开圆角后只比较背景条纹
        repeat(STRIPE_COUNT) { index ->
            val sampleX = ((index + 0.5f) * image.width / STRIPE_COUNT).toInt()
            if (sampleX < horizontalInset || sampleX >= image.width - horizontalInset) return@repeat
            val pixel = pixels[sampleX, sampleY]
            val luminance = pixel.red * 0.2126f + pixel.green * 0.7152f + pixel.blue * 0.0722f
            if (index % 2 == 0) blackLuminance += luminance else whiteLuminance += luminance
        }
        assertTrue("截图缺少足够的黑白背景样本", blackLuminance.isNotEmpty() && whiteLuminance.isNotEmpty())
        return (whiteLuminance.average() - blackLuminance.average()).toFloat()
    }

    private fun saveScreenshot(image: ImageBitmap, name: String) {
        val directory = requireNotNull(context.getExternalFilesDir(null))
        File(directory, name).outputStream().use { output ->
            assertTrue("截图保存失败: $name", image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun setChineseContent(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                content = content
            )
        }
    }

    private companion object {
        const val HEADER_TAG = "artist-platform-glass-header"
        const val STRIPE_COUNT = 32
    }
}
