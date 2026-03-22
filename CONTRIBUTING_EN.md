[English](./CONTRIBUTING_EN.md) | [中文](./CONTRIBUTING.md)

## Contributing to NeriPlayer

Thank you for your interest in contributing to NeriPlayer!
This document describes the **actual implementation of the Android client** and strives to stay consistent with the source code.

---

### Scope

- NeriPlayer is a **native Android audio player**, not a public cloud music service.
- Online source capabilities are currently provided by **NetEase Cloud Music** and **Bilibili**, with ongoing support for **YouTube Music**.
- Metadata and lyrics completion in the player screen currently utilize **NetEase Cloud Music + QQ Music**.
- Data is saved locally by default. GitHub sync is an **optional** feature, and it only syncs metadata such as playlists, favorites, and play history (not the actual media files).

---

### Development Environment

- **Android Studio**: Latest stable version
- **compileSdk**: 36
- **targetSdk**: 36
- **minSdk**: 28
- **Java / Kotlin**: Java 17, Kotlin `jvmTarget = 17`
- **Version Name Format**: `<git_short_hash>.<MMddHHmm>`
- **Release APK Filename**: `NeriPlayer-<versionName>.apk`

Additional notes:

- The build script reads the Git short commit hash to generate the version name, so please assure that Git is installed locally.
- Dependency versions are managed by `gradle/libs.versions.toml` and each module's `build.gradle.kts`.

---

### Quick Start

1. Clone the repository:
   ```bash
   git clone https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. Build the Debug version:
   ```bash
   ./gradlew :app:assembleDebug
   ```
3. Install it onto a device:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Upon first launch, the app enters a Disclaimer phase. Devices running Android 13+ will prompt for notification permissions.
5. For debug access, tap the **version number** 7 times in the Settings page. An independent `Debug` page will appear in the bottom navigation bar.

---

### Release Build

The release build enables code shrinking and obfuscation by default.

1. Provide your signing config in `~/.gradle/gradle.properties` or through environment variables:
   ```properties
   KEYSTORE_FILE=/absolute/path/to/neri.jks
   KEYSTORE_PASSWORD=your_store_password
   KEY_ALIAS=key0
   KEY_PASSWORD=your_key_password
   ```
2. Execute the build:
   ```bash
   ./gradlew :app:assembleRelease
   ```
3. Artifacts will be generated in `app/build/outputs/apk/release/`, named following this format:
   ```text
   NeriPlayer-<git_short_hash>.<MMddHHmm>.apk
   ```

Security Reminder:

- Never commit your keystore, passwords, Cookies, Tokens, or any other sensitive info.
- Do not paste full authorization information in Issues or PRs.

---

### Project Layout

- `app/src/main/java/moe/ouom/neriplayer/NeriPlayerApplication.kt`
  - The application initialization entry. Handles language settings, crash handling, `AppContainer`, global download manager, and the shared image loader.

- `app/src/main/java/moe/ouom/neriplayer/activity/`
  - `MainActivity.kt`: The main external entry point. Manages the startup flow, disclaimers, external audio imports, and the top-level Compose host.
  - `NeteaseWebLoginActivity.kt`, `BiliWebLoginActivity.kt`, and `YouTubeWebLoginActivity.kt`: Internal web login pages (not external entry points).

- `app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt`
  - Top-level Compose application scaffolding. Handles `NavHost`, dynamic bottom bar, `MiniPlayer`, `Now Playing` overlays, and Debug routing.

- `app/src/main/java/moe/ouom/neriplayer/core/api/`
  - `netease/`: NetEase Cloud Music endpoints and account features.
  - `bili/`: Bilibili search, playback metadata, and audio stream extraction.
  - `youtube/`: YouTube Music client (based on NewPipe Extractor), playback repository, PoToken, and JS Challenge support.
  - `search/`: Playback metadata/lyrics completion APIs. Current implementations include `CloudMusicSearchApi` and `QQMusicSearchApi`.
  - `lyrics/`: External lyrics sources. Current implementation includes `LrcLibClient`.

- `app/src/main/java/moe/ouom/neriplayer/util/SearchManager.kt`
  - Unified encapsulation for playback metadata matching logic.
  - Note: This does not share the same backend as the UI search function on the `Explore` page.

- `app/src/main/java/moe/ouom/neriplayer/core/player/`
  - `PlayerManager.kt`: Unified management layer for Media3 ExoPlayer. Handles stream parsing, playlist queues, caching, state restoration, and fallback mechanisms.
  - `AudioPlayerService.kt`: Foreground playback service, media session notifications, and media button controls.
  - `ConditionalHttpDataSourceFactory.kt`: Dynamically appends `Referer`, `User-Agent`, or `Cookie` for specific domains.
  - `ReactiveRenderersFactory.kt`, `AudioReactive.kt`: Supplies real-time audio energy data for the audio-reactive dynamic backgrounds on the 'Now Playing' screen.
  - `YouTubeGoogleVideoRangeSupport.kt`, `YouTubeSeekRefreshPolicy.kt`: Specially designed logic to handle Google Video Range requests and playback seek refresh policies during YouTube Music playback.
  - `SleepTimerManager.kt`: Sleep timer utilities.
  - `AudioDownloadManager.kt`: The core implementation for audio downloads. Resolves platform streams to obtain direct links and saves them to local storage.

- `app/src/main/java/moe/ouom/neriplayer/core/download/`
  - `GlobalDownloadManager.kt`: Maintains global download tasks and the list of locally downloaded files.

- `app/src/main/java/moe/ouom/neriplayer/data/`
  - `SettingsDataStore.kt`: Manages user settings and standard states.
  - `LocalPlaylistRepository.kt`: Atomic writes for local playlist JSONs.
  - `BackupManager.kt`: Implements JSON import/export and diff calculation over local configurations.
  - `LocalAudioImportManager.kt`: Supports external audio imports, scanning internal/external local audio, and copying sidecar files like lyrics/covers.

- `app/src/main/java/moe/ouom/neriplayer/data/github/`
  - `GitHubSyncManager.kt`: Orchestration and three-way logic merging for syncs.
  - `GitHubSyncWorker.kt`: Delayed and periodic sync execution based on `WorkManager`.
  - `SecureTokenStorage.kt`: Safely stores GitHub tokens utilizing local encryption.

---

### Current Boundaries

- `Explore` currently curates **NetEase playlists, YouTube Music playlists, and search results spanning NetEase, Bilibili, and YouTube Music** (YouTube Music search is currently WIP). Bilibili is not implemented yet as a fully-fledged discovery page.
- The QQ Music entry in `Library` remains a placeholder. Do not mistake it for a "completely integrated platform".
- `YouTube Music` already implemented login, playlist browsing/details, playback, and downloading; it's registered as a search module in Explore, but searching remains unimplemented.
- Downloads utilize a single shared `OkHttpClient` downloading straight into a dedicated directory. This is **not** handled by the system's `DownloadManager` or a persistent foreground downloading service. Moreover, **resume-support is unimplemented**.
- Streaming cache and permanent downloads are separated: caching leverages `SimpleCache`, while downloads are written to physical local files handled by `AudioDownloadManager`.
- GitHub Sync only persists metadata; audio caches, files, explicit user cookies, and streaming tokens are systematically skipped.
- Platform Cookies are currently persisted within `DataStore`. Only GitHub Tokens are fully encrypted utilizing `Android Keystore + EncryptedSharedPreferences`.

---

### Extension Paths

#### 1. Adding an Explore Search Source

Applies when integrating an external platform to the `Explore` page search functionalities.

Suggested steps:

1. Draft a client or repository within `core/api/`.
2. Connect data fetch requests alongside state mappings inside `ExploreViewModel`.
3. Add UI selectors throughout `ExploreScreen` / the Host layout.
4. Hook up the backend to `PlayerManager`'s audio-parsing mechanisms if playback functionality is desired.

#### 2. Enhancing Playback Metadata Completion

Applies when trying to patch incomplete cover art, lyrics, and extended track details in lieu of supplementing the overarching `Explore` catalog.

Suggested steps:

1. Draft a new `SearchApi` interface iteration underneath `core/api/search/`.
2. Push your singleton to `AppContainer`.
3. Create distinct routing and pattern-matching architectures inside `SearchManager`.
4. Supplement required enums (e.g. `MusicPlatform`) and UI strings according to contextual relevance.

#### 3. Integrating a New Streaming Outlet

1. Utilize modules akin to `bili/` for designing clients and playback repos.
2. Adapt customized network Headers across `ConditionalHttpDataSourceFactory.kt`.
3. Extend the overarching network parsing scope mapped internally throughout `PlayerManager` with your outlet logic.
4. Exercise diligent functional partitioning ensuring you strictly bypass merging "ephemeral caching" with "persistent file downloading".

#### 4. Supplementing Preference Toggle Settings

1. Draft corresponding keys, specific Flows, and setter architectures over by `SettingsDataStore.kt`.
2. Amalgamate and propagate them inside `NeriApp.kt`.
3. Bind UI interactions across `SettingsScreen.kt`.
4. Extensively recalibrate overarching top-level architectures around `NeriApp.kt` should your new layout disrupt global motifs.

#### 5. Altering GitHub Cloud Alignments

1. Extrapolate upon the initial tri-directional merge procedures established within `GitHubSyncManager.kt`.
2. Account for sync targets which invariably persist playlists, favorites, playback chronologies, and deletion histories.
3. Protect existing behavioral motifs inside `GitHubSyncWorker.kt`. Guard against overriding latency timers, routine executions, validated network checks, or subsequent retry protocols.
4. Securely route sensitive credentials across `SecureTokenStorage.kt`. Under absolutely no pretense should Tokens regress towards `DataStore` or plain text architectures.

---

### Debugging & Logs

- Enable Developer Mode: Tap the **version number** 7 times under the Settings page.
- Upon opening, a standalone `Debug` directory will appear on your bottom bar layer.

Logs instructions:
- Standard file-level logs operate exclusively only when developer mode is explicitly enabled.
- Hard Crash files are natively compartmentalized and routed by `ExceptionHandler` independent of any developer constraints.

Conventional commands:

```bash
adb logcat | findstr NeriPlayer
```

Mac/Linux Alternative:

```bash
adb logcat | grep NeriPlayer
```

---

### Testing & PR Procedures

Ensure the core minimum validation scopes beforehand:

1. Successful localized generation via:
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. For resources, UI layouts, navigation, configurational modifications, or synchronous alignment logic revisions, heavily consider running:
   ```bash
   ./gradlew :app:lintDebug
   ```
3. Locate specific isolated tests to `app/src/test/`; funnel UI Compose or distinct device tests onto `app/src/androidTest/`.
4. Sync structural architectural changes alongside their corresponding documentation adjustments, spanning README scopes, user pipelines, or parameter notes.

Your PR should inherently exhibit:
- General motivations / objectives.
- Targeted implementations.
- Associated compatibilities / latent risks.
- Assessment / Execution procedures.
- Accompanying screenshots or workflow clips for extensive front-end revisions.

Avoid committing:
- APK clusters, signing credentials, or isolated IDE directories.
- Caches, residual pipelines, error log snapshots, or tentative builds.
- Cookies, exclusive Token strings, or personal analytics datasets.

Try adopting standard Conventional Commit parameters spanning `feat: ...`, `fix: ...`, or `docs: ...`.

---

### Legal & License

- This project has been compiled exclusively within educational parameters; using it for illegal distributions inherently breaches repository policies.
- This application stands as an Open Source branch running on **GPL-3.0**.
- You automatically concur with disseminating your personalized modifications around GPL-3.0 architectures by submitting them back.

---

### Communication

- [Issues](https://github.com/cwuom/NeriPlayer/issues): Discrepancies, proposed enhancements, or generalized discourse
- [README.md](./README_EN.md): Capabilities alongside overarching workflow directives

Before advancing drastically isolated structural concepts, strictly propose them as comprehensive Issue tickets aiming towards universal alignment.
