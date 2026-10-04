// Compat 浏览接口提供搜索，并复用播放器现有的 framework 会话
@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.core.player.service.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.service.car.library.CarLibraryItem
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaIds
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaLibrary

class CarMediaBrowserService : MediaBrowserServiceCompat() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playbackBinding: CarPlaybackBinder? = null
    private var bindingRequested = false
    private val browsedParents = linkedSetOf<String>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val playback = binder as? CarPlaybackBinder ?: return
            val token = playback.sessionToken ?: return
            if (playbackBinding === playback) return
            if (sessionToken != null && sessionToken?.token != token) {
                NPLogger.w("NERI-CarBrowser", "Ignoring a replacement playback session while browser is bound")
                return
            }
            playbackBinding = playback
            if (sessionToken == null) sessionToken = checkNotNull(MediaSessionCompat.Token.fromToken(token))
            observeLibrary(playback)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackBinding = null
            stopSelf()
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (!CarControllerTrust.isTrusted(this, clientPackageName, clientUid)) return null
        if (PlayerDependencies.presentation.shouldEnterSafeMode(this)) return null
        if (!bindPlayback()) return null
        val flags = rootHintInt(rootHints, MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_SUPPORTED_FLAGS)
        return BrowserRoot(carBrowserRootId(rootHints, flags), Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onLoadChildren(parentId: String, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        val hints = browserRootHints
        val flags = rootHintInt(hints, MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_SUPPORTED_FLAGS)
        val rootId = carBrowserRootId(hints, flags)
        val categoryId = carRequestedBrowserRootId(hints)
        val wrapCategory = parentId == rootId && rootId == CarMediaIds.ROOT && categoryId != rootId
        val limit = rootHintInt(hints, MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT)
        load(result) { library ->
            if (library.getItem(parentId)?.isBrowsable == true) rememberParent(parentId)
            val children = if (wrapCategory) listOfNotNull(library.getItem(categoryId))
            else carBrowserChildren(library, parentId, rootId, limit, flags)
            children.map(::mediaItem)
        }
    }

    private fun rootHintInt(hints: Bundle?, key: String): Int? =
        hints?.takeIf { it.containsKey(key) }?.getInt(key)

    override fun onLoadItem(itemId: String, result: Result<MediaBrowserCompat.MediaItem>) {
        load(result) { it.getItem(itemId)?.let(::mediaItem) }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        load(result) { it.search(query).map(::mediaItem) }
    }

    private fun <T> load(result: Result<T>, query: (CarMediaLibrary) -> T?) {
        result.detach()
        scope.launch {
            val value = try {
                readyLibrary()?.let(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                NPLogger.w("NERI-CarBrowser", "Car library request failed", error)
                null
            }
            result.sendResult(value)
        }
    }

    private fun bindPlayback(): Boolean {
        if (bindingRequested) return true
        val intent = Intent(this, AudioPlayerService::class.java).setAction(AudioPlayerService.ACTION_BIND_CAR)
        bindingRequested = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        return bindingRequested
    }

    private suspend fun readyLibrary(): CarMediaLibrary? = try {
        withTimeoutOrNull(10_000L) {
            val binding = playbackBinding ?: return@withTimeoutOrNull null
            binding.runtimeReady.first { it }
            PlayerManager.localPlaylistsReadyFlow.first { it }
            androidCarMediaLibrary(this@CarMediaBrowserService)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        NPLogger.w("NERI-CarBrowser", "Car library unavailable", error)
        null
    }

    private fun mediaItem(item: CarLibraryItem): MediaBrowserCompat.MediaItem {
        val androidItem = item.toAndroidMediaItem(this)
        return MediaBrowserCompat.MediaItem(
            checkNotNull(MediaDescriptionCompat.fromMediaDescription(androidItem.description)),
            if (item.isPlayable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else MediaBrowserCompat.MediaItem.FLAG_BROWSABLE,
        )
    }

    private fun rememberParent(parentId: String) {
        if (browsedParents.size >= 256) browsedParents.remove(browsedParents.first())
        browsedParents.add(parentId)
    }

    private fun observeLibrary(binding: CarPlaybackBinder) {
        scope.launch {
            binding.runtimeReady.first { it }
            PlayerManager.currentQueueFlow.collect { notifyBrowsedChildren() }
        }
        scope.launch {
            binding.runtimeReady.first { it }
            PlayerManager.playlistsFlow.collect { notifyBrowsedChildren() }
        }
        scope.launch {
            binding.runtimeReady.first { it }
            PlayerDependencies.repositories.playHistoryRepo.historyFlow.collect { notifyBrowsedChildren() }
        }
        scope.launch {
            binding.runtimeReady.first { it }
            PlayerDependencies.downloads.downloadPresenceVersion.collect { notifyBrowsedChildren() }
        }
    }

    private fun notifyBrowsedChildren() {
        val roots = listOf(CarMediaIds.ROOT, CarMediaIds.QUEUE, CarMediaIds.PLAYLISTS, CarMediaIds.HISTORY, CarMediaIds.OFFLINE)
        (roots + browsedParents.toList()).distinct().forEach(::notifyChildrenChanged)
    }

    override fun onDestroy() {
        scope.cancel()
        if (bindingRequested) {
            runCatching { unbindService(connection) }
                .onFailure { NPLogger.w("NERI-CarBrowser", "Playback unbind failed", it) }
        }
        playbackBinding = null
        bindingRequested = false
        super.onDestroy()
    }
}
