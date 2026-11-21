package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeSongSectionDisplayMode
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
 * 这些设置都是「读改写整个映射 / 整个集合」：
 * 如果读和写不在同一个 DataStore 事务里，连续修改两个小模块时，
 * 后一次写入会覆盖前一次的选择，用户就会看到其中一个设置没有生效。
 */
class HomeSectionSettingsAtomicWriteTest {

    @Test
    fun `concurrent song section mode writes keep both selections`() = runTest {
        val context = IsolatedSettingsDataStore.context(InMemorySharedPreferencesRegistry())
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val repository = SettingsRepository(context)

            // 并发写入两个不同的小模块，模拟用户连续切换两个下拉框。
            listOf(
                HomeTrendingSubSectionIds.TOP_SOARING,
                HomeTrendingSubSectionIds.TOP_HOT
            ).map { id ->
                async {
                    repository.setHomeSongSectionMode(id, HomeSongSectionDisplayMode.CARD)
                }
            }.awaitAll()

            val modes = repository.homeSongSectionModesFlow.first()
            assertEquals(
                mapOf(
                    HomeTrendingSubSectionIds.TOP_SOARING to HomeSongSectionDisplayMode.CARD,
                    HomeTrendingSubSectionIds.TOP_HOT to HomeSongSectionDisplayMode.CARD
                ),
                modes
            )
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
    fun `resetting a song section mode back to default removes the entry`() = runTest {
        val context = IsolatedSettingsDataStore.context(InMemorySharedPreferencesRegistry())
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val repository = SettingsRepository(context)

            repository.setHomeSongSectionMode(
                HomeTrendingSubSectionIds.TOP_SOARING,
                HomeSongSectionDisplayMode.CARD
            )
            assertTrue(
                repository.homeSongSectionModesFlow.first()
                    .containsKey(HomeTrendingSubSectionIds.TOP_SOARING)
            )

            repository.setHomeSongSectionMode(
                HomeTrendingSubSectionIds.TOP_SOARING,
                HomeSongSectionDisplayMode.DEFAULT
            )
            assertFalse(
                repository.homeSongSectionModesFlow.first()
                    .containsKey(HomeTrendingSubSectionIds.TOP_SOARING)
            )
        }
    }

    @Test
    fun `chart usage cleanup flag is only handed out once`() = runTest {
        val context = IsolatedSettingsDataStore.context(InMemorySharedPreferencesRegistry())
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val repository = SettingsRepository(context)

            assertTrue("首次调用应返回 true 表示需要清理", repository.consumeHomeChartUsageCleanupFlag())
            assertFalse("第二次调用应返回 false，避免反复删除正常记录", repository.consumeHomeChartUsageCleanupFlag())
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
