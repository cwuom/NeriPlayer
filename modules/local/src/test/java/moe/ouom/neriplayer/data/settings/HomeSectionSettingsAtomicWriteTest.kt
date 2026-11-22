package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds
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
