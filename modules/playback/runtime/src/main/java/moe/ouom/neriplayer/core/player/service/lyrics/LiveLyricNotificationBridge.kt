package moe.ouom.neriplayer.core.player.service.lyrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.TtsSpan
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.service.AudioPlayerService

/** 供设置页诊断 Live Update：系统是否提升了通知只有发布后才知道 */
object LiveUpdateNotificationStatus {
    const val CHANNEL_ID = "neriplayer_live_lyric_updates_v1"

    private val declined = MutableStateFlow(false)
    val systemDeclinedPromotion: StateFlow<Boolean> = declined.asStateFlow()

    internal fun report(declinedBySystem: Boolean) {
        declined.value = declinedBySystem
    }
}

/** 提升资格与已发布内容的系统查询；抽出来便于在没有真实 SystemUI 的测试环境替换 */
internal interface LiveUpdatePromotionPort {
    /** @return null 表示无法判断，按允许处理 */
    fun canPostPromoted(): Boolean?

    /** @return 已发布通知是否带 FLAG_PROMOTED_ONGOING；null 表示找不到或无法判断 */
    fun isPromoted(notificationId: Int): Boolean?

    /** @return 系统当前保存的该通知的发布序号；null 表示通知不在或无法判断 */
    fun shownPublication(notificationId: Int): Long?
}

internal class AndroidLiveUpdatePromotionPort(private val manager: NotificationManager) : LiveUpdatePromotionPort {
    override fun canPostPromoted(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return runCatching { manager.canPostPromotedNotifications() }.getOrNull()
    }

    override fun isPromoted(notificationId: Int): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return runCatching {
            manager.activeNotifications
                .firstOrNull { it.id == notificationId && it.tag == null }
                ?.let { it.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0 }
        }.getOrNull()
    }

    override fun shownPublication(notificationId: Int): Long? = runCatching {
        manager.activeNotifications
            .firstOrNull { it.id == notificationId && it.tag == null }
            ?.notification
            ?.let(::liveUpdatePublication)
    }.getOrNull()
}

private class LiveUpdatePublication(val sequence: Long, val postedElapsedMs: Long)

/** 没有发布序号的通知（例如旧版本残留）按 -1 处理，总会被视为不是最新一次发布 */
internal fun liveUpdatePublication(notification: Notification): Long =
    notification.extras.getLong(LiveLyricNotificationBridge.EXTRA_PUBLICATION, -1L)

/**
 * 以 Android 16 Live Update（提升的常驻通知）展示正在播放：状态栏胶囊显示歌词或歌名，
 * 通知本体带封面、进度、计时与播放控制。MediaStyle 不具备提升资格，所以独立于媒体通知发布；
 * 系统不提升时撤回，避免在通知栏留下一条与媒体通知重复的普通通知。
 */
internal class LiveLyricNotificationBridge @VisibleForTesting internal constructor(
    context: Context,
    private val promotion: LiveUpdatePromotionPort,
) {
    constructor(context: Context) : this(
        context,
        AndroidLiveUpdatePromotionPort(
            context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ),
    )

    internal companion object {
        const val TAG = "LiveLyricNotification"
        const val CHANNEL_ID = LiveUpdateNotificationStatus.CHANNEL_ID
        const val NOTIFICATION_ID = 0x4e504c52
        // 系统按包限制每秒 5 次入队且与媒体通知共享额度，超额的更新会被静默丢弃
        const val MIN_UPDATE_INTERVAL_MS = 400L
        // 暂停后保留一段时间供用户直接恢复，随后收起，避免锁屏与息屏长期占位
        const val PAUSED_LINGER_MS = 60_000L
        // FLAG_PROMOTED_ONGOING 由系统在入队时异步写入，稍后再核对
        const val PROMOTION_CHECK_DELAY_MS = 1_000L
        // 查不到通知（入队尚未完成等）时每秒重试，次数有限，避免查询失败时一直空转
        const val PROMOTION_CHECK_MAX_UNKNOWN = 5
        // 超出入队频率的更新会被系统静默丢弃，notify() 照常返回；暂停这类之后不再变化的状态一旦被丢，
        // 卡片会一直停在播放态，暂停键只能再发暂停。每次发布带上序号，发布停下后核对系统实际保存的是哪一次
        const val EXTRA_PUBLICATION = "moe.ouom.neriplayer.live_update.publication"
        // 比逐秒节拍长，持续播放时不会每秒都查询一次
        const val PUBLICATION_CHECK_DELAY_MS = 1_500L
        // 节拍会不断推迟核对；持续播放时至少每 5 s 核对一次，避免更新被持续丢弃时进度一直不动
        const val PUBLICATION_CHECK_MAX_DEFER_MS = 5_000L
        // 入队是异步的（通知助手还会延迟约 200 ms），刚发布就读到旧内容不算丢弃
        const val PUBLICATION_SETTLE_MS = 600L
        const val PUBLICATION_MAX_RESENDS = 3
        // 划掉事件以歌曲区分：每首歌的 deleteIntent 是独立令牌，迟到的旧事件不会算到新歌头上
        private const val DISMISS_URI_SCHEME = "neriplayer-live-update"
        // 越过整秒后再刷新，避免外推位置还差几毫秒没进到下一格
        private const val PROGRESS_TICK_SLACK_MS = 20L
        // 逐秒节拍不走 400 ms 节流，否则紧跟在歌词换行之后的节拍会被推迟，进度忽快忽慢；
        // 仍保留最小间隔，与媒体通知合计远低于每秒 5 次
        private const val PROGRESS_TICK_MIN_GAP_MS = 250L
        private const val CHIP_MEASURE_TEXT_SIZE_PX = 100f
    }

    /** 由服务的封面管线提供，避免这里再解析一遍本地、下载与远程封面 */
    private data class Artwork(
        val songKey: String,
        val bitmap: Bitmap?,
        val coverSource: String?,
        val accentColor: Int?,
    )

    /** 一次发布的完整指纹；与上次相同时不重发 */
    private data class PostedState(
        val content: LiveUpdateContent,
        val clockAnchor: LiveUpdateClockAnchor?,
        val artwork: Bitmap?,
        val accentColor: Int?,
    )

    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())
    // 用系统默认字体按 em 实测，汉字、拉丁字母与 emoji 的宽度差异都能如实反映
    private val chipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = CHIP_MEASURE_TEXT_SIZE_PX }
    private val chipMeasurer = LiveUpdateTextMeasurer { chipPaint.measureText(it) / CHIP_MEASURE_TEXT_SIZE_PX }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dismissAction = "${appContext.packageName}.action.LIVE_UPDATE_DISMISSED"
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == dismissAction) onUserDismissed(intent.data?.schemeSpecificPart)
        }
    }
    private var dismissReceiverRegistered = false
    private var accentJob: Job? = null
    // 已取过色（成功或失败）的封面；失败的不再逐帧重试
    private var accentAttemptedCover: String? = null
    private val pausedDismiss = Runnable {
        pausedDismissScheduled = false
        clearImpl()
    }
    private var pausedDismissScheduled = false
    private val promotionCheck = Runnable(::verifyPromotion)
    private var promotionCheckPending = false
    private var promotionVerified = false
    private var promotionUnknownChecks = 0
    private val publicationCheck = Runnable(::verifyPublication)
    private var publication = 0L
    // 尚未核对的发布，最新的在末尾；只保留最近一次已落定的和之后的
    private val unconfirmedPublications = ArrayDeque<LiveUpdatePublication>()
    private var unconfirmedSinceElapsedMs: Long? = null
    private var publicationResends = 0
    private val progressTick = Runnable(::dispatchProgressTick)
    private var enabled = false
    private var latestFrame: LiveUpdateFrame? = null
    private var latestFrameElapsedMs = 0L
    private var artwork: Artwork? = null
    private var lastPosted: PostedState? = null
    private var pendingDispatch: Runnable? = null
    private var lastDispatchElapsedMs = 0L
    // 用户划掉的歌曲在切歌前不再重发，这是 Live Update 的使用要求
    private var dismissedSongKey: String? = null
    private var systemDeclinedPromotion = false
    private var permissionDeniedLogged = false

    init {
        // 上个进程被杀时残留的常驻通知不会自动消失
        mainHandler.post { if (!enabled) notificationManager.cancel(NOTIFICATION_ID) }
    }

    // 公共入口统一切到主线程；设置收集器运行在 IO 线程
    fun setEnabled(enabled: Boolean) {
        mainHandler.post { setEnabledImpl(enabled) }
    }

    fun publish(frame: LiveUpdateFrame) {
        mainHandler.post { publishImpl(frame) }
    }

    fun setArtwork(songKey: String, bitmap: Bitmap?, coverSource: String?) {
        mainHandler.post { setArtworkImpl(songKey, bitmap, coverSource) }
    }

    fun clear() {
        mainHandler.post(::clearImpl)
    }

    private fun setEnabledImpl(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        // 重新开启视为用户重新选择，清掉划掉与未提升的记忆以便重试
        dismissedSongKey = null
        systemDeclinedPromotion = false
        permissionDeniedLogged = false
        LiveUpdateNotificationStatus.report(false)
        if (enabled) {
            registerDismissReceiver()
        } else {
            clearImpl()
            unregisterDismissReceiver()
        }
    }

    private fun publishImpl(frame: LiveUpdateFrame) {
        if (!enabled) return
        if (dismissedSongKey != null && dismissedSongKey != frame.songKey) dismissedSongKey = null
        latestFrame = frame
        latestFrameElapsedMs = SystemClock.elapsedRealtime()
        ensureAccentColor()
        scheduleDispatch()
    }

    private fun setArtworkImpl(songKey: String, bitmap: Bitmap?, coverSource: String?) {
        val current = artwork
        if (current?.songKey == songKey && current.bitmap === bitmap && current.coverSource == coverSource) {
            // 取色可能因收起被取消，同一封面再次到达时补上
            ensureAccentColor()
            return
        }
        val retainedAccent = current?.accentColor?.takeIf { coverSource != null && current.coverSource == coverSource }
        val accent = retainedAccent ?: coverSource?.let(::peekAccentColor)
        artwork = Artwork(songKey, bitmap, coverSource, accent)
        accentJob?.cancel()
        ensureAccentColor()
        onArtworkChanged(songKey)
    }

    /** 只为正在展示的歌曲取色；关闭期间到达、或被收起取消的取色在下一帧补上 */
    private fun ensureAccentColor() {
        val current = artwork ?: return
        val coverSource = current.coverSource ?: return
        val load = shouldLoadLiveUpdateAccent(
            enabled = enabled,
            artworkSongKey = current.songKey,
            frameSongKey = latestFrame?.songKey,
            hasAccent = current.accentColor != null,
            loading = accentJob?.isActive == true,
            alreadyAttempted = accentAttemptedCover == coverSource,
        )
        if (load) loadAccentColor(current.songKey, coverSource)
    }

    private fun onArtworkChanged(songKey: String) {
        if (enabled && latestFrame?.songKey == songKey) scheduleDispatch()
    }

    private fun peekAccentColor(coverSource: String): Int? =
        runCatching { PlayerDependencies.presentation.peekCoverAccentColor(coverSource) }.getOrNull()

    /** 与播放页共用封面取色缓存，进度条与按钮随专辑色变化 */
    private fun loadAccentColor(songKey: String, coverSource: String) {
        accentJob?.cancel()
        accentJob = scope.launch {
            val color = withContext(Dispatchers.IO) {
                runCatching { PlayerDependencies.presentation.loadCoverAccentColor(appContext, coverSource) }
                    .getOrNull()
            }
            accentAttemptedCover = coverSource
            color ?: return@launch
            val current = artwork
            if (current?.songKey != songKey || current.coverSource != coverSource) return@launch
            artwork = current.copy(accentColor = color)
            onArtworkChanged(songKey)
        }
    }

    private fun clearImpl() {
        cancelPendingDispatch()
        cancelPausedDismiss()
        mainHandler.removeCallbacks(progressTick)
        cancelPromotionCheck()
        cancelPublicationCheck()
        accentJob?.cancel()
        accentJob = null
        latestFrame = null
        lastPosted = null
        lastDispatchElapsedMs = 0L
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun onUserDismissed(songKey: String?) {
        // 划掉的是旧歌而新歌已经发出时，不能把新歌算作被划掉
        if (!enabled || songKey == null || songKey != lastPosted?.content?.songKey) return
        dismissedSongKey = songKey
        cancelPendingDispatch()
        cancelPausedDismiss()
        mainHandler.removeCallbacks(progressTick)
        cancelPromotionCheck()
        cancelPublicationCheck()
        lastPosted = null
    }

    private fun scheduleDispatch() {
        val nowMs = SystemClock.elapsedRealtime()
        val remainingMs = if (lastDispatchElapsedMs == 0L) {
            0L
        } else {
            (MIN_UPDATE_INTERVAL_MS - (nowMs - lastDispatchElapsedMs)).coerceAtLeast(0L)
        }
        if (remainingMs == 0L) {
            cancelPendingDispatch()
            dispatchLatest()
            return
        }
        if (pendingDispatch != null) return
        val dispatch = Runnable {
            pendingDispatch = null
            dispatchLatest()
        }
        pendingDispatch = dispatch
        mainHandler.postDelayed(dispatch, remainingMs)
    }

    private fun dispatchLatest(resend: Boolean = false) {
        mainHandler.removeCallbacks(progressTick)
        val received = latestFrame ?: return
        if (!enabled || received.songKey == dismissedSongKey) return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val elapsedSinceFrameMs = nowElapsedMs - latestFrameElapsedMs
        val extrapolatedMs = extrapolateLiveUpdatePosition(received, elapsedSinceFrameMs)
        val anchor = resolveLiveUpdateClockAnchor(
            previous = lastPosted?.takeIf { it.content.songKey == received.songKey }?.clockAnchor,
            nowElapsedMs = nowElapsedMs,
            nowWallMs = System.currentTimeMillis(),
            positionMs = extrapolatedMs,
            playing = received.clockRunning,
            speed = received.effectiveSpeed,
        )
        val frame = received.copy(
            positionMs = resolveLiveUpdateClockPosition(anchor, nowElapsedMs, received, extrapolatedMs)
        )
        postFrame(frame, anchor, resend)
        scheduleProgressTick(frame, anchor, nowElapsedMs, elapsedSinceFrameMs)
    }

    private fun dispatchProgressTick() {
        if (SystemClock.elapsedRealtime() - lastDispatchElapsedMs < PROGRESS_TICK_MIN_GAP_MS) {
            scheduleDispatch()
            return
        }
        cancelPendingDispatch()
        dispatchLatest()
    }

    private fun postFrame(frame: LiveUpdateFrame, clockAnchor: LiveUpdateClockAnchor?, resend: Boolean) {
        val previous = lastPosted
        if (previous == null && !canStartLiveUpdate(frame)) return
        val content = resolveLiveUpdateContent(
            frame,
            fallbackTitle = localizedContext().getString(R.string.app_name),
            chipMeasurer = chipMeasurer,
            chipMaxWidthEm = chipMaxWidthEm(),
        )
        val currentArtwork = artwork?.takeIf { it.songKey == frame.songKey }
        val state = PostedState(
            content = content,
            clockAnchor = clockAnchor,
            artwork = currentArtwork?.bitmap,
            accentColor = currentArtwork?.accentColor,
        )
        updatePausedDismiss(frame.playing)
        // 重发时系统保存的是更早的内容，与上次发布相同也必须再发
        if (!resend) {
            if (state == previous) return
            // 熄屏时只有息屏显示可见，纯进度变化不值得唤起 SystemUI 重新布局
            if (previous != null && isOnlyProgressChange(previous, state) && powerManager?.isInteractive == false) {
                return
            }
        }
        val postedElapsedMs = SystemClock.elapsedRealtime()
        lastDispatchElapsedMs = postedElapsedMs
        val sequence = publication + 1
        val notification = buildLiveUpdateNotification(
            appContext,
            content = content,
            chronometerBaseWallMs = state.clockAnchor?.takeIf { it.drivesChronometer }?.wallMs,
            artwork = state.artwork,
            accentColor = state.accentColor,
            timeoutMs = resolveLiveUpdateTimeoutMs(
                playing = frame.playing,
                positionMs = frame.positionMs,
                durationMs = frame.durationMs,
                pausedLingerMs = PAUSED_LINGER_MS,
                speed = frame.effectiveSpeed,
            ),
            deleteIntent = dismissPendingIntent(content.songKey),
        )
        notification.extras.putLong(EXTRA_PUBLICATION, sequence)
        try {
            ensureChannel()
            notificationManager.notify(NOTIFICATION_ID, notification)
            publication = sequence
            lastPosted = state
            armPromotionCheck(firstPost = previous == null)
            recordPublication(LiveUpdatePublication(sequence, postedElapsedMs))
        } catch (securityException: SecurityException) {
            Log.w(TAG, "Unable to post Live Update notification", securityException)
        }
    }

    /** 亮屏播放时按锚点逐秒推进进度条；播放器长时间没有新位置时停下，等下一次推送再继续 */
    private fun scheduleProgressTick(
        frame: LiveUpdateFrame,
        anchor: LiveUpdateClockAnchor?,
        nowElapsedMs: Long,
        elapsedSinceFrameMs: Long,
    ) {
        anchor ?: return
        val delayMs = msUntilNextLiveUpdateProgressTick(nowElapsedMs - anchor.elapsedMs) + PROGRESS_TICK_SLACK_MS
        val ticking = lastPosted != null && frame.durationMs > 0L &&
            elapsedSinceFrameMs + delayMs <= LIVE_UPDATE_MAX_EXTRAPOLATION_MS && powerManager?.isInteractive != false
        if (ticking) mainHandler.postDelayed(progressTick, delayMs)
    }

    /**
     * 暂停态只更新已显示的通知，冷启动恢复的暂停队列不应凭空弹出 Live Update；
     * 系统明确不允许或不提升时也不新建，避免与媒体通知重复。
     */
    private fun canStartLiveUpdate(frame: LiveUpdateFrame): Boolean {
        if (!frame.playing || systemDeclinedPromotion) return false
        if (promotion.canPostPromoted() != false) return true
        if (!permissionDeniedLogged) {
            permissionDeniedLogged = true
            Log.i(TAG, "Live Update promotion is not allowed for this app; skip posting")
        }
        return false
    }

    /** 首次发布后核对提升结果；查不到时有限次重试（自行排期，也随后续发布补排），确认提升后不再核对 */
    private fun armPromotionCheck(firstPost: Boolean) {
        if (firstPost) {
            promotionVerified = false
            promotionUnknownChecks = 0
        }
        if (promotionVerified || promotionCheckPending || promotionUnknownChecks >= PROMOTION_CHECK_MAX_UNKNOWN) return
        promotionCheckPending = true
        mainHandler.postDelayed(promotionCheck, PROMOTION_CHECK_DELAY_MS)
    }

    private fun cancelPromotionCheck() {
        mainHandler.removeCallbacks(promotionCheck)
        promotionCheckPending = false
    }

    private fun verifyPromotion() {
        promotionCheckPending = false
        if (lastPosted == null) return
        when (promotion.isPromoted(NOTIFICATION_ID)) {
            true -> promotionVerified = true
            // 内容不变时不会再发布，重试不能依赖下一次发布，自行排下一次核对
            null -> {
                promotionUnknownChecks++
                armPromotionCheck(firstPost = false)
            }
            false -> {
                Log.i(TAG, "System did not promote the Live Update; withdraw it to avoid a duplicate notification")
                systemDeclinedPromotion = true
                LiveUpdateNotificationStatus.report(true)
                clearImpl()
            }
        }
    }

    private fun recordPublication(posted: LiveUpdatePublication) {
        if (unconfirmedSinceElapsedMs == null) unconfirmedSinceElapsedMs = posted.postedElapsedMs
        unconfirmedPublications.addLast(posted)
        while (unconfirmedPublications.size > 1 &&
            posted.postedElapsedMs - unconfirmedPublications[1].postedElapsedMs >= PUBLICATION_SETTLE_MS
        ) {
            unconfirmedPublications.removeFirst()
        }
        schedulePublicationCheck()
    }

    /** 截止前随新发布推迟；到期后不再推迟，歌词行再密也会按时核对 */
    private fun schedulePublicationCheck() {
        val unconfirmedSince = unconfirmedSinceElapsedMs ?: return
        val latest = unconfirmedPublications.lastOrNull() ?: return
        val checkAtElapsedMs = minOf(
            latest.postedElapsedMs + PUBLICATION_CHECK_DELAY_MS,
            unconfirmedSince + PUBLICATION_CHECK_MAX_DEFER_MS,
        )
        mainHandler.removeCallbacks(publicationCheck)
        mainHandler.postDelayed(
            publicationCheck,
            (checkAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
        )
    }

    private fun cancelPublicationCheck() {
        mainHandler.removeCallbacks(publicationCheck)
        unconfirmedPublications.clear()
        unconfirmedSinceElapsedMs = null
        publicationResends = 0
    }

    private fun verifyPublication() {
        if (lastPosted == null) return cancelPublicationCheck()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        // 刚发布的还在入队，核对最近一次已落定的发布；序号递增，系统保存的不早于它即说明它已送达
        val settled = unconfirmedPublications.lastOrNull { nowElapsedMs - it.postedElapsedMs >= PUBLICATION_SETTLE_MS }
        if (settled == null) {
            val oldest = unconfirmedPublications.firstOrNull() ?: return cancelPublicationCheck()
            mainHandler.postDelayed(publicationCheck, PUBLICATION_SETTLE_MS - (nowElapsedMs - oldest.postedElapsedMs))
            return
        }
        val shown = promotion.shownPublication(NOTIFICATION_ID)
        // 通知已不在时交给 deleteIntent 判断是否被划掉，这里重发可能把用户划掉的卡片又发回来
        if (shown == null) return cancelPublicationCheck()
        if (shown >= settled.sequence) return confirmPublicationsUpTo(shown)
        if (publicationResends >= PUBLICATION_MAX_RESENDS) {
            Log.w(TAG, "Live Update still shows publication $shown instead of ${settled.sequence}; stop re-sending")
            return cancelPublicationCheck()
        }
        publicationResends++
        Log.w(TAG, "System dropped Live Update publication ${settled.sequence} (showing $shown); re-sending")
        // 重发带着最新内容，之前的发布都被它取代；不清掉的话下一次核对会立即拿旧发布再判一次丢失
        unconfirmedPublications.clear()
        dispatchLatest(resend = true)
    }

    /** 之后的发布还没落定时开始新一轮核对，紧跟在密集更新后的暂停也不会漏掉 */
    private fun confirmPublicationsUpTo(shown: Long) {
        unconfirmedPublications.removeAll { it.sequence <= shown }
        publicationResends = 0
        val next = unconfirmedPublications.firstOrNull() ?: return cancelPublicationCheck()
        unconfirmedSinceElapsedMs = next.postedElapsedMs
        schedulePublicationCheck()
    }

    /** 胶囊宽度按 dp 封顶而文字随系统字号放大，大字号下可容纳的 em 数相应减少 */
    private fun chipMaxWidthEm(): Float =
        LIVE_UPDATE_CHIP_MAX_WIDTH_EM / appContext.resources.configuration.fontScale.coerceAtLeast(1f)

    private fun isOnlyProgressChange(previous: PostedState, next: PostedState): Boolean =
        previous.copy(content = previous.content.copy(progress = next.content.progress)) == next

    private fun updatePausedDismiss(playing: Boolean) {
        if (playing) {
            cancelPausedDismiss()
        } else if (!pausedDismissScheduled) {
            pausedDismissScheduled = true
            mainHandler.postDelayed(pausedDismiss, PAUSED_LINGER_MS)
        }
    }

    private fun cancelPausedDismiss() {
        mainHandler.removeCallbacks(pausedDismiss)
        pausedDismissScheduled = false
    }

    private fun cancelPendingDispatch() {
        pendingDispatch?.let(mainHandler::removeCallbacks)
        pendingDispatch = null
    }

    private fun dismissPendingIntent(songKey: String): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        LIVE_UPDATE_REQUEST_CODE_BASE,
        Intent(dismissAction)
            .setPackage(appContext.packageName)
            .setData(Uri.fromParts(DISMISS_URI_SCHEME, songKey, null)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun registerDismissReceiver() {
        if (dismissReceiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            dismissReceiver,
            IntentFilter(dismissAction).apply { addDataScheme(DISMISS_URI_SCHEME) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        dismissReceiverRegistered = true
    }

    private fun unregisterDismissReceiver() {
        if (!dismissReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(dismissReceiver) }
        dismissReceiverRegistered = false
    }

    private fun localizedContext(): Context = LanguageManager.applyLanguage(appContext)

    private fun ensureChannel() {
        val localizedContext = localizedContext()
        val channelName = localizedContext.getString(R.string.notification_channel_live_lyrics)
        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
            ?: NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        channel.name = channelName
        channel.description = localizedContext.getString(R.string.notification_channel_live_lyrics_description)
        notificationManager.createNotificationChannel(channel)
    }
}

/**
 * 构建满足 Live Update 提升条件的通知：常驻、有标题、ProgressStyle/BigTextStyle、
 * 无自定义视图且不是组摘要；按钮必须带标题，API 37 会丢弃无标题按钮。
 */
@VisibleForTesting
internal fun buildLiveUpdateNotification(
    context: Context,
    content: LiveUpdateContent,
    chronometerBaseWallMs: Long?,
    artwork: Bitmap?,
    accentColor: Int?,
    timeoutMs: Long,
    deleteIntent: PendingIntent? = null,
    legacyColorizedPromotion: Boolean = currentDeviceRequiresLegacyColorizedPromotion(),
): Notification {
    val localized = LanguageManager.applyLanguage(context)
    val progressColor = resolveLiveUpdateAccentColor(accentColor) ?: liveUpdateSystemAccentColor(context)
    val builder = NotificationCompat.Builder(context, LiveLyricNotificationBridge.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_small)
        .setContentTitle(content.title)
        .setContentText(content.text)
        .setSubText(content.subText)
        .setStyle(liveUpdateStyle(content, progressColor))
        .setLargeIcon(artwork)
        .setContentIntent(liveUpdateContentIntent(context))
        .setDeleteIntent(deleteIntent)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setAutoCancel(false)
        .setLocalOnly(true)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setShortCriticalText(content.chipText)
        .setRequestPromotedOngoing(true)
        .addAction(
            liveUpdateAction(context,
                liveUpdateGlyphTitle(LIVE_UPDATE_PREVIOUS_GLYPH, localized.getString(R.string.player_previous)),
                AudioPlayerService.ACTION_PREV, 1)
        )
        .addAction(
            if (content.playing) {
                liveUpdateAction(context,
                    liveUpdateGlyphTitle(LIVE_UPDATE_PAUSE_GLYPH, localized.getString(R.string.player_pause)),
                    AudioPlayerService.ACTION_PAUSE, 2)
            } else {
                liveUpdateAction(context,
                    liveUpdateGlyphTitle(LIVE_UPDATE_PLAY_GLYPH, localized.getString(R.string.player_play)),
                    AudioPlayerService.ACTION_PLAY, 3)
            }
        )
        .addAction(
            liveUpdateAction(context,
                liveUpdateGlyphTitle(LIVE_UPDATE_NEXT_GLYPH, localized.getString(R.string.player_next)),
                AudioPlayerService.ACTION_NEXT, 4)
        )
    progressColor?.let(builder::setColor)
    if (legacyColorizedPromotion) builder.setColorized(true)
    if (chronometerBaseWallMs != null) {
        builder.setWhen(chronometerBaseWallMs).setShowWhen(true).setUsesChronometer(true)
    } else {
        builder.setShowWhen(false).setUsesChronometer(false)
    }
    if (timeoutMs > 0L) builder.setTimeoutAfter(timeoutMs)
    return builder.build()
}

private fun currentDeviceRequiresLegacyColorizedPromotion(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
        requiresLegacyColorizedPromotion(Build.VERSION.SDK_INT, Build.VERSION.SDK_INT_FULL)

private fun liveUpdateStyle(content: LiveUpdateContent, progressColor: Int?): NotificationCompat.Style {
    val progress = content.progress ?: return NotificationCompat.BigTextStyle().bigText(content.title)
    val segment = NotificationCompat.ProgressStyle.Segment(progress.max)
    val style = NotificationCompat.ProgressStyle()
    if (progressColor != null) {
        segment.setColor(progressColor)
        style.setProgressTrackerIcon(IconCompat.createWithBitmap(createLiveUpdateTrackerBitmap(progressColor)))
    }
    return style
        .setProgressSegments(listOf(segment))
        .setStyledByProgress(true)
        .setProgress(progress.value)
}

private fun liveUpdateContentIntent(context: Context): PendingIntent? =
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(
            context,
            LIVE_UPDATE_REQUEST_CODE_BASE,
            it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

/** 暂停用两根竖线避开彩色表情，读屏时通过 TtsSpan 朗读本地化操作名称 */
private fun liveUpdateGlyphTitle(glyph: String, spokenLabel: String): CharSequence =
    SpannableString(glyph).apply {
        setSpan(TtsSpan.TextBuilder(spokenLabel).build(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

private fun liveUpdateAction(
    context: Context,
    title: CharSequence,
    action: String,
    requestOffset: Int,
): NotificationCompat.Action = NotificationCompat.Action.Builder(
    // 标题已经包含播放符号，部分系统会把单独提供的图标也画出来
    null,
    title,
    PendingIntent.getService(
        context,
        LIVE_UPDATE_REQUEST_CODE_BASE + requestOffset,
        Intent(context, AudioPlayerService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
).build()

private const val LIVE_UPDATE_REQUEST_CODE_BASE = 0x4c55
// U+FE0E 要求文本样式，避免部分系统字体把媒体符号画成彩色 emoji
private const val LIVE_UPDATE_PREVIOUS_GLYPH = "\u23EE\uFE0E"
private const val LIVE_UPDATE_PAUSE_GLYPH = "\u2759\u2759"
private const val LIVE_UPDATE_PLAY_GLYPH = "\u25B6\uFE0E"
private const val LIVE_UPDATE_NEXT_GLYPH = "\u23ED\uFE0E"
