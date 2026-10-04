package moe.ouom.neriplayer.core.di.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.activity.shouldProcessUsbDeviceAttachedAction
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.presentation.widget.PlaybackWidgetState
import moe.ouom.neriplayer.core.startup.safemode.SafeModeManager
import moe.ouom.neriplayer.data.traffic.isOfflineModeNow
import moe.ouom.neriplayer.data.model.system.BackgroundBehaviorAllowance
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.util.media.copyBitmapForRetainedDisplay
import moe.ouom.neriplayer.util.media.CoverArtColorCache
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.widget.PlaybackWidgetUpdater
import moe.ouom.neriplayer.util.platform.readBackgroundBehaviorAllowance

internal object AppPlayerPresentation : PlayerPresentationHost {
    override fun mainActivityIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
    override fun shouldEnterSafeMode(context: Context): Boolean = SafeModeManager.shouldEnterSafeMode(context)
    override fun shouldProcessUsbAttachedAction(action: String?, handlingEnabled: Boolean): Boolean =
        shouldProcessUsbDeviceAttachedAction(action, handlingEnabled)
    override fun backgroundBehaviorAllowance(context: Context): BackgroundBehaviorAllowance =
        context.readBackgroundBehaviorAllowance()

    override fun showFeedback(context: Context, message: String, forceToast: Boolean) {
        if (forceToast) AppFeedback.showToast(context = context, message = message)
        else AppFeedback.show(context = context, message = message)
    }

    override fun hasInstalledWidgets(context: Context): Boolean = PlaybackWidgetUpdater.hasInstalledWidgets(context)
    override fun publishWidget(context: Context, state: PlaybackWidgetState, artwork: Bitmap?) {
        PlaybackWidgetUpdater.updateFromPlaybackService(context, state, artwork)
    }

    override fun publishWidgetProgress(context: Context, state: PlaybackWidgetState) {
        PlaybackWidgetUpdater.updatePlaybackProgressFromPlaybackService(context, state)
    }

    override suspend fun loadArtwork(context: Context, source: String, sizePx: Int): Bitmap? {
        val request = offlineCachedImageRequest(
            context = context,
            data = source,
            sizePx = sizePx,
            allowHardware = false,
            offlineMode = context.isOfflineModeNow(),
        )
        val drawable = Coil.imageLoader(context).execute(request).drawable ?: return null
        return copyDrawable(drawable)
    }

    private fun copyDrawable(drawable: Drawable): Bitmap =
        checkNotNull(copyBitmapForRetainedDisplay(drawable.toBitmap())) {
            "Coil returned a recycled artwork bitmap"
        }

    override suspend fun loadCoverAccentColor(context: Context, source: String): Int? =
        CoverArtColorCache.getOrLoad(context, source)?.baseColorArgb

    override fun peekCoverAccentColor(source: String): Int? =
        CoverArtColorCache.peek(source)?.baseColorArgb
}
