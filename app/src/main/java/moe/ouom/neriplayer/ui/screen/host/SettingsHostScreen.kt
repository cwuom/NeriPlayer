package moe.ouom.neriplayer.ui.screen.host

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
 * File: moe.ouom.neriplayer.ui.screen.host/SettingsHostScreen
 * Created: 2025/1/17
 */

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.ui.settings.route.AppSettingsHostBindings
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassHostNavigationTransition
import moe.ouom.neriplayer.ui.effect.glass.animateAdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.screen.download.DownloadManagerScreen
import moe.ouom.neriplayer.ui.screen.download.DownloadProgressScreen
import moe.ouom.neriplayer.ui.screen.tab.settings.SettingsScreen

internal enum class SettingsScreenState {
    Settings,
    DownloadManager,
    DownloadProgress
}

internal fun <T> selectSettingsHostPage(
    state: SettingsScreenState,
    settings: T,
    downloadManager: T,
    downloadProgress: T
): T = when (state) {
    SettingsScreenState.Settings -> settings
    SettingsScreenState.DownloadManager -> downloadManager
    SettingsScreenState.DownloadProgress -> downloadProgress
}

private fun SettingsScreenState.saveableKey(): String = "settings_host:${name}"

private val SettingsScreenState.navigationDepth: Int
    get() = when (this) {
        SettingsScreenState.Settings -> 0
        SettingsScreenState.DownloadManager -> 1
        SettingsScreenState.DownloadProgress -> 2
    }

internal fun SettingsScreenState.nextTowards(
    requestedState: SettingsScreenState
): SettingsScreenState = when {
    navigationDepth < requestedState.navigationDepth -> when (this) {
        SettingsScreenState.Settings -> SettingsScreenState.DownloadManager
        SettingsScreenState.DownloadManager -> SettingsScreenState.DownloadProgress
        SettingsScreenState.DownloadProgress -> SettingsScreenState.DownloadProgress
    }
    navigationDepth > requestedState.navigationDepth -> when (this) {
        SettingsScreenState.Settings -> SettingsScreenState.Settings
        SettingsScreenState.DownloadManager -> SettingsScreenState.Settings
        SettingsScreenState.DownloadProgress -> SettingsScreenState.DownloadManager
    }
    else -> this
}

internal fun shouldAdvanceSettingsScreenTransition(
    targetState: SettingsScreenState,
    currentState: SettingsScreenState,
    isRunning: Boolean,
    requestedState: SettingsScreenState,
    renderedScreenStates: Set<SettingsScreenState>
): Boolean = !isRunning &&
    currentState == targetState &&
    targetState != requestedState &&
    renderedScreenStates == setOf(targetState)

@Composable
internal fun SettingsHostScreen(
    bindings: AppSettingsHostBindings,
    renderScene: @Composable (Float, Float, Float, Int, @Composable () -> Unit) -> Unit
) {
    val coherentFeedbackEnabled = bindings.environment.coherentFeedbackEnabled

    var screenState by rememberSaveable { mutableStateOf(SettingsScreenState.Settings) }
    var requestedScreenState by rememberSaveable { mutableStateOf(SettingsScreenState.Settings) }
    val saveableStateHolder = rememberSaveableStateHolder()

    // 保存设置页面的滚动状态，使用正确的Saver
    val listStateSaver: Saver<LazyListState, *> = LazyListState.Saver
    val settingsListState = rememberSaveable(saver = listStateSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val downloadManagerListState = rememberSaveable(saver = listStateSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val downloadProgressListState = rememberSaveable(saver = listStateSaver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    var pendingSettingsListRestoreIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingSettingsListRestoreOffset by rememberSaveable { mutableIntStateOf(0) }
    val navigationTransition = updateTransition(
        targetState = screenState,
        label = "settings_screen_switch"
    )
    val renderedScreenStates = remember { mutableStateListOf<SettingsScreenState>() }
    val settledRenderedScreenStates = renderedScreenStates.toSet()

    fun captureSettingsListPosition() {
        val position = settingsListState.captureHostScrollPosition()
        pendingSettingsListRestoreIndex = position.index
        pendingSettingsListRestoreOffset = position.offset
    }

    fun requestScreen(target: SettingsScreenState) {
        if (
            requestedScreenState == SettingsScreenState.Settings &&
            target != SettingsScreenState.Settings
        ) {
            captureSettingsListPosition()
        }
        requestedScreenState = target
    }

    LaunchedEffect(
        navigationTransition.currentState,
        navigationTransition.isRunning,
        requestedScreenState,
        screenState,
        settledRenderedScreenStates
    ) {
        if (
            shouldAdvanceSettingsScreenTransition(
                targetState = screenState,
                currentState = navigationTransition.currentState,
                isRunning = navigationTransition.isRunning,
                requestedState = requestedScreenState,
                renderedScreenStates = settledRenderedScreenStates
            )
        ) {
            screenState = screenState.nextTowards(requestedScreenState)
        }
    }

    LaunchedEffect(
        screenState,
        navigationTransition.isRunning,
        pendingSettingsListRestoreIndex
    ) {
        val restoreIndex = pendingSettingsListRestoreIndex ?: return@LaunchedEffect
        if (
            screenState != SettingsScreenState.Settings ||
            navigationTransition.isRunning
        ) {
            return@LaunchedEffect
        }
        settingsListState.restoreHostScrollPosition(
            HostScrollPosition(
                index = restoreIndex,
                offset = pendingSettingsListRestoreOffset
            )
        )
        pendingSettingsListRestoreIndex = null
        pendingSettingsListRestoreOffset = 0
    }

    PredictiveBackHandler(enabled = requestedScreenState != SettingsScreenState.Settings) { progress ->
        try {
            progress.collect { }
            requestScreen(
                when (requestedScreenState) {
                SettingsScreenState.DownloadProgress -> SettingsScreenState.DownloadManager
                SettingsScreenState.DownloadManager -> SettingsScreenState.Settings
                SettingsScreenState.Settings -> SettingsScreenState.Settings
                }
            )
        } catch (_: CancellationException) {
        }
    }

    Surface(color = Color.Transparent) {
        navigationTransition.AnimatedContent(
            transitionSpec = {
                advancedGlassHostNavigationTransition(
                    forward = targetState.navigationDepth > initialState.navigationDepth,
                    coherentFeedbackEnabled = coherentFeedbackEnabled,
                    targetContentZIndex = targetState.navigationDepth.toFloat()
                ).using(SizeTransform(clip = true))
            }
        ) { state ->
            DisposableEffect(state) {
                renderedScreenStates += state
                onDispose {
                    renderedScreenStates.remove(state)
                }
            }
            val sceneMotion = navigationTransition.animateAdvancedGlassSceneMotion(
                sceneState = state,
                coherentFeedbackEnabled = coherentFeedbackEnabled,
                navigationDepth = { item -> item.navigationDepth },
                label = "settings_host_scene"
            )
            renderScene(
                sceneMotion.revealTopFraction,
                sceneMotion.contentTranslationYFraction,
                sceneMotion.contentScale,
                state.navigationDepth
            ) {
                    saveableStateHolder.SaveableStateProvider(state.saveableKey()) {
                        val settingsPage: @Composable () -> Unit = {
                            SettingsScreen(
                                listState = settingsListState,
                                bindings = bindings,
                                isActive = requestedScreenState == SettingsScreenState.Settings,
                                onNavigateToDownloadManager = {
                                    requestScreen(SettingsScreenState.DownloadManager)
                                }
                            )
                        }
                        val downloadManagerPage: @Composable () -> Unit = {
                            DownloadManagerScreen(
                                onBack = { requestScreen(SettingsScreenState.Settings) },
                                onOpenDownloadProgress = {
                                    requestScreen(SettingsScreenState.DownloadProgress)
                                },
                                listState = downloadManagerListState
                            )
                        }
                        val downloadProgressPage: @Composable () -> Unit = {
                            DownloadProgressScreen(
                                onBack = { requestScreen(SettingsScreenState.DownloadManager) },
                                listState = downloadProgressListState
                            )
                        }
                        selectSettingsHostPage(
                            state, settingsPage, downloadManagerPage, downloadProgressPage
                        ).invoke()
                    }
            }
        }
    }
}
