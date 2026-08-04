package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlayerQueueDisplayItem
import moe.ouom.neriplayer.data.settings.NowPlayingControlPlacement
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType
import moe.ouom.neriplayer.data.model.SongItem
import kotlin.math.pow

class NowPlayingScreenTest {

    @Test
    fun `wide lyrics use synced renderer when advanced lyrics are disabled`() {
        assertEquals(
            NowPlayingWideLyricsMode.SYNCED,
            resolveNowPlayingWideLyricsMode(
                hasLyrics = true,
                advancedLyricsEnabled = false
            )
        )
    }

    @Test
    fun `wide lyrics keep advanced renderer when enabled`() {
        assertEquals(
            NowPlayingWideLyricsMode.ADVANCED,
            resolveNowPlayingWideLyricsMode(
                hasLyrics = true,
                advancedLyricsEnabled = true
            )
        )
    }

    @Test
    fun `wide lyrics show empty state only when lyrics are unavailable`() {
        assertEquals(
            NowPlayingWideLyricsMode.NO_LYRICS,
            resolveNowPlayingWideLyricsMode(
                hasLyrics = false,
                advancedLyricsEnabled = false
            )
        )
    }

    @Test
    fun `compact portrait layout is used when available height is limited`() {
        assertTrue(
            shouldUseCompactNowPlayingPortraitLayout(
                isLandscape = false,
                availableHeightDp = 600f,
                uiDensityScale = 1.0f
            )
        )
        assertTrue(
            shouldUseCompactNowPlayingPortraitLayout(
                isLandscape = false,
                availableHeightDp = 540f,
                uiDensityScale = 1.0f
            )
        )
    }

    @Test
    fun `compact portrait layout does not affect spacious or landscape screens`() {
        assertFalse(
            shouldUseCompactNowPlayingPortraitLayout(
                isLandscape = false,
                availableHeightDp = 601f,
                uiDensityScale = 1.0f
            )
        )
        assertFalse(
            shouldUseCompactNowPlayingPortraitLayout(
                isLandscape = true,
                availableHeightDp = 540f,
                uiDensityScale = 1.2f
            )
        )
    }

    @Test
    fun `high UI density uses compact portrait layout even with ample height`() {
        assertTrue(
            shouldUseCompactNowPlayingPortraitLayout(
                isLandscape = false,
                availableHeightDp = 720f,
                uiDensityScale = 1.1f
            )
        )
    }

    @Test
    fun `compact portrait layout hides cover lyrics and dock regardless of preferences`() {
        assertFalse(
            shouldShowNowPlayingCoverLyrics(
                coverLyricsEnabled = true,
                useCompactPortraitLayout = true
            )
        )
        assertFalse(
            shouldUseNowPlayingToolbarDock(
                toolbarDockEnabled = true,
                useCompactPortraitLayout = true
            )
        )
    }

    @Test
    fun `regular portrait layout honors cover lyrics and dock preferences`() {
        assertTrue(
            shouldShowNowPlayingCoverLyrics(
                coverLyricsEnabled = true,
                useCompactPortraitLayout = false
            )
        )
        assertTrue(
            shouldUseNowPlayingToolbarDock(
                toolbarDockEnabled = true,
                useCompactPortraitLayout = false
            )
        )
    }

    @Test
    fun `bottom playback layouts temporarily disable the dock`() {
        listOf(
            NowPlayingControlPlacement.BOTTOM,
            NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS
        ).forEach { placement ->
            assertTrue(placement.placesControlsAtBottom)
            assertFalse(
                shouldUseNowPlayingToolbarDock(
                    toolbarDockEnabled = true,
                    useCompactPortraitLayout = false,
                    controlsAtBottom = placement.placesControlsAtBottom
                )
            )
        }
        assertTrue(NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS.placesProgressAtBottom)
        assertTrue(
            shouldUseNowPlayingToolbarDock(
                toolbarDockEnabled = true,
                useCompactPortraitLayout = false,
                controlsAtBottom = false
            )
        )
    }

    @Test
    fun `lyrics transition keeps position-sensitive playback elements shared`() {
        assertEquals(
            setOf(
                "btn_back",
                "cover_image",
                "song_title",
                "song_artist",
                "progress_bar",
                "player_previous",
                "play_button",
                "player_next"
            ),
            NowPlayingLyricsSharedTransitionElement.values().map { it.key }.toSet()
        )
    }

    @Test
    fun `large main controls reduce spacing before overflowing narrow screens`() {
        val layout = resolveNowPlayingMainControlsLayout(
            availableWidth = 280.dp,
            secondaryButtonSize = 50.dp,
            primaryButtonSize = 60.dp,
            preferredSpacing = 24.dp
        )

        assertEquals(5.dp, layout.spacing)
        assertEquals(280.dp, layout.secondaryButtonSize * 4 + layout.primaryButtonSize + layout.spacing * 4)
    }

    @Test
    fun `main controls scale as a group when minimum spacing cannot fit`() {
        val layout = resolveNowPlayingMainControlsLayout(
            availableWidth = 250.dp,
            secondaryButtonSize = 50.dp,
            primaryButtonSize = 60.dp,
            preferredSpacing = 24.dp
        )

        assertEquals(45f, layout.secondaryButtonSize.value, 0.001f)
        assertEquals(54f, layout.primaryButtonSize.value, 0.001f)
        assertEquals(4.dp, layout.spacing)
        assertEquals(250.dp, layout.secondaryButtonSize * 4 + layout.primaryButtonSize + layout.spacing * 4)
    }

    @Test
    fun `active icon color boosts muted primary on dark now playing background`() {
        val mutedPrimary = Color(0xFFC9C4B8)
        val seedColor = Color(0xFF00897B)
        val inactiveContentColor = Color(0xFFECE7DC)
        val backgroundColor = Color(0xFF101A16)

        val activeColor = resolveNowPlayingActiveIconColor(
            accentColor = mutedPrimary,
            seedColor = seedColor,
            inactiveContentColor = inactiveContentColor,
            backgroundColor = backgroundColor
        )

        assertFalse(activeColor.toArgb() == mutedPrimary.toArgb())
        assertTrue(
            isNowPlayingActiveIconReadable(
                activeColor = activeColor,
                inactiveContentColor = inactiveContentColor,
                backgroundColor = backgroundColor
            )
        )
        assertTrue(nowPlayingTestSaturation(activeColor) >= 0.32f)
    }

    @Test
    fun `active icon color keeps already readable accent unchanged`() {
        val accentColor = Color(0xFF6FE0C0)
        val inactiveContentColor = Color(0xFFF1EEE7)
        val backgroundColor = Color(0xFF111816)

        val activeColor = resolveNowPlayingActiveIconColor(
            accentColor = accentColor,
            seedColor = Color(0xFF00897B),
            inactiveContentColor = inactiveContentColor,
            backgroundColor = backgroundColor
        )

        assertEquals(accentColor.toArgb(), activeColor.toArgb())
    }

    @Test
    fun `active icon color falls back when palette and seed are neutral`() {
        val inactiveContentColor = Color(0xFFECE7DC)
        val backgroundColor = Color(0xFF101A16)

        val activeColor = resolveNowPlayingActiveIconColor(
            accentColor = Color(0xFFC9C4B8),
            seedColor = Color(0xFF777777),
            inactiveContentColor = inactiveContentColor,
            backgroundColor = backgroundColor
        )

        assertTrue(
            isNowPlayingActiveIconReadable(
                activeColor = activeColor,
                inactiveContentColor = inactiveContentColor,
                backgroundColor = backgroundColor
            )
        )
        assertTrue(
            nowPlayingTestContrastRatio(
                activeColor.toArgb(),
                backgroundColor.toArgb()
            ) >= 3.0
        )
    }

    @Test
    fun `playback action toolbar keeps normal spacing when five touch targets fit`() {
        val layout = resolvePlaybackActionToolbarLayout(
            availableWidth = 300.dp,
            preferredHorizontalPadding = 16.dp,
            defaultIconSize = 20.dp
        )

        assertEquals(16.dp, layout.horizontalPadding)
        assertEquals(48.dp, layout.minimumInteractiveComponentSize)
        assertEquals(20.dp, layout.iconSize)
        assertFalse(layout.useEqualWidthSlots)
    }

    @Test
    fun `playback action toolbar assigns five equal slots when high density width is narrow`() {
        val layout = resolvePlaybackActionToolbarLayout(
            availableWidth = 220.dp,
            preferredHorizontalPadding = 16.dp,
            defaultIconSize = 20.dp
        )

        assertEquals(0.dp, layout.horizontalPadding)
        assertEquals(44.dp, layout.minimumInteractiveComponentSize)
        assertEquals(20.dp, layout.iconSize)
        assertTrue(layout.useEqualWidthSlots)
    }

    @Test
    fun `playback action toolbar reduces icon size before a very narrow fifth slot overflows`() {
        val layout = resolvePlaybackActionToolbarLayout(
            availableWidth = 190.dp,
            preferredHorizontalPadding = 16.dp,
            defaultIconSize = 20.dp
        )

        assertEquals(38.dp, layout.minimumInteractiveComponentSize)
        assertEquals(18.dp, layout.iconSize)
        assertTrue(layout.useEqualWidthSlots)
    }

    @Test
    fun `download action remains visible when completed task exists but local file is gone`() {
        assertFalse(
            shouldHideDownloadActionForSong(
                hasLocalDownload = false,
                currentTask = null
            )
        )
    }

    @Test
    fun `download action hides only when actual local download exists`() {
        assertTrue(
            shouldHideDownloadActionForSong(
                hasLocalDownload = true,
                currentTask = null
            )
        )
    }

    @Test
    fun `unfinished task keeps download action visible even when local download probe hits`() {
        val task = DownloadTask(
            song = SongItem(
                id = 1L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null
            ),
            progress = null,
            status = DownloadStatus.DOWNLOADING
        )

        assertFalse(
            shouldHideDownloadActionForSong(
                hasLocalDownload = true,
                currentTask = task
            )
        )
    }

    @Test
    fun `queue entry keys survive reorder for unique songs`() {
        val first = testSong(id = 1L, name = "First")
        val second = testSong(id = 2L, name = "Second")
        val third = testSong(id = 3L, name = "Third")
        val initialKeys = buildNowPlayingQueueEntries(listOf(first, second, third))
            .associate { it.song.name to it.key }
        val reorderedKeys = buildNowPlayingQueueEntries(listOf(third, first, second))
            .associate { it.song.name to it.key }

        assertEquals(initialKeys, reorderedKeys)
    }

    @Test
    fun `queue entry keys keep duplicate songs visible without row index`() {
        val song = testSong(id = 1L, name = "Song")
        val entries = buildNowPlayingQueueEntries(listOf(song, song))

        assertTrue(entries[0].key != entries[1].key)
    }

    @Test
    fun `queue entries preserve source queue indices for shuffled display rows`() {
        val first = testSong(id = 1L, name = "First")
        val second = testSong(id = 2L, name = "Second")
        val third = testSong(id = 3L, name = "Third")

        val entries = buildNowPlayingQueueEntriesFromDisplayItems(
            listOf(
                PlayerQueueDisplayItem(queueIndex = 2, song = third),
                PlayerQueueDisplayItem(queueIndex = 0, song = first),
                PlayerQueueDisplayItem(queueIndex = 1, song = second)
            )
        )

        assertEquals(listOf(2, 0, 1), entries.map { it.queueIndex })
        assertEquals(listOf(third, first, second), entries.map { it.song })
    }

    @Test
    fun `queue selected songs keep duplicate entries by row key`() {
        val song = testSong(id = 1L, name = "Song")
        val queue = listOf(song, song, testSong(id = 2L, name = "Other"))
        val selectedKeys = buildNowPlayingQueueEntries(queue)
            .take(2)
            .mapTo(LinkedHashSet()) { it.key }

        val selectedSongs = resolveNowPlayingQueueSelectedSongs(queue, selectedKeys)

        assertEquals(listOf(song, song), selectedSongs)
    }

    @Test
    fun `queue reorder moves entries by stable row key`() {
        val first = testSong(id = 1L, name = "First")
        val second = testSong(id = 2L, name = "Second")
        val third = testSong(id = 3L, name = "Third")
        val entries = buildNowPlayingQueueEntries(listOf(first, second, third)).toMutableList()

        assertTrue(moveNowPlayingQueueEntry(entries, entries[0].key, entries[2].key))

        assertEquals(listOf(second, third, first), entries.map { it.song })
    }

    @Test
    fun `queue reorder ignores missing and unchanged row keys`() {
        val entries = buildNowPlayingQueueEntries(
            listOf(
                testSong(id = 1L, name = "First"),
                testSong(id = 2L, name = "Second")
            )
        ).toMutableList()
        val initialOrder = entries.toList()

        assertFalse(moveNowPlayingQueueEntry(entries, "missing", entries[1].key))
        assertFalse(moveNowPlayingQueueEntry(entries, entries[0].key, entries[0].key))

        assertEquals(initialOrder, entries)
    }

    @Test
    fun `queue entry sync keeps the same mutable list after commit`() {
        val first = testSong(id = 1L, name = "First")
        val second = testSong(id = 2L, name = "Second")
        val third = testSong(id = 3L, name = "Third")
        val entries = buildNowPlayingQueueEntries(listOf(first, second, third)).toMutableList()
        val originalEntries = entries
        val committedEntries = buildNowPlayingQueueEntries(listOf(third, first, second))

        assertTrue(syncNowPlayingQueueEntries(entries, committedEntries))

        assertSame(originalEntries, entries)
        assertEquals(committedEntries, entries)
    }

    @Test
    fun `queue entry sync ignores unchanged source entries`() {
        val entries = buildNowPlayingQueueEntries(
            listOf(
                testSong(id = 1L, name = "First"),
                testSong(id = 2L, name = "Second")
            )
        ).toMutableList()

        assertFalse(syncNowPlayingQueueEntries(entries, entries.toList()))
    }

    @Test
    fun `queue invert selection keeps original order for remaining rows`() {
        val first = testSong(id = 1L, name = "First")
        val second = testSong(id = 2L, name = "Second")
        val third = testSong(id = 3L, name = "Third")
        val queue = listOf(first, second, third)
        val selectedKeys = setOf(buildNowPlayingQueueEntries(queue)[1].key)

        val invertedKeys = invertNowPlayingQueueSelection(queue, selectedKeys)

        assertEquals(
            setOf(
                buildNowPlayingQueueEntries(queue)[0].key,
                buildNowPlayingQueueEntries(queue)[2].key
            ),
            invertedKeys
        )
        assertEquals(
            listOf(first, third),
            resolveNowPlayingQueueSelectedSongs(queue, invertedKeys)
        )
    }

    @Test
    fun `queue quick actions only need non empty queue`() {
        assertFalse(
            shouldShowNowPlayingQueueQuickActions(
                queueSize = 0,
                currentIndex = 0,
                hasSourceRoute = true
            )
        )
        assertTrue(
            shouldShowNowPlayingQueueQuickActions(
                queueSize = 3,
                currentIndex = -1,
                hasSourceRoute = false
            )
        )
        assertTrue(
            shouldShowNowPlayingQueueQuickActions(
                queueSize = 3,
                currentIndex = -1,
                hasSourceRoute = true
            )
        )
    }

    @Test
    fun `queue reorder current index prefers dragged current row key`() {
        assertEquals(
            3,
            resolveNowPlayingQueueCurrentIndexAfterReorder(
                queueSize = 5,
                currentIndex = 1,
                currentIndexByKey = 3
            )
        )
    }

    @Test
    fun `queue reorder current index clamps stale current index`() {
        assertEquals(
            2,
            resolveNowPlayingQueueCurrentIndexAfterReorder(
                queueSize = 3,
                currentIndex = 9,
                currentIndexByKey = -1
            )
        )
    }

    @Test
    fun `queue reorder current index returns missing for empty queue`() {
        assertEquals(
            -1,
            resolveNowPlayingQueueCurrentIndexAfterReorder(
                queueSize = 0,
                currentIndex = 2,
                currentIndexByKey = -1
            )
        )
    }

    @Test
    fun `queue reorder autoscroll uses gentle per frame step`() {
        assertEquals(2.dp, NowPlayingQueueReorderAutoScrollMaxPerFrame)
    }

    @Test
    fun `queue index input resolves one based numbers`() {
        assertEquals(0, resolveNowPlayingQueueIndexInput("1", queueSize = 10))
        assertEquals(9, resolveNowPlayingQueueIndexInput("10", queueSize = 10))
        assertEquals(4, resolveNowPlayingQueueIndexInput(" 5 ", queueSize = 10))
    }

    @Test
    fun `queue index input rejects empty zero and out of range numbers`() {
        assertEquals(null, resolveNowPlayingQueueIndexInput("", queueSize = 10))
        assertEquals(null, resolveNowPlayingQueueIndexInput("0", queueSize = 10))
        assertEquals(null, resolveNowPlayingQueueIndexInput("11", queueSize = 10))
        assertEquals(null, resolveNowPlayingQueueIndexInput("1", queueSize = 0))
        assertEquals(null, resolveNowPlayingQueueIndexInput("abc", queueSize = 10))
    }

    @Test
    fun `queue scroll target keeps distant current item precise`() {
        assertEquals(
            4_096,
            resolveNowPlayingQueueScrollTarget(
                queueSize = 5_000,
                currentIndex = 4_096
            )
        )
    }

    @Test
    fun `queue scroll target ignores unavailable current item`() {
        assertEquals(
            null,
            resolveNowPlayingQueueScrollTarget(
                queueSize = 10,
                currentIndex = 10
            )
        )
        assertEquals(
            null,
            resolveNowPlayingQueueScrollTarget(
                queueSize = 0,
                currentIndex = 0
            )
        )
    }

    @Test
    fun `queue scroll skips work when initial list state is already at target`() {
        assertFalse(
            shouldUpdateNowPlayingQueueScroll(
                targetIndex = 4_096,
                firstVisibleItemIndex = 4_096,
                firstVisibleItemScrollOffset = 0
            )
        )
    }

    @Test
    fun `queue scroll updates when visible item or offset differs from target`() {
        assertTrue(
            shouldUpdateNowPlayingQueueScroll(
                targetIndex = 4_096,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0
            )
        )
        assertTrue(
            shouldUpdateNowPlayingQueueScroll(
                targetIndex = 4_096,
                firstVisibleItemIndex = 4_096,
                firstVisibleItemScrollOffset = 12
            )
        )
    }

    @Test
    fun `queue auto locate pauses while sorting or pending reorder commit`() {
        assertTrue(
            shouldAutoLocateNowPlayingQueue(
                selectionMode = false,
                queueOrderDirty = false
            )
        )
        assertFalse(
            shouldAutoLocateNowPlayingQueue(
                selectionMode = true,
                queueOrderDirty = false
            )
        )
        assertFalse(
            shouldAutoLocateNowPlayingQueue(
                selectionMode = false,
                queueOrderDirty = true
            )
        )
    }

    @Test
    fun `artist navigation ignores matched netease metadata for non netease songs`() {
        val biliSong = testSong(id = 6L, name = "Bili song").copy(
            album = "Bilibili|123",
            channelId = "bilibili",
            coverUrl = "https://music.126.net/netease-cover.jpg",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "9876"
        )
        val youtubeSong = testSong(id = 9L, name = "YouTube song").copy(
            channelId = "youtubeMusic",
            mediaUri = "ytmusic://video/demo",
            coverUrl = "https://music.126.net/netease-cover.jpg",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "9876"
        )
        val qqSong = testSong(id = 10L, name = "QQ song").copy(
            channelId = "qq",
            coverUrl = "https://music.126.net/netease-cover.jpg",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "9876"
        )

        assertFalse(isNeteaseArtistNavigationSource(biliSong))
        assertFalse(isNeteaseArtistNavigationSource(youtubeSong))
        assertFalse(isNeteaseArtistNavigationSource(qqSong))
    }

    @Test
    fun `artist navigation keeps explicit netease source without matched lyrics`() {
        val neteaseSong = testSong(id = 7L, name = "Netease song").copy(
            channelId = "netease",
            audioId = "7"
        )
        val legacyNeteaseSong = testSong(id = 8L, name = "Legacy Netease song").copy(
            album = "NeteaseLegacy"
        )

        assertTrue(isNeteaseArtistNavigationSource(neteaseSong))
        assertTrue(isNeteaseArtistNavigationSource(legacyNeteaseSong))
    }

    @Test
    fun `bilibili uploader navigation only accepts playable bilibili videos`() {
        val biliSong = testSong(id = 11L, name = "Bili song").copy(
            album = "Bilibili|123",
            channelId = "bilibili"
        )
        val missingVideoId = biliSong.copy(id = 0L)
        val neteaseSong = testSong(id = 12L, name = "Netease song").copy(
            album = "Netease"
        )

        assertTrue(isBiliUploaderNavigationSource(biliSong))
        assertFalse(isBiliUploaderNavigationSource(missingVideoId))
        assertFalse(isBiliUploaderNavigationSource(neteaseSong))
    }

    @Test
    fun `playback source badge uses resolved bili audio source over netease tag`() {
        val sourceType = resolveNowPlayingPlaybackSourceType(
            isLocalSong = false,
            isYouTubeMusicSong = false,
            isFromNeteaseTag = true,
            isFromBiliTag = false,
            currentMediaUrl = "https://m701.music.126.net/demo.mp3",
            playbackAudioSource = PlaybackAudioSource.BILIBILI
        )

        assertTrue(sourceType == PlaybackSourceType.BILIBILI)
    }

    @Test
    fun `remote local cache does not override original platform badge`() {
        val sourceType = resolveNowPlayingPlaybackSourceType(
            isLocalSong = false,
            isYouTubeMusicSong = false,
            isFromNeteaseTag = true,
            isFromBiliTag = false,
            currentMediaUrl = "content://downloads/demo.flac",
            playbackAudioSource = PlaybackAudioSource.LOCAL
        )

        assertTrue(sourceType == PlaybackSourceType.NETEASE)
    }

    @Test
    fun `netease local fallback shows local file badge`() {
        val sourceType = resolveNowPlayingPlaybackSourceType(
            isLocalSong = false,
            isYouTubeMusicSong = false,
            isFromNeteaseTag = true,
            isFromBiliTag = false,
            currentMediaUrl = "content://media/external/audio/media/42",
            playbackAudioSource = PlaybackAudioSource.LOCAL,
            isNeteaseLocalFallback = true
        )

        assertEquals(PlaybackSourceType.LOCAL, sourceType)
    }

    private fun testSong(
        id: Long,
        name: String
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "Artist",
            album = "Album",
            albumId = id,
            durationMs = 1_000L,
            coverUrl = null
        )
    }

    private fun nowPlayingTestSaturation(color: Color): Float {
        val argb = color.toArgb()
        val red = ((argb shr 16) and 0xFF) / 255f
        val green = ((argb shr 8) and 0xFF) / 255f
        val blue = (argb and 0xFF) / 255f
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min
        val lightness = (max + min) / 2f
        return if (delta == 0f) {
            0f
        } else if (lightness > 0.5f) {
            delta / (2f - max - min)
        } else {
            delta / (max + min)
        }
    }

    private fun nowPlayingTestContrastRatio(firstArgb: Int, secondArgb: Int): Double {
        val firstLuminance = nowPlayingTestRelativeLuminance(firstArgb)
        val secondLuminance = nowPlayingTestRelativeLuminance(secondArgb)
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun nowPlayingTestRelativeLuminance(argb: Int): Double {
        val red = nowPlayingTestLinearRgb((argb shr 16) and 0xFF)
        val green = nowPlayingTestLinearRgb((argb shr 8) and 0xFF)
        val blue = nowPlayingTestLinearRgb(argb and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun nowPlayingTestLinearRgb(channel: Int): Double {
        val normalized = channel / 255.0
        return if (normalized <= 0.03928) {
            normalized / 12.92
        } else {
            ((normalized + 0.055) / 1.055).pow(2.4)
        }
    }
}
