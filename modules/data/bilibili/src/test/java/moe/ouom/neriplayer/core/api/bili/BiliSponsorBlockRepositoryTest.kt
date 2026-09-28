package moe.ouom.neriplayer.core.api.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliSponsorBlockRepositoryTest {
    @Test
    fun `hash prefix matches the documented test video`() {
        assertEquals("5759", biliSponsorBlockHashPrefix("BV14741127BN"))
    }

    @Test
    fun `parser keeps compatible automatic skip segments including video boundaries`() {
        val segments = parseBiliSponsorBlockSegments(
            responseBody = """
                [
                  {
                    "videoID": "BV14741127BN",
                    "segments": [
                      {
                        "cid": "42",
                        "category": "sponsor",
                        "actionType": "skip",
                        "segment": [1.5, 4.0],
                        "UUID": "accepted-sponsor",
                        "videoDuration": 100
                      },
                      {
                        "cid": "42",
                        "category": "music_offtopic",
                        "actionType": "skip",
                        "segment": [20.0, 24.0],
                        "UUID": "stale-duration",
                        "videoDuration": 90
                      },
                      {
                        "cid": "43",
                        "category": "filler",
                        "actionType": "skip",
                        "segment": [30.0, 35.0],
                        "UUID": "other-page",
                        "videoDuration": 100
                      },
                      {
                        "cid": "42",
                        "category": "intro",
                        "actionType": "skip",
                        "segment": [40.0, 45.0],
                        "UUID": "accepted-intro",
                        "videoDuration": 100
                      },
                      {
                        "cid": "42",
                        "category": "outro",
                        "actionType": "skip",
                        "segment": [80.0, 85.0],
                        "UUID": "accepted-outro",
                        "videoDuration": 100
                      },
                      {
                        "cid": "42",
                        "category": "selfpromo",
                        "actionType": "skip",
                        "segment": [86.0, 90.0],
                        "UUID": "manual-category",
                        "videoDuration": 100
                      },
                      {
                        "cid": "42",
                        "category": "filler",
                        "actionType": "poi",
                        "segment": [50.0, 50.0],
                        "UUID": "non-skip-action",
                        "videoDuration": 100
                      },
                      {
                        "cid": "42",
                        "category": "filler",
                        "actionType": "skip",
                        "segment": [99.5, 102.0],
                        "UUID": "accepted-clamped",
                        "videoDuration": 100
                      }
                    ]
                  }
                ]
            """.trimIndent(),
            target = BiliSponsorBlockTarget(
                bvid = "BV14741127BN",
                cid = 42L,
                durationMs = 100_000L
            )
        )

        assertEquals(4, segments.size)
        assertEquals("accepted-sponsor", segments[0].uuid)
        assertEquals(1_500L, segments[0].startMs)
        assertEquals(4_000L, segments[0].endMs)
        assertEquals("accepted-intro", segments[1].uuid)
        assertEquals("accepted-outro", segments[2].uuid)
        assertEquals("accepted-clamped", segments[3].uuid)
        assertEquals(100_000L, segments[3].endMs)
        assertTrue(segments.none { it.uuid == "manual-category" })
        assertTrue(isBiliSponsorBlockDurationCompatible("101.5", 100_000L))
    }

    @Test
    fun `parser ignores malformed or unrelated responses`() {
        val target = BiliSponsorBlockTarget(
            bvid = "BV14741127BN",
            cid = 42L,
            durationMs = 100_000L
        )

        assertTrue(parseBiliSponsorBlockSegments("not-json", target).isEmpty())
        assertTrue(
            parseBiliSponsorBlockSegments(
                """[{"videoID":"BV1xx411c7mD","segments":[]}]""",
                target
            ).isEmpty()
        )
    }

    @Test
    fun `parser applies a multi-part submission only to its matching page`() {
        val response = """
            [
              {
                "videoID": "BV1Ha1cBJExg",
                "segments": [
                  {
                    "cid": "33638122342",
                    "category": "music_offtopic",
                    "actionType": "skip",
                    "segment": [150, 725],
                    "UUID": "part-one-only",
                    "videoDuration": 725
                  }
                ]
              }
            ]
        """.trimIndent()

        val firstPart = parseBiliSponsorBlockSegments(
            responseBody = response,
            target = BiliSponsorBlockTarget(
                bvid = "BV1Ha1cBJExg",
                cid = 33_638_122_342L,
                durationMs = 725_000L
            )
        )
        val secondPart = parseBiliSponsorBlockSegments(
            responseBody = response,
            target = BiliSponsorBlockTarget(
                bvid = "BV1Ha1cBJExg",
                cid = 33_717_159_668L,
                durationMs = 57_000L
            )
        )

        assertEquals(listOf("part-one-only"), firstPart.map { it.uuid })
        assertTrue(secondPart.isEmpty())
    }
}
