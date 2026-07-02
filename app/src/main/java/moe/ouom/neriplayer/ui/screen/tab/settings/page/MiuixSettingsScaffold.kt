package moe.ouom.neriplayer.ui.screen.tab.settings.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight

private val MiuixCardShape = RoundedCornerShape(16.dp)
private val MiuixSettingsContentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
private val MiuixPageRowPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MiuixSettingsHomeScaffold(
    listState: LazyListState,
    topAppBarState: TopAppBarState,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            state = listState,
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 10.dp,
                bottom = 18.dp + miniPlayerHeight
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
internal fun MiuixSettingsPageGroupCard(
    pages: List<SettingsPage>,
    onPageClick: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pages.isEmpty()) {
        return
    }

    val cardBackgroundModifier = Modifier.settingsCardBackground(
        baseColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        alpha = 0.58f
    )

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MiuixCardShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = cardBackgroundModifier) {
            Column(modifier = Modifier.fillMaxWidth()) {
                pages.forEachIndexed { index, page ->
                    MiuixSettingsPageRow(
                        page = page,
                        onClick = { onPageClick(page) }
                    )
                    if (index != pages.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 74.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixSettingsPageRow(
    page: SettingsPage,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(onClick = onClick)
            .padding(MiuixPageRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = stringResource(page.titleRes),
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.86f)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = stringResource(page.titleRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(page.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MiuixSettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    listState: LazyListState,
    topAppBarState: TopAppBarState,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            state = listState,
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 10.dp,
                bottom = 18.dp + miniPlayerHeight
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
internal fun MiuixSettingsHeader(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    val cardBackgroundModifier = Modifier.settingsCardBackground(
        baseColor = MaterialTheme.colorScheme.primaryContainer,
        alpha = 0.48f
    )

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MiuixCardShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = cardBackgroundModifier) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun MiuixSettingsSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val cardBackgroundModifier = Modifier.settingsCardBackground(
        baseColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        alpha = 0.56f
    )

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MiuixCardShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = cardBackgroundModifier) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MiuixSettingsContentPadding)
            ) {
                content()
            }
        }
    }
}

private fun Modifier.settingsCardBackground(
    baseColor: Color,
    alpha: Float
): Modifier {
    return fillMaxWidth()
        .clip(MiuixCardShape)
        .background(baseColor.copy(alpha = alpha), MiuixCardShape)
}

internal fun LazyListScope.miuixSettingsSectionCardItem(
    key: Any,
    content: @Composable () -> Unit
) {
    item(key = key) {
        MiuixSettingsSectionCard(
            modifier = Modifier.animateItem(),
            content = content
        )
    }
}
