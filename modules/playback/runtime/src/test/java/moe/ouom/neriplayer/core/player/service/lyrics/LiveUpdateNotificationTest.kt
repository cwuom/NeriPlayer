package moe.ouom.neriplayer.core.player.service.lyrics

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import org.mockito.Mockito.mock
import android.text.Spanned
import android.text.style.TtsSpan
import androidx.core.app.NotificationCompat
import java.time.Duration
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LiveUpdateNotificationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val playingContent = LiveUpdateContent(
        songKey = "netease:1",
        title = "晚风吹过旧街角",
        text = "示例歌曲 - Neri QA",
        subText = null,
        chipText = "晚风吹过旧街角",
        playing = true,
        progress = LiveUpdateProgress(max = 240, value = 28),
    )
    private val frame = LiveUpdateFrame(
        songKey = "netease:1",
        songTitle = "示例歌曲",
        artist = "Neri QA",
        lyric = "晚风吹过旧街角",
        translation = null,
        playing = true,
        positionMs = 30_000L,
        durationMs = 240_000L,
    )
    private val promotion = FakeLiveUpdatePromotionPort()
    private var bridge: LiveLyricNotificationBridge? = null

    @After
    fun clearBridge() {
        bridge?.setEnabled(false)
        idle()
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `playing notification meets every promotable characteristic`() {
        val notification = build(playingContent, chronometerBaseWallMs = 970_000L, accentColor = ACCENT)

        assertTrue(NotificationCompat.isRequestPromotedOngoing(notification))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(0, notification.flags and Notification.FLAG_GROUP_SUMMARY)
        assertEquals("晚风吹过旧街角", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(Notification.ProgressStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_COLORIZED))
        assertNull(notification.contentView)
        assertNull(notification.bigContentView)
        assertNull(notification.headsUpContentView)
        assertEquals("晚风吹过旧街角", NotificationCompat.getShortCriticalText(notification))
        assertEquals(LiveLyricNotificationBridge.CHANNEL_ID, notification.channelId)
    }

    @Test
    fun `playing notification carries progress, a ticking chronometer and titled controls`() {
        val notification = build(playingContent, chronometerBaseWallMs = 970_000L, accentColor = ACCENT, timeoutMs = 90_000L)

        assertEquals(28, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(240, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(970_000L, notification.`when`)
        assertEquals(90_000L, notification.timeoutAfter)
        assertEquals(ACCENT, notification.color)
        val actions = checkNotNull(notification.actions)
        assertEquals(listOf(PREVIOUS_GLYPH, PAUSE_GLYPH, NEXT_GLYPH), actions.map { it.title.toString() })
        assertEquals(
            listOf(text(R.string.player_previous), text(R.string.player_pause), text(R.string.player_next)),
            actions.map { spokenLabel(it.title) },
        )
        assertTrue(actions.all { it.actionIntent != null })
    }

    @Test
    fun `progress bar and its glowing tracker follow the lifted cover color`() {
        val dullCover = 0xFF706098.toInt()
        val lifted = checkNotNull(resolveLiveUpdateAccentColor(dullCover))

        val notification = build(playingContent, chronometerBaseWallMs = 970_000L, accentColor = dullCover)

        val style = progressStyle(notification)
        assertEquals(lifted, notification.color)
        assertEquals(lifted, style.progressSegments.single().color)
        assertNotNull(style.progressTrackerIcon)
    }

    @Test
    fun `songs without a cover color use the system accent for the bar and tracker`() {
        val systemAccent = context.getColor(android.R.color.system_accent1_500)

        val notification = build(playingContent, chronometerBaseWallMs = 970_000L, accentColor = null)

        val style = progressStyle(notification)
        assertEquals(systemAccent, notification.color)
        assertEquals(systemAccent, style.progressSegments.single().color)
        assertNotNull(style.progressTrackerIcon)
    }

    @Test
    fun `paused notification offers play and stops the chronometer`() {
        val notification = build(playingContent.copy(playing = false), chronometerBaseWallMs = null)

        val play = checkNotNull(notification.actions)[1].title
        assertEquals(PLAY_GLYPH, play.toString())
        assertEquals(text(R.string.player_play), spokenLabel(play))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
    }

    @Test
    fun `unknown durations fall back to big text and legacy devices request colorized`() {
        val notification = build(playingContent.copy(progress = null), chronometerBaseWallMs = null, legacyColorized = true)

        assertEquals(Notification.BigTextStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_COLORIZED))
    }

    @Test
    fun `a paused frame never creates a live update on its own`() {
        val bridge = enabledBridge()

        bridge.publish(frame.copy(playing = false))
        idle()

        assertNull(postedNotification())
    }

    @Test
    fun `pausing keeps the live update with a play button then collapses it`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()
        assertNotNull(postedNotification())

        bridge.publish(frame.copy(playing = false))
        idleFor(1_000L)
        val paused = checkNotNull(postedNotification())
        assertEquals("示例歌曲", paused.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(text(R.string.player_play), spokenLabel(checkNotNull(paused.actions)[1].title))

        idleFor(LiveLyricNotificationBridge.PAUSED_LINGER_MS)
        assertNull(postedNotification())
    }

    @Test
    fun `resuming within the linger window cancels the pending collapse`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()
        bridge.publish(frame.copy(playing = false))
        idleFor(10_000L)

        bridge.publish(frame.copy(positionMs = 30_000L))
        idleFor(LiveLyricNotificationBridge.PAUSED_LINGER_MS)

        assertEquals(text(R.string.player_pause), spokenLabel(checkNotNull(checkNotNull(postedNotification()).actions)[1].title))
    }

    @Test
    fun `player frames within the same second do not repost`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()
        val first = checkNotNull(postedNotification())

        bridge.publish(frame.copy(positionMs = 30_400L))
        idleFor(500L)

        assertSame(first, postedNotification())
    }

    @Test
    fun `progress advances every second between player frames`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()
        assertEquals(30, postedProgress())

        idleFor(1_100L)
        assertEquals(31, postedProgress())
        idleFor(1_000L)
        assertEquals(32, postedProgress())
    }

    @Test
    fun `off-beat player frames do not shift the once-per-second rhythm`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()

        // 播放器推送比墙钟慢 50 ms，节拍仍按计时器锚点在整秒处触发
        idleFor(700L)
        bridge.publish(frame.copy(positionMs = 30_650L))
        idleFor(340L)

        assertEquals(31, postedProgress())
    }

    @Test
    fun `a tick shortly after a lyric change is not held back by the update throttle`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()

        idleFor(700L)
        bridge.publish(frame.copy(lyric = "新的一句", positionMs = 30_700L))
        idleFor(340L)

        assertEquals("新的一句", checkNotNull(postedNotification()).extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(31, postedProgress())
    }

    @Test
    fun `progress stops advancing when the player goes quiet`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()

        idleFor(10_000L)

        assertEquals(32, postedProgress())
    }

    @Test
    fun `a paused frame stops the progress ticks`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()

        bridge.publish(frame.copy(playing = false, positionMs = 30_500L))
        idleFor(3_000L)

        assertEquals(30, postedProgress())
    }

    @Test
    fun `song changes update in place and artwork arriving later is attached`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()

        bridge.publish(frame.copy(songKey = "netease:2", songTitle = "第二首歌", lyric = null))
        idleFor(1_000L)
        val changed = checkNotNull(postedNotification())
        assertEquals("第二首歌", changed.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(changed.getLargeIcon())

        bridge.setArtwork("netease:2", Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), coverSource = null)
        idleFor(1_000L)
        assertNotNull(checkNotNull(postedNotification()).getLargeIcon())
    }

    @Test
    fun `a notification left behind by a previous process is removed`() {
        manager.notify(
            LiveLyricNotificationBridge.NOTIFICATION_ID,
            Notification.Builder(context, "stale").setSmallIcon(android.R.drawable.ic_media_play).build(),
        )

        bridge = LiveLyricNotificationBridge(context, promotion)
        idle()

        assertNull(postedNotification())
    }

    @Test
    fun `a user dismissal is respected until the song changes`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()
        val deleteIntent = checkNotNull(checkNotNull(postedNotification()).deleteIntent)

        manager.cancel(LiveLyricNotificationBridge.NOTIFICATION_ID)
        deleteIntent.send()
        idle()
        bridge.publish(frame.copy(lyric = "路灯把影子拉长", positionMs = 34_000L))
        idleFor(1_000L)
        assertNull(postedNotification())

        bridge.publish(frame.copy(songKey = "netease:2", songTitle = "第二首歌", lyric = null))
        idleFor(1_000L)
        assertEquals("第二首歌", checkNotNull(postedNotification()).extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `a late dismissal of the previous song does not hide the next one`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()
        val firstSongDelete = checkNotNull(checkNotNull(postedNotification()).deleteIntent)

        idleFor(500L)
        bridge.publish(frame.copy(songKey = "netease:2", songTitle = "第二首歌", lyric = null, positionMs = 0L))
        idle()
        firstSongDelete.send()
        idle()
        bridge.publish(frame.copy(songKey = "netease:2", songTitle = "第二首歌", lyric = "新的一句", positionMs = 1_000L))
        idleFor(500L)

        assertEquals("新的一句", checkNotNull(postedNotification()).extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `switching songs starts the new song's progress from its own position`() {
        val bridge = enabledBridge()
        bridge.publish(frame.copy(positionMs = 1_000L))
        idle()

        idleFor(400L)
        bridge.publish(frame.copy(songKey = "netease:2", songTitle = "第二首歌", lyric = null, positionMs = 0L))
        idle()

        assertEquals(0, postedProgress())
    }

    @Test
    fun `a refresh long after the last player frame does not run past the extrapolation limit`() {
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()

        idleFor(3_500L)
        bridge.setArtwork(frame.songKey, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), coverSource = null)
        idle()

        val posted = checkNotNull(postedNotification())
        assertNotNull(posted.getLargeIcon())
        assertEquals(32, posted.extras.getInt(Notification.EXTRA_PROGRESS))
    }

    @Test
    fun `promotion that cannot be confirmed yet is checked again on later posts`() {
        promotion.promoted = null
        val bridge = enabledBridge()
        bridge.publish(frame)
        idleFor(LiveLyricNotificationBridge.PROMOTION_CHECK_DELAY_MS + 100L)
        assertNotNull(postedNotification())

        promotion.promoted = false
        idleFor(1_200L)

        assertNull(postedNotification())
        assertTrue(LiveUpdateNotificationStatus.systemDeclinedPromotion.value)
    }

    @Test
    fun `an unknown promotion result is rechecked even when nothing is reposted`() {
        promotion.promoted = null
        val bridge = enabledBridge()
        // 时长未知没有进度节拍，歌词也不变，之后不会再有发布
        bridge.publish(frame.copy(durationMs = 0L))
        idleFor(LiveLyricNotificationBridge.PROMOTION_CHECK_DELAY_MS + 100L)
        assertNotNull(postedNotification())

        promotion.promoted = false
        idleFor(LiveLyricNotificationBridge.PROMOTION_CHECK_DELAY_MS)

        assertNull(postedNotification())
    }

    @Test
    fun `promotion checks stop after a bounded number of unknown results`() {
        promotion.promoted = null
        val bridge = enabledBridge()
        bridge.publish(frame.copy(durationMs = 0L))

        idleFor(LiveLyricNotificationBridge.PROMOTION_CHECK_DELAY_MS * 20)

        assertEquals(LiveLyricNotificationBridge.PROMOTION_CHECK_MAX_UNKNOWN, promotion.promotionQueries)
        assertNotNull(postedNotification())
    }

    @Test
    fun `non-1x playback moves the bar at the playback rate without a wall-clock chronometer`() {
        val bridge = enabledBridge()
        bridge.publish(frame.copy(playbackSpeed = 2f))
        idle()
        assertFalse(checkNotNull(postedNotification()).extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(30, postedProgress())

        idleFor(1_100L)

        assertEquals(32, postedProgress())
    }

    @Test
    fun `a cover delivered while switched off is colored once the live update starts`() {
        val loads = AtomicInteger()
        installPresentation { loads.incrementAndGet(); ACCENT }
        val bridge = LiveLyricNotificationBridge(context, promotion).also { this.bridge = it }
        bridge.setArtwork(frame.songKey, null, coverSource = "cover://neri")
        idle()
        assertEquals(0, loads.get())

        bridge.setEnabled(true)
        bridge.publish(frame)
        awaitMain { postedNotification()?.color == ACCENT }

        assertEquals(1, loads.get())
    }

    @Test
    fun `an album color request cancelled by a collapse is retried when playback continues`() {
        val loads = AtomicInteger()
        val neverAnswered = CompletableDeferred<Int?>()
        installPresentation { if (loads.incrementAndGet() == 1) neverAnswered.await() else ACCENT }
        val bridge = enabledBridge()
        bridge.publish(frame)
        bridge.setArtwork(frame.songKey, null, coverSource = "cover://neri")
        idle()
        awaitMain { loads.get() == 1 }

        bridge.clear()
        idle()
        bridge.publish(frame)
        awaitMain { postedNotification()?.color == ACCENT }

        assertEquals(2, loads.get())
    }

    @Test
    fun `buffering keeps the pause control but freezes progress and the chronometer`() {
        val bridge = enabledBridge()
        val buffering = frame.copy(advancing = false)
        bridge.publish(buffering)
        idle()

        // 播放器缓冲期间仍按节拍推送同一位置，进度不能随墙钟前进后再回跳
        repeat(3) {
            idleFor(1_000L)
            bridge.publish(buffering)
        }
        idleFor(1_000L)

        val posted = checkNotNull(postedNotification())
        assertEquals(30, posted.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(posted.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(text(R.string.player_pause), spokenLabel(checkNotNull(posted.actions)[1].title))
    }

    @Test
    fun `nothing is posted while the system disallows promotion for the app`() {
        promotion.canPost = false
        val bridge = enabledBridge()

        bridge.publish(frame)
        idleFor(1_000L)
        assertNull(postedNotification())

        // 用户在系统设置里打开后，下一帧即可出现
        promotion.canPost = true
        bridge.publish(frame.copy(positionMs = 31_000L))
        idleFor(1_000L)
        assertNotNull(postedNotification())
    }

    @Test
    fun `an unpromoted notification is withdrawn instead of duplicating the media notification`() {
        promotion.promoted = false
        val bridge = enabledBridge()
        bridge.publish(frame)
        idle()
        assertNotNull(postedNotification())

        idleFor(LiveLyricNotificationBridge.PROMOTION_CHECK_DELAY_MS)
        assertNull(postedNotification())
        assertTrue(LiveUpdateNotificationStatus.systemDeclinedPromotion.value)

        bridge.publish(frame.copy(songKey = "netease:2"))
        idleFor(1_000L)
        assertNull(postedNotification())

        // 重新开启开关会清掉记忆并再试一次
        promotion.promoted = true
        bridge.setEnabled(false)
        bridge.setEnabled(true)
        idle()
        assertFalse(LiveUpdateNotificationStatus.systemDeclinedPromotion.value)
        bridge.publish(frame)
        idleFor(1_000L)
        assertNotNull(postedNotification())
    }

    @Test
    fun `controls route to the playback service actions`() {
        val actions = checkNotNull(build(playingContent, chronometerBaseWallMs = null).actions)

        val intents = actions.map { shadowOf(it.actionIntent).savedIntent.action }
        assertEquals(
            listOf(AudioPlayerService.ACTION_PREV, AudioPlayerService.ACTION_PAUSE, AudioPlayerService.ACTION_NEXT),
            intents,
        )
    }

    private fun build(
        content: LiveUpdateContent,
        chronometerBaseWallMs: Long?,
        accentColor: Int? = null,
        timeoutMs: Long = 0L,
        legacyColorized: Boolean = false,
    ): Notification = buildLiveUpdateNotification(
        context,
        content = content,
        chronometerBaseWallMs = chronometerBaseWallMs,
        artwork = null,
        accentColor = accentColor,
        timeoutMs = timeoutMs,
        legacyColorizedPromotion = legacyColorized,
    )

    private fun installPresentation(loadAccent: suspend () -> Int?) {
        PlayerTestEnvironment.install(
            presentation = object : PlayerPresentationHost by mock(PlayerPresentationHost::class.java) {
                override suspend fun loadCoverAccentColor(context: Context, source: String): Int? = loadAccent()
                override fun peekCoverAccentColor(source: String): Int? = null
            }
        )
    }

    /** 取色在 IO 线程完成后才回到主线程，结果还要经过发布节流；边推进主线程时钟边等待 */
    private fun awaitMain(condition: () -> Boolean) {
        repeat(AWAIT_ATTEMPTS) {
            idleFor(AWAIT_STEP_MS)
            if (condition()) return
            Thread.sleep(AWAIT_STEP_MS)
        }
        fail("condition not met on the main looper")
    }

    private fun postedProgress(): Int = checkNotNull(postedNotification()).extras.getInt(Notification.EXTRA_PROGRESS)

    private fun spokenLabel(title: CharSequence): String? =
        (title as? Spanned)?.getSpans(0, title.length, TtsSpan::class.java)?.singleOrNull()?.args?.getString(TtsSpan.ARG_TEXT)

    private fun progressStyle(notification: Notification): Notification.ProgressStyle =
        Notification.Builder.recoverBuilder(context, notification).style as Notification.ProgressStyle

    private fun enabledBridge(): LiveLyricNotificationBridge =
        LiveLyricNotificationBridge(context, promotion).also {
            bridge = it
            it.setEnabled(true)
            idle()
        }

    private fun text(resId: Int): String = LanguageManager.applyLanguage(context).getString(resId)

    private fun postedNotification(): Notification? =
        shadowOf(manager).getNotification(LiveLyricNotificationBridge.NOTIFICATION_ID)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private companion object {
        const val ACCENT = 0xFF3482FF.toInt()
        const val AWAIT_ATTEMPTS = 200
        const val AWAIT_STEP_MS = 10L
        const val PREVIOUS_GLYPH = "\u23EE\uFE0E"
        const val PAUSE_GLYPH = "\u2759\u2759"
        const val PLAY_GLYPH = "\u25B6\uFE0E"
        const val NEXT_GLYPH = "\u23ED\uFE0E"
    }
}
