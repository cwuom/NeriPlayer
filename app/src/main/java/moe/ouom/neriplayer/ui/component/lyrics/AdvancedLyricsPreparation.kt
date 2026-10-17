package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry

private const val AdvancedLyricsPreparationCacheSize = 4
private val EmptyAdvancedSyncedLyrics = SyncedLyrics(emptyList())

internal val LocalAdvancedLyricsPreparationCache = staticCompositionLocalOf<AdvancedLyricsPreparationCache?> { null }

internal data class AdvancedLyricsPreparationInput(
    val rawLyrics: String?,
    val rawTranslatedLyrics: String?,
    val lyrics: List<LyricEntry>,
    val translatedLyrics: List<LyricEntry>,
    val showPhoneticAsTranslation: Boolean
)

internal class AdvancedLyricsPreparationCache(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val build: (AdvancedLyricsPreparationInput) -> SyncedLyrics = ::buildPreparedAdvancedLyrics
) {
    private val entries = mutableListOf<AdvancedLyricsPreparationEntry>()

    fun entryFor(input: AdvancedLyricsPreparationInput): AdvancedLyricsPreparationEntry {
        entries.firstOrNull { it.input == input }?.let { return it }
        return AdvancedLyricsPreparationEntry(input, dispatcher, build).also { entry ->
            entries.add(entry)
            if (entries.size > AdvancedLyricsPreparationCacheSize) entries.removeAt(0)
        }
    }
}

internal class AdvancedLyricsPreparationEntry(
    val input: AdvancedLyricsPreparationInput,
    private val dispatcher: CoroutineDispatcher,
    private val build: (AdvancedLyricsPreparationInput) -> SyncedLyrics
) {
    var ready by mutableStateOf<SyncedLyrics?>(null)
        private set
    private val mutex = Mutex()

    suspend fun prepare() {
        mutex.withLock {
            if (ready != null) return
            val prepared = withContext(dispatcher) { build(input) }
            // 退出或换曲后即使解析迟到，也不能把取消任务的结果发布回页面
            currentCoroutineContext().ensureActive()
            ready = prepared
        }
    }
}

@Composable
internal fun rememberAdvancedSyncedLyrics(
    input: AdvancedLyricsPreparationInput
): SyncedLyrics {
    val localCache = remember { AdvancedLyricsPreparationCache() }
    val cache = LocalAdvancedLyricsPreparationCache.current ?: localCache
    val entry = remember(cache, input) { cache.entryFor(input) }
    LaunchedEffect(entry) { entry.prepare() }
    return entry.ready ?: EmptyAdvancedSyncedLyrics
}

private fun buildPreparedAdvancedLyrics(input: AdvancedLyricsPreparationInput): SyncedLyrics =
    buildAdvancedSyncedLyrics(
        rawLyrics = input.rawLyrics,
        rawTranslatedLyrics = input.rawTranslatedLyrics,
        lyrics = input.lyrics,
        translatedLyrics = input.translatedLyrics,
        showPhoneticAsTranslation = input.showPhoneticAsTranslation
    )
