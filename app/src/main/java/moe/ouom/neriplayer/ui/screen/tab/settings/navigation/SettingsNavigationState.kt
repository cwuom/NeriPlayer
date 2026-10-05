package moe.ouom.neriplayer.ui.screen.tab.settings.navigation

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CoroutineScope
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsSearchEntry
import moe.ouom.neriplayer.ui.screen.tab.settings.page.backTargetPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.buildSettingsSearchEntries
import moe.ouom.neriplayer.ui.screen.tab.settings.page.resolveSettingsSearchHighlightTarget
import moe.ouom.neriplayer.ui.screen.tab.settings.page.searchSettingsEntries
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsSearchScrollAnchor
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.util.platform.PHONE_SMALLEST_SCREEN_WIDTH_DP
import kotlin.math.roundToInt

internal fun initialSettingsPage(splitLayout: Boolean): SettingsPage? =
    if (splitLayout) SettingsPage.General else null

internal fun shouldUseTabletSettingsTransitions(smallestScreenWidthDp: Int): Boolean =
    smallestScreenWidthDp >= PHONE_SMALLEST_SCREEN_WIDTH_DP

internal fun shouldUseSettingsSplitLayout(
    smallestScreenWidthDp: Int,
    windowWidth: Dp
): Boolean = shouldUseTabletSettingsTransitions(smallestScreenWidthDp) && windowWidth >= 840.dp

internal fun shouldShowSettingsDetailHeader(page: SettingsPage): Boolean =
    page != SettingsPage.Accounts

internal fun ensureSplitSettingsPage(splitLayout: Boolean, page: SettingsPage?): SettingsPage? =
    page ?: initialSettingsPage(splitLayout)

internal fun pendingSettingsNavigationForPage(
    activePage: SettingsPage?,
    pending: PendingSettingsSearchNavigation?
): PendingSettingsSearchNavigation? = pending?.takeIf { it.page == activePage }

internal fun canNavigateBackFromSettingsPage(
    activePage: SettingsPage?,
    splitLayout: Boolean
): Boolean = activePage != null && (!splitLayout || activePage.backTargetPage() != null)

internal fun settingsSearchResultsState(
    entries: List<SettingsSearchEntry>,
    queryState: State<String>
): State<List<SettingsSearchEntry>> = derivedStateOf {
    searchSettingsEntries(entries, queryState.value, limit = 8)
}

@Composable
private fun rememberInitialSettingsPageState(): MutableState<SettingsPage?> =
    rememberSaveable { mutableStateOf(null) }

@Composable
private fun settingsSplitLayout(): Boolean {
    // 手机播放页旋转时设置仍在底层组合，不能因此改写当前设置页
    return shouldUseSettingsSplitLayout(
        LocalConfiguration.current.smallestScreenWidthDp, currentWindowWidthDp()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
internal class SettingsNavigationState(
    val splitLayout: Boolean,
    val activePageState: MutableState<SettingsPage?>,
    val homeTopAppBarState: TopAppBarState,
    val detailTopAppBarStates: Map<SettingsPage, TopAppBarState>,
    val detailListStates: Map<SettingsPage, LazyListState>,
    val searchQueryState: MutableState<String>,
    val highlightTargetState: MutableState<String?>,
    val highlightPulseState: MutableIntState,
    private val searchRequestIdState: MutableIntState,
    private val pendingNavigationState: MutableState<PendingSettingsSearchNavigation?>,
    private val searchResultsState: State<List<SettingsSearchEntry>>,
    private val dynamicColor: Boolean,
    private val mobileDataFollowDefaultAudioQuality: Boolean,
    private val hasCustomBackground: Boolean
) {
    val searchResults: List<SettingsSearchEntry>
        get() = searchResultsState.value

    var activePage: SettingsPage?
        get() = ensureSplitSettingsPage(splitLayout, activePageState.value)
        set(value) { activePageState.value = value }

    val backTarget: SettingsPage?
        get() = activePage?.backTargetPage()

    val showSplitDetailBackButton: Boolean
        get() = backTarget != null

    fun navigateBack() {
        activePage = backTarget
    }

    fun clearHighlight() {
        highlightTargetState.value = null
    }

    fun selectSearchResult(entry: SettingsSearchEntry) {
        val targetId = resolveSettingsSearchHighlightTarget(
            entry = entry,
            dynamicColor = dynamicColor,
            mobileDataFollowDefaultAudioQuality = mobileDataFollowDefaultAudioQuality,
            hasCustomBackground = hasCustomBackground
        )
        activePage = entry.page
        clearHighlight()
        searchRequestIdState.intValue += 1
        pendingNavigationState.value = PendingSettingsSearchNavigation(
            page = entry.page,
            targetId = targetId,
            requestId = searchRequestIdState.intValue
        )
    }

    suspend fun completePendingNavigation(
        pending: PendingSettingsSearchNavigation,
        density: Density
    ) {
        clearHighlight()
        val detailListState = detailListStates.getValue(pending.page)
        val anchor = settingsSearchScrollAnchor(pending.page, pending.targetId)
        withFrameNanos { }
        withFrameNanos { }
        snapshotFlow { detailListState.layoutInfo.totalItemsCount }
            .first { it > anchor.itemIndex }
        val scrollOffset = with(density) { anchor.scrollOffset.toPx().roundToInt() }
        detailListState.scrollToItem(anchor.itemIndex, scrollOffset)
        withFrameNanos { }
        detailListState.scrollToItem(anchor.itemIndex, scrollOffset)
        highlightTargetState.value = pending.targetId
        highlightPulseState.intValue = pending.requestId
        pendingNavigationState.value = null
    }
}

internal class SettingsSearchScrollOwner(
    private val queryState: MutableState<String>,
    private val scrollToStart: suspend () -> Unit
) {
    suspend fun observe() {
        snapshotFlow { queryState.value }.collectLatest { query ->
            if (query.isNotBlank()) scrollToStart()
        }
    }
}

@Composable
private fun ObserveSettingsSearchScroll(
    queryState: MutableState<String>,
    listState: LazyListState
) {
    val owner = SettingsSearchScrollOwner(queryState) { listState.scrollToItem(0) }
    LaunchedEffect(listState) { owner.observe() }
}

@Composable
private fun CompletePendingSettingsNavigation(
    pending: PendingSettingsSearchNavigation?,
    navigation: SettingsNavigationState,
    density: Density
) {
    if (pending != null) {
        CompletePendingSettingsNavigationEffect(
            PendingSettingsNavigationAction(pending, navigation, density)
        )
    }
}

private class PendingSettingsNavigationAction(
    val pending: PendingSettingsSearchNavigation,
    private val navigation: SettingsNavigationState,
    private val density: Density
) {
    val effect: suspend CoroutineScope.() -> Unit = { execute() }

    suspend fun execute() {
        navigation.completePendingNavigation(pending, density)
    }
}

@Composable
private fun CompletePendingSettingsNavigationEffect(
    action: PendingSettingsNavigationAction
) {
    LaunchedEffect(action.pending, block = action.effect)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun rememberSettingsNavigationState(
    context: Context,
    listState: LazyListState,
    dynamicColor: Boolean,
    mobileDataFollowDefaultAudioQuality: Boolean,
    backgroundImageUri: String?
): SettingsNavigationState {
    val splitLayout = settingsSplitLayout()
    val activePageState = rememberInitialSettingsPageState()
    val homeTopAppBarState = rememberTopAppBarState()
    val detailTopAppBarStates = SettingsPage.entries.associateWith { rememberTopAppBarState() }
    val detailListStates = SettingsPage.entries.associateWith {
        rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    }
    val searchQueryState = rememberSaveable { mutableStateOf("") }
    val highlightTargetState = rememberSaveable { mutableStateOf<String?>(null) }
    val highlightPulseState = rememberSaveable { mutableIntStateOf(0) }
    val searchRequestIdState = rememberSaveable { mutableIntStateOf(0) }
    val pendingNavigationState = remember { mutableStateOf<PendingSettingsSearchNavigation?>(null) }
    val searchEntries = remember(context) { buildSettingsSearchEntries(context) }
    val searchResultsState = remember(searchEntries) {
        settingsSearchResultsState(searchEntries, searchQueryState)
    }
    val density = LocalDensity.current
    val navigation = SettingsNavigationState(
        splitLayout = splitLayout,
        activePageState = activePageState,
        homeTopAppBarState = homeTopAppBarState,
        detailTopAppBarStates = detailTopAppBarStates,
        detailListStates = detailListStates,
        searchQueryState = searchQueryState,
        highlightTargetState = highlightTargetState,
        highlightPulseState = highlightPulseState,
        searchRequestIdState = searchRequestIdState,
        pendingNavigationState = pendingNavigationState,
        searchResultsState = searchResultsState,
        dynamicColor = dynamicColor,
        mobileDataFollowDefaultAudioQuality = mobileDataFollowDefaultAudioQuality,
        hasCustomBackground = backgroundImageUri != null
    )

    LaunchedEffect(splitLayout) {
        activePageState.value = ensureSplitSettingsPage(splitLayout, activePageState.value)
    }
    ObserveSettingsSearchScroll(searchQueryState, listState)
    val eligiblePendingNavigation = pendingSettingsNavigationForPage(
        navigation.activePage, pendingNavigationState.value
    )
    CompletePendingSettingsNavigation(eligiblePendingNavigation, navigation, density)
    BackHandler(enabled = canNavigateBackFromSettingsPage(navigation.activePage, splitLayout)) {
        navigation.navigateBack()
    }
    return navigation
}
