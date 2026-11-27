package moe.ouom.neriplayer.core.player.service.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateNowPlayingContentTest {
    private val frame = LiveUpdateFrame(
        songKey = "netease:1",
        songTitle = "示例歌曲",
        artist = "Neri QA",
        lyric = null,
        translation = null,
        playing = true,
        positionMs = 30_000L,
        durationMs = 240_000L,
    )

    @Test
    fun `playing lyric takes the title and the chip while the song moves to the body`() {
        val content = resolveLiveUpdateContent(frame.copy(lyric = "  晚风吹过旧街角\n路灯把影子拉长 "), "Neri", FixedWidthMeasurer)

        assertEquals("晚风吹过旧街角 路灯把影子拉长", content.title)
        assertEquals("示例歌曲 - Neri QA", content.text)
        assertNull(content.subText)
        assertEquals("晚风吹过…", content.chipText)
        assertTrue(content.playing)
    }

    @Test
    fun `translation goes to the body and the song moves to the header`() {
        val content = resolveLiveUpdateContent(
            frame.copy(lyric = "Hello", translation = " 你好 "),
            "Neri",
            FixedWidthMeasurer,
        )

        assertEquals("Hello", content.title)
        assertEquals("你好", content.text)
        assertEquals("示例歌曲 - Neri QA", content.subText)
    }

    @Test
    fun `songs without a current lyric show song info and keep a chip`() {
        val content = resolveLiveUpdateContent(frame.copy(translation = "孤立的翻译"), "Neri", FixedWidthMeasurer)

        assertEquals("示例歌曲", content.title)
        assertEquals("Neri QA", content.text)
        assertNull(content.subText)
        assertEquals("示例歌曲", content.chipText)
    }

    @Test
    fun `paused frames drop the lyric so the lock screen does not freeze on one line`() {
        val content = resolveLiveUpdateContent(frame.copy(lyric = "晚风吹过旧街角", playing = false), "Neri", FixedWidthMeasurer)

        assertEquals("示例歌曲", content.title)
        assertEquals("Neri QA", content.text)
        assertFalse(content.playing)
    }

    @Test
    fun `blank metadata falls back to the app name without a dangling separator`() {
        val content = resolveLiveUpdateContent(frame.copy(songTitle = " ", artist = "\t", lyric = "line"), "Neri", FixedWidthMeasurer)

        assertEquals("line", content.title)
        assertEquals("Neri", content.text)
        assertEquals("Neri", resolveLiveUpdateContent(frame.copy(songTitle = "", artist = ""), "Neri", FixedWidthMeasurer).title)
    }

    @Test
    fun `progress moves in whole seconds so it steps together with the chronometer`() {
        assertEquals(LiveUpdateProgress(max = 240, value = 30), resolveLiveUpdateProgress(30_000L, 240_000L))
        assertEquals(LiveUpdateProgress(max = 240, value = 30), resolveLiveUpdateProgress(30_999L, 240_000L))
        assertEquals(LiveUpdateProgress(max = 240, value = 31), resolveLiveUpdateProgress(31_000L, 240_000L))
        assertEquals(LiveUpdateProgress(max = 30, value = 5), resolveLiveUpdateProgress(5_500L, 30_000L))
    }

    @Test
    fun `position is extrapolated between player frames only while playing and within limits`() {
        assertEquals(31_200L, extrapolateLiveUpdatePosition(frame, elapsedSinceFrameMs = 1_200L))
        assertEquals(30_000L + LIVE_UPDATE_MAX_EXTRAPOLATION_MS, extrapolateLiveUpdatePosition(frame, 60_000L))
        assertEquals(30_000L, extrapolateLiveUpdatePosition(frame, elapsedSinceFrameMs = -500L))
        assertEquals(30_000L, extrapolateLiveUpdatePosition(frame.copy(playing = false), 1_200L))
        assertEquals(240_000L, extrapolateLiveUpdatePosition(frame.copy(positionMs = 239_500L), 1_200L))
        assertEquals(31_200L, extrapolateLiveUpdatePosition(frame.copy(durationMs = 0L), 1_200L))
        // 缓冲时仍是播放意图，但引擎没在走，不能凭时间外推
        assertEquals(30_000L, extrapolateLiveUpdatePosition(frame.copy(advancing = false), 1_200L))
    }

    @Test
    fun `while the chronometer runs progress is read from its clock`() {
        assertEquals(30_400L, resolveLiveUpdateClockPosition(anchorAt(970_000L), 1_000_400L, frame, 30_400L))
        assertEquals(0L, resolveLiveUpdateClockPosition(anchorAt(1_000_500L), 1_000_000L, frame, 30_000L))
        assertEquals(29_000L, resolveLiveUpdateClockPosition(null, 1_000_000L, frame, 29_000L))
        assertEquals(
            240_000L,
            resolveLiveUpdateClockPosition(anchorAt(0L), 999_000L, frame.copy(positionMs = 239_000L), 240_000L),
        )
    }

    @Test
    fun `a stale frame cannot push the clock past the extrapolation limit`() {
        // 节拍或其他刷新迟到时，旧锚点仍在容差内，但显示位置必须停在最后一帧之后 2.5 秒
        assertEquals(
            30_000L + LIVE_UPDATE_MAX_EXTRAPOLATION_MS,
            resolveLiveUpdateClockPosition(anchorAt(970_000L), 1_003_500L, frame, 32_500L),
        )
    }

    @Test
    fun `playback speed scales extrapolation, the clock and the anchor`() {
        val doubled = frame.copy(playbackSpeed = 2f)
        assertEquals(32_400L, extrapolateLiveUpdatePosition(doubled, elapsedSinceFrameMs = 1_200L))
        assertEquals(30_000L + 2 * LIVE_UPDATE_MAX_EXTRAPOLATION_MS, extrapolateLiveUpdatePosition(doubled, 60_000L))
        assertEquals(31_200L, extrapolateLiveUpdatePosition(frame.copy(playbackSpeed = 0f), 1_200L))

        val anchor = checkNotNull(resolveLiveUpdateClockAnchor(null, 50_000L, 1_700_000_050_000L, 30_000L, true, speed = 2f))
        assertEquals(LiveUpdateClockAnchor(elapsedMs = 35_000L, wallMs = 1_700_000_035_000L, speed = 2f), anchor)
        assertFalse(anchor.drivesChronometer)
        assertEquals(32_000L, resolveLiveUpdateClockPosition(anchor, 51_000L, doubled, 32_000L))

        // 倍速变化后不沿用旧锚点
        val normal = resolveLiveUpdateClockAnchor(anchor, 51_000L, 1_700_000_051_000L, 32_000L, true, speed = 1f)
        assertEquals(LiveUpdateClockAnchor(elapsedMs = 19_000L, wallMs = 1_700_000_019_000L), normal)
        assertTrue(checkNotNull(normal).drivesChronometer)
    }

    @Test
    fun `the clock anchor is reused within tolerance and maps to wall time when replaced`() {
        val first = checkNotNull(resolveLiveUpdateClockAnchor(null, 50_000L, 1_700_000_050_000L, 30_000L, playing = true))
        assertEquals(LiveUpdateClockAnchor(elapsedMs = 20_000L, wallMs = 1_700_000_020_000L), first)

        // 播放器位置比时钟慢 400 ms 仍复用原锚点，墙钟读数的毫秒抖动不影响结果
        assertSame(first, resolveLiveUpdateClockAnchor(first, 60_000L, 1_700_000_060_007L, 39_600L, playing = true))

        val seeked = resolveLiveUpdateClockAnchor(first, 60_000L, 1_700_000_060_000L, 90_000L, playing = true)
        assertEquals(LiveUpdateClockAnchor(elapsedMs = -30_000L, wallMs = 1_699_999_970_000L), seeked)
        assertNull(resolveLiveUpdateClockAnchor(first, 60_000L, 1_700_000_060_000L, 40_000L, playing = false))
    }

    private fun anchorAt(elapsedMs: Long) = LiveUpdateClockAnchor(elapsedMs = elapsedMs, wallMs = elapsedMs + 1_000_000L)

    @Test
    fun `refreshes are aligned to the next whole second`() {
        assertEquals(1_000L, msUntilNextLiveUpdateProgressTick(30_000L))
        assertEquals(750L, msUntilNextLiveUpdateProgressTick(30_250L))
        assertEquals(1L, msUntilNextLiveUpdateProgressTick(30_999L))
        assertEquals(1_000L, msUntilNextLiveUpdateProgressTick(-40L))
    }

    @Test
    fun `progress clamps out of range positions and skips unknown durations`() {
        assertEquals(LiveUpdateProgress(max = 240, value = 240), resolveLiveUpdateProgress(999_000L, 240_000L))
        assertEquals(LiveUpdateProgress(max = 240, value = 0), resolveLiveUpdateProgress(-5_000L, 240_000L))
        assertEquals(LiveUpdateProgress(max = 1, value = 0), resolveLiveUpdateProgress(100L, 400L))
        assertNull(resolveLiveUpdateProgress(10_000L, 0L))
        assertNull(resolveLiveUpdateContent(frame.copy(durationMs = -1L), "Neri", FixedWidthMeasurer).progress)
    }

    @Test
    fun `chronometer anchor survives normal ticks and moves on seek or resume`() {
        val now = 1_000_000L
        val base = resolveLiveUpdateChronometerBase(null, now, positionMs = 30_000L, playing = true)
        assertEquals(970_000L, base)

        // 进度节拍带来的小抖动不改锚点，系统计时器自行走字
        assertEquals(base, resolveLiveUpdateChronometerBase(base, now + 1_400L, positionMs = 30_000L, playing = true))
        // 跳转后重新锚定
        assertEquals(now - 90_000L, resolveLiveUpdateChronometerBase(base, now, positionMs = 90_000L, playing = true))
        assertNull(resolveLiveUpdateChronometerBase(base, now, positionMs = 30_000L, playing = false))
        // 2× 时锚点按播放位置折算，容差也按播放位置计算
        assertEquals(now - 15_000L, resolveLiveUpdateChronometerBase(null, now, positionMs = 30_000L, true, speed = 2f))
        assertEquals(now - 15_000L, resolveLiveUpdateChronometerBase(now - 15_000L, now + 600L, 31_000L, true, 2f))
    }

    @Test
    fun `timeout follows the wall-clock length of the rest of the song at slow speeds`() {
        assertEquals(
            420_000L + LIVE_UPDATE_PLAYING_TIMEOUT_MARGIN_MS,
            resolveLiveUpdateTimeoutMs(true, 30_000L, 240_000L, pausedLingerMs = 60_000L, speed = 0.5f),
        )
    }

    @Test
    fun `timeout outlives the app's own paused collapse and the remaining song while playing`() {
        assertEquals(
            60_000L + LIVE_UPDATE_PAUSED_TIMEOUT_GRACE_MS,
            resolveLiveUpdateTimeoutMs(false, 10_000L, 240_000L, pausedLingerMs = 60_000L),
        )
        assertEquals(
            200_000L + LIVE_UPDATE_PLAYING_TIMEOUT_MARGIN_MS,
            resolveLiveUpdateTimeoutMs(true, 40_000L, 240_000L, pausedLingerMs = 60_000L),
        )
        assertEquals(0L, resolveLiveUpdateTimeoutMs(true, 40_000L, 0L, pausedLingerMs = 60_000L))
    }

    @Test
    fun `only Android 16 0 needs the legacy colorized promotion request`() {
        assertTrue(requiresLegacyColorizedPromotion(sdkInt = 36, sdkIntFull = 3_600_000))
        assertFalse(requiresLegacyColorizedPromotion(sdkInt = 36, sdkIntFull = 3_600_001))
        assertFalse(requiresLegacyColorizedPromotion(sdkInt = 37, sdkIntFull = 3_700_000))
        assertFalse(requiresLegacyColorizedPromotion(sdkInt = 35, sdkIntFull = 3_500_000))
    }
}
