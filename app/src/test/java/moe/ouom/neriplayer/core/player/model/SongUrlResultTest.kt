package moe.ouom.neriplayer.core.player.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SongUrlResultTest {

    @Test
    fun playbackUrls_trimBlankAndDeduplicateCandidates() {
        val result = SongUrlResult.Success(
            url = " https://cdn.example/primary.m4s ",
            candidateUrls = listOf(
                "https://cdn.example/primary.m4s",
                " ",
                "https://cdn.example/backup.m4s"
            )
        )

        assertEquals(
            listOf(
                "https://cdn.example/primary.m4s",
                "https://cdn.example/backup.m4s"
            ),
            result.playbackUrls()
        )
    }

    @Test
    fun playbackCandidates_expandPrimaryAndFallbackCandidatesInOrder() {
        val result = SongUrlResult.Success(
            url = "https://cdn.example/primary.m4s",
            candidateUrls = listOf("https://cdn.example/backup-a.m4s"),
            fallbackCandidates = listOf(
                PlaybackUrlCandidate(
                    url = "https://cdn.example/fallback.m4s",
                    candidateUrls = listOf(
                        "https://cdn.example/backup-a.m4s",
                        "https://cdn.example/fallback-backup.m4s"
                    )
                )
            )
        )

        assertEquals(
            listOf(
                "https://cdn.example/primary.m4s",
                "https://cdn.example/backup-a.m4s",
                "https://cdn.example/fallback.m4s",
                "https://cdn.example/fallback-backup.m4s"
            ),
            result.playbackCandidates().map { it.url }
        )
    }
}
