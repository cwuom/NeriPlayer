[English](./CONTRIBUTING_EN.md) | [中文](./CONTRIBUTING.md)

## Contributing to NeriPlayer

Thank you for contributing to NeriPlayer. This document describes the current Android client and Listen Together Worker implementation. Keep it in sync with the source code and runtime behavior.

---

### Scope

- NeriPlayer is a native Android audio player, not a public cloud music library.
- Online content comes from NetEase Cloud Music, Bilibili, and YouTube Music.
- Playback metadata and lyrics completion use NetEase and QQ Music, with LRCLIB, Kugou, and AMLL TTML as external lyrics sources.
- Data is stored locally by default.
- GitHub / WebDAV sync is optional. It syncs metadata such as playlists, favorites, recent plays, and playback stats, not media files.
- The Listen Together server lives in `np-submodule/NeriPlayer-LTW`, built on Cloudflare Workers and Durable Objects.

---

### Documentation Map

| Document | Purpose |
| --- | --- |
| [README](README_EN.md) | For users and new contributors: scope, key features, feature boundaries, installation and builds, sync, privacy. |
| [Implementation notes](docs/implementation_EN.md) | Build versions, the 14 owned libraries, entry points and navigation, playback and caching, data sources, downloads and backup. |
| [Contribution guide](CONTRIBUTING_EN.md) | For developers: module boundaries, extension paths, tests, PR requirements. |
| [Kotlin helpers](docs/kotlin-helpers_EN.md) | Usage and boundaries of helpers for request generations, units, player reads, and coroutine results. |
| [Music server API](docs/music-server-api.md) | **Preview feature, not sufficiently tested.** HTTP endpoints, authentication, source identity, and cache contracts (Chinese). |
| [Quality gates](tools_pub/quality/README_EN.md) | Scope, commands, and verification limits of the CRAP, source ownership, and dependency checks. |
| [Native source licensing](modules/native/src/main/cpp/README.md) | Alternative-license scope of NeriPlayer-owned native source, third-party exclusions, and the dual-license statement for external contributions. |
| [Native USB host gate](modules/native/src/main/cpp/tests/usb/config/host-gate-contract.md) | USB host gate, CI coverage, and real-device verification boundary. |
| [USB test corpus](modules/native/src/main/cpp/tests/usb/corpus/README.md) and [fixtures](modules/native/src/main/cpp/tests/usb/fixtures/README.md) | Public corpus and fixtures use synthetic or auditable sources only. Real-device evidence stays in the private tree. |
| [Listen Together server](https://github.com/TheSmallHanCat/NeriPlayer-LTW#readme) | For server deployers: Worker API, event model, deployment, local checks. |

Documentation conventions:

- Update the README when a behavior change affects what users see.
- Update this guide when a change affects extension paths, testing, or module boundaries.

---

### Development Environment

| Component | Requirement |
| --- | --- |
| Android Studio | Latest stable version |
| JDK | Compile target 17; Android 16 JVM tests in `:app` and `:playback:runtime` need 21 |
| Kotlin | 2.4.10, JVM target 17 |
| AGP | 9.4.1 |
| Gradle | 9.6.1 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 28 |
| NDK | `27.0.12077973` |
| CMake | `3.22.1`, set by the Android build script |
| Node.js | 22, for Listen Together Worker checks |
| Version name format | `<git_short_hash>.<MMddHHmm>` |
| Release APK filename | `NeriPlayer-<versionName>[-abi].apk` |

Environment notes:

- The repository uses Git submodules. Clone with `--recursive`, or run `git submodule update --init --recursive`.
- The build script reads the Git short commit hash for the version name, so Git must be installed.
- `build-logic` targets Java / Kotlin 17. `ksp-annotations` and `ksp-processor` use JDK 17 toolchains, so keep JDK 17 installed.
- The Android 16 Robolectric sandbox in `:app` and `:playback:runtime` needs JDK 21.
- For the full test suite or `verifyModularization`, set the Gradle JDK / `JAVA_HOME` to 21 and keep a Gradle-discoverable JDK 17. Production bytecode still targets 17.
- Dependency versions are managed in `gradle/libs.versions.toml` and each module's `build.gradle.kts`.
- The app keeps only `zh` and `en` resources, via the locale filter in `build-logic`.

---

### Quality Guardrails

NeriPlayer covers a broad feature surface. Protect these paths first before submitting.

- **Playback**: `PlayerManager`, playback resolution, cache, URL refresh and fallback, long-audio progress memory, BilibiliSponsorBlock skipping, state recovery, loudness normalization, channel balance, high-resolution output, the USB exclusive native path, startup watchdogs, foreground / background health audits.
- **Downloads**: `AudioDownloadManager`, `GlobalDownloadManager`, `DownloadTaskStore`, `DownloadLifecyclePolicies`, `ManagedDownloadStorage`, resume checkpoints, sidecar files, queue recovery, cancellation cleanup, SAF migration.
- **Sync**: GitHub / WebDAV three-way merge, deletion records, playback stats, missing-field snapshot cleanup, JSON / ProtoBuf / Base64 compatibility, WebDAV concurrency protection.
- **Local data**: playlist Room transactions, legacy JSON upgrade and atomic fallback, local metadata hydration, config import / export, encrypted auth storage, DataStore settings.
- **Lyrics and Now Playing UI**: `AdvancedLyricsView`, `SyncedLyricsView`, `LyricShareSheet`, phonetic lyrics, Japanese lyric translation spacing, long-press lyric sharing, the full-screen Lyrics page.
- **Navigation and glass UI**: `MainTabLayerHost`, drawer and coherent detail feedback, interruptible main-tab switching, page-state retention, standardized Snackbar overlays, Advanced Glass owner handoff.
- **Themes and info sheets**: Material / Clean preview styles, light / dark switching, predictive-back cancellation, song encyclopedia identity checks, request generations, optional-section fallback.
- **System entry points and desktop shell**: `LauncherShortcuts`, home-screen widgets, the `USB_DEVICE_ATTACHED` toggle, playback-service control entry points.
- **Storage and cache UI**: `StorageUsageAnalyzer`, cache cleanup options, download directory indexes, SAF snapshots.
- **Listen Together**: Android client, Worker protocol fields, role permissions, queues, version-gated updates, session-candidate sharing toggle, controller-offline recovery.
- **Diagnostics**: safe mode, JVM / native crash logs, ANR capture, Debug probes.
- **Local persistence**: batched playback and traffic stat writes, lifecycle flushes, atomic file replacement, local-playlist / SAF initialization readiness.

Tests live in the `src/test/` and `src/androidTest/` directories of app and library modules. When changing these paths, search for neighboring tests first, then add coverage.

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
3. Install it on a device:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. First launch shows the disclaimer and onboarding. Notification and local-music permissions are explained first and requested only after the user taps; both can be skipped.
5. For debug access, tap the **version number** 7 times in Settings. A standalone `Debug` tab appears in the bottom bar.

---

### Release Build

Release builds enable minification and resource shrinking. A plain `assembleRelease` packages `arm64-v8a` only. Multi-ABI output needs an extra Gradle property.

1. Provide signing config in `~/.gradle/gradle.properties`, project Gradle properties, or command-line `-P` properties:
   ```properties
   KEYSTORE_FILE=/absolute/path/to/neri.jks
   KEYSTORE_PASSWORD=your_store_password
   KEY_ALIAS=key0
   KEY_PASSWORD=your_key_password
   ```

   Signing rules:

   - A relative `KEYSTORE_FILE` is resolved against the `app/` module directory.
   - Release builds do not fall back to the debug signing config.
   - Local Android Studio / IntelliJ builds automatically allow unsigned Release packaging.
   - CLI and CI builds require a usable keystore.
   - GitHub PR builds automatically produce an unsigned Release for packaging validation. Other CI / PR environments can pass `-PallowUnsignedRelease=true`.

2. Build the default Release APK:
   ```bash
   ./gradlew :app:assembleRelease
   ```

3. Build multi-ABI Release APKs:
   ```bash
   ./gradlew :app:assembleRelease -PbuildAllReleaseAbis=true
   ```

4. Artifacts are written to `app/build/outputs/apk/release/`:
   ```text
   NeriPlayer-<git_short_hash>.<MMddHHmm>[-abi].apk
   ```

Security requirements:

- Never commit keystores, passwords, cookies, tokens, or other sensitive data.
- Do not paste full authorization data in Issues or PRs.
- Config exports exclude login state by default (`includeLoginState=false`).
- With login state included, the export carries platform authorization and GitHub / WebDAV sync credentials. Never upload it publicly or attach it to tests.

---

### Project Layout

#### Root modules

The [implementation notes](docs/implementation_EN.md#module-layout) hold the complete module map.

- `gradle/owned-modules.txt` registers all 14 owned libraries. Builds, lint, and structure checks read the same registry.
- Coverage is collected only for libraries with Kotlin / Java production sources.
- `:native` uses independent host tests and four-ABI compilation.
- Top-level directories express business domains, and remote sources are consolidated in `:platform`.
- Playback and downloads each have a rules library and a runtime library.

Library responsibilities:

- **Infrastructure**: `:common`, `:network`, `:model`, `:database`.
  - Models do not depend on project implementations.
  - The database owns historical Room schemas and the full upgrade chain.
- **Native**: `:native` owns the CMake build boundary, crash handling, USB protocols and PCM calculations, exclusive transport, and third-party libusb.
  - App consumes its AAR.
  - Kotlin playback sessions and the JNI bridge stay in `:playback:runtime`.
- **Playback**: `:playback:logic` holds policy / runtime / audio / queue. `:playback:runtime` owns the engine, service, USB, source resolution, and integration.
  - The nine policy leaf packages keep narrow dependencies, and USB policy stays in runtime.
  - Downloads are reached only through `PlayerDownloadAccess`.
- **Downloads**: `:download:logic` owns reusable rules and storage helpers. `:download:runtime` owns Room, SAF, recovery, and services.
  - Rules do not depend on runtime.
  - Worker and JobService class names stay stable.
- **Lyrics**: `:lyrics` owns parsing, transforms, shared time offsets, and lyric output.
  - `lyrics/lyricon` holds the SDK and position feeds.
  - `lyrics/output` manages async request generations, cancellation, and offset snapshots.
  - The player gets lyrics and snapshots through a narrow loading port. Lyrics do not depend on platform or player implementations.
- **Platform**: `:platform` owns protocols, accounts, caches, and business logic for Bilibili, NetEase, YouTube, and the Subsonic self-hosted music server (preview, not sufficiently tested), plus comments, lyric sources, metadata search, matching, and fallback.
  - `api` is the protocol responsibility inside the library. It must not read account repositories, Room, or the player.
  - Each platform keeps its own package dependency boundaries.
  - YouTube JS assets and consumer R8 rules stay here. Parsing is exported through `api(:lyrics)`.
- **Local data**: `:local` groups settings, media, playlists, statistics, backup, traffic, and sync adapters.
  - It owns business mappings, host interfaces, and WorkManager adapters.
  - Settings schema runs KSP here, and screen rendering stays in app.
- **Sync**: `:sync` owns GitHub / WebDAV transport, encrypted credentials, device identity, causal counters, sessions, sanitization, merging, and Worker policies.
  - Transport and calculation packages have separate dependency allowlists.
  - Local repository adapters live in `:local`.
  - Deletion records and mutation versions commit atomically.
- **Listen Together**: `:listentogether` owns protocol, transport, identity rules, and client sessions.
  - Protocol code uses only models, serialization, and the standard library.
  - Host interfaces provide playback, resources, and Android services.
- **App and tooling**: `:app` owns Android entry points, Compose screens, and dependency assembly.
  - KSP tools, the upstream lyrics submodule, `build-logic`, and the Listen Together server are outside the 14-library count.
  - The vendored Miuix source and docs tree is outside the app build.

Dependency rules:

- Libraries must not reference app, screens, or `AppContainer`, and dependencies must be acyclic.
- `:playback:runtime` may use its own `PlayerManager`.
- Settings are read through providers. The host injects HTTP clients, accounts, and device tokens, with no bypass through a global container.
- Room business mappings stay with local or platform repositories. Database and rule components do not depend on runtime implementations.

Build and test conventions:

- Libraries with Kotlin / Java production sources use the `build-logic.android.feature-library` convention.
- The pure-native `:native` uses `build-logic.android.library`, without JaCoCo or JVM tests.
- Owned libraries register their real Gradle path in `gradle/owned-modules.txt`. No separate JVM coverage list is needed.
- Tests stay with the implementation in `src/test` / `src/androidTest`. Host integration tests live in app.
- Moving a module directory does not change production package or class names. Source packages match directories, and path contracts, fixtures, resources, and CRAP selectors are updated together.

`verifyModuleBoundaries` checks:

- registration against actual directories
- domain dependencies and cycles
- forbidden imports
- source ownership and package-directory alignment
- library main sources under 2000 lines
- at most 16 direct source files per directory in libraries and `APP_FAMILIES` areas

Package-level calculation domains are isolated by the compiled-bytecode gate.

#### Android client key paths

**`app/src/main/java/moe/ouom/neriplayer/NeriPlayerApplication.kt`**

- Application entry point. Handles language, crash handling, `AppContainer`, Lyricon, global download management, and the shared image loader.

**`app/src/main/java/moe/ouom/neriplayer/activity/`**

- `MainActivity.kt` is the main UI and the audio-share / deep-link entry. It handles safe mode, startup, the disclaimer, onboarding, external audio import, Listen Together deep links, and the top-level Compose host.
- `activity/car/BluetoothMediaValidationActivity.kt` is the system Bluetooth media-capability validation entry.
- USB attach goes through an Activity alias to `MainActivity`.
- The car browser service and artwork provider are declared in the playback runtime Manifest. Each entry point maintains its own permissions and caller boundaries.
- Platform login activities live in `activity/auth/` and run in a separate secondary process.
- `activity/sync/` stores Activity-side sync warning state.
- `UsbDeviceAttachHandling.kt` enables or disables the USB attach Activity alias from settings. The playback service reuses the same policy to filter `USB_DEVICE_ATTACHED` broadcasts.
- `NeteaseWebLoginActivity.kt`, `NeteaseQrLoginActivity.kt`, `BiliWebLoginActivity.kt`, `BiliQrLoginActivity.kt`, and `YouTubeWebLoginActivity.kt` are the in-app platform sign-in pages.

**`app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt`**

- Top-level Compose app shell. Handles `NavHost`, the dynamic bottom bar, `MiniPlayer`, the `Now Playing` overlay, Debug routes, themes, cache cleanup, and playback service sync.
- `MainTabLayerHost.kt` keeps outgoing and incoming main-tab scenes, runs interruptible horizontal transitions in tab order, and preserves saveable state and a glass owner for each scene.
- `ui/theme/NeriTheme.kt` switches Material / Clean through one content host. Theme changes do not rebuild navigation.
- `NeriDesign.kt` provides style geometry, and `CleanTheme.kt` provides Clean colors and typography.
- `AppGlobalDialogHosts.kt` shows the global download traffic confirmation, and `globalDialogVisible` tells Now Playing and the More sheet to make room.
- `ui/feedback/` holds the app-wide Snackbar / Toast feedback policy. Before adding global feedback, check whether `AppFeedback` and `ViewSnackbar` already cover it.

**`app/src/main/java/moe/ouom/neriplayer/ui/component/lyrics/`**

- `AdvancedLyricsView.kt` and `SyncedLyricsView.kt` handle advanced lyric layout, word / character highlighting, translation and phonetic display, tap-to-seek, and long-press callbacks.
- `LyricShareSheet.kt` handles lyric line selection, copy, song sharing, and lyric card generation.
- LRC / YRC / TTML parsing and translation alignment live in the `lyrics.parser` package of `modules/lyrics`.
- Shared lyric data lives in the `lyrics` package of `:model`.
- The old `AppleMusicLyric` name exists only as an `@Deprecated` wrapper in `ui/component/LyricsCompatibility.kt`. New code uses `SyncedLyricsView`.

**`app/src/main/java/moe/ouom/neriplayer/ui/component/playback/`**

- `NeriMiniPlayer.kt` handles the bottom Mini Player, play / pause, and horizontal swipe to change tracks.
- The sleep-timer sheet lives in the same directory.
- The audio effects and speed settings page is in `ui/screen/tab/settings/audio/`.
- Same-named files in the `ui/component/` root are legacy package entry points. Put new implementations in responsibility subpackages such as `lyrics/`, `playback/`, `download/`, and `navigation/`.

**`app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/library/`**

- `LibraryScreen.kt` handles the top-level Library categories.
  - Local content switches between playlists and artists.
  - Favorites shows playlists, followed artists, and Hot playlists.
  - It provides the self-hosted music server entry (preview).
- `LocalArtistLibraryGrid.kt` shows the local artist grid, empty state, and artist cards.

**`app/src/main/java/moe/ouom/neriplayer/ui/screen/playlist/`**

- `LocalArtistDetailScreen.kt` shows local artist details with play-all, multi-select, playlist export, and batch download of songs that can be resolved online.
- `curation/` is batch lyrics and artwork curation.
  - `LocalCurationCoordinator` owns the process-wide session and reaches side effects through `LocalCurationPorts`.
  - The production implementation `AppLocalCurationPorts` calls `PlayerManager.embedLocalSongAssets`.
  - File writes run in parallel outside the global metadata lock, and writes to the same file are queued per target by the local media layer.
  - Under the lock, lyric and artwork fields are merged into the latest copy only.
  - `LocalCurationPolicy` holds the unit-tested planning, auto-selection, and size estimation rules.
- File-side embedded asset inspection, artwork compression, folder artwork lookup, and media write consent live in `data/local/media/curation/` of `modules/local`.
- Timeline, live-version, and LRC tag weighting live in `LyricMatchSupplementalSignals.kt` of `:platform`.

**`app/src/main/java/moe/ouom/neriplayer/ui/screen/artist/`**

- NetEase artist detail screen: artist info, hot songs, paged albums, and follow state.

**`app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/artist/`**

- NetEase artist summaries, JSON parsing, and detail screen state management.

**`app/src/main/java/moe/ouom/neriplayer/ui/onboarding/`**

- First-run onboarding: language, platform accounts, permission guidance, playback controls, GitHub sync, and personalization.

**`modules/platform/src/main/java/moe/ouom/neriplayer/platform/`**

- `netease/api`: NetEase client, crypto, request parameters, and QR authentication protocol.
- `bilibili/api`: search, QR login, favorites, collections, and playback info. Repositories and skip rules live in `platform/bilibili`.
- Explore link recognition keeps part, `cid`, and `season_id` context. When changing it, check `ExploreLinkRecognizer` and `ExploreViewModel`.
- `youtube/api`: YouTube Music client, PoToken, JS Challenge, and request / response parsing. Protocol models live in `:model`. Auth persistence and caches live in `platform/youtube`.
- `lyrics/api`: LrcLib, Kugou, and AMLL service access. Source repositories, matching, and fallback live in `platform/lyrics`.
- `search/api`: `SearchApi` and the NetEase and QQ metadata search services. `SearchManager` lives in `platform/lyrics`, and shared music models belong to `modules/model`.
- `netease/encyclopedia`: song encyclopedia source, parsing, and a bounded memory cache. Shared models live in `data/model/encyclopedia` of `:model`. Screens and request state stay in app.
- `AppContainer` assembles clients and routing and injects HTTP, debug configuration, and live settings providers. Libraries do not read the app container or the player singleton.
- Plain-text lyric timeline conversion lives in `PlainLyrics.kt` of `modules/lyrics`, shared by the player and the matcher.

**`modules/platform/src/main/java/moe/ouom/neriplayer/platform/comments/`**

- Comment sources, parsing, pagination, and caches.
- Comment data contracts belong to `:model`.
- `AppContainer` assembles and injects client providers and caches, and the host decides cache lifetime.
- Repository tests live in this module. ViewModel integration tests live in `app`.
- Song source tags belong to `:model`.
- Bilibili legacy playback identity resolution lives in `platform/bilibili/playback/resolver` and does not reference the player singleton.

**`modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/`**

- `PlayerManager.kt`: unified Media3 ExoPlayer management layer for source resolution, queue, cache, state recovery, failure retry, and playback policy.
- `service/AudioPlayerService.kt`: foreground playback service, media notification, MediaSession, and media buttons.
- Download execution belongs to `:download:runtime`. The player consumes only `host/PlayerDownloadAccess.kt`, and download state contracts live in `playback/storage` of `:model`.
- `effects/PlaybackEffectsController.kt`: speed and pitch.
- `engine/`: Media3 renderers and data source assembly.
- `engine/datasource/ConditionalHttpDataSourceFactory.kt`: adds headers for specific domains.
- `timer/SleepTimerManager.kt`: sleep timer.
- `metadata/`: playback-page data handling for lyrics, metadata, and external Bluetooth lyrics.

**Audio effects (`audio/effects/`)**

- `AudioEffectsAudioSink` sits in front of the USB / system output sink and runs the `dsp/` engine of `:native` on 16 / 24 / 32-bit integer and float PCM.
- When neutral or off, audio passes through unchanged and offload stays allowed.
- USB exclusive output bypasses it by default to stay bit-perfect.
- Effect configuration, built-in presets, codecs, and DSP parameter mapping live in `playback/effects` of `:model`. Parameter indices match `dsp/neri_dsp_params.h`.

**Runtime and audio processing in `:playback:logic`**

- PCM channel balance, loudness normalization, and audio visualization live in `audio/processing` and `audio/reactive`.
- `runtime/stats`, `runtime/progress`, `runtime/transport`, and `runtime/quality` handle stat collection, playback progress, transport, and quality control. The host provides side effects through the matching Ports.
- Playback commands and queue advancement live in `playback/PlayerManagerPlaybackExtensions.kt`.

**Queue state (`queue/state` and `queue/policy` in `:playback:logic`)**

- The two packages own state and editing / navigation rules. Queue data contracts live in `playback/queue` of `:model`.
- `PlayerQueueSnapshot` holds the list and current index.
- `PlayerQueueSessionSnapshot` combines the queue, shuffle mode, and restore order into one session, published by `PlayerQueueStateStore`.
- Session entry points update the queue and restore data in one transaction:
  - `startPlayback`: start a new playlist.
  - `setLocalShuffle`: toggle local shuffle.
  - `restoreSession`: load a persisted session.
- Queue update entry points:
  - `publishCurrentQueue`: remote whole-queue update.
  - `updateCurrentQueue`: move, delete, and insert.
  - `updateQueuedSong`: async metadata writeback. It updates the latest queue by song identity and keeps the current selection.
- These operations compute from the latest snapshot inside the lock. Player and disk operations stay outside it.
- UI reorders supply order only, and song content comes from the latest queue. When duplicate songs cannot be matched exactly, the stale request is rejected and the UI refreshes.
- When restoring the pre-shuffle order, the current queue is kept if the song set changed or copies are ambiguous.
- Clearing the queue also clears the restore order.
- Persistence reads one session snapshot, so shuffle state from different versions is never mixed.
- The thread boundary of the current-song Flow and Media3 side effects needs its own check.
- `session/PlayerQueueSessionBindings` adapts new-engine initialization, local / remote playback requests, and persisted restore.
- `verifyDomainDependencies` checks direct JVM dependencies of the queue calculation packages, which must not reference this host adapter.
- Shuffle display state uses `PlayerQueueDisplayState`. A shuffled display queue does not fall back to implicit index-remapping semantics.

**State persistence (`runtime/persistence/PlaybackStatePersistenceCoordinator.kt`)**

- Owns save requests and delayed tasks.
- At the synchronous event entry, call `prepareStatePersist` or `scheduleStatePersist` to capture a full snapshot and issue a request before waiting for stat writes or other async work.
- Writes run serially. Requests superseded while queued are not written.
- `PlaybackStateWriter` confirms only backends that actually succeeded.
- After a JSON fallback, Room is fully rewritten before incremental saving resumes.
- Coroutine cancellation propagates upward.
- Releasing the player closes the request entry.
- Storage completion callbacks do not modify current playback state.
- `RestoredPlaybackState` expresses three states: no restore, paused progress kept, and pending auto-resume. Restore progress and flags are not modified independently.

**URL refresh (`runtime/refresh` in `:playback:logic`)**

- Active state is owned only by `RefreshInFlightController`. The request contract and side-effect gating live in the same package.
- Requests with identical parameters reuse the active task.
- Writeback, completion, and cancellation use the task's `RefreshRequestHandle` identity, so an old task cannot affect a new task with identical parameters.
- The watchdog reads the controller directly and keeps no separate "refreshing" flag.
- Completion cleanup covers lazy coroutines cancelled before they start.
- Playback intent changes through `updateResumePlaybackRequested`, which revokes conflicting refreshes.
- Quality refresh captures current state and creates its request synchronously on the main thread, avoiding cross-thread reads of a newer generation.

**Playback startup, fallback, and compatibility**

- `watchdog/PlayerManagerStartupWatchdogExtensions.kt` and `lifecycle/PlayerManagerLifecycleExtensions.kt`: startup watchdog, foreground / background health audit, failure recovery, and USB exclusive fallback.
- `resolver/netease/PlayerManagerNeteaseAutoSourceSwitch.kt`: switches to Bilibili playback fallback when a NetEase track is restricted, has no playable result, or returns only a preview.
- YouTube Music playback compatibility:
  - `YouTubeGoogleVideoRangeSupport.kt` lives in `platform/youtube/api/transport/range/` of `:platform`.
  - `resolver/youtube/YouTubeSeekRefreshPolicy.kt` and `prefetch/YouTubePrefetchRunner.kt` live in the player.

**Playback state contracts**

- Player state contracts live in the `playback` package of `:model`.
- Pure playback policy belongs to `:playback:logic`.
- Playback presentation adapters and side effects belong to `:playback:runtime` and app.

**USB exclusive (`usb/`)**

- Split into `device/`, `path/`, `session/`, `sink/`, `system/`, and `transport/` for sessions, the native bridge, runtime snapshots, and recovery control.
- Supports **UAC1.0** and compatible **UAC2.0 Type I PCM** devices.
- Covers 32-bit PCM, software PCM-float conversion, UAC2 explicit feedback, coordinated AudioSink reconfiguration, dynamic transfer scaling, and backpressure stall recovery.

**`modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/download/`**

- `GlobalDownloadManager.kt` maintains global download tasks and the downloaded song list.
- `ManagedDownloadStorage.kt` is the facade for the app directory and SAF directory. Its implementation is split into `commit/`, `delete/`, `lookup/`, `migration/`, `recovery/`, `snapshot/`, `tree/`, and `working/` under `storage/`.
- `task/DownloadTaskStore.kt` manages in-memory task presentation, status, progress, and attemptId.
- `execution/persistence/DownloadExecutionRoomStore.kt` persists download operations and recovery state in Room.
- `policy/DownloadLifecyclePolicies.kt` centralizes recovery, cancellation cleanup, and fast-settle policies.
- `naming/ManagedDownloadNaming.kt` manages filename templates and legacy naming compatibility.
- `metadata/DownloadedAudioTagWriter.kt` writes audio tags.
- `catalog/` manages reads, writes, and projections of the downloaded song catalog.

**`app/src/main/java/moe/ouom/neriplayer/core/startup/`**

- Startup stages and decisions are split into `app/`, `crash/`, `download/`, `logging/`, `permission/`, `player/`, `safemode/`, `sync/`, and `theme/`.
- `MainActivity` only coordinates these components with the UI lifecycle.

**`modules/local/src/main/java/moe/ouom/neriplayer/data/`**

- `identity/`: song identity conversion. `SongIdentity` and `SongItem` belong to `modules/model`.
- `settings/`: `DataStore` settings, KSP schema, and preference mapping. Snapshot contracts live in the `settings` package of `:model`.
- `auth/`: shared Web login state and the YouTube credential rotation Worker. Platform cookie / auth repositories live in `data/auth` and `platform/youtube/auth` of `:platform`.
- Platform caches belong to `:platform`, local playlist orchestration to `:local`, and shared cache table schemas to `:database`.
- `storage/`: storage usage analysis, cache grouping, and extra cache cleanup.
- `local/playlist/`: local playlist Room reads and writes, legacy JSON upgrade and atomic fallback, system playlist compatibility, background metadata hydration, and local artist aggregation.
- `local/audioimport/`, `local/media/`: local audio import, fast scan, background metadata hydration, cover fallback, and sharing.
- `playlist/favorite/`, `playlist/usage/`: favorite playlists, followed artists, and Home continue-listening data.
- `history/`, `stats/`: recent plays, playback stats, and day / week / month / year / all-time aggregation.
- `backup/`: local playlist JSON backup, import, and diff analysis.
- `config/`: full config import / export.
- `sync/host/`: Android repository snapshots, resource resolution, and persistence adapters. `AndroidSyncMergeHost` supplies system-playlist identities and messages, and session and merge components call it through interfaces.
- `sync/cover/`: cover mapping persistence and legacy JSON import. A failed import keeps the old data and blocks later cleanup.
- `sync/github/`, `sync/webdav/`: provider backends and compatible Worker entry points.
- `sync/work/`: WorkManager, network validation, and notification adapters.
- App has no production `data/sync` sources.

**`modules/sync/src/main/java/moe/ouom/neriplayer/`**

- `api/sync/`: GitHub / WebDAV transport, response reading, and conditional writes.
- `data/sync/store/`: encrypted credentials, preferences, device identity, causal counters, and deletion state.
- `data/sync/merge/`: merge entry points, host contracts, conflicts, ordering, and stats rules under `engine`, `host`, `playlist`, `song`, `history`, and `stats`.
- `runtime/`, `codec/`, `sanitize/`, `change/`, `mapping/stats/`, `remote/`, `retry/`, and `schedule/` under `data/sync/`: sessions, compatible codecs, sanitization, change detection, stat mapping, remote protection, retries, and Worker policies.
- Sync payload and conflict models live in the sync package of `:model`.
- `verifyDomainDependencies` sets separate allowlists for the transport and calculation domains and rejects direct references to Android repositories or the player.

**`modules/listentogether/src/main/java/moe/ouom/neriplayer/api/ltw/`**

- HTTP, WebSocket, server URL validation, and reconnect policy, built on protocol models and an injected HTTP client.

**`modules/listentogether/src/main/java/moe/ouom/neriplayer/data/ltw/`**

- `ListenTogetherSessionManager` assembles session components, and `playback/` owns the queue, authoritative playback candidates, and progress sync.
- `control/`, `session/`, `invite/`, `mapping/`, and `validation/` own control, session, invite, song mapping, and input boundaries.
- Room, event, and transport models live in the `ltw` package of `modules/model`.
- `api/ltw` owns transport, and `listentogether/protocol` owns bounded reads and message codecs.
- App's `core/di/ltw` supplies platform interfaces, and the player's `core/player/ltw` supplies player and song mapping interfaces.
- `app/listentogether` has no production sources.

**`modules/native/src/main/cpp/`**

- `crash/`: native crash handling.
- `dsp/`: PCM effects engine. Its host tests live in `tests/dsp/` and run separately from the USB host gate.
- `cmake/`: shared compile options.
- `usb/` is split into `control/`, `exclusive/`, `feedback/`, `iso/`, `pcm/`, `uac1/`, and `uac2/`, with host tests in `tests/usb/`.
- `third_party/libusb/`: third-party libusb and its Android configuration.
- CMake targets link by responsibility, and the `:native` AAR supplies `lib_neri.so`.

**`modules/lyrics/src/main/java/moe/ouom/neriplayer/lyrics/lyricon/`**

- Lyricon integration (provider) and SuperLyric output, syncing song, playback state, position, word-level lyrics, and translations.
- `lyrics/output/` owns the output controller, async request generations, cancellation, and offset snapshots. Position anchors and coordinators are internal.

**`app/src/main/java/moe/ouom/neriplayer/navigation/`**

- `LauncherShortcuts.kt` maps app-icon shortcuts to navigation / playback requests.

**`app/src/main/java/moe/ouom/neriplayer/widget/`**

- Home-screen playback widget providers, state snapshots, artwork-derived visuals, and RemoteViews updates.

---

### Current Boundaries

#### Home, search, and platforms

- `Explore` combines NetEase curated playlists, YouTube Music playlists, and platform-specific NetEase / Bilibili / YouTube Music search. It does not do mixed search.
- In the default Chinese mode, `Home` shows local continue-listening, all available NetEase recommendation sources, and Radar playlists. International mode prioritizes YouTube Music home shelves.
- The QQ Music entry in `Library` is still a placeholder.
- Local artist categories are aggregated from the display artists of imported or saved local songs. They are not an online artist directory.
- NetEase artist detail depends on NetEase artist metadata and endpoints.
- Follow state for NetEase, Bilibili, and YouTube creators is stored in local favorites, grouped by platform.
- Remote follows from NetEase and YouTube Music are pulled manually and merged in bulk only after all pages load and the account is unchanged.
- The merge keeps existing favorites and their order, does not restore follows cancelled during loading, and does not write follows back to the platform.
- Bilibili supports search, favorites, audio playback and download, plus comment reading, nested replies, copying, and, after login, likes, posting, and replying.
- Bilibili link recognition supports selected parts, collection shares, and `season_id` context.
- YouTube Music supports login, anonymous playback, home and playlist browsing, details, search, and playback compatibility.
- Valid YouTube identity cookies are kept and rotated.
- YouTube playback resolution reuses bootstrap, `player.js`, PoToken, and challenge-result caches, and falls back to EJS / HLS when the signature or playback candidate fails.
- Cache hits and local tests do not prove stability on real accounts and networks.
- Large seeks in long audio use a shorter startup-recovery window. Real-network behavior needs separate verification.

#### Lyrics and visuals

- Status-bar lyrics rely on private vendor capabilities and work only on some devices.
- The `RuntimeShader` fluid / audio-reactive background and advanced blur are enabled on Android 13+ only. Cover blur supports Android 9+, so keep the fallback path for older versions when changing animations.
- Phonetic lyrics come from platform-provided phonetic lyrics or the phonetic field of embedded word-level lyrics. Without phonetic data, nothing is synthesized and no empty second line is shown.
- Lyric sharing sends lyric card files from the cache directory through `FileProvider`. These files are cleanable cache, not user downloads.
- The Lyricon / SuperLyric position feed pushes every 200 ms on its own, anchored to elapsed realtime.
- When changing the player progress interval, check foreground / background lyric timing and SuperLyric alignment.

#### Playback fallback and cache

- NetEase playback automatically tries lower qualities when the current one is unavailable.
- Bilibili and local-audio fallbacks are off by default. Once the user enables them in onboarding or settings, they are tried when a track is restricted, has no playable result, or returns only a preview.
- The NetEase playlist detail cache serves fast display and failure fallback on the playlist detail page. Album details always refresh live.
- Local "My Favorite Music" can sync recognizable NetEase songs to NetEase Liked Songs. It requires NetEase login and skips unsupported or existing songs.
- The streaming cache uses `SimpleCache`.
- Downloads are written by `AudioDownloadManager` and `ManagedDownloadStorage`, a separate feature from the streaming cache.

#### Downloads

- Downloads use a shared `OkHttpClient` and write to the app directory or a SAF directory. They do not use the system `DownloadManager`, and they support automatic resume and startup recovery.
- The download queue, cancellation records, and attemptId all take part in recovery decisions. When changing recovery, make sure an old request cannot clear the task state of a new one.
- Resume depends on the transport type:
  - Direct HTTP resumes through working-file size plus the `Range` header.
  - Platform transports that need explicit Range resume by byte offset.
  - HLS downloads resume per segment from the `.hls.json` checkpoint.
- Working files live in `files/download_staging/`, with `.resume.json` recovery metadata.
- Unfinished downloads are recovered automatically after app start and network recovery.
- Manual cancel rolls back partial output and deletes working files. Network-policy pauses and recoverable-error retries keep the checkpoint.
- Once the audio body is complete, a tag or SAF writable-handle failure completes the download without tags and keeps the saved audio.
- The app-private download directory is usually faster than a custom SAF directory.
- SAF snapshots and indexes reduce directory walks, an empty-directory scan does not overwrite an existing index, and SAF operations cost more than regular file operations.
- When sharing local audio from a managed directory, a readable URI is exposed directly when possible. Content URIs that cannot be shared directly are copied to cache staging.
- Share staging is cleanable cache.
- Normal cache cleanup deletes only regenerable cache and share staging.
- Download working files and resume credentials are managed by download recovery. Downloaded audio, lyrics, and covers are never removed through "clear cache".

#### Sync and data safety

- GitHub / WebDAV sync only metadata, not audio caches, downloaded files, local audio files, cookies, or playback tokens.
- Local and synced playlists use `songOrderVersion` for order semantics: `0` is the legacy order and `1` is the current display order. Legacy data is migrated on read.
- Sync snapshots may come from older JSON / ProtoBuf or malformed remote files, so reads use safe defaults.
- Sync reads drop records without a resolvable song identity, without a valid deletion time, or with an invalid playlist ID.
- Songs missing `addedAt` sort after songs that have timestamps.
- V4 format:
  - Writes a Protobuf record stream, ZSTD content chunks, and a root manifest.
  - Each compressed object is at most 2 MiB.
  - When the manifest is absent, reads legacy `backup.json`, `backup-raw.bin` GZIP, and `backup.bin` Base64.
  - The 8 / 12 / 16 MiB safety limits apply to legacy reads only.
  - V3 is read only for migration, and unknown future versions are rejected.
  - Every syncing device must support V4, and legacy data or V3 migration needs confirmed upgrade first.
- GitHub fast path:
  - Eligible commits publish objects, the manifest, and cleanup together through GraphQL `createCommitOnBranch` with `expectedHeadOid`.
  - Other commits write through Git Data API blob / tree / commit calls, then publish atomically with GraphQL `updateRefs` using `beforeOid` and `force=false`.
  - Base64 in upload requests is only a transport envelope. Repository bodies are raw binary.
  - Reads pin a fixed branch head and use raw content.
- WebDAV concurrency protection:
  - Creates immutable objects and the first manifest with `If-None-Match: *`.
  - Rechecks the manifest fingerprint before every publish.
  - Also sends `If-Match` when a strong ETag exists.
  - Without a strong ETag, publishes only after the fingerprint matches, then reads back to confirm.
- Playback and traffic stats are written in delayed batches:
  - Playback stats flush at key player / Activity lifecycle points.
  - The traffic accumulator flushes when a request or download attempt ends.
  - Playback totals and daily buckets are not evicted by count.
  - Sync merging maintains aggregate totals, daily buckets, and legacy bucket-only payload lifting, and lifts before trimming the window.
- Platform cookies / auth data, GitHub tokens, and WebDAV passwords are stored encrypted with `Android Keystore + EncryptedSharedPreferences`.
- `DataStore` holds regular settings and non-sensitive state only.

#### Playback progress and high-resolution output

- Long-audio progress memory:
  - Applies only to content of at least 15 minutes.
  - Positions under 5 seconds are not saved.
  - Positions within 30 seconds of the end are cleared.
  - An explicit playback position wins.
  - The persisted field is `resumePositionMs`.
  - It is unrelated to third-party platform playback history.
- BilibiliSponsorBlock is off by default. It sends only a SHA-256 prefix of the current BV ID and skips locally.
- BilibiliSponsorBlock stays off during Listen Together, and its public results are not written to sync or room state.
- 32-bit high-resolution system output keeps the high-precision pipeline of regular system output and bypasses loudness normalization, channel balance, audio visualization, and in-app speed. When changing it, check the settings copy and tests together.
- Local scans first return quick metadata, then a background task fills in title, artist, album, and cover. The first scan result is not the final form.

#### USB exclusive

- Needs a compatible **UAC1.0** or **UAC2.0 Type I PCM** DAC, the foreground service, wake locks, and the system background policy.
- The background-permission prompt on the settings page matters for screen-off scenarios. Verify them when changing related logic.
- Bit-perfect output mode:
  - Requests the DAC format from the source sample rate and bit depth.
  - Keeps software gain at 0 dB.
  - Applies no fades in the native pipeline.
  - Leaves volume to the DAC hardware, unlike regular system volume and in-app loudness processing.
- Volume follows regular media volume (`STREAM_MUSIC`), not MediaSession remote volume. The background anchor cannot set an output device, and Android 12+ then switches the media volume device to the speaker.
- USB attach response is a separate setting. When off, both the Activity alias and the playback-service broadcast entry skip `USB_DEVICE_ATTACHED`.
- When a foreground / background USB runtime report returns `native_refresh_deferred`, the player retries with a bounded delay. Other invalid reports stay fail-closed.

#### Listen Together

- With `shareAudioLinks=false`, room snapshots and queues do not expose `streamUrl`.
- Turning the toggle off immediately clears cached session candidates, and `REQUEST_LINK` is rejected outright.
- `streamUrl` is a legacy protocol field name. The Worker is not a media proxy or public distribution entry.
- Repeat / shuffle mode syncs through `PLAYBACK_MODE` / `REQUEST_PLAYBACK_MODE`.
- Member control validates the target stable track key and drops events older than `clientInstanceId`, `clientSequence`, and `clientTimeMs`.
- `REQUEST_SET_TRACK` can only select a track in the current queue.
- The current track keeps at most 3 deduplicated HTTP(S) candidates.
- Listeners resolve with their own quality policy first. Candidates are only a session-scoped fallback and are never written to regular songs or the offline cache.
- Server position is projected from track duration, and single-track repeat wraps by duration.
- Rejoining with the same member credential does not trigger new-member auto-pause.

---

### Extension Paths

#### 1. Add an Explore search source

Use this to bring a new platform into `Explore` search or discovery.

1. Implement the client in the `platform/<platform>/api` package of `:platform`. Put caching and business orchestration in the matching responsibility packages of the same library, and keep the package-level dependency rules between protocol and business.
2. Add request, pagination, and state mapping in `ExploreViewModel`.
3. Add the platform tab and result UI in `ExploreScreen` / host screens.
4. For playback, connect to the `PlayerManager` source resolution chain.
5. For downloads, complete `AudioDownloadManager` and the download metadata mapping.

#### 2. Add a playback metadata completion source

Use this for cover, lyrics, and track info completion.

1. Implement the `SearchApi` interface in the `platform/search/api` package of `:platform`. Shared music DTOs stay in `:model`.
2. Register the singleton in `AppContainer`.
3. Register the route in the `AppContainer.searchManager` provider. Maintain and test matching and fallback rules in the `platform/lyrics` package of `:platform`.
4. Add `MusicPlatform`, string resources, and debug probes as needed.

#### 3. Add an online playback platform

1. Use `platform/bilibili/` or `platform/youtube/` in `:platform` as a reference for the client and playback repository design.
2. For special headers, extend `core/player/engine/datasource/ConditionalHttpDataSourceFactory.kt`.
3. Hook the platform into the URL resolution chain in `core/player/url/` and the matching `resolver/`.
4. Keep downloads, lyrics, covers, and playback stats clearly bounded. Cache and permanent downloads use separate implementations.
5. To sync to NetEase Liked Songs, provide a stable NetEase song ID or a verifiable mapping, and reuse the candidate validation in `LocalPlaylistRepository`.

#### 4. Modify NetEase playback fallback

1. The entry point is the NetEase URL resolution flow in `core/player/url/PlayerManagerUrlExtensions.kt`.
2. Matching and scoring live in `core/player/resolver/netease/PlayerManagerNeteaseAutoSourceSwitch.kt`.
3. The fallback handles only NetEase tracks that are restricted, have no playable result, or return a preview. Do not extend it into cross-platform aggregate search.
4. When changing the matching strategy, consider title, artist, video part, duration tolerance, and cache key stability together.

#### 5. Add a setting

1. Register the key, default value, type, and UI metadata in `data/settings/AutoSettingsSchema.kt`.
2. Simple switches use the KSP-generated `AutoSettingsRepository` and `AutoSettingsSwitchItems`.
3. Settings with side effects, mutual exclusion, permissions, or startup snapshot needs use a handwritten setter.
4. When a setting affects early startup, update the matching snapshot: `BootstrapSettingsSnapshot`, `ThemePreferenceSnapshot`, or `PlaybackPreferenceSnapshot`.
5. Put the UI entry in the matching `SettingsPage` in `SettingsScreen.kt` or under `ui/screen/tab/settings/component/`.
6. Settings page responsibilities:
   - `SettingsDownloadDirectoryPreflight.kt`: download directory probe timeouts and Provider failure classification. The page only consumes the result.
   - `SettingsNavigationSearch.kt`: page switching and search entry.
   - `SettingsThemeControls.kt`: theme mode and palette options, including switch requests and animation origin decisions.
   - `SettingsPlaybackControlLayout.kt`: dialog state and preference change decisions for playback control position and size.
   - `SettingsPersonalizationContent.kt`: personalization and lyric appearance cards, each subscribing to the settings flow independently.
7. When adding or renaming a setting, also update:
   - localized strings
   - `SettingsSearchIndex.kt` keywords
   - Settings page visibility / filter tests
   - `AutoSettingsGeneratedTest`
8. When a setting controls an Activity alias, the playback service, a system entry point, or pre-startup behavior, verify that generated keys, handwritten setters, startup snapshots, and the real entry point read the same preference.
9. For complex preference models, extract normalization, bounds clamping, and layout math into unit-testable functions instead of handling them implicitly in Compose.
10. Explore search history is stored by `ExploreSearchHistoryRepository`. When `explore_search_history_enabled` is off, Explore hides history and stops recording new entries. Existing entries are kept.
11. Lyric font size has two groups, cover page and lyrics page, with lyrics and translation scaled independently. When changing it, update `SettingsRepository.lyricFontScalesFlow`, `setLyricFontScale(target, scale)`, and the matching preview / playback call sites.

#### 6. Modify USB exclusive playback

1. Read first:
   - `core/player/usb/sink/UsbExclusiveAudioSink.kt`
   - `core/player/usb/transport/`
   - `core/player/usb/session/`
   - `core/player/policy/usb/UsbAudioSinkReconfigurationCoordinator.kt`
   - `core/player/watchdog/PlayerManagerStartupWatchdogExtensions.kt`
   - `core/player/lifecycle/PlayerManagerLifecycleExtensions.kt`
   - related tests
2. The current implementation covers **UAC1.0** and compatible **UAC2.0 Type I PCM** devices. When extending to more complex UAC2.0 topologies or non-Type-I PCM devices, update the docs, boundaries, diagnostics, and compatibility assumptions together.
3. Consider together:
   - device selection, sample-rate / bit-depth policy
   - 32-bit PCM and software PCM-float conversion
   - bit-perfect volume
   - UAC2 clock topology and explicit-feedback endpoints (implicit feedback is not yet a usable candidate)
   - foreground / background buffers, wake locks, background-permission prompts, and the system fallback chain
4. When changing auto recovery, keep-alive, or background audit, verify four paths: foreground playback, screen-off background, USB attach / detach, and system fallback.
5. When changing feedback clocks, long-gap reacquisition, coordinated reconfiguration, dynamic transfer scaling, backpressure recovery, or candidate bit-depth fallback, also check:
   - `UsbExclusiveOutputFormatResolverTest`
   - `UsbExclusivePcmWritePlannerTest`
   - `UsbExclusiveSessionControllerReusePolicyTest`
   - `UsbAudioSinkReconfigurationCoordinatorTest`
   - native USB feedback / PCM / UAC tests
6. Runtime Report v2 fields are parsed fail-closed. When changing the feedback endpoint, state, holdover, recovery action, or generation fields, update the Kotlin parser and boundary tests together.
7. When error semantics or recovery strategy change, update the Settings / Debug diagnostics display and matching tests.
8. When changing USB attach response, check together:
   - `UsbDeviceAttachHandling.kt`
   - `AudioPlayerService.kt`
   - the Activity alias in `AndroidManifest.xml`
   - the `AutoSettingsSchema.kt` setting generation chain
9. Native changes run at least all three host gates plus the four-ABI Android compile. Host model, ABI compile, and real-DAC verification are three independent pass conditions.

#### 7. Modify GitHub / WebDAV sync

1. First understand the compatibility strategy of `data/model/sync/SyncDataModels.kt` and `data/sync/codec/SyncDataSerializer.kt` in `:sync`. Shared payload models live in `:model`, not in the GitHub provider package.
2. Sync data includes playlists, favorite playlists, recent plays, deletion records, playback stats, and independent lyric override records.
3. Format rules:
   - V4 uses a Protobuf record stream, ZSTD content chunks, and a root manifest, and keeps the `neriplayer-sync-v3.manifest` filename.
   - V3 is read only for migration.
   - Legacy JSON, raw GZIP, and Base64 are read only when the manifest is absent.
   - GitHub binary uploads use Base64 as a transport envelope. The repository stores the raw body.
   - Lyric migration keeps all legacy data and offers no lossy option. See `modules/sync/LYRIC_SYNC.md`.
4. `songOrderVersion=0` is the legacy order and `songOrderVersion=1` is the current display order. Serialization, merging, and writing back to local playlists keep the legacy-data migration.
5. Playlist membership uses `syncMembershipTokens` / `removedMembershipTokens` for observed-remove semantics:
   - New fields stay compatible with legacy JSON and ProtoBuf payloads that omit them.
   - Members with tokens are resolved by token, not by comparing only `addedAt` / `deletedAt`.
   - Verify deletion undo, backup restore, and cross-device sync together, so stale deletion records cannot remove a restored member again.
6. Clean missing-field or malformed snapshots before merging:
   - A `SyncSong` has at least one of id, audioId, or mediaUri.
   - A deletion record needs a valid deletion time.
   - A song missing `addedAt` is only a low-priority display item.
7. `CoverUrlMapper.kt` lives in the provider-neutral `data/sync/`.
8. GitHub and WebDAV both call `data/sync/merge/engine/SyncDataMerger.kt` for first uploads and two-sided merges.
9. Business rules live in shared components, and the host provides resource messages and system playlist resolution. `SyncSession` runs conflict retries, local mutation version checks, and persistence confirmation. Backends provide transport and remote version interfaces.
10. Keep the delayed sync, periodic sync, validated-network check, and failure retry behavior of `GitHubSyncWorker.kt` / `WebDavSyncWorker.kt`.
11. GitHub manifest publishing rules:
    - Publish against a fixed remote branch head.
    - Batch commits check `expectedHeadOid`. Staged publishing updates atomically with `beforeOid` and `force=false`, and fails on conflict.
    - Fast-path budgets are in `modules/sync/README.md`.
    - After a batch commit has been sent, an error does not switch to another write flow.
12. Later WebDAV manifest writes check the fingerprint first. They use `If-Match` with a strong ETag, otherwise read back after publishing, and re-merge as a conflict if a change is detected.
13. Sensitive data goes through `SecureTokenStorage.kt` or `WebDavStorage.kt`, never into `DataStore` or plaintext JSON.
14. Storage file names, key names, Worker class names, work names, and input keys are upgrade compatibility boundaries and stay unchanged when moving modules.
15. Device ID reads and first creation share one lock. Causal counters and deletion state commit atomically.
16. Every new sync file enters the directory-wide CRAP gate, which fails when any method scores above 9.

Verification commands:

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
./gradlew :local:verifyCrap :local:lintDebug
```

#### 8. Modify download storage

1. Read `ManagedDownloadStorage.kt`, `naming/ManagedDownloadNaming.kt`, `task/DownloadTaskStore.kt`, `policy/DownloadLifecyclePolicies.kt`, and the related unit tests first.
2. Consider the default app directory, SAF custom directories, migration, legacy naming, metadata files, and `.nomedia`.
3. Download tasks write to `files/download_staging/` first, then commit to the final directory. `.resume.json` and `.hls.json` belong to resume recovery and are not ordinary temp files to clean up.
4. Default download concurrency is **6**, adjustable from **1-8**. When changing concurrency, retry, or network recovery, check `DownloadParallelism.kt`, `AudioDownloadManager.kt`, and `GlobalDownloadManager.kt`.
5. When changing directory migration, delete semantics, resume checkpoints, or sidecar writes, add or update the matching unit tests.
6. The high-risk mobile-data download confirmation goes through `GlobalDownloadManager.trafficRiskDownloadRequests` to the global dialog host in `NeriApp`. When changing it, check occlusion and input on Now Playing and the More sheet, and keep the real-entry coverage in `MainActivityTrafficRiskDownloadTest`.

#### 9. Modify lyrics display, sharing, or phonetics

1. Now Playing lyrics live mainly in `ui/component/lyrics/AdvancedLyricsView.kt`, `ui/component/lyrics/SyncedLyricsView.kt`, and `NowPlayingScreen.kt`.
2. The full-screen lyrics page is `LyricsScreen.kt`, and lyric sharing reuses `LyricShareSheet.kt`.
3. Phonetic display is controlled by the `lyric_translation_use_phonetic` setting. Translation must be on and the current lyrics must have phonetic data.
4. Japanese translation spacing separates kana from ordinary CJK text. When changing it, check both the app-side `resolveLyricTranslationExtraGap` and `resolveJapaneseLyricTranslationTopPadding` in the `:accompanist-lyrics-ui` submodule.
5. Long-press on lyrics opens the share sheet. When changing gestures, also check tap-to-seek, manual lyric offset, and advanced lyric viewport scrolling.
6. Lyric cards are shared as `FileProvider` cache files. When changing the output location, update `file_paths.xml` and cache cleanup too.

#### 10. Modify storage usage and cache cleanup

1. Entry points are `data/local/storage/StorageUsageAnalyzer.kt` and `SettingsStorageCacheSection.kt`.
2. When adding a cache directory, first decide whether it is cleanable cache, downloaded content, diagnostics, or app data.
3. Cleanup covers only regenerable content. Downloaded songs, downloaded lyrics, download indexes, and auth data are never removed by normal cache cleanup.
4. Download staging holds working files, resume credentials, and HLS checkpoints, which normal cache cleanup keeps. Download recovery handles these files, and a UI task-state snapshot does not decide to delete the whole directory.

#### 11. Modify NetEase playlist detail cache

1. The cache entry point is `NeteasePlaylistCacheRepository.kt`, and page state is in `NeteaseCollectionDetailViewModel.kt`.
2. The cache signature is based on track count and recent track IDs, and decides whether the track list is reused.
3. A network or parse failure falls back to the cache. Manual refresh keeps force-refresh semantics.
4. Album details do not use the playlist cache, so the two data models stay separate.

#### 12. Modify Lyricon integration

1. SDK integration lives in `modules/lyrics/src/main/java/moe/ouom/neriplayer/lyrics/lyricon/`, and the output controller in `lyrics/output/`. The player supplies only lyrics, preferences, progress, and lifecycle snapshots and does not manage the SDK directly.
2. The switch is the `lyricon_enabled` setting, synced by the player lifecycle.
3. Lyric data uses `LyricEntry`, word-level data comes from `WordTiming`, and translation lines are matched to original lines by time tolerance.
4. The Lyricon / SuperLyric position feed pushes every 200 ms on its own and recomputes the dead zone against elapsed realtime. Keep foreground / background timing consistent when changing the progress cadence.
5. Keep the lyric structure compatible across Lyricon, SuperLyric, status-bar lyrics, advanced Now Playing lyrics, and external Bluetooth lyrics.
6. Original and translated Bluetooth lyrics are independent switches. When both are output, update the title / artist fields through one atomic snapshot, and keep the tests for track info, field length limits, whitespace cleanup, and duplicate-submission suppression.
7. `bluetooth_metadata_mode` only changes display when Bluetooth output is actually active. Dynamic Island behavior stays when Bluetooth is not connected.
8. The car browser entry lives in `service/car` of the playback runtime:
   - It reuses the existing framework session through a private service Binder.
   - Browsing does not promote to foreground or auto-resume playback. The private playback service starts only on an explicit play action.
   - Browse IDs are revalidated against the current library.
   - Artwork uses a restricted read-only Provider and a bounded JPEG cache, and accepts no arbitrary file or remote address from clients.
   - Compat only bridges browse and search for the existing session.

#### 13. Modify Listen Together

1. Client sessions, HTTP / WebSocket transport, codecs, and bounded reading live in `modules/listentogether`, maintained by the `data/ltw`, `api/ltw`, and `listentogether/protocol` packages. Protocol models are in `modules/model`.
2. App's `core/di/ltw` only binds platform capabilities, and the player adapter is in `core/player/ltw` of `modules/playback/runtime`.
3. Server logic is in `np-submodule/NeriPlayer-LTW`.
4. Protocol field changes stay compatible with both client and Worker, and tests are updated.
5. With `shareAudioLinks=false`, HTTP / WS room snapshots never expose `track.streamUrl` or `queue[*].streamUrl`. Turning the toggle off immediately clears session candidates cached in the room.
6. Review `REQUEST_LINK` / `LINK_READY`, member control, controller-offline recovery, and version-gated updates together, so old state does not overwrite new state.
7. Repeat / shuffle mode uses `PLAYBACK_MODE` / `REQUEST_PLAYBACK_MODE`. Member requests and `LINK_READY` validate the target stable track key, so async results cannot land on the wrong song.
8. A first join needs `joinSecret`, and an existing member reconnecting needs `memberSecret`. Neither secret is written to sanitized room state or logs, and the `secret` parameter of an invite URI is treated as sensitive input.
9. Control events can carry `clientInstanceId`, `clientSequence`, and `clientTimeMs`. The Worker rejects stale ordering, `REQUEST_SET_TRACK` can only pick a track already in the queue, and the client compatibility path stays consistent.
10. When a member leaves explicitly, the client calls `/api/rooms/:roomId/leave`, and the Worker removes the member and broadcasts `MEMBER_LEFT`.
11. A normal WebSocket drop counts as transport churn and keeps member credentials for reconnect. An explicit controller leave closes the room.
12. The 6-character room ID, 1-24 character nickname, 2000-item queue limit, and request deduplication are protocol boundaries. Change client and server validation together.
13. Settings support a custom server URL and availability test. Do not hard-code a single address.
14. HTTP control fallback validates the response against the server, room, user, and credentials. Leaving, disconnecting, or changing session cancels old tasks, and coroutine cancellation does not enter error or reconnect flows.
15. Queue mutation building and applying keep duplicate-song occurrence identity, consecutive insertion order, and the current song. Run the module round-trip tests after changes.

Verification commands:

```bash
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
./gradlew :app:testDebugUnitTest --tests "*ListenTogether*"
```

#### 14. Modify main navigation and glass transitions

1. The main-tab production path is carried jointly by `NeriApp.kt` and `MainTabLayerHost.kt`. Tab order and direction come from `resolveMainTabTransitionDirection`.
2. During a transition, keep both outgoing and incoming scenes and preserve each tab's state through `SaveableStateHolder`. On rapid reverse or repeated taps, do not clear the old scene first.
3. Each scene has its own `MainTabGlassOwner`. When changing Advanced Glass, only the currently visible owner takes part in compositing, and detail-page handoff is owned by the navigation layer.
4. `coherent_feedback_enabled` is off by default, and detail pages use a drawer-style foreground rise with a slight background sink. When on, background and detail page hand off in sync.
5. When changing animations, cover forward, reverse, interrupted, repeated requests, state restoration, and the first frame at startup. Geometry tests do not count an unlaid-out `Rect(0, 0, 0, 0)` scene as a real overlap.
6. Also check:
   - `NeriAppMainTabTransitionPolicyTest`
   - `AdvancedGlassNavigationTransitionTest`
   - `NeriAppNavigationTransitionTest`
   - `HostNavigationTransitionGeometryTest`
7. Theme adaptation reuses the geometry in `ui/theme/NeriDesign.kt` and the colors and typography of `MaterialTheme`. Dialogs use the existing entry points in `ui/component/overlay/`. When changing theme selection, also maintain `SettingsThemeControls.kt`, theme snapshots, and preview prompt records.
8. Verify Material and Clean in light and dark, and on phone and tablet, separately. For back and drag changes, cover system back, predictive-back complete / cancel / reverse, sheet drag, and content scroll.
9. Record screenshots, recordings, emulator results, and real-device results as separate evidence. A static screenshot cannot prove touch and navigation behavior.

#### 15. Modify home-screen widgets, launcher shortcuts, or global feedback

1. The widget entry is `widget/PlaybackWidgetProviders.kt`, and state and color extraction are in `PlaybackWidgetState.kt` and `PlaybackWidgetVisuals.kt`.
2. RemoteViews layouts have regular and API 31 resources. When changing visuals, clipping, or preview images, check that `layout/`, `layout-v31/`, `xml/`, and `xml-v31/` stay consistent.
3. Playback actions end up in `AudioPlayerService`. A new action needs a check of foreground-service start policy, MediaSession state refresh, and feedback when there is no song or it is buffering.
4. Launcher shortcuts are mapped by `navigation/LauncherShortcuts.kt`. When adding one, update `res/xml/shortcuts.xml`, localized strings, and `LauncherShortcutsTest`.
5. Global Snackbars go through `AppFeedback` / `ViewSnackbar`. Pages do not each hold a SnackbarHost that cannot show across overlays.
6. Batch playlist export, delete undo, and sync deletion records are linked. When changing any of them, also check:
   - `PlaylistExportSheetTest`
   - `AppFeedbackPolicyTest`
   - `LocalPlaylistRepositoryTest`
   - `SyncPlaylistDeletionPolicyTest`

#### 16. Modify song encyclopedia

1. Shared contracts live in `data/model/encyclopedia/` of `:model`, and the NetEase implementation and parsing in `platform/netease/encyclopedia/` of `:platform`. Data is requested only for recognizable NetEase songs.
2. `SongEncyclopediaRepository` distinguishes success, unsupported, no data, and failure. Cancellation propagates, and a failure in an optional request such as credits or artists degrades only its section.
3. `NowPlayingViewModel` uses `RequestGeneration` to drop stale requests, and `SongEncyclopediaUiState.forSongKey` revalidates song identity before rendering. Check track change, close, and retry together.
4. `ui/screen/nowplaying/actions/SongEncyclopediaContent.kt` composes the sections, and `NowPlayingSecondaryActions.kt` handles the sheet and artist, playlist, and comment navigation. Do not stack more overlays on the encyclopedia.
5. When changing this feature, check repository, parsing, cache, and ViewModel unit tests, plus device tests for section layout, comment entry, and gestures.

---

### Debugging & Logs

- Developer Mode: tap the **version number** 7 times in Settings. A standalone `Debug` tab appears in the bottom bar.
- Regular file logging is enabled only in Developer Mode.
- Crash logs are written independently by `ExceptionHandler` / `NativeCrashHandler`, regardless of Developer Mode.
- The Debug tab has YouTube, Bili, NetEase, Search, and Listen Together probes, plus regular log and crash log viewers.

Windows:

```bash
adb logcat | findstr NeriPlayer
```

Linux / macOS:

```bash
adb logcat | grep NeriPlayer
```

---

### Testing & PR

#### CI and release flow

Automatic CI:

- Candidate APKs build in parallel with tests, and the Native Release host check runs before packaging.
- Final publication waits for the JVM, lint, Worker, and emulator jobs, then verifies candidate artifact identity and test reports.

Manual Release:

- Runs JVM tests, lint, available module-boundary checks, and the Native Release host check against the source actually checked out at `target_ref`.
- Verification reports are named after the target commit SHA.
- `test_release` defaults to `true` and produces an unsigned test APK and a draft pre-release.
- With `test_release` set to `false`, it builds a signed APK and publishes formally.
- The full main CI emulator, Worker, and CRAP matrix is not included.
- Historical refs use their matching old or new native test entry. A release is refused when test infrastructure exists but its configuration is missing.

Extra verification:

- Emulator, sanitizer, and real-DAC verification run according to change risk.
- Real hardware is not a prerequisite for every release.

#### Pre-submit checks

Complete at least the following checks before submitting.

1. Build the Debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. Unit tests and CRAP gate:
   ```bash
   ./gradlew :app:verifyCrap
   ```
3. For changes to login state, playback resolution, or other high-regression integration behavior, run the smoke tests as needed:
   ```bash
   ./gradlew :app:testDebugUnitTest -DrunNeteaseSmoke=true
   ./gradlew :platform:testDebugUnitTest \
     -DrunYouTubePlaybackSmoke=true \
     -DyoutubeSmokeVideoId=VIDEO_ID
   ```
   - Replace `VIDEO_ID` with a real video ID.
   - Optionally add `-DyoutubeSmokeForceRefresh=true` or `-DyoutubeSmokeCookieFile=/absolute/path/to/cookies.json`.
4. For changes to resources, UI, navigation, settings, sync, or storage logic, run lint:
   ```bash
   ./gradlew :app:lintDebug
   ```
5. For Compose UI, permissions, Activity, or login flow changes, run on a device or emulator:
   ```bash
   ./gradlew :app:connectedDebugAndroidTest
   ```
6. For native USB changes, match the dedicated Android Native CI:
   ```bash
   for profile in release-werror-asserts asan-ubsan tsan; do
     tools_pub/usb-async-lab host-test \
       --manifest modules/native/src/main/cpp/tests/usb/config/run-manifest.example.yaml \
       --profile "$profile"
   done

   ./gradlew :native:externalNativeBuildDebug \
     --no-daemon \
     --warning-mode all \
     --stacktrace
   ```
   - The host gate runs one fixed CTest set.
   - The Android build must produce a non-empty `lib_neri.so` for each of `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
7. For DSP changes, run its host tests separately:
   ```bash
   cmake -S modules/native/src/main/cpp/tests/dsp -B .tmp/dsp-host -DCMAKE_BUILD_TYPE=Release
   cmake --build .tmp/dsp-host --parallel
   ctest --test-dir .tmp/dsp-host --output-on-failure
   ```
   - For memory and undefined-behavior checks, use a separate build directory with `-DDSP_TEST_SANITIZER=asan-ubsan`.
   - USB sanitizer profiles do not run DSP tests.
8. For Listen Together Worker changes:
   ```bash
   npm ci --prefix np-submodule/NeriPlayer-LTW
   npm run check --prefix np-submodule/NeriPlayer-LTW
   ```
   - `npm run check` runs the Node.js version check, `node --check`, protocol tests, and `wrangler deploy --dry-run` in order.
   - Protocol or room-state changes also need real create / join / ws flow verification.
9. Where tests go:
   - Unit tests go in `src/test/` of the module under test, and host integration tests in `app/src/test/`.
   - Device or Compose UI tests go in `src/androidTest/` of the matching module, and app-host integration tests in `app/src/androidTest/`.
10. When a behavior change touches the README, settings copy, user flows, or sync formats, update the docs too.

#### CRAP quality gate

```bash
./gradlew verifyModularization
```

This task runs:

- module boundary checks
- lint and JVM tests for app and the owned libraries
- merged JaCoCo coverage, reporting every JVM method score mapped to app and library main sources, with scores above 8 listed separately

Gate rules:

- `source_patterns` in `config/quality/crap-scope.json` covers complete source files, and `method_scopes` covers methods in the original entry points affected by the split.
- Any in-scope method with CRAP above 9 fails the gate. Out-of-scope methods stay in the full report.
- Scores approximate path coverage with JaCoCo complexity coverage. They do not measure native code and do not replace a coupling review.
- Both `:app:check` and Android CI run the gate.
- Full scores and scope reports are in `app/build/reports/crap/`. For methodology and dependencies, see the [quality guide](tools_pub/quality/README_EN.md).

Playback library gates:

- `:playback:logic` and `:playback:runtime` provide `verifyCrap` through `build-logic.android.module-quality`.
- Each gate uses the module's own coverage and the matching scope from the shared config, and fails when the selected scope is empty.
- The policy, runtime coordination, PCM, host interface, USB policy, and widget presentation packages include new files automatically.
- App's player adapters take part in the aggregate gate.
- Instrumented tests for lyrics rendering, Room queue, decoder, and playback Range that moved to the runtime library run through `:playback:runtime:connectedDebugAndroidTest`.

Line budgets:

- `OwnedMainSourceLineBudgetTest` requires checked owned main sources and components to stay strictly under 2000 physical lines. Owned `.cpp` / `.h` files under USB `exclusive/` are included. Update the file list in the test when adding components.
- `LocalManagementLineBudgetTest` applies the same limit to downloads, local data, Library, and related tests. When moving an entry file or splitting a directory, update the required paths and scan scope in the test.
- Third-party libusb and test files are exempt from this limit.

#### Splitting conventions

- Give state, async tasks, and release logic to the component with that responsibility, and connect external capabilities through small interfaces.
- `PlayerManager` access to the player and global services is concentrated in the matching `PlayerManager*Port` adapters.
- In `:listentogether`, the `session/` components own member operations, room state, connection recovery, and control results. Platform and player implementations are injected through host interfaces.
- Wake locks are released with the session, async tasks are cancelled by their owner components, and nothing reads back from the global container.
- `NowPlayingScreen`, `SettingsScreen`, and `NeriApp` compose pages and feature components. Editing sessions, directory selection, settings domain bindings, and navigation side effects are handled in the matching components.
- Do not move the original entry into extension files as a receiver, and new components do not read the original entry's internal state.
- Local storage in `:local` is organized into `source`, `scan`, `accounting`, `cleanup`, and `policy`, with data contracts in the `storage` package of `:model`.
- Storage analysis chain:
  - `StorageUsageScanner` collects snapshots through data-source interfaces.
  - `StorageUsagePresenter` reads only snapshots and string resources.
  - `StorageCacheCleaner` acts through file and platform cleanup ports.
  - Room and global service access is concentrated in `StorageUsageAndroid.kt`.
- New storage components keep this one-way dependency and join the full-file gate.

#### Test coverage highlights

- **YouTube**: login, cookie rotation, anonymous sessions, challenge parsing, PoToken, playback resolution, Range / Seek policy, and prefetching (including fast recovery from large seeks in long audio).
- **NetEase**: lyrics, local smoke tests, playback fallback, and playback response parsing.
- **USB exclusive**:
  - keep-alive, startup watchdog, foreground / background recovery
  - 32-bit / float output
  - UAC2 explicit feedback, long-gap clock reacquisition, coordinated reconfiguration
  - Runtime Report v2, backpressure recovery, deferred runtime refresh retry
  - bit-perfect volume, USB attach toggle, audio focus policy
- **Navigation**: dual-scene main-tab transitions, rapid reverse switching, drawer / coherent detail feedback, glass owner isolation, and unlaid-out scene geometry filtering.
- **Themes and info sheets**: Material / Clean themes and preview prompts, predictive back, encyclopedia request identity and sections, and the mobile-data download confirmation above Now Playing.
- **System entry points**: widget state / color / RemoteViews resources, launcher shortcut mapping, and the app-wide Snackbar overlay.
- **Downloads**: metadata, naming, directory migration, snapshot cache, `.nomedia`, delete semantics, and startup recovery.
- **Startup**: startup stages, notification permission, playback service startup, history, and safe-mode recovery planning.
- **Local data**: local scan, metadata hydration, cover fallback, system playlist dedup, and playlist order stability.
- **Sync**:
  - GitHub / WebDAV serialization, missing-field snapshot cleanup, legacy playlist order migration, deletion policy
  - playback stat rolling windows, aggregate total merge, legacy bucket-only compatibility
  - WebDAV concurrency fallback, atomic file writes, and upload retry
- **Playback policy**: long-audio progress thresholds, explicit position precedence, BilibiliSponsorBlock local skipping, and its Listen Together disable policy.
- **Listen Together**:
  - address validation, version gating, repeat / shuffle mode, stable track key target validation
  - playback sync planning, session candidate fallback, invite / member secrets
  - explicit leave / reconnect, event ordering, Session control / cancellation, and protocol compatibility
- **Lyrics and effects**: lyrics views, Japanese kana translation spacing, word timing, external Bluetooth lyrics, playback effects, and playback policy.
- **Config and diagnostics**: config backup, settings generation, security guards, crash log files, and safe mode.

#### PR requirements

The PR description includes:

- motivation
- key implementation points
- risks and compatibility impact
- how it was tested
- screenshots or recordings for UI changes

Do not commit:

- APKs, signing files, local IDE config
- caches, logs, temporary build outputs
- auth cookies, tokens, full config backups, personal data

Commit messages follow Conventional Commits, for example `feat: ...`, `fix: ...`, `docs: ...`.

---

### Legal & License

- This project is for learning and research only. Do not use it for illegal purposes.
- This project is licensed under **GPL-3.0**.
- By submitting a contribution, you agree to distribute your changes at least under GPL-3.0.
- The alternative license in `modules/native/src/main/cpp/README.md` covers only the NeriPlayer-owned native source listed there, not third-party code or other repository content.
- A native PR does not by itself grant the alternative license. Contributors who agree to dual licensing must include the statement provided by that README in the PR, a commit, or another auditable record accepted by the copyright holder.
- External native contributions without an explicit dual-license grant are accepted under GPL-3.0 and fall outside the "closed-source use with attribution" exception.

---

### Communication

- [Issues](https://github.com/cwuom/NeriPlayer/issues): bugs, feature requests, and improvements, one topic per Issue.
- [Discussions Q&A](https://github.com/cwuom/NeriPlayer/discussions/categories/q-a): usage, settings, and compatibility questions.
- [README_EN.md](./README_EN.md): features and usage.
- [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md): community code of conduct.

For a large structural change, open an Issue first to align on direction.
