package moe.ouom.neriplayer.core.player.lan

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.*
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.data.local.media.*
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.lanltw.media.*
import moe.ouom.neriplayer.lanltw.playback.LanPreparedSource
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class LanLocalPlayback {
    private val directory get() = File(PlayerManager.application.cacheDir, "lan-local")
    private val loader = LanAudioFileLoader()
    private val http = OkHttpClient()
    private val downloader = LanMediaDownload(http)
    var server: LanMediaServer? = null
    private var endpoint: Triple<String, Int, String>? = null
    private var hosted: LanPreparedSource? = null
    private var hostedFile: File? = null
    private var hostedSong: SongItem? = null
    private var hostedMedia: LanHostedMedia? = null
    private val published = mutableListOf<String>()
    private val received = mutableListOf<File>()
    var incoming: LanLocalMedia? = null
    var progress: (Long, Long) -> Unit = { _, _ -> }
    fun clearReceived() {
        received.forEach(File::delete); received.clear()
        LanSharedPresentationStore.clear()
    }
    fun clearAbandoned() {
        directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[a-f0-9-]{36}\\.(media|part)")) }?.forEach(File::delete)
    }
    fun endpoint(url: String, port: Int, token: String) { endpoint = Triple(url, port, token) }
    fun clearHosted() {
        published.toList().forEach { server?.retire(it) }; published.clear()
        hosted = null; hostedFile = null; hostedSong = null; hostedMedia = null
    }
    fun reset() {
        clearHosted(); clearReceived()
        incoming = null; endpoint = null; server = null
    }
    suspend fun offer(song: SongItem, sampleRate: Int, refresh: Boolean): LanPreparedSource {
        if (!refresh && hostedSong == song && hostedFile?.isFile == true) return checkNotNull(hosted)
        val service = checkNotNull(server) { "SOURCE" }
        val uri = song.localMediaUri() ?: error("SOURCE")
        val newResources = mutableListOf<String>()
        val temporary = mutableListOf<File>()
        try {
            val snapshot = LanFileSnapshot.create(directory) {
                if (uri.scheme == "file") File(checkNotNull(uri.path)).inputStream()
                else PlayerManager.application.contentResolver.openInputStream(uri) ?: error("LOCAL_READ")
            }
            temporary += snapshot.file
            val duration = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try { retriever.setDataSource(snapshot.file.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: song.durationMs
                } finally { retriever.release() }
            }
            val format = withContext(Dispatchers.IO) { loader.validate(snapshot.file, sampleRate, duration) }
            val enriched = withContext(Dispatchers.IO) {
                moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager.hydrateLocalSongMetadata(PlayerManager.application, song)
            }
            val metadata = resolveLanSharedLyrics(enriched)
            val cover = prepareCover(enriched, newResources, temporary)
            val presentation = metadata.copy(cover = cover?.first)
            val presentationResource = publishBytes(LanPresentationCodec.encode(presentation), "application/json", newResources, temporary)
            val mediaId = UUID.randomUUID().toString()
            service.publish(mediaId, snapshot.file, format.mime ?: "application/octet-stream")
            newResources += mediaId
            val media = LanLocalMedia(mediaId, snapshot.sha256, snapshot.byteLength, duration,
                format.mime ?: error("AUDIO_FORMAT"), presentationResource.first)
            val track = ListenTogetherTrack(media.stableKey, "local", media.sha256, name = metadata.name,
                artist = metadata.artist, album = metadata.album, durationMs = duration)
            currentCoroutineContext().ensureActive()
            published.toList().forEach(service::retire)
            published.clear(); published += newResources
            hosted = LanPreparedSource(track, localMedia = media)
            hostedFile = snapshot.file; hostedSong = song
            hostedMedia = LanHostedMedia(media, LanAudioFile(snapshot.file, format), presentation, cover?.second)
            LanLocalIdentity.bind(song, track)
            return checkNotNull(hosted)
        } catch (error: Throwable) {
            newResources.forEach(service::retire); temporary.forEach(File::delete)
            throw error
        }
    }
    private suspend fun prepareCover(song: SongItem, ids: MutableList<String>, files: MutableList<File>): Pair<LanFileResource, File>? {
        val reference = withContext(Dispatchers.IO) { song.displayCoverUrl(PlayerManager.application) } ?: return null
        val bitmap = PlayerDependencies.presentation.loadArtwork(PlayerManager.application, reference, 1024) ?: error("LOCAL_COVER")
        val bytes = withContext(Dispatchers.IO) {
            ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        }
        check(bytes.size <= LanPresentationCodec.MAX_BYTES) { "METADATA_LIMIT" }
        return publishBytes(bytes, "image/png", ids, files)
    }
    private suspend fun publishBytes(bytes: ByteArray, mime: String, ids: MutableList<String>, files: MutableList<File>): Pair<LanFileResource, File> {
        val snapshot = LanFileSnapshot.create(directory) { bytes.inputStream() }
        files += snapshot.file
        val id = UUID.randomUUID().toString()
        checkNotNull(server).publish(id, snapshot.file, mime); ids += id
        return LanFileResource(id, snapshot.sha256, snapshot.byteLength, mime) to snapshot.file
    }
    suspend fun receive(track: ListenTogetherTrack, sampleRate: Int): Pair<LanAudioFile, SongItem> {
        val media = checkNotNull(incoming) { "SOURCE" }
        check(media.valid() && media.stableKey == track.stableKey) { "AUDIO_CONTENT" }
        if (server != null) {
            val prepared = checkNotNull(hostedMedia) { "LOCAL_READ" }
            val result = prepared.read(media, track)
            LanSharedPresentationStore.publish(track.stableKey, prepared.presentation)
            return result
        }
        val connection = checkNotNull(endpoint) { "LOCAL_TRANSFER" }
        val newFiles = mutableListOf<File>()
        try {
            var lastPercent = -1L
            progress(0, media.byteLength)
            val file = downloader.fetch(connection.first.toHttpUrl(), connection.second, connection.third, media, directory) { count, total ->
                val percent = count * 100 / total
                if (percent != lastPercent) { lastPercent = percent; progress(count, total) }
            }
            newFiles += file
            val descriptor = checkNotNull(media.presentation) { "SOURCE" }
            val metadataFile = downloader.fetchResource(connection.first.toHttpUrl(), connection.second, connection.third,
                descriptor, directory, LanPresentationCodec.MAX_BYTES)
            newFiles += metadataFile
            val metadata = withContext(Dispatchers.IO) { LanPresentationCodec.decode(metadataFile.readBytes()) }
            val cover = metadata.cover?.let { resource ->
                downloader.fetchResource(connection.first.toHttpUrl(), connection.second, connection.third,
                    resource, directory, LanPresentationCodec.MAX_BYTES).also { newFiles += it }
            }
            val format = withContext(Dispatchers.IO) { loader.validate(file, sampleRate, media.durationMs) }
            currentCoroutineContext().ensureActive()
            received.forEach(File::delete); received.clear(); received += newFiles
            LanSharedPresentationStore.publish(track.stableKey, metadata)
            val song = lanLocalSessionSong(track, media, metadata, file, cover)
            return LanAudioFile(file, format) to song
        } catch (error: Throwable) { newFiles.forEach(File::delete); throw error }
    }
}
