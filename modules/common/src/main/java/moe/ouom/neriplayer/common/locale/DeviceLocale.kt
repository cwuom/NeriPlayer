package moe.ouom.neriplayer.common.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart

object DeviceLocale {
    private val state = MutableStateFlow(Locale.getDefault())
    val changes = state.asStateFlow()

    fun read(context: Context): Locale {
        val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales
        } else null
        val system = locales?.takeUnless { it.isEmpty } ?: Resources.getSystem().configuration.locales
        return if (system.isEmpty) Locale.ROOT else system[0]
    }

    fun refresh(context: Context) {
        state.value = read(context)
    }

    fun observe(context: Context) = changes.onStart { refresh(context) }
}
