package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Interests
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriTheme

@Immutable
internal data class StartupThemeInputs(
    val isDark: Boolean,
    val dynamicColor: Boolean,
    val seedColorHex: String,
    val paletteStyle: PaletteStyle,
    val colorSpec: ColorSpec.SpecVersion
)

@Composable
internal fun StartupStyleTheme(
    style: UiStyle,
    inputs: StartupThemeInputs,
    animateColors: Boolean = true,
    liquidGlass: Boolean = false,
    enhancedEffects: Boolean = false,
    content: @Composable () -> Unit
) {
    NeriTheme(
        followSystemDark = false,
        forceDark = inputs.isDark,
        dynamicColor = inputs.dynamicColor,
        seedColorHex = inputs.seedColorHex,
        paletteStyle = inputs.paletteStyle,
        colorSpec = inputs.colorSpec,
        uiStyle = style,
        liquidGlass = liquidGlass,
        enhancedEffects = enhancedEffects,
        animateColors = animateColors,
        content = content
    )
}

@Composable
internal fun startupStyleLabel(style: UiStyle): String = stringResource(
    when (style) {
        UiStyle.Material -> AppR.string.onboarding_style_material
        UiStyle.Clean -> AppR.string.onboarding_style_clean
    }
)

@Composable
internal fun StartupStyleStepBody(
    selectedStyle: UiStyle,
    themeInputs: StartupThemeInputs,
    onSelectStyle: (style: UiStyle, originInWindow: Offset, startRadiusPx: Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            listOf(UiStyle.Material, UiStyle.Clean).forEach { style ->
                StartupStylePreviewCard(
                    style = style,
                    selected = style == selectedStyle,
                    themeInputs = themeInputs,
                    onSelect = { origin, radius -> onSelectStyle(style, origin, radius) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        StartupStyleFeatures(selectedStyle)
        Text(
            text = stringResource(AppR.string.onboarding_style_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StartupStylePreviewCard(
    style: UiStyle,
    selected: Boolean,
    themeInputs: StartupThemeInputs,
    onSelect: (originInWindow: Offset, startRadiusPx: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    var cardOrigin by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val startRadius = with(density) { 24.dp.toPx() }
    val lift by animateFloatAsState(
        targetValue = if (selected) 1f else 0.95f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 360f),
        label = "startup_style_lift"
    )
    val ringColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.outlineVariant,
        animationSpec = tween(240),
        label = "startup_style_ring"
    )
    val frameShape = RoundedCornerShape(26.dp)
    val label = startupStyleLabel(style)
    // 手势协程只按 style 重启, 直接捕获 onSelect 会一直拿着第一次组合时的回调和当时的风格
    val latestOnSelect by rememberUpdatedState(onSelect)
    Column(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                cardOrigin = coordinates.positionInWindow()
                cardSize = coordinates.size
            }
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = label
                onClick {
                    onSelect(
                        cardOrigin + Offset(cardSize.width / 2f, cardSize.height / 2f),
                        startRadius
                    )
                    true
                }
            }
            .pointerInput(style) {
                detectTapGestures(
                    onPress = { position ->
                        // 按压时卡片向手指方向倾斜, 松手后回弹
                        val nx = (position.x / size.width - 0.5f) * 2f
                        val ny = (position.y / size.height - 0.5f) * 2f
                        scope.launch { tiltY.animateTo(nx * 7f, spring(0.7f, 500f)) }
                        scope.launch { tiltX.animateTo(-ny * 7f, spring(0.7f, 500f)) }
                        tryAwaitRelease()
                        scope.launch { tiltY.animateTo(0f, spring(0.45f, 260f)) }
                        scope.launch { tiltX.animateTo(0f, spring(0.45f, 260f)) }
                    },
                    onTap = { position -> latestOnSelect(cardOrigin + position, startRadius) }
                )
            }
            .graphicsLayer {
                rotationX = tiltX.value
                rotationY = tiltY.value
                scaleX = lift
                scaleY = lift
                cameraDistance = 14f * density.density
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.6f)
                .border(BorderStroke(if (selected) 2.5.dp else 1.dp, ringColor), frameShape)
                .padding(5.dp)
                .clip(RoundedCornerShape(21.dp))
        ) {
            StartupStyleTheme(style = style, inputs = themeInputs, animateColors = false) {
                StartupStyleMock()
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                enter = fadeIn(tween(160)) + scaleIn(spring(0.5f, 520f), initialScale = 0.3f),
                exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.5f)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = colors.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.primary else colors.onSurface
        )
    }
}

/** 不含文字的迷你界面骨架, 直接取所在主题的颜色和形状, 两种风格的差异一眼可见 */
@Composable
private fun StartupStyleMock() {
    val colors = MaterialTheme.colorScheme
    val tokens = NeriDesign.tokens
    val clean = tokens.isClean
    val progress by rememberInfiniteTransition(label = "startup_style_mock").animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 5_200, easing = LinearEasing), RepeatMode.Restart),
        label = "startup_style_mock_progress"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .clearAndSetSemantics { }
            .padding(horizontal = 9.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MockBar(width = 16.dp, height = 3.dp, color = colors.onSurface.copy(alpha = 0.55f))
            Spacer(Modifier.weight(1f))
            MockBar(width = 10.dp, height = 3.dp, color = colors.onSurface.copy(alpha = 0.4f))
        }
        Spacer(Modifier.height(9.dp))
        MockBar(width = 54.dp, height = 8.dp, color = colors.onSurface.copy(alpha = if (clean) 0.88f else 0.78f))
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(tokens.cardShape)
                .background(
                    Brush.linearGradient(
                        listOf(colors.primary, colors.tertiary.copy(alpha = 0.9f), colors.secondary)
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp)
                    .size(20.dp)
                    .background(colors.onPrimary.copy(alpha = 0.92f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = colors.primary, modifier = Modifier.size(13.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        MockSection(clean = clean) {
            repeat(3) { index ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(tokens.coverSmallShape)
                            .background(
                                if (index == 0) colors.primary.copy(alpha = 0.75f) else colors.secondaryContainer
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        MockBar(width = 48.dp - (index * 6).dp, height = 4.dp, color = colors.onSurface.copy(alpha = 0.7f))
                        MockBar(width = 30.dp, height = 3.dp, color = colors.onSurfaceVariant.copy(alpha = 0.45f))
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(tokens.miniPlayerShape)
                .background(if (clean) colors.surfaceContainerHigh.copy(alpha = 0.92f) else colors.secondaryContainer)
                .then(
                    if (clean) Modifier.border(0.5.dp, colors.outlineVariant, tokens.miniPlayerShape) else Modifier
                )
                .drawBehind {
                    val barHeight = 2.dp.toPx()
                    drawRect(
                        color = colors.primary,
                        topLeft = Offset(0f, size.height - barHeight),
                        size = androidx.compose.ui.geometry.Size(size.width * progress, barHeight)
                    )
                }
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .clip(tokens.coverSmallShape)
                        .background(colors.primary.copy(alpha = 0.8f))
                )
                Spacer(Modifier.width(5.dp))
                MockBar(width = 34.dp, height = 4.dp, color = colors.onSurface.copy(alpha = 0.65f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(4) { index ->
                val active = index == 0
                Box(
                    modifier = Modifier
                        .height(12.dp)
                        .width(if (active && !clean) 24.dp else 12.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                active && clean -> colors.primary
                                active -> colors.secondaryContainer
                                else -> colors.onSurfaceVariant.copy(alpha = 0.28f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (active && !clean) {
                        Box(Modifier.size(6.dp).background(colors.onSecondaryContainer, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun MockSection(clean: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tokens = NeriDesign.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.groupShape)
            .background(if (clean) colors.surfaceContainerLowest else colors.surfaceContainerHigh)
            .then(if (clean) Modifier.border(0.5.dp, colors.outlineVariant, tokens.groupShape) else Modifier)
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun MockBar(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, color: Color) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(CircleShape)
            .background(color)
    )
}

private data class StartupStyleFeature(val icon: ImageVector, val labelRes: Int)

private fun startupStyleFeatures(style: UiStyle): List<StartupStyleFeature> = when (style) {
    UiStyle.Material -> listOf(
        StartupStyleFeature(Icons.Rounded.ColorLens, AppR.string.onboarding_style_material_feature_color),
        StartupStyleFeature(Icons.Rounded.Interests, AppR.string.onboarding_style_material_feature_shape),
        StartupStyleFeature(Icons.Rounded.Layers, AppR.string.onboarding_style_material_feature_depth)
    )
    UiStyle.Clean -> listOf(
        StartupStyleFeature(Icons.Rounded.CropSquare, AppR.string.onboarding_style_clean_feature_neutral),
        StartupStyleFeature(Icons.Rounded.BlurOn, AppR.string.onboarding_style_clean_feature_glass),
        StartupStyleFeature(Icons.Rounded.AutoAwesome, AppR.string.onboarding_style_clean_feature_motion)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartupStyleFeatures(style: UiStyle) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    AnimatedContent(
        targetState = style,
        transitionSpec = {
            (fadeIn(tween(240, delayMillis = 60)) + scaleIn(spring(0.8f, 380f), initialScale = 0.94f))
                .togetherWith(fadeOut(tween(120)))
                .using(SizeTransform(clip = false))
        },
        label = "startup_style_features"
    ) { current ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(
                    when (current) {
                        UiStyle.Material -> AppR.string.onboarding_style_material_desc
                        UiStyle.Clean -> AppR.string.onboarding_style_clean_desc
                    }
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                startupStyleFeatures(current).forEach { feature ->
                    Surface(
                        shape = if (clean) CircleShape else RoundedCornerShape(10.dp),
                        color = if (clean) colors.surfaceContainerHigh else colors.tertiaryContainer,
                        contentColor = if (clean) colors.onSurface else colors.onTertiaryContainer,
                        border = if (clean) BorderStroke(0.5.dp, colors.outlineVariant) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(feature.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(feature.labelRes), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}
