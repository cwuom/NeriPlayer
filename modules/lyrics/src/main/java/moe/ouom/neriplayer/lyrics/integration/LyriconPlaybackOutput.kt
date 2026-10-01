package moe.ouom.neriplayer.lyrics.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.lyricon.LyriconManager
import moe.ouom.neriplayer.core.player.lyrics.LyriconUpdateCoordinator
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference

class LyriconPlaybackOutput internal constructor(
    private val loader: LyriconLyricsLoader,
    private val stableKey: (SongItem) -> String,
    private val sameIdentity: (SongItem, SongItem?) -> Boolean,
    private val sink: LyriconOutputSink,
) {
    constructor(
        loader: LyriconLyricsLoader,
        stableKey: (SongItem) -> String,
        sameIdentity: (SongItem, SongItem?) -> Boolean,
    ) : this(loader, stableKey, sameIdentity, LyriconManagerOutputSink())

    private val coordinator = LyriconUpdateCoordinator()
    @Volatile
    private var currentSong: SongItem? = null
    @Volatile
    private var preferences = LyriconPreferences(enabled = false)
    @Volatile
    private var preferredSourceSongKey: String? = null
    @Volatile
    private var preferredSource: LyricSourcePreference? = null

    fun publishSong(song: SongItem?) {
        currentSong = song
    }

    fun syncSong(
        scope: CoroutineScope,
        song: SongItem?,
        preferences: LyriconPreferences,
        lyricOffsetOverrideMs: Long? = null,
    ) {
        this.preferences = preferences
        val request = coordinator.replace(
            createJob = { generation ->
                if (!preferences.enabled || song == null) null else {
                    scope.launch(start = CoroutineStart.LAZY) {
                        val job = currentCoroutineContext().job
                        loader.load(song, preferences.preferredSource) { lyrics, translatedLyrics, source ->
                            job.ensureActive()
                            coordinator.runIfCurrent(generation, job) {
                                publishResolvedSong(song, lyrics, translatedLyrics, source, lyricOffsetOverrideMs)
                            }
                        }
                    }
                }
            },
            onPublished = { publishPendingSong(song, preferences, lyricOffsetOverrideMs) },
        )
        request.job?.start()
    }

    private fun publishResolvedSong(
        requestedSong: SongItem,
        lyrics: List<LyricEntry>,
        translatedLyrics: List<LyricEntry>,
        source: LyricSourcePreference?,
        lyricOffsetOverrideMs: Long?,
    ) {
        val song = currentSong ?: return
        if (!sameIdentity(requestedSong, song)) return
        preferredSourceSongKey = stableKey(song)
        preferredSource = source
        sink.updateSong(song, lyrics, translatedLyrics, lyricOffsetOverrideMs ?: preferences.offsetFor(song, source))
    }

    private fun publishPendingSong(song: SongItem?, preferences: LyriconPreferences, lyricOffsetOverrideMs: Long?) {
        preferredSourceSongKey = null
        preferredSource = null
        when {
            !preferences.enabled -> sink.setPlaybackState(false)
            song == null -> {
                sink.setPlaybackState(false)
                sink.setLyricOffset(0L)
                sink.setPosition(0L)
            }
            else -> sink.updateSong(song, null, null, lyricOffsetOverrideMs ?: preferences.offsetFor(song, null))
        }
    }

    fun updateOffset(song: SongItem?, preferences: LyriconPreferences, positionMs: Long) {
        this.preferences = preferences
        if (!preferences.enabled) return
        val source = preferredSource.takeIf { song != null && preferredSourceSongKey == stableKey(song) }
        sink.setLyricOffset(song?.let { preferences.offsetFor(it, source) } ?: 0L)
        sink.setPosition(if (song == null) 0L else positionMs)
    }

    fun cancel() = coordinator.cancelActive()

    fun hasPendingUpdate(): Boolean = coordinator.hasPendingJob()
}

internal class LyriconManagerOutputSink(private val manager: LyriconManager = LyriconManager) : LyriconOutputSink {
    override fun updateSong(song: SongItem, lyrics: List<LyricEntry>?, translatedLyrics: List<LyricEntry>?, lyricOffsetMs: Long) =
        manager.updateSong(song, lyrics, translatedLyrics, lyricOffsetMs)

    override fun setPlaybackState(playing: Boolean) = manager.setPlaybackState(playing)

    override fun setLyricOffset(offsetMs: Long) = manager.setLyricOffset(offsetMs)

    override fun setPosition(positionMs: Long) = manager.setPosition(positionMs)
}
