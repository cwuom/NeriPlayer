package moe.ouom.neriplayer.data.sync.dataset.disk

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.runtime.dataset.*
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class FileSyncPlaybackDatasetStore(private val directory: File) : SyncPlaybackDatasetStore {
    init {
        ensureDirectory()
        recoverAbandoned()
    }

    private fun ensureDirectory() {
        val path = directory.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(path)
        requirePlaybackDirectory(path)
    }

    override fun newSink(): SyncPlaybackSink = createSink(ordered = false)

    override fun newOrderedSink(): SyncPlaybackSink = createSink(ordered = true)

    suspend fun retainValidated(source: SyncPlaybackSource, checkActive: () -> Unit = {}): SyncPlaybackSource {
        require(source is FilePlaybackSource) { "Only sealed file playback sources can be retained" }
        val context = currentCoroutineContext()
        return source.retainValidated { context.ensureActive(); checkActive() }
    }

    private fun createSink(ordered: Boolean): SyncPlaybackSink {
        ensureDirectory()
        recoverAbandoned()
        val session = kotlin.io.path.createTempDirectory(directory.toPath(), "dataset-").toFile()
        return FilePlaybackSink(PlaybackDatasetLease.create(session), ordered)
    }

    private fun recoverAbandoned() {
        val path = directory.toPath()
        requirePlaybackDirectory(path)
        Files.newDirectoryStream(path, "dataset-*").use { sessions ->
            for (session in sessions) PlaybackDatasetLease.recover(session.toFile())
        }
    }
}

private class FilePlaybackSink(private val lease: PlaybackDatasetLease, ordered: Boolean) : SyncPlaybackSink {
    private val directory = lease.directory
    private val tracks = stagingWriter(directory, "tracks", trackCodec, dayFirst = false, ordered = ordered)
    private val buckets = stagingWriter(directory, "buckets", bucketCodec, dayFirst = true, ordered = ordered)
    private var sealed = false
    private var closed = false

    override suspend fun appendTracks(page: List<SyncTrackStat>) {
        checkWritable(page.size)
        for (record in page) tracks.append(record)
    }

    override suspend fun appendBuckets(page: List<SyncPlaybackStatBucket>) {
        checkWritable(page.size)
        for (record in page) buckets.append(record)
    }

    private fun checkWritable(size: Int) {
        check(!closed) { "Playback staging is already closed" }
        check(!sealed) { "Playback staging is already sealed" }
        require(size <= SYNC_PLAYBACK_PAGE_RECORDS) { "Playback page exceeds record budget" }
    }

    override suspend fun seal(): SyncPlaybackSource {
        checkWritable(0)
        currentCoroutineContext().ensureActive()
        val trackFile = tracks.finish()
        val bucketFile = buckets.finish()
        val identityFile = PlaybackFileSorter(directory, "identity-buckets", bucketCodec, false).use { identityBuckets ->
            PlaybackFileReader(bucketFile, bucketCodec).use { reader ->
                while (true) {
                    val record = reader.next() ?: break
                    identityBuckets.appendValidatedRecord(record)
                }
            }
            identityBuckets.finish()
        }
        currentCoroutineContext().ensureActive()
        sealed = true
        return FilePlaybackSource(lease, trackFile, bucketFile, identityFile)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (sealed) closeWriters()
        else Closeable { lease.closeAndDelete() }.use { closeWriters() }
    }

    private fun closeWriters() { tracks.use { buckets.close() } }
}

private fun <T> stagingWriter(directory: File, prefix: String, codec: PlaybackRecordCodec<T>,
    dayFirst: Boolean, ordered: Boolean): PlaybackStagingWriter<T> =
    if (ordered) PlaybackOrderedFile(File(directory, "$prefix-ordered.bin"), codec, dayFirst)
    else PlaybackFileSorter(directory, prefix, codec, dayFirst)

private class FilePlaybackSource(
    private val lease: PlaybackDatasetLease,
    private val tracks: PlaybackFile,
    private val buckets: PlaybackFile,
    private val identityBuckets: PlaybackFile
) : SyncPlaybackSource {
    private var closed = false

    override fun openTracks(): SyncPlaybackCursor<SyncTrackStat> = openCursor(tracks, trackCodec)

    override fun openBuckets(order: SyncPlaybackBucketOrder): SyncPlaybackCursor<SyncPlaybackStatBucket> {
        val file = if (order == SyncPlaybackBucketOrder.DAY_IDENTITY) buckets else identityBuckets
        return openCursor(file, bucketCodec)
    }

    fun retainValidated(checkActive: () -> Unit): FilePlaybackSource {
        val retained = synchronized(this) {
            check(!closed) { "Playback dataset is closed" }
            FilePlaybackSource(lease.retain(), tracks, buckets, identityBuckets)
        }
        try {
            for (file in listOf(tracks, buckets, identityBuckets)) validatePlaybackFile(file, checkActive)
            checkActive()
            return retained
        } catch (failure: Throwable) {
            try { retained.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun <T> openCursor(file: PlaybackFile, codec: PlaybackRecordCodec<T>): SyncPlaybackCursor<T> = synchronized(this) {
        check(!closed) { "Playback dataset is closed" }
        val cursorLease = lease.retain()
        try { PlaybackFileCursor(PlaybackFileReader(file, codec), cursorLease) }
        catch (failure: Throwable) {
            try { cursorLease.closeAndDelete() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun close() = synchronized(this) {
        if (!closed) {
            closed = true
            lease.closeAndDelete()
        }
    }
}

private fun validatePlaybackFile(file: PlaybackFile, checkActive: () -> Unit) {
    val path = file.file.toPath()
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("Playback cache file is unavailable")
    Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        if (channel.size() != file.bytes) throw IOException("Playback cache length mismatch")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(PLAYBACK_IO_BUFFER_BYTES)
        var bytes = 0L
        Channels.newInputStream(channel).use { input ->
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                bytes += count
            }
        }
        if (bytes != file.bytes || !MessageDigest.isEqual(digest.digest(), file.hash)) {
            throw IOException("Playback cache checksum mismatch")
        }
    }
}

private class PlaybackFileCursor<T>(private val reader: PlaybackFileReader<T>, private val lease: PlaybackDatasetLease) : SyncPlaybackCursor<T> {
    private var pending: PlaybackRecord<T>? = null
    private var closed = false
    override suspend fun nextPage(): List<T> {
        check(!closed) { "Playback cursor is closed" }
        val page = ArrayList<T>()
        var bytes = 0L
        while (page.size < SYNC_PLAYBACK_PAGE_RECORDS) {
            currentCoroutineContext().ensureActive()
            val record = pending ?: reader.next() ?: break
            pending = null
            if (page.isNotEmpty() && bytes + record.payload.size > SYNC_PLAYBACK_PAGE_BYTES) {
                pending = record
                break
            }
            page.add(record.value)
            bytes += record.payload.size
        }
        return page
    }
    override fun close() = synchronized(this) {
        if (!closed) {
            closed = true
            Closeable { lease.closeAndDelete() }.use { reader.close() }
        }
    }
}
