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
> This project is for learning and research only. Do not use it for illegal purposes. Access, play, or save content only where you have the rights, authorization, or platform permission to do so.
>
> The project provides no media content, keys, or paywall / DRM / region-bypass mechanisms, and no public cloud library, media proxy, or redistribution service.
>
> Online features depend on your authorization on third-party platforms, and VIP or restricted content follows the original platform's rules. YouTube Music capabilities in this document cover account sessions, playback compatibility, and error recovery only.
>
> This project and its maintainer do not accept any sponsorship, donation, or commercial funding.

---

## Start here

| Quick Links | Description |
| :--- | :--- |
| [Getting Started](#getting-started) | Download APKs, grab CI builds, or build from source |
| [Key Features](#key-features) | Playback, audio effects, lyrics, and local management |
| [Platform Status](#platform-status) | What each platform supports |
| [Connecting a music server](#connecting-a-music-server) | Connect a self-hosted Navidrome / OpenSubsonic library (Preview) |
| [Listen Together Deployment](#listen-together-deployment) | Self-host a real-time listening room on Cloudflare Workers |
| [docs/implementation_EN.md](./docs/implementation_EN.md) | Architecture, module boundaries, startup flow, and tests |
| [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md) | Development setup, pull request workflow, and guidelines |

**Highlights**

| Dimension | Highlights |
| :--- | :--- |
| **Multi-Source & Self-Hosted** | NetEase Cloud Music, Bilibili, YouTube Music, local audio, and Navidrome / OpenSubsonic (Preview) in one shared queue |
| **Lyrics** | Line- and word-synced LRC / YRC / TTML, Bilibili CC subtitles, translation, transliteration, and multi-source matching; floating, status-bar, Bluetooth / car, and system outputs |
| **Audio & Visuals** | Media3 engine, self-built DSP, 10-band EQ, AutoEQ, 32-bit output, USB exclusive mode; Material You and Clean themes, fluid background, widgets, and launcher shortcuts |
| **Local-First & User-Owned Sync** | Data stays on the device by default; optional metadata sync to your own GitHub repository or WebDAV remote |
| **Built-In Recovery** | Offline mode, safe mode, JVM / Native crash logs, ANR traces, and debug probes |

## About

NeriPlayer is a native Android audio player built with **Jetpack Compose + Media3**. It brings **NetEase Cloud Music**, **Bilibili**, **YouTube Music**, **self-hosted music servers**, and **local audio** into one playback and library experience. Local playback needs no account; online features use your authorization on the corresponding platform.

- **Self-hosted music (Preview)**: connect Navidrome / OpenSubsonic under Library → Music servers to browse and search albums, songs, and artists, with the existing player, artwork, lyrics, local favorites, playlists, and original-file offline downloads (see [Connecting a music server](#connecting-a-music-server)).
- **Local-first**: cache, downloads, playlists, history, settings, and auth data are stored on the device.
- **Optional sync**: playlists, favorites, recent plays, and playback stats can sync to your own GitHub repository (private by default when created in-app) or a WebDAV remote.
- **User-owned data**: synced data goes only to storage you control, never through a maintainer-operated server.
- **Account safety**: to avoid triggering third-party risk control, NeriPlayer never uploads playback history or stats to those platforms.

> 💡 **Three independent features**
>
> - **Music servers**: connect your own Navidrome / OpenSubsonic library.
> - **Listen Together**: sync playback state across devices in real time.
> - **GitHub / WebDAV sync**: sync playlists, stats, and other metadata, without audio files or music server profiles and credentials.

Architecture, startup flow, and tests are covered in [docs/implementation_EN.md](./docs/implementation_EN.md) and [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md).

### Connecting a music server

> [!WARNING]
> Preview version with limited testing. Compatibility and stability vary by server, library, and network. Server write-back, Listen Together, and profile migration are not wired up yet.

1. Under Settings → Third-party platform sign-in → Music servers, enter a name, address, username, and password, then save once the connection check passes.
2. Use the server's base address, such as `https://music.example.com:4533/navidrome/`. Ports and reverse-proxy subpaths are fine; leave out `/app/` and `/rest/`.
3. Open Library → Music servers, pick a profile, and browse or search albums, songs, and artists. Open an artist to see their albums.
4. Switching categories keeps your query; searching inside an album filters only that album.
5. Play a song, or add it to favorites, the queue, or a local playlist.
6. Lyrics come from the server's structured lyrics first, then the standard lyrics endpoint.
7. Long-press a song or select several, then choose Download to local. If the server allows it, files keep their original format, with progress and retries in the download manager.
8. Finished downloads appear under Local music → Downloaded and stay playable offline or after you remove the profile.

**How it works**

- **Directory cache**: opening a profile loads and caches the full album, song, and artist directories. Searches use the local directories, and the cache is reused for 24 hours.
- **Manual refresh**: refresh after library changes; a failed refresh keeps the existing directories.
- **Media loading**: the cache holds metadata only. Artwork loads on demand and audio is fetched on playback or download.
- **Artist images**: server images first, otherwise the cover of the artist's last song that has one.
- **Credentials**: profiles and passwords live in encrypted local storage and are not migrated by sync.
- **Editing**: only the name and password can be changed. A new address or username needs a new profile, and existing playlist entries do not migrate.
- **Artwork sync**: covers sync as endpoint URLs without authentication parameters; private servers still require sign-in.
- **Multiple devices**: another Android device with the same address and username can load these covers. Desktop clients only keep the URLs for now.

See the [API contract](docs/music-server-api.md) for protocol and client behavior (Chinese).

---

## Key Features

### 🎧 Playback and audio quality

- **Multi-source and local playback**: NetEase Cloud Music, Bilibili, YouTube Music, and local audio share one queue.
- **Playback fallback**: NetEase lowers quality first; with the Bilibili fallback on, candidates are matched by title, artist, and duration, and repeated failures are skipped.
- **YouTube Music compatibility**: valid identity cookies are kept and rotated; if a cached `player.js` or playback candidate fails, playback falls back to EJS / HLS.
- **Audio effects**: self-built DSP with built-in presets, a 10-band equalizer, AutoEQ import, loudness leveling, and 32-bit high-resolution output. Everything is bypassed when off.
- **USB exclusive output**: bit-perfect output to UAC1.0 and UAC2.0 Type I PCM DACs; responding to USB attach events can be turned off.
- **Background playback**: music keeps playing through the foreground media service after you switch apps or swipe NeriPlayer out of Recents.
- **Floating-window keep-alive**: optional invisible 1-pixel window that makes it less likely for the system to freeze background playback.
- **Playback behavior**: fade in/out, crossfade, pause on Bluetooth disconnect, mixing with other apps, and audio focus policy. Resume position and play mode are restored on launch.
- **Bilibili skipping**: SponsorBlock auto-skip and custom ranges; off by default and inactive during Listen Together.

### 🎤 Lyrics

- **Line and word level**: LRC / YRC / TTML with highlighting, translation, transliteration, offset, tap-to-seek, and full-screen lyrics.
- **CC subtitle lyrics**: Bilibili songs can use the video's CC subtitles, with track selection and an auto-generated fallback.
- **Sources and editing**: automatic source preference (NetEase Cloud Music / Kugou / QQ Music / LRCLIB / AMLL), plus manual matching and editing.
- **External output**: floating lyrics, status-bar lyrics, SuperLyric, Lyricon, Bluetooth lyrics, and car display.
- **ColorOS lock-screen lyrics**: experimental; needs Root or Shizuku and depends on ROM support.

### 🗂️ Library and local management

- **Add to NetEase playlists**: while playing from a NetEase Cloud Music source, open the top-right three-dot menu, choose “Add to NetEase playlist”, and pick one of your playlists.
  - Requires sign-in and a network connection.
  - The entry is hidden when playback falls back to Bilibili or local audio.
- **Category browsing**: local, favorites, NetEase Cloud Music, YouTube Music, and Bilibili entries. YouTube Music can be disabled entirely, and Bilibili music can be hidden from the home page alone.
- **Home discovery**: recently used playlists, charts, new songs, daily picks, Private FM, curated playlists, and radar playlists; a refresh updates every section.
- **Search**: per-platform search, with paging on NetEase Cloud Music and Bilibili.
- **Link recognition**: paste a share text with a title or short link to resolve NetEase Cloud Music, Bilibili, and YouTube Music content.
- **Import and batch artwork**: share import and folder scanning, plus one-tap batch matching and embedding of lyrics and artwork for local songs.
- **Local artists and playlists**: grouped by display artist, with sorting, block insertion, and undo for deletions.
- **Song encyclopedia**: genres, language, BPM, credits, comment previews, similar songs, and related playlists.

### ⬇️ Downloads, cache, and storage

- **In-app downloads**: multi-platform audio with resume and startup recovery, 6 concurrent by default (adjustable 1–8).
- **Custom directory**: SAF directory and filename templates; existing downloads migrate when you switch.
- **Cache limit**: the audio cache is capped at 1 GB by default.
- **Keep recent plays**: a 0–1000 track slider keeps recently played songs; the current track is always protected.
- **Local import cache**: Settings → Storage & Cache → Clear Cache removes extracted covers with no remaining references and temporary scan indexes.
- **Cover retention**: covers still used by history, statistics, or other records are kept; covers used in the current session are reconsidered after the next cold start.

### 🔄 Sync, stats, and backup

- **GitHub / WebDAV sync**: playlists, favorites, recent plays, deletion records, and playback stats, stored on remotes you own.
- **Playback stats**: play counts and listening time per track, with day / week / month / year views.
- **Traffic stats**: split into Wi-Fi, mobile, and roaming.
- **Backup and restore**: playlist JSON import and export.
- **Configuration export**: excludes login state by default; optionally encrypted with auth included.

### 🎨 Interface and personalization

- **Two UI styles**: Material You and a preview **Clean** style, which supports liquid glass.
- **Theme and display**: dynamic color, custom background, lyric font size, and UI scaling (0.6–1.2x on phones, 0.6–1.5x in car mode).
- **Fluid background**: a cover-colored `RuntimeShader` background on the player page that reacts to audio (Android 13+).
- **Desktop and car**: three widget sizes, launcher shortcuts, and Bluetooth car metadata.
- **Large screens**: tablet landscape pins a full lyrics column; phone landscape switches between cover and lyrics.

### 👥 Listen Together

- **Rooms**: create or join a room with real-time WebSocket sync.
- **Joining**: the first join needs an invite key; reconnects use a member key.
- **Synced state**: host / listener roles, repeat and shuffle modes, clock-offset correction, and host offline recovery.
- **Playback candidates**: the host can share up to 3 candidates; listeners resolve them with their own quality policy first.

### 🛟 Stability and diagnostics

- **Offline mode**: detects network state and uses only local files and cache when offline.
- **Safe mode**: after a JVM / Native crash or system ANR, enter safe mode to export logs.
- **Developer mode**: tap the version number 7 times to enable debug probes and the log viewer.

---

## Getting Started

### a. Download a Release build (recommended)

Go to [GitHub Releases](https://github.com/cwuom/NeriPlayer/releases) and download the APK for your device architecture.

| APK Architecture (ABI) | Target Devices |
| :--- | :--- |
| `arm64-v8a` | Most modern phones and tablets (recommended) |
| `armeabi-v7a` | Older 32-bit ARM devices |
| `x86` / `x86_64` | Desktop emulators, Intel devices, or Chromebooks |

> [!IMPORTANT]
> Releases are pushed manually after a batch of features is finished and may contain undiscovered issues.

### b. Download a CI build

1. Go to [GitHub Actions](https://github.com/cwuom/NeriPlayer/actions), download the Artifacts from the latest successful build, and extract them.
2. Or visit [NeriPlayer CI Builds](https://t.me/neriplayer_ci).

> Master CI uploads an `arm64-v8a` APK by default; the manual Release workflow builds multi-ABI APKs.

### c. Local build

1. Clone the repository and initialize submodules:
   ```bash
   git clone --recursive https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. Install JDK 17 and open the project with the latest stable Android Studio.
3. Running `:playback:runtime` JVM tests or the full module gate also needs a JDK 21 that Gradle can discover (production bytecode still targets 17).
4. Build and install the Debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. On first launch, read the disclaimer and complete onboarding. Notification and local-music permissions are requested only after you allow them.
6. For debugging tools, tap the **version number** 7 times in Settings.

**About Debug builds**

- For testing only; performance and size differ from Release builds.
- They use `moe.ouom.neriplayer.debug`, an orange launcher icon, and a `DEBUG` name suffix, and can coexist with Release builds.
- Invitations shared from Debug use `neriplayer-debug://`; regular invitation links can still be pasted to join.
- A Debug build warning appears after onboarding. Once confirmed it stays dismissed; clearing app data or reinstalling shows it again.

To run from Android Studio, select the `app` run configuration and the `debug` variant for `:app` in Build Variants.
After changing the application ID configuration or switching branches, run **Sync Project with Gradle Files**. Otherwise the IDE may keep its cached launch package and start the installed Release build.

For release build and signing details, see [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md#release-build).

---

## Platform Status

### NetEase Cloud Music

- Login, search, curated playlists, albums, cloud storage, playback, downloads, lyrics, and metadata completion.
- Playback fallback when permission is missing; local favorites can sync to "My Favorites".
- Artist pages, song encyclopedia, and artist following.
- Comments support hot / latest / recommended sorting, nested replies, and copy; signed-in users can like, post, and reply.
- Subject to platform rules and account permissions.

### Bilibili

- Web and QR login, video search, created and subscribed favorites, and collections.
- Multi-part videos play and download as audio.
- Home shows Hot and Original music charts and popular music videos.
- Link recognition keeps the selected part and restores collection context from `season_id`.
- Comments support hot / latest sorting and nested replies; signed-in users can interact.
- Audio-focused; no full video feed.

### YouTube Music

- Login, home and library playlist browsing, playlist details, search, and playback compatibility.
- Content access is subject to platform rules and account permissions.

### Self-Hosted Music Server (Navidrome / OpenSubsonic)

- Browse and search albums, songs, and artists; view an artist's albums.
- Uses the existing player, artwork, and lyrics.
- Supports local favorites, playlist management, and original-file offline downloads.
- Preview with limited testing; server write-back, Listen Together, and profile migration are not wired up yet.

### QQ Music

- Now Playing metadata and lyric completion.
- No login, playback, or library pages.

### Local Audio

- Share / open import, device and folder scanning, and playback.
- Artist grouping, sharing, and playlist management.

When online, tap a desktop-synced Bilibili multipart video in My Favorites or a local playlist to choose which part to play.

---

## Implementation Notes

Build versions, module boundaries, and key implementation paths are documented separately:

- [docs/implementation_EN.md](./docs/implementation_EN.md): build versions, the 14 owned libraries, entry points and navigation, playback and caching, search and data sources, local data and safety, downloads and backup.
- [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md#project-layout): module boundaries, extension paths, tests, and submission rules.

---

## Listen Together Deployment

NeriPlayer includes Listen Together. You can deploy your own server or use one deployed by someone else.

- In-repo server source: `np-submodule/NeriPlayer-LTW`
- Public deployment template: [TheSmallHanCat/NeriPlayer-LTW](https://github.com/TheSmallHanCat/NeriPlayer-LTW)

The server runs on **Cloudflare Workers** and **Durable Objects** and syncs in real time over WebSocket.

### One-click deploy to Cloudflare Workers

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/TheSmallHanCat/NeriPlayer-LTW)

In the app's Settings you can set the server address, test availability, and reset the local Listen Together identity.

- Room IDs are 6 readable characters.
- Nicknames are 1–24 characters: Chinese, Latin letters, and digits.
- The first join must use the key in the invite link.
- Local songs cannot create a room.

For the full protocol, event model, and deployment details, see [np-submodule/NeriPlayer-LTW/README.md](./np-submodule/NeriPlayer-LTW/README.md).

---

## Sync

NeriPlayer can sync metadata to **your own** GitHub repository or a WebDAV remote file. Repositories created in-app are private by default, and you can also attach an existing one.

**Synced objects**: local playlists, favorite playlists, recent plays, recent-play deletion records, and playback stats.

### Usage

1. Open Backup & Sync in Settings.
2. For GitHub, create a Personal Access Token with the `repo` scope.
3. Validate the token, then create the default private repository or attach an existing one.
4. Enable automatic sync, or tap Sync now.

New installs use V4 directly. If an upgrade dialog appears, update every device that participates in sync first, then confirm and sync immediately to migrate the address.

### Behavior

- 🔒 **Credentials**: the GitHub token and WebDAV password are stored in `Android Keystore + EncryptedSharedPreferences`.
- ⚠️ **Transport**: plain-HTTP addresses show a cleartext warning.
- 🔄 **Scheduling**: a change syncs after 5 seconds, plus an hourly periodic sync.
- 🔀 **Merging**: playlists, history, stats, and deletion records use a three-way merge. This is background two-way sync, not real-time push.
- 📦 **Remote format**: V4 uploads ZSTD chunks incrementally.
- 🧷 **Write protection**: strong-ETag conditional writes are preferred; Nutstore and similar services check manifest fingerprints instead.
- 🔓 **Encryption**: remote files are not end-to-end encrypted.

---

## Roadmap

Plans change with maintainer capacity, platform availability, and community feedback.

- [ ] Video playback
- [ ] Continued expansion of third-party playback, library pages, and account support
- [ ] Fuller QQ Music account support, library data, and auth flow

Shipped work is tracked in [Releases](https://github.com/cwuom/NeriPlayer/releases) and the commit history.

---

## Bug Report

- Enable developer mode first (tap the **version number** 7 times in Settings). It turns on plain file logging, and crash logs are saved separately.
- Open an [issue](https://github.com/cwuom/NeriPlayer/issues) with the OS version, device model, app version, reproduction steps, and relevant logs.
- Filter logs on Windows: `adb logcat | findstr NeriPlayer`
- Filter logs on Linux / macOS: `adb logcat | grep NeriPlayer`

---

## Known Issues

### Network

- Use split-tunneling rules for third-party platforms; a global proxy can make some APIs return unexpected data.

### Downloads

- Downloads use the in-app manager rather than the system download service. They support resume and startup recovery, within the app's background lifecycle limits.
- Manual cancel clears checkpoints and partial files; only network-policy pauses and recoverable errors keep resume state.
- A custom SAF directory is easier to access externally, but scanning, migration, and writes are slower than in the app's private directory.

### USB exclusive output

- Needs a compatible DAC and a foreground service. If playback stops with the screen off, lift battery and background restrictions as prompted in Settings.
- UAC2 asynchronous mode needs a single explicit feedback endpoint and a supported clock topology; implicit feedback and unverifiable topologies are unsupported.
- Turn off USB exclusive mode to use system audio output.
- Phone and DAC compatibility needs real-device checks.

### Audio output

- 32-bit high-resolution output bypasses loudness leveling, channel balance, audio visualization, and in-app playback speed. Turn it off when you need those features.

### Bluetooth car

- AVRCP has no lyric field, so car lyrics are sent through the track title field. Refresh rate and artwork depend on the phone OS and head unit.
- Android Auto uses its standard in-car UI; no standalone Android Automotive OS build is provided.

### Lyrics

- Transliteration needs transliteration data in the platform response or embedded tags.
- Generated lyric cards live in the app cache directory and can be freed by clearing the cache.
- ColorOS lock-screen lyrics are experimental, need vendor support plus Root / Shizuku, and should be re-checked after system updates.

### Platform scope

- Bilibili focuses on search, favorites, collections, and audio playback, not a full video discovery feed.
- QQ Music is used only for Now Playing metadata and lyric completion.

### Sync and export

- Remote sync files are not end-to-end encrypted.
- Configuration exports exclude login state by default. If you include auth credentials, set a password and keep it safe; it is not stored in the export file and cannot be recovered.
- When concurrent updates fork the same branch, a conflict is reported and remote commits are never force-overwritten.

### Other

- Long-audio position memory never overrides a start position you set, and is not written back to third-party platform history.
- Bilibili SponsorBlock queries a public API and is disabled in Listen Together rooms.

---

## Privacy

- No public cloud media distribution service, and no ad, third-party analytics, or crash-reporting SDKs.
- You choose and own the sync target; personal media data is never collected on a maintainer-controlled platform.
- Cache, downloads, local playlists, history, playback stats, settings, and auth data are stored on the device.
- Metadata such as playlists, favorites, history, and playback stats syncs only when you enable GitHub or WebDAV sync.
- Audio cache, downloaded files, cookies, and playback tokens are never uploaded to the developer.
- For account safety, local playback history and stats are never written back to third-party music platforms.
- Configuration exports exclude login state by default; including it exports auth and sync credentials, so set a password and keep it private.
- Android system cloud backup and device transfer are disabled by default.
- Access logs and risk-control policies on third-party platforms are handled under those platforms' own privacy policies.

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

- **Contributing**: read [CONTRIBUTING_EN.md](./CONTRIBUTING_EN.md) first. Core playback, local data, sync, and recovery paths are maintained first, and Releases are pushed manually in feature batches.
- **Support**: donations are not accepted; issues, pull requests, and shared usage feedback are welcome.
- **License**: NeriPlayer is released under **GPL-3.0**. You may use, modify, and distribute it, and modified distributions must stay under GPL-3.0. Full terms are in [LICENSE](./LICENSE).
- **Native source license**: the [native source license note](./modules/native/src/main/cpp/README.md) grants an alternative license with attribution conditions for the NeriPlayer-owned native sources it lists. Third-party sources and unlisted content are excluded.
- **External native contributions**: a pull request does not enter that scope automatically; only an explicit dual-license statement does.
- **Third-party code**: the Xiaomi Super Island lyric Shizuku / XMSF network-isolation adapter ports GPL-3.0 Capsulyric sources; see [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md).

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
