package moe.ouom.neriplayer.ui.onboarding

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.common.locale.getDisplayName
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriDesign

@Composable
internal fun StartupLanguageStepBody(
    selectedLanguage: LanguageManager.Language,
    onSelectLanguage: (LanguageManager.Language) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(AppR.string.onboarding_language_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                text = stringResource(AppR.string.onboarding_language_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
        LanguageManager.Language.entries.forEach { language ->
            StartupLanguageCard(
                language = language,
                selected = language == selectedLanguage,
                onClick = { onSelectLanguage(language) }
            )
        }
    }
}

@Composable
private fun StartupLanguageCard(
    language: LanguageManager.Language,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val context = LocalContext.current
    // Material 选中后卡片圆角放大, 形状本身就是反馈
    val corner by animateDpAsState(
        targetValue = if (selected) 28.dp else 18.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "startup_language_corner"
    )
    val shape = if (clean) ContinuousRoundedCornerShape(20.dp) else RoundedCornerShape(corner)
    val container by animateColorAsState(
        targetValue = when {
            clean -> colors.surfaceContainerHigh
            selected -> colors.secondaryContainer
            else -> colors.surfaceContainerLow
        },
        animationSpec = tween(260),
        label = "startup_language_container"
    )
    val borderColor by animateColorAsState(
        targetValue = if (clean && selected) colors.primary else colors.outlineVariant.copy(alpha = if (clean) 1f else 0f),
        animationSpec = tween(220),
        label = "startup_language_border"
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = shape,
        color = container,
        border = BorderStroke(if (clean && selected) 1.5.dp else 0.5.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StartupLanguageGlyph(language = language, selected = selected)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = language.getDisplayName(context),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected && !clean) colors.onSecondaryContainer else colors.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = startupLanguageSubtitle(language),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(160)) + scaleIn(spring(0.5f, 500f), initialScale = 0.4f),
                exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.6f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun StartupLanguageGlyph(language: LanguageManager.Language, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val background by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.surfaceContainerHighest,
        animationSpec = tween(240),
        label = "startup_language_glyph"
    )
    val foreground = if (selected) colors.onPrimary else colors.onSurfaceVariant
    val shape = when {
        clean -> ContinuousRoundedCornerShape(14.dp)
        selected -> remember { StartupScallopShape(lobes = 8, depth = 0.14f) }
        else -> CircleShape
    }
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(background, shape),
        contentAlignment = Alignment.Center
    ) {
        when (language) {
            LanguageManager.Language.SYSTEM -> Icon(
                imageVector = Icons.Rounded.Translate,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(22.dp)
            )
            else -> Text(
                text = stringResource(
                    if (language == LanguageManager.Language.CHINESE) {
                        AppR.string.onboarding_language_glyph_zh
                    } else {
                        AppR.string.onboarding_language_glyph_en
                    }
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = foreground
            )
        }
    }
}

@Composable
private fun startupLanguageSubtitle(language: LanguageManager.Language): String = when (language) {
    LanguageManager.Language.CHINESE -> stringResource(AppR.string.onboarding_language_sample_zh)
    LanguageManager.Language.ENGLISH -> stringResource(AppR.string.onboarding_language_sample_en)
    LanguageManager.Language.SYSTEM -> {
        // 应用语言会改写默认 Locale, 系统语言要从系统资源取
        val systemLocale = remember {
            Resources.getSystem().configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.ROOT
        }
        stringResource(
            AppR.string.onboarding_language_system_current,
            systemLocale.getDisplayName(systemLocale)
        )
    }
}
