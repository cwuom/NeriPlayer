package moe.ouom.neriplayer.listentogether.validation

import android.content.Context
import androidx.annotation.StringRes
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.util.platform.LanguageManager

data class ListenTogetherValidationError(
    @param:StringRes val messageResId: Int,
    val args: List<Any> = emptyList()
) {
    fun format(context: Context): String {
        val localizedContext = LanguageManager.applyLanguage(context.applicationContext)
        return localizedContext.getString(messageResId, *args.toTypedArray())
    }

    fun formatForApp(): String {
        return format(AppContainer.applicationContext)
    }
}
