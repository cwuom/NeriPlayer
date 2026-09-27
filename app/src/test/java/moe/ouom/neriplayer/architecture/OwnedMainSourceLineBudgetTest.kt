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
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/HomeContinueSection.kt"
        ).forEach { path ->
            val file = File(projectRoot, path)
            assertTrue("缺少受保护源码：$path", file.isFile)
            val physicalLines = LocalManagementLineBudget.countPhysicalLines(file)
            assertTrue(
                "$path 有 $physicalLines 行，必须少于 ${LocalManagementLineBudget.MAX_EXCLUSIVE} 行",
                LocalManagementLineBudget.isWithinBudget(physicalLines)
            )
        }
    }
}
