package moe.ouom.neriplayer.ui.screen.tab.settings.auth

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SettingsAccountCardsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val loginCalls = mutableListOf<SettingsAccountPlatform>()
    private val managementCalls = mutableListOf<SettingsAccountPlatform>()
    private var avatarFile: File? = null

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun cleanSyntheticAvatar() {
        avatarFile?.delete()
    }

    @Test
    fun phoneShowsIndependentPlatformCardsWithActionsBelowIdentity() {
        render(380, 1000)
        val netease = bounds("settingsAccountCard:netease")
        val bili = bounds("settingsAccountCard:bilibili")
        assertTrue(netease.bottom < bili.top)
        composeRule.onNodeWithTag("settingsAccountStacked:netease", true).assertIsDisplayed()
        composeRule.onNodeWithTag("settingsAccountInline:netease", true).assertDoesNotExist()
        composeRule.onNodeWithTag("settingsAccountNickname:netease", useUnmergedTree = true)
            .assertTextEquals(NETEASE_NAME)
        assertInside(bounds("settingsAccountAvatar:netease", true), netease)
        val login = bounds("settingsAccountLogin:netease")
        assertTrue(login.top > bounds("settingsAccountNickname:netease", true).bottom)
        assertInside(login, netease)
        composeRule.onNodeWithTag("settingsAccountLogin:youtube").assertIsDisplayed()
        capture("accounts-phone")
    }

    @Test
    fun tabletWideCardsKeepAvatarAndActionsBesideTheAccountDetails() {
        render(920, 700)
        for (platform in listOf("netease", "bilibili", "youtube")) {
            composeRule.onNodeWithTag("settingsAccountInline:$platform", true).assertIsDisplayed()
            assertInside(bounds("settingsAccountLogin:$platform"), bounds("settingsAccountCard:$platform"))
        }
        val avatar = bounds("settingsAccountAvatar:bilibili", true)
        val logout = bounds("settingsAccountLogout:bilibili")
        assertTrue("操作不能与头像重叠", logout.left > avatar.right)
        composeRule.onNodeWithTag("settingsAccountNickname:bilibili", true).assertTextEquals(BILI_NAME)
        capture("accounts-tablet-wide")
    }

    @Test
    fun narrowTabletAndLargeFontKeepLongNicknameAndBothActionsReachable() {
        val longName = "A synthetic account nickname with enough words to wrap on a narrow tablet"
        render(
            width = 430, height = 1400, fontScale = 1.6f,
            accounts = fixtureAccounts().map {
                if (it.platform == SettingsAccountPlatform.Netease) {
                    it.copy(profile = SettingsAccountProfile(longName, null))
                } else it
            }
        )
        composeRule.onNodeWithTag("settingsAccountStacked:netease", true).assertIsDisplayed()
        composeRule.onNodeWithTag("settingsAccountNickname:netease", true).assertTextEquals(longName)
        for (action in listOf("settingsAccountLogin:netease", "settingsAccountLogout:netease")) {
            composeRule.onNodeWithTag(action).performScrollTo().assertIsDisplayed()
            assertInside(bounds(action), bounds("settingsAccountCard:netease"))
        }
        capture("accounts-narrow-large-font")
    }

    @Test
    fun platformActionsRouteToTheirOwnLoginAndExistingSavedAuthorizationManagement() {
        render(380, 1000)
        composeRule.onNodeWithTag("settingsAccountLogin:youtube").performClick()
        composeRule.onNodeWithTag("settingsAccountLogin:netease").performClick()
        composeRule.onNodeWithTag("settingsAccountLogout:bilibili").performClick()
        composeRule.onNodeWithTag("settingsAccountCard:netease").performClick()
        composeRule.onNodeWithTag("settingsAccountLogin:qq").assertDoesNotExist()
        composeRule.onNodeWithTag("settingsAccountLogout:qq").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf(SettingsAccountPlatform.YouTube, SettingsAccountPlatform.Netease), loginCalls)
            assertEquals(listOf(SettingsAccountPlatform.Bilibili, SettingsAccountPlatform.Netease), managementCalls)
        }
    }

    @Test
    fun unavailableProfileAndIncompleteAuthorizationKeepTheSavedStateAndSignOutAction() {
        render(
            380, 1000,
            accounts = listOf(SettingsAccountCardUiState(
                platform = SettingsAccountPlatform.Bilibili,
                hasSavedAuthorization = true,
                authorizationComplete = false
            ))
        )
        composeRule.onNodeWithTag("settingsAccountNickname:bilibili", true)
            .assertTextEquals(string(CoreCommonR.string.settings_account_profile_unavailable))
        composeRule.onNodeWithTag("settingsAccountAuthorization:bilibili", true)
            .assertTextEquals(string(CoreCommonR.string.settings_account_authorization_saved))
        composeRule.onNodeWithTag("settingsAccountLogout:bilibili").assertIsDisplayed()
        composeRule.onNodeWithTag("settingsAccountLogin:bilibili").assertIsDisplayed()
        composeRule.onNodeWithTag("settingsAccountAvatar:bilibili", true).assertIsDisplayed()
    }

    @Test
    fun searchedPlatformAndAccountsPageHighlightBringOnlyTheirCardIntoViewAndComplete() {
        val target = mutableStateOf("manual:youtube_login")
        val pulse = mutableStateOf(1)
        val completed = AtomicInteger(0)
        val accounts = fixtureAccounts()
        renderFixture(380, 300, 1f) {
            SettingsAccountCardsContent(
                accounts = accounts,
                onLogin = { loginCalls.add(it) },
                onManageSaved = { managementCalls.add(it) },
                highlightTargetId = target.value,
                highlightPulse = pulse.value,
                onHighlightFinished = { completed.incrementAndGet() }
            )
        }
        val targets = listOf(
            "manual:youtube_login" to "youtube",
            "manual:netease_login" to "netease",
            "manual:bili_login" to "bilibili",
            "page:Accounts" to "netease"
        )
        targets.forEachIndexed { index, (targetId, platform) ->
            if (index > 0) {
                composeRule.runOnIdle {
                    target.value = targetId
                    pulse.value += 1
                }
            }
            try {
                composeRule.waitUntil(
                    conditionDescription = "账号高亮 $targetId 完成第 ${index + 1} 次回调",
                    timeoutMillis = 5_000
                ) { completed.get() >= index + 1 }
            } catch (failure: ComposeTimeoutException) {
                val viewport = composeRule.onNodeWithTag(FIXTURE_ROOT).fetchSemanticsNode()
                val card = composeRule.onNodeWithTag("settingsAccountCard:$platform").fetchSemanticsNode()
                val scroll = viewport.config[SemanticsProperties.VerticalScrollAxisRange]
                Log.e(
                    "SettingsAccountHighlights",
                    "target=$targetId pulse=${pulse.value} completed=${completed.get()} " +
                        "clock=${composeRule.mainClock.currentTime} autoAdvance=${composeRule.mainClock.autoAdvance} " +
                        "scroll=${scroll.value()}/${scroll.maxValue()} viewport=${viewport.boundsInRoot} " +
                        "cardPosition=${card.positionInRoot} cardSize=${card.size}"
                )
                composeRule.onRoot(useUnmergedTree = true).printToLog("SettingsAccountHighlights")
                capture("accounts-highlight-failure-$platform")
                throw failure
            }
            composeRule.onNodeWithTag("settingsAccountCard:$platform").assertIsDisplayed()
            val node = composeRule.onNodeWithTag("settingsAccountCard:$platform").fetchSemanticsNode()
            assertInside(
                Rect(node.positionInRoot.x, node.positionInRoot.y,
                    node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height),
                bounds(FIXTURE_ROOT)
            )
            composeRule.runOnIdle {
                assertEquals("只应由匹配卡片完成高亮", index + 1, completed.get())
                assertTrue(loginCalls.isEmpty())
                assertTrue(managementCalls.isEmpty())
            }
        }
    }

    @Test
    fun profileOwnerLoadsOnlyVisibleSavedAuthorizationAndReusesTheCompletedResult() {
        val request = mutableStateOf(SettingsAccountProfileRequest(false, 100, "fixture-a"))
        val active = mutableStateOf(false)
        var loads = 0
        renderOwner(request, active) {
            loads += 1
            SettingsAccountProfile("Fixture loaded account", null)
        }
        composeRule.runOnIdle {
            assertEquals(0, loads)
            active.value = true
        }
        composeRule.runOnIdle {
            assertEquals(0, loads)
            request.value = request.value.copy(hasSavedAuthorization = true)
        }
        composeRule.onNodeWithTag("settingsAccountNickname:bilibili", true)
            .assertTextEquals("Fixture loaded account")
        composeRule.runOnIdle { active.value = false }
        composeRule.runOnIdle { active.value = true }
        composeRule.runOnIdle { assertEquals(1, loads) }
    }

    @Test
    fun profileOwnerRetriesFailuresOnlyWhenReactivatedAndCachesTheSuccessfulResult() {
        val request = mutableStateOf(SettingsAccountProfileRequest(true, 100, "fixture-a"))
        val active = mutableStateOf(true)
        var loads = 0
        renderOwner(request, active) {
            loads += 1
            when (loads) {
                1 -> throw IOException("fixture temporarily unavailable")
                2 -> null
                else -> SettingsAccountProfile("Fixture recovered account", null)
            }
        }
        composeRule.onNodeWithTag("settingsAccountNickname:bilibili", true)
            .assertTextEquals(string(CoreCommonR.string.settings_account_profile_unavailable))
        composeRule.onNodeWithTag("settingsAccountAuthorization:bilibili", true)
            .assertTextEquals(string(CoreCommonR.string.settings_account_authorization_saved))
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { assertEquals(1, loads) }

        composeRule.runOnIdle { active.value = false }
        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("settingsAccountNickname:bilibili", true)
            .assertTextEquals(string(CoreCommonR.string.settings_account_profile_unavailable))
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { assertEquals(2, loads) }

        composeRule.runOnIdle { active.value = false }
        composeRule.runOnIdle { active.value = true }
        composeRule.onNodeWithTag("settingsAccountNickname:bilibili", true)
            .assertTextEquals("Fixture recovered account")
        composeRule.runOnIdle { active.value = false }
        composeRule.runOnIdle { active.value = true }
        composeRule.runOnIdle { assertEquals(3, loads) }
        composeRule.onNodeWithTag("settingsAccountNickname:bilibili", true)
            .assertTextEquals("Fixture recovered account")
    }

    @Test
    fun oldProfileResponseCannotReplaceANewAuthorizationWithTheSameSavedTimeOrSurviveLogout() {
        val firstResponse = CompletableDeferred<SettingsAccountProfile>()
        val request = mutableStateOf(SettingsAccountProfileRequest(true, 100, "fixture-a"))
        val active = mutableStateOf(true)
        var firstStarted = false
        renderOwner(request, active) { current ->
            if (current.authorizationIdentity == "fixture-a") {
                firstStarted = true
                withContext(NonCancellable) { firstResponse.await() }
            } else SettingsAccountProfile("Fixture account B", null)
        }
        composeRule.waitUntil(2_000) { firstStarted }
        composeRule.runOnIdle { request.value = request.value.copy(authorizationIdentity = "fixture-b") }
        composeRule.onNodeWithTag("settingsAccountNickname:bilibili", true).assertTextEquals("Fixture account B")
        composeRule.runOnIdle { firstResponse.complete(SettingsAccountProfile("Fixture old account A", null)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settingsAccountNickname:bilibili", true).assertTextEquals("Fixture account B")
        composeRule.runOnIdle {
            request.value = SettingsAccountProfileRequest(false, 0, null)
        }
        composeRule.onNodeWithTag("settingsAccountNickname:bilibili", true)
            .assertTextEquals(string(CoreCommonR.string.settings_account_sign_in_hint))
        composeRule.onNodeWithTag("settingsAccountAuthorization:bilibili", true)
            .assertTextEquals(string(CoreCommonR.string.settings_account_authorization_missing))
        composeRule.onNodeWithTag("settingsAccountAvatar:bilibili", true).assertDoesNotExist()
    }

    @Test
    fun leavingTheAccountsPageCancelsAnUnfinishedProfileWithoutPublishingIt() {
        val response = CompletableDeferred<SettingsAccountProfile>()
        val request = mutableStateOf(SettingsAccountProfileRequest(true, 100, "fixture-a"))
        val active = mutableStateOf(true)
        var started = false
        renderOwner(request, active) {
            started = true
            withContext(NonCancellable) { response.await() }
        }
        composeRule.waitUntil(2_000) { started }
        composeRule.runOnIdle { active.value = false }
        composeRule.runOnIdle { response.complete(SettingsAccountProfile("Fixture hidden response", null)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settingsAccountNickname:bilibili", true)
            .assertTextEquals(string(CoreCommonR.string.settings_account_profile_unavailable))
        composeRule.onNodeWithTag("settingsAccountAuthorization:bilibili", true)
            .assertTextEquals(string(CoreCommonR.string.settings_account_authorization_saved))
    }

    private fun render(
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        accounts: List<SettingsAccountCardUiState> = fixtureAccounts()
    ) {
        renderFixture(width, height, fontScale) {
            SettingsAccountCardsContent(accounts, { loginCalls.add(it) }, { managementCalls.add(it) })
        }
    }

    private fun renderOwner(
        request: MutableState<SettingsAccountProfileRequest>,
        active: MutableState<Boolean>,
        load: suspend (SettingsAccountProfileRequest) -> SettingsAccountProfile?
    ) {
        renderFixture(380, 700, 1f) {
            val currentRequest = request.value
            val profile = rememberSettingsAccountProfile(currentRequest, active.value) { load(currentRequest) }
            SettingsAccountCardsContent(
                accounts = listOf(SettingsAccountCardUiState(
                    platform = SettingsAccountPlatform.Bilibili,
                    hasSavedAuthorization = currentRequest.hasSavedAuthorization,
                    authorizationComplete = true,
                    profile = profile.profile,
                    profileLoading = profile.loading
                )),
                onLogin = {}, onManageSaved = {}
            )
        }
    }

    private fun renderFixture(width: Int, height: Int, fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val size = DpSize(width.dp, height.dp)
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
                            Modifier.requiredSize(size).background(MaterialTheme.colorScheme.background)
                                .testTag(FIXTURE_ROOT).verticalScroll(rememberScrollState()).padding(18.dp)
                        ) { content() }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun fixtureAccounts() = listOf(
        SettingsAccountCardUiState(
            SettingsAccountPlatform.Netease, hasSavedAuthorization = true, authorizationComplete = true,
            savedAtLabel = "Fixture update", profile = SettingsAccountProfile(NETEASE_NAME, syntheticAvatar())
        ),
        SettingsAccountCardUiState(
            SettingsAccountPlatform.Bilibili, hasSavedAuthorization = true, authorizationComplete = true,
            savedAtLabel = "Fixture update", profile = SettingsAccountProfile(BILI_NAME, syntheticAvatar())
        ),
        SettingsAccountCardUiState(SettingsAccountPlatform.YouTube),
        SettingsAccountCardUiState(SettingsAccountPlatform.QqMusic)
    )

    private fun syntheticAvatar(): String {
        avatarFile?.let { return Uri.fromFile(it).toString() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("account-avatar-fixture-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(95, 119, 160))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 227, 241) }
        canvas.drawCircle(64f, 43f, 24f, paint)
        canvas.drawCircle(64f, 119f, 44f, paint)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        avatarFile = file
        return Uri.fromFile(file).toString()
    }

    private fun bounds(tag: String, unmerged: Boolean = true): Rect =
        composeRule.onNodeWithTag(tag, unmerged).fetchSemanticsNode().boundsInRoot

    private fun assertInside(child: Rect, parent: Rect) {
        assertTrue("控件应在所属卡片内", child.left >= parent.left - 1 && child.right <= parent.right + 1)
        assertTrue("控件应在所属卡片内", child.top >= parent.top - 1 && child.bottom <= parent.bottom + 1)
    }

    private fun string(resourceId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId)

    private fun capture(stage: String) {
        val arguments = InstrumentationRegistry.getArguments()
        val prefix = arguments.getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(60)?.takeIf(String::isNotBlank)
            ?: "accounts".takeIf { arguments.getString("captureUi").toBoolean() } ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "$prefix-$stage.png")
        val bitmap = composeRule.onNodeWithTag(FIXTURE_ROOT).captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        Log.i("SettingsAccountCardsTest", "screenshot=${file.absolutePath}")
    }

    private companion object {
        const val FIXTURE_ROOT = "settingsAccountFixture"
        const val NETEASE_NAME = "Fixture NetEase account"
        const val BILI_NAME = "Fixture Bilibili account"
    }
}
