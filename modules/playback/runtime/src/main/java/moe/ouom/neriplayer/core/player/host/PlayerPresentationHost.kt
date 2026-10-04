package moe.ouom.neriplayer.core.player.host

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import moe.ouom.neriplayer.core.player.presentation.widget.PlaybackWidgetState
import moe.ouom.neriplayer.data.model.system.BackgroundBehaviorAllowance

interface PlayerPresentationHost {
    fun mainActivityIntent(context: Context): Intent
    fun shouldEnterSafeMode(context: Context): Boolean
    fun shouldProcessUsbAttachedAction(action: String?, handlingEnabled: Boolean): Boolean
    fun backgroundBehaviorAllowance(context: Context): BackgroundBehaviorAllowance
    fun showFeedback(context: Context, message: String, forceToast: Boolean)
    fun hasInstalledWidgets(context: Context): Boolean
    fun publishWidget(context: Context, state: PlaybackWidgetState, artwork: Bitmap?)
    fun publishWidgetProgress(context: Context, state: PlaybackWidgetState)
    suspend fun loadArtwork(context: Context, source: String, sizePx: Int): Bitmap?
    suspend fun loadCoverAccentColor(context: Context, source: String): Int? = null
    fun peekCoverAccentColor(source: String): Int? = null
}

internal object PlayerFeedback {
    fun show(context: Context, message: String) =
        PlayerDependencies.presentation.showFeedback(context, message, forceToast = false)

    fun showToast(context: Context, message: String) =
        PlayerDependencies.presentation.showFeedback(context, message, forceToast = true)
}
