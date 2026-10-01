package moe.ouom.neriplayer.data.ltw.validation

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherValidationError

import android.content.Context
import moe.ouom.neriplayer.common.locale.LanguageManager

fun ListenTogetherValidationError.format(context: Context): String {
    val localizedContext = LanguageManager.applyLanguage(context.applicationContext)
    return localizedContext.getString(messageResId, *args.toTypedArray())
}
