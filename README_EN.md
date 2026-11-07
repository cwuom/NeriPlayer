[English](./README_EN.md) | [中文](./README.md)

<h1 align="center">NeriPlayer</h1>

<div align="center">

<h3>✨ A native Android audio player uniting multi-source streaming, local libraries, self-hosted servers (preview), and word-synced lyrics 🎵</h3>

<p><strong>🖥️ Looking for the PC version? <a href="https://github.com/cwuom/NeriPlayer-Desktop">Visit the NeriPlayer Desktop repository →</a></strong></p>

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
  <img src="icon/readme-banner.svg" width="100%" alt="NeriPlayer — Modern Music Player" />
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
> **Self-hosted music server support is in preview and has not been sufficiently tested.**

---

> [!WARNING]
> This project is for learning and research purposes only. Do not use it for illegal purposes.
> Access, play, or save content only when you have the rights, authorization, or platform permission to do so.
> This project does not provide media content, keys, paywall/DRM/region-bypass mechanisms, a public media proxy, or media redistribution.
>
> NeriPlayer does not provide a public cloud music library or media distribution service.
> Online audio capabilities depend on your authorization on third-party platforms.
> VIP or restricted content still follows the original platform rules.
> YouTube Music references in this document describe account sessions, playback compatibility,
> and failure recovery only. They do not describe bypassing platform restrictions,
> copying protected content, or redistributing media.
>
> This project and its maintainer do not accept any form of sponsorship, donation, or commercial funding.

---

## Start here

| Quick Links | Description |
| :--- | :--- |
| [Getting Started](#getting-started) | Download APKs, grab CI builds, or build from source |
| [Key Features](#key-features) & [Platform Status](#platform-status) | Explore playback, audio effects, lyrics, local management, and platform boundaries |
| [Connecting a music server](#connecting-a-music-server) | Connect to a self-hosted Navidrome / OpenSubsonic library (Preview) |
| [Listen Together Deployment](#listen-together-deployment) | Self-host a real-time synchronized listening room on Cloudflare Workers |
| [docs/implementation_EN.md](./docs/implementation_EN.md) | Architecture, module boundaries, startup flow, and test guardrails |
| [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md) | Development setup, pull request workflow, and contribution guidelines |

| Dimension | Highlights |
| :--- | :--- |
| **Multi-Source & Self-Hosted** | NetEase Cloud Music, Bilibili, YouTube Music, local audio, and Navidrome / OpenSubsonic (Preview) in one shared queue |
| **Rich Lyrics** | Line- and word-synced LRC / YRC / TTML, Bilibili CC subtitles, translation, transliteration, multi-source matching, plus floating, status-bar, Bluetooth/car, and system lyric outputs |
| **Audio & Visuals** | Media3 engine, self-built DSP, 10-band EQ, AutoEQ, 32-bit output, USB exclusive mode; Material You and Clean themes, fluid background, home widgets, and launcher shortcuts |
| **Local-First & User-Owned Sync** | Cache, downloads, playlists, history, stats, and settings live locally by default; optional two-way metadata sync with your own GitHub repository or WebDAV remote |
| **Built-In Recovery** | Offline mode, safe mode, JVM/Native crash logs, ANR traces, and debug probes |

## About

NeriPlayer is a native Android audio player built with **Jetpack Compose + Media3**. It brings **NetEase Cloud Music**, **Bilibili**, **YouTube Music**, **self-hosted music servers**, and **local audio** into a unified playback and library experience. Local playback needs no third-party platform account; online account features use your authorization on the corresponding platform.

- **Account authorization**: online features that require sign-in use your account on the corresponding platform.
- **Self-hosted music (Preview)**: connect to Navidrome / OpenSubsonic under Library → Music servers to browse and search albums, songs, and artists with the existing player, artwork, lyrics, local favorites, playlists, and original-file offline downloads (see [Connecting a music server](#connecting-a-music-server)).
- **Local-first**: playback cache, downloads, playlists, history, settings, and auth data are stored locally on the device by default.
- **Optional sync**: playlists, favorites, recent plays, and playback stats can be synced two-way with your own GitHub repository (creating a new repository in-app defaults to private, or you can connect an existing public or private repository) or a WebDAV remote file.
- **Decentralized and account-safety first**: synced data is written only to storage you control, never to a maintainer-operated server. Because third-party music platforms use risk-control and behavior-sampling systems where uploading local playback history may trigger abnormal login or account restrictions, NeriPlayer never uploads local playback history or stats back to those platforms.

> 💡 **How the remote features differ**: **Music servers** stream your own Navidrome / OpenSubsonic library; **Listen Together** syncs real-time room playback state across devices; **GitHub / WebDAV sync** performs two-way sync of local playlists, history, and stats metadata (without uploading audio files or migrating music server profiles and credentials). Each works independently.

Architecture, startup flow, and test guardrails live in [docs/implementation_EN.md](./docs/implementation_EN.md) and [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md).

### Connecting a music server

> [!WARNING]
> Self-hosted music server support is currently in preview with limited testing. Compatibility and stability across different servers, libraries, and network environments still need verification. Server write-back, Listen Together, and profile migration are not wired up yet.

1. Under Settings → Third-party platform sign-in → Music servers, enter a name, address, username, and password. Save after the connection check succeeds.
2. Use the server's base address, such as `https://music.example.com:4533/navidrome/`. Ports and reverse-proxy subpaths are supported; omit `/app/` and `/rest/`.
3. Open Library → Music servers, select a profile, and browse or search albums, songs, and artists. Open an artist to browse their albums. Switching categories keeps the query; searching inside an album filters only that album.
4. Play a song, or add it to favorites, the queue, or a local playlist. Structured server lyrics are preferred, with the standard lyrics endpoint as a fallback; decoding and seeking depend on the server and device.
5. Long-press a song or select multiple songs and choose Download to local. When the server permits downloads, files keep their original format and use the existing download manager for progress and retries. Completed files appear under Local music → Downloaded and remain playable offline or after removing the server profile.

| Mechanism | Behavior & Limitations |
| :--- | :--- |
| **Directory Cache & Refresh** | Opening a profile automatically loads and caches all three complete metadata directories (albums, songs, artists). Searches use the local directories and reuse the cache for 24 hours; refresh manually after library changes. Failed refreshes keep the previous complete directory. |
| **Media & Artwork Loading** | Directory caches store metadata only. Artwork loads on demand, and audio is fetched during playback (including next-song prefetch) or explicit downloads; browse caches and next-song prefetch are distinct from full offline downloads. Artist images use server artwork first, falling back to the last song cover available for that artist. |
| **Credentials & Editing** | Profiles and passwords stay in encrypted local storage. Editing changes only the name and password; changing the address or username requires creating a new profile. Existing playlist entries do not migrate automatically, and ordinary metadata sync does not migrate server credentials. |
| **Cross-Device Artwork Sync** | Server artwork syncs as a real endpoint URL without authentication parameters (no passwords or access tokens are included; private servers still require sign-in). Another Android device can load these covers with its own account after adding the same server address and username. Desktop clients retain the URLs, while desktop artwork loading awaits music-server integration. |

See the [API contract](docs/music-server-api.md) for protocol and client behavior (Chinese).

---

## Key Features

### 🎧 Playback and audio quality

- **Multi-source and local playback**: NetEase, Bilibili, YouTube Music, and local audio share one queue.
- **Playback fallback**: NetEase downgrades quality first; with the Bilibili fallback enabled, candidates match by title, artist, and duration, and repeated failures skip automatically.
- **Bilibili source binding**: bind a song, album, or playlist to a multi-part Bilibili video by in-app search or a pasted share link; playback and the source badge follow the binding, which syncs through GitHub / WebDAV.
- **YouTube playback compatibility**: valid identity cookies are preserved and rotated; when a cached `player.js` or playback candidate is rejected, the app falls back to EJS/HLS instead of retrying the same candidate.
- **Audio effects**: a self-built DSP with built-in presets, a 10-band equalizer, AutoEQ import, loudness leveling, and 32-bit high-resolution output — fully bypassed when off.
- **USB exclusive output**: UAC1.0 and UAC2.0 Type I PCM DACs with bit-perfect output; responding to USB attach events can be disabled separately.
- **Background keep-alive**: an optional floating window (invisible 1-pixel) reduces the chance of the system freezing background playback.
- **Playback behavior**: fade in/out, crossfade on track change, pause on Bluetooth disconnect, mix with other apps, and audio focus policy, with resume position and play mode restored.
- **Bilibili skipping**: SponsorBlock auto-skip and custom ranges, off by default, disabled during Listen Together.

### 🎤 Lyrics

- **Line and word level**: LRC / YRC / TTML with highlighting, translation, transliteration, offset, tap-to-seek, and full-screen lyrics.
- **CC subtitle lyrics**: Bilibili songs can use the video's own CC subtitles, with track selection and auto-generated fallback.
- **Sources and editing**: automatic source preference (NetEase / Kugou / QQ Music / LRCLIB / AMLL) with manual matching and editing.
- **External output**: floating lyrics, status-bar lyrics, SuperLyric, Lyricon, Bluetooth lyrics, and car display; plus experimental ColorOS lock-screen lyrics (requires Root or Shizuku, depending on ROM support).

### 🗂️ Library and local management

- **Category browsing**: local, favorites, NetEase, YouTube Music, and Bilibili entries; YouTube can be disabled entirely, while Bilibili music can be turned off for the home sections only.
- **Home discovery**: recently used playlists, charts, new songs, daily picks, Private FM, curated playlists, and radar playlists; a refresh updates every section.
- **Search and link recognition**: per-platform search with paging on NetEase and Bilibili, plus pasting a share text with title or short link to resolve NetEase, Bilibili, and YouTube content.
- **Import and batch artwork**: share import and folder scanning, plus one-tap batch matching and embedding of lyrics and artwork.
- **Local artists and playlists**: aggregation by display artist, with sorting, block insertion, and undo.
- **Song encyclopedia**: genres, language, BPM, credits, comment previews, similar songs, and related playlists.

### ⬇️ Downloads, cache, and storage

- **In-app downloads**: multi-platform audio with resume and startup recovery, 6 concurrent by default (adjustable 1–8).
- **Relocatable directory**: SAF custom directory and filename templates, migrating existing downloads on switch.
- **Cache cleanup**: 1 GB audio cache cap by default; a 0–1000 track slider keeps recent plays while the current track stays protected.
- **Local import cache**: In Settings → Storage & Cache → Clear Cache, remove extracted covers with no remaining references and reset temporary scan indexes. Covers still used by history, statistics, or other records are kept. Covers used in the current app session are reconsidered after the next cold start.

### 🔄 Sync, stats, and backup

- **GitHub / WebDAV sync**: playlists, favorites, recent plays, deletion records, and playback stats synced with remotes you own.
- **Playback and traffic stats**: play counts and listening time per track identity with day/week/month/year views; traffic splits Wi-Fi / mobile / roaming.
- **Backup and restore**: playlist JSON import/export; configuration exports exclude login state by default, optionally encrypted with auth included.

### 🎨 Interface and personalization

- **Two UI styles**: Material You plus a preview **Clean** style, which can enable liquid glass.
- **Theme and display**: dynamic color, custom background, lyric font size, and UI scaling (0.6–1.2x on phones, 0.6–1.5x in car mode).
- **Fluid background**: a cover-colored `RuntimeShader` background with audio response (Android 13+).
- **Desktop and car**: three widget sizes, launcher shortcuts, and Bluetooth car metadata.
- **Large screens**: tablet landscape fixes a full lyrics column while phone landscape switches cover and lyrics.

### 👥 Listen Together

- Create or join a room with real-time WebSocket sync; the first join needs an invite key and reconnects use a member key.
- Host/listener roles, repeat and shuffle sync, clock-offset correction, and host offline recovery.
- Optional sharing of host playback candidates (at most 3); listeners resolve with their own quality policy first.

### 🛟 Stability and diagnostics

- **Offline mode**: detects network state and uses only local files and cache when offline.
- **Safe mode**: after a JVM / Native crash or system ANR, export logs from safe mode.
- **Developer mode**: tap the version number 7 times for debug probes and log viewers.

---

## Getting Started

### a. Download a Release build (recommended)

Go to [GitHub Releases](https://github.com/cwuom/NeriPlayer/releases) and download the APK matching your device architecture:

| APK Architecture (ABI) | Target Devices |
| :--- | :--- |
| `arm64-v8a` | Most modern phones and tablets (recommended) |
| `armeabi-v7a` | Older 32-bit ARM devices |
| `x86` / `x86_64` | Desktop emulators, Intel devices, or Chromebooks |

> [!IMPORTANT]
> The Release channel is not a strict stable channel. Builds are usually pushed manually after a batch of features is completed and may still contain unexposed issues.

### b. Download a CI build

1. Go to [GitHub Actions](https://github.com/cwuom/NeriPlayer/actions), download the Artifacts from the latest successful build, and extract them.
2. Or visit [NeriPlayer CI Builds](https://t.me/neriplayer_ci).

> The master CI artifact is `arm64-v8a` by default; the manual Release workflow builds multi-ABI APKs.

### c. Local build

1. Clone the repository and initialize submodules:
   ```bash
   git clone --recursive https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. Install JDK 17 and open the project with the latest stable Android Studio. Running
   `:playback:runtime` JVM tests or the full module gate also needs a JDK 21 that Gradle
   can discover; production bytecode still targets 17.
3. Build and install the Debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. On first launch, read the disclaimer and complete onboarding. The flow explains
   notification and local-music permissions and requests them only after you allow them.
5. For debugging tools, tap the **version number** 7 times in Settings.

> Debug builds are for testing only. Their performance and size do not represent Release builds.
> Debug builds use `moe.ouom.neriplayer.debug`, an orange launcher icon, and a `DEBUG` app-name suffix that also distinguishes themed icons. They can coexist with Release builds and keep separate app data.
> Invitations shared by Debug use `neriplayer-debug://`. Regular invitation links can still be pasted into the app to join.
> A Debug build warning appears after onboarding. Once confirmed, it stays dismissed across updates; clearing app data or reinstalling resets it.

To run from Android Studio, select the `app` run configuration and the `debug` variant
for `:app` in Build Variants. After changing the application ID configuration or
switching to a branch containing that change, run **Sync Project with Gradle Files**.
Rebuilding the APK alone does not refresh the IDE's cached launch package.

For release build and signing details, see
[CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md#release-build).

---

## Platform Status

| Source / Module | Supported Capabilities | Boundaries & Status |
| :--- | :--- | :--- |
| **NetEase Cloud Music** | Login, search, curated playlists, albums, playback, downloads, lyrics, metadata completion, no-permission playback fallback, syncing local favorites to "My Favorites", artist pages, song encyclopedia, and artist following; comments support hot/latest/recommended sorting, nested replies, copy, and — when signed in — like, post, and reply | Subject to platform rules and account permissions |
| **Bilibili** | Web and QR login, video search, created and subscribed favorites, collections, multi-part audio playback, and downloads; home adds Hot and Original music charts plus popular music videos; link recognition keeps the selected part and restores collection context from `season_id`; comments support hot/latest sorting, paged nested replies, copy, and signed-in interaction | Audio playback workflow rather than a full video feed |
| **YouTube Music** | Login, home/library playlist browsing, playlist detail, search, and playback compatibility | Content access remains subject to platform rules and account permissions |
| **Self-Hosted Music Server**<br>*(Navidrome / OpenSubsonic)* | Browse and search albums, songs, and artists; view artist albums; play with existing player, artwork, and lyrics; local favorites, playlist management, and original-file offline downloads | **Preview (limited testing)**; compatibility and stability across servers, libraries, and networks still need verification; server write-back, Listen Together, and profile migration are not wired up yet |
| **QQ Music** | Now Playing metadata and lyric completion | No login, playback, or library pages |
| **Local Audio** | Share/open import, device and folder scanning, playback, artist grouping, sharing, and playlist management | Local file playback and management |

---

## Implementation Notes

Build versions, module boundaries, and key implementation paths were split into a
dedicated document:

- [docs/implementation_EN.md](./docs/implementation_EN.md): build versions, the 14 owned
  libraries, entry points and navigation, playback and caching, search and data sources,
  local data and safety, downloads and backup.
- [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md#project-layout): the developer view of module
  boundaries, extension paths, tests, and submission rules.

---

## Listen Together Deployment

NeriPlayer ships a Listen Together feature. You can deploy your own server or use one
someone else deployed.

- In-repo server source: `np-submodule/NeriPlayer-LTW`
- Public deployment template: [TheSmallHanCat/NeriPlayer-LTW](https://github.com/TheSmallHanCat/NeriPlayer-LTW)

The server is built on **Cloudflare Workers** and **Durable Objects** and syncs in real
time over WebSocket.

### One-click deploy to Cloudflare Workers

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/TheSmallHanCat/NeriPlayer-LTW)

In the app, Settings lets you configure the server address, test availability, and reset
the local Listen Together identity. Room ids are fixed at 6 readable characters and
nicknames are 1-24 characters (currently Chinese, Latin letters, and digits). The first
join must use the key in the invite link, and local songs cannot create a room.
For the full protocol, event model, and deployment details, see
[np-submodule/NeriPlayer-LTW/README.md](./np-submodule/NeriPlayer-LTW/README.md).

---

## Sync

NeriPlayer can sync metadata to **your own** GitHub repository or a WebDAV remote file.
Repositories created in-app default to private; attaching an existing repository is also
supported.

Synced objects: local playlists, favorite playlists, recent plays, recent-play deletion
records, and playback stats.

### Usage

1. Open Backup & Sync in Settings.
2. For GitHub, create a Personal Access Token with the `repo` scope.
3. Validate the token, then create the default private repository or attach an existing one.
4. New installs use V4 directly. When the upgrade dialog appears, update every device that
   participates in sync first, then confirm and sync immediately to migrate the address.
5. Enable automatic sync, or tap Sync now.

### Behavior

- 🔒 **Credentials and transport**: the GitHub token and WebDAV credentials live in `Android Keystore + EncryptedSharedPreferences`; plain-HTTP WebDAV addresses warn before saving.
- 🔄 **Scheduling and merge**: a change syncs 5 seconds later, plus an hourly periodic sync; a three-way merge covers playlists, history, stats, and deletion records. This is background bidirectional sync, not real-time push.
- 📦 **Remote format**: V4 uploads ZSTD chunks incrementally and prefers strong-ETag conditional writes (Nutstore and similar use manifest fingerprint checks); legacy formats are read only when the new manifest is missing, and remotes are not end-to-end encrypted.

---

## Roadmap

These directions depend on maintainer capacity, platform availability, and community
feedback.

- [ ] Video playback
- [ ] Continued expansion of third-party playback, library pages, and account support
- [ ] More complete QQ Music account support, library data, and a more stable auth flow

Shipped work is tracked in [Releases](https://github.com/cwuom/NeriPlayer/releases) and
the commit history.

---

## Bug Report

- Enable developer mode first (tap the **version number** 7 times in Settings). It turns
  on plain file logging, and crash logs are written separately.
- Open an [issue](https://github.com/cwuom/NeriPlayer/issues) with the OS version, device
  model, app version, reproduction steps, and relevant logs.
- Filter logs on Windows: `adb logcat | findstr NeriPlayer`
- Filter logs on Linux / macOS: `adb logcat | grep NeriPlayer`

---

## Known Issues

- **Network**: Use split-tunneling rules when running a proxy; a global proxy can cause some third-party APIs to return unexpected data.
- **Downloads**: Managed in-app rather than through the system download service. Automatic resume and startup recovery are supported, subject to app background lifecycle limits. Manually canceling a task clears checkpoints and partial files; resume state is kept only for network-policy pauses or recoverable errors. A custom SAF directory makes files easier to access externally, but scanning, migration, and writes are slower than in the app's private directory.
- **USB exclusive output**: Requires a compatible DAC and an active foreground service; if playback stops with the screen off, lift battery and background restrictions as prompted in Settings. UAC2 asynchronous mode activates only when a single explicit feedback endpoint and a supported clock topology are detected. Implicit feedback and unverifiable topologies are currently unsupported; turn off USB exclusive mode to use system audio output. Host tests and four-ABI builds verify code; phone and DAC compatibility requires real-device checks.
- **Audio output**: Enabling 32-bit high-resolution output bypasses loudness leveling, channel balance, audio visualization, and in-app playback speed; turn the toggle off when you need those features.
- **Bluetooth car**: Because AVRCP lacks a dedicated lyric field, car lyrics are sent through the track title field; refresh behavior and artwork transfer depend on your phone OS and head unit implementation. Android Auto uses its standard in-car UI, and no standalone Android Automotive OS build is provided.
- **Lyrics**: Transliteration requires transliteration data in the platform response or embedded tags. Generated lyric cards live in the app cache directory and can be released by cache cleanup. ColorOS lock-screen lyrics are experimental, require vendor support plus Root/Shizuku, and should be re-checked after system updates.
- **Platform scope**: Bilibili focuses on search, favorites, collections, and audio playback rather than a full video discovery feed. QQ Music is used only for Now Playing metadata and lyric completion.
- **Sync**: Remote sync files are not end-to-end encrypted. Configuration exports exclude login state by default; if you choose to include auth credentials, set a password and keep it safe — the password is never stored inside the exported file and cannot be recovered if lost. Concurrent updates on the same branch report a conflict rather than force-overwriting remote commits.
- **Other**: Long-audio position memory never overrides an explicit start position and does not sync back to third-party platform history. Bilibili SponsorBlock queries a public API and remains disabled during Listen Together sessions.

---

## Privacy

- No public cloud media distribution service of its own, and no ad SDK, third-party
  analytics, or crash-reporting SDK.
- Data strategy is decentralized: sync targets are chosen and owned by the user, and
  personal media data is never collected into a maintainer-controlled platform.
- Cache, downloads, local playlists, history, playback stats, settings, and auth data are
  stored locally by default.
- Only metadata such as playlists, favorites, history, and playback stats is synced, and
  only when you enable GitHub or WebDAV sync.
- Audio cache, downloaded files, cookies, and playback tokens are never uploaded to the
  developer.
- Local playback history and playback stats are never written back to third-party music
  platforms, because platform risk control may misread such uploads.
- Configuration exports exclude login state by default; including it exports auth and sync
  credentials, which should be password-protected and kept private.
- Android system cloud backup and device transfer are disabled by default.
- Access logs and risk-control policies on third-party platforms are handled under those
  platforms' own privacy policies.

---

## Reference

- [netease-cloud-music](https://github.com/chaunsin/netease-cloud-music): NetEase Cloud Music implementation in Go
- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect): Bilibili API collection
- [ejs](https://github.com/yt-dlp/ejs): External JavaScript for yt-dlp supporting many runtimes
- [accompanist-lyrics-core](https://github.com/6xingyv/accompanist-lyrics-core): Lyrics parsing, converting, and exporting for Kotlin
- [accompanist-lyrics-ui](https://github.com/6xingyv/accompanist-lyrics-ui): A karaoke lyrics composable
- [LDDC](https://github.com/chenmozhijin/LDDC): Multi-platform lyric matching tool; reference for manual matching UX
- [Metrolist](https://github.com/MetrolistGroup/Metrolist): YouTube Music client for Android
- [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler): HyperOS enhancement module

---

## Contributing, Support, and License

- Read [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md) before contributing. The project is in
  continuous development; Releases are usually pushed manually in feature batches, and
  core playback, local data, sync, and recovery paths are maintained first.
- Donations are not accepted; issues, pull requests, and shared usage feedback are welcome.
- NeriPlayer is released under **GPL-3.0**: you may use, modify, and distribute it, and
  modified distributions must stay under GPL-3.0.
  The [native source license note](./modules/native/src/main/cpp/README.md) grants an
  alternative license with attribution conditions only for the NeriPlayer-owned native
  sources it lists; third-party sources and unlisted repository content are excluded.
  External native contributions do not enter that scope automatically through a pull
  request — only an explicit dual-license statement does. The Xiaomi Super Island lyric
  Shizuku/XMSF network-isolation adapter ports GPL-3.0 Capsulyric sources; see
  [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md) for attribution. Full terms are in
  [LICENSE](./LICENSE).

---

<p align="center">
  <img src="https://moe-counter.lxchapu.com/:neriplayer?theme=moebooru" alt="MoE Counter">
</p>

## Star History

<a href="https://www.star-history.com/?repos=cwuom%2Fneriplayer&type=timeline&legend=bottom-right">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=cwuom/neriplayer&type=timeline&theme=dark&legend=bottom-right" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=cwuom/neriplayer&type=timeline&legend=bottom-right" />
    <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=cwuom/neriplayer&type=timeline&legend=bottom-right" />
  </picture>
</a>
