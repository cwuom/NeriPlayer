package moe.ouom.neriplayer.data.ltw.mapping

import moe.ouom.neriplayer.data.model.server.isServerSong

import android.net.Uri
import moe.ouom.neriplayer.platform.youtube.api.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.ltw.playback.boundedAroundStableKey
import moe.ouom.neriplayer.data.ltw.playback.currentTrack
import moe.ouom.neriplayer.data.ltw.playback.isListenTogetherPlaybackModeQueueUpdate
import moe.ouom.neriplayer.data.ltw.playback.isListenTogetherQueueUpdateCause
import moe.ouom.neriplayer.data.ltw.session.state.normalized
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

class DefaultListenTogetherSongMapper(
    private val isLocalSong: (SongItem) -> Boolean,
    private val localAlbumIdentity: String,
    private val resolvedStreamUrls: () -> List<String> = { emptyList() }
) : ListenTogetherSongMapper {

    override fun SongItem.resolvedChannelId(): String {
        val explicit = channelId.nonBlankOrNull()
        if (explicit != null) return explicit
        return when {
            isLocalSong(this) -> ListenTogetherChannels.LOCAL
            !extractYouTubeMusicVideoId(mediaUri).isNullOrBlank() -> ListenTogetherChannels.YOUTUBE_MUSIC
            album.startsWith(SongSourceTags.BILIBILI) -> ListenTogetherChannels.BILIBILI
            else -> ListenTogetherChannels.NETEASE
        }
    }

    override fun SongItem.resolvedAudioId(): String? {
        val explicit = audioId.nonBlankOrNull()
        if (explicit != null) return explicit
        return when (resolvedChannelId()) {
            ListenTogetherChannels.YOUTUBE_MUSIC -> extractYouTubeMusicVideoId(mediaUri)
            else -> id.toString()
        }
    }

    override fun SongItem.resolvedSubAudioId(): String? {
        val explicit = subAudioId.nonBlankOrNull()
        if (explicit != null) return explicit
        if (resolvedChannelId() != ListenTogetherChannels.BILIBILI) {
            return null
        }
        return album
            .substringAfter('|', "")
            .substringBefore('|')
            .takeIf { it.isNotBlank() }
    }

    override fun SongItem.resolvedPlaylistContextId(): String? {
        val explicit = playlistContextId.nonBlankOrNull()
        if (explicit != null) return explicit
        if (resolvedChannelId() != ListenTogetherChannels.YOUTUBE_MUSIC) {
            return null
        }
        return playlistIdFromMediaUri(mediaUri)
    }

    override fun ListenTogetherTrack.toSongItem(): SongItem = when {
        channelId == ListenTogetherChannels.YOUTUBE_MUSIC -> toYouTubeSong()
        channelId == ListenTogetherChannels.BILIBILI -> toBilibiliSong()
        channelId == ListenTogetherChannels.LOCAL -> toLocalSong(localAlbumIdentity)
        else -> toNeteaseSong()
    }

    override fun SongItem.toListenTogetherTrackOrNull(includeLocal: Boolean): ListenTogetherTrack? {
        if (isServerSong()) return null
        val channel = resolvedChannelId()
        if (channel.equals(ListenTogetherChannels.LOCAL, ignoreCase = true) && !includeLocal) {
            return null
        }

        val audio = resolvedAudioId() ?: return null
        val subAudio = resolvedSubAudioId()
        val playlistContext = resolvedPlaylistContextId()
        return ListenTogetherTrack(
            stableKey = buildStableTrackKey(channel, audio, subAudio, playlistContext),
            channelId = channel,
            audioId = audio,
            subAudioId = subAudio,
            playlistContextId = playlistContext,
            mediaUri = mediaUri,
            streamUrl = streamUrl,
            streamUrls = listOfNotNull(streamUrl),
            name = customName ?: name,
            artist = customArtist ?: artist,
            album = album,
            durationMs = durationMs,
            coverUrl = customCoverUrl ?: coverUrl
        )
    }

    override fun ListenTogetherRoomState.targetSongItem(): SongItem? {
        return currentTrack()?.toSongItem()
    }

    override fun SongItem.sameTrackAs(other: SongItem): Boolean {
        return resolvedChannelId() == other.resolvedChannelId() &&
            resolvedAudioId() == other.resolvedAudioId() &&
            resolvedSubAudioId() == other.resolvedSubAudioId() &&
            resolvedPlaylistContextId() == other.resolvedPlaylistContextId()
    }

    override fun List<SongItem>.hasSameTrackSequenceAs(other: List<SongItem>): Boolean {
        if (size != other.size) return false
        return indices.all { index -> this[index].sameTrackAs(other[index]) }
    }

    private data class ListenTogetherPlaybackIdentity(
        val channelId: String?,
        val audioId: String?,
        val subAudioId: String?,
        val playlistContextId: String?
    )

    private fun SongItem.listenTogetherPlaybackIdentity(): ListenTogetherPlaybackIdentity {
        return ListenTogetherPlaybackIdentity(
            channelId = resolvedChannelId(),
            audioId = resolvedAudioId(),
            subAudioId = resolvedSubAudioId(),
            playlistContextId = resolvedPlaylistContextId()
        )
    }

    override fun List<SongItem>.hasSameTrackMultisetAs(other: List<SongItem>): Boolean {
        if (size != other.size) return false
        return groupingBy { it.listenTogetherPlaybackIdentity() }.eachCount() ==
            other.groupingBy { it.listenTogetherPlaybackIdentity() }.eachCount()
    }

    override fun shouldApplyListenTogetherQueueUpdateWithoutReload(
        causeType: String?,
        currentQueue: List<SongItem>,
        currentSong: SongItem?,
        incomingQueue: List<SongItem>,
        incomingCurrentIndex: Int
    ): Boolean {
        if (!isListenTogetherQueueUpdateCause(causeType)) return false
        val incomingCurrentSong = incomingQueue.getOrNull(incomingCurrentIndex) ?: return false
        if (currentQueue.isEmpty() || currentSong?.sameTrackAs(incomingCurrentSong) != true) {
            return false
        }
        return !isListenTogetherPlaybackModeQueueUpdate(causeType) ||
            currentQueue.hasSameTrackMultisetAs(incomingQueue)
    }

    override fun List<SongItem>.indexOfTrack(track: SongItem?): Int {
        track ?: return -1
        return indexOfFirst { candidate -> candidate.sameTrackAs(track) }
    }

    override fun SongItem?.isShareableForListenTogether(): Boolean {
        return this?.toListenTogetherTrackOrNull() != null
    }

    override fun List<SongItem>.hasShareableListenTogetherTrackAt(index: Int): Boolean {
        return getOrNull(index).isShareableForListenTogether()
    }

    override fun List<SongItem>.toShareableQueueSnapshot(
        currentIndex: Int,
        roomSettings: ListenTogetherRoomSettings?,
        includeResolvedStreamUrl: Boolean,
        resolvedCurrentStreamUrls: List<String>?
    ): Pair<List<ListenTogetherTrack>, Int> {
        if (isEmpty()) return emptyList<ListenTogetherTrack>() to 0

        val targetSong = getOrNull(currentIndex.coerceIn(0, lastIndex))
        val targetStableKey = targetSong?.toListenTogetherTrackOrNull()?.stableKey
        val canShareResolvedStreamUrls = includeResolvedStreamUrl &&
            roomSettings.normalized().shareAudioLinks
        val currentStreamUrls = if (canShareResolvedStreamUrls) {
            resolvedCurrentStreamUrls ?: resolvedStreamUrls()
        } else {
            emptyList()
        }
        val shareableQueue = shareableTracks(targetStableKey, canShareResolvedStreamUrls, currentStreamUrls)
            .boundedAroundStableKey(targetStableKey)
        if (shareableQueue.isEmpty()) return shareableQueue to 0

        val resolvedCurrentIndex = shareableQueue.currentIndexFor(targetStableKey)

        return shareableQueue to resolvedCurrentIndex
    }

    private fun List<SongItem>.shareableTracks(
        targetStableKey: String?,
        includeResolvedUrls: Boolean,
        currentStreamUrls: List<String>
    ): List<ListenTogetherTrack> = mapNotNull { song ->
        val track = song.toListenTogetherTrackOrNull() ?: return@mapNotNull null
        val urls = if (includeResolvedUrls && track.stableKey == targetStableKey) currentStreamUrls else emptyList()
        track.withStreamUrls(urls)
    }

    private fun List<ListenTogetherTrack>.currentIndexFor(stableKey: String?): Int {
        if (stableKey == null) return 0
        return indexOfFirst { it.stableKey == stableKey }.coerceAtLeast(0)
    }

    private fun playlistIdFromMediaUri(mediaUri: String?): String? =
        mediaUri?.let(Uri::parse)?.getQueryParameter("playlistId").nonBlankOrNull()

    private fun String?.nonBlankOrNull(): String? = if (isNullOrBlank()) null else this

    override fun List<SongItem>.toShareableShuffleRestoreQueueSnapshot(
        activeQueue: List<ListenTogetherTrack>
    ): List<ListenTogetherTrack> {
        if (isEmpty() || activeQueue.isEmpty()) return emptyList()
        val remainingCounts = activeQueue.groupingBy { it.stableKey }.eachCount().toMutableMap()
        val restoreQueue = mutableListOf<ListenTogetherTrack>()
        for (song in this) {
            val track = song.toListenTogetherTrackOrNull()?.withStreamUrls(emptyList()) ?: continue
            val remaining = remainingCounts[track.stableKey] ?: continue
            if (remaining == 1) {
                remainingCounts.remove(track.stableKey)
            } else {
                remainingCounts[track.stableKey] = remaining - 1
            }
            restoreQueue += track
        }
        return restoreQueue.takeIf { it.size == activeQueue.size }.orEmpty()
    }
}
