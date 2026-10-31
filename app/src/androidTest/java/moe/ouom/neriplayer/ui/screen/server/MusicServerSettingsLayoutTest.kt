package moe.ouom.neriplayer.ui.screen.server

import android.view.View
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.captureCommittedFrame
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountCardUiState
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountCardsContent
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountPlatform
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsMusicServerCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsDetailScaffold
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class MusicServerSettingsLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var hostView: View
    private var contentInsetPx = 0f
    private var pageMarginPx = 0f
    private var sampleInsetPx = 0

    @Before fun requireComposeHost() = assumeComposeHostAvailable()

    @Test fun cleanLightEntryMatchesAccounts() = verifyEntry(UiStyle.Clean, false, 1f)
    @Test fun cleanDarkEntryMatchesAccounts() = verifyEntry(UiStyle.Clean, true, 1f)
    @Test fun materialLightEntryMatchesAccounts() = verifyEntry(UiStyle.Material, false, 1f)
    @Test fun materialDarkEntryMatchesAccounts() = verifyEntry(UiStyle.Material, true, 1f)
    @Test fun cleanLightLargeFontEntryMatchesAccounts() = verifyEntry(UiStyle.Clean, false, 2f)
    @Test fun cleanDarkLargeFontEntryMatchesAccounts() = verifyEntry(UiStyle.Clean, true, 2f)
    @Test fun materialLightLargeFontEntryMatchesAccounts() = verifyEntry(UiStyle.Material, false, 2f)
    @Test fun materialDarkLargeFontEntryMatchesAccounts() = verifyEntry(UiStyle.Material, true, 2f)
    @Test fun cleanLightManagementMatchesAccounts() = verifyManagement(UiStyle.Clean, false, 1f)
    @Test fun cleanDarkManagementMatchesAccounts() = verifyManagement(UiStyle.Clean, true, 1f)
    @Test fun materialLightManagementMatchesAccounts() = verifyManagement(UiStyle.Material, false, 1f)
    @Test fun materialDarkManagementMatchesAccounts() = verifyManagement(UiStyle.Material, true, 1f)
    @Test fun cleanLightLargeFontManagementMatchesAccounts() = verifyManagement(UiStyle.Clean, false, 2f)
    @Test fun cleanDarkLargeFontManagementMatchesAccounts() = verifyManagement(UiStyle.Clean, true, 2f)
    @Test fun materialLightLargeFontManagementMatchesAccounts() = verifyManagement(UiStyle.Material, false, 2f)
    @Test fun materialDarkLargeFontManagementMatchesAccounts() = verifyManagement(UiStyle.Material, true, 2f)

    private fun renderReference(style: UiStyle, dark: Boolean, fontScale: Float, management: Boolean): Color {
        composeRule.setContent { SettingsFixture(style, dark, fontScale, management) }
        composeRule.onNodeWithTag(REFERENCE).performScrollTo()
        assertPageMargin(REFERENCE)
        val platformContent = bounds("settingsAccountStacked:netease")
        assertContentInset(bounds(REFERENCE), platformContent)
        return cardFill(REFERENCE)
    }

    private fun verifyEntry(style: UiStyle, dark: Boolean, fontScale: Float) {
        val platformFill = renderReference(style, dark, fontScale, management = false)
        composeRule.onNodeWithTag(ENTRY).performScrollTo()
        assertPageMargin(ENTRY)
        assertContentInset(bounds(ENTRY), bounds("settingsMusicServerContent"))
        assertCardFill(platformFill, cardFill(ENTRY))
        capture("server-settings-entry-${style.storageValue}-$dark-$fontScale")
        composeRule.onNodeWithTag(ENTRY).performClick()
        composeRule.onNodeWithTag("serverAccountIntro").performScrollTo().assertIsDisplayed()
    }

    private fun verifyManagement(style: UiStyle, dark: Boolean, fontScale: Float) {
        val platformFill = renderReference(style, dark, fontScale, management = true)
        composeRule.onNodeWithTag("serverAccountIntro").performScrollTo()
        assertPageMargin("serverAccountIntro")
        assertContentInset(bounds("serverAccountIntro"), bounds("serverAccountIntroContent"))
        assertInside(bounds("serverAccountAdd"), bounds("serverAccountIntroContent"))
        assertCardFill(platformFill, cardFill("serverAccountIntro"))

        composeRule.onNodeWithTag(PROFILE_CARD).performScrollTo()
        assertPageMargin(PROFILE_CARD)
        assertContentInset(bounds(PROFILE_CARD), bounds("serverAccountContent:${profile.id}"))
        assertCardFill(platformFill, cardFill(PROFILE_CARD))
        for (action in listOf(CoreCommonR.string.server_edit, CoreCommonR.string.server_remove)) {
            val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(action)
            val node = composeRule.onNodeWithText(label).fetchSemanticsNode()
            assertInside(Rect(node.positionInRoot, node.size.toSize()), bounds("serverAccountContent:${profile.id}"))
        }
        val identity = bounds("serverAccountIdentity:${profile.id}")
        val endpoint = bounds("serverAccountAddress:${profile.id}")
        val edit = bounds("serverAccountEdit:${profile.id}")
        val remove = bounds("serverAccountRemove:${profile.id}")
        assertTrue("server address follows the identity block", endpoint.top >= identity.bottom - 1f)
        assertTrue("account actions follow the server address", edit.top >= endpoint.bottom - 1f && remove.top >= endpoint.bottom - 1f)
        assertTrue("account actions do not overlap", !edit.overlaps(remove))
        assertInside(bounds("serverAccountLabel:${profile.id}"), identity)
        assertInside(bounds("serverAccountUser:${profile.id}"), identity)
        assertTrue("account actions retain a touchable height", edit.height >= with(composeRule.density) { 48.dp.toPx() } - 1f)
        capture("server-settings-management-${style.storageValue}-$dark-$fontScale")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsFixture(style: UiStyle, dark: Boolean, fontScale: Float, initialManagement: Boolean) {
        hostView = LocalView.current
        val baseDensity = LocalDensity.current
        val density = Density(baseDensity.density, fontScale)
        CompositionLocalProvider(LocalDensity provides density) {
            NeriTheme(followSystemDark = false, forceDark = dark, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = style, animateColors = false) {
                val margin = if (currentWindowWidthDp() >= 720.dp) 28.dp else if (style == UiStyle.Clean) 16.dp else 18.dp
                contentInsetPx = with(density) { 20.dp.toPx() }
                pageMarginPx = with(density) { margin.toPx() }
                sampleInsetPx = with(density) { 2.dp.roundToPx() }
                var management by remember { mutableStateOf(initialManagement) }
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.widthIn(max = 360.dp).fillMaxSize().testTag(ROOT)) {
                        MiuixSettingsDetailScaffold(
                            title = if (management) "Music servers" else "Accounts",
                            onBack = {},
                            listState = rememberLazyListState(),
                            topAppBarState = rememberTopAppBarState()
                        ) {
                            if (management) {
                                item {
                                    SettingsAccountCardsContent(
                                        accounts = listOf(SettingsAccountCardUiState(SettingsAccountPlatform.Netease)),
                                        onLogin = {}, onManageSaved = {})
                                }
                                item {
                                    MusicServerAccountsContent(listOf(profile), loaded = true, busy = false,
                                        error = null, onRetry = {}, onAdd = {}, onEdit = {}, onRemove = {})
                                }
                            } else {
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        SettingsAccountCardsContent(
                                            accounts = listOf(SettingsAccountCardUiState(SettingsAccountPlatform.Netease)),
                                            onLogin = {}, onManageSaved = {})
                                        SettingsMusicServerCard { management = true }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): Rect {
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    private fun assertPageMargin(tag: String) {
        val root = bounds(ROOT)
        val card = bounds(tag)
        assertEquals("page left margin: $tag", pageMarginPx, card.left - root.left, 1f)
        assertEquals("page right margin: $tag", pageMarginPx, root.right - card.right, 1f)
    }

    private fun assertContentInset(card: Rect, content: Rect) {
        assertEquals("card left content inset", contentInsetPx, content.left - card.left, 1f)
        assertEquals("card top content inset", contentInsetPx, content.top - card.top, 1f)
        assertEquals("card right content inset", contentInsetPx, card.right - content.right, 1f)
        assertEquals("card bottom content inset", contentInsetPx, card.bottom - content.bottom, 1f)
    }

    private fun assertInside(child: Rect, parent: Rect) {
        assertTrue("action stays within the padded content: $child in $parent",
            child.left >= parent.left - 1f && child.top >= parent.top - 1f &&
                child.right <= parent.right + 1f && child.bottom <= parent.bottom + 1f)
    }

    private fun cardFill(tag: String): Color {
        val bitmap = composeRule.captureCommittedFrame(hostView, bounds(tag))
        try {
            val x = bitmap.width / 2
            val y = bitmap.height - sampleInsetPx.coerceAtLeast(1)
            assertTrue("card sample is inside the captured card", x in 0 until bitmap.width && y in 0 until bitmap.height)
            return Color(bitmap.getPixel(x, y))
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertCardFill(expected: Color, actual: Color) {
        assertEquals("account card red", expected.red, actual.red, 0.01f)
        assertEquals("account card green", expected.green, actual.green, 0.01f)
        assertEquals("account card blue", expected.blue, actual.blue, 0.01f)
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureMusicServerUi") == "true") {
            composeRule.captureMusicServerUi("$name.png", hostView)
        }
    }

    private val profile = SubsonicProfile("layout-fixture", "A synthetic server account with a long label",
        "https://music.example.test/a-library-with-a-long-name/", "synthetic-listener")

    private companion object {
        const val ROOT = "serverSettingsPage"
        const val REFERENCE = "settingsAccountCard:netease"
        const val ENTRY = "settingsMusicServerCard"
        const val PROFILE_CARD = "serverAccount:layout-fixture"
    }
}
