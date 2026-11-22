package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.NeriDesign

/** 平板双栏时标题区移到左栏, 步骤内容只放可操作的部分 */
internal val LocalStartupOnboardingTwoPane = staticCompositionLocalOf { false }

@Composable
internal fun StartupOnboardingLayout(
    header: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val compactHeight = maxHeight < 480.dp
        val twoPane = maxWidth >= 600.dp
        val compactWidth = maxWidth < 720.dp
        val compactSpacing = compactHeight || compactWidth
        val sidebarWidth = (maxWidth * 0.34f).coerceIn(
            if (compactWidth) 200.dp else 240.dp,
            360.dp
        )
        val horizontalPadding = if (compactSpacing) 20.dp else 32.dp
        val verticalPadding = if (compactHeight) 12.dp else 24.dp
        val compactHeaderHeight = (maxHeight * 0.25f).coerceAtMost(88.dp)
        CompositionLocalProvider(LocalStartupOnboardingTwoPane provides twoPane) {
            if (twoPane) {
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 1160.dp)
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    horizontalArrangement = Arrangement.spacedBy(if (compactSpacing) 24.dp else 40.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .width(sidebarWidth)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center
                        ) {
                            header()
                        }
                        Spacer(Modifier.height(16.dp))
                        actions()
                    }
                    StartupOnboardingContentPane(
                        compactSpacing = compactSpacing,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        content = content
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 680.dp)
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = verticalPadding)
                ) {
                    Column(
                        modifier = if (compactHeight) {
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = compactHeaderHeight)
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    ) {
                        header()
                    }
                    Spacer(Modifier.height(if (compactHeight) 8.dp else 14.dp))
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        content()
                    }
                    Spacer(Modifier.height(if (compactHeight) 8.dp else 14.dp))
                    actions()
                }
            }
        }
    }
}

@Composable
private fun StartupOnboardingContentPane(
    compactSpacing: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tokens = NeriDesign.tokens
    Surface(
        modifier = modifier,
        shape = tokens.dialogShape,
        // 半透明让章节光斑透出来, 内容区仍保持足够对比
        color = if (tokens.isClean) {
            colors.surfaceContainerLowest.copy(alpha = 0.78f)
        } else {
            colors.surfaceContainerLow.copy(alpha = 0.82f)
        },
        border = if (tokens.isClean) BorderStroke(0.5.dp, colors.outlineVariant) else null
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (compactSpacing) 16.dp else 24.dp)
        ) {
            content()
        }
    }
}
