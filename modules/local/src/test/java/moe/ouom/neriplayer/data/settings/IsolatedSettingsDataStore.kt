package moe.ouom.neriplayer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * 顶层 settings DataStore 委托在整个测试进程内只有一个实例,
 * 这里在测试期间清空它并在结束后恢复, 避免测试之间互相污染
 */
object IsolatedSettingsDataStore {
    fun context(preferences: InMemorySharedPreferencesRegistry): Context {
        val filesDir = File.createTempFile("neriplayer-isolated-settings", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.applicationContext).thenReturn(context)
        return preferences.install(context)
    }

    suspend fun <T> withEmptySettings(
        context: Context,
        seed: (androidx.datastore.preferences.core.MutablePreferences) -> Unit = {},
        block: suspend () -> T
    ): T {
        val saved: Preferences = context.dataStore.data.first()
        try {
            context.dataStore.updateData {
                emptyPreferences().toMutablePreferences().apply(seed)
            }
            return block()
        } finally {
            context.dataStore.updateData { saved }
        }
    }
}
