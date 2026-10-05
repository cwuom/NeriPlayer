package moe.ouom.neriplayer.ui.onboarding

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountCardUiState
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountPlatform
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountProfile
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountProfileRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class StartupPlatformAccountsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val loginCalls = mutableListOf<SettingsAccountPlatform>()
    private val manageCalls = mutableListOf<SettingsAccountPlatform>()
    private var continueCalls = 0
    private var avatarFile: File? = null

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun deleteOwnedSyntheticAvatar() {
        avatarFile?.delete()
    }

    @Test
    fun phoneShowsSavedIdentityAndKeepsLoginAndAuthorizationManagementSeparate(): Unit =
        UiFailureDiagnostics.onFailure("onboarding-accounts-phone") {
            render(width = 380, height = 780)
            composeRule.onNodeWithTag("settingsAccountNickname:netease", true)
                .performScrollTo().assertIsDisplayed().assertTextEquals(NETEASE_NAME)
            composeRule.onNodeWithTag("settingsAccountAvatar:netease", true).assertIsDisplayed()
            composeRule.onNodeWithTag("settingsAccountAuthorization:netease", true)
                .assertTextEquals(string(CoreCommonR.string.settings_account_authorization_saved))
            composeRule.onNodeWithTag("settingsAccountStacked:netease", true).assertIsDisplayed()
            capture("phone")
            composeRule.onNodeWithTag("settingsAccountCard:netease").performScrollTo().performClick()
            composeRule.onNodeWithTag("settingsAccountLogin:netease").performScrollTo().performClick()
            composeRule.onNodeWithTag("settingsAccountLogout:bilibili").performScrollTo().performClick()
            composeRule.onNodeWithTag("settingsAccountCard:qq", true).performScrollTo()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            composeRule.onNodeWithTag("settingsAccountLogin:qq").assertDoesNotExist()
            composeRule.onNodeWithTag("settingsAccountLogout:qq").assertDoesNotExist()
            composeRule.onNodeWithTag(NEXT).assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(listOf(SettingsAccountPlatform.Netease), loginCalls)
                assertEquals(listOf(SettingsAccountPlatform.Netease, SettingsAccountPlatform.Bilibili), manageCalls)
                assertEquals(0, continueCalls)
            }
        }

    @Test
    fun shortLandscapeLargeFontKeepsYouTubeLoginAndContinueReachable(): Unit =
        UiFailureDiagnostics.onFailure("onboarding-accounts-short-landscape") {
            render(width = 540, height = 300, fontScale = 1.6f)
            val nextBeforeScroll = bounds(NEXT)
            composeRule.onNodeWithTag("settingsAccountLogin:youtube")
                .performScrollTo().assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("settingsAccountAuthorization:youtube", true)
                .performScrollTo().assertTextEquals(string(CoreCommonR.string.settings_account_authorization_missing))
            composeRule.onNodeWithTag(NEXT).assertIsDisplayed()
            assertEquals("滚动账号不能移动继续按钮", nextBeforeScroll, bounds(NEXT))
            composeRule.onNodeWithTag(NEXT).performClick()
            composeRule.runOnIdle {
                assertEquals(listOf(SettingsAccountPlatform.YouTube), loginCalls)
                assertTrue(manageCalls.isEmpty())
                assertEquals(1, continueCalls)
            }
            capture("short-landscape-large-font")
        }

    @Test
    fun wideTabletKeepsCardsBesideGuideWithInlineActionsAndFixedContinue(): Unit =
        UiFailureDiagnostics.onFailure("onboarding-accounts-tablet-wide") {
            render(width = 1280, height = 900)
            composeRule.onNodeWithTag("settingsAccountCard:netease").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("settingsAccountInline:netease", true).assertIsDisplayed()
            assertTrue("账号内容应位于引导说明右侧", bounds("settingsAccountCard:netease").left > bounds(INTRO).right)
            assertInside(bounds("settingsAccountAvatar:netease"), bounds("settingsAccountCard:netease"))
            assertInside(bounds("settingsAccountLogin:netease"), bounds("settingsAccountCard:netease"))
            capture("tablet-wide")
            val nextBeforeScroll = bounds(NEXT)
            composeRule.onNodeWithTag("settingsAccountLogin:youtube").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag(NEXT).assertIsDisplayed()
            assertEquals(nextBeforeScroll, bounds(NEXT))
        }

    @Test
    fun tabletPortraitLargeFontKeepsSavedAccountActionsInsideTheirCard(): Unit =
        UiFailureDiagnostics.onFailure("onboarding-accounts-tablet-portrait") {
            render(width = 600, height = 900, fontScale = 1.5f)
            val nextBeforeScroll = bounds(NEXT)
            for (action in listOf("settingsAccountLogin:netease", "settingsAccountLogout:netease")) {
                composeRule.onNodeWithTag(action).performScrollTo().assertIsDisplayed()
                assertInside(bounds(action), fullBounds("settingsAccountCard:netease"))
            }
            composeRule.onNodeWithTag("settingsAccountStacked:netease", true).assertIsDisplayed()
            composeRule.onNodeWithTag("settingsAccountNickname:netease", true).performScrollTo().assertTextEquals(NETEASE_NAME)
            assertTrue(bounds("settingsAccountCard:netease").left > bounds(INTRO).right)
            capture("tablet-portrait-large-font")
            composeRule.onNodeWithTag("settingsAccountLogin:youtube").performScrollTo().assertIsDisplayed()
            assertEquals(nextBeforeScroll, bounds(NEXT))
            composeRule.onNodeWithTag(NEXT).assertIsDisplayed()
        }

    @Test
    fun leavingPlatformsCancelsReadsAndDefersNewIdentityUntilForegroundReturn(): Unit =
        UiFailureDiagnostics.onFailure("onboarding-accounts-profile-lifecycle") {
            val owner = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
            composeRule.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            val active = mutableStateOf(false)
            val youtubeEnabled = mutableStateOf(false)
            val identity = mutableStateOf("fixture-a")
            val calls = AtomicInteger(0)
            val completed = AtomicInteger(0)
            val pending = CompletableDeferred<SettingsAccountProfile?>()
            var profiles: StartupAccountProfiles? = null
            try {
                setFixture(width = 380, height = 780) {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        val request = SettingsAccountProfileRequest(true, 100, identity.value)
                        val current = rememberStartupAccountProfiles(
                            neteaseRequest = request,
                            biliRequest = request,
                            youtubeRequest = request,
                            isActive = active.value,
                            youtubeEnabled = youtubeEnabled.value
                        ) { platform ->
                            if (calls.incrementAndGet() <= 2) {
                                try {
                                    pending.await()
                                } finally {
                                    completed.incrementAndGet()
                                }
                            } else SettingsAccountProfile("Fresh ${platform.key}", null)
                        }
                        profiles = current
                        StartupPlatformAccountsContent(
                            accounts = listOf(
                                SettingsAccountCardUiState(SettingsAccountPlatform.Netease, true, profile = current.netease.profile),
                                SettingsAccountCardUiState(SettingsAccountPlatform.Bilibili, true, profile = current.bili.profile),
                                SettingsAccountCardUiState(SettingsAccountPlatform.YouTube, true, profile = current.youtube.profile)
                            ),
                            inlineMessage = null,
                            onInlineMessageChange = {},
                            onLogin = {},
                            onManageSaved = {}
                        )
                    }
                }
                composeRule.runOnIdle {
                    assertEquals("准备或未选中的平台步骤不能读取资料", 0, calls.get())
                    active.value = true
                }
                composeRule.waitUntil(5_000) { calls.get() == 2 }
                composeRule.runOnIdle {
                    assertNull("YouTube 开关关闭时不能加载资料", checkNotNull(profiles).youtube.profile)
                    active.value = false
                }
                composeRule.waitUntil(5_000) { completed.get() == 2 }
                composeRule.runOnIdle {
                    identity.value = "fixture-b"
                    youtubeEnabled.value = true
                }
                composeRule.runOnIdle {
                    assertEquals("退出 Platforms 后授权变化不能发起新请求", 2, calls.get())
                    pending.complete(SettingsAccountProfile("Stale account", null))
                }
                composeRule.runOnIdle {
                    val current = checkNotNull(profiles)
                    assertNull(current.netease.profile)
                    assertNull(current.bili.profile)
                    assertNull(current.youtube.profile)
                    owner.lifecycle.currentState = Lifecycle.State.CREATED
                    active.value = true
                }
                composeRule.runOnIdle {
                    assertEquals("后台即使选中 Platforms 也不能读取资料", 2, calls.get())
                    owner.lifecycle.currentState = Lifecycle.State.RESUMED
                }
                composeRule.waitUntil(5_000) { calls.get() == 5 }
                composeRule.runOnIdle {
                    val current = checkNotNull(profiles)
                    assertEquals("Fresh netease", current.netease.profile?.nickname)
                    assertEquals("Fresh bilibili", current.bili.profile?.nickname)
                    assertEquals("Fresh youtube", current.youtube.profile?.nickname)
                }
            } finally {
                composeRule.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
            }
        }

    private fun render(width: Int, height: Int, fontScale: Float = 1f) {
        val accounts = fixtureAccounts()
        setFixture(width, height, fontScale) {
            StartupPlatformAccountsContent(
                accounts = accounts,
                inlineMessage = null,
                onInlineMessageChange = {},
                onLogin = { loginCalls.add(it) },
                onManageSaved = { manageCalls.add(it) }
            )
        }
    }

    private fun setFixture(width: Int, height: Int, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val baseDensity = LocalDensity.current
                    val scale = minOf(maxWidth.value / width, maxHeight.value / height)
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        screenWidthDp = width
                        screenHeightDp = height
                        smallestScreenWidthDp = minOf(width, height)
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(baseDensity.density * scale, fontScale),
                        LocalConfiguration provides configuration
                    ) {
                        Box(
                            Modifier.requiredSize(width.dp, height.dp)
                                .consumeWindowInsets(WindowInsets.safeDrawing)
                                .background(MaterialTheme.colorScheme.background).testTag(ROOT)
                        ) {
                            StartupOnboardingLayout(
                                header = {
                                    Text(
                                        stringResource(CoreCommonR.string.onboarding_title),
                                        modifier = Modifier.testTag(INTRO),
                                        style = MaterialTheme.typography.headlineSmall
                                    )
                                    Text(stringResource(CoreCommonR.string.onboarding_subtitle))
                                },
                                actions = {
                                    Button(onClick = { continueCalls += 1 }, modifier = Modifier.testTag(NEXT)) {
                                        Text(stringResource(CoreCommonR.string.onboarding_action_next))
                                    }
                                }
                            ) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun fixtureAccounts() = listOf(
        SettingsAccountCardUiState(
            SettingsAccountPlatform.Netease, true, true, "Fixture update",
            SettingsAccountProfile(NETEASE_NAME, syntheticAvatar())
        ),
        SettingsAccountCardUiState(
            SettingsAccountPlatform.Bilibili, true, true, "Fixture update",
            SettingsAccountProfile("Fixture Bilibili account", syntheticAvatar())
        ),
        SettingsAccountCardUiState(SettingsAccountPlatform.YouTube),
        SettingsAccountCardUiState(SettingsAccountPlatform.QqMusic)
    )

    private fun syntheticAvatar(): String {
        avatarFile?.let { return Uri.fromFile(it).toString() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("onboarding-avatar-fixture-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(95, 119, 160))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 227, 241) }
            canvas.drawCircle(64f, 43f, 24f, paint)
            canvas.drawCircle(64f, 119f, 44f, paint)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        avatarFile = file
        return Uri.fromFile(file).toString()
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot

    private fun fullBounds(tag: String): Rect {
        val node = composeRule.onNodeWithTag(tag, true).fetchSemanticsNode()
        return Rect(node.positionInRoot.x, node.positionInRoot.y,
            node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
    }

    private fun assertInside(child: Rect, parent: Rect) {
        assertTrue("控件应保持在卡片内部", child.left >= parent.left - 1 && child.right <= parent.right + 1)
        assertTrue("控件应保持在卡片内部", child.top >= parent.top - 1 && child.bottom <= parent.bottom + 1)
    }

    private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun capture(stage: String) {
        val arguments = InstrumentationRegistry.getArguments()
        val prefix = arguments.getString("capturePrefix")?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(60)
            ?.takeIf(String::isNotBlank) ?: "onboarding-accounts".takeIf { arguments.getString("captureUi").toBoolean() } ?: return
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$prefix-$stage.png")
        val bitmap = composeRule.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        Log.i("StartupPlatformAccountsTest", "capture=${file.absolutePath}")
    }

    private companion object {
        const val ROOT = "startupPlatformAccountsFixture"
        const val INTRO = "startupPlatformAccountsIntro"
        const val NEXT = "startupPlatformAccountsNext"
        const val NETEASE_NAME = "Fixture NetEase account"
    }
}
