package moe.ouom.neriplayer.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.LayoutRes
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.service.AudioPlayerService

internal const val ACTION_PLAYBACK_WIDGET_CONTROL =
    "moe.ouom.neriplayer.widget.action.PLAYBACK_CONTROL"
internal const val EXTRA_PLAYBACK_WIDGET_SERVICE_ACTION = "playback_widget_service_action"

abstract class NeriPlayerBaseWidgetProvider(
    @LayoutRes private val layoutRes: Int,
) : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PLAYBACK_WIDGET_CONTROL) {
            val serviceAction = intent.getStringExtra(EXTRA_PLAYBACK_WIDGET_SERVICE_ACTION)
                ?: return
            AudioPlayerService.dispatchPlaybackWidgetAction(context, serviceAction)
            return
        }
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            refreshWidgets(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, javaClass)),
                reason = "app_widget_package_replaced",
            )
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        refreshWidgets(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetIds = appWidgetIds,
            reason = "app_widget_update",
        )
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        refreshWidgets(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetIds = intArrayOf(appWidgetId),
            reason = "app_widget_resize",
            widgetOptions = newOptions,
        )
    }

    private fun refreshWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        reason: String,
        widgetOptions: android.os.Bundle? = null,
    ) {
        if (AudioPlayerService.refreshPlaybackWidgetsFromActiveService(reason)) {
            return
        }
        PlaybackWidgetUpdater.updateStoredWidgets(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetIds = appWidgetIds,
            layoutRes = layoutRes,
            widgetOptions = widgetOptions,
        )
    }
}

class NeriPlayerPlaybackWidgetProvider : NeriPlayerBaseWidgetProvider(
    R.layout.widget_playback_4x2,
)

class NeriPlayerCompactWidgetProvider : NeriPlayerBaseWidgetProvider(
    R.layout.widget_playback_2x2,
)
