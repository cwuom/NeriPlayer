package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRegion
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRegionRegistry
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.resolveCurrentAdvancedGlassRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class SettingsAccountCardsGlassTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var background: AdvancedGlassBackdrop
    private lateinit var content: AdvancedGlassBackdrop
    private lateinit var registry: AdvancedGlassRegionRegistry
    private var expectedCornerPx = 0f
    private var fallbackColor = Color.Unspecified
    private val loginCalls = mutableListOf<SettingsAccountPlatform>()
    private val managementCalls = mutableListOf<SettingsAccountPlatform>()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun fourAccountCardsUseStableNativeSettingsGlassAndFallBackWhenDisabled() {
        assertGlassAndFallback(AdvancedBlurQuality.High)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun fourAccountCardsUseStableLocalSettingsGlassAndFallBackWhenDisabled() {
        assertGlassAndFallback(AdvancedBlurQuality.Low)
    }

    @Test
    fun missingHostKeepsMaterialCardsAndWholeCardPressIsRoundedWithIndependentButtons() {
        val accounts = mutableStateOf(fixtureAccounts())
        val enabled = mutableStateOf(true)
        render(accounts, enabled, AdvancedBlurQuality.High, host = false, phone = true)
        assertFallbackPixels()
        composeRule.onNodeWithTag("settingsAccountIdentity:netease", true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        composeRule.onNodeWithTag("settingsAccountCard:qq")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

        val card = composeRule.onNodeWithTag("settingsAccountCard:netease")
        val initial = card.captureToImage()
        val sampleX = initial.width / 2
        val sampleY = initial.height - (initial.width / 344f * 10f).toInt().coerceAtLeast(3)
        val initialBottom = initial.toPixelMap()[sampleX, sampleY]
        val initialCorner = initial.toPixelMap()[1, 1]
        composeRule.mainClock.autoAdvance = false
        try {
            card.performTouchInput { down(Offset(width / 2f, height * 0.3f)) }
            composeRule.mainClock.advanceTimeBy(300)
            composeRule.waitUntil(2_000) {
                composeRule.mainClock.advanceTimeBy(64)
                colorDistance(card.captureToImage().toPixelMap()[sampleX, sampleY], initialBottom) > 0.02f
            }
            val pressed = card.captureToImage()
            assertTrue("按压反馈应覆盖资料行以外的卡片底部", colorDistance(pressed.toPixelMap()[sampleX, sampleY], initialBottom) > 0.02f)
            assertTrue("圆角外不能出现矩形按压遮罩", colorDistance(pressed.toPixelMap()[1, 1], initialCorner) < 0.02f)
            capture("accounts-phone-pressed", composeRule.onNodeWithTag(ROOT).captureToImage())
        } finally {
            card.performTouchInput { up() }
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.onNodeWithTag("settingsAccountLogin:netease").performTouchInput { click() }
        composeRule.onNodeWithTag("settingsAccountLogout:bilibili").performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(listOf(SettingsAccountPlatform.Netease), loginCalls)
            assertEquals(listOf(SettingsAccountPlatform.Netease, SettingsAccountPlatform.Bilibili), managementCalls)
        }
    }

    private fun assertGlassAndFallback(quality: AdvancedBlurQuality) {
        val accounts = mutableStateOf(fixtureAccounts())
        val enabled = mutableStateOf(true)
        render(accounts, enabled, quality, host = true, phone = false)
        composeRule.waitUntil(2_000) { registry.regions.size == 4 && background.hasActiveBlur }
        val initialRegions = currentRegions()
        assertRegionGeometry(initialRegions)
        val initialEffect = background.renderEffect
        val initialPlan = background.localBlurPlan
        if (quality == AdvancedBlurQuality.Low) {
            assertNotNull(initialPlan)
            assertNull(initialEffect)
        } else {
            assertNotNull(initialEffect)
            assertNull(initialPlan)
        }
        assertFalse("账号卡片只采样背景层", content.hasActiveBlur)
        assertBackdropShowsThroughAllCards()
        capture("accounts-glass-${quality.name.lowercase()}", composeRule.onNodeWithTag(ROOT).captureToImage())

        composeRule.runOnIdle {
            accounts.value = accounts.value.map { account ->
                if (account.platform == SettingsAccountPlatform.Netease) {
                    account.copy(profile = SettingsAccountProfile("Fixture account B", null))
                } else account
            }
        }
        composeRule.onNodeWithTag("settingsAccountNickname:netease", true).assertTextEquals("Fixture account B")
        repeat(3) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle {
                val regions = currentRegions()
                assertEquals(initialRegions.map { it.boundsInWindow }, regions.map { it.boundsInWindow })
                assertEquals(4, regions.size)
                assertTrue(background.hasActiveBlur)
                if (quality == AdvancedBlurQuality.Low) assertSame(initialPlan, background.localBlurPlan)
                else assertSame(initialEffect, background.renderEffect)
            }
        }
        composeRule.runOnIdle { enabled.value = false }
        composeRule.waitUntil(2_000) { registry.regions.isEmpty() && !background.hasActiveBlur }
        assertFallbackPixels()
        composeRule.onNodeWithTag("settingsAccountLogin:netease").performTouchInput { click() }
        composeRule.onNodeWithTag("settingsAccountLogout:bilibili").performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(listOf(SettingsAccountPlatform.Netease), loginCalls)
            assertEquals(listOf(SettingsAccountPlatform.Bilibili), managementCalls)
        }
    }

    private fun render(
        accounts: MutableState<List<SettingsAccountCardUiState>>,
        enabled: MutableState<Boolean>,
        quality: AdvancedBlurQuality,
        host: Boolean,
        phone: Boolean
    ) {
        val size = if (phone) DpSize(380.dp, 1100.dp) else DpSize(900.dp, 700.dp)
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val baseDensity = LocalDensity.current
                    val scale = minOf(maxWidth.value / size.width.value, maxHeight.value / size.height.value)
                    CompositionLocalProvider(LocalDensity provides Density(baseDensity.density * scale, 1f)) {
                        val controller = AdvancedGlassController(
                            sdkInt = Build.VERSION.SDK_INT,
                            advancedBlurEnabled = true,
                            enhancedAdvancedBlurEnabled = enabled.value,
                            backendReady = true,
                            advancedBlurQuality = quality
                        )
                        val density = LocalDensity.current
                        val materialFallback = MaterialTheme.colorScheme.surfaceContainerLow
                        SideEffect {
                            expectedCornerPx = with(density) { 24.dp.toPx() }
                            fallbackColor = materialFallback
                        }
                        if (host) {
                            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
                            val contentBackdrop = rememberAdvancedGlassBackdrop()
                            AdvancedGlassHost(controller, backgroundBackdrop, contentBackdrop) {
                                val backdrops = checkNotNull(LocalAdvancedGlassBackdrops.current)
                                SideEffect {
                                    background = backgroundBackdrop
                                    content = contentBackdrop
                                    registry = backdrops.regionRegistry
                                }
                                CardScene(size, accounts.value, backgroundBackdrop)
                            }
                        } else {
                            CompositionLocalProvider(LocalAdvancedGlassController provides controller) {
                                CardScene(size, accounts.value, null)
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun CardScene(
        size: DpSize,
        accounts: List<SettingsAccountCardUiState>,
        backdrop: AdvancedGlassBackdrop?
    ) {
        Box(Modifier.requiredSize(size).testTag(ROOT)) {
            val backgroundModifier = if (backdrop != null) Modifier.captureAdvancedGlassBackdrop(backdrop) else Modifier
            Row(Modifier.fillMaxSize().then(backgroundModifier)) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.Red))
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.Blue))
            }
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
                SettingsAccountCardsContent(
                    accounts = accounts,
                    onLogin = { loginCalls.add(it) },
                    onManageSaved = { managementCalls.add(it) }
                )
            }
        }
    }

    private fun currentRegions(): List<AdvancedGlassRegion> =
        resolveCurrentAdvancedGlassRegions(registry.regions).sortedBy { it.boundsInWindow.top }

    private fun assertRegionGeometry(regions: List<AdvancedGlassRegion>) {
        assertEquals(4, regions.size)
        regions.forEach { region ->
            assertEquals(AdvancedGlassRole.SettingsSection, region.role)
            assertTrue(region.boundsInWindow.width > 0f && region.boundsInWindow.height > 0f)
            assertEquals(expectedCornerPx, region.cornerRadiiPx.topLeft, 1f)
            assertEquals(expectedCornerPx, region.cornerRadiiPx.topRight, 1f)
            assertEquals(expectedCornerPx, region.cornerRadiiPx.bottomLeft, 1f)
            assertEquals(expectedCornerPx, region.cornerRadiiPx.bottomRight, 1f)
        }
        regions.zipWithNext { before, after ->
            assertTrue("平台卡片玻璃区域不能重叠", before.boundsInWindow.bottom < after.boundsInWindow.top)
        }
    }

    private fun assertBackdropShowsThroughAllCards() {
        SettingsAccountPlatform.entries.forEach { platform ->
            val image = composeRule.onNodeWithTag("settingsAccountCard:${platform.key}").captureToImage()
            val pixels = image.toPixelMap()
            val y = (image.width / 864f * 10f).toInt().coerceAtLeast(2)
            val left = pixels[image.width / 6, y]
            val right = pixels[image.width * 5 / 6, y]
            assertTrue("${platform.key} 卡片应透出左侧背景", left.red > left.blue + 0.25f)
            assertTrue("${platform.key} 卡片应透出右侧背景", right.blue > right.red + 0.25f)
        }
    }

    private fun assertFallbackPixels() {
        SettingsAccountPlatform.entries.forEach { platform ->
            val node = composeRule.onNodeWithTag("settingsAccountCard:${platform.key}").assertIsDisplayed()
            val image = node.captureToImage()
            val pixels = image.toPixelMap()
            val y = (image.width * 0.025f).toInt().coerceAtLeast(2)
            val sample = pixels[image.width / 2, y]
            assertTrue("${platform.key} 应保留Material回退背景", colorDistance(sample, fallbackColor) < 0.025f)
        }
    }

    private fun fixtureAccounts(): List<SettingsAccountCardUiState> = SettingsAccountPlatform.entries.map { platform ->
        SettingsAccountCardUiState(
            platform = platform,
            hasSavedAuthorization = platform == SettingsAccountPlatform.Netease || platform == SettingsAccountPlatform.Bilibili,
            authorizationComplete = true,
            savedAtLabel = "Fixture update",
            profile = if (platform == SettingsAccountPlatform.Netease || platform == SettingsAccountPlatform.Bilibili) {
                SettingsAccountProfile("Fixture account A", null)
            } else null
        )
    }

    private fun colorDistance(first: Color, second: Color): Float =
        max(max(abs(first.red - second.red), abs(first.green - second.green)), abs(first.blue - second.blue))

    private fun capture(stage: String, image: ImageBitmap) {
        val arguments = InstrumentationRegistry.getArguments()
        val prefix = arguments.getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(60)?.takeIf(String::isNotBlank)
            ?: "accounts".takeIf { arguments.getString("captureUi").toBoolean() } ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "$prefix-$stage.png")
        file.outputStream().use { assertTrue(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
        Log.i("SettingsAccountCardsGlassTest", "screenshot=${file.absolutePath}")
    }

    private companion object {
        const val ROOT = "settingsAccountsGlassFixture"
    }
}
