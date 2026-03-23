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
The project's name and icon are inspired by the character "Kazamata Neri" from "星空鉄道とシロの旅".
</p>

<p>
This project is developed natively for Android, currently supporting Android 9 (API 28) and above.
It continuously iterates around "multi-source exploration, online playback, and local control".
</p>

🚧 <strong>Work in progress</strong>

</div>

> [!WARNING]
> This project is for learning and research purposes only. Please do not use it for any illegal purposes.

---

> [!NOTE]
> NeriPlayer does not provide public cloud-based music libraries or media distribution services.
> Online audio capabilities rely on your account authorization on third-party platforms.
> VIP or restricted content must still comply with the original platform's rules.

---

## About
NeriPlayer is a native Android audio player based on **Jetpack Compose + Media3**.
The current focus is not to build a public cloud service, but to integrate online content from **NetEase Cloud Music** and **Bilibili** under the premise that the user already has third-party platform accounts. It also provides features like **streaming cache, in-app downloads, local imports, local playlist management, and optional GitHub private repository sync**.

- **Account as Capability**: By legally authorizing your third-party platform accounts, you enable online playback, searching, and playlist access.
- **Local Storage by Default**: Playback cache, downloaded files, playlists, history, settings, and authorization info are saved locally on the device by default.
- **Optional Private Repo Sync**: Metadata such as playlists, favorites, and history can be synced to your own GitHub private repository.
- **Single Activity + Compose Architecture**: `MainActivity` is the sole entry point, organizing the UI via Compose `NavHost`, Mini Player, and Now Playing overlay.
- **Initial Disclaimer Phase**: The app startup flow is `Loading -> Disclaimer -> Main`. First-time users must read and agree to the disclaimer.

---

## Key Features
- 🎧 **Multi-Source Exploration and Playback**: The `Explore` page currently supports browsing curated NetEase playlists and YouTube Music playlists, and provides search portals for **NetEase Cloud Music / Bilibili / YouTube Music**.
- 🔍 **Layered Search Capabilities**: Page searching and playback metadata completion are two separate flows. `Explore` uses **NetEase / Bilibili / YouTube Music**; `SearchManager` uses **NetEase / QQ Music** to complete cover art, lyrics, and track info.
- 🧠 **Media3-based Custom Playback Manager**: `PlayerManager` handles stream parsing, playlist queues, shuffle/looping, state persistence, failure retry, and recovery.
- 💾 **Configurable Streaming Cache**: The player uses `SimpleCache + LRU` for audio caching, with a default limit of **1 GB**. Allows manual cache clearing in settings.
- ⬇️ **In-App Downloads and Local Playback**: Supports downloading online audio sources to the app's dedicated directory, saving lyrics and covers synchronously, and allowing you to view progress and manage downloaded songs within the app.
- 📁 **Local Audio Import and Scanning**: Supports `VIEW / SEND / SEND_MULTIPLE` for `audio/*` via the system, allowing audio import after external sharing/opening. Also supports scanning local audio on the device.
- ☁️ **GitHub Private Repository Sync**: Optional syncing of local playlists, favorite playlists, recent play history, and deletion records using `WorkManager` for delayed and periodic syncing.
- 🛠️ **Developer Mode and Debug Tools**: Tap the version number **7 times** in settings to reveal an independent `Debug` page in the bottom bar, containing probes for YouTube / Bili / NetEase / Search API, general logs, and a crash log viewer.
- 🌈 **Audio-Reactive Dynamic Background**: On the Now Playing screen (Android 13+), you can optionally enable an audio-reactive background effect based on `RuntimeShader`.
- ♻️ **Local Backup and Restore**: Supports local JSON import/export of playlists and favorites for device migration or manual backup.

---

## Platform Status
- **NetEase Cloud Music**: Login, search, curated playlist/album access, playback, downloads, lyrics completion.
- **Bilibili**: Login, search, favorites access, multi-part video-to-audio playback, downloads.
- **YouTube Music**: Login, playlist browsing/details, playback, downloads; registered as a search source in Explore.
- **QQ Music**: Currently solely used for playback metadata/lyrics completion. Login, playback, and library features are not implemented.

---

## Implementation Notes
### Build and Versions
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 28`
- Java 17 / Kotlin JVM 17
- Version name format: `<git_short_hash>.<MMddHHmm>`
- Release APK filename: `NeriPlayer-<versionName>.apk`

### Entry Point and Navigation
- The only external entry point is `MainActivity`, which handles app startup and external audio import.
- The startup flow includes a disclaimer phase; Android 13+ requests notification permissions on the first launch.
- The main UI is built using **Compose NavHost + Dynamic Bottom Bar**: `Home / Explore / Library / Settings` form the primary paths.
- `Home` is only displayed if feed cards are enabled; `Debug` is only displayed after enabling developer mode.
- `Now Playing` is not a standard route but a full-screen playback layer over the main navigation, with a persistent `Mini Player` at the bottom.

### Playback, Caching, and Services
- Playback core is based on Media3 ExoPlayer, centrally managed by `PlayerManager`.
- `AudioPlayerService` provides a foreground playback service, media notifications, and basic transport controls.
- Bilibili audio playback uses `ConditionalHttpDataSourceFactory` to dynamically append `Referer / User-Agent / Cookie`.
- Playback states are periodically persisted for queue and state recovery upon app restarts.

### Search and Data Sources
- **UI Search**: Currently integrates **NetEase, Bilibili, and YouTube Music**. Searches are platform-specific and do not aggregate results.
- **Metadata Completion**: Underlying processes rely on **NetEase and QQ Music** exclusively for completing cover art, lyrics, and track metadata across different sources during playback.
- ⚠️ **QQ Music** currently acts strictly as a background metadata provider. Its Library entry remains an inactive placeholder.

### Local Data and Security
- Settings and platform Cookies are persisted using `DataStore`.
- Play histories, playlists, favorite snapshots, and specific mappings are persisted via local files.
- Local playlists are stored via JSON files and achieve atomic writes via temporary swappable files.
- GitHub Tokens are securely stored locally via **Android Keystore + EncryptedSharedPreferences**.
- GitHub sync utilizes locally generated UUIDs as device identifiers instead of relying on `ANDROID_ID`.

### Downloads, Imports, and Backups
- Downloads utilize a single shared `OkHttpClient`, avoiding the system `DownloadManager`.
- Downloaded files reside within a dedicated app directory, matching and appending their requisite lyrics alongside cover arts.
- `LocalAudioImportManager` handles foreign track inclusions and replicates nearby `lrc/txt` texts alongside graphical `cover/folder/front` album images.
- `BackupManager` orchestrates diff-based manual JSON deployments internally alongside standardized exportation pipelines.

Want to learn more about the implementation details? Please read [CONTRIBUTING.md](./CONTRIBUTING_EN.md).

---

## GitHub Sync
NeriPlayer supports syncing local metadata to **your own GitHub private repository**. The primary sync targets include:

- Local playlists
- Favorite playlists
- Recently played records
- Recently played deletion records

### Technical Details
- 🔒 **Local Secure Storage**: GitHub Tokens are kept safely in `Android Keystore + EncryptedSharedPreferences`.
- 🔄 **Sync Scheduling**: Local data modifications trigger an operation **delayed by 5 seconds**; a continuous **hourly** periodic cycle is also enforced.
- ⏱️ **Eventual Consistency**: Expect standard background two-way syncing rather than definitive real-time metric pushes.
- 🌐 **Network Requirements**: Syncing banks upon `WorkManager`, activating solely underneath **validated network** settings.
- 🧩 **Conflict Resolving**: Standard tri-directional matching occurs for playlists, histories, favorites, and wipe parameters.
- 🧯 **Conflict Borders**: Automatic built-in logic mitigates standard crashes. Manual resolution interfaces remain absent.
- 🪶 **Data Saver**: Operational sync modules possess natively enforced Data Saver parameters defaulting to active.
- 📦 **Remote Formatting**: Archival datasets operate upon plaintext JSON logic or compressed bin strings. Utilizing GitHub private repositories remains disparate from definitive end-to-end encryptions.
- 🚫 **Sync Borders**: We purely harmonize basic metadata values. Media playback cache, physical mp3 clusters, standalone external configurations, native cookies and token chains remain strictly localized variables.

### How to Use
1. Enter the GitHub Sync menu within Settings.
2. Formulate a standardized GitHub Personal Access Token (requiring the `repo` permission).
3. Validate and specify the preferred sync destination via internal dialogue parameters.
4. Toggle automatic syncing on.

---

## Getting Started
### a. Download Release Version (Recommended)
1. Go to [GitHub Release](https://github.com/cwuom/NeriPlayer/releases)
2. How to choose a version?
   - Most users should download the arm64-v8a version.
   - For older phones (32-bit systems), download the armeabi-v7a version.
   - x86 / x86_64 versions are intended solely for emulators/Intel devices/Chromebooks.

### b. Download CI Version
1. Go to [GitHub Actions](https://github.com/cwuom/NeriPlayer/actions), download the Artifacts of the latest successful build, and extract it.
2. Alternatively, visit [NeriPlayer CI Builds](https://t.me/neriplayer_ci).

> CI only builds the arm64-v8a version.

### c. Local Build
1. Clone the repository and open it in Android Studio (latest stable version):
   ```bash
   git clone https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. Sync dependencies.
3. Build the debug version:
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. Install the APK (Android 9+ device required):
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. Read and concur with the internal disclaimers on opening. Internal Android 13 architectures request prompt configurations synchronously.
6. For utilizing internal debugging functions: tap the localized **Version** descriptor sequentially 7 times natively located via configuration preferences, which spawns an isolated bottom bar `Debug` subset environment.

> DEBUG configurations exhibit compromised operational fluidity compared to release equivalents, serving specifically for developmental procedures.

For procedures spanning systematic builds or subsequent isolated app signature logic setups, check [CONTRIBUTING.md](./CONTRIBUTING_EN.md#release-build).

---

## Roadmap
- [ ] Video playback
- [ ] Comment section
- [x] Clear cache
- [x] Add to playlist
- [x] Tablet adaptation
- [ ] Floating lyrics
- [x] Internationalization
- [x] NetEase Cloud Music adaptation
- [x] Bilibili adaptation
- [x] YouTube Music basic adaptation
- [x] YouTube Music search capabilities
- [ ] Continuous extension for third-party platforms

> ⚠️ Currently, QQ Music is primarily used for playback page metadata completion.
> Complete account functionalities, library page data, and more stable authorization pipelines remain deep within developmental scopes.

---

## Bug Report
- Always prioritize booting into developer mode explicitly before articulating functional breakdowns. (Tap **version number** 7 times under Settings).
- Following activation: standard baseline log tracking initiates independently. Complete system breakdown registers trigger immediate isolated external hard drive prints.
- Access the [Issues](https://github.com/cwuom/NeriPlayer/issues) domain. Present critical systematic variables like localized OS models, explicit isolated application iterations, reproduction instructions, alongside core diagnostic parameter logs.
- Windows-based terminal tracking:
  ```bash
  adb logcat | findstr NeriPlayer
  ```
- MacOS / Linux iteration:
  ```bash
  adb logcat | grep NeriPlayer
  ```

---

## Known Issues
### Network
- Configure proxy implementations diligently. Submerging completely under external routing nodes frequently shatters internal standard third-party structural returns.

### Limitations
- The integrated overarching download mechanisms isolate system dependencies. Re-establishment hooks on aborted download strings remain completely inactive currently.
- `Bilibili` strictly functions as an expedited search/audio conversion hook currently. Comprehensive generalized audio discovery branches aren't natively supported yet.

---

## Privacy
- NeriPlayer explicitly avoids supplying universal cloud distributions. It fundamentally evades encompassing localized tracking analytic systems, invasive programmatic advertising components, alongside third-party crash evaluation platforms.
- General operational configurations, encompassing internal downloaded variables, user preferences, explicit platform credentials, historical playthroughs, alongside audio caches invariably constrain straight inside native storage environments defaults.
- For instances enabling explicit GitHub integrations, standard operations exclusively port standalone playlist variants alongside superficial metadata attributes straight into privatized native external hosting servers.
- Streaming credential variants, extensive cookie caches, complete file outputs, alongside audio logs consistently omit all potential avenues returning to core development branches.
- NeriPlayer operates within internalized standard Android Backup parameters; definitive exportation scopes inherently defer straight over to individualized overarching OS methodologies.
- Standalone localized tracking measures established via independent exterior servers fundamentally fall squarely upon respective platform regulations governing access interactions.

---

## Reference
<table>
<tr>
  <td><a href="https://github.com/chaunsin/netease-cloud-music">netease-cloud-music</a></td>
  <td>✨ NetEase Cloud Music Golang Implementation 🎵</td>
</tr>
<tr>
  <td><a href="https://github.com/SocialSisterYi/bilibili-API-collect">bilibili-API-collect</a></td>
  <td>Bilibili API Collection and Organization</td>
</tr>
<tr>
  <td><a href="https://github.com/yt-dlp/ejs">ejs</a></td>
  <td>External JavaScript for yt-dlp supporting many runtimes</td>
</tr>
<tr>
  <td><a href="https://github.com/ReChronoRain/HyperCeiler">HyperCeiler</a></td>
  <td>HyperOS enhancement module - Make HyperOS Great Again!</td>
</tr>
</table>

---

## Update Cycle
- Active modifications exclusively govern primary utility operations natively. Peripheral contributions are systematically welcomed straight through active community outreach variables.
- Operational commits face generalized pausing frequencies resulting over unforeseen contingencies organically.
- Extensive PR reviews alongside critical feedback arrays fundamentally improve overall project metrics continuously.

---

## Support
- Given overarching functional distinctness, monetary donation implementations remain fundamentally detached natively.
- Project amplification operates exclusively alongside actionable Issue generation, standardized PR inputs, alongside expressive conversational engagement detailing functional encounters externally.

---

## License
NeriPlayer is published under the **GPL-3.0** open source license.

This means:
- ✅ You can freely utilize, mutate, and branch out iterations organically.
- ⚠️ Modified releases necessitate returning equivalent architectures actively wrapped around GPL-3.0 structural bindings.
- 📚 Read definitive overarching parameters spanning native [LICENSE](./LICENSE) inclusions natively.

---

# Contributing to NeriPlayer
Before contributing, please read the complete [CONTRIBUTING.md](./CONTRIBUTING_EN.md).

---

<p align="center">
  <img src="https://moe-counter.lxchapu.com/:neriplayer?theme=moebooru" alt="Moe Counter">
  <br/>
  <a href="https://starchart.cc/cwuom/NeriPlayer">
    <img src="https://starchart.cc/cwuom/NeriPlayer.svg" alt="Star History Chart">
  </a>
</p>
