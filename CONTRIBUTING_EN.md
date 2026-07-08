[English](./CONTRIBUTING_EN.md) | [中文](./CONTRIBUTING.md)

## Contributing to NeriPlayer

Thank you for contributing to NeriPlayer.
This document describes the **current Android client and Listen Together Worker
implementation**. Keep documentation aligned with the source code and runtime behavior.

---

### Scope

- NeriPlayer is a **native Android audio player**, not a public cloud music service.
- Online source capabilities mainly come from **NetEase Cloud Music**,
  **Bilibili**, and **YouTube Music**.
- Playback metadata and lyrics completion currently use **NetEase + QQ Music**,
  with LRCLIB available as an external lyrics source.
- Data is local by default. GitHub / WebDAV sync is **optional** and syncs
  metadata such as playlists, favorites, recent plays, and playback stats,
  not media files.
- The Listen Together server lives in `np-submodule/NeriPlayer-LTW` and is based
  on Cloudflare Workers and Durable Objects.

---

### Documentation Map

When maintaining docs, split them by audience:

- `README.md` / `README_EN.md`
  - For users and new contributors: project scope, feature boundaries,
    installation/builds, sync, and privacy.
- `CONTRIBUTING.md` / `CONTRIBUTING_EN.md`
  - For developers: module boundaries, extension paths, tests, and PR expectations.
- `np-submodule/NeriPlayer-LTW/README.md`
  - For Listen Together server deployers: Worker API, event model, deployment,
    and local checks.

If a behavior change affects user understanding, update the README.
If it affects extension paths, tests, or module boundaries, update CONTRIBUTING.

---

### Development Environment

- **Android Studio**: latest stable version
- **JDK**: 17
- **Kotlin**: 2.2.x, JVM target 17
- **AGP**: 8.13.x
- **compileSdk / targetSdk / minSdk**: 36 / 36 / 28
- **NDK**: `27.0.12077973`
- **CMake**: `3.22.1`
- **Node.js**: 20, for Listen Together Worker checks
- **Version name format**: `<git_short_hash>.<MMddHHmm>`
- **Release APK filename**: `NeriPlayer-<versionName>[-abi].apk`

Additional notes:

- The repository uses Git submodules. Clone with `--recursive`, or run
  `git submodule update --init --recursive`.
- The build script reads the Git short commit hash to generate the version name,
  so Git must be installed locally.
- Dependency versions are managed by `gradle/libs.versions.toml` and module
  `build.gradle.kts` files.
- Only `zh` and `en` resources are kept in the app, via the locale filter in `build-logic`.

---

### Quality Guardrails

NeriPlayer covers a broad product surface. Protect these paths first:

- **Playback**: `PlayerManager`, stream resolution, cache, URL refresh,
  auto source switching, and state recovery.
- **Downloads**: `AudioDownloadManager`, `GlobalDownloadManager`,
  `ManagedDownloadStorage`, resume checkpoints, sidecar files, and SAF migration.
- **Sync**: GitHub / WebDAV three-way merge, deletion records, playback stats,
  and remote format compatibility.
- **Local data**: atomic playlist JSON writes, config import/export, encrypted
  auth storage, and DataStore settings.
- **Listen Together**: Android client, Worker protocol fields, roles, queues,
  and controller-offline recovery.
- **Diagnostics**: safe mode, JVM/native crash logs, ANR capture, and Debug probes.

Related tests live under `app/src/test/` and `app/src/androidTest/`.
When changing these areas, search for neighboring tests first, then add coverage
for the new behavior.

---

### Quick Start

1. Clone the repository:
   ```bash
   git clone --recursive https://github.com/cwuom/NeriPlayer.git
   cd NeriPlayer
   ```
2. Build the Debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   ```
3. Install it onto a device:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. First launch enters the disclaimer and startup onboarding flow. Android 13+
   devices request notification permission.
5. For debugging access, tap the **version number** 7 times in Settings. A
   standalone `Debug` tab will appear in the bottom navigation bar.

---

### Release Build

Release builds enable minification and resource shrinking by default.
A normal `assembleRelease` builds `arm64-v8a` only. Multi-ABI output requires an
extra Gradle property.

1. Provide signing config in `~/.gradle/gradle.properties`, project Gradle
   properties, or through command-line `-P` properties:
   ```properties
   KEYSTORE_FILE=/absolute/path/to/neri.jks
   KEYSTORE_PASSWORD=your_store_password
   KEY_ALIAS=key0
   KEY_PASSWORD=your_key_password
   ```

   If `KEYSTORE_FILE` is relative, it is resolved against the `app/` module
   directory. If no keystore is available locally, the Release build falls back
   to the debug signing config. This is useful for test installs only and should
   not be used as an official release artifact.

2. Build the default Release APK:
   ```bash
   ./gradlew :app:assembleRelease
   ```

3. Build multi-ABI Release APKs:
   ```bash
   ./gradlew :app:assembleRelease -PbuildAllReleaseAbis=true
   ```

4. Artifacts are generated in `app/build/outputs/apk/release/`:
   ```text
   NeriPlayer-<git_short_hash>.<MMddHHmm>[-abi].apk
   ```

Security reminders:

- Never commit keystores, passwords, cookies, tokens, or other sensitive data.
- Do not paste full authorization data in Issues or PRs.
- Full config export files contain platform auth and sync credentials. Do not
  attach them publicly.

---

### Project Layout

#### Root modules

- `:app`
  - Main Android application.
- `:ksp-annotations` / `:ksp-processor`
  - KSP-generated settings schema, keys, backup allowlists, and UI metadata.
- `:accompanist-lyrics-core` / `:accompanist-lyrics-ui`
  - Lyrics parsing and Compose lyrics UI submodules.
- `build-logic`
  - Android/Kotlin/Compose convention plugins.
- `buildSrc`
  - Retained auxiliary Gradle build logic.
- `np-submodule/NeriPlayer-LTW`
  - Listen Together Cloudflare Workers server.
- `np-submodule/miuix`
  - Vendored upstream Miuix source/docs tree, not part of the current app module graph.

#### Android client key paths

- `app/src/main/java/moe/ouom/neriplayer/NeriPlayerApplication.kt`
  - Application initialization. Handles language, crash handling, `AppContainer`,
    Lyricon, global downloads, and the shared image loader.

- `app/src/main/java/moe/ouom/neriplayer/activity/`
  - `MainActivity.kt`: the only external entry point. Handles safe mode, startup, disclaimer,
    onboarding, external audio imports, Listen Together deep links, and the top-level
    Compose host.
  - `NeteaseWebLoginActivity.kt`, `NeteaseQrLoginActivity.kt`,
    `BiliWebLoginActivity.kt`, `BiliQrLoginActivity.kt`, and `YouTubeWebLoginActivity.kt`:
    internal platform sign-in pages.

- `app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt`
  - Top-level Compose app shell. Handles `NavHost`, dynamic bottom bar, `MiniPlayer`,
    `Now Playing` overlay, Debug routes, themes, and playback service sync.

- `app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/`
  - `LibraryScreen.kt`: top-level Library categories. Local content can switch
    between playlists and artists, and Favorites can show playlists and followed artists.
  - `LocalArtistLibraryGrid.kt`: local artist grid, empty state, and artist cards.

- `app/src/main/java/moe/ouom/neriplayer/ui/screen/playlist/`
  - `LocalArtistDetailScreen.kt`: local artist details with play-all,
    multi-select, playlist export, and batch download for resolvable online songs.

- `app/src/main/java/moe/ouom/neriplayer/ui/screen/artist/`
  - NetEase artist detail screens for artist info, hot songs, paged albums,
    and follow state.

- `app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/artist/`
  - NetEase artist summaries, JSON parsing, and detail-screen state management.

- `app/src/main/java/moe/ouom/neriplayer/ui/onboarding/`
  - First-run onboarding for language, platform accounts, GitHub sync, and personalization.

- `app/src/main/java/moe/ouom/neriplayer/core/api/`
  - `netease/`: NetEase endpoints, crypto, and account capabilities.
  - `bili/`: Bilibili search, QR login, favorites, collections, playback info,
    and audio stream extraction.
  - `youtube/`: YouTube Music client based on NewPipe Extractor, home/playlist/search/playback,
    PoToken, and JS Challenge support.
  - `search/`: playback metadata/lyrics completion APIs. Current implementations:
    `CloudMusicSearchApi` and `QQMusicSearchApi`.
  - `lyrics/`: external lyrics sources. Current implementation: `LrcLibClient`.

- `app/src/main/java/moe/ouom/neriplayer/core/player/`
  - `PlayerManager.kt`: unified Media3 ExoPlayer management, stream resolution, queue,
    cache, state recovery, retry, and playback policy.
  - `AudioPlayerService.kt`: foreground playback service, media notification,
    MediaSession, and media button handling.
  - `AudioDownloadManager.kt`: resolves platform streams and saves downloads locally.
  - `PlaybackEffectsController.kt`: speed, pitch, loudness enhancer, and equalizer.
  - `PlaybackStatsTracker.kt`: playback stats tracking.
  - `SleepTimerManager.kt`: sleep timer.
  - `ConditionalHttpDataSourceFactory.kt`: adds platform-specific request headers.
  - `PlayerManagerNeteaseAutoSourceSwitch.kt`: Bilibili fallback for NetEase
    tracks that are restricted, have no playable URL, or only return previews.
  - `YouTubeGoogleVideoRangeSupport.kt`, `YouTubeSeekRefreshPolicy.kt`, and
    `prefetch/YouTubePrefetchRunner.kt`: YouTube Music playback compatibility policies.
  - `metadata/`: lyrics, metadata, and external Bluetooth lyrics handling.

- `app/src/main/java/moe/ouom/neriplayer/core/download/`
  - `GlobalDownloadManager.kt`: global download tasks and downloaded song list.
  - `ManagedDownloadStorage.kt`: app directory / SAF directory, migration, snapshots, and `.nomedia`.
  - `ManagedDownloadNaming.kt`: filename templates and legacy filename compatibility.
  - `DownloadedAudioTagWriter.kt`: audio tag writing.

- `app/src/main/java/moe/ouom/neriplayer/data/`
  - `settings/`: `DataStore` settings, KSP schema, bootstrap snapshot, theme snapshot,
    and playback preference snapshot.
  - `auth/`: NetEase, Bilibili, and YouTube cookie/auth storage and validation.
  - `local/playlist/`: local playlist JSON atomic writes, system playlist compatibility,
    and local artist aggregation models.
  - `local/audioimport/`, `local/media/`: local audio import, scanning, metadata reading, and sharing.
  - `playlist/favorite/`, `playlist/usage/`: favorite playlists, followed artists,
    and Home continue-listening data.
  - `history/`, `stats/`: recent plays, playback stats, and day/week/month/year/all-time aggregation.
  - `backup/`: playlist JSON backup/import and diff analysis.
  - `config/`: full app config import/export.
  - `sync/github/`: GitHub sync, three-way merge, serialization, Data Saver, and secure storage.
  - `sync/webdav/`: WebDAV sync, remote config, Worker, and WebDAV API.

- `app/src/main/java/moe/ouom/neriplayer/listentogether/`
  - Listen Together protocol, WebSocket client, session management, invites, sync planning,
    and server URL validation.

- `app/src/main/java/moe/ouom/neriplayer/core/lyricon/`
  - Lyricon integration and SuperLyric output for current song, playback state, position,
    word-level lyrics, and translations.

---

### Current Boundaries

- `Explore` is NetEase curated playlists + YouTube Music playlists +
  platform-specific NetEase/Bilibili/YouTube Music search. It is not mixed search.
- `Home` mainly shows local continue-listening and NetEase recommendations in the
  default Chinese mode. International mode prioritizes YouTube Music home shelves.
- The QQ Music entry in `Library` is still a placeholder and does not represent
  full platform integration.
- Local artist categories are aggregated from imported/saved local songs by
  display artist. They are not an online artist directory.
- NetEase artist detail pages depend on NetEase artist metadata and endpoints;
  follow state is saved into the local Favorites category.
- `Bilibili` supports search, favorites, audio playback, and downloads, but is
  not a full video discovery or comments client.
- `YouTube Music` supports login, home/playlist browsing, details, search,
  playback, and downloads.
- Status-bar lyrics depend on private vendor support and only work on select devices.
- NetEase playback tries lower qualities when the current quality is unavailable.
  For restricted, missing-URL, or preview-only tracks, it can auto-match a
  Bilibili fallback source when enabled.
- Local "My Favorite Music" can sync recognizable NetEase songs to NetEase
  Liked Songs. This requires NetEase login and skips unsupported or existing songs.
- Downloads use a shared `OkHttpClient` and write to the app directory or a SAF
  directory. They are **not** handled by the system `DownloadManager`, but they
  do support automatic resume and startup recovery.
- Resume behavior depends on transport type:
  - direct downloads resume through working-file size plus `Range`
  - explicit chunked downloads resume by byte offset
  - HLS downloads resume from a saved segment checkpoint in `.hls.json`
- Working files live under `cache/download_staging/` and also keep `.resume.json`
  metadata so unfinished downloads can be reconstructed after app restart or
  network recovery.
- Manual cancellation rolls back partial artifacts and removes working files.
  Partial data is preserved only for network-policy pauses and recoverable retry paths.
- Streaming cache and permanent downloads are separate features: cache uses
  `SimpleCache`; downloads are written by `AudioDownloadManager` and
  `ManagedDownloadStorage`.
- GitHub / WebDAV sync only sync metadata. Audio caches, downloaded files, local
  media files, cookies, and playback tokens are not synced.
- Platform cookies/auth data, GitHub tokens, and WebDAV passwords are encrypted
  with `Android Keystore + EncryptedSharedPreferences`.
- `DataStore` stores regular settings and non-sensitive state, not platform login credentials.

---

### Extension Paths

#### 1. Add an Explore search source

Use this when integrating a new platform into `Explore` search or discovery.

1. Implement a client or repository under `core/api/`.
2. Add request, pagination, and state mapping in `ExploreViewModel`.
3. Add platform tabs and result UI in `ExploreScreen` / host screens.
4. If playback is needed, connect the platform to `PlayerManager` stream resolution.
5. If downloads are needed, complete `AudioDownloadManager` and download metadata mapping.

#### 2. Add a playback metadata completion source

Use this for cover, lyrics, and track metadata completion, not for `Explore`.

1. Implement a new `SearchApi` under `core/api/search/`.
2. Register the singleton in `AppContainer`.
3. Add routing, matching, and fallback logic in `SearchManager`.
4. Add `MusicPlatform`, string resources, and debug probes as needed.

#### 3. Add a streaming platform

1. Use `bili/` or `youtube/` as a reference for client and playback repository design.
2. Extend `ConditionalHttpDataSourceFactory.kt` if special headers are needed.
3. Add the platform to the `PlayerManager` URL resolution path.
4. Keep downloads, lyrics, covers, and stats separated from transient streaming cache.
5. If NetEase Liked Songs sync should support the new source, provide stable
   NetEase song IDs or a verified mapping and reuse candidate validation in
   `LocalPlaylistRepository`.

#### 4. Modify NetEase auto source switching

1. The entry point is the NetEase URL resolution flow in `PlayerManagerUrlExtensions.kt`.
2. Matching and scoring live in `PlayerManagerNeteaseAutoSourceSwitch.kt`.
3. Auto source switching is only a fallback for restricted, missing-URL, or
   preview-only NetEase playback. Do not turn it into cross-platform aggregate search.
4. When changing matching, consider title, artist, video pages, duration
   tolerance, and cache key stability.

#### 5. Add a setting

1. Prefer registering keys, defaults, types, and UI metadata in
   `data/settings/AutoSettingsSchema.kt`.
2. Simple switches can use the generated `AutoSettingsRepository` and `AutoSettingsSwitchItems`.
3. Settings with side effects, mutual exclusion, permissions, or startup snapshot
   requirements should keep a handwritten setter.
4. If a setting affects early startup behavior, update the corresponding snapshot:
   `BootstrapSettingsSnapshot`, `ThemePreferenceSnapshot`, or `PlaybackPreferenceSnapshot`.
5. UI usually belongs in the matching `SettingsPage` in `SettingsScreen.kt` or
   under `ui/screen/tab/settings/component/`.

#### 6. Modify GitHub / WebDAV sync

1. Understand `SyncDataModels.kt` and `SyncDataSerializer.kt` compatibility first.
2. Sync data includes playlists, favorite playlists, recent plays, deletion records,
   and playback stats.
3. Most merge logic lives in `GitHubSyncManager.kt`; WebDAV reuses the same data
   model and much of the merge behavior.
4. Do not break the delayed sync, periodic sync, validated-network checks, or retry
   behavior in `GitHubSyncWorker.kt` / `WebDavSyncWorker.kt`.
5. Sensitive data must go through `SecureTokenStorage.kt` or `WebDavStorage.kt`.
   Do not store it in `DataStore` or plaintext JSON.

#### 7. Modify download storage

1. Read `ManagedDownloadStorage.kt`, `ManagedDownloadNaming.kt`, and related unit tests first.
2. Consider app-managed storage, SAF custom directories, migration, legacy names,
   metadata files, and `.nomedia`.
3. Download tasks write to `cache/download_staging/` before being committed to
   the final directory. `.resume.json` and `.hls.json` are part of resume
   recovery and should not be treated as disposable temp files.
4. Default download concurrency is **6**, configurable from **1-8**.
   When changing concurrency, retry, or network recovery, check
   `DownloadParallelism.kt`, `AudioDownloadManager.kt`, and `GlobalDownloadManager.kt`.
5. Changes to migration, delete semantics, resume checkpoints, or sidecar writes
   must update or add unit tests.

#### 8. Modify Lyricon integration

1. The integration entry point is `core/lyricon/LyriconManager.kt`.
2. The setting key is `lyricon_enabled`, and playback lifecycle keeps it in sync.
3. Lyrics use `LyricEntry`; word-level data comes from `WordTiming`, and
   translations are matched to original lines by timestamp tolerance.
4. Keep Lyricon, SuperLyric, status-bar lyrics, advanced Now Playing lyrics,
   and external Bluetooth lyrics compatible when changing lyric structures.

#### 9. Modify Listen Together

1. Android client logic is under `listentogether/`.
2. Server logic is under `np-submodule/NeriPlayer-LTW`.
3. Protocol field changes must stay compatible across the Android client and Worker,
   and tests must be updated.
4. Settings support custom server URLs and availability tests. Do not hard-code a single server.

---

### Debugging & Logs

- Enable Developer Mode by tapping the **version number** 7 times in Settings.
- A standalone `Debug` tab appears after enabling it.
- Regular file logging is enabled only in Developer Mode.
- Crash logs are written independently by `ExceptionHandler` / `NativeCrashHandler`.
- The Debug tab contains YouTube, Bili, NetEase, Search, and Listen Together probes,
  plus regular log and crash log viewers.

Common command:

```bash
adb logcat | findstr NeriPlayer
```

Linux / macOS:

```bash
adb logcat | grep NeriPlayer
```

---

### Testing & PR

Before submitting, consider at least these checks:

1. Debug build:
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. Unit tests:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
3. If you changed resources, UI, navigation, settings, sync, or storage logic:
   ```bash
   ./gradlew :app:lintDebug
   ```
4. If you changed Compose UI, permissions, Activity, or login flows:
   ```bash
   ./gradlew :app:connectedDebugAndroidTest
   ```
5. If you changed the Listen Together Worker:
   ```bash
   npm ci --prefix np-submodule/NeriPlayer-LTW
   npm run check --prefix np-submodule/NeriPlayer-LTW
   ```
6. Add unit tests under `app/src/test/`.
   Add device or Compose UI tests under `app/src/androidTest/`.
7. If behavior changes affect README, settings copy, user flows, or sync formats,
   update documentation in the same PR.

Existing focused tests cover areas such as:

- YouTube login, challenge parsing, PoToken, playback, Range/Seek policy, and prefetching
- NetEase lyrics, local smoke tests, auto source switching, and playback response parsing
- Download metadata, naming, directory migration, `.nomedia`, delete semantics, and startup recovery
- GitHub/WebDAV sync serialization, deletion policy, playback-stat merging, and upload retry
- Listen Together base URL validation, playback sync planning, session cancellation, and protocol validation
- Lyrics UI, word timing, external Bluetooth lyrics, playback sound controls, and playback policies
- Config backup, generated settings, security guards, crash log files, and safe-mode behavior

PRs should include:

- Motivation
- Key implementation details
- Risks and compatibility impact
- Test steps
- Screenshots or recordings for UI changes

Do not commit:

- APKs, signing files, or local IDE config
- Caches, logs, or temporary build outputs
- Auth cookies, tokens, full config backups, or personal data

Commit messages should follow Conventional Commits when possible, for example:
`feat: ...`, `fix: ...`, or `docs: ...`.

---

### Legal & License

- This project is for learning and research purposes only. Do not use it for illegal purposes.
- This project is licensed under **GPL-3.0**.
- By submitting contributions, you agree to distribute your changes under GPL-3.0.

---

### Communication

- [Issues](https://github.com/cwuom/NeriPlayer/issues): bugs, feature requests, and discussions
- [README_EN.md](./README_EN.md): features and usage
- [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md): community code of conduct

If you plan a large structural change, open an Issue first to align direction.
