[English](./README_EN.md) | [中文](./README.md)

<h1 align="center">NeriPlayer</h1>

<div align="center">

<h3>✨ Native Android Multi-Source Audio Player 🎵</h3>

<p>
  <a href="https://t.me/ouom_pub">
    <img alt="Join" src="https://img.shields.io/badge/Telegram-@ouom__pub-blue" />
  </a>
</p>

<p>
  <a href="https://t.me/neriplayer_ci">
    <img alt="ci_builds" src="https://img.shields.io/badge/CI_Builds-@neriplayer__ci-orange" />
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
focused on multi-source exploration, online playback, and local control.
</p>

🚧 <strong>Work in progress</strong>

<a href="https://trendshift.io/repositories/23906" target="_blank"><img src="https://trendshift.io/api/badge/repositories/23906" alt="cwuom%2FNeriPlayer | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

</div>

> [!WARNING]
> This project is for learning and research purposes only. Do not use it for illegal purposes.

---

> [!NOTE]
> NeriPlayer does not provide a public cloud music library or media distribution service.
> Online audio capabilities depend on your authorization on third-party platforms.
> VIP or restricted content still follows the original platform rules.

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
  be synced to your own GitHub private repository or WebDAV remote file.
- **Single Activity + Compose**: `MainActivity` is the only external entry point.
  The UI is organized by Compose `NavHost`, a dynamic bottom bar, Mini Player,
  and the Now Playing overlay.
- **Startup onboarding**: first launch follows
  `Loading -> Disclaimer -> Onboarding -> Main`, covering language, platform
  accounts, sync, and personalization after the disclaimer.

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
- 🔍 **Layered search**:
  `Explore` searches NetEase / Bilibili / YouTube Music separately.
  Playback metadata completion uses NetEase / QQ Music and integrates LRCLIB
  as an external lyrics source.
- 🧠 **Media3 playback core**:
  `PlayerManager` handles stream resolution, queue state, shuffle/repeat,
  persistence, failure retry, YouTube prefetching, and platform-specific request policies.
- 🔁 **NetEase auto source switch**:
  when a NetEase song is unavailable, has no playable URL, or only returns a
  preview clip, the player first tries lower quality and can then match a
  Bilibili fallback source by title, artist, and duration.
- 🎚️ **Playback sound controls**:
  Now Playing includes speed, pitch, loudness enhancer, equalizer presets, and
  manual EQ bands.
- 💾 **Configurable streaming cache**:
  audio cache uses `SimpleCache + LRU`, defaults to **1 GB**, and supports
  separate audio/image cache cleanup.
- ⬇️ **In-app downloads and management**:
  supports multi-platform audio downloads, task progress, cancel/retry, and
  local management with lyrics, covers, metadata, and audio tags.
- 📁 **Migratable download directory**:
  downloads default to the app-managed directory, but can be moved to a custom
  SAF directory. Existing downloads are migrated when switching directories.
  Custom filename templates are also supported.
- 🎵 **Local audio import and scanning**:
  supports system `VIEW / SEND / SEND_MULTIPLE` for `audio/*`, device music
  scanning, and nearby sidecar lyrics/covers.
- 🩷 **Local playlists and favorites**:
  built-in "My Favorite Music" and "Local Files" system playlists, plus user
  playlists with create/rename/delete/reorder/add-song support. "My Favorite
  Music" can sync recognizable songs to NetEase Liked Songs.
- ☁️ **GitHub / WebDAV sync**:
  optional sync for local playlists, favorite playlists, recent plays, playback
  stats, and deletion records through `WorkManager`.
- ♻️ **Backup and restore**:
  playlist JSON import/export, plus full config import/export for settings,
  language, platform auth, GitHub/WebDAV config, and Listen Together settings.
- 🎧 **Listen Together**:
  create or join rooms, sync playback state over WebSocket, support host/listener
  permissions, invite links, deep links, custom server URLs, and host-offline detection.
- 🌈 **Personalization and themes**:
  light/dark/follow-system mode, dynamic color, seed colors, theme styles,
  UI scaling, custom background image, lyric font size, and Home card toggles.
- ✨ **Now Playing visuals and lyrics**:
  audio-reactive dynamic background, cover blur background, advanced lyrics,
  word-timed lyrics, translated lyrics, lyric offset, lyric editing, font scaling,
  and a full Lyrics page.
- 🔌 **External lyrics/device integration**:
  Lyricon integration, external Bluetooth lyrics, pause on Bluetooth disconnect,
  and USB exclusive playback toggles. Lyricon receives the current song,
  playback state, position, word-level lyrics, and translations.
- 🛠️ **Developer mode and debug tools**:
  tap the version number **7 times** to reveal the `Debug` tab, including
  YouTube / Bili / NetEase / Search / Listen Together probes, log viewer, and
  crash log viewer.

---

## Platform Status

- **NetEase Cloud Music**:
  login, song search, curated playlists, albums, playlist/album list search,
  playback, downloads, lyrics, playback metadata completion, auto source switching
  for restricted playback, plus syncing local favorites to NetEase Liked Songs.
- **Bilibili**:
  login, video search, favorites, collections, favorite/collection list search,
  multi-part video-to-audio playback, and downloads.
  It is not a full video discovery or comments client.
- **YouTube Music**:
  login, home/playlist browsing, playlist details, search, playback, downloads,
  PoToken, and JS Challenge support.
- **QQ Music**:
  currently used only for playback metadata and lyrics completion. Login,
  playback, and library data are not implemented.
- **Local audio**:
  external share/open import, device scanning, local file playback, sharing, and
  local playlist management.

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
- `np-submodule/NeriPlayer-LTW`: Listen Together Cloudflare Workers server.

### Entry point and navigation

- `MainActivity` is the only external entry point. It handles startup, notification
  permission, external audio imports, and `neriplayer://listen-together/join` links.
- The main UI is **Compose NavHost + dynamic bottom bar**:
  `Home / Explore / Library / Settings` are the primary tabs.
- `Home` is displayed dynamically based on available Home cards. `Debug` appears
  only after enabling developer mode.
- `Now Playing` is a full-screen layer above main navigation, with a persistent
  bottom `Mini Player`.
- Library also exposes Recent Plays and Playback Stats.

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
- Sleep timer, fade-in/fade-out, crossfade-next, and playback mode recovery are
  handled in the player layer.

### Search and data sources

- **UI search**:
  `Explore` integrates NetEase, Bilibili, and YouTube Music as separate sources.
- **Metadata completion**:
  the playback screen uses `SearchManager` with NetEase and QQ Music for cover,
  lyrics, and track metadata.
- **Lyrics**:
  besides platform lyrics, LRCLIB is available as an external lyrics client.
  The player supports original lyrics, translated lyrics, word timing, and manual editing.
- **Lyricon integration**:
  `LyriconManager` outputs the current song, playback state, position,
  word-level lyrics, and translated lyrics to Lyricon.

### Local data and security

- General settings use `DataStore`. KSP generates setting keys, backup allowlists,
  and settings UI metadata.
- Platform cookies, YouTube auth data, GitHub tokens, and WebDAV passwords are
  stored locally with `Android Keystore + EncryptedSharedPreferences`.
- Play history, playback stats, playlists, favorite snapshots, and mappings are
  persisted through local files.
- Local playlists are stored as JSON with atomic temp-file writes.
- GitHub/WebDAV sync uses a locally generated UUID as the device identifier,
  not `ANDROID_ID`.

### Downloads, local import, and backups

- Downloads use a shared `OkHttpClient`, not the system `DownloadManager`.
- Downloaded files can be stored in the app-managed directory or a user-selected
  SAF directory, with lyrics, covers, metadata, and audio tags.
- Download resume is not implemented.
- `LocalAudioImportManager` imports external audio, scans device music, and copies
  nearby `lrc/txt` lyrics and `cover/folder/front` images.
- `BackupManager` supports playlist JSON export/import and diff analysis.
- `ConfigFileManager` supports full config export/import for migration.

For implementation details, see [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md).

---

## Listen Together Deployment

NeriPlayer includes a built-in "Listen Together" feature. You can deploy your
own server or use a server deployed by others.

Server repository:
[TheSmallHanCat/NeriPlayer-LTW](https://github.com/TheSmallHanCat/NeriPlayer-LTW)
or this repository's `np-submodule/NeriPlayer-LTW` submodule.

The server is based on **Cloudflare Workers** and **Durable Objects**, using
WebSocket for real-time sync.

### Deploy to Cloudflare Workers

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/TheSmallHanCat/NeriPlayer-LTW)

The app can configure a Listen Together server URL, test availability, and reset
the local Listen Together identity from Settings.

---

## GitHub Sync

NeriPlayer can sync local metadata to **your own GitHub private repository**.

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
  deletion records, and playback stats.
- 🪶 **Data Saver**: uses `ProtoBuf + GZIP` as `backup.bin`; JSON is used when
  Data Saver is disabled.
- 📦 **Remote format**: a private GitHub repository is not end-to-end encryption.
  You are responsible for protecting remote files.
- 🚫 **Sync boundary**: audio caches, downloaded files, local media files, cookies,
  and playback tokens are not uploaded.

### How to use

1. Open Backup & Sync in Settings.
2. Create a GitHub Personal Access Token with `repo` permission.
3. Validate the token and configure the repository in the app.
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

- [ ] Video playback
- [ ] Comment section
- [ ] Floating lyrics
- [ ] Continuous extension for third-party platforms such as KuGou
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

> ⚠️ QQ Music is currently used mainly for playback metadata completion.
> Full account capabilities, library data, and a more stable auth flow are still in development.

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

- Downloads do not rely on the system download service and do not support resume.
- Bilibili mainly provides video search, favorites, collections, and audio playback. It is not a
  full video discovery client.
- QQ Music is only a playback metadata/lyrics completion source.
- GitHub/WebDAV sync is not end-to-end encrypted. Full config export files may
  contain auth data and must be protected by the user.

---

## Privacy

- NeriPlayer does not provide its own public cloud media distribution service,
  and does not include ad SDKs, third-party analytics, or third-party crash SDKs.
- Playback cache, downloads, local playlists, history, playback stats, settings,
  and auth data are stored locally by default.
- If you enable GitHub or WebDAV sync, only metadata such as playlists, favorites,
  history, and playback stats are synced.
- Audio caches, downloaded files, cookies, and playback tokens are not uploaded
  to the developers.
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

- We maintain only core features. Community contributions are welcome for other capabilities.
- Development may pause for special reasons.
- PRs and feedback are welcome.

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
