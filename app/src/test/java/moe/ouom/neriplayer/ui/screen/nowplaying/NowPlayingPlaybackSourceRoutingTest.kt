package moe.ouom.neriplayer.ui.screen.nowplaying

import java.io.File
import moe.ouom.neriplayer.architecture.LocalManagementLineBudget
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingPlaybackSourceRoutingTest {

    // 引导预览把这几页换成示例播放源, 漏一处直连 PlayerManager 就会改到真实播放
    @Test
    fun `now playing and lyrics pages route playback through the playback source`() {
        val projectRoot = LocalManagementLineBudget.findProjectRoot(
            File(System.getProperty("user.dir") ?: ".")
        )
        val directCalls = RoutedSources.flatMap { path ->
            File(projectRoot, path).readLines().mapIndexedNotNull { index, line ->
                "$path:${index + 1}: ${line.trim()}".takeIf { DirectPlayerManagerCall.containsMatchIn(line) }
            }
        }
        assertEquals(emptyList<String>(), directCalls)
    }

    private companion object {
        val RoutedSources = listOf(
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/nowplaying/NowPlayingScreen.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/nowplaying/NowPlayingPlaybackControls.kt",
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/lyrics/LyricsScreen.kt"
        )
        val DirectPlayerManagerCall =
            Regex("""\bPlayerManager\s*(?:\.|::)\s*(?!NETEASE_SOURCE_TAG\b|BILI_SOURCE_TAG\b)[A-Za-z_]""")
    }
}
