package moe.ouom.neriplayer.ui.screen.tab.settings.navigation

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.navigation/SettingsNavigationSearch
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassScene
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsPageGroupCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsHomePageGroups
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsSearchEntry
import moe.ouom.neriplayer.ui.screen.tab.settings.page.backTargetPage

internal data class PendingSettingsSearchNavigation(
    val page: SettingsPage,
    val targetId: String,
    val requestId: Int
)

internal fun isForwardSettingsPageTransition(
    initialPage: SettingsPage?,
    targetPage: SettingsPage?
): Boolean {
    if (targetPage == null) return false
    if (initialPage == null) return true
    if (targetPage.backTargetPage() == initialPage) return true
    if (initialPage.backTargetPage() == targetPage) return false
    return targetPage.ordinal >= initialPage.ordinal
}

internal fun shouldHandoffGlass(isolated: Boolean, transitionRunning: Boolean): Boolean =
    isolated && transitionRunning

internal fun shouldShowActiveGlassScene(
    isolated: Boolean,
    selectedPage: SettingsPage?,
    activePage: SettingsPage?
): Boolean = isolated || selectedPage == activePage

internal fun settingsHomeSelectedPage(page: SettingsPage?): SettingsPage? =
    page?.backTargetPage() ?: page

internal fun LazyListScope.settingsHomePageItems(navigation: SettingsNavigationState) {
    settingsHomeSearchFieldItem(navigation)
    settingsHomeSearchResultsItem(navigation)
    settingsHomePageGroupItems(navigation)
}

private fun LazyListScope.settingsHomeSearchFieldItem(navigation: SettingsNavigationState) {
    item(key = "settings_search_field") {
        SettingsSearchField(
            query = navigation.searchQueryState.value,
            onQueryChange = { navigation.searchQueryState.value = it }
        )
    }
}

private fun LazyListScope.settingsHomeSearchResultsItem(navigation: SettingsNavigationState) {
    if (navigation.searchQueryState.value.isBlank()) return
    item(key = "settings_search_results") {
        SettingsSearchResultsCard(
            results = navigation.searchResults,
            onResultClick = navigation::selectSearchResult
        )
    }
}

private fun LazyListScope.settingsHomePageGroupItems(navigation: SettingsNavigationState) {
    SettingsHomePageGroups.forEachIndexed { groupIndex, pages ->
        item(key = "settings_group_$groupIndex") {
            MiuixSettingsPageGroupCard(
                pages = pages,
                onPageClick = { page -> navigation.activePage = page },
                selectedPage = settingsHomeSelectedPage(navigation.activePage),
                modifier = Modifier.animateItem()
            )
        }
    }
}

@Composable
internal fun SettingsPageScaffold(
    page: SettingsPage?,
    home: @Composable () -> Unit,
    detail: @Composable (SettingsPage) -> Unit
) {
    if (page == null) home() else detail(page)
}

@Composable
internal fun SettingsPageHost(
    activePage: SettingsPage?,
    splitLayout: Boolean,
    isolateAdvancedGlassTransitions: Boolean,
    content: @Composable (SettingsPage?) -> Unit
) {
    if (splitLayout) {
        AdvancedGlassScene(active = true) {
            content(activePage)
        }
    } else {
        SettingsStackedPageHost(activePage, isolateAdvancedGlassTransitions, content)
    }
}

@Composable
private fun SettingsStackedPageHost(
    activePage: SettingsPage?,
    isolateAdvancedGlassTransitions: Boolean,
    content: @Composable (SettingsPage?) -> Unit
) {
    if (shouldUseTabletSettingsTransitions(LocalConfiguration.current.smallestScreenWidthDp)) {
        SettingsPageTransitionHost(
            activePage = activePage,
            isolateAdvancedGlassTransitions = isolateAdvancedGlassTransitions,
            content = content
        )
    } else {
        PhoneSettingsPageTransitionHost(
            activePage = activePage,
            isolateAdvancedGlassTransitions = isolateAdvancedGlassTransitions,
            content = content
        )
    }
}

@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(16.dp)

    AdvancedGlassSurface(
        role = AdvancedGlassRole.SettingsSection,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = shape,
        fallbackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.62f),
        tintColor = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { focusManager.clearFocus() }
                ),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isBlank()) {
                            Text(
                                text = stringResource(CoreCommonR.string.settings_search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
internal fun SettingsSearchResultsCard(
    results: List<SettingsSearchEntry>,
    onResultClick: (SettingsSearchEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    MiuixSettingsSectionCard(modifier = modifier) {
        if (results.isEmpty()) {
            Text(
                text = stringResource(CoreCommonR.string.settings_search_empty),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            results.forEach { entry ->
                SettingsSearchResultRow(
                    entry = entry,
                    onClick = { onResultClick(entry) }
                )
            }
        }
    }
}

@Composable
private fun SettingsSearchResultRow(
    entry: SettingsSearchEntry,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.settingsItemClickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = entry.page.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(entry.title) },
        supportingContent = {
            Text(stringResource(entry.page.titleRes))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
