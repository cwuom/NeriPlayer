package moe.ouom.neriplayer.ui.screen.playlist.curation

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.LocalSongAssetEmbedding
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.curation.CommittedEmbeddedCover
import moe.ouom.neriplayer.data.local.media.curation.EmbeddedCoverPreparer
import moe.ouom.neriplayer.data.local.media.curation.EmbeddedCoverSizePreset
import moe.ouom.neriplayer.data.local.media.curation.FolderArtworkLocator
import moe.ouom.neriplayer.data.local.media.curation.LocalEmbeddedAssetInspector
import moe.ouom.neriplayer.data.local.media.curation.LocalEmbeddedAssetState
import moe.ouom.neriplayer.data.local.media.curation.PreparedEmbeddedCover
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import moe.ouom.neriplayer.ui.feedback.AppFeedback

internal object LocalCurationRuntime {
    val coordinator: LocalCurationCoordinator by lazy {
        LocalCurationCoordinator(
            ports = AppLocalCurationPorts(AppContainer.applicationContext),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
    }
}

internal class AppLocalCurationPorts(private val context: Context) : LocalCurationPorts {

    override fun songKey(song: SongItem): String = song.stableKey()

    override fun currentSongKey(): String? = PlayerManager.currentSongFlow.value?.stableKey()

    override suspend fun preferWordTimedLyrics(): Boolean =
        AppContainer.settingsRepo.preferWordTimedLyricsFlow.first()

    override suspend fun inspect(song: SongItem): LocalEmbeddedAssetState? =
        LocalEmbeddedAssetInspector.inspect(context, song)

    override suspend fun currentLyrics(song: SongItem): LocalCurrentLyrics? = withContext(Dispatchers.IO) {
        val current = LocalMediaSupport.inspectLyricsFast(
            context = context,
            song = song,
            includeStoredFallback = true,
            includeEmbeddedFallback = false
        )
        current.lyric?.takeIf(String::isNotBlank)?.let { lyric ->
            LocalCurrentLyrics(
                lyrics = lyric,
                translatedLyrics = current.translatedLyric?.takeIf(String::isNotBlank),
                romanizedLyrics = current.romanizedLyric?.takeIf(String::isNotBlank)
            )
        }
    }

    override suspend fun folderCover(song: SongItem): String? = FolderArtworkLocator.find(context, song)

    override suspend fun matchLyrics(request: EditableLyricMatchRequest): List<RankedEditableLyricMatch> =
        withContext(Dispatchers.IO) { AppContainer.editableLyricsMatcher.matchLyrics(request) }

    override suspend fun prepareCover(
        reference: String,
        preset: EmbeddedCoverSizePreset
    ): PreparedEmbeddedCover? = EmbeddedCoverPreparer.prepare(context, reference, preset)

    override suspend fun commitCover(prepared: PreparedEmbeddedCover): CommittedEmbeddedCover? =
        EmbeddedCoverPreparer.commit(context, prepared)

    /** 写入失败也可能已经发布了新封面（例如补写标签失败），仍被歌曲引用的文件不能删 */
    override suspend fun discardCovers(references: Collection<String>) {
        val inUse = buildSet {
            val songs = LocalPlaylistRepository.getInstance(context).playlists.value.asSequence()
                .flatMap { it.songs.asSequence() } +
                PlayerManager.currentQueueFlow.value.asSequence() +
                listOfNotNull(PlayerManager.currentSongFlow.value)
            songs.forEach { song ->
                song.customCoverUrl?.let(::add)
                song.originalCoverUrl?.let(::add)
            }
        }
        EmbeddedCoverPreparer.discard(context, references.filterNot(inUse::contains))
    }

    /** 歌词与封面在一次文件事务里写入；文件写入不占用播放器的全局元数据锁，可与其他歌曲并行 */
    override suspend fun apply(
        song: SongItem,
        lyric: LocalCurationLyricProposal?,
        coverReference: String?
    ): LocalCurationFailure? {
        val outcome = runCatchingNonCancellation {
            PlayerManager.embedLocalSongAssets(song, localSongAssetEmbedding(lyric, coverReference))
        }.getOrElse { error ->
            NPLogger.e(TAG, "local curation write threw: ${error.javaClass.simpleName}: ${error.message}", error)
            LocalMediaMetadataWriteOutcome.FAILED
        }
        return localCurationFailureFor(outcome, lyric != null, coverReference != null)
    }

    override suspend fun resetSessionCaches() = withContext(Dispatchers.IO) {
        EmbeddedCoverPreparer.clearStaging(context)
        FolderArtworkLocator.clearCache()
    }

    private val preferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    override fun loadOptions(): LocalCurationOptions? {
        val stored = preferences.getString(KEY_LYRIC_MODE, null) ?: return null
        val defaults = LocalCurationOptions()
        return LocalCurationOptions(
            lyricMode = enumValueOrDefault(stored, defaults.lyricMode),
            coverMode = enumValueOrDefault(preferences.getString(KEY_COVER_MODE, null), defaults.coverMode),
            coverSize = enumValueOrDefault(preferences.getString(KEY_COVER_SIZE, null), defaults.coverSize),
            useFolderCover = preferences.getBoolean(KEY_USE_FOLDER_COVER, defaults.useFolderCover),
            useOnlineCover = preferences.getBoolean(KEY_USE_ONLINE_COVER, defaults.useOnlineCover),
            searchParallelism = clampLocalCurationParallelism(
                preferences.getInt(KEY_SEARCH_PARALLELISM, defaults.searchParallelism)
            ),
            writeParallelism = clampLocalCurationParallelism(
                preferences.getInt(KEY_WRITE_PARALLELISM, defaults.writeParallelism)
            )
        )
    }

    override fun saveOptions(options: LocalCurationOptions) {
        preferences.edit {
            putString(KEY_LYRIC_MODE, options.lyricMode.name)
            putString(KEY_COVER_MODE, options.coverMode.name)
            putString(KEY_COVER_SIZE, options.coverSize.name)
            putBoolean(KEY_USE_FOLDER_COVER, options.useFolderCover)
            putBoolean(KEY_USE_ONLINE_COVER, options.useOnlineCover)
            putInt(KEY_SEARCH_PARALLELISM, options.searchParallelism)
            putInt(KEY_WRITE_PARALLELISM, options.writeParallelism)
        }
    }

    override fun notifyFinished(applied: Int, failed: Int) {
        if (applied == 0 && failed == 0) return
        AppFeedback.show(
            context = context,
            message = context.getString(CoreCommonR.string.local_curation_notify_finished, applied, failed)
        )
    }

    private companion object {
        const val TAG = "LocalCuration"
        const val PREFERENCES_NAME = "local_curation"
        const val KEY_LYRIC_MODE = "lyric_mode"
        const val KEY_COVER_MODE = "cover_mode"
        const val KEY_COVER_SIZE = "cover_size"
        const val KEY_USE_FOLDER_COVER = "use_folder_cover"
        const val KEY_USE_ONLINE_COVER = "use_online_cover"
        const val KEY_SEARCH_PARALLELISM = "search_parallelism"
        const val KEY_WRITE_PARALLELISM = "write_parallelism"
    }
}

internal fun localSongAssetEmbedding(
    lyric: LocalCurationLyricProposal?,
    coverReference: String?
): LocalSongAssetEmbedding = LocalSongAssetEmbedding(
    lyrics = lyric?.lyrics,
    // 写歌词时空串表示清除，避免新歌词沿用旧翻译或旧音译
    translatedLyrics = lyric?.let { it.translatedLyrics.orEmpty() },
    romanizedLyrics = lyric?.let { it.romanizedLyrics.orEmpty() },
    userEditedLyrics = lyric?.origin == LocalCurationLyricOrigin.ONLINE,
    coverReference = coverReference,
    replacesLyricProvenance = lyric?.origin == LocalCurationLyricOrigin.ONLINE,
    matchedLyricSource = lyric?.takeIf { it.origin == LocalCurationLyricOrigin.ONLINE }
        ?.source?.toMusicPlatformOrNull(),
    matchedSongId = lyric?.takeIf { it.origin == LocalCurationLyricOrigin.ONLINE }?.candidateId
)

/** 只有网易云与 QQ 音乐的曲目能记录为匹配来源，其余来源会清空歌曲原有的匹配信息 */
internal fun EditableLyricMatchSource.toMusicPlatformOrNull(): MusicPlatform? = when (this) {
    EditableLyricMatchSource.CLOUD_MUSIC -> MusicPlatform.CLOUD_MUSIC
    EditableLyricMatchSource.QQ_MUSIC -> MusicPlatform.QQ_MUSIC
    else -> null
}

internal fun localCurationFailureFor(
    outcome: LocalMediaMetadataWriteOutcome,
    writesLyrics: Boolean,
    writesCover: Boolean
): LocalCurationFailure? = when {
    outcome == LocalMediaMetadataWriteOutcome.SUCCESS -> null
    writesLyrics && writesCover -> LocalCurationFailure.FILE_WRITE
    writesLyrics -> LocalCurationFailure.LYRICS_WRITE
    else -> LocalCurationFailure.COVER_WRITE
}

/** 版本升级删改枚举后，旧值回落到默认，不能让设置页因解析失败而崩溃 */
internal inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
