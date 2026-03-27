@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package moe.ouom.neriplayer.core.player

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
 * File: moe.ouom.neriplayer.core.player/PlayerManager
 * Updated: 2025/8/16
 */


import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.bili.buildBiliPartSong
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.di.AppContainer.biliCookieRepo
import moe.ouom.neriplayer.core.di.AppContainer.settingsRepo
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.debug.playWhenReadyChangeReasonName
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.core.player.model.AudioDevice
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig
import moe.ouom.neriplayer.core.player.model.PlaybackSoundState
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.PersistedState
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.model.deriveCodecLabel
import moe.ouom.neriplayer.core.player.model.estimateBitrateKbps
import moe.ouom.neriplayer.core.player.model.inferYouTubeQualityKeyFromBitrate
import moe.ouom.neriplayer.core.player.model.mergeLocalPlaybackAudioInfoWithRemoteQuality
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import moe.ouom.neriplayer.core.player.model.toPersistedSongItem
import moe.ouom.neriplayer.core.player.playlist.PlayerFavoritesController
import moe.ouom.neriplayer.core.player.source.toSongItem
import moe.ouom.neriplayer.core.player.state.blockingIo
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeMusicSong
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.listentogether.ListenTogetherChannels
import moe.ouom.neriplayer.listentogether.buildStableTrackKey
import moe.ouom.neriplayer.listentogether.resolvedAudioId
import moe.ouom.neriplayer.listentogether.resolvedChannelId
import moe.ouom.neriplayer.listentogether.resolvedPlaylistContextId
import moe.ouom.neriplayer.listentogether.resolvedSubAudioId
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.SearchManager
import java.io.File
import kotlin.random.Random
import androidx.core.net.toUri

enum class PlaybackCommandSource {
    LOCAL,
    REMOTE_SYNC
}

data class PlaybackCommand(
    val type: String,
    val source: PlaybackCommandSource,
    val timestampMs: Long = System.currentTimeMillis(),
    val queue: List<SongItem>? = null,
    val currentIndex: Int? = null,
    val positionMs: Long? = null,
    val force: Boolean = false
)

/**
 * PlayerManager 闂傚倷娴囧畷鍨叏閻㈢绀夋俊銈呮噹缁愭鏌￠崶銉ョ仾闁?
 * - 闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鍟畷鏌ユ煕瀹€鈧崕鎴犵礊?ExoPlayer闂傚倸鍊风欢姘焽瑜嶈灋闁哄啫鐗婇崵鍕煕閹捐尙鍔嶆い顐ｆ礋閺屻劑寮崼鐔告闂佺顑嗛幐鎼佸煡婢跺ň鏋嶉弶鍫涘妿閻帡鏌涢埞鎯т壕婵＄偑鍊栭崹鐓庘枖閺囥垹绀夌€广儱顦伴崑锟犳煛閸モ晛浠︽い銉ｅ灮閳ь剚顔栭崰鏍€﹂悜鐣屽祦婵☆垯绱槐锝嗙節闂堟稒顥為柛濠傛捣缁辨捇宕掑▎鎴濆闂佺楠哥壕顓熺閹间礁宸濇い鏍ㄤ緱濞肩喎鈹戦悩缁樻锭妞ゆ垵娲ら悾鍨瑹閳ь剟寮婚敓鐘查唶闁靛繆鍓濆В鍕⒑閼姐倕鏋傞柛銊ょ矙閻涱噣寮介鐔蜂壕婵炴垶顏伴幋锕€鐓曢柟鐑橆殕閻撴稑霉閿濆娑ч柍褜鍓氱换鍫濐嚕缂佹绡€闁稿被鍊曢幃鎴︽⒑閹肩偛鍔楅柡鍛矒閹顢橀姀鈾€鎷洪梺鍦焾濞寸兘鍩ユ径鎰厽婵°倓鐒﹀畷宀勬煙椤曞棛绡€鐎规洘绮嶉幏鍛存偡闁缚绱╅梻鍌欑窔濞佳団€﹂鐘茬筏婵炲樊浜滅壕濠氭煙閹屽殶闁崇粯妫冮弻娑㈡倷閼碱剛宕爇ie 缂傚倸鍊搁崐鐑芥倿閿斿墽鐭欓柟娆″眰鍔戦崺鈧い鎺戝€荤壕濂稿级閸稑濡奸柛婵堝劋椤?
 * - 缂傚倸鍊搁崐鎼佸磹妞嬪海鐭嗗〒姘ｅ亾闁诡喗妞芥俊鎼佹晜閽樺浼庨梻渚€娼х换鍫ュ磹閺囩姷涓嶇紓浣骨滄禍婊堟煙閹规劖纭鹃柡瀣灴閺屸剝寰勬繝鍕ㄥ┑顔硷工椤嘲鐣峰鈧、鏃堝礋椤掑倵鍋撴繝姘拺闁告捁灏欓崢娑樏瑰鍕畼缂侇喗鐟﹀鍕偓锝庡亜閸斿懘姊洪幐搴ｇ畵闁瑰啿瀛╃粋宥夊醇閺囩喎鈧灚绻涢崼婵堜虎闁哄鍠愮换娑樏圭€ｎ偅鐝栧┑鈥冲级閸旀洟鍩為幋鐘亾閿濆簼绨奸弶鍫濈墦濮婃椽宕烽鈩冾€楅梺鍝ュУ椤ㄥ﹪骞?StateFlow ?UI闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸嬪鏌涢埄鍐噮缂佲偓婵犲洦鍊甸柨婵嗛閺嬫稓绱掗埀顒勫醇閳垛晛浜炬鐐茬仢閸旀碍淇婇锝囨噰闁哄被鍔戞俊鑸靛緞鐎ｎ剙甯鹃梻浣虹《閸撴繂煤濠婂懐鐜婚柡鍐ｅ亾濞ｅ洤锕﹂幏鐘侯槾闁伙絿鏁搁埀顒冾潐濞叉﹢鎳濇ィ鍐ｂ偓鏃堝礃椤忓啰鍓ㄩ梺鍝勮癁閸屾凹妫滄繝鐢靛仩閹活亞绱為埀顒併亜椤愩埄妲哄ù?闂傚倷绀侀幖顐λ囬锕€鐤炬繝濠傛噽閻瑩鏌熺€电袥闁稿鎸鹃幉鎾礋椤愮喐鐏嗗┑鐘殿暯閳ь剙纾幗鐘电磼濡ゅ啫鏋涢柛鈹惧亾濡炪倖宸婚崑鎾绘煃?闂備浇顕уù鐑藉极婵犳艾纾诲┑鐘插暟椤╂煡鏌ｉ幇顖涱仩鐎?
 * - 闂傚倷娴囧畷鐢稿窗閹扮増鍋￠弶鍫氭櫅缁躲倕螖閿濆懎鏆為柛濠勬暬閺岋綁鏁愰崨顔芥嫳閻庤娲栭ˇ鐢稿蓟閺囩喓绠鹃柛顭戝枛婵箓姊洪悷鏉款棌闁告挾鍠栧璇测槈濡攱鐎婚梺鐟扮摠缁诲秹寮抽銏♀拺閻熸瑥瀚崐鎰瑰鍡樼【妞ゆ洩缍佹俊鎼佸煛娴ｈ櫣鏆伴柣鐔哥矊闁帮絽鐣烽悢鎼炲亝闁告劏鏅濋崢閬嶆偡濠婂喚妯€鐎规洘鍨块獮妯肩磼濡厧寮虫繝鐢靛仦閸ㄥ爼鈥﹂崶銊︽珷闁靛繈鍨荤壕鐣屸偓骞垮劚閹峰螣閳ь剟鎮楀▓鍨灆闁告濞婇悰顔碱潨閳ь剟骞婇悩娲绘晢闁告洦鍓﹂崬?B 缂傚倸鍊搁崐鐑芥倿閿曗偓椤繗銇愰幒鎾充画闁哄鐗冮弲婊堬綖閺囩喐鍙忔俊顖氱仢閻撴劙鏌￠崪浣稿⒋闁诡喗锕㈤幃娆撳箵閹哄棙瀵栭梻?MediaItem 濠电姷鏁搁崑鐐哄垂閸洖绠扮紒瀣儥濞尖晠鏌ㄩ弴鐐测偓鎼佸触鐟欏嫮绡€濠电姴鍊归崳鎶芥煕鐎ｎ偅灏甸柟鍙夋尦瀹曠喖顢橀悩鎻掔秵缂傚倸鍊烽懗鑸垫叏閻戣棄绐楅柟鎹愬煐椤洟鏌熼幑鎰靛殭缂佺姾宕甸埀顒冾潐濞叉牕煤閿曞倸绠?
 * - 闂傚倷娴囬褎顨ョ粙鍖¤€块梺顒€绉寸壕濠氭煟閺冨洤浜圭€规挷绶氶弻娑㈠Ψ閿濆懎惟婵炲瓨绮嶇划鎾诲蓟濞戞ǚ鏀介柛鈩冾殢娴尖偓缂?闂傚倸鍊搁崐鎼佸磹閹间礁绠犻幖杈剧稻瀹曟煡鏌熺€涙濡囬柡鈧敃鍌涚厓鐟滄粓宕滈悢鐓庤摕闁绘棁娅ｇ壕濂告煏韫囨洖孝缂佺姵妞藉娲传閸曨偀鍋撴搴㈩偨婵娉涢弸浣衡偓骞垮劚濞诧絽鈻介鍫熺參婵☆垯璀﹀Σ鐑樹繆椤栨瑧绐旀慨濠冩そ閹兘鏌囬敂鐣屾瀮闂備胶顭堢换鎴︽晝閵忋倕鏄ラ柍褜鍓氶妵鍕箳瀹ュ牆鍘￠梺娲诲幖閿曪箓鍩€?闂傚倸鍊风粈渚€骞栭锔藉亱婵犲﹤瀚々鍙夌節闂堟稒锛嶉柣?闂傚倸鍊烽懗鍫曞箠閹惧瓨娅犻幖娣妼閻ょ偓銇勯幇璺虹槣闁轰礁妫楅—鍐偓锝庝簻椤掋垽鏌涚€ｎ偆澧甸柟顔筋殜閺佸秹骞嶆担闀愬闂備胶顭堥鍡涘箰閹间礁鐓橀柟杈剧畱瀹告繃銇勯弽銊х煁闁哥喆鍔戝缁樻媴閸涘﹥鍎撶紓浣割槹閹告娊骞冭缁绘繈宕ㄩ鐔割唹闂傚倸鍊烽悞锕傚箖閸洖绀夌€光偓閸曞灚鏅為梺鐟邦嚟婵敻锝為弴鐔虹瘈闂傚牊绋掑婵嬫煃閻熸壆校缂佺粯绻堝Λ鍐ㄢ槈閸愭彃顫犳繝纰樺墲瑜板啴鎮ч幘璇茶摕婵炴垶菤閺嬪孩淇婇婵囶仩濞寸姭鏅涢埞鎴︽倷閹绘帞楠囬梺缁橆殘婵炩偓鐎殿喖顭峰畷銊╁级閹寸姴濮︽俊鐐€栫敮濠囨嚄閸洖鐓濋柡鍐ㄧ墕缁犲湱绱掗鐓庡辅闁稿鎸搁悾鐑藉炊閿濆懍澹曟繝鐢靛У绾板秹鎮￠弴鐔虹闁糕剝顨嗙粋瀣繆閼碱剦鐒炬い?
 * - 闂傚倷鑳堕幊鎾诲触鐎ｎ亶鐒芥繛鍡樺灦瀹曟煡鏌熼悧鍫熺凡闁?闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁靛／鍛厠闁诲骸鐏氶悺鏇熸叏閾忣偁浜滈柟鎯у船閻忊晝绱掗埀顒勫幢濡湱绠氶梺闈涚墕閸婂憡绂嶆ィ鍐╃厽閹艰揪绱曟禒娑㈡煟閹垮嫮绡€闁绘侗鍠栭～婊堝焵椤掑嫨鈧礁顫滈埀顒勫箖閵忋倕宸濆┑鐘插暟閻ｎ亪姊婚崒娆掑厡缂侇噮鍨堕垾锕傚醇閵夈儲妲梺鍝勭▉閸樺ジ鎮為崹顐犱簻闁硅揪绲剧涵鍫曟煕閺傝法效闁哄矉绱曢埀顒婄秵娴滄粓寮抽悙鐢电＜闁稿本绋戠粭褔鏌熷畡閭﹀剶鐎规洜鍏橀、姗€鎮埀顒€危閻戣姤鈷戦悹鍥ㄧ叀椤庢绻濋埀顒勬焼瀹ュ懐锛涢梺缁樻閸嬫劕鐣垫笟鈧弻娑㈠Ψ閿濆懎顬夐梺绋挎捣閸犳牠寮婚敓鐘茬闁靛鍎崑鎾广亹閹烘垶杈堥棅顐㈡处缁嬫帡鍩涢幋锔界厵缂備焦锚缁椦呪偓娑欑箞濮婅櫣鎷犻垾宕囦哗闂備礁搴滅徊浠嬫偩閻戣棄唯闁挎棁妫勯崝鍛存⒑閹稿海绠撴俊顐ｇ洴瀵娊鎮╃紒妯锋嫼闁荤姴娲犻埀顒€鍟跨粻鏌ユ⒑閹肩偛濡肩紓宥咃龚濡? */
internal data class PlaybackStartPlan(
    val useFadeIn: Boolean,
    val fadeDurationMs: Long,
    val initialVolume: Float
)

internal const val RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS = 1000L

internal fun resolvePlaybackStartPlan(
    shouldFadeIn: Boolean,
    fadeDurationMs: Long
): PlaybackStartPlan {
    val normalizedDurationMs = fadeDurationMs.coerceAtLeast(0L)
    val useFadeIn = shouldFadeIn && normalizedDurationMs > 0L
    return PlaybackStartPlan(
        useFadeIn = useFadeIn,
        fadeDurationMs = normalizedDurationMs,
        initialVolume = if (useFadeIn) 0f else 1f
    )
}

internal fun resolveManagedPlaybackStartPlan(
    playbackFadeInEnabled: Boolean,
    playbackFadeInDurationMs: Long,
    playbackCrossfadeInDurationMs: Long,
    useTrackTransitionFade: Boolean = false,
    forceStartupProtectionFade: Boolean = false
): PlaybackStartPlan {
    val targetDurationMs = when {
        useTrackTransitionFade -> playbackCrossfadeInDurationMs
        forceStartupProtectionFade && playbackFadeInEnabled ->
            maxOf(
                playbackFadeInDurationMs,
                RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS
            )
        forceStartupProtectionFade -> RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS
        else -> playbackFadeInDurationMs
    }
    return resolvePlaybackStartPlan(
        shouldFadeIn = useTrackTransitionFade ||
            playbackFadeInEnabled ||
            forceStartupProtectionFade,
        fadeDurationMs = targetDurationMs
    )
}

internal fun shouldForceStartupProtectionFadeOnManualResume(
    isPlayerPrepared: Boolean,
    resumePositionMs: Long,
    currentMediaUrlResolvedAtMs: Long
): Boolean {
    return !isPlayerPrepared &&
        resumePositionMs > 0L &&
        currentMediaUrlResolvedAtMs <= 0L
}

object PlayerManager {
    const val BILI_SOURCE_TAG = "Bilibili"
    const val NETEASE_SOURCE_TAG = "Netease"
    private val NETEASE_QUALITY_FALLBACK_ORDER = listOf(
        "jymaster",
        "sky",
        "jyeffect",
        "hires",
        "lossless",
        "exhigh",
        "standard"
    )

    private var initialized = false
    private lateinit var application: Application
    private lateinit var player: ExoPlayer

    private lateinit var cache: Cache
    private var conditionalHttpFactory: ConditionalHttpDataSourceFactory? = null

    // Helper function to get localized string
    private fun getLocalizedString(resId: Int, vararg formatArgs: Any): String {
        val context = moe.ouom.neriplayer.util.LanguageManager.applyLanguage(application)
        return context.getString(resId, *formatArgs)
    }

    private fun newIoScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private fun newMainScope() = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var ioScope = newIoScope()
    private var mainScope = newMainScope()
    private var progressJob: Job? = null
    private var volumeFadeJob: Job? = null
    private var pendingPauseJob: Job? = null
    private var bluetoothDisconnectPauseJob: Job? = null
    private var playbackSoundPersistJob: Job? = null

    private val localRepo: LocalPlaylistRepository
        get() = LocalPlaylistRepository.getInstance(application)

    private lateinit var stateFile: File

    private var preferredQuality: String = "exhigh"
    private var youtubePreferredQuality: String = "very_high"
    private var biliPreferredQuality: String = "high"
    private var playbackFadeInEnabled = false
    private var playbackCrossfadeNextEnabled = false
    private var playbackFadeInDurationMs = DEFAULT_FADE_DURATION_MS
    private var playbackFadeOutDurationMs = DEFAULT_FADE_DURATION_MS
    private var playbackCrossfadeInDurationMs = DEFAULT_FADE_DURATION_MS
    private var playbackCrossfadeOutDurationMs = DEFAULT_FADE_DURATION_MS
    private var playbackSoundConfig = PlaybackSoundConfig()
    private var keepLastPlaybackProgressEnabled = true
    private var keepPlaybackModeStateEnabled = true
    private var stopOnBluetoothDisconnectEnabled = true
    private var allowMixedPlaybackEnabled = false

    private var currentPlaylist: List<SongItem> = emptyList()
    private var currentIndex = -1

    /** 闂傚倸鍊搁崐鎼佸磹閹间礁绠犻幖杈剧稻瀹曟煡鏌熺€涙濡囬柡鈧敃鍌涚厓鐟滄粓宕滈悢鐓庤摕闁绘棁娅ｇ壕濂告煏韫囨洖孝缂佺姵妞藉娲传閸曨偀鍋撴搴㈩偨婵娉涚粻姘舵煕閺囥劌骞橀柣顓熺懇閺屾盯鈥﹂幋婵囩亪缂? */
    private val shuffleHistory = mutableListOf<Int>()   // 闂備浇顕у锕傦綖婢舵劖鍋ら柡鍥╁С閻掑﹥绻涢崱妯虹仴闁搞劍绻堥弻銊モ槈濡警浠鹃柣蹇撶箰闁帮綁寮婚悢椋庢殝闁规鍠氭导鍥╃磽娴ｅ搫校闁绘濞€瀵鏁愭径濞⑩晠鏌曟径鍫濆姶濞寸厧鍊垮鍝劽虹拠鎻掔闂佺瀛╂繛濠囥€佸璺何ㄩ柍杞拌兌椤︿即姊洪崨濠冨矮闁煎啿鐖艰棟闁冲搫鎳忛埛鎴︽偠濞戞巻鍋撻崗鍛棜濠碉紕鍋戦崐鏍偋濡ゅ啰鐭欓柟杈鹃檮閸庡﹥銇勯弬璺ㄦ癁婵℃彃鐗撻弻鐔煎礈瑜滈崝婊勬叏婵犲喚娈樼紒杈ㄥ浮瀵剛鎹勯妸锔藉枛闂備焦瀵х粙鎴﹀疮閺夋埈鍤曞ù鐘差儐閸嬨劑鏌ｉ姀鈽嗗晱婵?
    private val shuffleFuture  = mutableListOf<Int>()   // queued next items for shuffle history
    private var shuffleBag     = mutableListOf<Int>()   // remaining shuffle candidates for current cycle

    private var consecutivePlayFailures = 0
    private const val MAX_CONSECUTIVE_FAILURES = 10
    private const val MEDIA_URL_STALE_MS = 10 * 60 * 1000L
    private const val URL_REFRESH_COOLDOWN_MS = 10 * 1000L
    private const val STATE_PERSIST_INTERVAL_MS = 15 * 1000L
    private const val DEFAULT_FADE_DURATION_MS = 500L
    private const val BLUETOOTH_DISCONNECT_CONFIRM_DELAY_MS = 1200L
    private const val AUTO_TRANSITION_EXTERNAL_PAUSE_GUARD_MS = 2_000L
    private const val AUTO_TRANSITION_BUFFER_POSITION_GUARD_MS = 1_500L
    private const val PENDING_SEEK_POSITION_TOLERANCE_MS = 1_500L
    private const val MIN_FADE_STEPS = 4
    private const val MAX_FADE_STEPS = 30
    @Volatile
    private var urlRefreshInProgress = false
    @Volatile
    private var pendingSeekPositionMs: Long = C.TIME_UNSET
    private var lastUrlRefreshKey: String? = null
    private var lastUrlRefreshAtMs: Long = 0L
    private var currentMediaUrlResolvedAtMs: Long = 0L
    private var restoredResumePositionMs: Long = 0L
    private var restoredShouldResumePlayback = false
    private var lastStatePersistAtMs: Long = 0L
    private var lastAutoTrackAdvanceAtMs: Long = 0L
    @Volatile
    private var resumePlaybackRequested = false
    @Volatile
    private var suppressAutoResumeForCurrentSession = false
    @Volatile
    private var listenTogetherSyncPlaybackRate = 1f

    private val _currentSongFlow = MutableStateFlow<SongItem?>(null)
    val currentSongFlow: StateFlow<SongItem?> = _currentSongFlow

    private val _currentQueueFlow = MutableStateFlow<List<SongItem>>(emptyList())
    val currentQueueFlow: StateFlow<List<SongItem>> = _currentQueueFlow

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow

    private val _playWhenReadyFlow = MutableStateFlow(false)
    val playWhenReadyFlow: StateFlow<Boolean> = _playWhenReadyFlow

    private val _playerPlaybackStateFlow = MutableStateFlow(Player.STATE_IDLE)
    val playerPlaybackStateFlow: StateFlow<Int> = _playerPlaybackStateFlow

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionFlow: StateFlow<Long> = _playbackPositionMs

    private val _shuffleModeFlow = MutableStateFlow(false)
    val shuffleModeFlow: StateFlow<Boolean> = _shuffleModeFlow

    private val _repeatModeFlow = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatModeFlow: StateFlow<Int> = _repeatModeFlow
    private var repeatModeSetting: Int = Player.REPEAT_MODE_OFF

    private val _currentAudioDevice = MutableStateFlow<AudioDevice?>(null)
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private val _playerEventFlow = MutableSharedFlow<PlayerEvent>()
    val playerEventFlow: SharedFlow<PlayerEvent> = _playerEventFlow.asSharedFlow()

    private val _playbackCommandFlow = MutableSharedFlow<PlaybackCommand>(
        extraBufferCapacity = 32
    )
    val playbackCommandFlow: SharedFlow<PlaybackCommand> = _playbackCommandFlow.asSharedFlow()

    /** ?UI 闂傚倸鍊风粈渚€骞栭鈶芥稑鈻庨幘宕囶唶闂佸憡鍔橀婊堟嚀閸ф鐓欐い鏍ㄧ箓閻掕棄霉濠婂啰绉洪柡宀€鍠栭獮鍡氼槻闁哄棜椴搁妵鍕Χ閸涱喖娈楅梺璇″枟椤ㄥ﹤鐣烽悜绛嬫晣闁绘劗澧楅～鏇熺節绾板纾块柧蹇撻叄瀹曞綊宕奸弴鐘茬ウ濠殿喗銇涢崑鎾淬亜閵忊槄鑰块柟顔哄灲瀹曘劍绻濋崘顏嗘殬闂傚倸鍊搁崐椋庣矆娴ｈ娅犲ù鐘差儏绾惧鏌熼幑鎰厫闁哄棙绮撻弻鐔虹磼閵忕姵鐏嶉梺鍝勬媼閸撴盯鍩€椤掆偓閸樻粓宕戦幘缁樼厓鐟滄粓宕滈悢濂夊殨妞ゆ劧绠戝洿闂佺硶鍓濋悷顖毭洪幖浣圭厵闁稿繗鍋愰弳姗€鏌涢妸锔姐仢婵﹨妫勯濂稿幢濞嗘垹妲囬柣鐔哥矊闁帮絽鐣峰┑瀣嵆闁靛繆鈧啿澹?*/
    private val _currentMediaUrl = MutableStateFlow<String?>(null)
    val currentMediaUrlFlow: StateFlow<String?> = _currentMediaUrl

    private val _currentPlaybackAudioInfo = MutableStateFlow<PlaybackAudioInfo?>(null)
    val currentPlaybackAudioInfoFlow: StateFlow<PlaybackAudioInfo?> = _currentPlaybackAudioInfo

    private val playbackEffectsController = PlaybackEffectsController()
    private val _playbackSoundState = MutableStateFlow(PlaybackSoundState())
    val playbackSoundStateFlow: StateFlow<PlaybackSoundState> = _playbackSoundState

    /** ?UI 闂傚倸鍊烽悞锕€顪冮崹顕呯劷闁秆勵殔缁€澶愬箹缁顎嗛柡瀣閺岀喓鈧稒顭囩粻銉︾箾闂傛潙宓嗛柟顔斤耿閹瑩鎳滃▓鎸庮棄缂傚倷璁查崑?*/
    private val _playlistsFlow = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlistsFlow: StateFlow<List<LocalPlaylist>> = _playlistsFlow

    private var playJob: Job? = null
    private var playbackRequestToken = 0L
    private var lastHandledTrackEndKey: String? = null
    private var lastTrackEndHandledAtMs = 0L
    val audioLevelFlow get() = AudioReactive.level
    val beatImpulseFlow get() = AudioReactive.beat

    var biliRepo = AppContainer.biliPlaybackRepository
    var biliClient = AppContainer.biliClient
    var neteaseClient = AppContainer.neteaseClient
    var youtubeMusicPlaybackRepository = AppContainer.youtubeMusicPlaybackRepository
    var youtubeMusicClient = AppContainer.youtubeMusicClient

    val cloudMusicSearchApi = AppContainer.cloudMusicSearchApi
    val qqMusicSearchApi = AppContainer.qqMusicSearchApi
    var lrcLibClient = AppContainer.lrcLibClient

    // YouTube Music 婵犵數濮甸鏍窗濡ゅ啯宕查柛宀€鍋為崕妤€銆掑锝呬壕闂佽鍨粈渚€鍩為幋鐘亾閿濆簼绨芥い锔哄姂濮婃椽宕ㄦ繝浣虹箒闂佸憡锕㈢粻鏍嵁濡ゅ懏鍋愰柣銏㈡暩閿涙繈姊虹粙鎸庢拱缂佸甯炴禍鎼侇敇閵忥紕鍘介柟鐑樺▕瀹曟繈骞嬮敃鈧弸浣衡偓骞垮劚閹虫劙寮抽崱娑欑厱闁哄洢鍔嬬花鐣岀磼鏉堛劌鍝烘慨濠呮缁瑧鎹勯妸褜鍞剁紓鍌欑椤﹂亶寮繝姘畾濞撴埃鍋撻柛銊╃畺瀵剟濡烽敂閿亾婵犳碍鈷戦柡鍌樺劜濞呭懘鏌涢悤浣镐喊闁糕晛鎳橀幃鐣屽枈濡桨澹曞┑鐐茬墕閻忔繈寮搁幘缁樼厱閻庯綆鍋呯亸浼存煙娓氬灝濡奸柍瑙勫灴楠炴鎹勯崫鍕帓濠电姷鏁告慨顓㈠磻閹炬番浜滈煫鍥ㄦ尵婢ф洜绱掗悩鐢靛笡闁靛洤瀚伴獮妯兼崉閻戞鈧箖姊洪崫鍕垫Ш闁硅櫕鎹囬垾鏃堝礃椤斿槈褔鏌涢妷锝呭婵絽鐗嗛埞鎴︽倷閻戞﹫绱甸梺鍛婎殕婵炲﹤顕ｆ繝姘唶闁绘柧璀﹀ù鍕煟鎼搭垳绉靛ù婊勭箞椤㈡棃鎮㈤崗灏栨嫼闂佸憡绻傜€氼剟鍩€椤掑喚娼愰柛鎺戯攻缁傛帞鈧絽鐏氶弲?
    private val ytMusicLyricsCache = android.util.LruCache<String, List<LyricEntry>>(20)

    // 闂傚倷娴囧畷鍨叏閹惰姤鍊块柨鏇楀亾妞ゎ厼鐏濊灒闁兼祴鏅濋ˇ顖炴倵楠炲灝鍔氶柣妤€瀚粋宥堛亹閹烘挾鍘甸梺璇″瀻閸愨晜鐦ｆ俊鐐€戦崐鏇㈠箠閹邦喗顫曢柟鎹愵嚙绾惧吋绻涢幋鐐垫噧缁炬澘绉瑰铏光偓鐧搁檮濠㈡ɑ淇婇悾灞稿亾閸偅绶查悗姘煎灦钘濋柡灞诲劜閻撶喐銇勯鐔风仴濠⒀傚嵆閺岀喎鐣￠柇锕€鍓堕悗瑙勬礃缁繘藝閹绢喗鐓?
    private var currentCacheSize: Long = 1024L * 1024 * 1024

    var sleepTimerManager: SleepTimerManager = createSleepTimerManager()
        private set

    private fun createSleepTimerManager(): SleepTimerManager {
        return SleepTimerManager(
            scope = mainScope,
            onTimerExpired = {
                pause()
                sleepTimerManager.cancel()
            }
        )
    }

    fun isTransportActive(): Boolean {
        ensureInitialized()
        if (!initialized || _currentSongFlow.value == null) return false
        return resumePlaybackRequested ||
            playJob?.isActive == true ||
            pendingPauseJob?.isActive == true ||
            _playWhenReadyFlow.value ||
            _isPlayingFlow.value
    }

    fun isTransportBuffering(): Boolean {
        ensureInitialized()
        if (!initialized || !isTransportActive()) return false
        return playJob?.isActive == true || _playerPlaybackStateFlow.value == Player.STATE_BUFFERING
    }

    fun shouldIgnoreExternalPauseCommand(): Boolean {
        ensureInitialized()
        if (!initialized || _currentSongFlow.value == null) return false
        if (!resumePlaybackRequested) return false

        val autoAdvanceAgeMs = SystemClock.elapsedRealtime() - lastAutoTrackAdvanceAtMs
        if (autoAdvanceAgeMs !in 0L..AUTO_TRANSITION_EXTERNAL_PAUSE_GUARD_MS) return false

        if (playJob?.isActive == true) {
            return true
        }

        val currentPositionMs = runCatching { player.currentPosition.coerceAtLeast(0L) }
            .getOrDefault(Long.MAX_VALUE)
        val playbackState = _playerPlaybackStateFlow.value
        if (playbackState == Player.STATE_ENDED) {
            return true
        }
        if (!_playWhenReadyFlow.value) {
            return false
        }
        return when (playbackState) {
            Player.STATE_BUFFERING,
            Player.STATE_READY -> currentPositionMs <= AUTO_TRANSITION_BUFFER_POSITION_GUARD_MS
            else -> false
        }
    }

    private fun markAutoTrackAdvance() {
        lastAutoTrackAdvanceAtMs = SystemClock.elapsedRealtime()
    }

    private fun fadeStepsFor(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return (durationMs / 40L).toInt().coerceIn(MIN_FADE_STEPS, MAX_FADE_STEPS)
    }

    private fun runPlayerActionOnMainThread(action: () -> Unit) {
        if (!::player.isInitialized) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        mainScope.launch {
            if (!::player.isInitialized) return@launch
            action()
        }
    }

    private fun applyAudioFocusPolicy() {
        if (!::player.isInitialized) return
        val handleFocus = !allowMixedPlaybackEnabled
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        mainScope.launch {
            player.setAudioAttributes(attributes, handleFocus)
        }
    }

    private fun isPreparedInPlayer(): Boolean =
        player.currentMediaItem != null && (
            player.playbackState == Player.STATE_READY ||
                player.playbackState == Player.STATE_BUFFERING
            )

    fun setListenTogetherSyncPlaybackRate(rate: Float) {
        ensureInitialized()
        if (!initialized || !::player.isInitialized) return
        val resolvedRate = rate.coerceIn(0.95f, 1.05f)
        if (kotlin.math.abs(listenTogetherSyncPlaybackRate - resolvedRate) < 0.001f) return
        listenTogetherSyncPlaybackRate = resolvedRate
        mainScope.launch {
            if (::player.isInitialized) {
                player.setPlaybackSpeed(resolvedRate)
            }
        }
    }

    fun resetListenTogetherSyncPlaybackRate() {
        setListenTogetherSyncPlaybackRate(1f)
    }

    fun resetForListenTogetherJoin() {
        ensureInitialized()
        if (!initialized) return
        cancelPendingPauseRequest(resetVolumeToFull = true)
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        resumePlaybackRequested = false
        restoredShouldResumePlayback = false
        restoredResumePositionMs = 0L
        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        _isPlayingFlow.value = false
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
        _currentMediaUrl.value = null
        currentMediaUrlResolvedAtMs = 0L
        _currentSongFlow.value = null
        _currentQueueFlow.value = emptyList()
        currentPlaylist = emptyList()
        currentIndex = -1
        consecutivePlayFailures = 0
        ioScope.launch {
            persistState(positionMs = 0L, shouldResumePlayback = false)
        }
    }

    private fun pendingSeekPositionOrNull(): Long? {
        return pendingSeekPositionMs.takeIf { it != C.TIME_UNSET }
    }

    private fun rememberPendingSeekPosition(positionMs: Long) {
        pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
    }

    private fun clearPendingSeekPosition() {
        pendingSeekPositionMs = C.TIME_UNSET
    }

    private fun resolveDisplayedPlaybackPosition(actualPositionMs: Long): Long {
        val actual = actualPositionMs.coerceAtLeast(0L)
        val pending = pendingSeekPositionOrNull() ?: return actual
        return if (kotlin.math.abs(actual - pending) <= PENDING_SEEK_POSITION_TOLERANCE_MS) {
            clearPendingSeekPosition()
            actual
        } else {
            pending
        }
    }

    private val gson = Gson()

    private fun isLocalSong(song: SongItem): Boolean = LocalSongSupport.isLocalSong(song, application)

    private fun isDirectStreamUrl(url: String?): Boolean {
        val normalized = url?.trim().orEmpty()
        return normalized.startsWith("https://", ignoreCase = true) ||
            normalized.startsWith("http://", ignoreCase = true)
    }

    private fun activeListenTogetherRoomState() = AppContainer.listenTogetherSessionManager.roomState.value

    private fun activeListenTogetherSessionState() = AppContainer.listenTogetherSessionManager.sessionState.value

    private fun isListenTogetherActive(): Boolean {
        return !activeListenTogetherSessionState().roomId.isNullOrBlank()
    }

    private fun isCurrentUserControllerInListenTogether(): Boolean {
        val session = activeListenTogetherSessionState()
        val room = activeListenTogetherRoomState()
        val sessionUserId = session.userUuid?.trim()?.takeIf { it.isNotBlank() }
        val controllerUserId = room?.controllerUserUuid?.trim()?.takeIf { it.isNotBlank() }
            ?: room?.controllerUserId?.trim()?.takeIf { it.isNotBlank() }
        return sessionUserId != null && controllerUserId != null && sessionUserId == controllerUserId
    }

    private fun currentListenTogetherTargetStableKey(): String? {
        val room = activeListenTogetherRoomState() ?: return null
        return room.track?.stableKey ?: room.queue.getOrNull(room.currentIndex)?.stableKey
    }

    private fun currentListenTogetherTargetStreamUrl(): String? {
        val room = activeListenTogetherRoomState() ?: return null
        return room.track?.streamUrl ?: room.queue.getOrNull(room.currentIndex)?.streamUrl
    }

    private fun SongItem.listenTogetherStableKeyOrNull(): String? {
        val channel = resolvedChannelId() ?: return null
        val audioId = resolvedAudioId() ?: return null
        return buildStableTrackKey(
            channelId = channel,
            audioId = audioId,
            subAudioId = resolvedSubAudioId(),
            playlistContextId = resolvedPlaylistContextId()
        )
    }

    private fun shouldWaitForListenTogetherAuthoritativeStream(song: SongItem): Boolean {
        if (!isListenTogetherActive()) return false
        if (isCurrentUserControllerInListenTogether()) return false
        val room = activeListenTogetherRoomState() ?: return false
        if (!room.settings.shareAudioLinks || room.roomStatus != "active") return false
        if (isDirectStreamUrl(currentListenTogetherTargetStreamUrl())) return false
        val targetStableKey = currentListenTogetherTargetStableKey() ?: return false
        val songStableKey = song.listenTogetherStableKeyOrNull() ?: return false
        return songStableKey == targetStableKey
    }

    private fun stopCurrentPlaybackForListenTogetherAwaitingStream() {
        cancelPendingPauseRequest(resetVolumeToFull = true)
        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        _isPlayingFlow.value = false
        _currentMediaUrl.value = null
        currentMediaUrlResolvedAtMs = 0L
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
    }

    private fun rejectListenTogetherControl(messageResId: Int): Boolean {
        postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(messageResId)))
        return true
    }

    private fun shouldBlockLocalRoomControl(commandSource: PlaybackCommandSource): Boolean {
        if (commandSource != PlaybackCommandSource.LOCAL) return false
        if (!isListenTogetherActive()) return false
        val room = activeListenTogetherRoomState()
        if (room?.roomStatus == "controller_offline" && !isCurrentUserControllerInListenTogether()) {
            return rejectListenTogetherControl(R.string.listen_together_error_controller_offline)
        }
        if (room?.settings?.allowMemberControl == false && !isCurrentUserControllerInListenTogether()) {
            return rejectListenTogetherControl(R.string.listen_together_error_member_control_disabled)
        }
        return false
    }

    private fun shouldBlockLocalSongSwitch(song: SongItem, commandSource: PlaybackCommandSource): Boolean {
        if (commandSource != PlaybackCommandSource.LOCAL) return false
        if (!isListenTogetherActive()) return false
        if (!isLocalSong(song)) return false
        return rejectListenTogetherControl(R.string.listen_together_error_local_playback_blocked)
    }

    private fun isYouTubeMusicTrack(song: SongItem): Boolean {
        return song.channelId == ListenTogetherChannels.YOUTUBE_MUSIC || isYouTubeMusicSong(song)
    }

    private fun isBiliTrack(song: SongItem): Boolean {
        return song.channelId == ListenTogetherChannels.BILIBILI ||
            song.album.startsWith(BILI_SOURCE_TAG)
    }
    private fun shouldPersistEmbeddedLyrics(song: SongItem): Boolean = !isLocalSong(song)

    private fun queueIndexOf(song: SongItem, playlist: List<SongItem> = currentPlaylist): Int {
        return playlist.indexOfFirst { it.sameIdentityAs(song) }
    }

    private fun localMediaSource(song: SongItem): String? {
        return song.localFilePath?.takeIf { it.isNotBlank() }
            ?: song.mediaUri?.takeIf { it.isNotBlank() }
    }

    private fun toPlayableLocalUrl(mediaUri: String?): String? {
        val uriString = mediaUri?.takeIf { it.isNotBlank() } ?: return null
        return if (uriString.startsWith("/")) {
            Uri.fromFile(File(uriString)).toString()
        } else {
            val parsed = runCatching { uriString.toUri() }.getOrNull() ?: return null
            when (parsed.scheme?.lowercase()) {
                null, "" -> Uri.fromFile(File(uriString)).toString()
                else -> uriString
            }
        }
    }

    private fun isReadableLocalMediaUri(mediaUri: String?): Boolean {
        val uriString = mediaUri?.takeIf { it.isNotBlank() } ?: return false
        if (uriString.startsWith("/")) {
            return File(uriString).exists()
        }

        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            null, "" -> File(uriString).exists()
            "file" -> uri.path?.let(::File)?.exists() == true
            "content", "android.resource" -> runCatching {
                application.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            else -> false
        }
    }

    private fun isReadableLocalSong(song: SongItem): Boolean {
        return isReadableLocalMediaUri(localMediaSource(song))
    }

    private fun isRestorableLocalMediaUri(mediaUri: String?): Boolean {
        val uriString = mediaUri?.takeIf { it.isNotBlank() } ?: return false
        if (uriString.startsWith("/")) {
            return File(uriString).exists()
        }

        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            null, "" -> File(uriString).exists()
            "file" -> uri.path?.let(::File)?.exists() == true
            // 闂傚倸鍊峰ù鍥敋閺嶎厼鍌ㄧ憸鐗堝笒閸ㄥ倻鎲搁悧鍫濆惞闁搞儺鍓欓拑鐔兼煏婢跺牆鍔ゆい鏃€甯掗—鍐Χ閸℃ê鏆楅梺鍝ュУ閸旀瑥顕ｉ幆鑸汗闁圭儤鎸鹃崢钘夆攽閳藉棗鐏犻柣蹇旂箞閹繝骞囬悧鍫㈠帗閻熸粍绮撳畷妤€鈽夐姀鈥斥偓鍧楁煥閺囨浜惧銈庝簻閸熸潙鐣烽幒鎴旀婵﹫绲鹃悘鍐煟鎼达紕鐣柛搴ㄤ憾钘濆ù鍏兼綑閻撴繈骞栫划瑙勵€嗛柡鈧挊澶樼唵閻犳椽缂氱€氭澘霉閻欏懐鐣甸柟?SAF 闂傚倸鍊风粈渚€骞栭锕€纾圭紒瀣紩濞差亝鏅查柛娑变簼閻庡姊洪棃娑氱疄闁稿﹥鐗犲鏌ヮ敆閸屾浜鹃柛蹇擃槸娴滈箖姊洪柅鐐茶嫰婢ь噣鏌ㄩ弴妯虹伈闁搞劑绠栭獮鍥ㄦ媴鐟欏嫨鈧啴姊绘担鐟邦嚋缂佽鍊歌灋婵炲棙鍨抽悞濠囨煥閻斿搫校闁绘挻鐩弻宥堫檨闁告挾鍠栭獮鍐ㄢ枎閹垮啯鏅㈡繛鎾磋壘濞诧箒鈪堕梻鍌氬€风粈渚€骞夐敓鐘茬闁哄洨濮烽惌鎾绘倵濞戞瑯鐒芥い鈺呮敱缁绘盯骞嬮悙鍨櫘闂佸磭绮Λ鍐蓟閺囩喎绶炴繛鎴烇供濞差厽绻濋姀锝庢綈闁告梹鐟╁濠氬灳閹颁礁鎮戦棅顐㈡处濞叉粓鎯侀崼銉︹拺?            "content", "android.resource" -> true
            else -> false
        }
    }

    private fun isRestorableLocalSong(song: SongItem): Boolean {
        return isRestorableLocalMediaUri(localMediaSource(song))
    }

    private fun sanitizeRestoredPlaylist(playlist: List<SongItem>): List<SongItem> {
        return playlist.filter { song ->
            !isLocalSong(song) || isRestorableLocalSong(song)
        }
    }

    private fun isCurrentSong(song: SongItem): Boolean {
        return _currentSongFlow.value?.sameIdentityAs(song) == true
    }

    private fun maybeUpdateSongDuration(song: SongItem, durationMs: Long) {
        val resolvedDurationMs = durationMs.takeIf { it > 0L } ?: return
        var changed = false

        val queueIndex = queueIndexOf(song)
        if (queueIndex != -1) {
            val queuedSong = currentPlaylist[queueIndex]
            if (queuedSong.durationMs <= 0L) {
                val updatedPlaylist = currentPlaylist.toMutableList()
                updatedPlaylist[queueIndex] = queuedSong.copy(durationMs = resolvedDurationMs)
                currentPlaylist = updatedPlaylist
                _currentQueueFlow.value = currentPlaylist
                changed = true
            }
        }

        val currentSong = _currentSongFlow.value
        if (currentSong?.sameIdentityAs(song) == true && currentSong.durationMs <= 0L) {
            _currentSongFlow.value = currentSong.copy(durationMs = resolvedDurationMs)
            changed = true
        }

        if (changed) {
            ioScope.launch { persistState() }
        }
    }

    private fun maybeBackfillCurrentSongDurationFromPlayer() {
        if (!::player.isInitialized) {
            return
        }
        val currentSong = _currentSongFlow.value ?: return
        val playerDurationMs = player.duration.takeIf { it > 0L } ?: return
        maybeUpdateSongDuration(currentSong, playerDurationMs)
    }

    fun changeCurrentPlaybackQuality(optionKey: String) {
        val normalizedKey = optionKey.trim().lowercase()
        if (normalizedKey.isBlank()) return
        val currentAudioInfo = _currentPlaybackAudioInfo.value ?: return
        if (normalizedKey == currentAudioInfo.qualityKey) return

        ioScope.launch {
            when (currentAudioInfo.source) {
                PlaybackAudioSource.NETEASE -> settingsRepo.setAudioQuality(normalizedKey)
                PlaybackAudioSource.BILIBILI -> settingsRepo.setBiliAudioQuality(normalizedKey)
                PlaybackAudioSource.YOUTUBE_MUSIC -> settingsRepo.setYouTubeAudioQuality(normalizedKey)
                PlaybackAudioSource.LOCAL -> Unit
            }
        }
    }

    fun setPlaybackSpeed(speed: Float, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(speed = normalizePlaybackSpeed(speed)),
            persist = persist
        )
    }

    fun setPlaybackPitch(pitch: Float, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(pitch = normalizePlaybackPitch(pitch)),
            persist = persist
        )
    }

    fun setPlaybackLoudnessGain(levelMb: Int, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(
                loudnessGainMb = normalizePlaybackLoudnessGainMb(levelMb)
            ),
            persist = persist
        )
    }

    fun setPlaybackEqualizerEnabled(enabled: Boolean, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(equalizerEnabled = enabled),
            persist = persist
        )
    }

    fun selectPlaybackEqualizerPreset(presetId: String, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(
                equalizerEnabled = true,
                presetId = presetId
            ),
            persist = persist
        )
    }

    fun updatePlaybackEqualizerBandLevel(
        index: Int,
        levelMb: Int,
        persist: Boolean = true
    ) {
        ensureInitialized()
        val currentBands = _playbackSoundState.value.bands
        if (index !in currentBands.indices) return
        val updatedLevels = currentBands.map { it.levelMb }.toMutableList()
        updatedLevels[index] = levelMb
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(
                equalizerEnabled = true,
                presetId = PlaybackEqualizerPresetId.CUSTOM,
                customBandLevelsMb = updatedLevels
            ),
            persist = persist
        )
    }

    fun resetPlaybackSoundSettings(persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            PlaybackSoundConfig(
                speed = DEFAULT_PLAYBACK_SPEED,
                pitch = DEFAULT_PLAYBACK_PITCH,
                loudnessGainMb = DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB,
                equalizerEnabled = false,
                presetId = PlaybackEqualizerPresetId.FLAT,
                customBandLevelsMb = emptyList()
            ),
            persist = persist
        )
    }

    private fun applyPlaybackSoundConfig(
        newConfig: PlaybackSoundConfig,
        persist: Boolean
    ) {
        playbackSoundConfig = newConfig.copy(
            speed = normalizePlaybackSpeed(newConfig.speed),
            pitch = normalizePlaybackPitch(newConfig.pitch),
            loudnessGainMb = normalizePlaybackLoudnessGainMb(newConfig.loudnessGainMb)
        )
        _playbackSoundState.value = playbackEffectsController.updateConfig(playbackSoundConfig)
        if (persist) {
            persistPlaybackSoundConfig(playbackSoundConfig)
        }
    }

    private fun applyPlaybackSoundConfigIfChanged(newConfig: PlaybackSoundConfig) {
        val normalizedConfig = newConfig.copy(
            speed = normalizePlaybackSpeed(newConfig.speed),
            pitch = normalizePlaybackPitch(newConfig.pitch),
            loudnessGainMb = normalizePlaybackLoudnessGainMb(newConfig.loudnessGainMb)
        )
        if (normalizedConfig == playbackSoundConfig) return
        applyPlaybackSoundConfig(normalizedConfig, persist = false)
    }

    private fun persistPlaybackSoundConfig(config: PlaybackSoundConfig) {
        playbackSoundPersistJob?.cancel()
        playbackSoundPersistJob = ioScope.launch {
            delay(150)
            settingsRepo.setPlaybackSpeed(config.speed)
            settingsRepo.setPlaybackPitch(config.pitch)
            settingsRepo.setPlaybackLoudnessGainMb(config.loudnessGainMb)
            settingsRepo.setPlaybackEqualizerEnabled(config.equalizerEnabled)
            settingsRepo.setPlaybackEqualizerPreset(config.presetId)
            settingsRepo.setPlaybackEqualizerCustomBandLevels(config.customBandLevelsMb)
        }
    }

    private suspend fun refreshCurrentSongForQualityChange(
        source: PlaybackAudioSource,
        reason: String
    ) {
        val currentAudioInfo = _currentPlaybackAudioInfo.value ?: return
        if (currentAudioInfo.source != source) return
        val currentSong = _currentSongFlow.value ?: return
        if (isLocalSong(currentSong)) return

        val (positionMs, shouldResumePlaybackAfterRefresh) = withContext(Dispatchers.Main) {
            player.currentPosition.coerceAtLeast(0L) to (player.playWhenReady || player.isPlaying)
        }
        refreshCurrentSongUrl(
            resumePositionMs = positionMs,
            allowFallback = true,
            reason = reason,
            fallbackSeekPositionMs = positionMs,
            resumePlaybackAfterRefresh = shouldResumePlaybackAfterRefresh
        )
    }

    /** 闂傚倸鍊风欢姘焽閼姐倖瀚婚柣鏃傚帶缁€澶屸偓鍏夊亾闁告洦鍋嗛悾鍝勨攽鎺抽崐鏇㈠箠鎼淬劍鍎楁俊銈呮噺閻撴瑦绻涢崼婵堜虎闁哄绋掔换娑㈠醇閵忕姌銉х磼鏉堛劌绗ч柍褜鍓ㄧ紞鍡樼閸洖纾块煫鍥ㄦ礃閸犳劙鏌℃径濠勪虎闁逞屽墮椤嘲顕ｉ锔绘晪闁逞屽墮閻ｅ嘲螣濞嗙偓鞋闂備焦鐪归崝宥夊垂閻㈠憡绠掗梻浣虹帛椤洭宕曢妶鍥ㄥ厹濡わ絽鍟悡?UI闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸嬪鏌熼悙顒€澧柣鎺曞Г閵囧嫯绠涢幘璺侯杸闂佹娊鏀遍幑鍥箖瑜版帒鐐婄憸搴ㄥ煝閺囥垺鐓?*/
    private fun postPlayerEvent(event: PlayerEvent) {
        ioScope.launch { _playerEventFlow.emit(event) }
    }

    private fun emitPlaybackCommand(
        type: String,
        source: PlaybackCommandSource,
        queue: List<SongItem>? = null,
        currentIndex: Int? = null,
        positionMs: Long? = null,
        force: Boolean = false
    ) {
        if (source != PlaybackCommandSource.LOCAL) return
        _playbackCommandFlow.tryEmit(
            PlaybackCommand(
                type = type,
                source = source,
                queue = queue,
                currentIndex = currentIndex,
                positionMs = positionMs,
                force = force
            )
        )
    }

    private fun resetTrackEndDeduplicationState() {
        lastHandledTrackEndKey = null
        lastTrackEndHandledAtMs = 0L
    }

    /**
     * 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忛柟缁㈠枛缁犵娀骞栧ǎ顒€濡奸柛?ExoPlayer 闂傚倸鍊风欢姘焽閼姐倖瀚婚柣鏃傚帶缁€澶婎熆閼搁潧濮囬柣鎺戠仛閵囧嫰骞掑鍫濆帯闂佹椿鍘奸敃锕傚焵椤掑喚娼愭繛鍙夌矒瀹曚即寮借閸ゆ洟鏌熺紒銏犳灈缂佺姷鏁婚弻鐔兼倻濡纰嶉梺鍛婃尵閸忔ê顫忛搹鍦＜婵☆垵鍋愰悡鐘绘⒑閸撹尙鍘涘ù婊庝邯瀹曟椽鍩€椤掍降浜滈柟鍝勭Х閸忓瞼绱掗悩宕囧濞ｅ洤锕幃娆撳箵閹哄棗浜鹃柟闂寸贰閺佸鏌曟径鍡樻珦闁轰礁鍊块弻娑㈠焺閸愮偓鐣奸梺浼欑秮缁犳牕顫忓ú顏勭閹艰揪绲烘慨鍥╃磽娴ｅ壊鍎愰柛銊ョ仢閻ｇ兘鏁愭径瀣偓閿嬨亜閹哄秶顦︽い搴㈡尭閳规垿鎮欓崣澶樻濠电偛顕崗姗€銆?OFF闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸庢柨鈹戦崒婊庣劸闁哄绶氬娲敆閳ь剛绮旈悽绋跨厱闁瑰墽绮悡鐔兼煛閸屾氨浠㈡俊鎻掝煼閺岋綀绠涢弮鍌涘櫚濠殿喖锕ュ浠嬨€侀弴銏℃櫜闁糕檧鏅滈崑銉х磽閸屾瑧绐旀い銈呭€垮畷鎴炵節閸モ晛绁﹂梺鍦劋椤ㄥ懎鏁柣鐔哥矋缁挸鐣烽妷鈺佺劦?
     */
    private fun syncExoRepeatMode() {
        val desired = if (repeatModeSetting == Player.REPEAT_MODE_ONE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        if (player.repeatMode != desired) {
            player.repeatMode = desired
        }
    }

    private fun shouldResumePlaybackSnapshot(): Boolean {
        return resumePlaybackRequested || playJob?.isActive == true
    }

    /**
     * 闂傚倸鍊烽懗鍓佹兜閸洖鐤鹃柣鎰▕濞存牠鏌曟繛褍妫楀皬婵＄偑鍊栧濠氬磻閹炬番浜滄い鎰Т閻忣喖霉閻欏懐鐣甸柟顔界懇椤㈡宕熼浣圭暥闂傚倸鍊风粈渚€骞栭位鍥敇閵忕姷锛熼梺鑲┾拡閸撴繃鎱ㄩ搹顐犱簻闁哄啫鍊瑰▍鏇㈡煃缂佹ɑ顥堟鐐寸墪鑿愭い鎺嗗亾闁诲繆鏅滈妵鍕晲閸℃瑥寮ㄥ┑顔硷攻濡炰粙銆侀弴銏狀潊闁冲搫鍊甸弸鍛存⒒娴ｈ櫣甯涢柟绋挎啞閺呰埖鎯旈妸銉ь唵閻熸粎澧楃敮鎺撳劔闂備焦瀵уΛ鍐极椤曗偓瀹曟垿骞樺ú缁樻櫖濠殿喗顭囬崢褍鈻撳鈧娲箚瑜忕粻鐗堢節閳ь剚娼忛妸銉х獮闂佸憡娲﹂崹閬嶅磹閸偆绠鹃柟瀵稿仧閹虫洟鏌ら弶鎸庡仴闁?     * - 闂傚倸鍊风粈渚€骞栭锔藉亱婵犲﹤鐗嗙粈鍫熺箾閸℃鐛滈柤鏉挎健閺岀喓绱掗姀鐘崇亶闂佸搫鎷嬮崜姘跺箞閵娿儮鏀介柛顐ゅ枎瀹€绔巃l-hash
     * - B 缂傚倸鍊搁崐鐑芥倿閿曗偓椤繗銇愰幒鎾充画闁哄鐗冮弲婊堬綖閺囥垺鐓熺憸蹇撯枍椤＄灇-avid-闂傚倸鍊风粈渚€骞夐敓鐘冲仭妞ゆ牜鍋涢崹鍌炴煕椤愶絾绀€闁绘帒鐏氶妵鍕箳閸℃ぞ澹曢梻渚€娼уΛ妤€鐣烽埡?闂傚倸鍊搁崐鎼佸磹閹间焦鏅濋柕鍫濐槹閸嬵亪鏌涢妷鎴濊嫰閺?     * - 缂傚倸鍊搁崐鎼佸磹閹间礁鐤い鏍仜閸ㄥ倿鎮规潪鎵Э闁挎繂鎲橀弮鈧幏鍛瑹椤栨盯鏁滈梻鍌欐祰椤骞嗗畝鍕瀭鐎规洖娲ㄩ悳缁樻叏婵犲嫬澹媡ease-songId-闂傚倸鍊搁崐鎼佸磹閹间焦鏅濋柕鍫濐槹閸嬵亪鏌涢妷鎴濊嫰閺?     * - YouTube Music闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟鐑橆殢閺佸棛绱掓径瀣靛妿usic-videoId-闂傚倸鍊搁崐鎼佸磹閹间焦鏅濋柕鍫濐槹閸嬵亪鏌涢妷鎴濊嫰閺?婵犵數濮烽弫鎼佸磻閻旂儤宕叉繝闈涚墛椤愯姤鎱ㄥΟ鎸庣【闁绘帒鐏氶妵鍕箳閸℃ぞ澹曟俊鐐€ч梽鍕珶閸℃稑鐒垫い鎺嶇閹兼悂鏌涢弬鎸庢崳闁告帗甯￠幃鐣岀矙閸喖鏁ゆ俊鐐€栭崝褏寰婃ィ鍐炬晣?
     */
    private fun computeCacheKey(song: SongItem): String {
        return when {
            isLocalSong(song) -> "local-${song.stableKey().hashCode()}"
            isYouTubeMusicTrack(song) -> {
                val videoId = song.audioId ?: extractYouTubeMusicVideoId(song.mediaUri).orEmpty()
                "ytmusic-$videoId-$youtubePreferredQuality-m4a"
            }
            isBiliTrack(song) -> {
            val cidPart = song.subAudioId ?: song.album.split('|').getOrNull(1)
            val biliSongId = song.audioId ?: song.id.toString()
            if (cidPart != null) {
                "bili-$biliSongId-$cidPart-$biliPreferredQuality"
            } else {
                "bili-$biliSongId-$biliPreferredQuality"
            }
            }
            else -> "netease-${song.id}-$preferredQuality"
        }
    }

    /** 闂傚倸鍊烽懗鍓佹兜閸洖鐤鹃柣鎰▕濞存牠鏌曟繛褍妫楀皬?URL 濠电姷鏁搁崑鐐哄垂閸洖绠扮紒瀣紩鐟欏嫷妲归幖娣焺濞肩喖姊洪幐搴㈩棃闁轰緡鍣ｅ畷鎴﹀箻閺傘儲顫嶅┑鐐叉閻熴劑鍩€椤掆偓濞硷繝寮婚埄鍐懝闁搞儜鍐潉闁诲孩顔栭崰妤呭箖閸屾氨鏆﹂柣鏃傗拡閺佸啴鏌曢崼婵囨悙妞?MediaItem闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸嬪鏌涢埄鍐槈缂佺姵宀搁弻娑㈩敃閻樻彃濮曢梺鎼炲€曢敃銉╁Φ閸曨喚鐤€闁圭偓鍓氭导鈧梻浣告惈濡瑥顭垮鈧崺銉﹀緞閹邦剚顥濆┑鐐叉閸嬫挻顨欑紓鍌氬€烽悞锕傚船缂佹ü绻嗛柛銉墮閻掑灚銇勯幒宥堝厡闁愁垱娲熼弻銊╁即濡櫣浼堥悗娈垮櫘閸嬪﹪銆佸鈧慨鈧柍鈺佸暞閻濐偊姊虹拠鑼闁稿濞€瀹曟垿骞囬弶璺ㄥ幋闂佸湱鍎ら崵姘舵偡闁妇鍙嗛梺鍛婂姀閺呮盯寮ㄩ鐐╂斀闁绘劕妯婂Σ瑙勭箾閼碱剙鏋庨摶鐐烘煕濞戝崬鐏欓柛瀣崌瀹曠兘顢橀悙鎰╁灮缁?闂傚倸鍊风粈渚€骞栭位鍥敇閵忕姷锛熼梺鑲┾拡閸撴繃鎱ㄩ搹顐犱簻闁哄啫娲﹂ˉ澶岀磼閸撲礁浠ч柍褜鍓欑粻宥夊磿閸楃伝娲晝閸屾碍杈?闂傚倸鍊搁崐鎼佸磹閹间礁绠犻煫鍥ㄦ惄濞兼牕鈹戦悩鍐茬劰闁?*/
    private fun buildMediaItem(
        song: SongItem,
        url: String,
        cacheKey: String,
        mimeType: String? = null
    ): MediaItem {
        val isLocalFile = url.startsWith("file://")
        return MediaItem.Builder()
            .setMediaId("${song.id}|${song.album}|${song.mediaUri.orEmpty()}")
            .setUri(url.toUri())
            .apply {
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
                // Local files do not need a custom cache key.
                if (!isLocalFile) {
                    setCustomCacheKey(cacheKey)
                }
            }
            .build()
    }

    /** 濠电姷鏁告慨浼村垂閻撳簶鏋栨繛鎴炲焹閸嬫挸顫濋悡搴㈢彎濡ょ姷鍋涢崯顖滄崲濠靛鐐婄憸蹇涖€侀崨瀛樷拺闁告繂瀚婵嬫煕閻樺啿濮嶉柡灞诲姂婵¤埖寰勭€ｎ剙骞嶇紓鍌氬€烽梽宥夊垂瑜版帞宓佹俊銈呮噺閻撴盯鏌涚仦鍓ф噯闁稿繐鐬肩槐鎺撴綇閵娿儲璇炲Δ鐘靛仦椤洭鍩€椤掍胶鈯曢柨姘舵煟閵婏絽鐏叉慨濠冩そ楠炴劖鎯旈敐鍌氼潓缂備胶鍋撳妯肩矓瑜版帒绠栫憸鏃堢嵁濮椻偓椤㈡瑩鎳栭埡濠傛暭濠碉紕鍋戦崐鏍蓟閵娾晛瑙﹂悗锝庡枛绾惧潡鏌￠崶銉ョ仾闁绘挾鍠愮换婵囩節閸岀儐鈧鏌ｈ箛銉х瘈闁硅櫕宀搁崺锟犲礃閿濆懍澹曞┑鐐茬墕閻忔繈寮稿☉姘辩＜閻犲洤妯婇崕鎴犵磼椤曞懎骞栭柣锝嗙箞瀹曠喖顢栭懞銉ョ槻闂傚倸顭崑鍕洪敃鈧～蹇涘捶椤撯€承″┑顔斤供閸忔ê危閸儲鐓欓柣鎰靛墮婢ь垱淇婇幓鎺旂Ш闁哄矉缍佸浠嬪Ψ閵夈垹浜炬繝闈涱儑瀹撲線鏌熼悜姗嗘當閹喖姊洪棃娴ゆ盯宕担鍛婃珨闂傚倸鍊烽懗鍫曞箠閹剧粯鍋ら柕濞炬櫆閸嬪鏌￠崶鈺佹灁妞?*/
    private fun handleTrackEnded() {
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
        // Check whether the sleep timer should stop at track end.
        val isLastInPlaylist = if (player.shuffleModeEnabled) {
            shuffleFuture.isEmpty() && shuffleBag.isEmpty()
        } else {
            currentIndex >= currentPlaylist.lastIndex
        }

        if (sleepTimerManager.shouldStopOnTrackEnd(isLastInPlaylist)) {
            pause()
            sleepTimerManager.cancel()
            return
        }

        when (repeatModeSetting) {
            Player.REPEAT_MODE_ONE -> {
                markAutoTrackAdvance()
                playAtIndex(currentIndex)
            }
            Player.REPEAT_MODE_ALL -> {
                markAutoTrackAdvance()
                next(force = true)
            }
            else -> {
                if (player.shuffleModeEnabled) {
                    if (shuffleFuture.isNotEmpty() || shuffleBag.isNotEmpty()) {
                        markAutoTrackAdvance()
                        next(force = false)
                    } else {
                        stopPlaybackPreservingQueue()
                    }
                } else {
                    if (currentIndex < currentPlaylist.lastIndex) {
                        markAutoTrackAdvance()
                        next(force = false)
                    } else {
                        stopPlaybackPreservingQueue()
                    }
                }
            }
        }
    }

    fun initialize(app: Application, maxCacheSize: Long = 1024L * 1024 * 1024) {
        if (initialized) return
        application = app
        currentCacheSize = maxCacheSize

        ioScope = newIoScope()
        mainScope = newMainScope()

        runCatching {
            stateFile = File(app.filesDir, "last_playlist.json")
            blockingIo {
                // 闂傚倸鍊风粈渚€骞夐敓鐘茬闁哄洢鍨规惔濠囨煛鐏炶鍔氱紒鐘冲哺楠炴牕菐椤掆偓婵¤偐绱掗埀顒佸緞閹邦厾鍘繝鐢靛Т缁绘帞妲愭导瀛樼厱闁圭儤鎸哥粭鎺撱亜椤撯剝纭堕柟鐟板閹煎綊宕滈幇鍓佺？缂佽鲸甯″畷锝呂熼崫鍕偖闁诲氦顫夊ú婊堝礂濡绻嗛柟闂寸鎯熼悷婊冪箻椤?Flow collect 闂傚倸鍊风粈渚€骞夐敍鍕殰濠电姴娲ょ涵鈧梺纭呮硾椤洟鍩€椤掆偓閹虫劗妲愰幒鎳崇喓浜搁弽銊т簷濠碉紕鍋戦崐鏇犳崲閹邦喒鍋撳鈧崶褏锛涢梺鐟板⒔缁垶鍩涢幒妤佸€甸梻鍫熺☉椤ュ繘鏌涘顒佸殗婵﹥妞藉畷銊︾節閸愩劎顣茬紓鍌欑椤﹂亶寮绘径鎯电兘宕掗悙绮规嫼闂佸憡绋戦敃锕傚箠閸曨垱鐓曢柍杞扮筏閸旂喐淇婇崣澶婂闁宠鍨归埀顒婄秵娴滅偤藝瑜斿娲礈閹绘帊绨肩紓浣筋嚙閸熸潙鐣烽幇顓熺秶闁靛绠戝鍨攽閻樼粯娑ч悗姘煎墴瀹曟繂顭ㄩ崼鐔封偓鐢告煥濠靛棗顏紒鎰⒒閳ь剝顫夊ú妯煎枈瀹ュ洠鍋撻棃娑栧仮妤犵偞鐗犻、姗€鎮㈡笟顖涘闂傚倸鍊风粈浣革耿闁秵鎯為幖娣妼閻鏌涢幇闈涙灈闁稿被鍔戦弻鏇㈠醇濠靛洤绐涚紒鎯у⒔閸嬫捇濡甸崟顖氬嵆闁绘劖鎯屽Λ銈囩磽娴ｆ彃浜?
                // 闂傚倸鍊搁崐椋庢閿熺姴绀堟繛鍡樻尰閸婅埖绻濋棃娑卞剰闁稿被鍔戦弻鏇㈠醇濠垫劖笑缂備胶濮电敮锟犲蓟閺囷紕鐤€闁哄洨鍎愰埀顒€鏈穱濠囧箵閹烘繄鍚嬮悗娈垮枛閻栧ジ宕洪敓鐘茬＜婵犙呭亾椤秹姊虹拠鍙夋崳闁硅櫕鎸炬竟鏇㈩敇閻樻剚娼熼梺鍦劋椤ㄥ繘寮崶顒佺厽闁哄啫鍋嗛悞楣冩煕閻斿嚖韬慨濠冩そ瀹曨偊宕熼锝嗩唲濠电姭鎷冮崘顔煎及闂佽鍣换婵嬨€佸鈧慨鈧柣妯虹枃缁躲垽姊绘担渚劸闁哄牜鍓熷畷娆撴偡閹佃櫕鐏佹繛瀵稿帶閻°劑鎮￠悢鎼炰簻妞ゆ劦鍋勬晶顔尖攽椤旂厧鈧崵妲愰幒妤婃晪闁告侗鍘炬禒顖炴⒑閻熸澘妲绘い鎴濐槹娣囧﹪鎳滈棃娑氱獮闁诲繒鍋犲Λ鍕?濠电姵顔栭崰妤冩暜濡ゅ啰鐭欓柟鐑樸仜閳ь剨绠撳畷濂稿Ψ椤旇姤娅嶅┑鐘垫暩婵數鍠婂澹﹀绻濋崶銊у弰闂婎偄娴勭徊鑺ョ濠靛洨绠?
                keepLastPlaybackProgressEnabled = settingsRepo.keepLastPlaybackProgressFlow.first()
                keepPlaybackModeStateEnabled = settingsRepo.keepPlaybackModeStateFlow.first()
                playbackFadeInEnabled = settingsRepo.playbackFadeInFlow.first()
                playbackCrossfadeNextEnabled = settingsRepo.playbackCrossfadeNextFlow.first()
                playbackFadeInDurationMs =
                    settingsRepo.playbackFadeInDurationMsFlow.first().coerceAtLeast(0L)
                playbackFadeOutDurationMs =
                    settingsRepo.playbackFadeOutDurationMsFlow.first().coerceAtLeast(0L)
                playbackCrossfadeInDurationMs =
                    settingsRepo.playbackCrossfadeInDurationMsFlow.first().coerceAtLeast(0L)
                playbackCrossfadeOutDurationMs =
                    settingsRepo.playbackCrossfadeOutDurationMsFlow.first().coerceAtLeast(0L)
                stopOnBluetoothDisconnectEnabled =
                    settingsRepo.stopOnBluetoothDisconnectFlow.first()
                allowMixedPlaybackEnabled = settingsRepo.allowMixedPlaybackFlow.first()
                playbackSoundConfig = PlaybackSoundConfig(
                    speed = settingsRepo.playbackSpeedFlow.first(),
                    pitch = settingsRepo.playbackPitchFlow.first(),
                    loudnessGainMb = settingsRepo.playbackLoudnessGainMbFlow.first(),
                    equalizerEnabled = settingsRepo.playbackEqualizerEnabledFlow.first(),
                    presetId = settingsRepo.playbackEqualizerPresetFlow.first(),
                    customBandLevelsMb = settingsRepo.playbackEqualizerCustomBandLevelsFlow.first()
                )
            }
            // Base HTTP client shared by playback data sources.
            val okHttpClient = AppContainer.sharedOkHttpClient
            val upstreamFactory: HttpDataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
            val conditionalFactory = ConditionalHttpDataSourceFactory(
                upstreamFactory,
                biliCookieRepo,
                AppContainer.youtubeAuthRepo
            )
            conditionalHttpFactory = conditionalFactory

            val finalDataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (maxCacheSize > 0) {
                val cacheDir = File(app.cacheDir, "media_cache")
                val dbProvider = StandaloneDatabaseProvider(app)

                cache = SimpleCache(
                    cacheDir,
                    LeastRecentlyUsedCacheEvictor(maxCacheSize),
                    dbProvider
                )

                val cacheDsFactory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(conditionalFactory)
                    .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
                    
                androidx.media3.datasource.DefaultDataSource.Factory(app, cacheDsFactory)
            } else {
                NPLogger.d("NERI-Player", "Cache disabled by user setting (size=0).")
                androidx.media3.datasource.DefaultDataSource.Factory(app, conditionalFactory)
            }

            // 闂傚倷娴囬褏鎹㈤幇顔藉床闁归偊鍎靛☉妯锋瀻闊洤顑傞崑鎾诲箳濡も偓缁€鍫澝归敐鍫燁仩闁挎稒绮撳娲礈閹绘帊绨撮梺鎼炲妽濡炰粙骞冩ィ鍐╃劶鐎广儱妫涢崢鍛婄箾鏉堝墽鍒伴柟纰卞亰閵嗗倿鎳栭埞鎯т壕閻熸瑥瀚亸顐ょ磼閳ь剚绗熼埀顒勫春閻愬搫绀冩い鏃囧亹閻も偓婵＄偑鍊栭崹鐓庘枖閺囥垺鍎楁俊銈呮噺閳锋垿鏌涘☉妯峰妞ゅ繐鐗嗙壕鐟邦渻鐎ｎ亜顒㈡い?MediaSourceFactory
            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
            val mediaSourceFactory = DefaultMediaSourceFactory(finalDataSourceFactory, extractorsFactory)

            val renderersFactory = ReactiveRenderersFactory(app)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

            player = ExoPlayer.Builder(app, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    setWakeMode(C.WAKE_MODE_NETWORK)
                }
            _playbackSoundState.value = playbackEffectsController.attachPlayer(player)
            applyPlaybackSoundConfig(playbackSoundConfig, persist = false)
            applyAudioFocusPolicy()
            _playWhenReadyFlow.value = player.playWhenReady
            _playerPlaybackStateFlow.value = player.playbackState

            val audioOffload = TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                )
                .build()

            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(audioOffload)
                .build()

            // 闂傚倸鍊风粈渚€骞夐敓鐘茬鐟滅増甯掗崹鍌炴煟濡も偓閻楀﹪宕ｈ箛娑欑厓闁告繂瀚崳鍦磼椤愩垻效闁诡喖缍婇幖褰掓偡閺夊灝顬嗛梻浣圭湽閸斿秹宕归崷顓燁潟闁圭儤顨呯壕鍏肩箾閸℃ê鐏ュ┑陇妫勯—?Exo 闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鎳岄埀顒€鍟鍕箛椤戔斂鍎甸弻娑㈠箛閻㈤潧甯ラ梺鎼炲€栭〃鍡涘Φ閸曨喚鐤€闁圭偓鎯屽Λ鐐电磽娴ｅ搫校鐎光偓閹间礁钃熼柕濞垮劗濡插牊淇婇婵嗕汗濞寸厧娲鐑樻姜閹殿喚鐛㈡繛瀛樼矊閻栧ジ鎮伴閿亾閿濆骸鏋涚紒鐙呯稻缁绘繃绻濋崒姘间紑闂佽鐏氶崝鎴濐潖濞差亝鍋傞幖鎼枟閻ゅ洦绻涚€涙鐭嬬紒顔奸叄楠炴垿濮€閿涘嫮鎳濋梺閫炲苯澧撮柣娑卞櫍楠炴帒螖閳ь剙鏁柣鐔哥矋缁挸鐣烽妷鈺佺劦妞ゆ帒瀚埛鎴︽煙缁嬫寧鎹ｉ柍钘夘樀閺岋絽螖閳ь剟骞婇幇鏉跨闁圭儤鎸婚崕鐔兼煏婵炲灝鍔ゆい銏犳嚇濮婃椽宕崟顒€绐涢梺鍝ュУ閸旀瑩寮婚妸鈺佄ч柛銉到娴滈箖鏌ｉ姀銏╃劸濞存粍婢橀湁婵犲﹤鎳庢禒閬嶆煟濞戝崬娅嶇€规洖宕埥澶娢熼悡搴㈡啟闂傚倷绀侀幉锟犲箰閸℃稑宸濋柡澶庢硶閸?Exo?
            player.repeatMode = Player.REPEAT_MODE_OFF

            youtubeMusicPlaybackRepository.warmBootstrapAsync()

            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    NPLogger.e("NERI-Player", "onPlayerError: ${error.errorCodeName}", error)

                    // 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘埈娼ュ┑鐘殿暯閳ь剙鍟跨痪褔鏌熼鐓庘偓鎼佹偩閻戣棄唯闁冲搫鍊瑰▍鍡涙⒑閸忛棿鑸柛搴㈢洴閺佸倿宕滆閿涙粓姊虹粙鎸庢拱缂佽鍊垮畷銉ㄣ亹閹烘挾鍘遍梺闈涚墕閹冲繘骞戦敐鍥╃＜閺夊牄鍔嶅畷灞绢殽閻愮柕顏堟偩閻戣棄鐐婇柕濠忕畱閺嬫盯姊婚崒娆戭槮婵犫偓闁秵鎯為幖娣妼閸屻劌霉閻樺樊鍎忔俊顐Ｃ湁闁挎繂娲﹂崵鈧紓浣稿閸嬨倕顕ｉ崼鏇為唶妞ゆ劦婢€閸戜粙鎮?
                    val currentUrl = _currentMediaUrl.value
                    val isOfflineCache = currentUrl?.startsWith("http://offline.cache/") == true

                    val cause = error.cause
                    if (shouldAttemptUrlRefresh(error, _currentSongFlow.value, isOfflineCache)) {
                        val shouldBypassRefreshCooldown = pendingSeekPositionOrNull() != null &&
                            YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(
                                _currentSongFlow.value,
                                currentUrl
                            )
                        val resumePositionMs = pendingSeekPositionOrNull()
                            ?: player.currentPosition.coerceAtLeast(0L)
                        val resumePlaybackAfterRefresh = player.playWhenReady || player.isPlaying
                        // url 闂傚倸鍊风粈渚€骞夐敍鍕殰闁跨喓濮寸紒鈺呮⒑椤掆偓缁夋挳鎷戦悢灏佹斀闁绘ê寮堕幖鎰磼閻欏懐绉柡宀€鍠栭弻鍥晝閳ь剚鏅堕濮愪簻妞ゆ劑鍊曢埢鍫ユ煛鐏炲墽鈽夋い顐ｇ箞椤㈡宕掑顒佺槗缂傚倸鍊风粈浣姐亹婢舵劕纭€闁规儼妫勯拑鐔兼倶閻愮數鎽傞柛姘儔閺屾盯濡烽姀鈩冪彆闂?consecutivePlayFailures闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸庢霉閿濆洤鍔嬬€规洘鐓￠弻鈥愁吋鎼粹€崇闂佺粯鎸搁崐鍧楀箖濮椻偓閹瑧鎹勬潪鐗堢潖闂備浇宕甸崰鎰崲閸儱钃熼柣鏃囨绾惧吋淇婇姘儓妞ゎ偄鏈换婵嬪煕閳ь剟宕ㄩ澶堝劜娣囧﹪宕ｆ径濠傤潚濡炪們鍨虹粙鎴︼綖濠靛鏁嗗璺侯儏椤?
                        refreshCurrentSongUrl(
                            resumePositionMs = resumePositionMs,
                            allowFallback = false,
                            reason = "playback_error_${error.errorCodeName}",
                            bypassCooldown = shouldBypassRefreshCooldown,
                            fallbackSeekPositionMs = resumePositionMs,
                            resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
                        )
                        return
                    }

                    // 闂傚倷娴囧畷鍨叏瀹ュ绀嬫い鎺戝€搁崵鎺楁⒒娴ｈ櫣銆婇柡鍛洴瀵敻顢楅埀顒勨€﹂崶鈺傚珰婵炴潙顑嗛悗濠氭⒑鐟欏嫬绀冩繛鍛礋椤㈡棃鎮欓悜妯煎幗闂佺粯锚瀵爼骞栭幇鐗堢厽閹烘娊宕濈仦钘夌カ闂備胶鎳撻顓㈠磻閻旂厧鐓濋柡鍐ㄥ€甸崑鎾荤嵁閸喖濮庨柣搴㈠嚬閸犳氨鍒掗敐鍛傛棃宕ㄩ灏栧亾閸洜鍙撻柛銉ｅ妽閳锋帞绱掗悩鍐插摵闁?url 闂傚倸鍊风粈渚€骞夐敍鍕殰闁跨喓濮寸紒鈺呮⒑椤掆偓缁夋挳鎷戦悢灏佹斀闁绘ê寮舵径鍕煛閸滀礁澧伴柍褜鍓欓崢婊堝磻閹剧粯鐓冪憸婊堝礈閻旂厧绠栭柛褎顨忛弫瀣煃瑜滈崜娆撴偩瀹勬壋鏀介悗锝庡亜娴滃綊鏌ｈ箛鏇炰粶闁稿﹦鍏樺鍫曨敇閵忥紕鍘介梺缁樏崯鎸庢叏閸岀偞鐓曢幖娣灪鐏忥附銇?
                    consecutivePlayFailures++

                    val msg = when {
                        isOfflineCache -> {
                            NPLogger.w(
                                "NERI-Player",
                                "Offline cached playback failed, pausing current song and waiting for recovery."
                            )
                            getLocalizedString(R.string.player_playback_failed_with_code, error.errorCodeName)
                        }
                        cause?.message?.contains("no protocol: null", ignoreCase = true) == true ->
                            getLocalizedString(R.string.player_playback_invalid_url)
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                            getLocalizedString(R.string.player_playback_network_error)
                        else ->
                            getLocalizedString(R.string.player_playback_failed_with_code, error.errorCodeName)
                    }

                    postPlayerEvent(PlayerEvent.ShowError(msg))

                    if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
                        stopPlaybackPreservingQueue(clearMediaUrl = true)
                        return
                    }

                    // 缂傚倸鍊搁崐椋庣矆娓氣偓瀹曟劙鎸婃径灞炬闂佹眹鍨婚…鍫ュ触瑜版帒绠归弶鍫濆⒔閹偐鐥崣銉х煓闁哄瞼鍠栭獮鍡氼槾闁挎稑绉归弻鐔兼偩鐏炶В鏋呭┑顔硷攻濡炰粙鐛幇顓熷劅闁挎繂妫楀▍娆愪繆閻愵亜鈧牕煤韫囨稑纾规繝闈涙－閸ゆ洟鏌熼幆鐗堫棄缁炬儳鍚嬮幈銊ヮ潨閸℃ぞ绨芥繛鎴炴尭缁夊墎妲愰幘瀛樺闁革富鍘稿Σ鍫濐渻閵堝繐鐦滈柛銊ュ暱閳诲酣濮€閵堝棗浜滈梺缁樻尭濞寸兘鎮鹃幎鑺モ拺闂傚牊绋撶粻鍐测攽椤栵絽骞栭崡鍗炩攽閻樺磭顣查柣鎾卞劜缁绘盯骞嬮悜鍡欏姼闁诲孩鐭崡鎶藉蓟濞戞ǚ鏋庨柟瀛樼箑婢规洟姊洪悷鏉挎Щ闁瑰啿閰ｉ崺銏℃償閵娿儳鐤€濡炪倖宸婚崑鎾愁熆閻熸壆澧︽慨濠冩そ瀹曘劍绻濋崒姘兼綋闂備礁鎲￠崺鍐磻閹惧墎纾藉ù锝堫潐閳锋劖绻涢崗鑲╂噰妞ゃ垺宀告俊鐤槷闁稿鎹囬弫鎰償閳ュ磭顔掗梻浣侯焾鐎涒晠鎮￠敓鐘茶摕婵炴垯鍨圭粻铏繆閵堝拑鏀绘繛鍫涘劤缁辨挻鎷呯拠鈩冪暥闂佺懓鍟跨换鎺斿垝濞嗘劕绶為柟閭﹀墰閸旓箑顪冮妶鍡楃瑐缂佽翰鍊楀Σ鎰板籍閸喓鍘搁悗骞垮劚妤犲憡绂嶅┑瀣厱闁冲搫鍊婚妴鎺旂磼鏉堛劍灏扮紒妤冨枛瀹曟儼顦茬紒鐘虫そ濮?
                    if (isOfflineCache) {
                        pause()
                    } else {
                        mainScope.launch { handleTrackEnded() }
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    _playerPlaybackStateFlow.value = state
                    if (state == Player.STATE_READY) {
                        maybeBackfillCurrentSongDurationFromPlayer()
                    }
                    if (state == Player.STATE_ENDED) {
                        handleTrackEndedIfNeeded(source = "playback_state_changed")
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlayingFlow.value = isPlaying
                    if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                    val positionMs = player.currentPosition.coerceAtLeast(0L)
                    val shouldResumePlayback = shouldResumePlaybackSnapshot()
                    ioScope.launch {
                        persistState(
                            positionMs = positionMs,
                            shouldResumePlayback = shouldResumePlayback
                        )
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    _playWhenReadyFlow.value = playWhenReady
                    if (!playWhenReady) {
                        val stackHint = Throwable().stackTrace.take(6).joinToString(" <- ") {
                            "${it.fileName}:${it.lineNumber}"
                        }
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "playWhenReady=false, reason=${playWhenReadyChangeReasonName(reason)}, state=${playbackStateName(player.playbackState)}, mediaId=${player.currentMediaItem?.mediaId}, stack=[$stackHint]"
                        )
                    }
                    if (
                        !playWhenReady &&
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                        player.playbackState == Player.STATE_ENDED
                    ) {
                        handleTrackEndedIfNeeded(source = "play_when_ready_end_of_item")
                    }
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _shuffleModeFlow.value = shuffleModeEnabled
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    // 濠电姷鏁搁崑鐐哄垂閸洖绠伴柛婵勫劤閻捇鏌ｉ姀鐘典粵闁?Exo 闂傚倸鍊烽悞锕傛儑瑜版帒绀夌€光偓閳ь剟鍩€椤掍礁鍤柛鎾跺枎閻ｇ柉銇愰幒婵囨櫆闂佺硶鍓濋敋濞寸姵甯″娲濞戣京鍔搁梺绋垮閻撯€崇暦閺囥垹鍗抽柕蹇ョ磿閸樿棄鈹戦悩璇у伐濡ょ姵鎮傞幃姗€鎳犻鍌滐紲闁哄鐗滈悡鍫ユ偄閳х悋L闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟娆″眰鍔戦崺鈧い鎺戝€荤壕濂稿级閸偄浜伴柛婵婃缁辨帡宕掑☉妯昏癁閻庤娲忛崝鎴︺€佸☉妯锋婵炲棗鏈悘鍡涙⒒閸屾瑧顦︾紓宥咃躬瀵煡鎳犻鍌滅劶婵炶揪绲介幗婊堝汲閿曞倹鐓熼柡鍐ㄥ€哥敮鍫曟煕濡や礁鈻曢柡宀€鍠撶槐鎺懳熼搹鍦嚃闂備胶顭堢€涒晛煤閻旂厧钃熼柨婵嗘媼閻撱儵骞栨潏鍓х？濞寸媭鍙冨娲箹閻愭彃惟闂佸憡姊归崹鐢告偩瀹勬嫈鏃堝川椤撶媭鍞撮梻浣稿悑娴滀粙宕曢懡銈嗩潟?
                    syncExoRepeatMode()
                    _repeatModeFlow.value = repeatModeSetting
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    _playbackSoundState.value =
                        playbackEffectsController.onAudioSessionIdChanged(audioSessionId)
                }
            })

        player.playWhenReady = false

        ioScope.launch {
            settingsRepo.audioQualityFlow.collect { q ->
                val previousQuality = preferredQuality
                preferredQuality = q
                if (previousQuality != q) {
                    refreshCurrentSongForQualityChange(
                        source = PlaybackAudioSource.NETEASE,
                        reason = "netease_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.youtubeAudioQualityFlow.collect { q ->
                val previousQuality = youtubePreferredQuality
                youtubePreferredQuality = q
                if (previousQuality != q) {
                    refreshCurrentSongForQualityChange(
                        source = PlaybackAudioSource.YOUTUBE_MUSIC,
                        reason = "youtube_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.biliAudioQualityFlow.collect { q ->
                val previousQuality = biliPreferredQuality
                biliPreferredQuality = q
                if (previousQuality != q) {
                    refreshCurrentSongForQualityChange(
                        source = PlaybackAudioSource.BILIBILI,
                        reason = "bili_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.playbackFadeInFlow.collect { enabled -> playbackFadeInEnabled = enabled }
        }
        ioScope.launch {
            settingsRepo.playbackCrossfadeNextFlow.collect { enabled ->
                playbackCrossfadeNextEnabled = enabled
            }
        }
        ioScope.launch {
            settingsRepo.playbackFadeInDurationMsFlow.collect { duration ->
                playbackFadeInDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackFadeOutDurationMsFlow.collect { duration ->
                playbackFadeOutDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackCrossfadeInDurationMsFlow.collect { duration ->
                playbackCrossfadeInDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackCrossfadeOutDurationMsFlow.collect { duration ->
                playbackCrossfadeOutDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackSpeedFlow.collect { speed ->
                applyPlaybackSoundConfigIfChanged(playbackSoundConfig.copy(speed = speed))
            }
        }
        ioScope.launch {
            settingsRepo.playbackPitchFlow.collect { pitch ->
                applyPlaybackSoundConfigIfChanged(playbackSoundConfig.copy(pitch = pitch))
            }
        }
        ioScope.launch {
            settingsRepo.playbackLoudnessGainMbFlow.collect { levelMb ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(loudnessGainMb = levelMb)
                )
            }
        }
        ioScope.launch {
            settingsRepo.playbackEqualizerEnabledFlow.collect { enabled ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(equalizerEnabled = enabled)
                )
            }
        }
        ioScope.launch {
            settingsRepo.playbackEqualizerPresetFlow.collect { presetId ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(presetId = presetId)
                )
            }
        }
        ioScope.launch {
            settingsRepo.playbackEqualizerCustomBandLevelsFlow.collect { levels ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(customBandLevelsMb = levels)
                )
            }
        }
        ioScope.launch {
            settingsRepo.keepLastPlaybackProgressFlow.collect { enabled ->
                val changed = keepLastPlaybackProgressEnabled != enabled
                keepLastPlaybackProgressEnabled = enabled
                if (changed && initialized && currentPlaylist.isNotEmpty()) {
                    persistState()
                }
            }
        }
        ioScope.launch {
            settingsRepo.keepPlaybackModeStateFlow.collect { enabled ->
                val changed = keepPlaybackModeStateEnabled != enabled
                keepPlaybackModeStateEnabled = enabled
                if (changed && initialized && currentPlaylist.isNotEmpty()) {
                    persistState()
                }
            }
        }
        ioScope.launch {
            settingsRepo.stopOnBluetoothDisconnectFlow.collect { enabled ->
                stopOnBluetoothDisconnectEnabled = enabled
            }
        }
        ioScope.launch {
            settingsRepo.allowMixedPlaybackFlow.collect { enabled ->
                allowMixedPlaybackEnabled = enabled
                applyAudioFocusPolicy()
            }
        }

        // 闂傚倸鍊风粈渚€骞夐敓鐘冲殞濡わ絽鍟€氬銇勯幒鎴濐伌闁轰礁妫濋弻锝夊箛椤掍焦鍎撻柣搴㈣壘椤︿即濡甸崟顖氱闁糕剝銇炴竟鏇熺節閻㈤潧袥闁稿鎹囬弻鈩冨緞鐎ｎ亞鍔稿┑鐐烘？閸楁娊骞冨鈧幃娆撴嚋濞堟寧顥夌紓鍌欒閸?
        ioScope.launch {
            localRepo.playlists.collect { repoLists ->
                _playlistsFlow.value = PlayerFavoritesController.deepCopyPlaylists(repoLists)
            }
        }

        setupAudioDeviceCallback()
        restoreState()

        // 闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鍟畷鏌ユ煕瀹€鈧崕鎴犵礊閺嶎厽鐓欓柣妤€鐗婄欢鑼磼閳ь剙鐣濋崟顒傚幐閻庡箍鍎辨鍛婄濠靛鐓㈤柛灞惧嚬閸庢棃鏌″畝瀣М妞ゃ垺妫冨畷鍗烆渻閵夛絽鐏查柡宀嬬秮閺佹劖寰勫畝鈧弳顐︽倵濞堝灝娅橀柛鐘冲哺楠炴垿宕熼娑樷偓缁樹繆椤栨粌甯剁紓宥嗘濮婄粯鎷呴悷閭﹀殝缂備礁顑嗛崹鍧楀箖瑜斿畷銊╊敊閸撗屽晭闂備焦鎮堕崕娲礈濮樿泛纾婚柕濞炬櫆閻撳繐鈹戦悩鑼婵＄虎鍠栭湁?scope?
        sleepTimerManager = createSleepTimerManager()

        // 闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鍟畷鏌ユ煕瀹€鈧崕鎴犵礊閺嶎厽鐓欓柣妤€鐗婄欢鑼磼閳ь剙鐣濋崟顒傚幐閻庤鎼╅崰鏍箠韫囨挴鏋旀い鏇楀亾婵﹥妞介幃鐑藉箥椤旇姤鍠栭梻浣告啞濮婄懓煤閻旇偐宓侀柛鎰╁壆閺冨牆鐒垫い鎺戝暟娴滈亶姊绘笟鈧埀顒傚仜閼活垱鏅剁€电硶鍋撳▓鍨灈妞ゎ參鏀辨穱濠囨倻閼恒儲娅嗛梺鍛婄懐閸嬫挾绮婚幘璇茶摕婵炴垶锕╁鈺佄旈敐鍛殲妞ゅ骸绉撮埞鎴︽偐椤愵澀澹曞┑鐐舵彧缁茶姤绔熸繝鍥у惞婵°倕鎳忛悡鏇熺箾閹存繂鑸归柡瀣枛閹绠涢妷褏鐦堥梺鍝勭焿缁绘繈宕洪埀顒併亜閹哄秹妾烽柛瀣嚇閺屾盯骞囬幆鏉挎倕闂佺顑嗛幐鎼侇敇閸忕厧绶為悘鐐垫櫕閵堬箓姊洪懡銈呪枅缂傚倹鑹鹃埢宥夋晲閸涘懏甯￠弫鍐磼濞戞ü鍖栭梻浣规偠閸庮垶宕濆鍥╃焼闁逞屽墴濮婅櫣鎷犻垾铏亶闂佺懓鎲￠幃鍌炴晲閻愬搫顫呴柕鍫濇啒閵娾晜鐓忓璺虹墕閸旀挳鏌＄€Ｑ冧壕闂傚倸鍊风粈渚€骞夐敓鐘冲仭鐟滃酣寮茬捄浣曟棃鍩€椤掑嫭鏅?
        initialized = true
        NPLogger.d("NERI-Player", "PlayerManager initialized with cache size: $maxCacheSize")
        }.onFailure { e ->
            NPLogger.e("NERI-Player", "PlayerManager initialize failed", e)
            runCatching { conditionalHttpFactory?.close() }
            conditionalHttpFactory = null
            runCatching { if (::player.isInitialized) player.release() }
            runCatching { _playbackSoundState.value = playbackEffectsController.release() }
            runCatching { if (::cache.isInitialized) cache.release() }
            runCatching { mainScope.cancel() }
            runCatching { ioScope.cancel() }
            initialized = false
        }
    }

    suspend fun clearCache(clearAudio: Boolean = true, clearImage: Boolean = true): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            var apiRemovedCount = 0
            var physicalDeletedCount = 0
            var totalSpaceFreed = 0L

            try {
                // Clear audio cache files and indexed spans.
                if (clearAudio) {
                    if (::cache.isInitialized) {
                        val keysSnapshot = HashSet(cache.keys)
                        keysSnapshot.forEach { key ->
                            try {
                                val resource = cache.getCachedSpans(key)
                                resource.forEach { totalSpaceFreed += it.length }
                                cache.removeResource(key)
                                apiRemovedCount++
                            } catch (_: Exception) {
                            }
                        }
                    }

                    val cacheDir = File(application.cacheDir, "media_cache")
                    if (cacheDir.exists() && cacheDir.isDirectory) {
                        val files = cacheDir.listFiles() ?: emptyArray()
                        files.forEach { file ->
                            if (file.isFile && file.name.endsWith(".exo") && file.delete()) {
                                physicalDeletedCount++
                            }
                        }
                    }
                }

                if (clearImage) {
                    val imageCacheDir = File(application.cacheDir, "image_cache")
                    if (imageCacheDir.exists() && imageCacheDir.isDirectory) {
                        val deleted = imageCacheDir.deleteRecursively()
                        if (deleted) {
                            imageCacheDir.mkdirs()
                        }
                    }
                }

                NPLogger.d("NERI-Player", "Cache Clear: API removed $apiRemovedCount keys, Physically deleted $physicalDeletedCount .exo files.")

                val msg = if (physicalDeletedCount > 0 || apiRemovedCount > 0 || clearImage) {
                    getLocalizedString(R.string.cache_clear_complete)
                } else {
                    getLocalizedString(R.string.settings_cache_empty)
                }
                Pair(true, msg)
            } catch (e: Exception) {
                NPLogger.e("NERI-Player", "Clear cache failed", e)
                Pair(false, getLocalizedString(R.string.toast_cache_clear_error, e.message ?: "Unknown"))
            }
        }
    }

    private fun ensureInitialized() {
        if (!initialized && ::application.isInitialized) {
            initialize(application)
        }
    }

    private fun setupAudioDeviceCallback() {
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        _currentAudioDevice.value = getCurrentAudioDevice(audioManager)
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                handleDeviceChange(audioManager)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                handleDeviceChange(audioManager)
            }
        }
        // 濠电姷鏁搁崕鎴犲緤閽樺娲晜閻愵剙搴婇梺绋跨灱閸嬬偤宕戦妶澶嬬厪濠电偛鐏濋崝鎾煕閵堝棙绀嬮柡宀嬬節瀹曞爼鍩℃担鍥风秮閺岋繝宕卞Δ鍐唶闂傚洤顦甸弻锝咁潨閸℃鐏堥梺鍛婃⒐閻熝呮?release 闂傚倸鍊风粈渚€骞栭锕€鐤柟鎯版閺勩儵鏌″搴″箹闁哄绶氶弻锝夊箛椤旂厧濡洪梺缁樻尪閸庣敻寮诲鍫闂佸憡鎸鹃崰鏍ь嚕椤愩埄鍚嬮柛娑卞灡濞堟洟姊洪崨濠冨濞存粎鍋熷Σ鎰潩閼哥鎷虹紓浣割儓濞夋洜绮婚幍顔剧＜濠㈣泛顑嗙亸锔锯偓瑙勬处閸撴盯鍩€椤掑倹鏆╃痪顓炵埣瀹曟垿骞橀弬銉︻潔濠电偛鎳撳▍鏇㈠垂閻熸壋妲?
        audioDeviceCallback = deviceCallback
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }

    fun handleAudioBecomingNoisy(): Boolean {
        ensureInitialized()
        if (!initialized) return false
        if (!_isPlayingFlow.value) return false
        val currentDevice = _currentAudioDevice.value
        if (currentDevice == null || !isHeadsetLikeOutput(currentDevice.type)) {
            return false
        }
        if (requiresDisconnectConfirmation(currentDevice.type)) {
            if (!shouldPauseForBluetoothDisconnect(currentDevice, null)) {
                return false
            }
            schedulePauseForBluetoothDisconnect(
                previousDevice = currentDevice,
                reason = "becoming_noisy"
            )
            return true
        }
        NPLogger.d("NERI-PlayerManager", "Audio becoming noisy, pausing playback immediately.")
        pause()
        return true
    }

    private fun handleDeviceChange(audioManager: AudioManager) {
        val previousDevice = _currentAudioDevice.value
        val newDevice = getCurrentAudioDevice(audioManager)
        _currentAudioDevice.value = newDevice
        if (shouldPauseForBluetoothDisconnect(previousDevice, newDevice)) {
            schedulePauseForBluetoothDisconnect(
                previousDevice = previousDevice,
                reason = "device_changed_to_${newDevice.type}"
            )
        } else if (shouldPauseForImmediateOutputDisconnect(previousDevice, newDevice)) {
            bluetoothDisconnectPauseJob?.cancel()
            bluetoothDisconnectPauseJob = null
            NPLogger.d(
                "NERI-PlayerManager",
                "Detected immediate output disconnect (${previousDevice?.type} -> ${newDevice.type}), pausing playback."
            )
            pause()
        } else if (newDevice.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            bluetoothDisconnectPauseJob?.cancel()
            bluetoothDisconnectPauseJob = null
        }
    }

    private fun shouldPauseForBluetoothDisconnect(
        previousDevice: AudioDevice?,
        newDevice: AudioDevice?
    ): Boolean {
        if (!stopOnBluetoothDisconnectEnabled) return false
        if (!_isPlayingFlow.value) return false
        if (previousDevice == null || !requiresDisconnectConfirmation(previousDevice.type)) return false
        return newDevice == null || newDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    // 闂傚倸鍊峰ù鍥涢崟顖氱柈妞ゆ牗绮嶅畷鏌ユ煕閺囥劌澧繛鍛У閵囧嫰寮崒姘閻庤娲栭ˇ浼村Φ閸曨垰鍐€闁靛ě鍛帒闂備礁鎼Λ娑欑箾閳ь剟鏌＄仦鍓ф创妤犵偞甯￠獮瀣倷瀹割喗袩闂佽姘﹂～澶娒哄┑鍫滅剨婵炲棙鎸搁弸浣广亜閺囨浜鹃悗瑙勬磸閸斿秶鎹㈠┑瀣妞ゅ繐妫欏▓娲⒒閸屾瑧鍔嶆俊鐐叉健瀹曟粓鏁冮崒姘鳖槶濠殿喗锕╅崜锕€銆掓繝姘厱鐎光偓閳ь剟宕戝☉姘变笉妞ゆ洍鍋撻柡灞糕偓宕囨殼妞ゆ梻鍘ч弫顏呬繆閵堝懏鍣洪柣鎾卞劜缁绘盯骞嬮悜鍡欏姺闂佺粯甯￠ˉ鎾舵閹烘梻纾兼俊顖滃帶閳峰矂姊洪崫鍕闁活剙鍚嬬粋鎺楁晝閸屾稑娈愰梺鍐叉惈閸燁垶顢撻弴鐔虹瘈闁汇垽娼ф禒婊勪繆椤愩埄鍤熸俊鍙夊姍瀵挳濮€閻樺吀绱滄繝纰樻閸ㄤ即宕ョ€ｎ偄鍨斿┑鍌氭啞閻撳繘鏌涢锝囩畺闁革絾妞介弻娑欑節閸曨剚姣堥梺鍝勬湰閻╊垶鐛崶顒夋晢闁稿苯鍋婄徊鍓ф崲濞戙垹閱囨繝闈涚墢椤旀帡鎮楃憴鍕闁搞劎鏁婚垾锕傚Ω閳轰胶鐤€濡炪倖姊婚搹搴∥ｉ悜鑺モ拻闁稿本鐟х拹浼存煕閹惧鎳囬柟顔矫埞鎴犫偓锝庝簻閸嬪秹姊洪棃娑氬婵炶濡囬埀顒佺绾板秹濡甸崟顖氬唨闁靛ě鍕珬缂傚倷娴囧▍锝夊磿閵堝绠為柕濞垮劗閺€浠嬫煙閹规劖纭鹃柛鈺佺焸濮婅櫣绱掑Ο鐓庘吂闂佺粯鐗曢妶鎼佹偘椤旂⒈娼ㄩ柍褜鍓熼妴浣割潨閳ь剟骞冮姀銈呭窛濠电姴鍟伴悾?
    private fun schedulePauseForBluetoothDisconnect(previousDevice: AudioDevice?, reason: String) {
        if (previousDevice == null || !requiresDisconnectConfirmation(previousDevice.type)) return
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = mainScope.launch {
            delay(BLUETOOTH_DISCONNECT_CONFIRM_DELAY_MS)
            if (!stopOnBluetoothDisconnectEnabled || !_isPlayingFlow.value) {
                bluetoothDisconnectPauseJob = null
                return@launch
            }

            val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val confirmedDevice = getCurrentAudioDevice(audioManager)
            _currentAudioDevice.value = confirmedDevice
            if (confirmedDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Confirmed bluetooth disconnect ($reason), pausing playback."
                )
                pause()
            } else {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Ignored transient bluetooth route change ($reason): ${confirmedDevice.type}"
                )
            }
            bluetoothDisconnectPauseJob = null
        }
    }

    private fun getCurrentAudioDevice(audioManager: AudioManager): AudioDevice {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bluetoothDevice = devices.firstOrNull { isBluetoothOutputType(it.type) }
        if (bluetoothDevice != null) {
            return try {
                AudioDevice(
                    name = bluetoothDevice.productName.toString()
                        .ifBlank { getLocalizedString(R.string.device_bluetooth_headset) },
                    type = bluetoothDevice.type,
                    icon = Icons.Default.BluetoothAudio
                )
            } catch (_: SecurityException) {
                AudioDevice(
                    getLocalizedString(R.string.device_bluetooth_headset),
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    Icons.Default.BluetoothAudio
                )
            }
        }
        val wiredHeadset = devices.firstOrNull { isWiredOutputType(it.type) }
        if (wiredHeadset != null) {
            return AudioDevice(
                getLocalizedString(R.string.device_wired_headset),
                wiredHeadset.type,
                Icons.Default.Headset
            )
        }
        return AudioDevice(
            getLocalizedString(R.string.device_speaker),
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            Icons.Default.SpeakerGroup
        )
    }

    private fun isBluetoothOutputType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                (type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
    }

    private fun isWiredOutputType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun isHeadsetLikeOutput(type: Int): Boolean {
        return isBluetoothOutputType(type) || isWiredOutputType(type)
    }

    private fun requiresDisconnectConfirmation(type: Int): Boolean {
        return isBluetoothOutputType(type)
    }

    private fun shouldPauseForImmediateOutputDisconnect(
        previousDevice: AudioDevice?,
        newDevice: AudioDevice?
    ): Boolean {
        if (previousDevice == null || !isWiredOutputType(previousDevice.type)) return false
        if (!_isPlayingFlow.value) return false
        return newDevice == null || newDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    private fun cancelVolumeFade(resetToFull: Boolean = false) {
        volumeFadeJob?.cancel()
        volumeFadeJob = null
        if (resetToFull && ::player.isInitialized) {
            runPlayerActionOnMainThread {
                runCatching { player.volume = 1f }
            }
        }
    }

    private fun cancelPendingPauseRequest(resetVolumeToFull: Boolean = false) {
        val hadPendingPause = pendingPauseJob?.isActive == true
        pendingPauseJob?.cancel()
        pendingPauseJob = null
        if (resetVolumeToFull && hadPendingPause && ::player.isInitialized) {
            runPlayerActionOnMainThread {
                if (::player.isInitialized) {
                    player.volume = 1f
                }
            }
        }
    }

    private fun preparePlayerForManagedStart(plan: PlaybackStartPlan) {
        if (!::player.isInitialized) return
        cancelVolumeFade()
        // ?prepare 闂傚倸鍊风粈渚€骞夐敓鐘茬闁告縿鍎抽惌鎾绘煙缂併垹鏋涢柛灞诲妽缁绘盯骞嬪▎蹇曚患缂備讲鍋撻柛鈩冪⊕閻撴洟鏌曟径娑氬埌濠碘€茬矙閺岋綁骞掑鍥╃厯闂佸搫鐭夌换婵嗙暦濮椻偓瀹曠喖顢曢敐鍛仭闂備椒绱紞浣圭閸洖钃熼柕鍫濇闂勫嫬顭跨捄渚Ш闁烩晛瀛╃换婵嗏枔閸喗鐏€闂佸搫鎳愭繛鈧柣娑卞枛椤粓鍩€椤掑嫨鈧礁顫滈埀顒勫箖閵忋倕宸濆┑鐘插暟閻ｎ亪姊婚崒娆掑厡缂侇噮鍨堕垾锕傚醇閵夈儲妲梺鍝勭▉閸樺ジ鎮為崹顐犱簻闁硅揪绲剧涵鍓佺磼濞戞绠崇紒杈ㄥ笚濞煎繘濡歌閻ゅ嫰姊洪崫鍕婵☆偅绻傞悾鐤亹閹烘繃鏅╅梺缁樻尭濮橈妇绮婇幘顔解拻濞达絽鎲￠幆鍫ユ煟濡や胶鐭掔€规洘绮岄埢搴ょ疀閵夈劉鍋撻崸妤佺厵缂備降鍨归弸娑欎繆椤愶綇鑰块柡灞剧洴婵＄兘顢涢悙鎼偓宥咁渻閵堝棗濮冪紒顔界懇瀵鈽夐姀鐘栥劎鎲稿鍛懃闂傚倷绶氬鑽ょ礊閸℃稑鍨傞柛妤冨剱閸ゆ鏌涢弴銊ョ仩闁绘劕锕ラ妵鍕疀閹炬惌妫ら梺浼欑稻濡炶棄螞閸涙惌鏁冮柕蹇ョ磿閵堚晠姊虹粙娆惧剰閻庢碍婢橀悾鐑藉閻橆偅顫嶅┑鐐叉閸╁牆霉閸曨垱鈷戦梻鍫熶緱濡狙囨⒒閸曨偄顏柡浣哥Ф閳ь剨缍嗛崰妤呭煕閹烘垟鏀介柣妯诲絻閺嗘瑩鏌嶇紒妯荤闁哄本鐩弫鎰板幢濡晲铏庨柣搴ゎ潐濞叉﹢鎮烽埡浣烘殾妞ゆ劧绠戠粈瀣煕閹捐尪鍏岄柣?
        player.playWhenReady = false
        player.volume = plan.initialVolume
    }

    private suspend fun fadeOutCurrentPlaybackIfNeeded(
        enabled: Boolean,
        fadeOutDurationMs: Long = playbackCrossfadeOutDurationMs
    ) {
        if (!enabled || !::player.isInitialized) {
            return
        }

        val shouldFade = _isPlayingFlow.value
        if (!shouldFade) {
            return
        }

        val durationMs = fadeOutDurationMs.coerceAtLeast(0L)
        if (durationMs <= 0L) {
            return
        }

        cancelVolumeFade()
        val startVolume = withContext(Dispatchers.Main) { player.volume.coerceIn(0f, 1f) }
        if (startVolume <= 0f) {
            return
        }

        val steps = fadeStepsFor(durationMs)
        if (steps <= 0) return
        val stepDelay = (durationMs / steps).coerceAtLeast(1L)
        repeat(steps) { step ->
            val fraction = (step + 1).toFloat() / steps
            withContext(Dispatchers.Main) {
                if (!::player.isInitialized) {
                    return@withContext
                }
                player.volume = (startVolume * (1f - fraction)).coerceAtLeast(0f)
            }
            delay(stepDelay)
        }

        withContext(Dispatchers.Main) {
            if (::player.isInitialized) {
                player.volume = 0f
            }
        }
    }

    private fun startPlayerPlaybackWithFade(plan: PlaybackStartPlan) {
        cancelVolumeFade()
        runPlayerActionOnMainThread {
            if (!::player.isInitialized) return@runPlayerActionOnMainThread
            player.volume = plan.initialVolume
            player.playWhenReady = true
            player.play()
        }
        if (!plan.useFadeIn) {
            return
        }

        val steps = fadeStepsFor(plan.fadeDurationMs)
        if (steps <= 0) return
        val stepDelay = (plan.fadeDurationMs / steps).coerceAtLeast(1L)
        volumeFadeJob = mainScope.launch {
            repeat(steps) { step ->
                delay(stepDelay)
                if (!::player.isInitialized) return@launch
                player.volume = ((step + 1).toFloat() / steps).coerceAtMost(1f)
            }
            if (::player.isInitialized) {
                player.volume = 1f
            }
            volumeFadeJob = null
        }
    }

    private fun resolveCurrentPlaybackStartPlan(
        useTrackTransitionFade: Boolean = false,
        forceStartupProtectionFade: Boolean = false
    ): PlaybackStartPlan {
        return resolveManagedPlaybackStartPlan(
            playbackFadeInEnabled = playbackFadeInEnabled,
            playbackFadeInDurationMs = playbackFadeInDurationMs,
            playbackCrossfadeInDurationMs = playbackCrossfadeInDurationMs,
            useTrackTransitionFade = useTrackTransitionFade,
            forceStartupProtectionFade = forceStartupProtectionFade
        )
    }

    fun playPlaylist(
        songs: List<SongItem>,
        startIndex: Int,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        ensureInitialized()
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        if (songs.isEmpty()) {
            NPLogger.w("NERI-Player", "playPlaylist called with EMPTY list")
            return
        }
        val targetSong = songs.getOrNull(startIndex.coerceIn(0, songs.lastIndex)) ?: songs.first()
        if (shouldBlockLocalRoomControl(commandSource) || shouldBlockLocalSongSwitch(targetSong, commandSource)) {
            return
        }
        suppressAutoResumeForCurrentSession = false
        consecutivePlayFailures = 0
        currentPlaylist = songs
        _currentQueueFlow.value = currentPlaylist
        currentIndex = startIndex.coerceIn(0, songs.lastIndex)

        // 婵犵數濮烽弫鎼佸磻閻愬搫绠伴柟闂寸缁犵娀鏌熼悧鍫熺凡闁绘挻锕㈤弻鈥愁吋鎼粹€崇闂佺琚崝鎴﹀蓟濞戙垹鐒洪柛鎰亾閻ｅ爼姊洪懞銉ョ仴妞わ富鍨堕崺銉﹀緞閹邦剛顔掗梺鍛婂姇濡﹪宕濇径濞炬斀闁绘﹩鍋勬禍鎯ь渻閵堝棙灏柛銊ユ贡閻ヮ亣顦归柡灞炬礃瀵板嫬螣閾忛€涘寲缂傚倷璁查崑鎾愁熆閼搁潧濮堥柍閿嬪灴閺岀喓绱掑Ο铏诡儌閻庢稒绻勭槐鎾诲磼濮橆兘鍋撹ぐ鎺戠闁告縿鍎卞鍙夌節瀵伴攱婢橀埀顒佹礋瀹曨垶顢曞熬?
        shuffleHistory.clear()
        shuffleFuture.clear()
        if (player.shuffleModeEnabled) {
            rebuildShuffleBag(excludeIndex = currentIndex)
        } else {
            shuffleBag.clear()
        }

        maybeWarmCurrentAndUpcomingYouTubeMusic(currentIndex)
        playAtIndex(currentIndex, commandSource = commandSource)
        emitPlaybackCommand(
            type = "PLAY_PLAYLIST",
            source = commandSource,
            queue = currentPlaylist,
            currentIndex = currentIndex
        )
        ioScope.launch {
            persistState()
        }
    }

    /** 闂傚倸鍊搁崐鐑芥倿閿曚降浜归柛鎰典簽閻捇鎮楅棃娑欐喐缁惧彞绮欓弻鐔煎礈瑜忕敮娑㈡煃闁垮绀嬮柣鎿冨亰瀹曞爼濡搁敂瑙勫缂傚倷闄嶉崝宥呯暆閹间礁钃熼柣鏃傚帶缁€鍫澝归敐鍫綈闁绘繍浜滈—鍐Χ閸℃璇炲銈冨劜閹瑰洭鐛崘顕呮晩闂佹鍨版禍楣冩煥濠靛棙鍣规い锝呯－缁辨帡鍩€椤掍礁绶為柟閭﹀枛瑜板嫰姊洪幖鐐插妧闁告洦鍋呴悾顒佷繆閻愵亜鈧劙寮查鍡欎笉闁圭偓鍓氶崵鏇熴亜閺囨浜鹃梺纭呮珪缁挸螞閸愩劉妲堟慨妯块哺閸ｄ即姊婚崒姘偓鎼佸磹閸濄儮鍋撳銉ュ鐎规洘鍔欏鎾偐閻㈡妲搁梺璇插嚱缂嶅棝宕伴弽顐ょ焼闁割偀鎳囬崑鎾荤嵁閸喖濮庡┑鈽嗗亝閻熲晠寮婚妸鈺佄у璺侯儑閸欏棗鈹戦悙鏉戠仸妞ゎ厼娲幃鐐电矙濡潧婀辨禍鎼佸冀閵婏妇褰嬮柣搴ゎ潐濞叉﹢銆冮崨鎼晣濠靛倻顭堝婵囥亜閺傝法姣為柛瀣崌閺屽棗顓奸崱娆忓箰闂備焦鎮堕崕鐑樼濠婂牊鍎嶆繛宸簼閻撶喐绻濋棃娑欏窛婵炴彃顕埀顒冾潐濞叉ê鐣濈粙娆惧殨闁瑰嘲鐬奸悿鈧梺鍝勬川婵參宕?*/
    private fun rebuildShuffleBag(excludeIndex: Int? = null) {
        shuffleBag = currentPlaylist.indices.toMutableList()
        if (excludeIndex != null) shuffleBag.remove(excludeIndex)
        shuffleBag.shuffle()
    }

    private fun playAtIndex(
        index: Int,
        resumePositionMs: Long = 0L,
        useTrackTransitionFade: Boolean = false,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
        forceStartupProtectionFade: Boolean = false
    ) {
        if (currentPlaylist.isEmpty() || index !in currentPlaylist.indices) {
            NPLogger.w("NERI-Player", "playAtIndex called with invalid index: $index")
            return
        }

        if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
            NPLogger.e("NERI-PlayerManager", "Too many consecutive playback failures: $consecutivePlayFailures")
            mainScope.launch {
                Toast.makeText(
                    application,
                    getLocalizedString(R.string.toast_playback_stopped),
                    Toast.LENGTH_SHORT
                ).show()
            }
            stopPlaybackPreservingQueue(clearMediaUrl = true)
            return
        }

        val song = currentPlaylist[index]
        cancelPendingPauseRequest()
        _currentSongFlow.value = song
        _currentMediaUrl.value = null
        _currentPlaybackAudioInfo.value = null
        currentMediaUrlResolvedAtMs = 0L
        val shouldAwaitAuthoritativeStream =
            commandSource == PlaybackCommandSource.REMOTE_SYNC &&
                shouldWaitForListenTogetherAuthoritativeStream(song)
        if (shouldAwaitAuthoritativeStream) {
            stopCurrentPlaybackForListenTogetherAwaitingStream()
        }
        resumePlaybackRequested = true
        restoredShouldResumePlayback = false
        restoredResumePositionMs = 0L
        ioScope.launch {
            persistState(positionMs = resumePositionMs.coerceAtLeast(0L), shouldResumePlayback = true)
        }

        // 闂備浇宕甸崰鎰垝鎼淬垺娅犳俊銈呮噹缁犱即鏌涘☉姗堟敾婵炲懐濞€閺岋絽螣濞嗘儳娈梺鍛婎殕婵炲﹤螞閸涙惌鏁冩い鎰╁灩缁犲姊洪懡銈呮瀾缂佽鐗撻悰顕€寮介鐔蜂壕婵炴垶顏伴幋锔藉亗闁靛鏅滈悡鏇㈡煟閺冣偓瑜板啯绂嶆ィ鍐┾拻濞达絽鎲￠崯鐐烘煕閵娧冨付闁宠绉归弫鎰緞鐏炵晫銈﹂梻浣规偠閸庢椽宕滃▎鎾崇闁割偅娲橀悡蹇撯攽閻樿尙绠版い鈺婂墯閵囧嫯鐔侀柛銉ｅ妿閸樿棄鈹戞幊閸婃劙宕戦幘缁樼厽妞ゆ挻绻勭粣鏃堟煏?
        if (player.shuffleModeEnabled) {
            shuffleBag.remove(index)
        }

        playJob?.cancel()
        playbackRequestToken += 1
        val requestToken = playbackRequestToken
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
        maybeWarmCurrentAndUpcomingYouTubeMusic(index)
        playJob = ioScope.launch {
            val result = resolveSongUrl(song)
            if (requestToken != playbackRequestToken || !isActive) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "闂傚倸鍊搁…顒勫磻閸曨個娲Ω閳轰胶鏌у銈嗗姧缁犳垵效閺屻儲鐓欓梺顓ㄧ畱婢у鏌涢悢閿嬪殗闁哄被鍊濋幃鈩冩償閳藉棙娈虹紓鍌欒兌婵敻鎮ч悩璇茶摕闁跨喓濮撮柋鍥煏韫囨洖校闁诲繋绀侀埞鎴︽倷閼碱剙顤€闂佹悶鍔屽锟犳偘椤旂⒈娼ㄩ柍褜鍓熼妴浣割潨閳ь剟骞冮姀銈呭窛濠电姴鍟伴悾顏堟⒒娴ｈ棄鍚瑰┑顔芥綑鐓ゆい鎾卞灩缂佲晠姊洪鈧粔鐢稿磿? song=${song.name}, requestToken=$requestToken, currentToken=$playbackRequestToken, active=$isActive"
                )
                return@launch
            }

            when (result) {
                is SongUrlResult.Success -> {
                    consecutivePlayFailures = 0

                    result.noticeMessage?.let { message ->
                        postPlayerEvent(PlayerEvent.ShowError(message))
                    }
                    maybeUpdateSongDuration(song, result.durationMs ?: 0L)
                    val cacheKey = computeCacheKey(song)
                    NPLogger.d("NERI-PlayerManager", "Using custom cache key: $cacheKey for song: ${song.name}")
                    invalidateMismatchedCachedResource(
                        cacheKey = cacheKey,
                        expectedContentLength = result.expectedContentLength
                    )

                    val mediaItem = buildMediaItem(
                        _currentSongFlow.value ?: song,
                        result.url,
                        cacheKey,
                        result.mimeType
                    )

                    _currentMediaUrl.value = result.url
                    _currentPlaybackAudioInfo.value = result.audioInfo
                    currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime()
                    persistState(
                        positionMs = resumePositionMs.coerceAtLeast(0L),
                        shouldResumePlayback = true
                    )
                    if (requestToken != playbackRequestToken || !isActive) {
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "濠电姷鏁告慨鐢割敊閺嶎厼鍨傞弶鍫氭櫆閺嗘粌鈹戦悩鎻掝伀缂佲偓婵犲洦鍊甸柨婵嗛娴滄牕霉濠婂嫮鐭掗柡宀嬬秮閹垽宕ㄦ繝鍕殥婵犵數鍋涢幊蹇涙偡閵夆斁鈧妇鎹勯妸锕€纾梺缁樺灦钃遍柟顔界懄缁绘繈鍩涢埀顒勫川椤撳鍎甸弻鐔割槹鎼粹檧鏋呴梺鍝勮閸斿酣鍩€椤掑﹦绉甸柛鎾村哺瀹曠喐銈ｉ崘鈺佲偓鐢告煟閻斿搫顣奸柛鐔哄仧閹叉悂寮堕崹顔芥婵? song=${song.name}, requestToken=$requestToken, currentToken=$playbackRequestToken, active=$isActive"
                        )
                        return@launch
                    }

                    fadeOutCurrentPlaybackIfNeeded(
                        enabled = useTrackTransitionFade,
                        fadeOutDurationMs = playbackCrossfadeOutDurationMs
                    )
                    if (requestToken != playbackRequestToken || !isActive) {
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        if (requestToken != playbackRequestToken) {
                            return@withContext
                        }
                        val startPlan = resolveCurrentPlaybackStartPlan(
                            useTrackTransitionFade = useTrackTransitionFade,
                            forceStartupProtectionFade = forceStartupProtectionFade &&
                                resumePositionMs > 0L
                        )
                        preparePlayerForManagedStart(startPlan)
                        resetTrackEndDeduplicationState()
                        player.setMediaItem(mediaItem)
                        // 婵犵數濮甸鏍闯椤栨粌绶ら柣锝呮湰瀹曟煡鏌熸潏鎯х槣闁轰礁鍟埞鎴﹀磼濠婂海鍔哥紓浣插亾闁糕剝绋掗悡鏇㈡煏婢舵稓鍒板┑鈥虫健閺岀喖鎸婃径濠冩闂佸搫鏈粙鎴︼綖濠靛鏁嗛柛鏇ㄥ亜閸欏﹪姊绘担渚劸缂佺粯鍨块弫鍐閵堝懓鎽曢梺鎸庢礀閸婂摜绮堥崘顔界厵缂備焦锚缁椦囨煕?Exo 闂傚倸鍊烽悞锕傛儑瑜版帒绀夌€光偓閳ь剟鍩€椤掍礁鍤柛鎾跺枛閻涱噣宕卞Ο鑲╃槇闂佹悶鍎崝灞剧閵忕媭娓婚柕鍫濇婢ь剛绱掗鑲╃伇闁荤啙鍥ㄢ拻濞达絿鐡旈崵鍐煕韫囨梻銆掓繛鍡愬灲婵″爼宕堕…鎴炵稐婵犵數濞€濞佳囶敄閸℃稒鍊垮┑鍌氭啞閻撴洘銇勯鐔风仴闁哄鐩幃浠嬵敍濞戞ǚ鏋欓梺鍝勬湰缁嬫垼鐏冩繛杈剧到濠€閬嵥囬妷鈺傗拺缂備焦顭囬妴鎺楁煕閻樺啿濮嶆鐐茬箻瀹曘劑寮堕幋鐙€鍟嬫繝娈垮枤閹虫挸煤閻樿纾婚柟鐐墯閸氬顭跨捄鐚村姛濞寸厧顑呴埞鎴︽偐鐠囇冧紣闂佸摜鍣ラ崑濠囧箖濡　鏋庨柟鐐綑娴犫晛鈹戦悙鏉戠仸闁绘娲樻穱濠冪附閸涘﹦鍘靛銈嗙墬閻旑剟鐓鍕厵?
                        syncExoRepeatMode()
                        if (resumePositionMs > 0L) {
                            player.seekTo(resumePositionMs)
                            _playbackPositionMs.value = resumePositionMs
                        }
                        player.prepare()
                        startPlayerPlaybackWithFade(startPlan)
                    }
                    maybeAutoMatchBiliMetadata(song, requestToken)
                    maybeWarmCurrentAndUpcomingYouTubeMusic(index)
                }
                SongUrlResult.WaitingForAuthoritativeStream -> {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Waiting for authoritative listen-together stream: song=${song.name}, stableKey=${song.listenTogetherStableKeyOrNull()}"
                    )
                    resumePlaybackRequested = false
                    ioScope.launch {
                        persistState(
                            positionMs = resumePositionMs.coerceAtLeast(0L),
                            shouldResumePlayback = false
                        )
                    }
                }
                is SongUrlResult.RequiresLogin -> {
                    NPLogger.w("NERI-PlayerManager", "Requires login to play: id=${song.id}, source=${song.album}")
                    postPlayerEvent(
                        PlayerEvent.ShowLoginPrompt(
                            getLocalizedString(R.string.player_playback_login_required)
                        )
                    )
                    withContext(Dispatchers.Main) { next() }
                }
                is SongUrlResult.Failure -> {
                    NPLogger.e("NERI-PlayerManager", "闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ椤旂厧顫╃紓浣哄Х缁垶濡甸崟顖氱閻犺櫣鍎ら悗楣冩⒑?URL 濠电姷鏁告慨浼村垂濞差亜纾块柤娴嬫櫅閸ㄦ繈鏌涢幘妤€瀚弸? 闂傚倷娴囧畷鍨叏閹绢喖绠规い鎰堕檮閸嬵亪鏌涢妷銏℃珕鐎? id=${song.id}, source=${song.album}")
                    consecutivePlayFailures++
                    withContext(Dispatchers.Main) { next() } // 闂傚倸鍊烽懗鍫曞储瑜旈妴鍐╂償閵忋埄娲稿┑鐘诧工鐎氼參宕ｈ箛娑欑厓闁告繂瀚弳鐐碘偓瑙勬礀椤︾敻寮诲☉銏犵疀妞ゆ挾鍋熺粊鐑芥⒑缂佹ɑ灏柛濠冩倐閸┿儲寰勯幇顒傤啋闂佸綊顣︾粈渚€宕滈鍓х＝?
                }
            }
        }
    }

    private fun maybeAutoMatchBiliMetadata(song: SongItem, requestToken: Long) {
        if (!isBiliTrack(song)) return
        if (song.matchedSongId != null || !song.matchedLyric.isNullOrEmpty()) return
        if (song.customName != null || song.customArtist != null || song.customCoverUrl != null) return

        ioScope.launch {
            val currentSong = _currentSongFlow.value ?: return@launch
            if (requestToken != playbackRequestToken || !currentSong.sameIdentityAs(song)) {
                return@launch
            }

            val candidate = SearchManager.findBestSearchCandidate(song.name, song.artist) ?: return@launch
            val latestSong = _currentSongFlow.value ?: return@launch
            if (requestToken != playbackRequestToken || !latestSong.sameIdentityAs(song)) {
                return@launch
            }

            replaceMetadataFromSearch(latestSong, candidate, isAuto = true)
        }
    }

    private fun maybeWarmCurrentAndUpcomingYouTubeMusic(currentSongIndex: Int) {
        val currentVideoId = currentPlaylist.getOrNull(currentSongIndex)
            ?.let { extractYouTubeMusicVideoId(it.mediaUri) }
        val nextVideoId = currentPlaylist.getOrNull(currentSongIndex + 1)
            ?.let { extractYouTubeMusicVideoId(it.mediaUri) }
        if (currentVideoId == null && nextVideoId == null) {
            return
        }
        runCatching {
            youtubeMusicPlaybackRepository.warmBootstrapAsync()
        }.onFailure { error ->
            NPLogger.w(
                "NERI-PlayerManager",
                "Warm YouTube Music bootstrap failed: ${error.message}"
            )
        }
        currentVideoId?.let { videoId ->
            runCatching {
                // 闂傚倸鍊烽懗鍫曞磿閻㈢鐤炬繛鎴欏灪閸嬨倝鏌曟繛褍瀚▓浼存⒑閸︻叀妾搁柛鐘崇墱濞戠敻鍩€椤掑嫭鈷戠紓浣癸供閻掓儳霉濠婂骸澧扮紒?in-flight 婵犵數濮烽。浠嬪焵椤掆偓閸熻法鐥閺屾盯濡搁敂鍓х暭缂備浇浜崑銈呯暦閵娧€鍋撳☉娅虫垵鈻嶉弽顓熺厽闊洦娲栨禒鈺佲攽椤斿搫鈧繂鐣烽幇鐗堝仺闁汇垻鏁搁敍婵囩箾閹剧澹樻繛灞傚€濋悰顔嘉旀担鍏哥盎闂佸湱鍎ら幐楣冩儗婵犲啨浜滈柡鍥朵簽缁夘剛绱掗悩宕囨创鐎殿噮鍣ｉ崺鈧い鎺戝€烽悞濠囨煥閻斿搫校闁绘挻鐩弻宥堫檨闁告挾鍠栭悰顕€骞嬮敃鈧粻娑欍亜閹哄棗浜惧Δ鐘靛仜椤戝洨妲愰幘瀛樺闁告挻褰冮崜浼存⒑閸濄儱鏋庢繛纭风節楠炲啯銈ｉ崘鈺佲偓閿嬨亜閹哄棗浜剧紓浣稿閸嬬喖鍩€椤掆偓缁犲秹宕曢崡鐏绘椽鏁冮崒姘緢闂婎偄娲︾粙鎴犵棯瑜旈弻宥夊传閸曨偀鍋撴繝姘疇闁告洦鍏欐禍婊堟煙闁箑鐏犲ù婊冪秺閺岀喖宕ｆ径瀣攭闂佽桨鐒﹂崝娆忕暦閸楃偐鏋庨悘鐐村灊婢规洟姊烘导娆戝埌闁活剝鍋愭竟鏇熺附閸涘﹦鍘甸梺璇″瀻鐏炶姤顔嶅┑?
                youtubeMusicPlaybackRepository.kickoffPlayableAudioPrefetch(
                    videoId = videoId,
                    preferredQualityOverride = youtubePreferredQuality,
                    requireDirect = true,
                    preferM4a = true
                )
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Warm current YouTube Music stream failed for $videoId: ${error.message}"
                )
            }
        }
        nextVideoId?.let { videoId ->
            runCatching {
                youtubeMusicPlaybackRepository.kickoffPlayableAudioPrefetch(
                    videoId = videoId,
                    preferredQualityOverride = youtubePreferredQuality,
                    requireDirect = true,
                    preferM4a = true
                )
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Prefetch next YouTube Music stream failed for $videoId: ${error.message}"
                )
            }
        }
    }

    private suspend fun resolveSongUrl(
        song: SongItem,
        forceRefresh: Boolean = false
    ): SongUrlResult {
        if (shouldWaitForListenTogetherAuthoritativeStream(song)) {
            return SongUrlResult.WaitingForAuthoritativeStream
        }
        if (isDirectStreamUrl(song.streamUrl)) {
            return SongUrlResult.Success(song.streamUrl.orEmpty())
        }
        if (isLocalSong(song)) {
            val localMediaUri = localMediaSource(song)
            if (localMediaUri != null && isReadableLocalMediaUri(localMediaUri)) {
                return SongUrlResult.Success(
                    url = toPlayableLocalUrl(localMediaUri) ?: localMediaUri,
                    audioInfo = buildLocalPlaybackAudioInfo(song)
                )
            }
            postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
            return SongUrlResult.Failure
        }

        // Prefer locally downloaded files before remote resolution.
        val localResult = checkLocalCache(song)
        if (localResult != null) return localResult
        val cacheKey = computeCacheKey(song)
        val hasCachedData = checkExoPlayerCache(cacheKey)
        val result = when {
            isYouTubeMusicTrack(song) -> getYouTubeMusicAudioUrl(
                song = song,
                suppressError = hasCachedData,
                forceRefresh = forceRefresh
            )
            isBiliTrack(song) -> getBiliAudioUrl(song, suppressError = hasCachedData)
            else -> getNeteaseSongUrl(song, suppressError = hasCachedData)
        }

        // 濠电姷鏁告慨鐑姐€傛禒瀣劦妞ゆ巻鍋撻柛鐔锋健閸┾偓妞ゆ巻鍋撶紓宥咃躬楠炲啫螣鐠囪尙绐為悗鍏夊亾闁逞屽墰婢规洘銈ｉ崘鈺冨幍闁诲海鏁搁…鍫濇毄闂備胶顢婇～澶娒洪弽顓涒偓锕傚锤濡も偓缁犳岸鏌熷▓鍨灓缂傚牓浜堕弻锝夋偄閸濄儲鍤傞梻鍌氬鐎氫即鐛箛娑樺窛闁哄鍨甸懓鍨攽閳藉棗鐏ョ€规洜鏁婚幃鎯х暦閸モ晝锛濋梺绋挎湰閼归箖鍩€椤掆偓缂嶅﹪鐛幇鏉跨濞达絿鎳撴禒閬嶆⒑闂堟单鍫ュ疾濠婂牆鐤炬い鎺戝閸婂爼鏌ｉ幇鐗堟锭閻㈩垰鐖奸弻宥囨嫚閼碱剛顔掗梺鍝勭焿缂嶄線鏁愰悙渚晢闁逞屽墯閹便劑鍩€椤掆偓閳规垿鎮欓崣澶婃闁诲孩鐭崡鎶芥偘椤旇法鐤€婵炴垶鐟ラ埀顒冨吹缁辨挸顓奸崶銊モ偓鐘绘⒒娴ｈ棄鍚瑰┑顔藉▕濮婅棄顓兼径濠勫姦濡炪倖甯掗ˇ鎵矆閳х惤Player濠电姷鏁搁崑鐘诲箵椤忓棛绀婇柍褜鍓氱换娑欏緞鐎ｎ偆顦伴悗娈垮櫘閸嬪﹥淇婇崼鏇炵倞闁冲搫鍠涚槐鎶芥⒒娴ｇ瓔娼愰柛搴＄－婢规洟顢橀姀鐘殿啈?
        return if (result is SongUrlResult.Failure && hasCachedData && !isYouTubeMusicTrack(song)) {
            NPLogger.d("NERI-PlayerManager", "缂傚倸鍊搁崐鎼佸磹閹间礁鐤い鏍仜閸ㄥ倿鏌涢敂璇插箹闁搞劍绻堥弻銈夊箹娴ｈ閿紓浣稿閸嬨倕顕ｉ崼鏇為唶妞ゆ劦婢€閸戜粙鎮跺顓犵畺缂佺粯绋掑蹇涘礈瑜嶉崺灞解攽閳藉棗浜濋柣鐔村劦閺佸啴濮€閳╁啫顎撻梺鎯х箰濠€閬嶆晬濠靛鈷戦悷娆忓閸斻倗鐥紒銏犲籍妤犵偞顨婇幃鈺冪磼濡厧寮虫繝鐢靛仦閸ㄥ爼鈥﹂崼鈶╁亾濮橆偄宓嗛柡宀嬬秮婵℃悂濡烽妷銏″瘱闂備礁缍婇ˉ鎾寸箾閳ь剛鈧鍠楅幐鎶藉箖椤忓牆鐒垫い鎺戝閸婄敻鏌ょ喊鍗炲缁炬儳銈搁弻锝夊箛闂堟稑顫╁銈嗘礃缁捇寮? $cacheKey")
            // Use a synthetic offline URL so ExoPlayer can hit the cache by key.
            val fallbackAudioInfo = _currentPlaybackAudioInfo.value
            SongUrlResult.Success(
                url = "http://offline.cache/$cacheKey",
                audioInfo = fallbackAudioInfo
            )
        } else {
            result
        }
    }

    private fun shouldAttemptUrlRefresh(
        error: PlaybackException,
        song: SongItem?,
        isOfflineCache: Boolean
    ): Boolean {
        if (song == null || isOfflineCache) return false
        if (isLocalSong(song)) return false
        return error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    }

    private fun resumePlaybackFallback(
        seekPositionMs: Long?,
        resumePlaybackAfterRefresh: Boolean
    ) {
        mainScope.launch {
            val resolvedSeekPositionMs = seekPositionMs?.coerceAtLeast(0L)
            if (resolvedSeekPositionMs != null) {
                player.seekTo(resolvedSeekPositionMs)
                _playbackPositionMs.value = resolvedSeekPositionMs
            }
            player.playWhenReady = resumePlaybackAfterRefresh
            if (resumePlaybackAfterRefresh) {
                player.play()
            } else {
                player.pause()
            }
        }
    }

    private fun refreshCurrentSongUrl(
        resumePositionMs: Long,
        allowFallback: Boolean,
        reason: String,
        bypassCooldown: Boolean = false,
        fallbackSeekPositionMs: Long? = null,
        resumePlaybackAfterRefresh: Boolean = true,
        resumedPlaybackCommandSource: PlaybackCommandSource? = null
    ) {
        val song = _currentSongFlow.value ?: return
        if (isLocalSong(song)) return
        if (urlRefreshInProgress) {
            if (allowFallback) {
                resumePlaybackFallback(
                    seekPositionMs = fallbackSeekPositionMs,
                    resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
                )
            }
            return
        }

        val cacheKey = computeCacheKey(song)
        val now = SystemClock.elapsedRealtime()
        if (!bypassCooldown && lastUrlRefreshKey == cacheKey && now - lastUrlRefreshAtMs < URL_REFRESH_COOLDOWN_MS) {
            if (allowFallback) {
                resumePlaybackFallback(
                    seekPositionMs = fallbackSeekPositionMs,
                    resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
                )
            } else {
                clearPendingSeekPosition()
                // url 闂傚倸鍊风粈渚€骞夐敍鍕殰闁跨喓濮寸紒鈺呮⒑椤掆偓缁夋挳鎷戦悢灏佹斀闁绘ê寮堕幖鎰磼閳ь剟宕橀浣镐壕闁荤喐婢橀顏呯節閵忊槅鐒界紒顔硷躬瀹曪繝鎮欓埡鍌ゆ綌闂備胶鎳撻悘婵嬪疮椤愶妇宓侀柟鐑樻⒒绾惧吋淇婇妶鍕厡濠⒀呮暩閳ь剚顔栭崳顕€宕抽敐澶婄畺闁宠桨鑳堕弳锕傛煕閵夛絽鍔楅柛瀣尰缁绘繈宕堕妸褍骞堥梻渚€娼ч敍蹇涘礃瑜庨～鏇㈡⒒娓氣偓閳ь剛鍋涢懟顖炲储閹绢喗鐓曢悗锝庡亝瀹曞矂鏌＄仦璇插闁宠棄顦灒闁兼祴鏅涙慨浼存⒒娴ｈ櫣甯涢柛鈺侊功缁骞樺畷鍥ㄦ濠殿喗锕╅崣蹇曟閿濆悿褰掓晲閸噥浠╅梺鐟版▕閸犳鎹㈠☉銏犵闁绘劖娼欓惃鎴︽⒑缁嬫鍎愰柟鍛婄摃閻忓姊洪幐搴ｇ畵婵☆偅绋掗弲鍫曟焼瀹ュ棛鍘甸梺鍦檸閸ｎ喖螞閹寸姷纾奸弶鍫涘妼濞搭喗銇勯姀鈽呰€块柟顔哄灮缁瑩宕归鑺ョ彟闂傚倸鍊峰ù鍥Υ閳ь剟鏌涚€ｎ偅宕岄柡宀€鍠撻幉鎾礋椤愩埄娼婇梻浣侯焾椤戝棝骞愰悙顒傤浄闁挎洖鍊归崵鍐煃閸濆嫬鏆為柛鐘冲浮濮婄粯鎷呴崨濠冨創闁诲孩姘ㄩ崗妯虹暦閸︻厽宕夊〒姘煎灣閸旂兘姊洪棃娑氬婵☆偒鍘奸妴鎺楁嚋閸忓摜绠氬銈嗙墬缁海鏁☉銏＄厽?
                consecutivePlayFailures++
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.player_playback_network_error)))
                if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
                    mainScope.launch { stopPlaybackPreservingQueue(clearMediaUrl = true) }
                } else {
                    mainScope.launch { handleTrackEnded() }
                }
            }
            return
        }

        urlRefreshInProgress = true
        lastUrlRefreshKey = cacheKey
        lastUrlRefreshAtMs = now

        ioScope.launch {
            try {
                NPLogger.d("NERI-PlayerManager", "Refreshing stream url ($reason): $cacheKey")
                val result = resolveSongUrl(
                    song = song,
                    forceRefresh = isYouTubeMusicTrack(song)
                )
                if (result is SongUrlResult.Success &&
                    _currentSongFlow.value?.sameIdentityAs(song) == true
                ) {
                    maybeUpdateSongDuration(song, result.durationMs ?: 0L)
                    withContext(Dispatchers.Main) {
                        applyResolvedMediaItem(
                            _currentSongFlow.value ?: song,
                            result.url,
                            result.mimeType,
                            result.expectedContentLength,
                            result.audioInfo,
                            resumePositionMs,
                            resumePlaybackAfterRefresh
                        )
                        consecutivePlayFailures = 0
                        if (
                            resumePlaybackAfterRefresh &&
                            resumedPlaybackCommandSource == PlaybackCommandSource.LOCAL
                        ) {
                            emitPlaybackCommand(
                                type = "PLAY",
                                source = resumedPlaybackCommandSource,
                                positionMs = resumePositionMs.coerceAtLeast(0L),
                                currentIndex = currentIndex
                            )
                        }
                    }
                } else if (allowFallback) {
                    resumePlaybackFallback(
                        seekPositionMs = fallbackSeekPositionMs,
                        resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
                    )
                } else {
                    clearPendingSeekPosition()
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.player_playback_network_error)))
                    withContext(Dispatchers.Main) { pause(commandSource = PlaybackCommandSource.REMOTE_SYNC) }
                }
            } finally {
                urlRefreshInProgress = false
            }
        }
    }

    private suspend fun applyResolvedMediaItem(
        song: SongItem,
        url: String,
        mimeType: String?,
        expectedContentLength: Long?,
        audioInfo: PlaybackAudioInfo?,
        resumePositionMs: Long,
        resumePlaybackAfterRefresh: Boolean
    ) {
        if (_currentSongFlow.value?.sameIdentityAs(song) != true) return

        val cacheKey = computeCacheKey(song)
        invalidateMismatchedCachedResource(
            cacheKey = cacheKey,
            expectedContentLength = expectedContentLength
        )
        val mediaItem = buildMediaItem(song, url, cacheKey, mimeType)

        _currentMediaUrl.value = url
        _currentPlaybackAudioInfo.value = audioInfo
        currentMediaUrlResolvedAtMs = SystemClock.elapsedRealtime()
        persistState()

        withContext(Dispatchers.Main) {
            preparePlayerForManagedStart(resolvePlaybackStartPlan(shouldFadeIn = false, fadeDurationMs = 0L))
            resetTrackEndDeduplicationState()
            player.setMediaItem(mediaItem)
            syncExoRepeatMode()
            if (resumePositionMs > 0) {
                player.seekTo(resumePositionMs)
                _playbackPositionMs.value = resumePositionMs
            }
            player.prepare()
            player.playWhenReady = resumePlaybackAfterRefresh
            if (resumePlaybackAfterRefresh) {
                player.play()
            } else {
                player.pause()
            }
        }
    }

    /** 婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘埈娼ラ梻鍌欒兌閸庣敻宕滃┑鍠㈡椽鎮㈡總澶婃闂佸湱铏庨崰鏍儗濡も偓椤法鎹勯搹瑙勫櫚闂佹寧娲栭崐褰掓偂閺囩喐鍙忔慨妞诲亾缂佺姵鐗曢‖濠囶敊閻ｅ瞼鐦堥梺閫炲苯澧紒妤冨枛閸┾偓妞ゆ巻鍋撴い鏇秮閹粙宕滈崣澶嬫珜闂備線鈧偛鑻晶顕€鏌ㄩ弴妯虹伄闁逞屽墴濞佳囧箺濠婂懎顥氬ù鐘差儐閻撴瑩鏌熼鍡楁嫅缁辩偤姊洪崷顓熸珪濠电偐鍋撻梺鍝勮閸斿矂鍩ユ径濞㈢喖宕归鍛磾濠碉紕鍋戦崐鎰板极椤忓牆鐐婄憸宥夊吹閹存績鏀介柣妯款嚋瀹搞儵鎮楀鐓庡箻缂侇喖顭烽獮鎺懳旀担鍝勫箞闂備礁婀遍崕銈夊箰妤ｅ啫绠犲ù锝堟绾剧厧危鐏炲墽澧€殿噮鍠氶埀顒冾潐濞叉﹢銆冩繝鍌滄殾闁绘挸瀵掗悡銉╂煕閹板墎绋婚柣搴°偢濮婄粯鎷呯憴鍕╀户濠电偟鍘у鈥崇暦濠靛棭鍚嬮柛婊冨暢閳ь剙鐏濋湁闁稿繐鍚嬬紞鎴︽煙閻熸壆鍩ｉ柡宀嬬到铻栭柍褜鍓熼幃褎绻濋崶銊モ偓?*/
    private fun checkLocalCache(song: SongItem): SongUrlResult? {
        val context = application
        val localReference = AudioDownloadManager.getLocalPlaybackUri(context, song) ?: return null
        // 濠电姷鏁告慨鐑姐€傛禒瀣劦妞ゆ巻鍋撻柛鐔锋健閸┾偓妞ゆ巻鍋撶紓宥咃躬楠炲啫螣鐠囪尙绐炴繝鐢靛Т鐎涒晛顩奸妸鈺傜厽闊洦娲栨禒褔鏌涚€ｎ偅宕岄柡灞诲姂婵¤埖寰勭€ｎ剙骞愰梻浣告啞閸旀垿宕濇惔銊ユ槬闁挎繂顦伴悡娑㈡煕閳╁啰鈼ラ柟鎻掑⒔閳ь剚顔栭崰姘跺极婵犳哎鈧線寮崼婵嗚€垮┑鐐叉缁绘劗绱掗埡鍛拻濞达絿鎳撻婊呯磼鐎ｎ偄鐏撮柟顖氼槹缁虹晫绮欓幐搴ｂ偓?MediaMetadataRetriever 闂傚倷娴囬褏鎹㈤幇顔藉床闁瑰濮靛畷鏌ユ煕閳╁啰鈯曢柛搴★攻閵囧嫰寮介妸褏鐓侀柣搴㈢瀹€鎼佸箖瑜版帒鐐婇柕濞垮劤缁佸嘲鈹?
        val durationMs = if (song.durationMs <= 0L) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                val localUri = localReference.toUri()
                when (localUri.scheme?.lowercase()) {
                    "content", "android.resource" -> retriever.setDataSource(context, localUri)
                    "file" -> retriever.setDataSource(localUri.path)
                    null, "" -> retriever.setDataSource(localReference)
                    else -> retriever.setDataSource(context, localUri)
                }
                val d = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                retriever.release()
                d
            } catch (_: Exception) { null }
        } else null
        val localAudioInfo = buildLocalPlaybackAudioInfo(localReference.toUri())
        return SongUrlResult.Success(
            url = localReference,
            durationMs = durationMs,
            audioInfo = mergeLocalPlaybackAudioInfoWithRemoteQuality(
                localAudioInfo = localAudioInfo,
                previousAudioInfo = _currentPlaybackAudioInfo.value
                    ?.takeIf { _currentSongFlow.value?.sameIdentityAs(song) == true }
            )
        )
    }

    /** 闂傚倸鍊风粈渚€骞夐敓鐘冲仭妞ゆ牗绋撻々鍙夌節闂堟稒锛旈柤鏉跨仢闇夐柨婵嗙墑閳ь兘鍋撻梺绋款儐閹瑰洭骞冮悜鑺ュ亱闁割偆鍠愮€氭娊姊绘担鍛婃儓婵☆偄顕濠勬崉閵婏富娼熼梺瑙勫礃椤曆呯不閼姐倐鍋撶憴鍕婵炶绠撻獮澶嬬附閸涘ň鎷洪悷婊呭鐢鈽夎缁辨帗寰勭€ｎ偄鍞夐悗瑙勬磸閸ㄤ粙鐛€ｎ喗鍋愰柛鎰▕閳ь剚鐩娲川婵犲嫮绱伴梺绋垮濡炰粙骞冨Δ鍛殤妞ゆ帒鍊婚敍婊堟⒑鐠団€崇€婚柛娑卞墲濡炬悂姊绘担鍛婂暈妞ゎ厼鐗撻敐鐐村緞閹邦剛鐣鹃梺鍝勫暙閻楀棝宕￠搹顐犱簻闊洦鎸婚ˉ鐘炽亜閿旇娅嶆慨濠勭帛缁楃喖宕惰椤晠姊虹拠鑼缂佽鐗嗛悾鐑藉閵堝憘褍顭跨捄鐚村姛妞ゃ垹鎳忕换婵嬫偨闂堟刀銏＄箾閺夋垵鈧悂婀侀梺鍝勬川閸犳劙宕ｈ箛鏃傜瘈闂傚牊绋掗ˉ鐘绘煟韫囨矮鍚紒杈ㄥ浮楠炲鈧綆浜跺ú顓㈡⒑闁偛鑻晶顖滅磼鐎ｎ偄娴柍銉畵瀹曞ジ濡烽婊呪棨闂備焦瀵у濠氬磻閹惧灈鍋撳顓狀暡闁靛洤瀚板浠嬪Ω閿旂偓顓荤紓鍌欑椤︻垶濡堕幖浣歌摕闁绘棁銆€閸嬫挸鈽夊▍顓т簼閹便劑宕堕埡鍌氭瀾闂佺粯顨呴悧蹇涘矗閳ь剙螖閻橀潧浠﹂柨鏇樺灲閵嗕礁顫滈埀顒勫箖閵忋倕宸濆┑鐘插暟閻ｎ亪姊绘担钘夊惞濠殿喖绉瑰畷姗€鈥﹂幋婊呮／缂?*/
    private fun checkExoPlayerCache(cacheKey: String): Boolean {
        return try {
            if (!::cache.isInitialized) return false

            val cachedSpans = cache.getCachedSpans(cacheKey)
            if (cachedSpans.isEmpty()) return false

            val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
            if (contentLength <= 0L) {
                NPLogger.d("NERI-PlayerManager", "缂傚倸鍊搁崐鎼佸磹閹间礁纾圭憸鐗堝笒缁犱即鏌熼梻瀵稿妽闁稿鍊濋弻鏇熺箾閻愵剚鐝曢梺鎶芥敱閸旀瑩寮诲☉鈶┾偓锕傚箣濠靛洨浜俊鐐€栭弻銊╂晝椤忓牆钃熼柨鐔哄Т闁卞洦銇勯幇鍓佺？闁轰焦鐗犲铏圭磼濡粯鍎撶紓浣虹帛缁诲牆顕ｉ銏╁悑闁搞儻闄勯崟鍐⒑閸忚偐銈撮柡鍛箞楠炲銇愰幒鎾嫼闂佸憡绋戦敃銈夊吹椤掑嫭鐓曢柕濠忕畱閳绘洜鈧鍣崑濠冧繆閸洖鐐婇柍鍝勫暟閸斿綊姊绘担鍝勫付妞ゎ偅娲熷畷鎰板即閻愨晜鐏侀梺缁橆焽缁垶鎮￠弴銏㈠彄闁搞儯鍔嶉埛鎺楁煟椤撶儑鍔熺紒? $cacheKey")
                return false
            }

            val orderedSpans = cachedSpans.sortedBy { it.position }
            var coveredUntil = 0L
            for (span in orderedSpans) {
                if (span.position > coveredUntil) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "缂傚倸鍊搁崐鎼佸磹閹间礁纾圭憸鐗堝笒缁犱即鏌熼梻瀵稿妽闁稿鍊濋弻鏇熺箾瑜夐崑鎾绘煕鐎ｎ偅灏柍钘夘槸閳诲秹顢樿缁ㄥ鏌ㄩ弴妯虹伈妞ゃ垺妫冨畷鍗炩枎閹寸姴骞囬梻鍌欒兌缁垶宕归崼鏇椻偓锕傚炊椤戯箑娲、娑㈡倷鐎电寮虫繝鐢靛仦閸ㄥ爼鏁嬪銈冨妽閻熝呮閹烘嚦鏃堝焵椤掑媻鍥濞戞碍娈鹃梺闈涱槴閺呪晠寮鍡欑瘈濠电姴鍊搁顏呯箾閸稑鐏紒杈ㄦ尰閹峰懐绮欐惔鎾充壕闁芥ê顦遍弳锕傛煟閵忋埄鐒鹃柛姘秺楠炴牕菐椤掆偓婵¤偐绱掗埀顒勫磼濞戞氨鐦堟繝鐢靛Т閸婃悂寮抽悢闈? $cacheKey @ ${span.position}"
                    )
                    return false
                }
                coveredUntil = maxOf(coveredUntil, span.position + span.length)
                if (coveredUntil >= contentLength) break
            }

            val isComplete = coveredUntil >= contentLength
            if (isComplete) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "闂傚倸鍊烽懗鍫曞箠閹剧粯鍊舵慨妯挎硾绾惧潡鏌熼幆鐗堫棄闁哄嫨鍎抽埀顒€鍘滈崑鎾绘煃瑜滈崜鐔煎春閳ь剚銇勯幒鎴濃偓褰掑吹閳ь剟姊洪崫鍕⒈闁告挾鍠庨锝夊箮閼恒儲娅滈梺鎼炲劗閺呮繈鏁嶅┑瀣拺閻熸瑥瀚崝銈囩棯缂併垹寮鐐搭殜閹晝绱掑Ο鐓庡箰濠电偠鎻徊浠嬪箹椤愶负鈧倿鎳栭埞鎯т壕? $cacheKey, 闂傚倸鍊搁崐鎼佸磹閻㈢纾婚柟鍓х帛閻撴洘銇勯鐔风仴闁哄绋掗妵? $contentLength, 闂傚倸鍊烽懗鍓佸垝椤栫偑鈧啴宕ㄩ鍏兼そ閺佸啴宕掗妶鍡樻珦? ${cachedSpans.size}"
                )
            } else {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "缂傚倸鍊搁崐鎼佸磹閹间礁纾圭憸鐗堝笒缁犱即鏌熼梻瀵稿妽闁稿鍊濋弻鏇熺箾閻愵剚鐝旈柣搴㈣壘椤︿即濡甸崟顔剧杸闁圭偓鍓氭导鈧梻浣告惈濡瑥鐣濋幖浣歌摕闁斥晛鍟刊鏉戙€掑鐓庣仭妞ゃ儲鎸搁—鍐Χ閸屾稒鐝﹂梺绋款儏閿曨亪鐛崱娑樼妞ゆ牗鑹鹃崬銊╂⒑濮瑰洤鐏柡浣规倐钘濇い鏃傛櫕缁犻箖鏌熺€电浠﹂悘蹇ｅ幘缁辨帗寰勭€ｎ偄鍞夊Δ鐘靛仜濡瑩骞嗛弮鍫澪╅柨鏇楀亾濞寸姵妞藉娲濞淬倖绋戦悾婵嬪焵椤掑嫬鐒垫い鎺戝暙閻ㄨ櫣绱掓潏銊ユ诞妤犵偛妫滈ˇ鏉懨归悩顔肩伈闁? $cacheKey, 闂備浇顕у锕傦綖婢舵劖鍋ら柡鍥╁С閻掑﹥銇勮箛鎾跺闁? $coveredUntil/$contentLength"
                )
            }

            isComplete
        } catch (e: Exception) {
            NPLogger.w("NERI-PlayerManager", "婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呭畷宀勬煛瀹€瀣？濞寸媴濡囬幏鐘诲箵閹烘嚩鎴犵磽閸屾瑧鍔嶉柛鐐电帛娣囧﹪宕堕鈧悞鍨亜閹哄秷鍏岄柍顖涙礋閺屻劌顫濋懜鐢靛帾? ${e.message}")
            false
        }
    }

    private fun qualityLabelForNetease(key: String): String = when (key) {
        "standard" -> getLocalizedString(R.string.quality_standard)
        "higher" -> getLocalizedString(R.string.settings_audio_quality_higher)
        "exhigh" -> getLocalizedString(R.string.quality_very_high)
        "lossless" -> getLocalizedString(R.string.quality_lossless)
        "hires" -> getLocalizedString(R.string.quality_hires)
        "jyeffect" -> getLocalizedString(R.string.quality_hd_surround)
        "sky" -> getLocalizedString(R.string.quality_surround)
        "jymaster" -> getLocalizedString(R.string.settings_audio_quality_jymaster)
        else -> key
    }

    private fun qualityLabelForBili(key: String): String = when (key) {
        "dolby" -> getLocalizedString(R.string.quality_dolby)
        "hires" -> getLocalizedString(R.string.quality_hires)
        "lossless" -> getLocalizedString(R.string.quality_lossless)
        "high" -> getLocalizedString(R.string.settings_audio_quality_high)
        "medium" -> getLocalizedString(R.string.settings_audio_quality_medium)
        "low" -> getLocalizedString(R.string.settings_audio_quality_low)
        else -> key
    }

    private fun qualityLabelForYouTube(key: String): String = when (key) {
        "low" -> getLocalizedString(R.string.settings_audio_quality_low)
        "medium" -> getLocalizedString(R.string.settings_audio_quality_medium)
        "high" -> getLocalizedString(R.string.settings_audio_quality_high)
        "very_high" -> getLocalizedString(R.string.quality_very_high)
        else -> key
    }

    private fun buildNeteaseQualityOptions(): List<PlaybackQualityOption> = listOf(
        PlaybackQualityOption("standard", qualityLabelForNetease("standard")),
        PlaybackQualityOption("higher", qualityLabelForNetease("higher")),
        PlaybackQualityOption("exhigh", qualityLabelForNetease("exhigh")),
        PlaybackQualityOption("lossless", qualityLabelForNetease("lossless")),
        PlaybackQualityOption("hires", qualityLabelForNetease("hires")),
        PlaybackQualityOption("jyeffect", qualityLabelForNetease("jyeffect")),
        PlaybackQualityOption("sky", qualityLabelForNetease("sky")),
        PlaybackQualityOption("jymaster", qualityLabelForNetease("jymaster"))
    )

    private fun buildYouTubeQualityOptions(): List<PlaybackQualityOption> = listOf(
        PlaybackQualityOption("low", qualityLabelForYouTube("low")),
        PlaybackQualityOption("medium", qualityLabelForYouTube("medium")),
        PlaybackQualityOption("high", qualityLabelForYouTube("high")),
        PlaybackQualityOption("very_high", qualityLabelForYouTube("very_high"))
    )

    private fun inferBiliQualityKey(biliAudioStream: moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo): String {
        return when {
            biliAudioStream.qualityTag == "dolby" -> "dolby"
            biliAudioStream.qualityTag == "hires" -> "hires"
            biliAudioStream.bitrateKbps >= 180 -> "high"
            biliAudioStream.bitrateKbps >= 120 -> "medium"
            else -> "low"
        }
    }

    private fun buildBiliQualityOptions(
        availableStreams: List<moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo>
    ): List<PlaybackQualityOption> {
        val availableKeys = availableStreams
            .map(::inferBiliQualityKey)
            .distinct()
        val orderedKeys = listOf("dolby", "hires", "lossless", "high", "medium", "low")
        return orderedKeys
            .filter { it in availableKeys }
            .map { PlaybackQualityOption(it, qualityLabelForBili(it)) }
    }

    private fun normalizeNeteaseMimeType(type: String?): String? {
        val normalizedType = type
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when (normalizedType) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "m4a", "mp4" -> "audio/mp4"
            else -> if (normalizedType.contains('/')) normalizedType else "audio/$normalizedType"
        }
    }

    private fun buildLocalPlaybackAudioInfo(song: SongItem): PlaybackAudioInfo? {
        return runCatching {
            LocalMediaSupport.inspect(application, song)
        }.getOrNull()?.let { details ->
            PlaybackAudioInfo(
                source = PlaybackAudioSource.LOCAL,
                codecLabel = deriveCodecLabel(details.audioMimeType ?: details.mimeType),
                mimeType = details.audioMimeType ?: details.mimeType,
                bitrateKbps = details.bitrateKbps,
                sampleRateHz = details.sampleRateHz,
                bitDepth = details.bitsPerSample,
                channelCount = details.channelCount
            )
        }
    }

    private fun buildLocalPlaybackAudioInfo(localUri: Uri): PlaybackAudioInfo? {
        return runCatching {
            LocalMediaSupport.inspect(application, localUri)
        }.getOrNull()?.let { details ->
            PlaybackAudioInfo(
                source = PlaybackAudioSource.LOCAL,
                codecLabel = deriveCodecLabel(details.audioMimeType ?: details.mimeType),
                mimeType = details.audioMimeType ?: details.mimeType,
                bitrateKbps = details.bitrateKbps,
                sampleRateHz = details.sampleRateHz,
                bitDepth = details.bitsPerSample,
                channelCount = details.channelCount
            )
        }
    }

    private fun buildNeteasePlaybackAudioInfo(
        parsed: NeteasePlaybackResponseParser.PlaybackResult.Success,
        resolvedQualityKey: String,
        fallbackDurationMs: Long
    ): PlaybackAudioInfo {
        val mimeType = normalizeNeteaseMimeType(parsed.type)
        return PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityKey = resolvedQualityKey,
            qualityLabel = qualityLabelForNetease(resolvedQualityKey),
            qualityOptions = buildNeteaseQualityOptions(),
            codecLabel = deriveCodecLabel(mimeType) ?: parsed.type?.uppercase(),
            mimeType = mimeType,
            bitrateKbps = if (parsed.notice == NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                null
            } else {
                estimateBitrateKbps(parsed.contentLength, fallbackDurationMs)
            }
        )
    }

    private fun buildBiliPlaybackAudioInfo(
        selectedStream: moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo,
        availableStreams: List<moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo>
    ): PlaybackAudioInfo {
        val qualityKey = inferBiliQualityKey(selectedStream)
        return PlaybackAudioInfo(
            source = PlaybackAudioSource.BILIBILI,
            qualityKey = qualityKey,
            qualityLabel = qualityLabelForBili(qualityKey),
            qualityOptions = buildBiliQualityOptions(availableStreams),
            codecLabel = deriveCodecLabel(selectedStream.mimeType),
            mimeType = selectedStream.mimeType,
            bitrateKbps = selectedStream.bitrateKbps
        )
    }

    private fun buildYouTubePlaybackAudioInfo(
        playableAudio: moe.ouom.neriplayer.core.api.youtube.YouTubePlayableAudio
    ): PlaybackAudioInfo {
        val qualityKey = inferYouTubeQualityKeyFromBitrate(playableAudio.bitrateKbps)
        return PlaybackAudioInfo(
            source = PlaybackAudioSource.YOUTUBE_MUSIC,
            qualityKey = qualityKey,
            qualityLabel = qualityLabelForYouTube(qualityKey),
            qualityOptions = buildYouTubeQualityOptions(),
            codecLabel = deriveCodecLabel(playableAudio.mimeType),
            mimeType = playableAudio.mimeType,
            bitrateKbps = playableAudio.bitrateKbps,
            sampleRateHz = playableAudio.sampleRateHz
        )
    }

    private fun buildNeteaseQualityCandidates(preferredQuality: String): List<String> {
        val normalizedQuality = preferredQuality.trim().lowercase().ifBlank { "exhigh" }
        val preferredIndex = NETEASE_QUALITY_FALLBACK_ORDER.indexOf(normalizedQuality)
        return if (preferredIndex >= 0) {
            NETEASE_QUALITY_FALLBACK_ORDER.drop(preferredIndex)
        } else {
            listOf(normalizedQuality, "exhigh", "standard").distinct()
        }
    }

    private fun shouldRetryNeteaseWithLowerQuality(
        reason: NeteasePlaybackResponseParser.FailureReason
    ): Boolean {
        return reason == NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION ||
            reason == NeteasePlaybackResponseParser.FailureReason.NO_PLAY_URL
    }

    private fun buildNeteaseSuccessResult(
        parsed: NeteasePlaybackResponseParser.PlaybackResult.Success,
        resolvedQualityKey: String,
        fallbackDurationMs: Long
    ): SongUrlResult.Success {
        val finalUrl = if (parsed.url.startsWith("http://")) {
            parsed.url.replaceFirst("http://", "https://")
        } else {
            parsed.url
        }
        val noticeMessage = when (parsed.notice) {
            NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP ->
                getLocalizedString(R.string.player_netease_preview_only)
            null -> null
        }
        return SongUrlResult.Success(
            url = finalUrl,
            noticeMessage = noticeMessage,
            expectedContentLength = parsed.contentLength,
            audioInfo = buildNeteasePlaybackAudioInfo(
                parsed = parsed,
                resolvedQualityKey = resolvedQualityKey,
                fallbackDurationMs = fallbackDurationMs
            )
        )
    }

    private fun shouldReplaceCachedPreviewResource(
        cachedContentLength: Long,
        expectedContentLength: Long
    ): Boolean {
        val contentLengthGap = expectedContentLength - cachedContentLength
        return cachedContentLength > 0L &&
            expectedContentLength > 0L &&
            contentLengthGap >= 512L * 1024L &&
            cachedContentLength * 100L < expectedContentLength * 85L
    }

    private suspend fun invalidateMismatchedCachedResource(
        cacheKey: String,
        expectedContentLength: Long?
    ) = withContext(Dispatchers.IO) {
        val expectedLength = expectedContentLength?.takeIf { it > 0L } ?: return@withContext
        if (!::cache.isInitialized) return@withContext

        try {
            val cachedSpans = cache.getCachedSpans(cacheKey)
            if (cachedSpans.isEmpty()) return@withContext

            val cachedContentLength = ContentMetadata.getContentLength(
                cache.getContentMetadata(cacheKey)
            )
            if (!shouldReplaceCachedPreviewResource(cachedContentLength, expectedLength)) {
                return@withContext
            }

            NPLogger.w(
                "NERI-PlayerManager",
                "婵犵數濮烽。钘壩ｉ崨鏉戠；闁逞屽墴閺屾稓鈧綆鍋呯亸鎵磼缂佹娲寸€殿喖鐖奸獮瀣敇閻愭彃顥嶉梻鍌欒兌绾爼寮插☉銏″剶濠靛倻顭堢粻顖炴煟濡鍤欓柛妤佸▕閺岋綁骞囬鐔虹▏闂佸憡鎹佸▔娑㈠煘閹达附鍊烽柤纰卞墮椤ｅ搫顪冮妶鍡楃仴婵炶绠撳鏌ュ醇閺囩偛鍞ㄥ銈嗗姂閸婃鏁嶅┑瀣拺閻熸瑥瀚崝銈囩棯缂併垹寮鐐搭殜閹晝绱掑Ο鐓庡汲婵犵數鍋為崹鎯版懌濠电姭鍋撻柟娈垮枤绾惧ジ鏌￠崒娑卞劌闁绘挸銈搁弻鐔碱敊閼测晛鐓熼悗瑙勬礀瀹曨剝鐏冩繛杈剧悼绾爼宕戦幘缁樺仺闁告稑锕﹂崢閬嶆⒑閸︻厼鍔嬮柛銊ㄥ煐瀵板嫰宕熼娑樹壕閻熸瑥瀚粈鍐煟閹绢垪鍋撳畷鍥ㄦ濠殿喗顭堝▔娑氱棯瑜旈幃褰掑箒閹烘垵顬夊┑鐐茬墱閸ｏ絽顫忛悜妯诲闁规鍠栨俊浠嬫⒑閻熺増鍟炲┑鐐诧躬楠? key=$cacheKey, cached=$cachedContentLength, expected=$expectedLength"
            )
            cache.removeResource(cacheKey)
        } catch (e: Exception) {
            NPLogger.w(
                "NERI-PlayerManager",
                "婵犵數濮烽弫鎼佸磻閻愬搫绠伴柟闂寸缁犵娀鏌熼悧鍫熺凡缂佺姵鐗曢埞鎴︽偐閹绘帩浠鹃柣搴㈢瀹€鎼佸蓟閿熺姴鐐婇柍杞版濞岊亪姊虹粙鍨劉婵﹤婀遍幑銏犫槈閵忕娀鍞跺┑鐘茬仛閸旀洟宕滈搹顐ょ瘈闁冲皝鍋撻柛鏇ㄥ幖閳峰牏绱撴担浠嬪摵闁圭懓娲悰顔嘉熼搹瑙勬闂佹悶鍎崝宥夊窗閺嵮€鏀介柣姗嗗枛閻忣亪鏌涙惔銈呬汗闁告帗甯″畷婊嗩槼闁? key=$cacheKey, error=${e.message}"
            )
        }
    }

    private suspend fun getNeteaseSongUrl(song: SongItem, suppressError: Boolean = false): SongUrlResult = withContext(Dispatchers.IO) {
        try {
            val qualityCandidates = buildNeteaseQualityCandidates(preferredQuality)
            var previewFallback: SongUrlResult.Success? = null
            var lastFailureReason: NeteasePlaybackResponseParser.FailureReason? = null

            for ((index, quality) in qualityCandidates.withIndex()) {
                val resp = neteaseClient.getSongDownloadUrl(
                    song.id,
                    level = quality
                )
                NPLogger.d("NERI-PlayerManager", "id=${song.id}, level=$quality, resp=$resp")

                when (val parsed = NeteasePlaybackResponseParser.parsePlayback(resp, song.durationMs)) {
                    is NeteasePlaybackResponseParser.PlaybackResult.RequiresLogin -> {
                        return@withContext SongUrlResult.RequiresLogin
                    }

                    is NeteasePlaybackResponseParser.PlaybackResult.Success -> {
                        val success = buildNeteaseSuccessResult(
                            parsed = parsed,
                            resolvedQualityKey = quality,
                            fallbackDurationMs = song.durationMs
                        )
                        if (parsed.notice != NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                            if (quality != preferredQuality) {
                                NPLogger.w(
                                    "NERI-PlayerManager",
                                    "缂傚倸鍊搁崐鎼佸磹閹间礁鐤い鏍仜閸ㄥ倿鎮规潪鎵Э闁挎繂鎲橀弮鈧幏鍛瑹椤栨盯鏁滈梻鍌欒兌椤㈠﹪骞撻鍛箚闁搞儺鍓欓惌妤€螖閿濆懎鏆為柛瀣剁秮閺岀喖骞嶉搹顐ｇ彅婵犵鈧偨鍋㈤柡灞剧洴楠炲顢涘鍛婵犳鍠栭敃銊ノ涢崟顖ｆ晣濠靛倻顭堥悙濠冦亜閹烘垵鈧摜绮堥崼銉︹拻濞达絽鎲￠崯鐐烘煟閻曞倻鐣甸柟顔ㄥ吘鏃堝礃椤忓棛鍔? id=${song.id}, preferred=$preferredQuality, resolved=$quality"
                                )
                            }
                            return@withContext success
                        }

                        previewFallback = success
                        if (index < qualityCandidates.lastIndex) {
                            NPLogger.w(
                                "NERI-PlayerManager",
                                "缂傚倸鍊搁崐鎼佸磹閹间礁鐤い鏍仜閸ㄥ倿鎮规潪鎵Э闁挎繂鎲橀弮鈧幏鍛瑹椤栨盯鏁滈梻鍌欒兌椤㈠﹪骞撻鍡楃筏閻犳亽鍔庢稉宥夋煛鐏炶鍔滈柣鎾存礋閺屾洘绔熼姘殨闁告繃顨婂鐑樺濞嗗繒妲ｉ梺鎼炲姀濞夋盯鎮鹃悜钘壩ㄩ柍鍝勶攻閺呫垺绻涙潏鍓хɑ濞存粈绮欏畷鎴炵節閸ャ劉鎷洪梺鍦焾濞寸兘鍩ユ径鎰厾婵炶尪顕ч悘锕傛煙椤栨艾鏆ｇ€规洘绮忛ˇ鎶芥倵濮橆厼鍝洪柡宀嬬節瀹曞爼濡烽妷褌鐥俊鐐€曠€涒晠鎮у鍛潟闁圭儤顨嗛崑鎴炵箾閸℃绠茬€瑰憡绻堥弻娑樷枎閹惧墎鏆梺璇″枛缂嶅﹪鐛崶顒€鐓涘ù锝呭濞煎酣姊? id=${song.id}, level=$quality"
                            )
                            continue
                        }
                        return@withContext success
                    }

                    is NeteasePlaybackResponseParser.PlaybackResult.Failure -> {
                        lastFailureReason = parsed.reason
                        if (index < qualityCandidates.lastIndex &&
                            shouldRetryNeteaseWithLowerQuality(parsed.reason)
                        ) {
                            NPLogger.w(
                                "NERI-PlayerManager",
                                "缂傚倸鍊搁崐鎼佸磹閹间礁鐤い鏍仜閸ㄥ倿鎮规潪鎵Э闁挎繂鎲橀弮鈧幏鍛瑹椤栨盯鏁滈梻鍌欒兌閹虫捇鎮洪妸褎宕查柛灞剧矊缁躲倝鏌熺粙鎸庢崳缂佺姴澧界槐鎾诲醇閵忕姌銉︺亜閿旇偐鐣甸柡灞炬礋瀹曞爼濡搁妷褌鎮ｉ梻浣烘嚀閸㈣尙绱炴繝鍐х箚闁绘垹鐡旈弫宥嗙節婵犲倹濯兼俊鑼厴濮婅櫣鎷犻懠顒傤唶缂備胶绮崹褰掑箲閵忋倕骞㈡繛鎴炵懃閸撱劌顪冮妶鍡欏⒈闁稿鍋ら崺娑㈠籍閸屾浜炬鐐茬仢閸旀碍绻涘顔煎籍闁诡喕鍗抽獮妯肩磼濡攱瀚藉┑鐘垫暩婵挳宕愭繝姘嚑闁告劦浜栭崑鎾绘偡閻楀牆鏆堢紓浣筋嚙閻楁挸顕ｉ懡銈勬勃缂佹稑顑呴幃鎴︽⒑閸撴彃浜栭柛銊ㄦ閻? id=${song.id}, level=$quality, reason=${parsed.reason}"
                            )
                            continue
                        }
                        break
                    }
                }
            }

            previewFallback?.let { return@withContext it }

            if (!suppressError) {
                val messageRes = when (lastFailureReason) {
                    NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION ->
                        R.string.player_netease_no_permission_switch_platform
                    NeteasePlaybackResponseParser.FailureReason.NO_PLAY_URL,
                    NeteasePlaybackResponseParser.FailureReason.UNKNOWN,
                    null -> R.string.error_no_play_url
                }
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(messageRes)))
            }
            SongUrlResult.Failure
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            NPLogger.e("NERI-PlayerManager", "Failed to get url", e)
            if (!suppressError) {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(R.string.player_playback_url_error_detail, e.message.orEmpty())
                    )
                )
            }
            SongUrlResult.Failure
        }
    }

    private suspend fun getBiliAudioUrl(song: SongItem, suppressError: Boolean = false): SongUrlResult = withContext(Dispatchers.IO) {
        try {
            val resolved = resolveBiliSong(song, biliClient)
            if (resolved == null || resolved.cid == 0L) {
                if (!suppressError) {
                    postPlayerEvent(
                        PlayerEvent.ShowError(
                            getLocalizedString(R.string.player_playback_video_info_unavailable)
                        )
                    )
                }
                return@withContext SongUrlResult.Failure
            }

            val (availableStreams, audioStream) = biliRepo.getAudioWithDecision(
                resolved.videoInfo.bvid,
                resolved.cid
            )

            if (audioStream?.url != null) {
                NPLogger.d("NERI-PlayerManager-BiliAudioUrl", audioStream.url)
                SongUrlResult.Success(
                    url = audioStream.url,
                    mimeType = audioStream.mimeType,
                    audioInfo = buildBiliPlaybackAudioInfo(audioStream, availableStreams)
                )
            } else {
                if (!suppressError) {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
                }
                SongUrlResult.Failure
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            NPLogger.e("NERI-PlayerManager", "Failed to get Bili play url", e)
            if (!suppressError) {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(R.string.player_playback_url_error_detail, e.message.orEmpty())
                    )
                )
            }
            SongUrlResult.Failure
        }
    }

    private suspend fun getYouTubeMusicAudioUrl(
        song: SongItem,
        suppressError: Boolean = false,
        forceRefresh: Boolean = false
    ): SongUrlResult = withContext(Dispatchers.IO) {
        val videoId = extractYouTubeMusicVideoId(song.mediaUri)
        if (videoId.isNullOrBlank()) {
            if (!suppressError) {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
            }
            return@withContext SongUrlResult.Failure
        }

        try {
            val directPlayableAudio = youtubeMusicPlaybackRepository.getBestPlayableAudio(
                videoId = videoId,
                preferredQualityOverride = youtubePreferredQuality,
                forceRefresh = forceRefresh,
                requireDirect = true,
                preferM4a = true
            )
            val playableAudio = directPlayableAudio?.takeIf { it.url.isNotBlank() }
                ?: run {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "YouTube Music direct stream unavailable, falling back for $videoId"
                    )
                    youtubeMusicPlaybackRepository.getBestPlayableAudio(
                        videoId = videoId,
                        preferredQualityOverride = youtubePreferredQuality,
                        forceRefresh = forceRefresh,
                        preferM4a = true
                    )
                }
            val resolvedPlayableAudio = playableAudio?.takeIf { it.url.isNotBlank() }
            if (resolvedPlayableAudio != null) {
                maybeUpdateSongDuration(song, resolvedPlayableAudio.durationMs)
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Resolved YouTube Music stream: videoId=$videoId, type=${resolvedPlayableAudio.streamType}, mime=${resolvedPlayableAudio.mimeType}, contentLength=${resolvedPlayableAudio.contentLength}"
                )
                SongUrlResult.Success(
                    url = resolvedPlayableAudio.url,
                    durationMs = resolvedPlayableAudio.durationMs.takeIf { it > 0L },
                    mimeType = resolvedPlayableAudio.mimeType,
                    audioInfo = buildYouTubePlaybackAudioInfo(resolvedPlayableAudio)
                )
            } else {
                if (!suppressError) {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(R.string.error_no_play_url)))
                }
                SongUrlResult.Failure
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            NPLogger.e("NERI-PlayerManager", "Failed to get YouTube Music play url", e)
            if (!suppressError) {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(R.string.player_playback_url_error_detail, e.message.orEmpty())
                    )
                )
            }
            SongUrlResult.Failure
        }
    }

    /**
     * 闂傚倸鍊风粈浣革耿闁秵鎯為幖娣妼閸屻劌霉閻樺樊鍎忔俊?Bilibili 闂傚倷娴囧畷鐢稿窗閹扮増鍋￠柕澶堝剻濞戞ǚ鏀介悗锝冨妷閸嬫捇宕掗悙鏌ュ敹闂佸搫娲ㄩ崰搴ㄥ礈椤撱垺鈷戦柛娑橈攻婢跺嫰鏌涢幘瀵告噰闁靛棗鍊垮濠氬Ψ閿旀儳骞愰梻浣稿閸嬪懐鎹㈠鍛傦綁骞栨担鍦幈?P
     * @param videoInfo 闂傚倸鍊风粈渚€骞夐敓鐘冲亱闁哄洢鍨圭粻鐘诲箹濞ｎ剙濡肩紒鐘冲哺閺屾盯顢曢悩鎻掑缂備胶濮伴崕鐢稿蓟瀹ュ牜妾ㄩ梺鍛婃尵閸犲酣顢氶敐澶婂瀭妞ゆ劑鍨荤粣鐐寸節閻㈤潧孝闁稿﹤顭烽崺鈧?P 濠电姷鏁搁崕鎴犲緤閽樺娲偐鐠囪尙顦┑鐘绘涧濞层倝顢氶柆宥嗙厱婵炴垶顭囬幗鐘绘煕鎼达紕效闁哄本鐩鎾Ω閵夛妇褰繝鐢靛仜閸氬鏁幒鏇犱罕闂備胶绮〃鍛存偋閸℃稒鍊堕弶鍫涘妸娴滄粓鏌￠崶鈺佹灁婵炴彃鐡ㄩ〃銉╂倷閸欏妫﹂悗瑙勬磸閸旀垿銆佸Ο娆炬Щ濠?
     * @param startIndex 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忛柡澶嬪殮瑜版帗鍊荤紒娑橆儐閺呯偤姊洪幖鐐插姶闁告挻鐟х划鍫ュ礋椤栨稓鍘藉┑顔姐仜閸嬫挸霉濠婂啰鍩ｇ€?P 闂備浇顕х€涒晠顢欓弽顓炵獥闁圭儤顨呯壕濠氭煙閻愵剚鐏遍柡鈧懞銉ｄ簻闁哄啫鍊甸幏锟犳煕鎼淬垹濮嶉柡?
     * @param coverUrl 闂傚倷娴囬褏鎹㈤幇顔藉床閻庯綆鍓氶鑺ユ叏濮楀棗澧婚柣?URL
     */
    fun playBiliVideoParts(videoInfo: BiliClient.VideoBasicInfo, startIndex: Int, coverUrl: String) {
        ensureInitialized()
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        val songs = videoInfo.pages.map { page -> buildBiliPartSong(page, videoInfo, coverUrl) }
        playPlaylist(songs, startIndex)
    }

    fun play(commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) {
        ensureInitialized()
        if (!initialized) return
        if (shouldBlockLocalRoomControl(commandSource)) return
        cancelPendingPauseRequest(resetVolumeToFull = true)
        suppressAutoResumeForCurrentSession = false
        resumePlaybackRequested = true
        val song = _currentSongFlow.value
        val preparedInPlayer = isPreparedInPlayer()
        if (preparedInPlayer && song != null && !isLocalSong(song)) {
            val url = _currentMediaUrl.value
            if (!url.isNullOrBlank()) {
                val ageMs = if (currentMediaUrlResolvedAtMs > 0L) {
                    SystemClock.elapsedRealtime() - currentMediaUrlResolvedAtMs
                } else {
                    Long.MAX_VALUE
                }
                if (
                    ageMs >= MEDIA_URL_STALE_MS ||
                    YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeResume(song, url)
                ) {
                    refreshCurrentSongUrl(
                        resumePositionMs = player.currentPosition,
                        allowFallback = false,
                        reason = "stale_resume",
                        bypassCooldown = true,
                        resumedPlaybackCommandSource = commandSource
                    )
                    return
                }
            }
        }
        when {
            preparedInPlayer -> {
                syncExoRepeatMode()
                startPlayerPlaybackWithFade(resolveCurrentPlaybackStartPlan())
                val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
                _playbackPositionMs.value = resumePositionMs
                ioScope.launch {
                    persistState(
                        positionMs = resumePositionMs,
                        shouldResumePlayback = true
                    )
                }
                emitPlaybackCommand(
                    type = "PLAY",
                    source = commandSource,
                    positionMs = resumePositionMs,
                    currentIndex = currentIndex
                )
            }
            currentPlaylist.isNotEmpty() && currentIndex != -1 -> {
                val resumePositionMs = if (keepLastPlaybackProgressEnabled) {
                    maxOf(restoredResumePositionMs, _playbackPositionMs.value).coerceAtLeast(0L)
                } else {
                    0L
                }
                playAtIndex(currentIndex, resumePositionMs = resumePositionMs, commandSource = commandSource)
                emitPlaybackCommand(
                    type = "PLAY",
                    source = commandSource,
                    positionMs = resumePositionMs,
                    currentIndex = currentIndex
                )
            }
            currentPlaylist.isNotEmpty() -> {
                playAtIndex(0, commandSource = commandSource)
                emitPlaybackCommand(
                    type = "PLAY",
                    source = commandSource,
                    positionMs = 0L,
                    currentIndex = 0
                )
            }
            else -> {}
        }
    }

    private fun handleTrackEndedIfNeeded(source: String) {
        val currentKey = trackEndDeduplicationKey(
            mediaId = player.currentMediaItem?.mediaId,
            fallbackSongKey = _currentSongFlow.value?.stableKey()
        )
        if (!shouldHandleTrackEnd(lastHandledKey = lastHandledTrackEndKey, currentKey = currentKey)) {
            NPLogger.d(
                "NERI-PlayerManager",
                "闂傚倸鍊搁…顒勫磻閸曨個娲Ω閳轰胶鏌у銈嗗姧缁犳垵效閺屻儲鐓欓柟娈垮枛椤ｅジ鏌ｉ幘鍐测偓濠氬焵椤掆偓缁犲秹宕曢柆宥呯閻庯綆鈧叏绲剧换婵嗩潩椤撶姴骞楅梻渚€娼ч…鍫ュ磿闁稁鏁傛い鎰╁€楃壕鍏笺亜閺傛寧鎯堝┑顔煎€婚埀顒侇問閸犳牠鎮ユ總绋跨畺闁靛繈鍊栭悡銉╂倵閿濆骸澧绘俊? source=$source, key=$currentKey"
            )
            return
        }
        // 闂傚倸鍊搁崐鎼佸磹閹间礁鐤い鎰╁焺閸ゆ洟鏌涢锝嗗闁轰礁妫欑换娑㈠箣閻戝洣绶靛┑鐐烘？閸楁娊骞冨鈧幃娆撴濞戞顥氶梻鍌欐祰閿熴儵宕愬┑瀣摕婵炴垯鍨圭粻鎶芥煙鐎电浠滄い锔规櫊閹鎲撮崟顒傤槰闂佺粯鎸撮埀顒佸墯閸ゆ洟鏌ｉ姀鐘冲暈闁稿瀚伴弻娑滅疀閺囨ǚ鍋撶€ｎ剚顫?mediaId 闂傚倸鍊风粈渚€骞夐敓鐘冲仭闁挎洑鐒﹂幊灞剧節閻㈤潧浠╅悘蹇ｄ簻閳绘棃寮撮姀鐘靛姦濡炪倖鍨煎▔鏇犵矈瀹勯偊鐔嗛柣鐔稿婢э妇鈧娲戠徊濠毸囬崷顓涘亾鐟欏嫭纾搁柛銊ㄦ閹广垹鈹戦崱鈺傚兊濡炪倖甯婄欢鈩冪瑜嶉埞鎴︽偐椤旇偐浠鹃梺绋块閸氬濡甸弮鍫濈濞达絽鎽滈弻褔姊洪棃娑辨Т闁哄懏绮撳畷鐢稿焵椤掑嫭鍊甸柛蹇擃槸娴滈箖姊洪崨濠冨闁稿鎳橀、鏃堟偄閸濄儳鐦堥梺闈涢獜缁插墽娑甸崜褏纾煎璺烘湰閻掓寧銇?
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrackEndHandledAtMs < 500L) {
            NPLogger.d(
                "NERI-PlayerManager",
                "闂傚倸鍊搁…顒勫磻閸曨個娲Ω閳轰胶鏌у銈嗗姧缁犳垵效閺屻儲鐓欓梺顓ㄧ畱楠炴霉濠у灝鈧繈寮诲☉銏犖ㄦい鏍ㄥ嚬濞差參姊洪懝閭︽綈闁稿骸鐤囬悘瀣⒑缂佹﹩娈旈柣妤€妫濋幃姗€鍩￠崘顏嗭紲闂佺粯锚閸熷灝霉椤曗偓閺岀喖顢涘顒佹閻庤娲滈崰鏍€佸鈧幃鈺呭礃閼碱剚顕涢梻鍌氬€风粈渚€骞栭位鍥焼瀹ュ懐鐛ラ柟鑲╄ˉ閸? source=$source, key=$currentKey, delta=${now - lastTrackEndHandledAtMs}ms"
            )
            return
        }
        lastHandledTrackEndKey = currentKey
        lastTrackEndHandledAtMs = now
        NPLogger.d(
            "NERI-PlayerManager",
            "濠电姷鏁告慨浼村垂閻撳簶鏋栨繛鎴炲焹閸嬫挸顫濋悡搴㈢彎濡ょ姷鍋涢崯顖滄崲濠靛鐐婄憸蹇涱敊閹烘埈娓婚柕鍫濇鐏忕敻鏌涢悩鍐插鐎殿喖鐖煎畷鐑筋敇閻樼绱冲┑鐐舵彧缂嶁偓妞ゎ偄顦辨禍鎼佹偋閸垻顔? source=$source, key=$currentKey, index=$currentIndex, queueSize=${currentPlaylist.size}"
        )
        handleTrackEnded()
    }

    fun pause(
        forcePersist: Boolean = false,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        ensureInitialized()
        if (!initialized) return
        if (shouldBlockLocalRoomControl(commandSource)) return
        cancelPendingPauseRequest()
        resumePlaybackRequested = false
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        val shouldFadeOut =
            playbackFadeInEnabled && playbackFadeOutDurationMs > 0L && ::player.isInitialized
        if (shouldFadeOut) {
            val scheduledPauseToken = playbackRequestToken
            lateinit var scheduledPauseJob: Job
            scheduledPauseJob = mainScope.launch {
                try {
                    fadeOutCurrentPlaybackIfNeeded(
                        enabled = true,
                        fadeOutDurationMs = playbackFadeOutDurationMs
                    )
                    if (scheduledPauseToken != playbackRequestToken) {
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "闂傚倸鍊搁…顒勫磻閸曨個娲Ω閳轰胶鏌у銈嗗姧缁犳垵效閺屻儲鐓欓梺顓ㄧ畱楠炴霉濠у灝鈧繈寮诲☉銏犖ㄩ柨婵嗘噹椤姊虹化鏇熸珔闁稿﹤娼″濠氭晲婢跺á鈺呮煏婢跺牆鍔村ù鐘靛帶閳规垿鎮欓崣澶樻闂佹悶鍔岄幖顐︹€﹂崶銊х瘈婵﹩鍘兼禍婊堟⒑閸涘﹦鈽夐柨鏇畵瀵娊鎮欓悜妯锋嫼缂備礁顑嗛娆撳磿閹邦喚纾兼い鏃囧亹缁犵粯銇? requestToken=$scheduledPauseToken, currentToken=$playbackRequestToken"
                        )
                        return@launch
                    }
                    pauseInternal(forcePersist, resetVolumeToFull = false)
                } finally {
                    if (pendingPauseJob === scheduledPauseJob) {
                        pendingPauseJob = null
                    }
                }
            }
            pendingPauseJob = scheduledPauseJob
        } else {
            pauseInternal(forcePersist, resetVolumeToFull = true)
        }
        emitPlaybackCommand(
            type = "PAUSE",
            source = commandSource,
            positionMs = _playbackPositionMs.value,
            currentIndex = currentIndex
        )
    }

    private fun pauseInternal(forcePersist: Boolean, resetVolumeToFull: Boolean) {
        pendingPauseJob = null
        resumePlaybackRequested = false
        val currentSong = _currentSongFlow.value
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        val expectedDuration = currentSong?.durationMs?.takeIf { it > 0L } ?: player.duration
        val shouldForceFlushShortLocalSong =
            currentSong?.let(::isLocalSong) == true && expectedDuration in 1L..5_000L
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        cancelVolumeFade(resetToFull = resetVolumeToFull)
        val stackHint = Throwable().stackTrace.take(6).joinToString(" <- ") {
            "${it.fileName}:${it.lineNumber}"
        }
        NPLogger.d(
            "NERI-PlayerManager",
            "pauseInternal: song=${currentSong?.name}, positionMs=$currentPosition, state=${playbackStateName(player.playbackState)}, playWhenReady=${player.playWhenReady}, forcePersist=$forcePersist, stack=[$stackHint]"
        )
        player.playWhenReady = false
        player.pause()
        if (shouldForceFlushShortLocalSong) {
            // 闂傚倷鑳堕崕鐢稿礈濠靛牊鏆滈柟鐑橆殔缁犵娀鏌熼幍顔碱暭闁稿鍊归妵鍕箻閸楃偟浠奸柣搴㈣壘椤︿即濡甸崟顖氱闁糕剝銇炴竟鏇熺節閻㈤潧袥闁稿鎹囬弻鐔虹磼閵忕姵鐏嶅銈冨劚閿曨亪寮婚悢鐓庣濞达絿鍎ら幉姗€姊洪崫鍕棑闁稿海鏁诲璇测槈閵忊剝娅嗛梺鍛婄箓鐎氼剟鈥栨径鎰拺閻犲洦褰冮惁銊╂煕閹惧銆掗柛鎺撳浮閸╋繝宕担瑙勬珫婵犵數濮撮敃銈夊箠韫囨稑桅婵犻潧顑嗛埛鎴炵箾閼奸鍤欓柡瀣懄娣囧﹪鎮欓弶鎴狀儌缂備緡鍣崣鍐ㄧ暦閵娾晛绾ч柟瀵稿Х閸橆剚淇婇悙顏勨偓鏍偋濡ゅ啯宕查柛宀€鍋涢崒銊╂煙缂併垹鏋熼柣鎾寸懇閹綊鎼归悷鎵閻庤鎮堕崕鐢稿蓟濞戙垹绠ｆい鏍ㄧ矌閻﹀牆螖閻橀潧浠﹂悽顖ょ節閻涱噣骞掗幊铏⒐閹?PCM 缂傚倸鍊搁崐鎼佸磹妞嬪海绀婇柍褜鍓熼弻娑樷槈閸楃偟浠梺鍝勬閸楀啿顫忓ú顏勬嵍妞ゆ挴鍓濋妤呮⒑閸涘⊕顏堝Χ閹间礁绠栧Δ锝呭枤閺佸棝鏌涢弴銊ュ婵炲懏鐗犻幃妤呭礂婢跺﹣澹曢梻浣告啞閸旓附绂嶉悙渚晩闁规壆澧楅埛鎺懨归敐鍥ㄥ殌缂佹彃顭烽弻锝夘敆閳ь剟濡剁粙娆惧殨妞ゆ劧绠戝洿闂佺硶鍓濋悷銏ゅ几閺嶎厽鈷戦梺顐ゅ仜閼活垱鏅堕鈧弻宥囨嫚閺屻儱寮伴梺鎼炲姂缁犳牠骞栬ぐ鎺濇晝闁冲灈鏅涙禍?seek 闂備浇顕х€涒晠顢欓弽顓炵獥闁哄稁鍘肩粻瑙勩亜閹板墎鐣遍柡鍕╁劜娣囧﹪濡堕崨顔兼缂備讲鍋撻柛鈩冪⊕閳锋垿鏌涢…鎴濇珮闁稿孩鍔欓弻锝夊箻椤栨矮澹曟繝鐢靛Х閺佹悂宕戦悙鍝勭缂佸顑欓崵鏇㈡煕椤愶絾绀冮柛?
            runCatching {
                player.seekTo(currentPosition.coerceAtMost(expectedDuration.coerceAtLeast(0L)))
            }
            _playbackPositionMs.value = currentPosition
        }
        if (!resetVolumeToFull) {
            runPlayerActionOnMainThread {
                if (::player.isInitialized) {
                    player.volume = 1f
                }
            }
        }
        if (forcePersist) {
            blockingIo {
                persistState(positionMs = currentPosition, shouldResumePlayback = false)
            }
        } else {
            ioScope.launch {
                persistState(positionMs = currentPosition, shouldResumePlayback = false)
            }
        }
    }

    fun togglePlayPause() {
        ensureInitialized()
        if (!initialized) return
        if (player.isPlaying || player.playWhenReady || playJob?.isActive == true) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(
        positionMs: Long,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        ensureInitialized()
        if (!initialized) return
        if (shouldBlockLocalRoomControl(commandSource)) return
        val resolvedPositionMs = positionMs.coerceAtLeast(0L)
        if (YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(_currentSongFlow.value, _currentMediaUrl.value)) {
            rememberPendingSeekPosition(resolvedPositionMs)
        } else {
            clearPendingSeekPosition()
        }
        player.seekTo(resolvedPositionMs)
        _playbackPositionMs.value = resolvedPositionMs
        ioScope.launch {
            persistState(
                positionMs = resolvedPositionMs,
                shouldResumePlayback = shouldResumePlaybackSnapshot()
            )
        }
        emitPlaybackCommand(
            type = "SEEK",
            source = commandSource,
            positionMs = resolvedPositionMs,
            currentIndex = currentIndex
        )
    }

    fun next(
        force: Boolean = false,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        ensureInitialized()
        if (!initialized) return
        if (shouldBlockLocalRoomControl(commandSource)) return
        if (currentPlaylist.isEmpty()) return
        val isShuffle = player.shuffleModeEnabled
        val useTransitionFade =
            playbackCrossfadeNextEnabled && (player.isPlaying || player.playWhenReady)

        if (isShuffle) {
            // 濠电姷鏁告慨鐑姐€傛禒瀣劦妞ゆ巻鍋撻柛鐔锋健閸┾偓妞ゆ巻鍋撶紓宥咃躬楠炲啫螣鐠囪尙绐為梺褰掑亰閸撴瑩鎮靛Ο鑽ょ瘈闁靛骏绲介悡鎰版煕閺冩挾鐣电€殿喗濞婂鎾閳ユ枼鍋撻悽鍛婂仭婵炲棗绻愰顐︽煟閹垮啫鐏ｇ紒杈ㄥ笒铻栭柍褜鍓熼獮濠呯疀閺囩姷鐓撴繝銏ｅ煐閸旀洟鎮為崹顐犱簻闁瑰搫绉剁粵蹇曠磼鏉堛劍顥堥柡灞糕偓鎰佸悑闁搞儯鍔嶅В鍕磽娴ｆ彃浜炬繝銏ｆ硾閳洝銇愰幒鎴狀槯濠电偞娼欑€垫帗绂嶉鍕殾闁靛繒濮Σ鍫熸叏濮楀棗骞栭柣顭戝亰濮婃椽宕崟鍨┑鐐差槹濞茬喎顕?
            if (shuffleFuture.isNotEmpty()) {
                val nextIdx = shuffleFuture.removeAt(shuffleFuture.lastIndex)
                if (currentIndex != -1) shuffleHistory.add(currentIndex)
                currentIndex = nextIdx
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                emitPlaybackCommand(
                    type = "NEXT",
                    source = commandSource,
                    currentIndex = currentIndex,
                    force = force
                )
                return
            }

            // 婵犵數濮烽弫鎼佸磻濞戞瑥绶為柛銉墮缁€鍫熺節闂堟稒锛旈柤鏉跨仢闇夐柨婵嗘处椤忕喓绱掗幉瀣М闁哄本鐩鎾Ω閵夈倗鏁栭梻浣告惈濡瑥顭垮鈧崺銉﹀緞閹邦剛顔掗梺褰掝暒缁€渚€宕滈鍓х＝濞达綀顫夐悡銉╂煟鎺抽崝鎴﹀极瀹ュ绀冩い鏃傚帶閸炪劑姊哄Ч鍥х伄闁轰焦鎮傝棟妞ゆ洍鍋撴慨濠傤煼瀹曟帒顫濋鐙€妲洪梻浣侯焾椤戝棝骞戦崶顒€绠栭柣鎴ｆ閸楄櫕鎱ㄥΟ鍝勮埞濠德ゅГ缁绘繈鎮介棃娑楃捕闂佹寧娲︽禍婊堫敋閿濆鏁嗗ù锝呭閸ゃ倝鏌ｆ惔銏⑩姇闁挎洍鏅犲畷?
            if (shuffleBag.isEmpty()) {
                if (force || repeatModeSetting == Player.REPEAT_MODE_ALL) {
                    rebuildShuffleBag(excludeIndex = currentIndex)
                } else {
                    stopPlaybackPreservingQueue()
                    return
                }
            }

            if (shuffleBag.isEmpty()) {
                // 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忛柟缁㈠枛缁犵姷鈧箍鍎卞ú銊╁础濮樿埖鍊垫繛鎴炵懕閸忣剛绱掓潏銊︻棃闁哄备鈧剚鍚嬮柛鎰╁妼椤姊虹涵鍛棄闁哥喐澹嗗Σ鎰板箳閹存梹顫嶅┑顔筋殔濡瑦鏅ラ梻鍌欐祰椤曆呪偓鍨浮瀹曟粌鈽夊顒€鐏婇梺鍐叉惈閹峰寮鍡欑闁瑰浼濋鍛彾?
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                return
            }

            if (currentIndex != -1) shuffleHistory.add(currentIndex)
            // 闂傚倸鍊风粈渚€骞栭锕€纾归柣鎴ｆ绾偓闂佸憡鍔栫粊鎾几?-> 闂傚倸鍊风粈渚€骞栭锕€纾婚柛鈩冪☉閸屻劑鏌ゅù瀣珕妞ゎ偅娲熼弻鐔告綇閸撗呮殸闁诲孩鑹鹃ˇ浼村Φ閸曨喚鐤€闁圭偓娼欏▍銈夋⒒閸屾艾顏╃紒澶婄秺楠炲啰鎲撮崟顓犳嚌濡炪倖鐗楀銊︾閳哄啰纾?            shuffleFuture.clear()

            val pick = if (shuffleBag.size == 1) 0 else Random.nextInt(shuffleBag.size)
            currentIndex = shuffleBag.removeAt(pick)
            playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
            emitPlaybackCommand(
                type = "NEXT",
                source = commandSource,
                currentIndex = currentIndex,
                force = force
            )
        } else {
            // 濠电姷顣槐鏇㈠磻閹达箑纾归柡鍥ュ灩缁犺銇勯幇鈺佲偓娑欐叏閾忣偁浜滈柟鎯у船閻忊晝绱掗悩鐢靛笡闁靛洤瀚伴獮妯兼崉閻戞鈧箖姊?
            if (currentIndex < currentPlaylist.lastIndex) {
                currentIndex++
            } else {
                if (force || repeatModeSetting == Player.REPEAT_MODE_ALL) {
                    currentIndex = 0
                } else {
                    NPLogger.d("NERI-Player", "Already at the end of the playlist.")
                    return
                }
            }
            playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
            emitPlaybackCommand(
                type = "NEXT",
                source = commandSource,
                currentIndex = currentIndex,
                force = force
            )
        }
    }

    fun previous(commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) {
        ensureInitialized()
        if (!initialized) return
        if (shouldBlockLocalRoomControl(commandSource)) return
        if (currentPlaylist.isEmpty()) return
        val isShuffle = player.shuffleModeEnabled
        val useTransitionFade =
            playbackCrossfadeNextEnabled && (player.isPlaying || player.playWhenReady)

        if (isShuffle) {
            if (shuffleHistory.isNotEmpty()) {
                // 闂傚倸鍊烽悞锕傚箖閸洖纾块柟鎯版绾惧鏌曢崼婵愭Ц闁绘帒鐏氶妵鍕箣閿濆棭妫勬繛瀛樼矌閸嬫挾鎹㈠☉銏犵闁绘劖娼欓惃鎴︽⒑缁嬫鍎愰柟绋款煼楠炲牓濡搁妷銏℃杸闂佸憡娲﹂崑鍕叏妞嬪海纾藉ù锝勭矙閸濊櫣绱掔拠鑼闁伙絿鍏樻俊鐤槺婵炲矈浜弻锝夊箛闂堟稑鈷掑┑鈥虫▕閸ㄨ泛顫忛搹瑙勫厹闁告侗鍠氶妴鎰版⒑閹稿孩纾搁柛濠冾殘濡叉劙鎮欑喊妯轰壕闁挎繂楠搁弸娑氱磼閳ь剟宕奸埗鈺佷壕妤犵偛鐏濋崝姘繆椤愶絿鎳囬柡灞诲姂婵¤埖寰勭€ｎ剙骞愰梻渚€鈧偛鑻晶瀛橆殽閻愬弶顥℃い锔界叀閺岋綁骞橀崘娴嬪亾閸ф钃熼柨鐔哄Т闁卞洦銇勯幇鍓佺？闁告垹鎳撻埞鎴︽倷鐠鸿桨姹楅梺杞版祰椤曆囶敋閿濆棛顩烽悗锝庝邯閸炶泛顪冮妶鍡楃瑨闁稿﹤顭疯棟妞ゆ梻鏅粻楣冩倵閻㈡鐒剧悮銊╂⒑缁嬪灝顒㈠鐟版閸掓帒顫濈捄娲敹闂佸搫娲ㄩ崑鐔妓囬妸鈺傚€垫鐐茬仢閸旀岸鏌熼崘鏌ュ弰鐠侯垶鏌涘☉娆愮稇缂佺嫏鍥ㄧ厓闁告繂瀚埀顒€顭烽垾鏍ㄧ附閸涘﹦鍘遍棅顐㈡处濡垿鎳撶捄銊㈠亾鐟欏嫭绀€闁绘牕銈搁獮鍐ㄢ枎閹惧磭顔岄梺鍦劋閺屻劎鏁幘缁樷拻濞达絽鎲￠崯鐐淬亜椤撶偞鍠樼€规洑鍗抽獮鍥敆婢跺苯濮洪梻浣圭湽閸ㄥ綊骞夐敓鐘茬？鐎广儱顦伴悡鏇㈡煛閸ャ儱濡虹紒銊ょ矙閺岋綁鎮㈤崨濠傜闂佸疇妫勯ˇ鐢哥嵁濮椻偓椤㈡稑顫濋銏╂?
                if (currentIndex != -1) shuffleFuture.add(currentIndex)
                val prev = shuffleHistory.removeAt(shuffleHistory.lastIndex)
                currentIndex = prev
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                emitPlaybackCommand(
                    type = "PREVIOUS",
                    source = commandSource,
                    currentIndex = currentIndex
                )
            } else {
                NPLogger.d("NERI-Player", "No previous track in shuffle history.")
            }
        } else {
            if (currentIndex > 0) {
                currentIndex--
                playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                emitPlaybackCommand(
                    type = "PREVIOUS",
                    source = commandSource,
                    currentIndex = currentIndex
                )
            } else {
                if (repeatModeSetting == Player.REPEAT_MODE_ALL && currentPlaylist.isNotEmpty()) {
                    currentIndex = currentPlaylist.lastIndex
                    playAtIndex(currentIndex, useTrackTransitionFade = useTransitionFade)
                    emitPlaybackCommand(
                        type = "PREVIOUS",
                        source = commandSource,
                        currentIndex = currentIndex
                    )
                } else {
                    NPLogger.d("NERI-Player", "Already at the start of the playlist.")
                }
            }
        }
    }

    fun cycleRepeatMode() {
        ensureInitialized()
        if (!initialized) return
        val newMode = when (repeatModeSetting) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        repeatModeSetting = newMode
        _repeatModeFlow.value = newMode
        // 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忛柟缁㈠枛缁犵娀骞栨潏鍓ф偧缂佲偓婵犲洦鍊甸柨婵嗛閺嬫稓鐥娑樹壕闂傚倷绀侀幖顐⒚洪妸鈺佺獥闁规壆澧楅悡銉╂煏閸繃绀岄柛瀣崌閹晫绮欐径绋挎瀳濠电偛鐡ㄧ划搴ㄥ磻閵堝鍋樻い鏇楀亾鐎规洖宕埥澶娢熼懖鈺傜秮闂傚倷娴囬鏍垂閸洖纾诲┑鐘叉搐閼?Exo 闂備浇顕уù鐑藉极婵犳艾纾诲┑鐘插暟椤╂煡鏌ｉ幇顖涱仩鐎规挷绶氶弻娑㈠焺閸愵亖濮囬梺鍝勬媼閸撴氨鎹㈠☉銏犵闁诲繑妞挎禍婊堫敋閵夆晛绠婚悺鎺嶇缂嶅﹪寮幇鏉垮耿婵炲棙鍔曞▍蹇旂節濞堝灝鏋熼柨鏇ㄥ亝娣囧﹪宕堕埡鍌ゆ綗闂佸湱鍎ら崹鐔煎几鎼淬劍鐓欓梺顓ㄧ畱婢ф澘霉濠婂骸鐏＄紒缁樼〒閳ь剛鏁搁…鍫ヮ敁瀹€鍕厱闁规儳顕埥澶愭煃缂佹ɑ宕屾鐐差儔閺佸倻鎲撮敐鍡楃槯闂傚倷鑳剁划顖炲蓟閵娾晛搴婇柡灞诲劜閸婅埖绻涢崱妯诲鞍闁绘挻鐟╅弻鐔碱敇閻旈鐟ㄦ繝娈垮枓閸嬫挾绱?        syncExoRepeatMode()
        ioScope.launch {
            persistState()
        }
    }

    fun release() {
        if (!initialized) return
        resumePlaybackRequested = false
        lastAutoTrackAdvanceAtMs = 0L

        try {
            val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        } catch (_: Exception) { }
        audioDeviceCallback = null

        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        cancelPendingPauseRequest(resetVolumeToFull = true)
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = null
        playbackSoundPersistJob?.cancel()
        playbackSoundPersistJob = null
        playJob?.cancel()
        playJob = null

        if (::player.isInitialized) {
            runCatching { player.stop() }
            player.release()
        }
        _playbackSoundState.value = playbackEffectsController.release()
        _playWhenReadyFlow.value = false
        _playerPlaybackStateFlow.value = Player.STATE_IDLE
        if (::cache.isInitialized) {
            cache.release()
        }
        conditionalHttpFactory?.close()
        conditionalHttpFactory = null

        mainScope.cancel()
        ioScope.cancel()

        _isPlayingFlow.value = false
        _currentMediaUrl.value = null
        _currentPlaybackAudioInfo.value = null
        currentMediaUrlResolvedAtMs = 0L
        _currentSongFlow.value = null
        _currentQueueFlow.value = emptyList()
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L

        currentPlaylist = emptyList()
        currentIndex = -1
        shuffleBag.clear()
        shuffleHistory.clear()
        shuffleFuture.clear()
        consecutivePlayFailures = 0

        initialized = false
    }

    fun setShuffle(enabled: Boolean) {
        ensureInitialized()
        if (!initialized) return
        if (player.shuffleModeEnabled == enabled) return
        player.shuffleModeEnabled = enabled
        shuffleHistory.clear()
        shuffleFuture.clear()
        if (enabled) {
            rebuildShuffleBag(excludeIndex = currentIndex)
        } else {
            shuffleBag.clear()
        }
        ioScope.launch {
            persistState()
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = mainScope.launch {
            while (isActive) {
                val positionMs = resolveDisplayedPlaybackPosition(
                    player.currentPosition.coerceAtLeast(0L)
                )
                _playbackPositionMs.value = positionMs
                maybePersistPlaybackProgress(positionMs)
                delay(40)
            }
        }
    }

    private fun stopProgressUpdates() { progressJob?.cancel(); progressJob = null }

    private fun maybePersistPlaybackProgress(positionMs: Long) {
        if (currentPlaylist.isEmpty()) return
        if (!shouldResumePlaybackSnapshot()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatePersistAtMs < STATE_PERSIST_INTERVAL_MS) return
        lastStatePersistAtMs = now
        ioScope.launch {
            persistState(positionMs = positionMs, shouldResumePlayback = true)
        }
    }

    private fun stopPlaybackPreservingQueue(clearMediaUrl: Boolean = false) {
        cancelPendingPauseRequest(resetVolumeToFull = true)
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        lastHandledTrackEndKey = null
        resumePlaybackRequested = false
        lastAutoTrackAdvanceAtMs = 0L
        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        _isPlayingFlow.value = false
        _playWhenReadyFlow.value = false
        _playerPlaybackStateFlow.value = Player.STATE_IDLE
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
        if (currentPlaylist.isEmpty()) {
            currentIndex = -1
            _currentSongFlow.value = null
            _currentMediaUrl.value = null
            _currentPlaybackAudioInfo.value = null
            currentMediaUrlResolvedAtMs = 0L
        } else {
            currentIndex = currentIndex.coerceIn(0, currentPlaylist.lastIndex)
            _currentSongFlow.value = currentPlaylist.getOrNull(currentIndex)
            if (clearMediaUrl) {
                _currentMediaUrl.value = null
                _currentPlaybackAudioInfo.value = null
                currentMediaUrlResolvedAtMs = 0L
            }
        }
        consecutivePlayFailures = 0
        ioScope.launch {
            persistState()
        }
    }

    fun hasItems(): Boolean = currentPlaylist.isNotEmpty()

    private fun updateCurrentFavorite(song: SongItem, add: Boolean) {
        val updatedLists = PlayerFavoritesController.optimisticUpdateFavorites(
            playlists = _playlistsFlow.value,
            add = add,
            song = song,
            application = application,
            favoritePlaylistName = getLocalizedString(R.string.favorite_my_music)
        )
        _playlistsFlow.value = PlayerFavoritesController.deepCopyPlaylists(updatedLists)

        ioScope.launch {
            try {
                if (add) {
                    localRepo.addToFavorites(song)
                } else {
                    localRepo.removeFromFavorites(song)
                }
            } catch (error: Exception) {
                val action = if (add) "addToFavorites" else "removeFromFavorites"
                NPLogger.e("NERI-PlayerManager", "$action failed: ${error.message}", error)
            }
        }
    }

    /** 婵犵數濮烽弫鎼佸磿閹寸姷绀婇柍褜鍓氶妵鍕即閸℃顏柛娆忕箻閺岋綁骞囬婊冪墯婵炲瓨绮岀紞濠囧蓟閻旂厧绠氱憸宥夊汲鏉堛劊浜滈柕鍫濇噺閸ｆ椽鏌熸笟鍨闁宠鍨垮畷鍗烆潩椤掑绱旈梻鍌欒兌绾爼寮插☉銏″剹闁稿瞼鍋涢崹鍌炴煙椤栧棗鐬奸崣鍡涙⒑閸涘﹥澶勯柛瀣缁傚秹顢涢悙绮规嫼闂佸憡绺块崕杈ㄧ墡缂傚倷绶￠崰鏍矓瑜版帇鈧線寮撮悢渚祫闁诲函缍嗛崑鍕礈椤撱垺鈷戦柛娑橈攻婢跺嫰鏌涢妸锕€鈻曠€规洩缍侀獮鍡氼槷婵?*/
    fun addCurrentToFavorites() {
        ensureInitialized()
        if (!initialized) return
        val song = _currentSongFlow.value ?: return
        updateCurrentFavorite(song = song, add = true)
    }

    /** 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忛悗鍨摃婵娊鏌涢锝嗙闁绘帒鐏氶妵鍕箳瀹ュ牆鍘￠梺娲诲幖椤戝寮诲☉銏犖╅柨鏂垮⒔閻撳鎮楃憴鍕婵犮垺锕㈤垾锕傚Ω閳轰胶顦伴梻鍌氱墛缁嬫劗绱撻幘缁樷拻濞达絽婀卞﹢浠嬫煕閺傝法鐏遍柍褜鍓氶惌顕€宕￠幎钘夌畺闁跨喓濮甸崵瀣煟閵忋埄鏆柡瀣懇濮婅櫣娑甸崨顓犲姺闂佸憡鏌ㄩˇ闈涚暦閹达箑绠婚柡鍌樺劜閺傗偓闂備胶纭堕弲娑氳姳闁秴鐤鹃柣妯烘▕濞兼牕霉閻樺樊鍎忛崶鎾⒑閸涘﹣绶遍柛銊ㄦ珪閻″繘姊婚崒娆戭槮闁硅绻濋獮鎰板传閵壯呯厠闂佹眹鍨归幉锟犲磻?*/
    fun removeCurrentFromFavorites() {
        ensureInitialized()
        if (!initialized) return
        val song = _currentSongFlow.value ?: return
        updateCurrentFavorite(song = song, add = false)
    }

    /** 闂傚倸鍊风粈渚€骞夐敍鍕殰闁圭儤鍤氬ú顏呮櫇闁逞屽墴閹箖鎮滈挊澶庢憰闂侀潧顦崕鎶藉传濡ゅ懏鈷戦悹鍥ｂ偓宕囦化婵犫拃鍕垫疁閽樼喖鏌熸潏楣冩闁?*/
    fun toggleCurrentFavorite() {
        ensureInitialized()
        if (!initialized) return
        val song = _currentSongFlow.value ?: return
        if (PlayerFavoritesController.isFavorite(_playlistsFlow.value, song, application)) {
            updateCurrentFavorite(song = song, add = false)
        } else {
            updateCurrentFavorite(song = song, add = true)
        }
    }

    private suspend fun persistState(
        positionMs: Long = _playbackPositionMs.value.coerceAtLeast(0L),
        shouldResumePlayback: Boolean = currentPlaylist.isNotEmpty() && shouldResumePlaybackSnapshot()
    ) {
        val playlistSnapshot = currentPlaylist.toList()
        val currentIndexSnapshot = currentIndex
        val mediaUrlSnapshot = _currentMediaUrl.value
        val persistedShouldResumePlayback =
            shouldResumePlayback && !suppressAutoResumeForCurrentSession
        val persistedPositionMs = if (keepLastPlaybackProgressEnabled) {
            positionMs.coerceAtLeast(0L)
        } else {
            0L
        }
        val persistedRepeatMode = if (keepPlaybackModeStateEnabled) {
            repeatModeSetting
        } else {
            Player.REPEAT_MODE_OFF
        }
        val persistedShuffleEnabled = keepPlaybackModeStateEnabled && _shuffleModeFlow.value

        withContext(Dispatchers.IO) {
            try {
                if (playlistSnapshot.isEmpty()) {
                    restoredResumePositionMs = 0L
                    restoredShouldResumePlayback = false
                    if (stateFile.exists()) stateFile.delete()
                } else {
                    val data = PersistedState(
                        playlist = playlistSnapshot.map { song ->
                            song.toPersistedSongItem(
                                includeLyrics = shouldPersistEmbeddedLyrics(song)
                            )
                        },
                        index = currentIndexSnapshot,
                        mediaUrl = mediaUrlSnapshot,
                        positionMs = persistedPositionMs,
                        shouldResumePlayback = persistedShouldResumePlayback,
                        repeatMode = persistedRepeatMode,
                        shuffleEnabled = persistedShuffleEnabled
                    )
                    stateFile.writeText(gson.toJson(data))
                }
            } catch (e: Exception) {
                NPLogger.e("PlayerManager", "Failed to persist state", e)
            }
        }
    }

    fun addCurrentToPlaylist(playlistId: Long) {
        ensureInitialized()
        if (!initialized) return
        val song = _currentSongFlow.value ?: return
        ioScope.launch {
            try {
                localRepo.addSongToPlaylist(playlistId, song)
            } catch (e: Exception) {
                NPLogger.e("NERI-PlayerManager", "addCurrentToPlaylist failed: ${e.message}", e)
            }
        }
    }

    /**
     * ?playBiliVideoAsAudio 濠电姷鏁搁崑鐐哄垂鐠轰警娼栧┑鐘宠壘缁愭鈧箍鍎卞Λ娑氱不妤ｅ啯鐓曢柕澶樺枛婢ь垶鏌涚€ｃ劌濮傞柡灞炬礃缁绘稖顦查柟鍐查叄閹€愁潩椤撶姷顔曢梺鐟邦嚟閸嬬偟浜搁妸鈺傜厱?playPlaylist 闂傚倸鍊烽懗鍫曗€﹂崼銏″床闁割偁鍎辩壕鍧楀级閸偄浜栧ù?     */
    fun playBiliVideoAsAudio(videos: List<BiliVideoItem>, startIndex: Int) {
        ensureInitialized()
        check(initialized) { "Call PlayerManager.initialize(application) first." }
        if (videos.isEmpty()) {
            NPLogger.w("NERI-Player", "playBiliVideoAsAudio called with EMPTY list")
            return
        }
        // 闂傚倷绀侀幖顐λ囬柆宥呯？闁圭増婢樼粈鍫熸叏濮楀棗澧伴柡鍡畵閺屾稑鈻庤箛锝喰ㄩ梺宕囩帛濮婂湱鎹㈠☉姗嗗晠妞ゆ棁宕甸幐澶愭⒑缁嬫鍎愰柟鍛婃倐閿濈偛鈹戦崶銊хФ闂侀潧顭梽鍕敇?SongItem 闂傚倸鍊风粈渚€骞夐敍鍕殰婵°倕鎳岄埀顒€鍟鍕箛椤戔斂鍎甸弻娑㈠箛闂堟稒鐏嶉梺鍝勬媼閸撴盯鍩€椤掆偓閸樻粓宕戦幘缁樼厓鐟滄粓宕滈悢椋庢殾婵犻潧顭Ο鍕倵鐟欏嫭纾搁柛搴㈢叀楠炲棝寮崼婢囨煕閵夘喚鍘涢柛鐔插亾闂傚倸鍊烽悞锕€顪冮崹顕呯劷闁秆勵殔缁€澶愬箹鏉堝墽鍒伴柛銊︾箞閺岋綁骞嬮悜鍡欏姺闂佸磭绮Λ鍐蓟瀹ュ牜妾ㄩ梺鍛婃尵閸犳牠鐛崱娑樼妞ゆ棁鍋愰ˇ鏉款渻閵堝棗绗傜紒鈧笟鈧畷顖涚節閸ャ劉鎷洪柣鐐寸▓閳ь剙鍘栨竟鏇㈡⒒娴ｇ瓔鍤冮柛銊ラ叄瀹曟﹢濡搁敃浣哄彂?
        val songs = videos.map { it.toSongItem() }
        playPlaylist(songs, startIndex)
    }


    /** 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ閵忊剝鐝栫紒楣冪畺缁犳牠寮婚悢琛″亾閻㈡鐒惧ù鐙呴檮閵囧嫰顢曢敐鍥╃厐闂傚洤顦扮换婵囩節閸屾凹浠奸梺璇查獜缁犳捇寮?*/
    suspend fun getNeteaseLyrics(songId: Long): List<LyricEntry> {
        return PlayerLyricsProvider.getNeteaseLyrics(songId, neteaseClient)
    }

    /** 闂傚倸鍊风粈渚€宕ョ€ｎ喖纾块柟鎯版鎼村﹪鏌ら懝鎵牚濞存粌缍婇弻娑㈠Ψ閵忊剝鐝栫紒楣冪畺缁犳牠寮婚悢琛″亾閻㈡鐒惧ù鐙呴檮閵囧嫰顢曢敐鍥╃厐闂傚洤顦扮换婵囩節閸屾凹浠奸梺璇查獜缁犳捇寮婚敐澶婄労闁告劏鏅濋ˇ顓㈡⒑瑜版帩妫戝┑顔芥尦椤㈡ɑ绺界粙璺ㄥ€為梺瀹犳〃鐠佹煡宕戦幘璇茬闁兼亽鍎辨禒顖炴⒑閹肩偛鍔橀柛鏂跨焸閹繝鍩€椤掑倻纾藉ù锝勭矙閸濇椽鏌ｅ☉宕囶吋yric?*/
    suspend fun getNeteaseTranslatedLyrics(songId: Long): List<LyricEntry> {
        return PlayerLyricsProvider.getNeteaseTranslatedLyrics(songId, neteaseClient)
    }

    /** 闂傚倸鍊风粈渚€骞栭銈囩煋闁绘垶鏋荤紞鏍ь熆鐠虹尨鍔熼柡鍡愬€曢湁闁挎繂鎳庨ˉ蹇旂箾闂傛潙宓嗛柟顔斤耿閹瑩妫冨☉妤€顥氶梻鍌欐祰閿熴儵宕愬┑瀣摕闁斥晛鍟欢鐐烘煕椤愶絿绠撳┑顔芥礈缁辨挻绗熼崶鈺佸弗闂佸摜濮甸〃鍛粹€﹂崶銊х瘈婵﹩鍓氶ˉ婵嬫⒑閸︻収鐒炬繛瀵稿厴钘濋柕澶嗘櫆閳锋垿鏌涘☉姗堝姛闁硅櫕鍔欓弻娑㈠Ω閿曗偓閳绘洜鈧鍣崑濠冧繆閼搁潧绶為悗锝庝簻婢瑰嫰姊绘担鍛婂暈婵炶绠撳畷銏°偅閸愩劎锛欓梺瑙勫劶婵倝宕戦敐澶嬬厱闁靛绲芥俊濂告煟閹绢垰浜剧紓鍌氬€峰ù鍥ㄣ仈閹间焦鍋￠柨鏃傛櫕閳瑰秴鈹戦悩鍙夋悙缁炬儳鍚嬬换娑㈠箣閻愮數鍙濋梺鍛婂灣缁瑥顫忓ú顏勪紶闁告洦鍘奸。鍝勵渻閵囨墎鍋撻柛瀣尵缁?*/
    suspend fun getTranslatedLyrics(song: SongItem): List<LyricEntry> {
        return PlayerLyricsProvider.getTranslatedLyrics(
            song = song,
            application = application,
            neteaseClient = neteaseClient,
            biliSourceTag = BILI_SOURCE_TAG
        )
    }

    suspend fun getLyrics(song: SongItem): List<LyricEntry> {
        return PlayerLyricsProvider.getLyrics(
            song = song,
            application = application,
            neteaseClient = neteaseClient,
            youtubeMusicClient = youtubeMusicClient,
            lrcLibClient = lrcLibClient,
            ytMusicLyricsCache = ytMusicLyricsCache,
            biliSourceTag = BILI_SOURCE_TAG
        )
    }

    fun playFromQueue(
        index: Int,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        ensureInitialized()
        if (!initialized) return
        if (currentPlaylist.isEmpty()) return
        if (index !in currentPlaylist.indices) return
        val targetSong = currentPlaylist[index]
        if (shouldBlockLocalRoomControl(commandSource) || shouldBlockLocalSongSwitch(targetSong, commandSource)) return

        // 闂傚倸鍊烽悞锕€顪冮崹顕呯劷闁秆勵殔缁€澶屸偓骞垮劚椤︻垶寮伴妷锔剧闁瑰瓨鐟ラ悘顏堟煕婵犲浂妫戦柟鍙夋倐閹囧醇濠靛棙缍夐梻浣侯焾椤戝棝骞愮拠宸殫闁告洦鍨扮粻娑欍亜閹炬剚妲规繛鑼枛瀵鈽夐姀鐘殿唺闂佺懓鐏濋崯顖烆敇閾忓湱纾藉ù锝勭矙閸濊櫣绱掔拠鎻掝伃妤犵偛顦甸獮姗€顢欓悡搴も偓鍧楁⒑閸濆嫭宸濆┑顔惧厴閸┿垽寮撮姀鈾€鎷洪梺鍛婄箓鐎氼剟鍩€椤掑喚娼愰柛鎺戯攻缁傛帞鈧綆鍋勯崜褰掓⒑鐠団€崇€婚悘鐐跺Г椤斿倿姊绘担鍛婂暈婵炶绠撳畷鎴﹀礋椤栨稑鈧?
        if (player.shuffleModeEnabled) {
            if (currentIndex != -1) shuffleHistory.add(currentIndex)
            shuffleFuture.clear()
            shuffleBag.remove(index)
        }

        currentIndex = index
        playAtIndex(index, commandSource = commandSource)
        emitPlaybackCommand(
            type = "PLAY_FROM_QUEUE",
            source = commandSource,
            currentIndex = currentIndex
        )
    }

    /**
     * 闂傚倷娴囬褏鎹㈤幇顔藉床闁归偊鍎靛☉妯锋瀻闁圭偓娼欐禒閬嶆⒑閸濆嫭鍌ㄩ柛銊ョ秺瀹曪繝骞庨懞銉у幈濠电偛妫楀ù姘ｉ崨濠勭闁稿繒鍘ч悘鎾煛鐏炲墽娲寸€殿噮鍣ｅ畷鎺懶掔憗銈呯伈闁哄本鐩崺鈩冩媴閾忕懓濮辨俊銈囧Х閸嬬偤鏁冮姀銈冣偓浣割潨閳ь剟骞冮姀銈呭窛濠电姴鍟伴悾顏堟⒒閸屾艾鈧悂宕愰幖浣哥柈濞村吋娼欑粣妤呭箹濞ｎ剙濡奸柛銊ュ€归妵鍕疀閹捐泛顤€闂佺楠哥粔褰掑蓟濞戙垹鍗抽柕濞垮€楅弫鏍⒑閼姐倕鏋戦柟鍛婃倐閸┿儲寰勯幇顒傤啋濡炪倖鐗楅惌顔界珶閺囩偐鏀介柣鎰綑閻忥箓鏌熺亸鏍ㄦ珕缂佸倸绉撮埢搴ㄥ箣閻樻妲?
     * @param song 闂傚倷娴囧畷鐢稿窗閹邦優娲箣閿旇棄鈧潡鏌ㄩ弴鐐测偓鎼佹煥閵堝應鏀介柣妯哄级閹兼劗绱掗埀顒佸緞閹邦厾鍘藉┑鈽嗗灥濞咃絾绂掑☉銏＄厽闁挎繂鐗撻崫娲煙娓氬灝濡奸柍瑙勫灴瀹曞崬螣缂佹ê顏板┑?     */
    fun addToQueueNext(song: SongItem) {
        ensureInitialized()
        if (!initialized) return

        // 缂傚倸鍊搁崐椋庣矆娓氣偓瀵敻顢楅埀顒勨€旈崘顔藉癄濠㈠厜鏅紞渚€寮崘顔肩＜婵﹢纭稿Σ瀛樼節瀵伴攱婢橀埀顒佹礋瀹曨垶顢曚綅婢跺á鏃€鎷呴搹璇″晭闂備礁澹婇崑鍛崲閹邦剦鍟呴柕澶涜礋娴?
        if (currentPlaylist.isEmpty()) {
            playPlaylist(listOf(song), 0)
            return
        }

        val currentSong = _currentSongFlow.value
        val newPlaylist = currentPlaylist.toMutableList()
        var insertIndex = (currentIndex + 1).coerceIn(0, newPlaylist.size + 1)

        // If the song already exists, move it to the next slot.
        val existingIndex = newPlaylist.indexOfFirst { it.sameIdentityAs(song) }
        if (existingIndex != -1) {
            // 濠电姷鏁告慨鐑姐€傛禒瀣劦妞ゆ巻鍋撻柛鐔锋健閸┾偓妞ゆ巻鍋撶紓宥咃躬楠炲啫螣鐠囪尙绐為悗鍏夊亾闁逞屽墴閹矂骞橀弬銉︽杸闂佺粯锚绾绢參銆傞弻銉︾厽婵犻潧鐗嗛弸娑㈡煛瀹€瀣М濠碘剝鎮傛俊鐑藉Ψ椤旇崵妫梻鍌欒兌閸庣敻宕滃┑鍠㈡椽鎮㈡總澶婃闂佸湱铏庨崰鏍不閻㈠憡鐓欓柣鎴炆戝畷鍕煕濮樼厧浜版慨濠冩そ瀹曠兘顢橀悩鑼偧闂佹眹鍩勯崹杈╂暜閳ュ磭鏆﹂柨婵嗩槸楠炪垺淇婇婵愬殭妞ゅ孩鎹囬幃妤呯嵁閸喖濮庡┑鐐茬湴閸旀垵顕ｉ幖浣哥＜婵﹫绲鹃～宥夋⒑閸濆嫬鈧綊顢栧▎鎾崇？闊洦鎼╅悢鍡欐喐韫囨梻绠鹃柍褜鍓熼弻鈩冩媴閸濄儛銈吤归悪鍛暤妤犵偛妫滈ˇ瀵哥磼娴ｅ搫鍘存慨濠呮缁瑧鎹勯妸褏鏉介梻浣侯焾鐎涒晜绔熼崱娆愵潟闁绘劕鎼婵囥亜閺嶃劎鐭岄柨娑氬仜椤啴濡堕崱妯硷紩闂佺顑嗛幐鑽ゆ崲濞戙垹绠婚柛鎰▕閺嗐垽姊虹涵鍛毢濠⒀勵殜楠炲繘宕ㄩ婊呯厯濠电偛妫涢崑鎾绘倵椤愩倗纾介柛灞剧懅椤︼附銇勯敂钘夆枙妞ゃ垺鐟︾换婵嬪礋椤撶偛瑙?
            if (existingIndex < insertIndex) {
                insertIndex--
            }
        }

        // 缂傚倸鍊烽懗鍫曟惞鎼淬劌鐭楅幖娣妼缁愭鏌￠崶鈺佇ｇ€规洖寮堕幈銊ノ熼崸妤€鎽靛銈庡亝濞茬喖寮婚弴锛勭杸闁哄洨鍊妷褏纾奸悘鐐跺Г閸嬨儵鏌熼绛嬫疁闁诡喚鍏橀弻鍥晜閸欘偓绠撳?        insertIndex = insertIndex.coerceIn(0, newPlaylist.size)
        newPlaylist.add(insertIndex, song)

        // 闂傚倸鍊风粈渚€骞栭鈷氭椽濡舵径瀣槐闂侀潧艌閺呮盯鎷戦悢灏佹斀闁绘ê寮堕幖鎰磼閳ь剟宕卞Ο鍦畾闂侀潧鐗嗛幊搴敂閵夆晜鐓?        currentPlaylist = newPlaylist
        _currentQueueFlow.value = currentPlaylist
        currentIndex = if (currentSong != null) {
            queueIndexOf(currentSong, newPlaylist)
        } else {
            currentIndex.coerceIn(0, newPlaylist.lastIndex)
        }
        if (player.shuffleModeEnabled) {
            val newSongRealIndex = queueIndexOf(song, newPlaylist)

            if (newSongRealIndex != -1) {
                shuffleBag.remove(newSongRealIndex)
                shuffleFuture.add(newSongRealIndex)
            }
        }

        ioScope.launch {
            persistState()
        }
    }


    /**
     * 闂傚倷娴囬褏鎹㈤幇顔藉床闁归偊鍎靛☉妯锋瀻闁圭偓娼欐禒閬嶆⒑閸濆嫭鍌ㄩ柛銊ョ秺瀹曪繝骞庨懞銉у幈濠电偛妫楀ù姘ｉ崨濠勭闁稿繒鍘ч悘鎾煛鐏炲墽娲寸€殿噮鍣ｅ畷鎺懶掔憗銈呯伈闁哄本鐩崺鈩冩媴閾忕懓濮辨俊銈囧Х閸嬬偤鏁冮姀銈冣偓浣割潨閳ь剟骞冮姀銈呭窛濠电姴鍟伴悾顏堟⒒閸屾艾鈧悂宕愰幖浣哥柈濞村吋娼欑粣妤呭箹濞ｎ剙濡奸柛銊ュ€归妵鍕疀閹捐泛顤€闂佺楠哥粔褰掑蓟濞戙垹鍗抽柕濞垮劚椤亜鈹?
     * @param song 闂傚倷娴囧畷鐢稿窗閹邦優娲箣閿旇棄鈧潡鏌ㄩ弴鐐测偓鎼佹煥閵堝應鏀介柣妯哄级閹兼劗绱掗埀顒佸緞閹邦厾鍘藉┑鈽嗗灥濞咃絾绂掑☉銏＄厽闁挎繂鐗撻崫娲煙娓氬灝濡奸柍瑙勫灴瀹曞崬螣缂佹ê顏板┑?     */
    fun addToQueueEnd(song: SongItem) {
        ensureInitialized()
        if (!initialized) return
        if (currentPlaylist.isEmpty()) {
            // 濠电姷鏁告慨鐑姐€傛禒瀣劦妞ゆ巻鍋撻柛鐔锋健閸┾偓妞ゆ巻鍋撶紓宥咃躬楠炲啫螣鐠囪尙绐為柟鐓庣摠缁嬫劕效濡ゅ懏鈷戦悷娆忓閸斻倝鏌涢悢閿嬪仴鐠侯垶鏌涘☉鍗炴灓缂佺娀绠栭幃姗€鎮欏▓璺ㄥ姼婵犫拃鍐ㄧ骇缂佺粯绻堥崺鈧い鎺嶈兌閻熷綊鏌嶈閸撶喖鎮伴纰辨建闁逞屽墴閵嗕礁顫滈埀顒勫箖閵忋倕宸濆┑鐘插暟閻ｎ亪姊婚崒姘偓鎼佸磹閹间礁鐤ù鍏兼綑缁愭骞栧ǎ顒€濡奸柛銊ュ€归妵鍕疀閹捐泛顤€闂佸搫鎷嬮崜娑㈠焵椤掆偓閸樻粓宕戦幘缁樼厓鐟滄粓宕滃▎鎴濆疾闂備胶顫嬮崟鍨暥缂備胶濮垫繛濠囧蓟閺囩喎绶炴繛鎴欏灪椤庡秹鏌ｈ箛鎾荤崪缂佺粯绻堝濠氭晸閻樿尙鍔﹀銈嗗笒鐎氼剛绮婚弻銉︾厵闂侇叏绠戦弸銈嗐亜閺冣偓閸旀牗绌辨繝鍥舵晝妞ゆ帒鍊昏摫闂備胶绮〃鍡涖€冩繝鍥х畺?            playPlaylist(listOf(song), 0)
            return
        }

        val currentSong = _currentSongFlow.value
        val newPlaylist = currentPlaylist.toMutableList()

        // If the song already exists, move it to the end.
        val existingIndex = newPlaylist.indexOfFirst { it.sameIdentityAs(song) }
        if (existingIndex != -1) {
            newPlaylist.removeAt(existingIndex)
        }

        newPlaylist.add(song)

        // 闂傚倸鍊风粈渚€骞栭鈷氭椽濡舵径瀣槐闂侀潧艌閺呮盯鎷戦悢灏佹斀闁绘ê寮堕幖鎰磼閻樼數甯涢柕鍥у楠炴鎹勯悜妯尖偓楣冩⒑閸濆嫷妲哥紓宥咃工椤繑绻濆顒傤槰濡炪倕绻愮€氼參鎮楁繝姘拺?        currentPlaylist = newPlaylist
        _currentQueueFlow.value = currentPlaylist
        currentIndex = if (currentSong != null) {
            queueIndexOf(currentSong, newPlaylist).takeIf { it >= 0 }
                ?: currentIndex.coerceIn(0, newPlaylist.lastIndex)
        } else {
            currentIndex.coerceIn(0, newPlaylist.lastIndex)
        }

        // 濠电姷鏁告慨鐑姐€傛禒瀣劦妞ゆ巻鍋撻柛鐔锋健閸┾偓妞ゆ巻鍋撶紓宥咃躬楠炲啫螣鐠囪尙绐為梺褰掑亰閸撴繆顤傚┑锛勫亼閸婃牠鎮уΔ鍐ㄥ灊鐎广儱顦Ч鏌ョ叓閸ャ劍濯奸柣鐔煎亰閻撱儵鏌涢幇銊︽珦婵☆偓绠戦埞鎴︽倷閼碱剙顣堕梺鎼炲姀濡嫰顢氶敐澶嬪仺闁告稑锕ら埀顒傜帛娣囧﹪顢涘┑鎰闂佸摜鍠庨敃顏勵潖閾忚鍋橀柍銉ュ帠婢规洟姊绘担绛嬫綈闁稿孩濞婇獮濠囧箛椤斿墽鐒兼繝銏ｅ煐閸旀牠鍩涢幒妤佺厱闁靛鍨抽崚鏉棵瑰鍫㈢暫闁哄矉缍侀幃銏ゅ川婵犲嫸绱辩紓鍌欐祰閸╂牕鐣濈粙璺ㄦ殾鐎规洖娲ㄩ惌娆愮箾閸℃ê鍔ゆ繛鍫熋埞鎴︽倷閸欏鏋欐繛瀛樼矋缁诲牓銆佸Ο瑁や汗闁圭儤鎸鹃崢鎼佹⒑閸撴彃浜栭柛銊ョ秺瀹曟繄鈧綆鍠楅悡娆撴煙闂傚瓨顦烽柟鎻掑⒔閳ь剚顔栭崰鎾诲礉閹达妇宓侀柟杈剧畱缁犳盯鏌涢…鎴濅簼妞?
        if (player.shuffleModeEnabled) {
            rebuildShuffleBag()
        }

        ioScope.launch {
            persistState()
        }
    }

    private fun restoreState() {
        try {
            if (!stateFile.exists()) return
            val type = object : TypeToken<PersistedState>() {}.type
            val data: PersistedState = gson.fromJson(stateFile.readText(), type)
            currentPlaylist = sanitizeRestoredPlaylist(
                data.playlist.map { persistedSong -> persistedSong.toSongItem() }
            )
            if (currentPlaylist.isEmpty()) {
                currentIndex = -1
                _currentQueueFlow.value = emptyList()
                _currentSongFlow.value = null
                _currentMediaUrl.value = null
                _currentPlaybackAudioInfo.value = null
                _playbackPositionMs.value = 0L
                currentMediaUrlResolvedAtMs = 0L
                restoredResumePositionMs = 0L
                restoredShouldResumePlayback = false
                resumePlaybackRequested = false
                return
            }
            val preferredSong = data.playlist.getOrNull(data.index)?.toSongItem()
            currentIndex = when {
                currentPlaylist.isEmpty() -> -1
                preferredSong != null -> queueIndexOf(preferredSong, currentPlaylist).takeIf { it >= 0 }
                    ?: data.index.coerceIn(0, currentPlaylist.lastIndex)
                data.index in currentPlaylist.indices -> data.index
                else -> 0
            }
            _currentQueueFlow.value = currentPlaylist
            _currentSongFlow.value = currentPlaylist.getOrNull(currentIndex)
            _currentMediaUrl.value = data.mediaUrl?.takeIf {
                _currentSongFlow.value?.let(::isLocalSong) != true ||
                    _currentSongFlow.value?.let(::isRestorableLocalSong) == true
            }
            repeatModeSetting = if (keepPlaybackModeStateEnabled) {
                when (data.repeatMode) {
                    Player.REPEAT_MODE_ALL,
                    Player.REPEAT_MODE_ONE,
                    Player.REPEAT_MODE_OFF -> data.repeatMode
                    else -> Player.REPEAT_MODE_OFF
                }
            } else {
                Player.REPEAT_MODE_OFF
            }
            syncExoRepeatMode()
            _repeatModeFlow.value = repeatModeSetting

            val restoreShuffleEnabled = keepPlaybackModeStateEnabled && (data.shuffleEnabled == true)
            player.shuffleModeEnabled = restoreShuffleEnabled
            _shuffleModeFlow.value = restoreShuffleEnabled
            shuffleHistory.clear()
            shuffleFuture.clear()
            if (restoreShuffleEnabled) {
                rebuildShuffleBag(excludeIndex = currentIndex)
            } else {
                shuffleBag.clear()
            }

            restoredResumePositionMs = if (keepLastPlaybackProgressEnabled) {
                data.positionMs.coerceAtLeast(0L)
            } else {
                0L
            }
            restoredShouldResumePlayback = data.shouldResumePlayback && currentIndex != -1
            resumePlaybackRequested = restoredShouldResumePlayback
            _playbackPositionMs.value = restoredResumePositionMs
            currentMediaUrlResolvedAtMs = 0L
        } catch (e: Exception) {
            NPLogger.w("NERI-PlayerManager", "Failed to restore state: ${e.message}")
        }
    }

    fun resumeRestoredPlaybackIfNeeded(): Long? {
        ensureInitialized()
        if (!initialized) return null
        if (!restoredShouldResumePlayback) return null
        if (currentPlaylist.isEmpty() || currentIndex !in currentPlaylist.indices) return null
        val resumeIndex = currentIndex
        val resumePositionMs = restoredResumePositionMs.coerceAtLeast(0L)
        restoredShouldResumePlayback = false
        restoredResumePositionMs = 0L
        lastStatePersistAtMs = SystemClock.elapsedRealtime()
        playAtIndex(
            resumeIndex,
            resumePositionMs = resumePositionMs,
            forceStartupProtectionFade = true
        )
        return resumePositionMs
    }

    fun suppressFutureAutoResumeForCurrentSession(forcePersist: Boolean = false) {
        ensureInitialized()
        if (!initialized || currentPlaylist.isEmpty()) return
        suppressAutoResumeForCurrentSession = true
        restoredShouldResumePlayback = false
        val positionMs = if (::player.isInitialized) {
            player.currentPosition.coerceAtLeast(0L)
        } else {
            _playbackPositionMs.value.coerceAtLeast(0L)
        }
        _playbackPositionMs.value = positionMs
        if (forcePersist) {
            blockingIo {
                persistState(positionMs = positionMs, shouldResumePlayback = false)
            }
        } else {
            ioScope.launch {
                persistState(positionMs = positionMs, shouldResumePlayback = false)
            }
        }
    }


    fun replaceMetadataFromSearch(
        originalSong: SongItem,
        selectedSong: SongSearchInfo,
        isAuto: Boolean = false
    ) {
        ioScope.launch {
            val platform = selectedSong.source

            val api = when (platform) {
                MusicPlatform.CLOUD_MUSIC -> cloudMusicSearchApi
                MusicPlatform.QQ_MUSIC -> qqMusicSearchApi
            }

            try {
                val newDetails = api.getSongInfo(selectedSong.id)

                val updatedSong = if (isAuto) {
                    originalSong.copy(
                        matchedLyric = newDetails.lyric ?: originalSong.matchedLyric,
                        matchedTranslatedLyric = newDetails.translatedLyric ?: originalSong.matchedTranslatedLyric,
                        matchedLyricSource = selectedSong.source,
                        matchedSongId = selectedSong.id
                    )
                } else {
                    applyManualSearchMetadata(
                        originalSong = originalSong,
                        songName = newDetails.songName,
                        singer = newDetails.singer,
                        coverUrl = newDetails.coverUrl,
                        lyric = newDetails.lyric,
                        translatedLyric = newDetails.translatedLyric,
                        matchedSource = selectedSong.source,
                        matchedSongId = selectedSong.id,
                        useCustomOverride = shouldApplySearchMetadataAsCustomOverride(originalSong)
                    )
                }

                updateSongInAllPlaces(originalSong, updatedSong)

            } catch (e: Exception) {
                mainScope.launch {
                    Toast.makeText(
                        application,
                        getLocalizedString(R.string.toast_match_failed, e.message.orEmpty()),
                        Toast.LENGTH_SHORT
                    ).show()
                    NPLogger.e("NERI-PlayerManager", "replaceMetadataFromSearch failed: ${e.message}", e)
                }
            }
        }
    }

    private fun shouldApplySearchMetadataAsCustomOverride(song: SongItem): Boolean {
        return isLocalSong(song) || AudioDownloadManager.getLocalPlaybackUri(application, song) != null
    }

    fun updateSongCustomInfo(
        originalSong: SongItem,
        customCoverUrl: String?,
        customName: String?,
        customArtist: String?
    ) {
        ioScope.launch {
            NPLogger.d("PlayerManager", "updateSongCustomInfo: id=${originalSong.id}, album='${originalSong.album}'")

            // 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忛悗娑欙供濞堜粙鏌涘┑鍕姢缂佲偓婵犲洦鍊甸柨婵嗛閺嬫稓绱掗埀顒勫醇閳垛晛浜炬鐐茬仢閸旀碍淇婇锝囨噮濞存粍鎮傞弫鎾绘偐瀹曞洤骞愰梻渚€鈧偛鑻晶瀛橆殽閻愬弶顥℃い锔界叀閺岋綀绠涢弮鍌涘櫚闂佽鍠涘▔娑㈠煡婢舵劕鍐€鐟滃秹鈥栭崱娑欌拺闁绘挸娴锋禒娑㈡煕閵娿儲鍋ユ鐐诧躬婵偓闁绘ê鐏氬▓婵嬫⒑閸濆嫷妲兼繛澶嬫礋楠炲啯绺介崨濞炬嫼闂佸憡绻傜€氼喗鏅堕幇鐗堢厱閻庯綆鍋呭畷宀勬煛瀹€瀣瘈鐎规洘甯掗埥澶婎潨閸℃﹩妫嗗┑鐘垫暩閸嬫盯鎮ф繝鍥у瀭闁汇垽娼荤紞鏍煏韫囷絾绶氭繛宀婁邯閺岋綁骞囬棃娑樷拫闂佽桨鐒︾划鎾愁潖?濠电姷鏁搁崕鎴犲緤閽樺娲晜閻愵剙搴婇梺鍛婃处閸ㄦ澘效閺屻儲鐓冪憸婊堝礈濮樿泛绠為柕濞垮劗閺€浠嬫煕閳╁啰鎳呴柛鏃戝櫍濮婄儤瀵煎▎鎾搭€嶇紓浣筋嚙鐎氼噣宕氶幒妤€鍨傛い鎰╁灮缁愮偞绻濋悽闈浶㈤悗姘煎弮楠?
            val currentSong = currentPlaylist.firstOrNull { it.sameIdentityAs(originalSong) }
                ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(originalSong) }
                ?: originalSong

            val baseName = currentSong.name
            val baseArtist = currentSong.artist
            val baseCoverUrl = currentSong.coverUrl
            val originalName = currentSong.originalName ?: baseName
            val originalArtist = currentSong.originalArtist ?: baseArtist
            val originalCoverUrl = currentSong.originalCoverUrl ?: baseCoverUrl

            val normalizedCustomName = normalizeCustomMetadataValue(
                desiredValue = customName,
                baseValue = baseName
            )
            val normalizedCustomArtist = normalizeCustomMetadataValue(
                desiredValue = customArtist,
                baseValue = baseArtist
            )
            val normalizedCustomCoverUrl = normalizeCustomMetadataValue(
                desiredValue = customCoverUrl,
                baseValue = baseCoverUrl
            )

            val updatedSong = currentSong.copy(
                customName = normalizedCustomName,
                customArtist = normalizedCustomArtist,
                customCoverUrl = normalizedCustomCoverUrl,
                originalName = originalName,
                originalArtist = originalArtist,
                originalCoverUrl = originalCoverUrl
            )

            updateSongInAllPlaces(originalSong, updatedSong)
        }
    }

    suspend fun updateUserLyricOffset(songToUpdate: SongItem, newOffset: Long) {
        val queueIndex = queueIndexOf(songToUpdate)
        if (queueIndex != -1) {
            val updatedSong = currentPlaylist[queueIndex].copy(userLyricOffsetMs = newOffset)
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
        }

        if (isCurrentSong(songToUpdate)) {
            _currentSongFlow.value = _currentSongFlow.value?.copy(userLyricOffsetMs = newOffset)
        }

        val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
            ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(songToUpdate) }
        if (latestSong != null) {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(songToUpdate, latestSong)
            }
        }

        persistState()
    }

    suspend fun updateSongLyrics(songToUpdate: SongItem, newLyrics: String?) {
        val queueIndex = queueIndexOf(songToUpdate)
        if (queueIndex != -1) {
            val updatedSong = currentPlaylist[queueIndex].copy(matchedLyric = newLyrics)
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
        }

        if (isCurrentSong(songToUpdate)) {
            _currentSongFlow.value = _currentSongFlow.value?.copy(matchedLyric = newLyrics)
        }

        // 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忛柟缁㈠枟閻撯偓闂佹寧绻傞幉姗€鏁愭径瀣珖闂佺鏈銊┧囬鈶╂斀闁挎稑瀚禒锔锯偓瑙勬礃閿曘垹鐣烽姀銈庢晢闁告洦鍓涢崢楣冩⒑鐠団€崇€婚柛鎰ㄦ暕閳哄懏鍋℃繝濠傚缁跺弶绻涘顔煎箹妞ゆ洩缍侀幃鐣岀矙鐠恒劌濮︽俊鐐€栫敮鎺斺偓姘煎墮铻炴慨妞诲亾闁哄瞼鍠庨埢鎾诲垂椤旂晫浜堕梻浣风串缂嶁偓濡炲瓨鎮傞獮鍫ュΩ閵夈垺鏂€闂佺硶鍓濊摫闁瑰嘲宕埞鎴︽倷閺夋垹绁烽梺纭咁嚋缁辨洜鍒掑鑸电劶鐎广儱鎳愰崝鎾⒑閸涘﹤澹冮柛娑卞灱濡差剟姊婚崒娆戝妽閻庣瑳鍛煓闁硅揪闄勯崕妤呮煕鐏炲墽鈽夋い鏇憾閺屽秹宕崟顒€娅ｇ紓浣插亾闁稿本澹曢崑鎾荤嵁閸喖濮庨梺缁橆殕缁瞼鍙呭銈嗗笒鐎氼參鎮￠悢鍏肩厓闁告繂瀚埀顒傜帛閺呭爼顢旈崼鐔哄幈濠电偛妫欓崹鍨閸撗呯＜闁稿本绋戝ù顔锯偓娈垮枛閳ь剛鍣ュΣ楣冩⒑?
        val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
        if (latestSong != null) {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(songToUpdate, latestSong)
            }
        }

        persistState()
    }

    suspend fun updateSongTranslatedLyrics(songToUpdate: SongItem, newTranslatedLyrics: String?) {
        val queueIndex = queueIndexOf(songToUpdate)
        if (queueIndex != -1) {
            val updatedSong = currentPlaylist[queueIndex].copy(matchedTranslatedLyric = newTranslatedLyrics)
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
        }

        if (isCurrentSong(songToUpdate)) {
            _currentSongFlow.value = _currentSongFlow.value?.copy(matchedTranslatedLyric = newTranslatedLyrics)
        }

        // 濠电姷鏁搁崑娑㈩敋椤撶喐鍙忛柟缁㈠枟閻撯偓闂佹寧绻傞幉姗€鏁愭径瀣珖闂佺鏈銊┧囬鈶╂斀闁挎稑瀚禒锔锯偓瑙勬礃閿曘垹鐣烽姀銈庢晢闁告洦鍓涢崢楣冩⒑鐠団€崇€婚柛鎰ㄦ暕閳哄懏鍋℃繝濠傚缁跺弶绻涘顔煎箹妞ゆ洩缍侀幃鐣岀矙鐠恒劌濮︽俊鐐€栫敮鎺斺偓姘煎墮铻炴慨妞诲亾闁哄瞼鍠庨埢鎾诲垂椤旂晫浜堕梻浣风串缂嶁偓濡炲瓨鎮傞獮鍫ュΩ閵夈垺鏂€闂佺硶鍓濊摫闁瑰嘲宕埞鎴︽倷閺夋垹绁烽梺纭咁嚋缁辨洜鍒掑鑸电劶鐎广儱鎳愰崝鎾⒑閸涘﹤澹冮柛娑卞灱濡差剟姊婚崒娆戝妽閻庣瑳鍛煓闁硅揪闄勯崕妤呮煕鐏炲墽鈽夋い鏇憾閺屽秹宕崟顒€娅ｇ紓浣插亾闁稿本澹曢崑鎾荤嵁閸喖濮庨梺缁橆殕缁瞼鍙呭銈嗗笒鐎氼參鎮￠悢鍏肩厓闁告繂瀚埀顒傜帛閺呭爼顢旈崼鐔哄幈濠电偛妫欓崹鍨閸撗呯＜闁稿本绋戝ù顔锯偓娈垮枛閳ь剛鍣ュΣ楣冩⒑?
        val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
        if (latestSong != null) {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(songToUpdate, latestSong)
            }
        }

        persistState()
    }

    suspend fun updateSongLyricsAndTranslation(songToUpdate: SongItem, newLyrics: String?, newTranslatedLyrics: String?) {
//        NPLogger.e("PlayerManager", "!!! FUNCTION CALLED !!! updateSongLyricsAndTranslation")
//        NPLogger.e("PlayerManager", "songId=${songToUpdate.id}, album='${songToUpdate.album}'")
//        NPLogger.e("PlayerManager", "newLyrics=${newLyrics?.take(50)}, newTranslatedLyrics=${newTranslatedLyrics?.take(50)}")

        // 闂傚倸鍊烽懗鍫曞箠閹剧粯鍊舵慨妯挎硾缁犳壆绱掔€ｎ厽纭堕柡鍡愬€濋弻娑㈠箛闂堟稒鐏堢紓浣哄Х缁垶濡甸崟顖氱閻犺櫣鍎ら悗楣冩⒑閸濆嫷妲哥紓宥咃躬瀵鈽夐姀鐘殿唺闂佺懓鐏濋崯顖炴偩娴犲鈷戦梻鍫熺⊕椤ユ粓鏌涢悤浣哥仯缂侇喗鐟﹀鍕箛閸偅娅嶉梻浣虹帛閺屻劑銆冩惔銊ユ辈闁跨喓濮甸埛鎴︽煕濠靛棗顏褎娲橀妵鍕晜閸喖绁梺璇″枟濡炶棄顕ｉ幘顔碱潊闁绘ɑ褰冪粊顕€姊绘担钘変汗闁冲嘲鐗撳畷婊冾潩閼搁潧浠奸梺鐑╂櫆缁愬姊婚崒娆戭槮闁硅绱曢崚鎺戠暆閸曨剙浠奸梺姹囧灮缁傚〗m闂傚倸鍊烽悞锔锯偓绗涘懐鐭欓柟杈鹃檮閸ゆ劖銇勯弽銊р姇闁稿海鍠愮换婵囩節閸屾粌顣虹紓浣插亾濠㈣埖鍔栭悡鍐煕濠靛棗顏╂い蹇ｅ亝缁绘盯宕煎┑鍫濈厽闂佽鍠栫紞濠傜暦閸洖惟闁挎梻铏庡Σ顒勬⒑鐠囪尙绠版繛瀛樺哺瀹曟娊鏁愭径濠呮憰婵犵數濮撮崑鍡涘触鐎ｎ亶鐔嗛悹杞拌閸庢粎绱?
//        NPLogger.e("PlayerManager", "=== 闂備浇宕甸崰鎰垝鎼淬垺娅犳俊銈呮噹缁犱即鏌涘☉姗堟敾婵炲懐濞€閺岋絽螣濞嗘儳娈紓浣哄Х缁垶濡甸崟顖氱閻犺櫣鍎ら悗楣冩⒑閸濆嫷妲哥紓宥咃躬瀵鈽夐姀鐘殿唺闂佺懓鐏濋崯顖炴偩娴犲鈷戦梻鍫熺⊕椤ユ粓鏌涢悤浣哥仯缂侇喗鐟﹀鍕箛閸偅娅嶉梻浣虹帛閿氱痪缁㈠弮瀵娊鎮╃紒妯锋嫼闂侀潻瀵岄崣搴ㄥ汲椤栫偞鐓曢悗锝庡亝瀹曞矂鏌″畝瀣瘈鐎规洜鍘ч埞鎴﹀箛椤撳／鍥ㄢ拺?===")
//        currentPlaylist.forEachIndexed { index, song ->
//            NPLogger.e("PlayerManager", "[$index] id=${song.id}, album='${song.album}', name='${song.name}', hasLyric=${song.matchedLyric != null}")
//        }
//        NPLogger.e("PlayerManager", "=== 闂傚倸鍊风粈浣革耿闁秵鎯為幖娣妼閸屻劌霉閻樺樊鍎忔俊顐Ｃ妴鎺戭潩閿濆懍澹曢柣搴ゎ潐濞叉﹢鎳濋幑鎰簷闂備礁鎲″ú宥夊棘娓氣偓瀹曟垿骞樼拠鑼槰濡炪倖妫侀崑鎰邦敇婵傚憡鈷戦悷娆忓閸斻倝鏌涢悢缁樼《缂侇喖锕、鏃堝醇閻斿皝鍋撻悽鍛婄厽闁绘柨鎼。鍏肩節閳ь剚瀵肩€涙鍘?===")

        val queueIndex = queueIndexOf(songToUpdate)
//        NPLogger.e("PlayerManager", "queueIndex=$queueIndex, currentPlaylist.size=${currentPlaylist.size}")

        if (queueIndex != -1) {
            val updatedSong = currentPlaylist[queueIndex].copy(
                matchedLyric = newLyrics,
                matchedTranslatedLyric = newTranslatedLyrics
            )
//            NPLogger.e("PlayerManager", "闂傚倸鍊风粈渚€骞栭鈷氭椽濡舵径瀣槐闂侀潧艌閺呮盯鎷? matchedLyric=${currentPlaylist[queueIndex].matchedLyric?.take(50)}")
//            NPLogger.e("PlayerManager", "闂傚倸鍊风粈渚€骞栭鈷氭椽濡舵径瀣槐闂侀潧艌閺呮盯鎷? matchedLyric=${updatedSong.matchedLyric?.take(50)}")
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
            NPLogger.e("PlayerManager", "Queue song updated")
        } else {
            NPLogger.e("PlayerManager", "Song to update was not found in queue")
        }

        NPLogger.e("PlayerManager", "Current playing song: id=${_currentSongFlow.value?.id}, album='${_currentSongFlow.value?.album}'")
        if (isCurrentSong(songToUpdate)) {
            val beforeUpdate = _currentSongFlow.value?.matchedLyric
            _currentSongFlow.value = _currentSongFlow.value?.copy(
                matchedLyric = newLyrics,
                matchedTranslatedLyric = newTranslatedLyrics
            )
            NPLogger.e("PlayerManager", "Current song lyrics updated: before=${beforeUpdate?.take(50)}, after=${_currentSongFlow.value?.matchedLyric?.take(50)}")
        } else {
            NPLogger.e("PlayerManager", "Current song does not match target update")
        }

        // 将最新歌词变更同步到本地仓库，保证内存态和持久化数据一致。
        val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
        if (latestSong != null) {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(songToUpdate, latestSong)
            }
            NPLogger.d(
                "PlayerManager",
                "歌词更新已同步到本地仓库: id=${latestSong.id}, lyric=${latestSong.matchedLyric?.take(32)}, translated=${latestSong.matchedTranslatedLyric?.take(32)}"
            )
        } else {
            NPLogger.e("PlayerManager", "歌词更新后未找到最新歌曲副本，跳过本地仓库同步")
        }

        persistState()
        NPLogger.d("PlayerManager", "updateSongLyricsAndTranslation completed")
    }

    private suspend fun updateSongInAllPlaces(originalSong: SongItem, updatedSong: SongItem) {
        val queueIndex = queueIndexOf(originalSong)
        if (queueIndex != -1) {
            val newList = currentPlaylist.toMutableList()
            newList[queueIndex] = updatedSong
            currentPlaylist = newList
            _currentQueueFlow.value = currentPlaylist
        }

        if (isCurrentSong(originalSong)) {
            _currentSongFlow.value = updatedSong
        }

        withContext(Dispatchers.IO) {
            localRepo.updateSongMetadata(originalSong, updatedSong)
        }
        GlobalDownloadManager.syncDownloadedSongMetadata(updatedSong)
        AppContainer.playHistoryRepo.updateSongMetadata(originalSong, updatedSong)
        AppContainer.playlistUsageRepo.syncLocalEntries(localRepo.playlists.value)

        persistState()
    }

}

internal fun normalizeCustomMetadataValue(
    desiredValue: String?,
    baseValue: String?
): String? {
    val normalizedDesired = desiredValue?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return normalizedDesired.takeIf { it != baseValue }
}

internal fun applyManualSearchMetadata(
    originalSong: SongItem,
    songName: String,
    singer: String,
    coverUrl: String?,
    lyric: String?,
    translatedLyric: String?,
    matchedSource: MusicPlatform,
    matchedSongId: String,
    useCustomOverride: Boolean
): SongItem {
    val originalName = originalSong.originalName ?: originalSong.name
    val originalArtist = originalSong.originalArtist ?: originalSong.artist
    val originalCoverUrl = originalSong.originalCoverUrl ?: originalSong.coverUrl

    return if (useCustomOverride) {
        originalSong.copy(
            matchedLyric = lyric,
            matchedTranslatedLyric = translatedLyric,
            matchedLyricSource = matchedSource,
            matchedSongId = matchedSongId,
            customCoverUrl = normalizeCustomMetadataValue(coverUrl, originalSong.coverUrl),
            customName = normalizeCustomMetadataValue(songName, originalSong.name),
            customArtist = normalizeCustomMetadataValue(singer, originalSong.artist),
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalSong.originalLyric ?: originalSong.matchedLyric,
            originalTranslatedLyric = originalSong.originalTranslatedLyric ?: originalSong.matchedTranslatedLyric
        )
    } else {
        originalSong.copy(
            name = songName,
            artist = singer,
            coverUrl = coverUrl,
            matchedLyric = lyric,
            matchedTranslatedLyric = translatedLyric,
            matchedLyricSource = matchedSource,
            matchedSongId = matchedSongId,
            customCoverUrl = null,
            customName = null,
            customArtist = null,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalSong.originalLyric ?: originalSong.matchedLyric,
            originalTranslatedLyric = originalSong.originalTranslatedLyric ?: originalSong.matchedTranslatedLyric
        )
    }
}


