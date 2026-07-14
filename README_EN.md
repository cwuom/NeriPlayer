[English](./README_EN.md) | [中文](./README.md)

<h1 align="center">NeriPlayer</h1>

<div align="center">

<h3>✨ A native Android audio player that combines multi-source streaming, local control, rich lyrics, and self-hosted sync 🎵</h3>

<p>
  <a href="https://github.com/cwuom/NeriPlayer/releases">
    <img alt="Downloads" src="https://img.shields.io/github/downloads/cwuom/NeriPlayer/total?style=social" />
  </a>
  <a href="https://github.com/cwuom/NeriPlayer/releases">
    <img alt="Release" src="https://img.shields.io/github/v/release/cwuom/NeriPlayer?include_prereleases&label=Release" />
  </a>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" />
  <a href="https://t.me/ouom_pub">
    <img alt="Telegram" src="https://img.shields.io/badge/Telegram-@ouom__pub-blue" />
  </a>
  <a href="https://t.me/neriplayer_ci">
    <img alt="CI Builds" src="https://img.shields.io/badge/CI_Builds-@neriplayer__ci-orange" />
  </a>
</p>

<p>
  <img src="icon/neriplayer.svg" width="260" alt="NeriPlayer logo" />
</p>

<p>
The project name and icon are inspired by "Kazamata Neri" from
"星空鉄道とシロの旅".
</p>

<p>
NeriPlayer is a native Android app for Android 9 (API 28) and above,
focused on multi-source exploration, online playback, local control, and
user-owned data.
</p>

🛠️ <strong>Active development</strong>

<a href="https://trendshift.io/repositories/23906" target="_blank"><img src="https://trendshift.io/api/badge/repositories/23906" alt="cwuom%2FNeriPlayer | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

</div>

> [!WARNING]
> This project is for learning and research purposes only. Do not use it for illegal purposes.
>
> This project and its maintainer do not accept any form of sponsorship, donation, or commercial funding.

---

> [!NOTE]
> NeriPlayer does not provide a public cloud music library or media distribution service.
> Online audio capabilities depend on your authorization on third-party platforms.
> VIP or restricted content still follows the original platform rules.

---

## Start here

If you only want to try the app, start with [Getting Started](#getting-started).
If you want to understand what makes the project different, read
[Why it stands out](#why-it-stands-out) and [Key Features](#key-features).
If you plan to contribute, read [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md).
If you want to self-host Listen Together, jump to
[Listen Together Deployment](#listen-together-deployment).

```text
NeriPlayer
├── Multi-source playback: NetEase / Bilibili / YouTube Music
├── Local-first data: cache, downloads, playlists, history, stats, settings
├── User-owned sync: GitHub / WebDAV metadata sync
├── Rich playback: Media3, lyrics, effects, fluid background, floating/status-bar lyrics
└── Recovery built in: safe mode, crash logs, ANR traces, debug probes
```

---

## About

NeriPlayer is a native Android audio player built with **Jetpack Compose + Media3**.
It does not build a public cloud service. Instead, it integrates online content
from **NetEase Cloud Music**, **Bilibili**, and **YouTube Music** when the user
has the corresponding third-party platform account capability. It also provides
local playback, downloads, caching, playlist management, and several sync/backup
options.

Current positioning:

- **Account as capability**: third-party platform authorization enables search,
  playback, playlists, and favorites access.
- **Local-first**: playback cache, downloads, playlists, history, settings, and
  auth data are stored locally on the device by default.
- **Optional sync**: playlists, favorites, recent plays, and playback stats can
  be synced to your own GitHub repository (repositories created in-app default
  to private) or a WebDAV remote file.
- **Privacy and account safety first**: sync is intentionally decentralized.
  Data is written to GitHub/WebDAV storage that you control, not to a centralized
  service operated by the project maintainer. The app is technically capable of
  sending playback history back to third-party music platforms, but centralized
  music clients often have risk-control and behavior-sampling systems. Uploading
  local playback history directly may be interpreted as abnormal login or playback
  behavior and could put an account at risk. To protect account safety,
  NeriPlayer does not upload local playback history or playback stats back to
  those platforms.
- **Single Activity + Compose**: `MainActivity` is the only external entry point.
  The UI is organized by Compose `NavHost`, a dynamic bottom bar, Mini Player,
  and the Now Playing overlay.
- **Startup and recovery flow**: the normal startup path is
  `Loading -> Disclaimer -> Onboarding -> Main`. If the previous launch ended in
  a crash or system ANR, the app enters `Safe Mode` first.
- **Test guardrails**: download storage, sync merging, YouTube playback,
  Listen Together, lyrics, playback policies, config backup, and safe mode all
  have focused unit or device tests.

---

## Why it stands out

- **Local-first, with real offline behavior**:
  `NetworkStatusMonitor` detects whether Android has a validated network, while
  `offlineCachedImageRequest` disables remote image requests in offline mode and
  falls back to local caches. Home, Explore, Now Playing, Lyrics, playlists, and
  downloads all receive `offlineMode`, so local files, downloaded audio,
  playback cache, cached covers, and local playlists remain usable without a network.
- **Multi-source playback is not just a list of entry points**:
  `PlayerManager` owns stream resolution, queues, and failure recovery. When a
  NetEase track is unavailable, has no playable URL, or only returns a preview,
  the player first tries a lower quality, then
  `PlayerManagerNeteaseAutoSourceSwitch` scores Bilibili candidates by title,
  artist, and duration. Playback errors can also refresh the current URL before
  falling back to skip/stop behavior.
- **High-performance GLSL/AGSL fluid background**:
  the Now Playing dynamic background is rendered frame-by-frame by
  `BgEffectPainter`, `RuntimeShader`, and `assets/hyper_background_effect.glsl`.
  The shader combines cover-derived colors, animated color blobs, and lightweight
  grain while reacting to `uMusicLevel / uBeat`; it is not just a Gaussian-blurred
  cover. The RuntimeShader/audio-reactive path requires Android 13+. Cover blur and
  advanced blur require Android 12+, and older versions automatically use a
  compatible fallback without those effects.
- **Apple Music-style lyrics, backed by the playback pipeline**:
  `SyncedLyricsView` and `AdvancedLyricsView` support word/character-timed
  highlighting, translated lyrics, phonetic display, lyric offset, click-to-seek,
  long-press sharing, depth blur, edge fade, and a full-screen Lyrics page.
  `LyricShareSheet` can select lyric lines, copy text, share the song, or render
  a 1080px lyric card. Floating lyrics, status-bar lyrics,
  SuperLyric, Lyricon, Bluetooth lyrics, and lyric editing reuse the same
  playback data path.
- **Complete local music management**:
  `LocalAudioImportManager` supports external share/open imports, authorized
  folder scanning, device media-library scanning, common audio formats, nearby
  `lrc/txt` lyrics, and `cover/folder/front` cover files. Large scans can show
  a quick preview first, then hydrate richer title/artist/album/cover metadata
  in the background. `LocalPlaylistRepository` manages system local playlists,
  user playlists, favorites, sorting, de-duplication, backup, and sync triggers.
- **Library browsing now has real categories**:
  `Library` is no longer just a playlist list. Local content can switch between
  playlists and artists, while `LocalArtistSummary` groups songs by display
  artist, splits common collaboration artist text, and keeps stable identity
  and cover selection. NetEase songs can open an artist page with songs, albums,
  and follow state.
- **Large screens and daily controls are getting real polish**:
  tablet/landscape Now Playing, Lyrics, Settings, and artist pages use steadier
  width constraints and bottom control layouts. The `Mini Player` supports
  horizontal swipe for previous/next without expanding the full player.
- **Sound controls are tied to the active audio session**:
  `PlaybackEffectsController` applies speed, pitch, Android `Equalizer`, and
  `LoudnessEnhancer` to the current Media3 audio session. Presets, manual bands,
  loudness gain, per-track real-time normalization, fade/crossfade, pause on
  Bluetooth disconnect, USB exclusive playback, and audio-focus behavior are
  all available. Native USB exclusive
  playback currently targets **UAC1.0** audio devices, so system sounds and other
  apps cannot share the USB transport on that path. Device selection, sample-rate/bit-depth/
  buffer policies, background-playback guidance, startup watchdogs, foreground/
  background health audits, and automatic stall recovery are now part of the path.
- **Downloads have moved from "can save" to "can recover"**:
  downloads do not use the system `DownloadManager`. They use the shared
  `OkHttpClient`, configurable concurrency, staging files, and sidecar metadata.
  Direct links, YouTube range chunks, and HLS each have a resume strategy.
  Network-policy pauses can continue later, startup recovery restores unfinished
  queues, and already-finalized local hits are settled directly. Manual
  cancellation cleans up partial artifacts.
- **Storage usage is visible and cleanup has boundaries**:
  `StorageUsageAnalyzer` groups audio cache, image cache, download staging,
  share staging, platform playlist cache, downloaded content, logs, crash
  reports, and core app data. Cache cleanup targets regenerable cache data and
  does not treat user-saved downloads as disposable cache.
- **Self-owned sync plus playback stats**:
  NeriPlayer does not provide a public cloud library or developer-hosted user
  data service. GitHub/WebDAV sync stores playlists, favorites, recent plays,
  and playback stats in the user's own remote. `PlaybackStatsRepository` records
  play count, total listen time, first/last played time, and daily buckets by
  stable track identity, then participates in sync merging.
- **Traffic controls are built into the product, not bolted on later**:
  `TrafficStatsRepository` tracks playback/download bytes, Wi-Fi/mobile/roaming
  distribution, and cache-hit bytes. Download flows can also warn before high-risk
  mobile or roaming transfers.
- **Highly personalized, beyond theme colors**:
  `AutoSettingsSchema` covers dynamic colors, seed colors, palette style,
  auto/light/dark mode, UI scaling, custom backgrounds, lyric font size,
  lyric blur, the fluid Now Playing background, Home card toggles, default start
  destination, haptic feedback, and custom song title/artist/cover metadata.
- **ANR, crash logs, and safe mode form a diagnostics loop**:
  `AnrWatchdog` reads Android `ApplicationExitInfo.REASON_ANR` and stores the
  system ANR trace. `ExceptionHandler` and `NativeCrashHandler` record JVM and
  native crashes. If the previous startup failed, `SafeModeManager` can skip full
  app initialization and open safe mode for preview, copy, or export.
- **Listen Together syncs the room, not just a progress bar**:
  the Android client and Cloudflare Worker maintain rooms, roles, queues,
  playback state, repeat/shuffle modes, controller-offline recovery, member
  control requests, optional stream-link sharing, version-gated updates, and
  custom server URLs. Repeat/shuffle changes use `PLAYBACK_MODE` /
  `REQUEST_PLAYBACK_MODE`, while member controls and asynchronous stream-link
  results validate the target stable track key so they cannot affect a track
  that has already changed. The client also estimates server clock offset, corrects
  position drift by threshold, and reloads the authoritative stream after an
  asynchronous shared link arrives so pending local startup cannot override
  the room's pause/play command.
  Durable Objects persist room state while WebSocket keeps active members in sync.

---

## Getting Started

### a. Download a Release build (recommended)

1. Go to [GitHub Releases](https://github.com/cwuom/NeriPlayer/releases).
2. Which APK should you choose?
   - Most phones should use `arm64-v8a`.
   - Older 32-bit devices should use `armeabi-v7a`.
   - `x86` / `x86_64` are mainly for emulators, Intel devices, or Chromebooks.

> [!IMPORTANT]
> The Release channel is not a strict stable channel. Builds are usually pushed
> manually after a batch of features is completed and may still contain issues.

### b. Download a CI build

1. Go to [GitHub Actions](https://github.com/cwuom/NeriPlayer/actions), download
   the Artifacts from the latest successful build, and extract them.
2. Or visit [NeriPlayer CI Builds](https://t.me/neriplayer_ci).

> The master CI artifact is `arm64-v8a` by default; the manual Release workflow
> builds multi-ABI APKs.

### c. Local build

1. Clone the repository and initialize submodules:
   ```bash
   git clone --recursive https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. Open the project with the latest stable Android Studio and sync dependencies.
3. Build the Debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. Install the APK:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. On first launch, read the disclaimer and complete onboarding. Android 13+
   devices will request notification permission.
6. For debugging tools, tap the **version number** 7 times in Settings. A
   standalone `Debug` tab will appear in the bottom bar.

> Debug builds are for testing only. Their performance and size do not represent Release builds.

For release build and signing details, see
[CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md#release-build).

---

## Key Features

- 🎧 **Multi-source exploration and playback**:
  supports NetEase Cloud Music, Bilibili, YouTube Music, and local audio.
- 🏠 **Home recommendations and continue listening**:
  the Home page supports recently used playlists and recommendation cards.
  International mode prioritizes YouTube Music home shelves.
- 🗂️ **Categorized Library browsing**:
  `Library` includes Local, Favorites, NetEase, YouTube Music, and Bilibili areas.
  Local content can switch between playlists/artists with search and artist
  sorting; Favorites can switch between playlists/artists; NetEase can switch
  between playlists/albums; Bilibili separates created favorites, subscribed
  favorites, and collections.
- 🔍 **Layered search**:
  `Explore` searches NetEase / Bilibili / YouTube Music separately.
  Playback metadata completion uses NetEase / QQ Music and integrates LRCLIB
  as an external lyrics source.
- 🧠 **Media3 playback core**:
  `PlayerManager` handles stream resolution, queue state, shuffle/repeat,
  persistence, failure retry, playback URL refresh, YouTube prefetching, and
  platform-specific request policies.
- 🔁 **NetEase auto source switch**:
  when a NetEase song is unavailable, has no playable URL, or only returns a
  preview clip, the player first tries lower quality and can then match a
  Bilibili fallback source by title, artist, and duration.
- 🧯 **Playback failure fallback**:
  playback errors first try refreshing the active playback URL. Bilibili stream
  resolution retries missing DASH audio and can fall back to html5/mp4 progressive
  streams; repeated failures skip or stop playback to avoid getting stuck.
- 🎚️ **Playback sound controls**:
  Now Playing includes speed, pitch, loudness enhancer, Android system equalizer
  presets, and manual EQ bands. Playback settings also provide per-track
  real-time loudness normalization, which is bypassed during USB exclusive playback.
- 🎛️ **Fine-grained playback behavior**:
  keep last playback progress, restore playback mode, fade-in/fade-out,
  crossfade-next, pause on Bluetooth disconnect, USB exclusive playback,
  mixed playback, and preemptive audio focus are configurable.
- 🔌 **USB exclusive playback**:
  currently supports **UAC1.0** USB DAC devices, with device selection,
  sample-rate/bit-depth/buffer policies, compatibility toggles, and
  background-playback guidance. If playback startup,
  native transfer, or foreground/background transitions become unhealthy, the
  app tries to recover automatically and can fall back to Android system output.
- 💾 **Configurable streaming cache**:
  audio cache uses `SimpleCache + LRU`, defaults to **1 GB**, and supports
  cleanup for audio cache, image cache, download staging, share staging, and
  platform playlist cache, with grouped storage usage details.
- 🛰️ **Offline mode**:
  automatically detects network availability, disables online Explore and remote
  Home refreshes while offline, and uses cached images only for remote artwork.
  Local files, downloaded audio, playback cache, playlists, recent plays, and
  playback stats remain available.
- ⬇️ **In-app downloads and management**:
  supports multi-platform audio downloads, task progress, cancel/retry, and
  local management with lyrics, covers, metadata, and audio tags. Default
  download concurrency is **6**, configurable up to **8**. Download queues are
  persisted so unfinished work can recover after restart, while complete local
  files can settle directly as finished.
- 📁 **Migratable download directory**:
  downloads default to the app-managed directory, but can be moved to a custom
  SAF directory. Existing downloads are migrated when switching directories.
  Custom filename templates are also supported. For performance, avoid moving
  to SAF unless an external directory is actually needed.
- 🎵 **Local audio import and scanning**:
  supports system `VIEW / SEND / SEND_MULTIPLE` for `audio/*`, device music
  scanning, authorized-folder scanning, and nearby sidecar lyrics/covers.
  Large scans can show a quick preview first, then continue background metadata hydration.
- 👤 **Local artist grouping and detail pages**:
  local songs are grouped by display artist automatically, including common
  `feat.`, `with`, Chinese conjunction, punctuation, and slash-separated artist
  forms. Local artist pages support play-all, multi-select, playlist export,
  and batch downloads for online-source songs.
- 🩷 **Local playlists and favorites**:
  built-in "My Favorite Music" and "Local Files" system playlists, plus user
  playlists with create/rename/delete/reorder/add-song support. "My Favorite
  Music" can sync recognizable songs to NetEase Liked Songs.
- 🧑‍🎤 **NetEase artist pages**:
  NetEase songs can open artist pages with artist metadata, paged songs/albums,
  and follow/unfollow support. Followed artists appear in the Library Favorites
  artist category.
- 🧺 **NetEase playlist detail cache**:
  playlist detail pages cache headers and track lists. Reopening a playlist can
  show local data first, and network or parse failures can fall back to the last
  successful load.
- ☁️ **GitHub / WebDAV sync**:
  optional sync for local playlists, favorite playlists, recent plays, playback
  stats, and deletion records through `WorkManager`, stored in the user's own
  remote.
- 📊 **Playback stats**:
  records play count, accumulated listen time, first/last played time, and daily
  stat buckets by stable track identity. Day/week/month/year/all-time views are
  available, and stats can be synced through GitHub/WebDAV when configured.
- 📶 **Traffic stats and download risk prompts**:
  tracks playback/download bytes, Wi-Fi/mobile/roaming distribution, and cache
  hits, and can warn before downloads on mobile data or roaming.
- ♻️ **Backup and restore**:
  playlist JSON import/export, plus full config import/export for settings,
  language, platform auth, GitHub/WebDAV config, and Listen Together settings.
- 🎧 **Listen Together**:
  create or join rooms, sync playback state over WebSocket, support host/listener
  permissions, member-control toggles, auto-pause on member changes,
  repeat/shuffle mode sync, optional sharing of controller-resolved stream URLs,
  invite links, deep links, custom server URLs, and host-offline detection.
- 🌈 **Personalization and themes**:
  auto/light/dark mode, dynamic color, seed colors, theme styles, UI scaling,
  custom background image, haptic feedback, lyric font size, lyric blur,
  default start destination, and Home card toggles.
- ✨ **Now Playing visuals and lyrics**:
  `RuntimeShader` / GLSL fluid background, audio-reactive dynamic background,
  cover blur background, Apple Music-style lyrics, advanced lyrics, word-timed
  lyrics, translated lyrics, lyric offset, phonetic display, long-press lyric
  sharing, lyric card generation, lyric editing, font scaling, lyric-aware
  haptics, and a full Lyrics page. RuntimeShader animation is enabled on Android
  13+, while cover/advanced blur requires Android 12+; older versions fall back.
- 👆 **Mini Player gestures**:
  the bottom Mini Player supports horizontal swipe for previous/next while
  keeping tap-to-expand and play/pause controls.
- 🪟 **Floating and status-bar lyrics**:
  system overlay lyrics with customizable color, outline, font size, position,
  alignment, and translation display, plus Meizu status-bar lyrics for select
  devices, SuperLyric output, and auto-hide while the app is foregrounded.
- 🔌 **External lyrics/device integration**:
  Lyricon integration, SuperLyric, external Bluetooth lyrics, pause on Bluetooth
  disconnect, and USB exclusive playback toggles. The external lyrics path
  receives the current song, playback state, position, word-level lyrics,
  and translations.
- 🛠️ **Developer mode and debug tools**:
  tap the version number **7 times** to reveal the `Debug` tab, including
  YouTube / Bili / NetEase / Search / Listen Together probes, log viewer, and
  crash log viewer.
- 🧾 **Friendlier sign-in and logging**:
  NetEase and Bilibili support QR login with web-login fallback. Persistent file
  logging can also be enabled outside developer mode for hard-to-reproduce bugs.
- 🛟 **Safe mode and crash logs**:
  if the previous startup hit a JVM/native crash or system ANR, the app can boot
  directly into safe mode so you can inspect or export the log and selectively
  clear settings or auth data.

---

## Platform Status

- **NetEase Cloud Music**:
  login, song search, curated playlists, albums, playlist/album list search,
  playback, downloads, lyrics, playback metadata completion, auto source switching
  for restricted playback, syncing local favorites to NetEase Liked Songs,
  artist pages, paged artist songs/albums, and artist follow support.
- **Bilibili**:
  web login, QR login, video search, created favorites, subscribed favorites,
  collections, favorite/collection list search, multi-part video-to-audio
  playback, and downloads.
  It is not a full video discovery or comments client.
- **YouTube Music**:
  login, home/library playlist browsing, playlist details, search, playback,
  downloads, PoToken, and JS Challenge support.
- **QQ Music**:
  currently used only for playback metadata and lyrics completion. Login,
  playback, and library data are not implemented.
- **Local audio**:
  external share/open import, device scanning, authorized-folder scanning,
  local file playback, local artist grouping, sharing, and local playlist management.

---

## Implementation Notes

### Build and versions

- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 28`
- Java 17 / Kotlin JVM 17
- NDK `27.0.12077973`
- CMake `3.22.1`
- Version name format: `<git_short_hash>.<MMddHHmm>`
- Release APK filename: `NeriPlayer-<versionName>[-abi].apk`
- Release builds are `arm64-v8a` by default. Use `-PbuildAllReleaseAbis=true`
  for multi-ABI output.

### Module layout

- `:app`: main Android application.
- `:ksp-annotations` / `:ksp-processor`: generated settings registration and metadata.
- `:accompanist-lyrics-core` / `:accompanist-lyrics-ui`: lyrics parsing and Compose lyrics UI submodules.
- `build-logic`: shared Gradle convention plugins.
- `buildSrc`: retained auxiliary Gradle build logic.
- `np-submodule/NeriPlayer-LTW`: Listen Together Cloudflare Workers server.
- `np-submodule/miuix`: vendored upstream Miuix source/docs tree, not part of the current app module graph.

### Entry point and navigation

- `MainActivity` is the only external entry point. It handles startup, notification
  permission, external audio imports, and `neriplayer://listen-together/join` links.
- If the previous launch ended with a JVM/native crash or system ANR, the app
  enters `Safe Mode` first and exposes only recovery and export actions.
- The main UI is **Compose NavHost + dynamic bottom bar**:
  `Home / Explore / Library / Settings` are the primary tabs.
- `Home` is displayed dynamically based on available Home cards. `Debug` appears
  only after enabling developer mode.
- `Now Playing` is a full-screen layer above main navigation, with a persistent
  bottom `Mini Player`. The Mini Player supports horizontal swipe for previous/next.
- `Library` uses paged navigation for Local, Favorites, NetEase, YouTube Music,
  Bilibili, and the QQ Music placeholder. It also exposes Recent Plays and
  Playback Stats.
- Local Library has playlist/artist categories; Favorites has playlist/artist
  categories; NetEase has playlist/album categories.
- `LocalArtistDetailScreen` handles local artist pages with play-all,
  multi-select, playlist export, and batch downloads for online songs.
  `NeteaseArtistDetailScreen` handles NetEase artist songs/albums and follow state.
- Tablet and landscape layouts constrain content width and adjust bottom control
  areas on Now Playing, Lyrics, artist detail, and Settings pages to avoid overly
  wide content and scattered controls.

### Playback, cache, and service

- Playback is based on Media3 ExoPlayer and managed by `PlayerManager`.
- `AudioPlayerService` provides foreground playback, media notifications,
  MediaSession, and basic transport controls.
- Bilibili playback uses `ConditionalHttpDataSourceFactory` to append
  `Referer / User-Agent / Cookie`.
- YouTube Music playback includes Google Video Range support, seek refresh policy,
  and prefetching.
- NetEase playback automatically tries lower quality when the current quality is
  unavailable, and can switch to a matched Bilibili fallback source for
  restricted or preview-only tracks.
- Playback state is persisted periodically for queue and state recovery.
- Player code is split by responsibility across `playback/`, `url/`, `resolver/`,
  `service/`, `effects/`, `lifecycle/`, `watchdog/`, and `usb/`. Shared song
  models live under `data/model/`; legacy packages only retain a small set of
  compatibility aliases and should not be used for new code.
- Sleep timer, fade-in/fade-out, crossfade-next, and playback mode recovery are
  handled in the player layer.
- Preemptive audio focus, mixed playback, pause on Bluetooth disconnect, and
  USB exclusive playback are stored in playback preference snapshots so they are
  available early in player startup.
- USB exclusive playback currently supports **UAC1.0** devices, with device
  selection, sample-rate/bit-depth/buffer policies, compatibility toggles, and
  background buffer tuning. To reduce stuck
  states, the player layer also includes startup watchdogs, foreground/background
  health audits, keep-alive checks, native transfer recovery, and system-output fallback.

### Search and data sources

- **UI search**:
  `Explore` integrates NetEase, Bilibili, and YouTube Music as separate sources.
- **Metadata completion**:
  the playback screen uses `SearchManager` with NetEase and QQ Music for cover,
  lyrics, and track metadata.
- **Lyrics**:
  besides platform lyrics, LRCLIB is available as an external lyrics client.
  The player supports original lyrics, translated lyrics, phonetic lyrics,
  word timing, lyric sharing, and manual editing.
- **Lyricon integration**:
  `LyriconManager` outputs the current song, playback state, position,
  word-level lyrics, and translated lyrics to Lyricon and SuperLyric.
  Status-bar lyrics depend on vendor support and currently target select devices.
- **Artist entry points**:
  NetEase search, Home, playlist/album detail pages, and Now Playing try to keep
  `neteaseArtists` metadata so the UI can open NetEase artist detail pages.
- **NetEase playlist cache**:
  `NeteasePlaylistCacheRepository` caches playlist headers, tracks, recent-track
  signatures, and save time. `NeteaseCollectionDetailViewModel` can publish cached
  data before refreshing the network and reuse the cache when the signature is
  unchanged or a network/parse failure occurs.

### Local data and security

- General settings use `DataStore`. KSP generates setting keys, backup allowlists,
  and settings UI metadata.
- Theme mode is represented by `ThemeMode`, with light, dark, and Auto
  follow-system behavior.
- Platform cookies, YouTube auth data, GitHub tokens, and WebDAV passwords are
  stored locally with `Android Keystore + EncryptedSharedPreferences`.
- Play history, playback stats, playlists, favorite snapshots, and mappings are
  persisted through local files.
- Local playlists are stored as JSON with atomic temp-file writes.
- GitHub/WebDAV sync uses a locally generated UUID as the device identifier,
  not `ANDROID_ID`.

### Downloads, local import, and backups

- Downloads use a shared `OkHttpClient`, not the system `DownloadManager`.
- Default download concurrency is **6**, configurable up to **8** in Settings.
- Downloads are first written into `cache/download_staging` working files, then
  committed into the app-managed directory or a user-selected SAF directory.
  Audio metadata is prepared before commit, and lyrics, covers, `.npmeta.json`,
  and audio tags are written after the audio file is finalized.
- `DownloadTaskStore` persists queued work and task state. On startup,
  `GlobalDownloadManager` waits for active/queued work to settle before restoring
  unfinished tasks, so stale queues and new requests do not overwrite each other.
- If completed audio can be found quickly through the download index or cached
  snapshot, the task is settled as complete without re-fetching the stream or
  repeatedly probing SAF storage.
- Downloads support **automatic resume**, but the strategy depends on transport type:
  - **Direct downloads** resume through `Range: bytes=<offset>-`
  - **Chunked range downloads** resume by byte offset, mainly for YouTube flows
    that require explicit range requests
  - **HLS downloads** resume from a saved segment index plus downloaded byte count
    through a `.hls.json` checkpoint
- Each working file also stores `.resume.json` metadata so unfinished downloads
  can be reconstructed after app restart. `GlobalDownloadManager` scans and
  restores resumable downloads on startup.
- Transient network failures try to keep partial data. Downloads that enter
  `WAITING_NETWORK` because Wi-Fi was lost also keep their working files and can
  continue after network recovery or user confirmation.
- Manual cancellation is different from pause/resume: it cleans up working files
  and rolls back partially committed audio/sidecar artifacts.
- Download indexes keep snapshot caches and sidecar references to reduce SAF
  directory walks. Android SAF access is still much slower than the app-private
  directory, so custom directories are recommended only when they are really needed.
- `StorageUsageAnalyzer` groups storage into cleanable cache, downloaded content,
  diagnostics, and app data. Cache cleanup removes regenerable cache/staging files,
  not user-saved downloaded songs.
- `LocalAudioImportManager` imports external audio, scans device music, and copies
  nearby `lrc/txt` lyrics and `cover/folder/front` images.
- Local scan previews can filter to tracks that already have metadata. After a
  local playlist is created or enriched, the app can continue hydrating titles,
  artists, albums, and covers in the background while preserving edited local metadata.
- Download "metadata post-processing" can be disabled separately. When disabled,
  NeriPlayer still keeps management metadata, but stops writing tags, lyrics,
  and covers back into the audio file itself.
- `BackupManager` supports playlist JSON export/import and diff analysis.
- `ConfigFileManager` supports full config export/import for migration.

For implementation details, see [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md).

---

## Listen Together Deployment

NeriPlayer includes a built-in "Listen Together" feature. You can deploy your
own server or use a server deployed by others.

Server source and deployment entry points:

- `np-submodule/NeriPlayer-LTW` inside this repository
- Public deployment template:
  [TheSmallHanCat/NeriPlayer-LTW](https://github.com/TheSmallHanCat/NeriPlayer-LTW)

The server is based on **Cloudflare Workers** and **Durable Objects**, using
WebSocket for real-time sync.

### Deploy to Cloudflare Workers

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/TheSmallHanCat/NeriPlayer-LTW)

The app can configure a Listen Together server URL, test availability, and reset
the local Listen Together identity from Settings.

Additional notes:

- Room IDs use a 6-character readable charset, and nicknames must be 1-24
  characters long using Chinese characters, letters, or digits
- For full protocol, event, and deployment details, see
  [np-submodule/NeriPlayer-LTW/README.md](./np-submodule/NeriPlayer-LTW/README.md)

---

## GitHub Sync

NeriPlayer can sync local metadata to **your own GitHub repository**.
When created from inside the app, the repository defaults to private, and
existing repositories are also supported.

Current sync targets:

- Local playlists
- Favorite playlists
- Recent plays
- Recent play deletion records
- Playback stats

### Technical details

- 🔒 **Local secure storage**: GitHub tokens are stored with
  `Android Keystore + EncryptedSharedPreferences`.
- 🔄 **Scheduling**: local mutations trigger a sync **after 5 seconds**; an
  **hourly** periodic sync is also scheduled.
- ⏱️ **Eventual consistency**: this is background two-way sync, not real-time push.
- 🌐 **Network requirement**: sync runs through `WorkManager` and requires a
  **validated network**.
- 🧩 **Conflict handling**: three-way merge handles playlists, favorites, history,
  deletion records, and playback stats. Newly added or restored songs carry
  membership tokens, so an older deletion only removes membership it actually
  observed instead of deleting restored content during the next sync.
- 🪶 **Data Saver**: uses `ProtoBuf + GZIP` as `backup.bin`; JSON is used when
  Data Saver is disabled.
- 📦 **Remote format**: a GitHub repository is not end-to-end encryption.
  You are responsible for protecting remote files.
- 🚫 **Sync boundary**: audio caches, downloaded files, local media files, cookies,
  and playback tokens are not uploaded.

### How to use

1. Open Backup & Sync in Settings.
2. Create a GitHub Personal Access Token with `repo` permission.
3. Validate the token, then either create the default private repository or use an existing one.
4. Enable automatic sync, or run a manual sync.

---

## WebDAV Sync

NeriPlayer also supports storing the same sync data in a WebDAV remote file.

- Sync targets are the same as GitHub Sync.
- Automatic sync and manual sync are supported.
- `WorkManager` handles delayed sync, periodic sync, network checks, and retries.
- WebDAV URL, username, and password are stored in local encrypted storage.
- The remote WebDAV file is not an end-to-end encrypted backup.

---

## Roadmap

### Exploring

These directions can change with maintainer bandwidth, platform availability,
and community feedback. They are not fixed-date commitments.

- [ ] Video playback
- [ ] Comment section
- [ ] More third-party platforms such as KuGou
- [ ] Fuller QQ Music account support, library data, and a more stable auth path

### Shipped recently

- [x] Listen Together repeat/shuffle sync, stable-track-key target validation,
  server clock-offset estimation, and authoritative stream recovery
- [x] Responsibility-based package splits for player, download storage, startup,
  lyric UI, and Listen Together
- [x] Versioned local-playlist display order with compatible migration of older
  GitHub/WebDAV sync data
- [x] USB exclusive device selection, quality policies, background-playback guidance, and layered automatic recovery
- [x] Fast local scan previews, background metadata hydration, and cover fallback resolution
- [x] Listen Together stream-link sharing toggles, asynchronous stream resolution, and more stable room sync
- [x] Long-press lyric selection, copy, song sharing, and lyric card generation
- [x] Phonetic lyric display and the lyric behavior sheet
- [x] Mini Player horizontal swipe for previous/next
- [x] NetEase playlist detail cache with network-failure fallback
- [x] Grouped storage usage analysis and expanded cache cleanup
- [x] Stale download queue recovery on startup, direct settlement for completed downloads, and SAF index performance improvements
- [x] Redesigned Library navigation with local/favorite/NetEase subcategories
- [x] Local artist grouping and local artist detail pages
- [x] NetEase artist details, paged songs/albums, and artist follow support
- [x] Day/week/month/year/all-time playback statistics views
- [x] NetEase and Bilibili QR login
- [x] Configurable download concurrency, recovery, and finalization reliability
- [x] Standardized lyric embedding setting
- [x] Auto theme mode, redesigned theme settings, and refined dark-mode detection
- [x] Lyric-seek haptic feedback
- [x] Preemptive audio focus setting
- [x] Floating lyrics, status-bar lyrics, and SuperLyric output
- [x] Clear cache
- [x] Add to playlist
- [x] Tablet / landscape Now Playing adaptation
- [x] Internationalization
- [x] NetEase Cloud Music adaptation
- [x] Bilibili adaptation
- [x] YouTube Music basic adaptation
- [x] YouTube Music search
- [x] WebDAV sync
- [x] Playback stats
- [x] Playback sound effects
- [x] NetEase auto source switch for restricted playback
- [x] Lyricon integration / external lyrics output
- [x] Safe mode and startup crash logs

> ⚠️ QQ Music is currently used mainly for playback metadata completion.
> Full account capabilities, library data, and a more stable auth flow are still in development.

---

Thank you for using NeriPlayer. Since the project has many features and user
environments can vary a lot, you may occasionally encounter behavior differences
or unexpected issues. If you run into any problems, feel free to submit feedback.
We will keep improving the project over time.

---

## Bug Report

- Before reporting, enable developer mode by tapping the **version number** 7 times in Settings.
- After developer mode is enabled, regular file logging is enabled. Crash logs are stored separately.
- Open [Issues](https://github.com/cwuom/NeriPlayer/issues) and include:
  OS version, device model, app version, reproduction steps, and key logs.
- Windows:
  ```bash
  adb logcat | findstr NeriPlayer
  ```
- Linux / macOS:
  ```bash
  adb logcat | grep NeriPlayer
  ```

---

## Known Issues

### Network

- Configure proxy rules carefully. Global proxying may cause abnormal responses
  from some third-party APIs.

### Limitations

- Downloads do not rely on the system download service. They support automatic
  resume and startup recovery, but they are not a system-level background
  downloader and do not sync downloaded media across devices.
- Manual download cancellation removes resume checkpoints and partial artifacts.
  Only network-policy pauses or recoverable errors keep resume state.
- Custom SAF download directories make files easier to access externally, but
  scanning, migration, and finalization are usually slower than the app-private directory.
- USB exclusive playback depends on a compatible **UAC1.0** USB DAC, the foreground service,
  and the system's background/battery policy. If playback is limited after the
  screen turns off, follow the in-app guidance to allow unrestricted background behavior.
- Phonetic lyric display depends on phonetic data from the platform or embedded
  lyrics. The toggle stays unavailable when the current lyric has no phonetics.
- Lyric cards are written to the app cache for system sharing and can be removed
  later through cache cleanup.
- Bilibili mainly provides video search, favorites, collections, and audio playback.
  It is not a full video discovery client.
- QQ Music is only a playback metadata/lyrics completion source.
- GitHub/WebDAV sync is not end-to-end encrypted. Full config export files may
  contain auth data and must be protected by the user.

---

## Privacy

- NeriPlayer does not provide its own public cloud media distribution service,
  and does not include ad SDKs, third-party analytics, or third-party crash SDKs.
- The project follows a decentralized data strategy: you choose and control the
  sync target, and personal media data is not gathered into a maintainer-operated
  central platform.
- Playback cache, downloads, local playlists, history, playback stats, settings,
  and auth data are stored locally by default.
- If you enable GitHub or WebDAV sync, only metadata such as playlists, favorites,
  history, and playback stats are synced.
- Audio caches, downloaded files, cookies, and playback tokens are not uploaded
  to the developers.
- For account safety, the app does not write local playback history or playback
  stats back to third-party music platforms, because that kind of reporting may
  be misclassified by platform risk-control systems.
- Full config export files contain settings, auth data, and sync configuration.
  They are intended for personal migration and should not be shared publicly.
- Android system cloud backup / device transfer is disabled by default.
- Third-party platform logs and risk-control behavior are governed by the
  corresponding platforms' privacy policies.

---

## Reference

<table>
<tr>
  <td><a href="https://github.com/chaunsin/netease-cloud-music">netease-cloud-music</a></td>
  <td>✨ NetEase Cloud Music Golang implementation 🎵</td>
</tr>
<tr>
  <td><a href="https://github.com/SocialSisterYi/bilibili-API-collect">bilibili-API-collect</a></td>
  <td>Bilibili API collection and notes</td>
</tr>
<tr>
  <td><a href="https://github.com/yt-dlp/ejs">ejs</a></td>
  <td>External JavaScript for yt-dlp supporting many runtimes</td>
</tr>
<tr>
  <td><a href="https://github.com/6xingyv/accompanist-lyrics-core">accompanist-lyrics-core</a></td>
  <td>A lyrics parsing, converting, exporting library for Kotlin</td>
</tr>
<tr>
  <td><a href="https://github.com/6xingyv/accompanist-lyrics-ui">accompanist-lyrics-ui</a></td>
  <td>The state-of-the-art karaoke lyrics composable</td>
</tr>
<tr>
  <td><a href="https://github.com/ReChronoRain/HyperCeiler">HyperCeiler</a></td>
  <td>HyperOS enhancement module - Make HyperOS Great Again!</td>
</tr>
</table>

---

## Update Cycle

- The project is under active iteration. Releases are usually published manually
  after a batch of features lands.
- Core playback, local data, sync, and recovery paths are maintained first.
- Third-party platform support can be affected by platform policy changes.
  Issues, PRs, and reproducible logs are welcome.

---

## Support

- Due to the nature of this project, donations are not accepted.
- You can support the project by submitting Issues, PRs, or sharing your experience.

---

## License

NeriPlayer is released under **GPL-3.0**.

This means:

- ✅ You can freely use, modify, and distribute this software.
- ⚠️ Modified distributions must remain open source under GPL-3.0.
- 📚 See [LICENSE](./LICENSE) for details.

---

# Contributing to NeriPlayer

Before contributing, please read [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md).

---

<p align="center">
  <img src="https://moe-counter.lxchapu.com/:neriplayer?theme=moebooru" alt="Moe Counter">
  <br/>
  <a href="https://starchart.cc/cwuom/NeriPlayer">
    <img src="https://starchart.cc/cwuom/NeriPlayer.svg" alt="Star History Chart">
  </a>
</p>
