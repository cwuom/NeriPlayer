package moe.ouom.neriplayer.ui.screen.tab.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.viewmodel.tab.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreSearchHeaderStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private data class SearchContent(
        val query: String = "demo",
        val searching: Boolean = false,
        val error: String? = null,
        val resultCount: Int = 20,
        val source: SearchSource = SearchSource.NETEASE
    )

    private val content = mutableStateOf(SearchContent())
    private lateinit var header: ExploreCollapsingHeaderState

    @Test
    fun loadingSearchExpandsCollapsedHeader() {
        assertHeaderExpands(SearchContent(searching = true))
    }

    @Test
    fun emptySearchExpandsCollapsedHeader() {
        assertHeaderExpands(SearchContent(resultCount = 0))
    }

    @Test
    fun failedSearchExpandsCollapsedHeader() {
        assertHeaderExpands(SearchContent(error = "request failed"))
    }

    @Test
    fun clearingSearchExpandsCollapsedHeader() {
        assertHeaderExpands(SearchContent(query = "", resultCount = 0))
    }

    @Test
    fun updatingSuccessfulResultsKeepsTheHeaderCollapsed() {
        showCollapsedHeader()

        composeRule.runOnIdle { content.value = SearchContent(resultCount = 40) }

        composeRule.onNodeWithTag(HEADER_TAG).assertHeightIsEqualTo(0.dp)
        composeRule.runOnIdle { assertEquals(-header.heightPx.toFloat(), header.offsetPx, 0f) }
    }

    @Test
    fun switchingToBilibiliDefaultContentExpandsCollapsedHeader() {
        assertHeaderExpands(
            initial = SearchContent(query = "", resultCount = 0),
            next = SearchContent(query = "", resultCount = 0, source = SearchSource.BILIBILI)
        )
    }

    @Test
    fun switchingToLinkDefaultContentExpandsCollapsedHeader() {
        assertHeaderExpands(
            initial = SearchContent(query = "", resultCount = 0),
            next = SearchContent(query = "", resultCount = 0, source = SearchSource.LINK_RECOGNITION)
        )
    }

    @Test
    fun switchingScrollableDefaultSourcesKeepsTheHeaderCollapsed() {
        showCollapsedHeader(SearchContent(query = "", resultCount = 0))

        composeRule.runOnIdle {
            content.value = SearchContent(query = "", resultCount = 0, source = SearchSource.YOUTUBE_MUSIC)
        }

        composeRule.onNodeWithTag(HEADER_TAG).assertHeightIsEqualTo(0.dp)
    }

    @Test
    fun switchingSuccessfulSearchSourceKeepsTheHeaderCollapsed() {
        showCollapsedHeader()

        composeRule.runOnIdle { content.value = SearchContent(source = SearchSource.BILIBILI) }

        composeRule.onNodeWithTag(HEADER_TAG).assertHeightIsEqualTo(0.dp)
    }

    private fun assertHeaderExpands(next: SearchContent, initial: SearchContent = SearchContent()) {
        showCollapsedHeader(initial)

        composeRule.runOnIdle { content.value = next }

        composeRule.onNodeWithTag(HEADER_TAG).assertHeightIsEqualTo(180.dp)
        composeRule.runOnIdle { assertEquals(0f, header.offsetPx, 0f) }
    }

    private fun showCollapsedHeader(initial: SearchContent = SearchContent()) {
        content.value = initial
        composeRule.setContent {
            val current = content.value
            header = rememberExploreSearchCollapsingHeaderState(
                query = current.query,
                source = current.source,
                searching = current.searching,
                searchError = current.error,
                hasResults = current.resultCount > 0
            )
            Box(Modifier.width(300.dp).testTag(HEADER_TAG).exploreCollapsingHeader(header)) {
                Box(Modifier.width(300.dp).height(180.dp))
            }
        }
        composeRule.runOnIdle {
            header.connection.onPreScroll(
                Offset(0f, -header.heightPx.toFloat()),
                NestedScrollSource.UserInput
            )
        }
        composeRule.onNodeWithTag(HEADER_TAG).assertHeightIsEqualTo(0.dp)
    }

    private companion object {
        const val HEADER_TAG = "explore-search-header"
    }
}
