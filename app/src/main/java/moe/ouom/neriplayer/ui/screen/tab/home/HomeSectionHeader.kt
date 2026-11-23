package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.compose.runtime.saveable.rememberSaveable
import moe.ouom.neriplayer.ui.theme.NeriMotion
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import moe.ouom.neriplayer.common.R as CoreCommonR
import java.util.Calendar
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.remember
import android.text.format.DateFormat
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.NeriDesign

/** LargeTopAppBar 自带的标题水平内边距，首页大标题据此补齐到卡片左边缘 */
internal val TopAppBarTitleInset = 16.dp

/**
 * 分区标题图标与网格卡片左边缘对齐。
 */
@Composable
internal fun SectionHeader(icon: ImageVector, title: String, onClick: (() -> Unit)? = null) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    if (NeriDesign.isClean) {
        // clean style carries hierarchy by weight alone; the accent stays reserved for actions
        if (onClick == null) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)
            )
            return
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = clickableModifier.padding(top = 18.dp, bottom = 10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        return
    }
    // Material: 标题用正文墨色压住层级, 图标收进小色调圆点, 主色只做点缀
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onClick != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .then(clickableModifier),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 首页副标题的时段问候, 按本地小时划分; 深夜不再寒暄, 改成提醒休息 */
internal enum class HomeGreetingPeriod { Morning, Noon, Afternoon, Evening, LateEvening, Midnight }

internal fun homeGreetingPeriod(hourOfDay: Int): HomeGreetingPeriod = when (hourOfDay) {
    in 5..10 -> HomeGreetingPeriod.Morning
    // 临近中午到午饭后说午安, 不说早上好或下午好
    in 11..12 -> HomeGreetingPeriod.Noon
    in 13..17 -> HomeGreetingPeriod.Afternoon
    in 18..22 -> HomeGreetingPeriod.Evening
    23 -> HomeGreetingPeriod.LateEvening
    else -> HomeGreetingPeriod.Midnight
}

/** 白天的问候按会话随机带一点语气, 深夜两档保持平静不加语气符号 */
internal fun homeGreetingUsesTone(period: HomeGreetingPeriod): Boolean =
    period != HomeGreetingPeriod.LateEvening && period != HomeGreetingPeriod.Midnight

private val CjkBeforeDigit = Regex("(?<=[\\u4e00-\\u9fff])(?=[0-9])")
private val DigitBeforeCjk = Regex("(?<=[0-9])(?=[\\u4e00-\\u9fff])")

/** 中文与数字之间补一个空格, 例如 11月22日 -> 11 月 22 日 */
internal fun spaceCjkAndDigits(text: String): String =
    text.replace(CjkBeforeDigit, " ").replace(DigitBeforeCjk, " ")

/** 距下一个整点的毫秒数; 问候按小时变, 日期在午夜变, 整点刷新就够了 */
internal fun homeGreetingNextRefreshDelayMs(nowMs: Long): Long {
    val next = Calendar.getInstance().apply {
        timeInMillis = nowMs
        add(Calendar.HOUR_OF_DAY, 1)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return (next.timeInMillis - nowMs).coerceAtLeast(1L)
}

/**
 * 停在首页时问候也要跟着时间走: 回到前台先刷新一次, 之后每个整点再刷新;
 * 页面暂停或离开组合时协程随之取消
 */
@Composable
private fun rememberHomeGreetingClock(): Calendar {
    val lifecycleOwner = LocalLifecycleOwner.current
    val nowMs by produceState(System.currentTimeMillis(), lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(homeGreetingNextRefreshDelayMs(value))
            }
        }
    }
    return remember(nowMs) { Calendar.getInstance().apply { timeInMillis = nowMs } }
}

/** "晚上好～ · 11 月 22 日 星期日"; 语气在一次会话内固定, 不会每次重组都变 */
@Composable
internal fun rememberHomeGreeting(): String {
    val locale = LocalConfiguration.current.locales[0]
    val now = rememberHomeGreetingClock()
    val period = homeGreetingPeriod(now.get(Calendar.HOUR_OF_DAY))
    val toneSeed = rememberSaveable { (0..2).random() }
    val greeting = stringResource(
        when (period) {
            HomeGreetingPeriod.Morning -> CoreCommonR.string.home_greeting_morning
            HomeGreetingPeriod.Noon -> CoreCommonR.string.home_greeting_noon
            HomeGreetingPeriod.Afternoon -> CoreCommonR.string.home_greeting_afternoon
            HomeGreetingPeriod.Evening -> CoreCommonR.string.home_greeting_evening
            HomeGreetingPeriod.LateEvening -> CoreCommonR.string.home_greeting_night
            HomeGreetingPeriod.Midnight -> CoreCommonR.string.home_greeting_midnight
        }
    )
    val tone = if (homeGreetingUsesTone(period)) {
        when (toneSeed) {
            1 -> stringResource(CoreCommonR.string.home_greeting_tone_soft)
            2 -> stringResource(CoreCommonR.string.home_greeting_tone_bright)
            else -> ""
        }
    } else {
        ""
    }
    val dayKey = now.get(Calendar.DAY_OF_YEAR)
    val date = remember(locale, dayKey) {
        val day = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(now.time)
        val weekday = SimpleDateFormat("EEEE", locale).format(now.time)
        if (locale.language == "zh") spaceCjkAndDigits("$day $weekday") else "$weekday, $day"
    }
    return "$greeting$tone · $date"
}

/**
 * 大标题下方的时段问候: 上移贴住大标题, 高度和透明度跟随标题栏折叠一起收起;
 * 折叠进度只在布局和绘制阶段读取, 滚动时不触发重组
 */
@Composable
internal fun HomeGreetingLine(
    text: String,
    collapsedFraction: () -> Float,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (NeriDesign.isClean) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val expanded = 1f - collapsedFraction().coerceIn(0f, 1f)
                val pullUp = HomeGreetingPullUp.roundToPx()
                val height = ((placeable.height - pullUp) * expanded).roundToInt().coerceAtLeast(0)
                layout(placeable.width, height) {
                    placeable.placeWithLayer(0, (-pullUp * expanded).roundToInt()) {
                        alpha = expanded
                    }
                }
            }
    )
}

private val HomeGreetingPullUp = 14.dp

/** 横滑分页的页码点: 当前页拉长成主色胶囊, 切页时宽度和颜色平滑过渡 */
@Composable
internal fun HomePagerDots(
    pageCount: Int,
    currentPage: () -> Int,
    modifier: Modifier = Modifier
) {
    val clean = NeriDesign.isClean
    val active = if (clean) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val selected = currentPage()
        repeat(pageCount) { index ->
            val isSelected = index == selected
            val width by animateDpAsState(
                targetValue = if (isSelected) 18.dp else 6.dp,
                animationSpec = NeriMotion.smooth(),
                label = "home_pager_dot_width"
            )
            val color by animateColorAsState(
                targetValue = if (isSelected) active else inactive,
                animationSpec = NeriMotion.smooth(),
                label = "home_pager_dot_color"
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = 6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
