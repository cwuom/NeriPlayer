package moe.ouom.neriplayer.core.player.service.car

import android.media.session.MediaSession
import android.os.Bundle
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaLibrary
import moe.ouom.neriplayer.core.player.service.car.library.CarPlaybackSelection

internal const val CAR_ACTION_TOGGLE_SHUFFLE = "moe.ouom.neriplayer.car.TOGGLE_SHUFFLE"
internal const val CAR_ACTION_CYCLE_REPEAT = "moe.ouom.neriplayer.car.CYCLE_REPEAT"

internal interface CarMediaSessionControlPort {
    fun runWhenReady(source: String, action: () -> Unit)
    fun runWhenLibraryReady(source: String, action: () -> Unit)
    fun library(): CarMediaLibrary
    fun resume()
    fun playSelection(selection: CarPlaybackSelection)
    fun playQueueItem(id: Long)
    fun pause(source: String, stopService: Boolean)
    fun next()
    fun previous()
    fun seek(positionMs: Long)
    fun customAction(action: String, extras: Bundle?)
}

internal class CarMediaSessionCallback(
    private val port: CarMediaSessionControlPort,
) : MediaSession.Callback() {
    private sealed interface PreparedRequest {
        data class MediaId(val id: String) : PreparedRequest
        data class Search(val query: String) : PreparedRequest
    }

    private var preparedRequest: PreparedRequest? = null
    private var requestGeneration = 0L

    fun cancelPendingPlaybackRequests() {
        requestGeneration += 1L
        preparedRequest = null
    }

    override fun onPlay() {
        val generation = ++requestGeneration
        val request = preparedRequest
        preparedRequest = null
        port.runWhenReady("media_session_play") {
            if (generation != requestGeneration) return@runWhenReady
            if (request == null) port.resume()
            else port.runWhenLibraryReady("media_session_prepared_play") {
                if (generation == requestGeneration) playPrepared(request)
            }
        }
    }

    override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
        preparedRequest = null
        dispatchLookup("play_id") { port.library().resolveId(mediaId)?.let(port::playSelection) }
    }

    override fun onPlayFromSearch(query: String, extras: Bundle?) {
        preparedRequest = null
        if (query.isBlank()) {
            dispatch("play_search") { port.resume() }
            return
        }
        dispatchLookup("play_search") {
            port.library().resolveSearch(query)?.let(port::playSelection)
        }
    }

    override fun onPrepare() {
        cancelPendingPlaybackRequests()
    }

    override fun onPrepareFromMediaId(mediaId: String, extras: Bundle?) {
        requestGeneration += 1L
        preparedRequest = PreparedRequest.MediaId(mediaId)
    }

    override fun onPrepareFromSearch(query: String, extras: Bundle?) {
        requestGeneration += 1L
        preparedRequest = query.takeUnless(String::isBlank)?.let(PreparedRequest::Search)
    }

    override fun onSkipToQueueItem(id: Long) {
        preparedRequest = null
        dispatch("queue_item") { port.playQueueItem(id) }
    }

    override fun onPause() = dispatch("pause") { port.pause("media_session_pause", false) }

    override fun onStop() = dispatch("stop") { port.pause("media_session_stop", true) }

    override fun onSkipToNext() {
        preparedRequest = null
        dispatch("next") { port.next() }
    }

    override fun onSkipToPrevious() {
        preparedRequest = null
        dispatch("previous") { port.previous() }
    }

    override fun onSeekTo(pos: Long) = dispatch("seek") { port.seek(pos) }

    override fun onCustomAction(action: String, extras: Bundle?) = port.runWhenReady("media_session_custom_action") {
        port.customAction(action, extras)
    }

    private fun playPrepared(request: PreparedRequest) {
        val library = port.library()
        val selection = when (request) {
            is PreparedRequest.MediaId -> library.resolveId(request.id)
            is PreparedRequest.Search -> library.resolveSearch(request.query)
        }
        selection?.let(port::playSelection)
    }

    private fun dispatch(source: String, action: () -> Unit) {
        requestGeneration += 1L
        port.runWhenReady("media_session_$source", action)
    }

    private fun dispatchLookup(source: String, action: () -> Unit) {
        val generation = ++requestGeneration
        port.runWhenLibraryReady("media_session_$source") {
            if (generation == requestGeneration) action()
        }
    }
}
