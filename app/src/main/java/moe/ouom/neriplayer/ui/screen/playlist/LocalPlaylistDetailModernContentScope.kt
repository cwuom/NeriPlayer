package moe.ouom.neriplayer.ui.screen.playlist

import android.content.Context
import android.content.res.Resources
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSongDeleteResult
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.sync.NeteaseRemotePlaylist
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalMetadataProcessingState
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalScanPreviewState
import org.burnoutcrew.reorderable.ReorderableLazyListState
import kotlin.reflect.KProperty

internal class LocalPlaylistDetailMutableValue<T>(
    private val read: () -> T,
    private val write: (T) -> Unit
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = read()

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        write(value)
    }
}

internal class LocalPlaylistDetailModernContentScope(
    val context: Context,
    val composeResources: Resources,
    val scope: CoroutineScope,
    val playlist: LocalPlaylist,
    val playlistId: Long,
    val onBack: () -> Unit,
    val onSongClick: (List<SongItem>, Int) -> Unit,
    val offlineMode: Boolean,
    val isFavorites: Boolean,
    val isLocalFilesPlaylist: Boolean,
    val isSystemPlaylist: Boolean,
    val isPlaying: Boolean,
    val downloadPresenceVersion: Int,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
    val autoShowKeyboard: Boolean,
    val hasDownloadManagerEntry: Boolean,
    val scanPreviewState: LocalScanPreviewState,
    val searchInputState: MutableState<TextFieldValue>,
    val searchFocusRequester: FocusRequester,
    val focusManager: FocusManager,
    val keyboardController: SoftwareKeyboardController?,
    val snackbarHostState: SnackbarHostState,
    val maxNameLength: Int,
    val displayedSongs: List<SongItem>,
    val selectedKeysState: MutableState<Set<String>>,
    val selectedSongsForAction: List<SongItem>,
    val selectedDownloadedSongsForAction: List<DownloadedSong>,
    val downloadedSongKeys: Set<String>,
    val playlistPlayCount: Long,
    val hasCustomBackground: Boolean,
    val tabSongs: List<SongItem>,
    val headerKey: String,
    val headerCover: String?,
    val headerDisplayName: String,
    val totalDurationText: String,
    val metadataProcessingVisible: Boolean,
    val visibleMetadataProcessingState: LocalMetadataProcessingState,
    val favoriteSongLookup: SongIdentityLookup,
    val currentSongLookup: SongIdentityLookup,
    val queueIndexBySongKey: Map<String, Int>,
    val canReorderCurrentSongs: Boolean,
    val reorderState: ReorderableLazyListState,
    val currentIndexInDisplay: Int,
    val vm: LocalPlaylistDetailViewModel,
    val repo: LocalPlaylistRepository,
    val localSongs: SnapshotStateList<SongItem>,
    val allPlaylists: List<LocalPlaylist>,
    val navigateAfterPlaylistDeleted: () -> Unit,
    val toggleSongFavorite: (SongItem, Boolean) -> Unit,
    val toggleSelect: (String) -> Unit,
    private val exitSelectionModeAction: () -> Unit,
    val requestNeteaseSync: () -> Unit,
    private val openNeteaseRemotePlaylistPickerAction: () -> Unit,
    private val dismissNeteaseRemotePlaylistPickerAction: () -> Unit,
    private val selectNeteaseRemotePlaylistAction: (NeteaseRemotePlaylist) -> Unit,
    private val startNeteaseRemotePlaylistSyncAction: (
        NeteaseRemotePlaylist,
        List<SongItem>,
        Int
    ) -> Unit,
    val handleLocalSongDeleteResult: (
        List<SongItem>,
        Result<List<LocalPlaylistSongDeleteResult>>
    ) -> Unit,
    private val launchWithLocalSyncWarningAction: (List<SongItem>, String, () -> Unit) -> Unit,
    val openNeteaseSyncPreview: () -> Unit,
    private val playPlaylistAction: (Boolean) -> Unit,
    val copyText: (String) -> Unit,
    private val syncInProgressState: LocalPlaylistDetailMutableValue<Boolean>,
    private val showNeteaseSyncConfirmState: LocalPlaylistDetailMutableValue<Boolean>,
    private val showNeteaseRemotePlaylistPickerState: LocalPlaylistDetailMutableValue<Boolean>,
    private val neteaseRemotePlaylistsState: LocalPlaylistDetailMutableValue<List<NeteaseRemotePlaylist>>,
    private val neteaseRemotePlaylistsLoadingState: LocalPlaylistDetailMutableValue<Boolean>,
    private val neteaseRemotePlaylistsErrorState: LocalPlaylistDetailMutableValue<String?>,
    private val pendingNeteaseRemoteSyncConfirmState: LocalPlaylistDetailMutableValue<PendingNeteaseRemotePlaylistSync?>,
    private val showDeletePlaylistConfirmState: LocalPlaylistDetailMutableValue<Boolean>,
    private val showDeleteMultiConfirmState: LocalPlaylistDetailMutableValue<Boolean>,
    private val showExportSheetState: LocalPlaylistDetailMutableValue<Boolean>,
    private val showExportAllSheetState: LocalPlaylistDetailMutableValue<Boolean>,
    private val detailSongState: LocalPlaylistDetailMutableValue<SongItem?>,
    private val pendingSyncConfirmActionState: LocalPlaylistDetailMutableValue<(() -> Unit)?>,
    private val pendingSyncConfirmLabelState: LocalPlaylistDetailMutableValue<String>,
    private val showSearchState: LocalPlaylistDetailMutableValue<Boolean>,
    private val searchQueryState: LocalPlaylistDetailMutableValue<String>,
    private val headerSearchFocusedState: LocalPlaylistDetailMutableValue<Boolean>,
    private val dockedSearchFocusedState: LocalPlaylistDetailMutableValue<Boolean>,
    private val showDownloadManagerState: LocalPlaylistDetailMutableValue<Boolean>,
    private val showLocalScanModeDialogState: LocalPlaylistDetailMutableValue<Boolean>,
    private val selectionModeState: LocalPlaylistDetailMutableValue<Boolean>,
    private val showRenameState: LocalPlaylistDetailMutableValue<Boolean>,
    private val renameTextState: LocalPlaylistDetailMutableValue<TextFieldValue>,
    private val renameErrorState: LocalPlaylistDetailMutableValue<String?>,
    private val selectedLocalFilesTabIndexState: LocalPlaylistDetailMutableValue<Int>,
    private val pendingOrderIdentitiesState: LocalPlaylistDetailMutableValue<List<SongIdentity>?>,
    private val blockSyncState: LocalPlaylistDetailMutableValue<Boolean>
) {
    val selectedLocalFilesTab: LocalFilesSongTab
        get() = if (selectedLocalFilesTabIndex == LocalFilesSongTab.DOWNLOADED.ordinal) {
            LocalFilesSongTab.DOWNLOADED
        } else {
            LocalFilesSongTab.MANUALLY_ADDED
        }

    var syncInProgress by syncInProgressState
    var showNeteaseSyncConfirm by showNeteaseSyncConfirmState
    var showNeteaseRemotePlaylistPicker by showNeteaseRemotePlaylistPickerState
    var neteaseRemotePlaylists by neteaseRemotePlaylistsState
    var neteaseRemotePlaylistsLoading by neteaseRemotePlaylistsLoadingState
    var neteaseRemotePlaylistsError by neteaseRemotePlaylistsErrorState
    var pendingNeteaseRemoteSyncConfirm by pendingNeteaseRemoteSyncConfirmState
    var showDeletePlaylistConfirm by showDeletePlaylistConfirmState
    var showDeleteMultiConfirm by showDeleteMultiConfirmState
    var showExportSheet by showExportSheetState
    var showExportAllSheet by showExportAllSheetState
    var detailSong by detailSongState
    var pendingSyncConfirmAction by pendingSyncConfirmActionState
    var pendingSyncConfirmLabel by pendingSyncConfirmLabelState
    var showSearch by showSearchState
    var searchQuery by searchQueryState
    var headerSearchFocused by headerSearchFocusedState
    var dockedSearchFocused by dockedSearchFocusedState
    var showDownloadManager by showDownloadManagerState
    var showLocalScanModeDialog by showLocalScanModeDialogState
    var selectionMode by selectionModeState
    var showRename by showRenameState
    var renameText by renameTextState
    var renameError by renameErrorState
    var selectedLocalFilesTabIndex by selectedLocalFilesTabIndexState
    var pendingOrderIdentities by pendingOrderIdentitiesState
    var blockSync by blockSyncState

    fun exitSelectionMode() = exitSelectionModeAction()

    fun openNeteaseRemotePlaylistPicker() = openNeteaseRemotePlaylistPickerAction()

    fun dismissNeteaseRemotePlaylistPicker() = dismissNeteaseRemotePlaylistPickerAction()

    fun selectNeteaseRemotePlaylist(target: NeteaseRemotePlaylist) {
        selectNeteaseRemotePlaylistAction(target)
    }

    fun startNeteaseRemotePlaylistSync(
        target: NeteaseRemotePlaylist,
        songs: List<SongItem>,
        unsupportedCount: Int
    ) {
        startNeteaseRemotePlaylistSyncAction(target, songs, unsupportedCount)
    }

    fun launchWithLocalSyncWarning(
        songs: List<SongItem>,
        actionLabel: String,
        action: () -> Unit
    ) {
        launchWithLocalSyncWarningAction(songs, actionLabel, action)
    }

    fun playPlaylist(shuffle: Boolean) = playPlaylistAction(shuffle)
}
