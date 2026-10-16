package moe.ouom.neriplayer.data.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class SettingsUiDensityScaleTest {
    @Test
    fun typedWritesPersistTheDeviceLimitAndSurviveRepositoryRecreation() {
        for ((width, mode, expected) in listOf(
            Triple(599, Configuration.UI_MODE_TYPE_NORMAL, 1.2f),
            Triple(600, Configuration.UI_MODE_TYPE_NORMAL, 2f),
            Triple(320, Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_YES, 2f)
        )) {
            withSettings(width, mode) { context ->
                SettingsRepository(context).setUiDensityScale(3f)

                assertEquals(expected, context.dataStore.data.first()[AutoSettingsKeys.UI_DENSITY_SCALE])
                assertEquals(expected, SettingsRepository(context).uiDensityScaleFlow.first(), 0f)
            }
        }
    }

    @Test
    fun rawRestoredPreferencesKeepTheirFiniteRequestedScaleForRuntimeDeviceClamping() {
        withSettings(390, Configuration.UI_MODE_TYPE_NORMAL) { context ->
            context.dataStore.edit { it[AutoSettingsKeys.UI_DENSITY_SCALE] = 2f }
            assertEquals(2f, SettingsRepository(context).uiDensityScaleFlow.first(), 0f)
            for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                context.dataStore.edit { it[AutoSettingsKeys.UI_DENSITY_SCALE] = scale }
                assertEquals(1f, SettingsRepository(context).uiDensityScaleFlow.first(), 0f)
            }
        }
    }

    @Test
    fun generatedAccessAlsoRejectsInvalidNumbersAndValuesOutsideTheGlobalEnvelope() {
        withSettings(800, Configuration.UI_MODE_TYPE_NORMAL) { context ->
            val generated = AutoSettingsRepository(context)
            generated.setUiDensityScale(3f)
            assertEquals(2f, generated.uiDensityScaleFlow.first(), 0f)
            generated.setUiDensityScale(Float.NaN)
            assertEquals(1f, generated.uiDensityScaleFlow.first(), 0f)
            context.dataStore.edit { it[AutoSettingsKeys.UI_DENSITY_SCALE] = Float.POSITIVE_INFINITY }
            assertEquals(1f, generated.uiDensityScaleFlow.first(), 0f)
            generated.setUiDensityScale(0.2f)
            assertEquals(0.6f, generated.uiDensityScaleFlow.first(), 0f)
        }
    }

    private fun withSettings(width: Int, uiMode: Int, assertions: suspend (Context) -> Unit) = runBlocking {
        val filesDir = File.createTempFile("neriplayer-ui-density", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val configuration = mock(Configuration::class.java).apply {
            smallestScreenWidthDp = width
            this.uiMode = uiMode
        }
        val resources = mock(Resources::class.java).also { `when`(it.configuration).thenReturn(configuration) }
        val context = mock(Context::class.java).also {
            `when`(it.filesDir).thenReturn(filesDir)
            `when`(it.applicationContext).thenReturn(it)
            `when`(it.resources).thenReturn(resources)
        }
        val saved = context.dataStore.data.first()
        try {
            context.dataStore.updateData { emptyPreferences() }
            assertions(context)
        } finally {
            context.dataStore.updateData { saved }
        }
    }
}
