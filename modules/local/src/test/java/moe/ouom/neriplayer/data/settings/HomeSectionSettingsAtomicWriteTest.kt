package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeFeedKindIds
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeLayout
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeSectionIds
import moe.ouom.neriplayer.data.model.settings.home.parseHomeSectionHidden
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页小模块设置写入的原子性测试。
 *
 * 这些设置都是「读改写整个隐藏集合」：
 * 如果读和写不在同一个 DataStore 事务里，连续修改两个小模块时，
 * 后一次写入会覆盖前一次的选择，用户就会看到其中一个设置没有生效。
 */
class HomeSectionSettingsAtomicWriteTest {

    @Test
    fun `feed kinds survive concurrent changes and mode switches on an existing layout`() = runTest {
        val context = IsolatedSettingsDataStore.context(InMemorySharedPreferencesRegistry())
        IsolatedSettingsDataStore.withEmptySettings(context, seed = {
            it[SettingsKeys.INTERNATIONAL_HOME_ORDER] = "feed,spotlight,continue"
            it[SettingsKeys.INTERNATIONAL_HOME_HIDDEN] = "continue"
            it[SettingsKeys.HOME_RECOMMENDED_HIDDEN] = "personalized"
        }) {
            val repository = SettingsRepository(context)
            val kinds = InternationalHomeFeedKindIds
            val original = repository.internationalHomeLayoutFlow.first()
            assertEquals(kinds.DEFAULT_ORDER, original.visibleFeedKinds)
            repository.setInternationalizationEnabled(true)
            kinds.DEFAULT_ORDER.map { id ->
                async { repository.setInternationalHomeFeedKindVisible(id, false) }
            }.awaitAll()
            repository.setInternationalHomeFeedKindVisible("unknown", false)
            val hidden = original.copy(hiddenFeedKinds = kinds.DEFAULT_ORDER.toSet())
            assertEquals(hidden, context.dataStore.data.first().toInternationalHomeLayout())
            repository.setInternationalizationEnabled(false)
            assertEquals(setOf("personalized"), repository.homeRecommendedHiddenFlow.first())

            val reopened = SettingsRepository(context)
            reopened.setInternationalizationEnabled(true)
            assertEquals(hidden, reopened.internationalHomeLayoutFlow.first())
            reopened.setInternationalHomeFeedKindVisible(kinds.ALBUM, true)
            val restored = reopened.internationalHomeLayoutFlow.first()
            assertEquals(listOf(kinds.ALBUM), restored.visibleFeedKinds)
            assertEquals(original.visibleSections, restored.visibleSections)
        }
    }

    @Test
    fun `international order and visibility survive reopen independently of legacy cards`() = runTest {
        val context = IsolatedSettingsDataStore.context(InMemorySharedPreferencesRegistry())
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val repository = SettingsRepository(context)
            repository.setHomeMajorOrder(listOf("radar", "bilibili", "recommended", "trending", "continue"))
            repository.setHomeCardContinue(false)
            assertEquals(InternationalHomeLayout(), repository.internationalHomeLayoutFlow.first())

            val ids = InternationalHomeSectionIds
            repository.setInternationalHomeOrder(listOf(ids.FEED, "radar", ids.FEED))
            listOf(ids.SPOTLIGHT, ids.FEED).map { id ->
                async { repository.setInternationalHomeSectionVisible(id, false) }
            }.awaitAll()

            val reopened = SettingsRepository(context)
            assertEquals(
                InternationalHomeLayout(listOf(ids.FEED, ids.SPOTLIGHT, ids.CONTINUE), setOf(ids.FEED, ids.SPOTLIGHT)),
                reopened.internationalHomeLayoutFlow.first()
            )
            assertFalse(reopened.homeCardContinueFlow.first())
            assertEquals("radar", reopened.homeMajorOrderFlow.first().first())
        }
    }

    @Test
    fun `concurrent hidden toggles keep both sections hidden`() = runTest {
        val context = IsolatedSettingsDataStore.context(InMemorySharedPreferencesRegistry())
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val repository = SettingsRepository(context)

            listOf(
                HomeRadarSubSectionIds.DAILY_RECOMMEND,
                HomeRadarSubSectionIds.PRIVATE_FM
            ).map { id ->
                async {
                    repository.setHomeRadarSubSectionVisible(id, visible = false)
                }
            }.awaitAll()

            val hidden = repository.homeRadarHiddenFlow.first()
            assertTrue(
                "每日推荐应保持隐藏，实际 $hidden",
                hidden.contains(HomeRadarSubSectionIds.DAILY_RECOMMEND)
            )
            assertTrue(
                "私人 FM 应保持隐藏，实际 $hidden",
                hidden.contains(HomeRadarSubSectionIds.PRIVATE_FM)
            )
        }
    }

    @Test
    fun `showing a hidden section again removes it from the hidden set`() = runTest {
        val context = IsolatedSettingsDataStore.context(InMemorySharedPreferencesRegistry())
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val repository = SettingsRepository(context)

            repository.setHomeTrendingSubSectionVisible(
                HomeTrendingSubSectionIds.TOP_HOT,
                visible = false
            )
            assertTrue(repository.homeTrendingHiddenFlow.first().contains(HomeTrendingSubSectionIds.TOP_HOT))

            repository.setHomeTrendingSubSectionVisible(
                HomeTrendingSubSectionIds.TOP_HOT,
                visible = true
            )
            assertFalse(repository.homeTrendingHiddenFlow.first().contains(HomeTrendingSubSectionIds.TOP_HOT))
        }
    }

    @Test
    fun `hidden set parsing keeps only known ids`() {
        assertEquals(
            setOf(HomeRadarSubSectionIds.DAILY_RECOMMEND),
            parseHomeSectionHidden(
                "${HomeRadarSubSectionIds.DAILY_RECOMMEND},unknown_id",
                HomeRadarSubSectionIds.DEFAULT_ORDER
            )
        )
    }
}
