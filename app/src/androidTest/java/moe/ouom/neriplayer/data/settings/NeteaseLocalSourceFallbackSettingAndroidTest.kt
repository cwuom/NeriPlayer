package moe.ouom.neriplayer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val TestPlaybackSnapshotPrefs = "playback_snapshot_cache"

/**
 * 本地音频兜底开关的落盘往返, 覆盖 DataStore 读取与 SharedPreferences 镜像两条路径
 */
@RunWith(AndroidJUnit4::class)
class NeteaseLocalSourceFallbackSettingAndroidTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        repository = SettingsRepository(context)
        runBlocking {
            context.dataStore.edit { preferences ->
                preferences.remove(SettingsKeys.NETEASE_AUTO_SOURCE_SWITCH)
                preferences.remove(SettingsKeys.NETEASE_LOCAL_SOURCE_FALLBACK)
            }
            context.deleteSharedPreferences(TestPlaybackSnapshotPrefs)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            context.dataStore.edit { preferences ->
                preferences.remove(SettingsKeys.NETEASE_AUTO_SOURCE_SWITCH)
                preferences.remove(SettingsKeys.NETEASE_LOCAL_SOURCE_FALLBACK)
            }
            context.deleteSharedPreferences(TestPlaybackSnapshotPrefs)
        }
    }

    @Test
    fun defaultsToDisabled() {
        runBlocking {
            assertFalse(repository.neteaseLocalSourceFallbackFlow.first())
            assertFalse(readPlaybackPreferenceSnapshot(context).neteaseLocalSourceFallback)
        }
    }

    @Test
    fun disablingIsVisibleToFlowAndSnapshotMirror() {
        runBlocking {
            repository.setNeteaseLocalSourceFallback(false)

            assertFalse(repository.neteaseLocalSourceFallbackFlow.first())
            val cached = readPlaybackPreferenceSnapshotCached(context)
            assertNotNull(cached)
            assertFalse(cached!!.neteaseLocalSourceFallback)
            assertFalse(readPlaybackPreferenceSnapshot(context).neteaseLocalSourceFallback)
        }
    }

    @Test
    fun disabledValueSurvivesSnapshotMirrorLoss() {
        runBlocking {
            repository.setNeteaseLocalSourceFallback(false)
            // 丢掉镜像后必须能从 DataStore 重新推导, 否则冷启动会读回默认值
            context.deleteSharedPreferences(TestPlaybackSnapshotPrefs)

            assertFalse(readPlaybackPreferenceSnapshot(context).neteaseLocalSourceFallback)
        }
    }

    @Test
    fun disablingDoesNotDisturbTheAutoSourceSwitch() {
        runBlocking {
            repository.setNeteaseAutoSourceSwitch(true)
            repository.setNeteaseLocalSourceFallback(false)

            val snapshot = readPlaybackPreferenceSnapshot(context)
            assertTrue(snapshot.neteaseAutoSourceSwitch)
            assertFalse(snapshot.neteaseLocalSourceFallback)
            assertTrue(repository.neteaseAutoSourceSwitchFlow.first())
        }
    }

    @Test
    fun combinedFallbackSettingUpdatesBothOptions() {
        runBlocking {
            repository.setNeteasePlaybackSourceFallback(true)

            assertTrue(repository.neteaseAutoSourceSwitchFlow.first())
            assertTrue(repository.neteaseLocalSourceFallbackFlow.first())
            assertTrue(readPlaybackPreferenceSnapshot(context).neteaseAutoSourceSwitch)
            assertTrue(readPlaybackPreferenceSnapshot(context).neteaseLocalSourceFallback)
        }
    }

    @Test
    fun legacySnapshotIsRebuiltWithNewDefaultsWhenDataStoreKeysAreMissing() {
        runBlocking {
            context.getSharedPreferences(TestPlaybackSnapshotPrefs, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("ready", true)
                .putInt("schema_version", 4)
                .putBoolean("netease_auto_source_switch", true)
                .putBoolean("netease_local_source_fallback", true)
                .commit()

            val snapshot = readPlaybackPreferenceSnapshot(context)

            assertFalse(snapshot.neteaseAutoSourceSwitch)
            assertFalse(snapshot.neteaseLocalSourceFallback)
            assertEquals(
                5,
                context.getSharedPreferences(TestPlaybackSnapshotPrefs, Context.MODE_PRIVATE)
                    .getInt("schema_version", 0)
            )
        }
    }

    @Test
    fun legacySnapshotRebuildPreservesExplicitDataStoreChoice() {
        runBlocking {
            context.dataStore.edit { preferences ->
                preferences[SettingsKeys.NETEASE_AUTO_SOURCE_SWITCH] = true
                preferences[SettingsKeys.NETEASE_LOCAL_SOURCE_FALLBACK] = true
            }
            context.getSharedPreferences(TestPlaybackSnapshotPrefs, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("ready", true)
                .putInt("schema_version", 4)
                .putBoolean("netease_auto_source_switch", false)
                .putBoolean("netease_local_source_fallback", false)
                .commit()

            val snapshot = readPlaybackPreferenceSnapshot(context)

            assertTrue(snapshot.neteaseAutoSourceSwitch)
            assertTrue(snapshot.neteaseLocalSourceFallback)
        }
    }

    /**
     * 预取复验用 isLocalMediaUri 判断结果要不要重新解析, 这里锁死它对各类地址的判定
     */
    @Test
    fun localMediaUriGateAcceptsFallbackUrlsAndRejectsRemoteOnes() {
        val cacheFile = File(context.cacheDir, "local-fallback-probe.mp3").apply {
            writeBytes(byteArrayOf(0, 1, 2))
        }
        try {
            assertTrue(LocalSongSupport.isLocalMediaUri(android.net.Uri.fromFile(cacheFile).toString()))
            assertTrue(LocalSongSupport.isLocalMediaUri(cacheFile.absolutePath))
            assertTrue(
                LocalSongSupport.isLocalMediaUri(
                    "content://media/external/audio/media/42"
                )
            )
            assertFalse(LocalSongSupport.isLocalMediaUri("https://music.163.com/song/media.mp3"))
            assertFalse(LocalSongSupport.isLocalMediaUri("http://offline.cache/netease-123-exhigh"))
            assertFalse(LocalSongSupport.isLocalMediaUri(null))
            assertFalse(LocalSongSupport.isLocalMediaUri(""))
        } finally {
            cacheFile.delete()
        }
    }

    @Test
    fun fileUriBuiltFromAbsolutePathKeepsPointingAtTheSameFile() {
        val cacheFile = File(context.cacheDir, "local-fallback-roundtrip.mp3").apply {
            writeBytes(byteArrayOf(3, 4, 5))
        }
        try {
            val fileUri = android.net.Uri.fromFile(cacheFile)
            assertEquals("file", fileUri.scheme)
            assertEquals(cacheFile.absolutePath, fileUri.path)
            assertTrue(File(fileUri.path!!).canRead())
        } finally {
            cacheFile.delete()
        }
    }
}
