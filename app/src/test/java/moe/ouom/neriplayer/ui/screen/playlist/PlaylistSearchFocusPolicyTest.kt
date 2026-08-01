package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSearchFocusPolicyTest {
    @Test
    fun requestsFocusOnlyWhenSearchIsOpenOutsideSelectionMode() {
        assertTrue(
            shouldRequestPlaylistSearchFocus(
                showSearch = true,
                selectionMode = false,
                autoShowKeyboard = true
            )
        )
        assertFalse(
            shouldRequestPlaylistSearchFocus(
                showSearch = false,
                selectionMode = false,
                autoShowKeyboard = true
            )
        )
        assertFalse(
            shouldRequestPlaylistSearchFocus(
                showSearch = true,
                selectionMode = true,
                autoShowKeyboard = true
            )
        )
    }

    @Test
    fun doesNotRequestFocusWhenAutomaticKeyboardIsDisabled() {
        assertFalse(
            shouldRequestPlaylistSearchFocus(
                showSearch = true,
                selectionMode = false,
                autoShowKeyboard = false
            )
        )
    }

    @Test
    fun transfersFocusWhenScrolledSearchStillHasInput() {
        assertTrue(
            shouldTransferPlaylistSearchFocus(
                showSearch = true,
                selectionMode = false,
                searchFieldComposed = true,
                searchInputFocused = false,
                searchQuery = "已输入的内容"
            )
        )
        assertTrue(
            shouldTransferPlaylistSearchFocus(
                showSearch = true,
                selectionMode = false,
                searchFieldComposed = true,
                searchInputFocused = true,
                searchQuery = ""
            )
        )
        assertFalse(
            shouldTransferPlaylistSearchFocus(
                showSearch = true,
                selectionMode = false,
                searchFieldComposed = true,
                searchInputFocused = false,
                searchQuery = ""
            )
        )
        assertFalse(
            shouldTransferPlaylistSearchFocus(
                showSearch = true,
                selectionMode = true,
                searchFieldComposed = true,
                searchInputFocused = true,
                searchQuery = "内容"
            )
        )
    }

    @Test
    fun synchronizesUntouchedSearchNodeWithoutOverwritingActiveTyping() {
        assertEquals(
            TextFieldValue(
                text = "最新查询",
                selection = TextRange("最新查询".length)
            ),
            resolvePlaylistSearchInputSyncValue(
                inputValue = TextFieldValue(
                    text = "",
                    selection = TextRange.Zero
                ),
                lastSynchronizedQuery = "",
                query = "最新查询"
            )
        )
        assertEquals(
            TextFieldValue(
                text = "",
                selection = TextRange.Zero
            ),
            resolvePlaylistSearchInputSyncValue(
                inputValue = TextFieldValue(
                    text = "旧内容",
                    selection = TextRange(1)
                ),
                lastSynchronizedQuery = "旧内容",
                query = ""
            )
        )
        assertEquals(
            null,
            resolvePlaylistSearchInputSyncValue(
                inputValue = TextFieldValue(
                    text = "正在输入",
                    selection = TextRange(2)
                ),
                lastSynchronizedQuery = "旧查询",
                query = "旧查询"
            )
        )
        assertEquals(
            null,
            resolvePlaylistSearchInputSyncValue(
                inputValue = TextFieldValue(
                    text = "正在输入",
                    selection = TextRange(2)
                ),
                lastSynchronizedQuery = "旧查询",
                query = ""
            )
        )
        assertEquals(
            null,
            resolvePlaylistSearchInputSyncValue(
                inputValue = TextFieldValue(
                    text = "拼音",
                    selection = TextRange(2),
                    composition = TextRange(0, 2)
                ),
                lastSynchronizedQuery = "拼音",
                query = "最新查询"
            )
        )
    }

    @Test
    fun resolvesSearchFieldOffsetUntilItDocksAtTop() {
        assertEquals(
            106,
            resolvePlaylistSearchFieldOffsetPx(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 0,
                expandedOffsetPx = 106
            )
        )
        assertEquals(
            26,
            resolvePlaylistSearchFieldOffsetPx(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 80,
                expandedOffsetPx = 106
            )
        )
        assertEquals(
            0,
            resolvePlaylistSearchFieldOffsetPx(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 140,
                expandedOffsetPx = 106
            )
        )
        assertEquals(
            0,
            resolvePlaylistSearchFieldOffsetPx(
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffsetPx = 0,
                expandedOffsetPx = 106
            )
        )
    }

    @Test
    fun usesStableHeroFallbackSeedBeforeCoverColorLoads() {
        assertEquals(
            0xFF5F6875.toInt(),
            resolvePlaylistHeroFallbackSeedArgb(isDarkTheme = false)
        )
        assertEquals(
            0xFF303846.toInt(),
            resolvePlaylistHeroFallbackSeedArgb(isDarkTheme = true)
        )
        assertFalse(
            resolvePlaylistHeroFallbackSeedArgb(isDarkTheme = false) ==
                0xFF795548.toInt()
        )
    }

    @Test
    fun normalizesBlankAndWhitespacePlaylistCoverModels() {
        assertEquals(
            "about:blank",
            normalizeLocalPlaylistHeaderCoverModel(null)
        )
        assertEquals(
            "about:blank",
            normalizeLocalPlaylistHeaderCoverModel("   ")
        )
        assertEquals(
            "https://example.com/cover.jpg",
            normalizeLocalPlaylistHeaderCoverModel("  https://example.com/cover.jpg  ")
        )
    }

    @Test
    fun keepsSearchSlotComposedWhileVisibilityAnimationIsRunning() {
        assertTrue(
            shouldComposePlaylistSearchSlot(
                searchVisible = true,
                visibilityProgress = 0f
            )
        )
        assertTrue(
            shouldComposePlaylistSearchSlot(
                searchVisible = false,
                visibilityProgress = 0.5f
            )
        )
        assertFalse(
            shouldComposePlaylistSearchSlot(
                searchVisible = false,
                visibilityProgress = 0f
            )
        )
    }

    @Test
    fun keepsPlaylistTopBarsTranslucentButReadable() {
        assertEquals(
            Color(0xFF17191F).toArgb(),
            resolvePlaylistSolidTopBarContentColor(Color.White).toArgb()
        )
        assertEquals(
            Color.White.copy(alpha = 0.95f).toArgb(),
            resolvePlaylistSolidTopBarContentColor(Color(0xFF102030)).toArgb()
        )
        assertEquals(
            1f,
            resolvePlaylistTranslucentTopBarColor(
                playlistColor = Color(0xFF102030),
                collapseProgress = 0f
            ).alpha,
            0.001f
        )
        assertEquals(
            0f,
            resolvePlaylistTranslucentTopBarColor(
                playlistColor = Color(0xFF102030),
                collapseProgress = 1f
            ).alpha,
            0.001f
        )
    }

    @Test
    fun revealsDockedSearchOnlyAfterActionBarHasScrolledAway() {
        assertEquals(
            0f,
            resolvePlaylistDockedSearchRevealProgress(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 160,
                revealDistancePx = 68
            ),
            0.001f
        )
        assertEquals(
            0f,
            resolvePlaylistDockedSearchRevealProgress(
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffsetPx = 120,
                revealDistancePx = 68
            ),
            0.001f
        )
        assertEquals(
            0f,
            resolvePlaylistDockedSearchRevealProgress(
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffsetPx = 0,
                revealDistancePx = 68
            ),
            0.001f
        )
        assertEquals(
            0.5f,
            resolvePlaylistDockedSearchRevealProgress(
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffsetPx = 34,
                revealDistancePx = 68
            ),
            0.001f
        )
        assertEquals(
            1f,
            resolvePlaylistDockedSearchRevealProgress(
                firstVisibleItemIndex = 3,
                firstVisibleItemScrollOffsetPx = 0,
                revealDistancePx = 68
            ),
            0.001f
        )
    }

    @Test
    fun adaptsDockedSearchGlassColorForLightAndDarkSurfaces() {
        assertEquals(
            Color.White.copy(alpha = 0.42f).toArgb(),
            resolvePlaylistDockedSearchGlassColor(
                playlistColor = Color(0xFF102030),
                isDarkSurface = false
            ).toArgb()
        )
        assertEquals(
            Color.White.copy(alpha = 0.50f).toArgb(),
            resolvePlaylistDockedSearchGlassColor(
                playlistColor = Color(0xFFF2F4EC),
                isDarkSurface = false
            ).toArgb()
        )
        assertEquals(
            Color.Black.copy(alpha = 0.28f).toArgb(),
            resolvePlaylistDockedSearchGlassColor(
                playlistColor = Color(0xFF102030),
                isDarkSurface = true
            ).toArgb()
        )
        assertEquals(
            Color.White.copy(alpha = 0.14f).toArgb(),
            resolvePlaylistDockedSearchGlassColor(
                playlistColor = Color(0xFFF2F4EC),
                isDarkSurface = true
            ).toArgb()
        )
    }

    @Test
    fun resolvesSearchDockedProgressDuringTheMoveToTop() {
        assertEquals(
            0f,
            resolvePlaylistSearchDockedProgress(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 0,
                expandedOffsetPx = 106
            ),
            0.001f
        )
        assertEquals(
            0.5f,
            resolvePlaylistSearchDockedProgress(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 53,
                expandedOffsetPx = 106
            ),
            0.001f
        )
        assertEquals(
            1f,
            resolvePlaylistSearchDockedProgress(
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffsetPx = 0,
                expandedOffsetPx = 106
            ),
            0.001f
        )
    }

    @Test
    fun reservesListTopSpaceOnlyAfterSearchFieldDocks() {
        assertEquals(
            0,
            resolvePlaylistSearchListTopPaddingPx(
                searchVisible = false,
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffsetPx = 0,
                revealDistancePx = 68,
                dockedSlotHeightPx = 68
            )
        )
        assertEquals(
            0,
            resolvePlaylistSearchListTopPaddingPx(
                searchVisible = true,
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffsetPx = 140,
                revealDistancePx = 68,
                dockedSlotHeightPx = 68
            )
        )
        assertEquals(
            34,
            resolvePlaylistSearchListTopPaddingPx(
                searchVisible = true,
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffsetPx = 34,
                revealDistancePx = 68,
                dockedSlotHeightPx = 68
            )
        )
        assertEquals(
            68,
            resolvePlaylistSearchListTopPaddingPx(
                searchVisible = true,
                firstVisibleItemIndex = 3,
                firstVisibleItemScrollOffsetPx = 0,
                revealDistancePx = 68,
                dockedSlotHeightPx = 68
            )
        )
    }

    @Test
    fun resolvesChromeCollapseAsContinuousScrollProgress() {
        assertEquals(
            0f,
            resolvePlaylistChromeCollapseProgress(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 0,
                expandedHeroHeightPx = 190
            ),
            0.001f
        )
        assertEquals(
            0.5f,
            resolvePlaylistChromeCollapseProgress(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 95,
                expandedHeroHeightPx = 190
            ),
            0.001f
        )
        assertEquals(
            1f,
            resolvePlaylistChromeCollapseProgress(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 240,
                expandedHeroHeightPx = 190
            ),
            0.001f
        )
        assertEquals(
            1f,
            resolvePlaylistChromeCollapseProgress(
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffsetPx = 0,
                expandedHeroHeightPx = 190
            ),
            0.001f
        )
    }

    @Test
    fun keepsDockedSearchHiddenUntilBothSearchAndDockRevealAreReady() {
        assertEquals(
            0f,
            resolvePlaylistDockedSearchSlotProgress(
                searchVisibilityProgress = 0f,
                dockedRevealProgress = 1f
            ),
            0.001f
        )
        assertEquals(
            0f,
            resolvePlaylistDockedSearchSlotProgress(
                searchVisibilityProgress = 1f,
                dockedRevealProgress = 0f
            ),
            0.001f
        )
        assertTrue(
            resolvePlaylistDockedSearchSlotProgress(
                searchVisibilityProgress = 1f,
                dockedRevealProgress = 0.5f
            ) in 0f..1f
        )
        assertEquals(
            1f,
            resolvePlaylistDockedSearchSlotProgress(
                searchVisibilityProgress = 1f,
                dockedRevealProgress = 1f
            ),
            0.001f
        )
    }

    @Test
    fun fadesHeaderSearchOutAsChromeCollapses() {
        assertEquals(
            0f,
            resolvePlaylistHeaderSearchAlpha(
                searchVisibilityProgress = 0f,
                chromeCollapseProgress = 0f
            ),
            0.001f
        )
        assertEquals(
            1f,
            resolvePlaylistHeaderSearchAlpha(
                searchVisibilityProgress = 1f,
                chromeCollapseProgress = 0f
            ),
            0.001f
        )
        assertTrue(
            resolvePlaylistHeaderSearchAlpha(
                searchVisibilityProgress = 1f,
                chromeCollapseProgress = 0.5f
            ) in 0f..1f
        )
        assertEquals(
            0f,
            resolvePlaylistHeaderSearchAlpha(
                searchVisibilityProgress = 1f,
                chromeCollapseProgress = 1f
            ),
            0.001f
        )
    }
}
