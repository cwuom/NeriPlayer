package moe.ouom.neriplayer.listentogether.validation

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherValidationError

import android.content.Context
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.util.platform.LanguageManager

fun ListenTogetherValidationError.format(context: Context): String {
    val localizedContext = LanguageManager.applyLanguage(context.applicationContext)
    return localizedContext.getString(messageResId, *args.toTypedArray())
}

fun ListenTogetherValidationError.formatForApp(): String {
    return format(AppContainer.applicationContext)
}
