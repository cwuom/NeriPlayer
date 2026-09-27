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
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/NowPlayingQueueSheet.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/onboarding/StartupOnboardingScreen.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/onboarding/StartupOnboardingSharedComponents.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/onboarding/StartupBackupRestoreStep.kt"
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
