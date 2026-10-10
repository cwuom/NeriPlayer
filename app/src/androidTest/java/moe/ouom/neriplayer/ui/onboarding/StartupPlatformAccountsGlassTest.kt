package moe.ouom.neriplayer.ui.onboarding

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRegionRegistry
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassNavigationOwner
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountCardUiState
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountPlatform
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountProfile
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class StartupPlatformAccountsGlassTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun prewarmedInactivePlatformCardSuppressesFallbackThroughOwnerHandoff() =
        UiFailureDiagnostics.onFailure("onboarding-accounts-glass-handoff") {
            val activeOwner = mutableStateOf(OTHER_STEP)
            val enabled = mutableStateOf(true)
            lateinit var registry: AdvancedGlassRegionRegistry
            var fallbackColor = Color.Unspecified
            composeRule.setContent {
                MaterialTheme {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val baseDensity = LocalDensity.current
                        val scale = minOf(maxWidth.value / 840, maxHeight.value / 760)
                        CompositionLocalProvider(LocalDensity provides Density(baseDensity.density * scale)) {
                            val controller = AdvancedGlassController(
                                sdkInt = Build.VERSION.SDK_INT,
                                advancedBlurEnabled = true,
                                enhancedAdvancedBlurEnabled = enabled.value,
                                backendReady = true,
                                advancedBlurQuality = AdvancedBlurQuality.High
                            )
                            val background = rememberAdvancedGlassBackdrop()
                            val content = rememberAdvancedGlassBackdrop()
                            val materialFallback = MaterialTheme.colorScheme.surfaceContainerLow
                            val accountFallback = MaterialTheme.colorScheme.surfaceContainer
                            AdvancedGlassHost(
                                controller = controller,
                                backgroundBackdrop = background,
                                contentBackdrop = content,
                                activeNavigationOwners = setOf(activeOwner.value),
                                prewarmedNavigationOwners = setOf(PLATFORMS_STEP, OTHER_STEP)
                            ) {
                                val backdrops = checkNotNull(LocalAdvancedGlassBackdrops.current)
                                SideEffect {
                                    registry = backdrops.regionRegistry
                                    fallbackColor = accountFallback
                                }
                                Box(Modifier.requiredSize(840.dp, 760.dp)) {
                                    Box(Modifier.fillMaxSize().captureAdvancedGlassBackdrop(background).background(BACKGROUND))
                                    Row(Modifier.fillMaxSize()) {
                                        Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp).testTag("startupAccountsOtherScene")) {
                                            CompositionLocalProvider(LocalAdvancedGlassNavigationOwner provides OTHER_STEP) {
                                                AdvancedGlassSurface(
                                                    role = AdvancedGlassRole.SemanticCard,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    fallbackColor = materialFallback,
                                                    tintColor = materialFallback,
                                                    suppressInactiveNavigationSurface = true
                                                ) { Text("Other onboarding scene", Modifier.padding(20.dp)) }
                                            }
                                        }
                                        Column(
                                            Modifier.weight(1f).fillMaxHeight().padding(16.dp)
                                                .testTag("startupAccountsPlatformsScene").verticalScroll(rememberScrollState())
                                        ) {
                                            CompositionLocalProvider(LocalAdvancedGlassNavigationOwner provides PLATFORMS_STEP) {
                                                StartupPlatformAccountsContent(
                                                    accounts = listOf(
                                                        SettingsAccountCardUiState(
                                                            SettingsAccountPlatform.Netease, true, true,
                                                            profile = SettingsAccountProfile("Synthetic account", null)
                                                        )
                                                    ),
                                                    inlineMessage = null,
                                                    onInlineMessageChange = {},
                                                    onLogin = {},
                                                    onManageSaved = {}
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            composeRule.waitUntil(5_000) {
                registry.regions.any { it.role == AdvancedGlassRole.SettingsSection && it.navigationOwner == PLATFORMS_STEP }
            }
            composeRule.onNodeWithTag("startupAccountsOtherScene").assertIsDisplayed()
            composeRule.onNodeWithTag("startupAccountsPlatformsScene").assertIsDisplayed()
            assertTrue("非活动预热页不能绘制普通账号卡片底色", colorDistance(cardBackgroundPixel(), BACKGROUND) < 0.025f)

            composeRule.runOnIdle { activeOwner.value = PLATFORMS_STEP }
            composeRule.waitForIdle()
            assertTrue("活动账号卡片应使用玻璃 tint", colorDistance(cardBackgroundPixel(), BACKGROUND) > 0.05f)

            composeRule.runOnIdle { activeOwner.value = OTHER_STEP }
            composeRule.waitForIdle()
            assertTrue("退出时仍被渲染的账号页也应抑制普通底色", colorDistance(cardBackgroundPixel(), BACKGROUND) < 0.025f)

            composeRule.runOnIdle { enabled.value = false }
            composeRule.waitUntil(5_000) { registry.regions.isEmpty() }
            assertTrue("关闭模糊时仍需保留 Material 回退卡片", colorDistance(cardBackgroundPixel(), fallbackColor) < 0.025f)
        }

    private fun cardBackgroundPixel(): Color {
        val image = composeRule.onNodeWithTag("settingsAccountCard:netease").assertIsDisplayed().captureToImage()
        val y = (image.width * 0.025f).toInt().coerceAtLeast(2)
        return image.toPixelMap()[image.width / 2, y]
    }

    private fun colorDistance(first: Color, second: Color): Float =
        max(max(abs(first.red - second.red), abs(first.green - second.green)), abs(first.blue - second.blue))

    private companion object {
        const val PLATFORMS_STEP = "fixture-platforms"
        const val OTHER_STEP = "fixture-other"
        val BACKGROUND = Color(0xFF18314B)
    }
}
