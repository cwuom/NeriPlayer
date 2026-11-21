@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan

import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.media3.datasource.DefaultDataSource
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.lan.relay.LanRelayOrigin
import moe.ouom.neriplayer.core.player.url.currentListenTogetherShareableStreamUrls
import moe.ouom.neriplayer.core.player.url.listenTogetherQualityKeyFromStreamUrl
import moe.ouom.neriplayer.core.player.url.resolveShareableListenTogetherStreamUrls
import moe.ouom.neriplayer.core.player.url.stripListenTogetherStreamQualityMetadata
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.ltw.mapping.trustedListenTogetherStreamUrls
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.lanltw.media.*
import moe.ouom.neriplayer.lanltw.playback.LanPreparedSource

internal class LanHostStreamPublisher {
    var server: LanMediaServer? = null
    private data class Published(val server: LanMediaServer, val source: LanPreparedSource,
        val origin: LanRelayOrigin, val covers: ArrayDeque<LanFileResource> = ArrayDeque())
    private var active: Published? = null
    private var initial: Pair<String, List<String>>? = null

    fun captureCurrentSource() {
        val track = LanPlaybackBinding.track(PlayerManager.currentSongFlow.value) ?: return
        initial = track.stableKey to trustedListenTogetherStreamUrls(track.channelId,
            PlayerManager.currentListenTogetherShareableStreamUrls())
    }

    suspend fun offer(track: ListenTogetherTrack, song: SongItem, refresh: Boolean): LanPreparedSource {
        val host = checkNotNull(server) { "LOCAL_TRANSFER" }
        active?.takeIf { !refresh && it.source.track.stableKey == track.stableKey }?.let { return it.source }
        val local = song.localMediaUri()
        // 在后台打开音源时如果房间已退出，仍由本次请求关闭已打开的连接
        var opened: LanRelayOrigin? = null
        try {
            val quality = if (local != null) {
                withContext(Dispatchers.IO) {
                    opened = LanRelayOrigin.open(DefaultDataSource.Factory(PlayerManager.application),
                        local, PlayerManager.application.contentResolver.getType(local))
                }
                null
            } else if (track.channelId == ServerSongRef.CHANNEL) {
                val ref = checkNotNull(ServerSongRef.from(song)) { "SOURCE" }
                val result = PlayerDependencies.repositories.subsonicRepository?.playback(song, refresh) as? SongUrlResult.Success
                    ?: error("SOURCE")
                check(result.url == ref.resourceUrl()) { "SOURCE" }
                val factory = checkNotNull(PlayerManager.conditionalHttpFactory) { "SOURCE" }
                opened = LanRelayOrigin.openRemote(factory::forLanRelay,
                    PlayerDependencies.repositories.sharedOkHttpClient, result.url.toUri(), result.mimeType)
                result.audioInfo?.qualityKey
            } else {
                val url = selectCandidate(track, song) { opened = it }
                listenTogetherQualityKeyFromStreamUrl(url, lanAudioSource(track.channelId))
            }
            currentCoroutineContext().ensureActive()
            check(host === server) { "LOCAL_TRANSFER" }
            val origin = checkNotNull(opened)
            val resource = LanStreamResource(UUID.randomUUID().toString(), origin.mime, origin.byteLength, qualityKey = quality)
            val clean = track.copy(streamUrl = null, streamUrls = emptyList(), mediaUri = null, coverUrl = null)
            val source = LanPreparedSource(clean, resource)
            host.publishStream(resource.mediaId, resource.mimeType, resource.byteLength, origin.window::open)
            val previous = active
            active = Published(host, source, origin)
            opened = null
            previous?.let(::retire)
            if (local != null) LanLocalIdentity.bind(song, clean)
            return source
        } finally { opened?.close() }
    }

    private suspend fun selectCandidate(track: ListenTogetherTrack, song: SongItem, onOpen: (LanRelayOrigin) -> Unit): String {
        val attempted = mutableSetOf<String>()
        val factory = checkNotNull(PlayerManager.conditionalHttpFactory) { "SOURCE" }
        suspend fun tryUrls(urls: List<String>): String? {
            for (url in urls) {
                val origin = stripListenTogetherStreamQualityMetadata(url)
                if (!attempted.add(origin)) continue
                try {
                    onOpen(LanRelayOrigin.openRemote(factory::forLanRelay,
                        PlayerDependencies.repositories.sharedOkHttpClient, origin.toUri()))
                    return url
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { currentCoroutineContext().ensureActive() }
            }
            return null
        }
        val captured = initial?.takeIf { it.first == track.stableKey }?.second.orEmpty()
        initial = null
        tryUrls(captured)?.let { return it }
        val resolved = PlayerManager.resolveShareableListenTogetherStreamUrls(song, progressiveRelay = true, firstQualityOnly = true)
        check(!resolved.isPreviewOnly) { "FULL_AUDIO_REQUIRED" }
        tryUrls(resolved.streamUrls)?.let { return it }
        if (resolved.streamUrls.isEmpty()) error("SOURCE")
        val alternatives = PlayerManager.resolveShareableListenTogetherStreamUrls(song, progressiveRelay = true)
        return tryUrls(alternatives.streamUrls) ?: error("SOURCE")
    }

    fun presentationUpdates(track: ListenTogetherTrack, mediaId: String, song: SongItem): Flow<LanSharedPresentation> = flow {
        val shared = active?.takeIf { it.source.streamResource.mediaId == mediaId &&
            it.source.track.stableKey == track.stableKey } ?: return@flow
        val initial = LanSharedPresentation(song.displayName(), song.displayArtist(),
            song.displayAlbum(PlayerManager.application), lyricOffsetMs = song.userLyricOffsetMs)
        emitAll(lanPresentationUpdates(initial, {
            try { resolveLanSharedLyrics(song) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { initial }
        }, coverUpdates(shared, song)))
    }

    private fun coverUpdates(shared: Published, song: SongItem): Flow<LanFileResource> = channelFlow {
        val trackKey = shared.source.track.stableKey
        val fallback = launch {
            if (LanPlaybackArtwork.ready.value?.trackKey == trackKey) return@launch
            val bitmap = loadFallbackCover(song) ?: return@launch
            publishCover(shared, bitmap) { LanPlaybackArtwork.ready.value?.trackKey != trackKey }?.let { send(it) }
        }
        LanPlaybackArtwork.ready.collect { artwork ->
            if (artwork == null || artwork.trackKey != trackKey) return@collect
            fallback.cancel()
            publishCover(shared, artwork.bitmap) { LanPlaybackArtwork.ready.value === artwork }?.let { send(it) }
        }
    }

    private suspend fun loadFallbackCover(song: SongItem): Bitmap? {
        val app = PlayerManager.application
        return try {
            val ref = withContext(Dispatchers.IO) { song.displayCoverUrl(app) } ?: return null
            PlayerDependencies.presentation.loadArtwork(app, ref, 512)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private suspend fun publishCover(shared: Published, bitmap: Bitmap, isCurrent: () -> Boolean): LanFileResource? {
        return try {
            val bytes = withContext(Dispatchers.Default) { ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            } }
            if (bytes.size > LanPresentationCodec.MAX_BYTES) return null
            val (source, hash) = LanReadableMedia.fingerprint { bytes.inputStream() }
            currentCoroutineContext().ensureActive()
            if (active !== shared || !isCurrent()) return null
            shared.covers.lastOrNull()?.takeIf { it.sha256 == hash }?.let { return it }
            val resource = LanFileResource(UUID.randomUUID().toString(), hash, source.byteLength, "image/png")
            shared.server.publishBorrowed(resource.mediaId, source, resource.mimeType)
            shared.covers.addLast(resource)
            // 上一份展示仍可能在下载，暂时保留它引用的封面
            if (shared.covers.size > 2) shared.server.retire(shared.covers.removeFirst().mediaId)
            resource
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private fun retire(value: Published) {
        value.server.retire(value.source.streamResource.mediaId)
        value.covers.forEach { value.server.retire(it.mediaId) }
        value.origin.close()
    }

    fun clear() {
        active?.let(::retire)
        active = null
        initial = null
        server = null
    }
}
