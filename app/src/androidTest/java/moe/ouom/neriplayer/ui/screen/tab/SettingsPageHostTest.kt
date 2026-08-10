package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsPageGroupCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsResponsiveDetailScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.backTargetPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsPageRowTestTag
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPageHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun splitLayoutKeepsOneNavigationPaneWhileSwitchingPages() {
        lateinit var activePage: MutableState<SettingsPage>
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                activePage = remember { mutableStateOf(SettingsPage.General) }
                MaterialTheme {
                    SettingsPageHost(
                        activePage = activePage.value,
                        splitLayout = true,
                        isolateAdvancedGlassTransitions = false
                    ) { page ->
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(0.42f)
                                    .fillMaxSize()
                                    .testTag(NAVIGATION_PANE_TAG)
                            )
                            Box(
                                modifier = Modifier
                                    .weight(0.58f)
                                    .fillMaxSize()
                                    .testTag("settings-detail-${page?.name}")
                            )
                        }
                    }
                }
            }

            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag(NAVIGATION_PANE_TAG).assertCountEquals(1)
            composeRule.onNodeWithTag("settings-detail-General").assertExists()

            composeRule.runOnIdle { activePage.value = SettingsPage.Accounts }
            advanceRecompositionFrame()
            composeRule.onAllNodesWithTag(NAVIGATION_PANE_TAG).assertCountEquals(1)
            composeRule.onNodeWithTag("settings-detail-Accounts").assertExists()

            composeRule.runOnIdle { activePage.value = SettingsPage.Theme }
            advanceRecompositionFrame()
            composeRule.onAllNodesWithTag(NAVIGATION_PANE_TAG).assertCountEquals(1)
            composeRule.onNodeWithTag("settings-detail-Theme").assertExists()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun splitDetailSwitchesWithoutKeepingOutgoingPage() {
        lateinit var activePage: MutableState<SettingsPage>
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                activePage = remember { mutableStateOf(SettingsPage.General) }
                MaterialTheme {
                    MiuixSettingsResponsiveDetailScaffold(
                        title = "Settings",
                        onBack = {},
                        listState = rememberLazyListState(),
                        topAppBarState = rememberTopAppBarState(),
                        splitLayout = true,
                        selectedPage = activePage.value,
                        homeListState = rememberLazyListState(),
                        homeTopAppBarState = rememberTopAppBarState(),
                        homeTitle = {},
                        homeContent = {
                            item {
                                Box(modifier = Modifier.testTag(NAVIGATION_PANE_TAG))
                            }
                        },
                        detailContent = { page ->
                            item {
                                Box(
                                    modifier = Modifier.testTag(
                                        "settings-detail-${page.name}"
                                    )
                                )
                            }
                        },
                        content = {}
                    )
                }
            }

            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag(NAVIGATION_PANE_TAG).assertCountEquals(1)
            composeRule.onNodeWithTag("settings-detail-General").assertExists()

            composeRule.runOnIdle { activePage.value = SettingsPage.Accounts }
            advanceRecompositionFrame()
            composeRule.onAllNodesWithTag(NAVIGATION_PANE_TAG).assertCountEquals(1)
            composeRule.onNodeWithTag("settings-detail-Accounts").assertExists()
            composeRule.onAllNodesWithTag("settings-detail-General").assertCountEquals(0)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun splitNavigationSelectionMovesWithPrimaryPageSwitch() {
        lateinit var activePage: MutableState<SettingsPage>
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                activePage = remember { mutableStateOf(SettingsPage.General) }
                MaterialTheme {
                    MiuixSettingsPageGroupCard(
                        pages = listOf(
                            SettingsPage.General,
                            SettingsPage.Accounts,
                            SettingsPage.Playback
                        ),
                        onPageClick = { page -> activePage.value = page },
                        selectedPage = activePage.value.backTargetPage() ?: activePage.value
                    )
                }
            }

            composeRule.waitForIdle()
            composeRule.onNodeWithTag(settingsPageRowTestTag(SettingsPage.General))
                .assertIsSelected()
            composeRule.onNodeWithTag(settingsPageRowTestTag(SettingsPage.Accounts))
                .assertIsNotSelected()

            composeRule.runOnIdle { activePage.value = SettingsPage.Accounts }
            advanceRecompositionFrame()
            composeRule.onNodeWithTag(settingsPageRowTestTag(SettingsPage.General))
                .assertIsNotSelected()
            composeRule.onNodeWithTag(settingsPageRowTestTag(SettingsPage.Accounts))
                .assertIsSelected()

            repeat(3) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag(settingsPageRowTestTag(SettingsPage.General))
                    .assertIsNotSelected()
                composeRule.onNodeWithTag(settingsPageRowTestTag(SettingsPage.Accounts))
                    .assertIsSelected()
            }

            composeRule.runOnIdle { activePage.value = SettingsPage.UsbExclusive }
            advanceRecompositionFrame()
            composeRule.onNodeWithTag(settingsPageRowTestTag(SettingsPage.Accounts))
                .assertIsNotSelected()
            composeRule.onNodeWithTag(settingsPageRowTestTag(SettingsPage.Playback))
                .assertIsSelected()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun splitDetailKeepsAnimationForNestedPage() {
        lateinit var activePage: MutableState<SettingsPage>
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                activePage = remember { mutableStateOf(SettingsPage.Playback) }
                MaterialTheme {
                    MiuixSettingsResponsiveDetailScaffold(
                        title = "Settings",
                        onBack = {},
                        listState = rememberLazyListState(),
                        topAppBarState = rememberTopAppBarState(),
                        splitLayout = true,
                        selectedPage = activePage.value,
                        homeListState = rememberLazyListState(),
                        homeTopAppBarState = rememberTopAppBarState(),
                        homeTitle = {},
                        homeContent = {},
                        detailContent = { page ->
                            item {
                                Box(
                                    modifier = Modifier.testTag(
                                        "settings-detail-${page.name}"
                                    )
                                )
                            }
                        },
                        content = {}
                    )
                }
            }

            composeRule.waitForIdle()
            composeRule.onNodeWithTag("settings-detail-Playback").assertExists()

            composeRule.runOnIdle { activePage.value = SettingsPage.UsbExclusive }
            repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
            composeRule.onNodeWithTag("settings-detail-Playback").assertExists()
            composeRule.onNodeWithTag("settings-detail-UsbExclusive").assertExists()

            composeRule.mainClock.advanceTimeBy(2_000)
            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag("settings-detail-Playback").assertCountEquals(0)
            composeRule.onNodeWithTag("settings-detail-UsbExclusive").assertExists()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun advanceRecompositionFrame() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private companion object {
        const val NAVIGATION_PANE_TAG = "settings-navigation-pane"
    }
}
