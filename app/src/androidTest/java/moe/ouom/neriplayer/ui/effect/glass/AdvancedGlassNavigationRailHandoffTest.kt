package moe.ouom.neriplayer.ui.effect.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class AdvancedGlassNavigationRailHandoffTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun nativeBlurKeepsThePageMaskWhileOnlyTheGlobalRailIsRegistered() {
        assertRailHandoff(AdvancedBlurQuality.High)
    }

    @Test
    fun localBlurKeepsThePageMaskWhileOnlyTheGlobalRailIsRegistered() {
        assertRailHandoff(AdvancedBlurQuality.Low)
    }

    private fun assertRailHandoff(quality: AdvancedBlurQuality) {
        val firstOwner = Any()
        val secondOwner = Any()
        lateinit var activeOwners: MutableState<Set<Any>>
        lateinit var handoffActive: MutableState<Boolean>
        lateinit var backdrop: AdvancedGlassBackdrop
        composeRule.setContent {
            backdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            activeOwners = remember { mutableStateOf(setOf(firstOwner)) }
            handoffActive = remember { mutableStateOf(false) }
            MaterialTheme {
                AdvancedGlassHost(
                    controller = AdvancedGlassController(
                        sdkInt = Build.VERSION.SDK_INT,
                        advancedBlurEnabled = true,
                        enhancedAdvancedBlurEnabled = true,
                        backendReady = true,
                        advancedBlurQuality = quality
                    ),
                    backgroundBackdrop = backdrop,
                    contentBackdrop = contentBackdrop,
                    activeNavigationOwners = activeOwners.value
                ) {
                    Box(Modifier.size(240.dp, 160.dp).testTag(RootTag)) {
                        Row(Modifier.fillMaxSize().captureAdvancedGlassBackdrop(backdrop)) {
                            repeat(24) { index ->
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().background(
                                        if (index % 2 == 0) Color.Black else Color.White
                                    )
                                )
                            }
                        }
                        AdvancedGlassNavigationHandoff(handoffActive.value) {
                            AdvancedGlassSurface(
                                role = AdvancedGlassRole.NavigationRail,
                                modifier = Modifier.width(24.dp).fillMaxHeight(),
                                tintColor = Color.Transparent
                            ) {}
                            CompositionLocalProvider(LocalAdvancedGlassNavigationOwner provides firstOwner) {
                                AdvancedGlassSurface(
                                    role = AdvancedGlassRole.SettingsSection,
                                    modifier = Modifier.size(80.dp).align(Alignment.CenterStart).offset(x = 40.dp),
                                    tintColor = Color.Transparent
                                ) {}
                            }
                            CompositionLocalProvider(LocalAdvancedGlassNavigationOwner provides secondOwner) {
                                AdvancedGlassSurface(
                                    role = AdvancedGlassRole.SettingsSection,
                                    modifier = Modifier.size(80.dp).align(Alignment.CenterEnd),
                                    tintColor = Color.Transparent
                                ) {}
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertPageMask(firstPage = true)
        lateinit var originalBlur: Any
        composeRule.runOnIdle {
            handoffActive.value = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            originalBlur = checkNotNull(backdrop.localBlurPlan ?: backdrop.renderEffect)
            activeOwners.value = emptySet()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertSame("$quality replaced the page mask with only the rail mask", originalBlur,
                backdrop.localBlurPlan ?: backdrop.renderEffect)
        }
        assertPageMask(firstPage = true)

        composeRule.runOnIdle { activeOwners.value = setOf(secondOwner) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNotSame("$quality did not install the arriving page mask", originalBlur,
                backdrop.localBlurPlan ?: backdrop.renderEffect)
            assertTrue("$quality kept the old local frame after the new page arrived", !backdrop.freezeLocalBlurFrame)
            handoffActive.value = false
        }
        composeRule.waitForIdle()
        assertPageMask(firstPage = false)
    }

    private fun assertPageMask(firstPage: Boolean) {
        val image = composeRule.onNodeWithTag(RootTag).captureToImage()
        val pixels = image.toPixelMap()
        val boundaryOffset = (image.width / 100).coerceAtLeast(2)
        val first = pixels[image.width / 3 - boundaryOffset, image.height / 2]
        val second = pixels[image.width * 5 / 6 - boundaryOffset, image.height / 2]
        val blurred = if (firstPage) first else second
        val sharp = if (firstPage) second else first
        assertTrue("The active page lost its blur: $blurred", blurred.red in 0.15f..0.85f)
        assertTrue("The inactive page kept a stale mask: $sharp", sharp.red < 0.05f || sharp.red > 0.95f)
    }

    private companion object {
        const val RootTag = "rail_handoff_root"
    }
}
