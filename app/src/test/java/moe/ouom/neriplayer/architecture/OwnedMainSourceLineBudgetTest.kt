package moe.ouom.neriplayer.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedMainSourceLineBudgetTest {
    @Test
    fun `completed owned source sections stay below 2000 physical lines`() {
        val projectRoot = LocalManagementLineBudget.findProjectRoot(
            File(System.getProperty("user.dir") ?: ".")
        )
        listOf(
            "app/src/main/java/moe/ouom/neriplayer/data/settings/AutoSettingsSchema.kt",
            "app/src/main/java/moe/ouom/neriplayer/data/settings/PlaybackSettingsSection.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/api/bili/BiliClient.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/api/bili/BiliCommentApi.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/api/bili/BiliCookieSession.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/api/bili/BiliRequestSupport.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/HomeScreen.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/HomeContinueSection.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/SettingsDownloadDirectoryPreflight.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/SettingsNavigationSearch.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/SettingsThemeControls.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/NowPlayingQueueSheet.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/NowPlayingLyricSettingsSheet.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/onboarding/StartupOnboardingScreen.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/onboarding/StartupOnboardingSharedComponents.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/onboarding/StartupBackupRestoreStep.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/ExploreScreen.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/ExploreSearchResultRows.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/ExploreBrowseContent.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/ExploreGlassPillSurface.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/MainTabNavigationMotion.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/SettingsPlaybackControlLayout.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/SettingsPersonalizationContent.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/SettingsListenTogetherController.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/SettingsListenTogetherDialogs.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/SettingsListenTogetherSection.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/NowPlayingPlaybackControls.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/NowPlayingSecondaryActions.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/PlaybackArtworkOwner.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/PlaybackArtworkPolicies.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/PlaybackArtworkSources.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/PlaybackCoverSourceResolver.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/PlaybackNotificationWidgetPresentation.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/PlaybackServiceMetadataPresentation.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/UsbExclusiveServiceKeepAliveOwner.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/AndroidUsbExclusiveKeepAlivePort.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/UsbExclusiveMediaSessionVolumeRouter.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/AndroidUsbExclusiveVolumeRoutingPort.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/api/youtube/YouTubeStreamingCipherResolution.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/api/youtube/YouTubePlayerRequestComposer.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/AudioPlayerService.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/PlaybackServicePresentationOwner.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/PlaybackServicePresentationSource.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/service/AndroidPlaybackServicePresentationPort.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/api/youtube/YouTubePlayerResponseParsers.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/usb/sink/UsbExclusiveAudioSink.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/usb/sink/UsbExclusivePcmWriter.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/usb/sink/UsbExclusiveSinkVolumeOwner.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/usb/sink/UsbExclusiveTransportStartPolicy.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/NowPlayingCover.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/NowPlayingCoverTopBar.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/NowPlayingTrackIdentity.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/NowPlayingCoverActionToolbar.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/NowPlayingCoverControlsPlacement.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/NowPlayingCoverLyrics.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/api/youtube/YouTubeMusicClient.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/api/youtube/YouTubeMusicProtocolModels.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/api/youtube/YouTubeMusicResponseParser.kt"
        ).forEach { path ->
            val file = File(projectRoot, path)
            assertTrue("缺少受保护源码：$path", file.isFile)
            val physicalLines = LocalManagementLineBudget.countPhysicalLines(file)
            assertTrue(
                "$path 有 $physicalLines 行，必须少于 ${LocalManagementLineBudget.MAX_EXCLUSIVE} 行",
                LocalManagementLineBudget.isWithinBudget(physicalLines)
            )
        }

        val nativeSources = File(projectRoot, "app/src/main/cpp/usb/exclusive")
        assertTrue("缺少 USB 独占输出源码目录", nativeSources.isDirectory)
        val nativeFiles = nativeSources.listFiles().orEmpty()
            .filter { it.isFile && it.extension in setOf("cpp", "h") }
        assertTrue("USB 独占输出源码目录为空", nativeFiles.isNotEmpty())
        nativeFiles.forEach { file ->
            val physicalLines = LocalManagementLineBudget.countPhysicalLines(file)
            assertTrue(
                "${file.relativeTo(projectRoot)} 有 $physicalLines 行，必须少于 ${LocalManagementLineBudget.MAX_EXCLUSIVE} 行",
                LocalManagementLineBudget.isWithinBudget(physicalLines)
            )
        }
    }
}
