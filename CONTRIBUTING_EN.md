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

- [README_EN.md](README_EN.md)
  - For users and new contributors: project scope, feature boundaries,
    installation/builds, sync, and privacy.
- [CONTRIBUTING_EN.md](CONTRIBUTING_EN.md)
  - For developers: module boundaries, extension paths, tests, and PR expectations.
- [docs/kotlin-helpers_EN.md](docs/kotlin-helpers_EN.md)
  - Usage and boundaries for request generations, units, player reads, and coroutine results.
- [tools_pub/quality/README_EN.md](tools_pub/quality/README_EN.md)
  - Scope, commands, and verification limits for CRAP, source ownership, and dependency checks.
- [modules/native/src/main/cpp/README.md](modules/native/src/main/cpp/README.md)
  - Defines the alternative-license scope for NeriPlayer-owned native source,
    third-party exclusions, and the explicit dual-license statement required
    for external contributions to enter that scope.
- [modules/native/src/main/cpp/tests/usb/config/host-gate-contract.md](modules/native/src/main/cpp/tests/usb/config/host-gate-contract.md)
  - Defines the public native USB host gate, CI coverage, and real-device boundary.
- [modules/native/src/main/cpp/tests/usb/corpus/README.md](modules/native/src/main/cpp/tests/usb/corpus/README.md) and
  [modules/native/src/main/cpp/tests/usb/fixtures/README.md](modules/native/src/main/cpp/tests/usb/fixtures/README.md)
  - Define the synthetic/auditable boundary for public USB test corpus and fixtures;
    device-derived evidence stays in the private evidence tree.
- [np-submodule/NeriPlayer-LTW/README.md](https://github.com/TheSmallHanCat/NeriPlayer-LTW#readme)
  - For Listen Together server deployers: Worker API, event model, deployment,
    and local checks.

If a behavior change affects user understanding, update the README.
If it affects extension paths, tests, or module boundaries, update CONTRIBUTING.

---

### Development Environment

- **Android Studio**: latest stable version
- **JDK**: 17
- **Kotlin**: 2.4.10, JVM target 17
- **AGP**: 9.4.1
- **Gradle**: 9.6.1
- **compileSdk / targetSdk / minSdk**: 37 / 36 / 28
- **NDK**: `27.0.12077973`
- **CMake**: `3.22.1`, pinned by the Android build script
- **Node.js**: 22, for Listen Together Worker checks
- **Version name format**: `<git_short_hash>.<MMddHHmm>`
- **Release APK filename**: `NeriPlayer-<versionName>[-abi].apk`

Additional notes:

- The repository uses Git submodules. Clone with `--recursive`, or run
  `git submodule update --init --recursive`.
- `buildSrc`, `ksp-annotations`, and `ksp-processor` use JDK 17 toolchains.
  Install JDK 17; point the Android Studio Gradle JDK and command-line
  `JAVA_HOME` to it when possible.
- The build script reads the Git short commit hash to generate the version name,
  so Git must be installed locally.
- Dependency versions are managed by `gradle/libs.versions.toml` and module
  `build.gradle.kts` files.
- Only `zh` and `en` resources are kept in the app, via the locale filter in `build-logic`.

---

### Quality Guardrails

NeriPlayer covers a broad product surface. Protect these paths first:

- **Playback**: `PlayerManager`, playback resolution, cache, URL refresh,
  playback fallback, long-form progress memory, BilibiliSponsorBlock skip
  policy, state recovery, loudness normalization, channel balance, high-resolution
  output, the USB-exclusive native path, startup watchdogs, and foreground/background
  health audits.
- **Downloads**: `AudioDownloadManager`, `GlobalDownloadManager`,
  `DownloadTaskStore`, `DownloadLifecyclePolicies`, `ManagedDownloadStorage`,
  resume checkpoints, sidecar files, queue recovery, cancellation cleanup, and SAF migration.
- **Sync**: GitHub / WebDAV three-way merge, deletion records, playback stats,
  missing-field snapshot cleanup, JSON/ProtoBuf/Base64 compatibility, and WebDAV
  concurrency protection.
- **Local data**: playlist Room transactions, legacy JSON upgrades and atomic fallback, local metadata hydration,
  config import/export, encrypted auth storage, and DataStore settings.
- **Lyrics and Now Playing UI**: `AdvancedLyricsView`, `SyncedLyricsView`,
  `LyricShareSheet`, phonetic lyric display, Japanese lyric translation spacing,
  long-press lyric sharing, and the full-screen Lyrics page.
- **Navigation and glass UI**: `MainTabLayerHost`, drawer/coherent detail feedback,
  interruptible main-tab switching, page-state retention, standardized Snackbar
  overlays, and Advanced Glass owner handoff.
- **System entry points and desktop shell**: `LauncherShortcuts`, home-screen widgets,
  `USB_DEVICE_ATTACHED` handling, and playback-service control entry points.
- **Storage and cache UI**: `StorageUsageAnalyzer`, cache cleanup options,
  download directory indexes, and SAF snapshots.
- **Listen Together**: Android client, Worker protocol fields, roles, queues,
  version-gated updates, session-candidate sharing toggles, and controller-offline recovery.
- **Diagnostics**: safe mode, JVM/native crash logs, ANR capture, and Debug probes.
- **Local persistence**: debounced playback/traffic-stat writes, lifecycle flushes,
  atomic file replacement, and local-playlist/SAF initialization readiness.

Related JVM and device tests live in app and library `src/test/` and `src/androidTest/` directories.
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
4. First launch enters the disclaimer and startup onboarding flow. Notification and
   local-music permissions are explained first and requested only after the user
   chooses them; either can be skipped.
5. For debugging access, tap the **version number** 7 times in Settings. A
   standalone `Debug` tab will appear in the bottom navigation bar.

---

### Release Build

Release builds enable minification and resource shrinking by default.
A normal `assembleRelease` packages `arm64-v8a` only in the APK. Multi-ABI output requires an
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
   directory. The current Release build does **not** fall back to the debug
   signing config. Local Android Studio / IntelliJ builds automatically allow
   unsigned Release packaging so IDE `Build APK` / `assemble` flows keep
   working. CLI and CI builds still require a usable keystore by default.
   GitHub PR builds automatically produce an unsigned Release for packaging
   validation. Other CI/PR environments can pass `-PallowUnsignedRelease=true`.

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

The root [README_EN.md](README_EN.md#module-layout) is the entry point for the complete module map. `gradle/owned-modules.txt` registers all 14 owned libraries once for builds, lint, and structural checks. Coverage includes libraries with Kotlin/Java production sources; `:native` uses independent host tests and four-ABI compilation. Top-level names express business domains, with remote sources consolidated in `:platform`. Playback and downloads retain separate rule and runtime build boundaries.

- Infrastructure: `:common`, `:network`, `:model`, `:database`. Models have no project implementation dependencies; the database owns historical Room schemas and complete upgrade paths.
- Native: `:native` owns the CMake build boundary, crash handling, USB protocols and PCM calculations, exclusive transport, and third-party libusb. App consumes its AAR; Kotlin playback sessions and the JNI bridge remain in `:playback:runtime`.
- Playback: `:playback:logic` contains policy/runtime/audio/queue; `:playback:runtime` owns the engine, service, USB, source resolution, and integration. Nine policy leaf packages retain narrow dependencies; USB policy stays in runtime. Downloads are accessed through `PlayerDownloadAccess`.
- Downloads: `:download:logic` owns reusable rules and storage helpers; `:download:runtime` owns Room, SAF, recovery, and services. Rules cannot depend on runtime; Worker and JobService class names remain stable.
- Lyrics: `:lyrics` owns parsing, transforms, shared time offsets, and external lyric output. Its `lyrics/lyricon` owns SDKs and position feeds, while `lyrics/output` manages asynchronous request generations, cancellation, and offset snapshots. Playback supplies lyrics through a narrow loading port and provides snapshots; lyrics cannot depend on platform or player implementations.
- Platforms: `:platform` owns Bilibili, NetEase, and YouTube protocols, accounts, caches, and business services, plus comments, lyric sources, metadata search, matching, and fallback. `api` describes the internal protocol responsibility and cannot read account repositories, Room, or player state. Platforms retain package dependency boundaries. YouTube JS assets and consumer R8 rules stay here; `api(:lyrics)` exports parsing capabilities.
- Local data: `:local` groups settings, media, playlists, statistics, backup, traffic, and sync adapters. It owns business mappings, host interfaces, WorkManager adapters, and settings KSP; screen rendering stays in app.
- Sync: `:sync` combines GitHub/WebDAV transport, encrypted credentials, device identity, causal counters, sessions, sanitization, merging, and Worker policies. Transport and calculation packages retain separate dependency allowlists; local repository adapters stay in `:local`. Deletion records and mutation versions commit atomically.
- Listen Together: `:listentogether` owns protocol, transport, identity rules, and sessions. Protocol code uses only models, serialization, and the standard library; host interfaces provide playback, resources, and Android services.
- App and tooling: `:app` owns Android entry points, Compose screens, and assembly. KSP tools, upstream lyric modules, `build-logic`, `buildSrc`, and the Listen Together server are outside the 14-library count. The vendored Miuix source/docs tree is outside the app graph.

Libraries cannot reference app, screens, or `AppContainer`, and the graph must remain acyclic. `:playback:runtime` may use its own `PlayerManager`. Live settings use providers; the host injects HTTP, account, and device-token capabilities. Room business mappings belong to local/platform repositories; database and rule components must not depend on runtime implementations.

Libraries with Kotlin/Java production sources use `build-logic.android.feature-library`; the pure-native `:native` module uses `build-logic.android.library` without JaCoCo or JVM tests. Owned libraries register their actual Gradle path in `gradle/owned-modules.txt`; no separate JVM coverage list is required. Tests stay with implementations in `src/test` / `src/androidTest`, with host integration tests in app. Module ownership changes retain production packages and class names. Internal source directories match packages; path contracts, fixtures, resources, and CRAP selectors are maintained together.

`verifyModuleBoundaries` checks registrations, actual locations, domain dependencies, cycles, forbidden imports, source ownership, and package alignment. Library files stay below 2000 lines; library and app `APP_FAMILIES` directories contain at most 16 direct source files. Compiled package-level dependency gates preserve calculation isolation.

#### Android client key paths

- `app/src/main/java/moe/ouom/neriplayer/NeriPlayerApplication.kt`
  - Application initialization. Handles language, crash handling, `AppContainer`,
    Lyricon, global downloads, and the shared image loader.

- `app/src/main/java/moe/ouom/neriplayer/activity/`
  - `MainActivity.kt`: the only external entry point. Handles safe mode, startup, disclaimer,
    onboarding, external audio imports, Listen Together deep links, and the top-level
    Compose host.
  - Platform login activities live under `activity/auth/` and run in dedicated
    secondary processes. `activity/sync/` stores Activity-side sync warning state.
  - `UsbDeviceAttachHandling.kt` enables or disables the USB attach Activity alias
    from settings; the playback service reuses the same policy for
    `USB_DEVICE_ATTACHED` broadcasts.
  - `NeteaseWebLoginActivity.kt`, `NeteaseQrLoginActivity.kt`,
    `BiliWebLoginActivity.kt`, `BiliQrLoginActivity.kt`, and `YouTubeWebLoginActivity.kt`:
    internal platform sign-in pages.

- `app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt`
  - Top-level Compose app shell. Handles `NavHost`, dynamic bottom bar, `MiniPlayer`,
    `Now Playing` overlay, Debug routes, themes, cache cleanup, and playback service sync.
  - `MainTabLayerHost.kt` retains outgoing and incoming main-tab scenes,
    performs interruptible directional transitions, and preserves saveable state
    plus a glass owner for each scene.
  - `ui/feedback/` owns app-wide Snackbar/Toast feedback policy. Before adding a
    new global feedback surface, check `AppFeedback` and `ViewSnackbar` first.

- `app/src/main/java/moe/ouom/neriplayer/ui/component/lyrics/`
  - `AdvancedLyricsView.kt` and `SyncedLyricsView.kt`: advanced lyric layout,
    word/character highlighting, translation/phonetic display, click-to-seek,
    and long-press callbacks.
  - `LyricShareSheet.kt`: lyric-line selection, copy, song sharing, and lyric card generation.
  - LRC/YRC/TTML parsing and translation alignment live in `modules/lyrics`; shared lyric contracts belong to the `lyrics` package in `:model`.
  - The old `AppleMusicLyric` name exists only as an `@Deprecated` wrapper in
    `ui/component/LyricsCompatibility.kt`. New code should use `SyncedLyricsView`.

- `app/src/main/java/moe/ouom/neriplayer/ui/component/playback/`
  - `NeriMiniPlayer.kt`: bottom Mini Player, play/pause, and horizontal swipe for previous/next.
    Playback sound and sleep-timer sheets also live here.
  - Same-named files in the `ui/component/` root are primarily legacy package
    compatibility entry points. New implementations belong in responsibility-based
    subpackages such as `lyrics/`, `playback/`, `download/`, and `navigation/`.

- `app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/`
  - `LibraryScreen.kt`: top-level Library categories. Local content can switch
    between playlists and artists, and Favorites can show playlists, followed artists, and Hot playlists.
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
  - First-run onboarding for language, platform accounts, permission guidance,
    playback controls, GitHub sync, and personalization.

- `modules/platform/src/main/java/moe/ouom/neriplayer/platform/`
  - `netease/api`: clients, crypto, request parameters, and QR authentication protocols.
  - `bilibili/api`: search, QR login, favorites, collections, and playback information. Repositories and skip policies live in the same library under `platform/bilibili`.
    Explore link recognition preserves selected parts, `cid`, and `season_id`; check `ExploreLinkRecognizer` and `ExploreViewModel` when changing it.
  - `youtube/api`: YouTube Music clients, PoToken, JS Challenge, request/response parsing. Protocol models belong to `:model`. Authentication persistence and caches live in the same library under `platform/youtube`.
  - `lyrics/api`: LrcLib, Kugou, and AMLL service access; source repositories, matching, and fallback live in the same library under `platform/lyrics`.
  - `search/api`: `SearchApi`, NetEase, and QQ metadata search services. `SearchManager` lives in the same library under `platform/lyrics`, and shared music models belong to `modules/model`.
  - `AppContainer` assembles clients and routing with HTTP, debug configuration, and live settings providers; libraries do not read the container or player singleton.
  - `PlainLyrics.kt` in `modules/lyrics` provides shared timeline conversion.

- `modules/platform/src/main/java/moe/ouom/neriplayer/platform/comments/`
  - Comment sources, parsing, pagination, and caches; comment contracts belong to `:model`;  `AppContainer` injects client providers and cache instances.
  - The host owns cache lifetime. Libraries do not access the global container. Repository tests belong to the module; ViewModel integration tests remain in `app`.
  - Source tags belong to `:model`; Bilibili legacy playback identity resolution lives in the same library under `platform/bilibili/playback/resolver` and does not depend on the player singleton.

- `modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/`
  - `PlayerManager.kt`: unified Media3 ExoPlayer management, playback resolution, queue,
    cache, state recovery, retry, and playback policy.
  - `service/AudioPlayerService.kt`: foreground playback service, media notification,
    MediaSession, and media button handling.
  - Downloads belong to `:download:runtime`; playback uses the `PlayerDownloadAccess` interface.
  - `effects/PlaybackEffectsController.kt`: speed, pitch, loudness enhancer, and equalizer.
  - `engine/`: Media3 renderers and data sources. PCM normalization, channel balance,
    and reactive audio signals belong to `:playback:logic` under `audio/processing` and `audio/reactive`.
  - `runtime/stats`, `runtime/progress`, `runtime/transport`, and `runtime/quality`
    in `:playback:logic` own statistics collection, playback progress, transport,
    and quality controls. The host supplies side effects through the corresponding
    ports. Playback commands and queue advancement live in `playback/PlayerManagerPlaybackExtensions.kt`.
  - `queue/state` and `queue/policy` in `:playback:logic` hold state ownership
    and editing/navigation rules. Queue contracts belong to `:model` under `playback/queue`.
    `PlayerQueueSnapshot` holds the list and current index. `PlayerQueueSessionSnapshot`
    combines the queue, shuffle mode, and restore order; `PlayerQueueStateStore`
    publishes the complete session. Use `startPlayback`, `setLocalShuffle`, and
    `restoreSession` for playlist starts, local shuffle changes, and persisted-session
    hydration so queue and restore data change in one transaction.
    Use `publishCurrentQueue` for remote queue updates and `updateCurrentQueue` for
    moves, removal, and insertion. Compute from the latest snapshot inside the lock;
    keep player and disk operations outside it.
    Async metadata updates use `updateQueuedSong` to preserve the current selection.
    UI reorders provide order only; song content comes from the latest queue.
    Reject stale reorders when duplicate songs cannot be matched unambiguously,
    and retain the current queue if restoring pre-shuffle order is ambiguous.
    Clearing the queue also clears its restore order. Persistence reads one session
    snapshot to avoid mixing different versions of shuffle state. The current-song
    Flow and Media3 side effects still require their own thread-boundary checks.
    `session/PlayerQueueSessionBindings` adapts new-engine initialization, local/remote
    playlist starts, and persisted-state restoration. `verifyDomainDependencies`
    checks direct JVM dependencies of the queue package, which cannot reference this host adapter.
  - `runtime/persistence/PlaybackStatePersistenceCoordinator.kt` in `:playback:logic` owns save requests and delays.
    Call `prepareStatePersist` or `scheduleStatePersist` at the synchronous event
    boundary to capture a complete snapshot and issue a request before awaiting
    stats writes or other async work. Writes are serial; superseded queued requests
    do not write. `PlaybackStateWriter` acknowledges only successful backends.
    After JSON fallback, fully write Room before resuming incremental persistence.
    Propagate cancellation and close request admission when releasing the player;
    storage completion callbacks must not change current playback state.
    `RestoredPlaybackState` keeps absent, paused, and pending auto-resume states
    together instead of independently changing the restored position and flags.
  - `runtime/refresh` in `:playback:logic` owns request contracts and effect admission;
    `RefreshInFlightController` alone owns active URL refresh state.
    Matching parameters can share an active request, but completion, cancellation,
    and writeback use its `RefreshRequestHandle` identity so an older request
    cannot affect a newer one. Watchdogs read the controller directly.
    Cleanup must also handle lazy coroutines cancelled before they start.
    Change playback intent through `updateResumePlaybackRequested` to invalidate
    conflicting refreshes; capture quality-refresh state and create its request
    together on the main thread.
  - `timer/SleepTimerManager.kt`: sleep timer.
  - `engine/datasource/ConditionalHttpDataSourceFactory.kt`: adds platform-specific request headers.
  - `watchdog/PlayerManagerStartupWatchdogExtensions.kt` and
    `lifecycle/PlayerManagerLifecycleExtensions.kt`:
    playback startup watchdogs, foreground/background health audits, failure recovery,
    and USB-exclusive fallback handling.
  - `resolver/netease/PlayerManagerNeteaseAutoSourceSwitch.kt`: Bilibili fallback for NetEase
    tracks that are restricted, have no playable result, or only return previews.
  - `resolver/youtube/YouTubeGoogleVideoRangeSupport.kt`, `YouTubeSeekRefreshPolicy.kt`, and
    `prefetch/YouTubePrefetchRunner.kt`: YouTube Music playback compatibility policies.
  - `metadata/`: lyrics, metadata, and external Bluetooth lyrics handling.
  - Playback state contracts live in the `playback` package of `:model`; pure policy
    decisions belong to `:playback:logic`, while presentation adapters and effects
    belong to `:playback:runtime` and app.
    Shuffle display state is represented by `PlayerQueueDisplayState`; avoid
    returning to implicit index-remapping semantics for a shuffled queue.
  - `usb/`: split into `device/`, `path/`, `session/`, `sink/`, `system/`, and
    `transport/` for USB-exclusive sessions, the native bridge, runtime snapshots,
    and recovery controls. The current implementation covers **UAC1.0** and
    compatible **UAC2.0 Type I PCM** devices, with 32-bit PCM, software
    PCM-float conversion, UAC2 explicit feedback, coordinated AudioSink
    reconfiguration, dynamic transfer scaling, and backpressure stall recovery.

- `modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/download/`
  - `GlobalDownloadManager.kt`: global download tasks and downloaded song list.
  - `ManagedDownloadStorage.kt` is the facade for app-managed and SAF storage.
    Implementation details are split across `storage/commit/`, `delete/`, `lookup/`,
    `migration/`, `recovery/`, `snapshot/`, `tree/`, and `working/`.
  - `task/DownloadTaskStore.kt`: in-memory task presentation, status, progress, and attempt IDs.
  - `execution/persistence/DownloadExecutionRoomStore.kt`: Room persistence for download operations and recovery state.
  - `policy/DownloadLifecyclePolicies.kt`: recovery, cancellation cleanup, and fast-settle policies.
  - `naming/ManagedDownloadNaming.kt`: filename templates and legacy filename compatibility.
  - `metadata/DownloadedAudioTagWriter.kt`: audio tag writing; `catalog/` owns
    downloaded-song catalog access and projections.

- `app/src/main/java/moe/ouom/neriplayer/core/startup/`
  - Startup stages and decisions are split across `app/`, `crash/`, `download/`,
    `logging/`, `permission/`, `player/`, `safemode/`, `sync/`, and `theme/`.
    `MainActivity` coordinates these components with the UI lifecycle.

- `modules/local/src/main/java/moe/ouom/neriplayer/data/`
  - `identity/`: song identity conversion; `SongIdentity` and `SongItem` belong to `modules/model`.
  - `settings/`: `DataStore` settings, KSP schema, and preference mapping; snapshot contracts belong to `:model` under `settings`.
  - `auth/`: shared Web login state and the YouTube credential rotation Worker; platform cookie/auth repositories live in `:platform` under `data/auth` and `platform/youtube/auth`.
  - Platform caches belong to `:platform`; local playlist orchestration and shared cache-table schemas belong to `:local` and `:database`, respectively.
  - `storage/`: storage usage analysis, cache grouping, and extra cache cleanup.
  - `local/playlist/`: local playlist Room reads/writes, legacy JSON upgrades and atomic fallback, system playlist compatibility,
    background metadata hydration, and local artist aggregation.
  - `local/audioimport/`, `local/media/`: local audio import, fast scans,
    background metadata hydration, cover fallback resolution, and sharing.
  - `playlist/favorite/`, `playlist/usage/`: favorite playlists, followed artists,
    and Home continue-listening data.
  - `history/`, `stats/`: recent plays, playback stats, and day/week/month/year/all-time aggregation.
  - `backup/`: playlist JSON backup/import and diff analysis.
  - `config/`: full app config import/export.
  - `sync/host/`: Android repository snapshots, resource resolution, and persistence adapters; `AndroidSyncMergeHost` supplies system-playlist identities and messages through interfaces.
  - `sync/cover/`: cover mapping persistence and legacy JSON import; failed imports retain legacy data and block cleanup.
  - `sync/github/` and `sync/webdav/`: provider backends and compatible Worker entry points; `sync/work/` owns WorkManager, validated-network, and notification adapters.
  - App has no production `data/sync` sources.

- `modules/sync/src/main/java/moe/ouom/neriplayer/`
  - `api/sync/`: GitHub/WebDAV transport, response reading, and conditional writes.
  - `data/sync/store/`: encrypted credentials, preferences, device identity, causal counters, and deletion state.
  - `data/sync/merge/`: merge entry points, host contracts, conflicts, ordering, and statistics under `engine`, `host`, `playlist`, `song`, `history`, and `stats`.
  - `data/sync/runtime/`, `codec/`, `sanitize/`, `change/`, `mapping/stats/`, `remote/`, `retry/`, and `schedule/`: sessions, compatible codecs, sanitization, change detection, stat mapping, remote protection, retries, and Worker policies.
  - Sync payload and conflict contracts belong to `:model`. `verifyDomainDependencies` retains separate transport and calculation allowlists, rejecting direct Android repository or player dependencies.

- `modules/listentogether/src/main/java/moe/ouom/neriplayer/api/ltw/`
  - HTTP, WebSocket, server URL validation, and reconnect policies using protocol models and an injected HTTP client.

- `modules/listentogether/src/main/java/moe/ouom/neriplayer/data/ltw/`
  - `ListenTogetherSessionManager` assembles session components; `playback/` owns queues, authoritative streams, and position synchronization.
  - `control/`, `session/`, `invite/`, `mapping/`, and `validation/` own controls, sessions, invites, song mapping, and input boundaries.
  - Room, event, and transport models live in `modules/model` under `ltw`; `api/ltw` in `modules/listentogether` owns transport, and `listentogether/protocol` owns bounded reading and message codecs.
  - App supplies platform interfaces under `core/di/ltw`; the player supplies playback and song mapping under `core/player/ltw`. Production sources cannot remain in `app/listentogether`.

- `modules/native/src/main/cpp/`
  - Native crash handling lives under `crash/`. USB code is split across
    `usb/exclusive/`, `usb/feedback/`, `usb/iso/`, `usb/pcm/`, `usb/uac1/`,
    and `usb/uac2/`, with matching host tests under `tests/usb/`. Third-party libusb
    and its Android configuration live in `third_party/libusb/`. CMake targets link
    by responsibility; the `:native` AAR supplies `lib_neri.so`.

- `modules/lyrics/src/main/java/moe/ouom/neriplayer/lyrics/lyricon/`
  - Lyricon integration and SuperLyric output for current song, playback state, position,
    word-level lyrics, and translations.
  - The same library's `lyrics/output/` owns the output controller, asynchronous
    request generations, cancellation, and offset snapshots; position anchors and
    coordination state remain internal.

- `app/src/main/java/moe/ouom/neriplayer/navigation/`
  - `LauncherShortcuts.kt` maps app-icon shortcuts to navigation or playback requests.

- `app/src/main/java/moe/ouom/neriplayer/widget/`
  - Home-screen playback widget providers, state snapshots, artwork-derived visuals,
    and RemoteViews updates.

---

### Current Boundaries

- `Explore` is NetEase curated playlists + YouTube Music playlists +
  platform-specific NetEase/Bilibili/YouTube Music search. It is not mixed search.
- `Home` shows local continue-listening, all available NetEase recommendation
  sources, and Radar playlists in the default Chinese mode. Refreshing updates
  each section. International mode prioritizes YouTube Music home shelves.
- The QQ Music entry in `Library` is still a placeholder and does not represent
  full platform integration.
- Local artist categories are aggregated from imported/saved local songs by
  display artist. They are not an online artist directory.
- NetEase artist detail pages depend on NetEase artist metadata and endpoints;
  NetEase, Bilibili, and YouTube creator follows are stored locally and categorized by platform.
  Remote follows from NetEase and YouTube Music are imported manually after every page succeeds
  and the account remains unchanged. Import preserves existing entries and their order, respects
  unfollows during loading, and does not write follows back to the platform.
- `Bilibili` supports search, favorites, audio playback, downloads, comment reading,
  nested replies, and copying. Signing in also enables likes, posting, and replying.
  Link recognition supports selected parts, collection shares, and `season_id`
  context, but this is still not a full Bilibili client.
- `YouTube Music` supports login, anonymous playback, home/playlist browsing,
  details, search, playback, and downloads. Valid identity cookies are preserved
  and rotated when needed; bootstrap, `player.js`, PoToken, and challenge-result
  caches are reused; signature or playback-candidate failures can fall through to EJS/HLS.
  Cache hits and local tests are not proof of real-account or network stability.
  Large seeks in long audio use an expedited startup-recovery window and still
  need real-network qualification.
- Status-bar lyrics depend on private vendor support and only work on select devices.
- The RuntimeShader fluid/audio-reactive background is enabled only on Android 13+.
  Cover blur and advanced blur require Android 12+, so animation changes must
  preserve the fallback path for older versions.
- Phonetic lyric display depends on phonetic lyrics returned by the platform or
  phonetic fields embedded in word-level lyrics. When no phonetic data exists,
  do not synthesize it or render an empty second line.
- Lyric sharing uses `FileProvider` to share lyric card files from the app cache.
  These generated share files are cleanable cache, not user-downloaded content.
- Lyricon/SuperLyric position updates use an independent 200 ms feed anchored to
  elapsed realtime. Changes to playback progress intervals must preserve foreground
  and background lyric timing.
- NetEase playback tries lower qualities when the current quality is unavailable.
  For restricted, missing-URL, or preview-only tracks, it can auto-match a
  Bilibili or local-audio fallback source only when the user enables it; both
  fallback settings are disabled by default.
- NetEase playlist detail cache is only for playlist detail fast display and
  failure fallback. Album details still refresh live and should not reuse playlist cache.
- Local "My Favorite Music" can sync recognizable NetEase songs to NetEase
  Liked Songs. This requires NetEase login and skips unsupported or existing songs.
- Downloads use a shared `OkHttpClient` and write to the app directory or a SAF
  directory. They are **not** handled by the system `DownloadManager`, but they
  do support automatic resume and startup recovery.
- Download queues, cancellation records, and attempt IDs all participate in
  recovery decisions. When changing recovery, make sure stale requests cannot
  clear task state for newer requests.
- Resume behavior depends on transport type:
  - direct downloads resume through working-file size plus `Range`
  - platform-specific explicit chunked downloads resume by byte offset
  - HLS downloads resume from a saved segment checkpoint in `.hls.json`
- Working files live under `files/download_staging/` and also keep `.resume.json`
  metadata so unfinished downloads can be reconstructed after app restart or
  network recovery.
- Manual cancellation rolls back partial artifacts and removes working files.
  Partial data is preserved only for network-policy pauses and recoverable retry paths.
- When the audio body is complete, tag-writing or an unwritable SAF handle still
  finalizes the download without tags; the completed audio must not be deleted.
- The app-private download directory is usually faster than custom SAF directories.
  SAF snapshots and indexes reduce directory walking, and an empty scan must not
  overwrite an existing index. SAF access should not be treated as having the same
  cost as normal file IO.
- Local audio sharing exposes a controlled URI directly when possible; content URIs
  that cannot be shared directly are copied to cache staging, which is cleanable.
- Normal cache cleanup must only delete regenerable cache and share staging.
  Download working files and resume state are managed by download recovery.
  Do not delete recovery files, user-saved audio, downloaded lyrics/covers, or auth
  data through normal cache cleanup.
- Streaming cache and permanent downloads are separate features: cache uses
  `SimpleCache`; downloads are written by `AudioDownloadManager` and
  `ManagedDownloadStorage`.
- GitHub / WebDAV sync only sync metadata. Audio caches, downloaded files, local
  media files, cookies, and playback tokens are not synced.
- Local and synced playlists use `songOrderVersion` to distinguish order semantics:
  `0` is the legacy order and `1` is the current display order. Older data must
  be migrated compatibly instead of being interpreted directly as the new order.
- Sync snapshots may come from older JSON/ProtoBuf payloads or malformed remote
  files. Use safe defaults, filter records without resolvable track identity,
  valid deletion time, or valid playlist id, and never let songs with missing
  `addedAt` sort ahead of songs that already have timestamps.
- V4 writes a Protobuf record stream, ZSTD content chunks, and a root manifest;
  compressed objects are capped at 2 MiB. Legacy JSON, raw GZIP, and Base64 files are
  read only when the manifest is absent. The 8/12/16 MiB safety limits apply only to
  legacy reads. V3 archives are read only for migration, and unsupported future versions must be rejected.
  Every syncing device must support V4; traditional and V3 migration require confirmation.
- GitHub writes through Git Data API blob/tree/commit calls and atomically publishes
  the manifest through GraphQL `updateRefs` with `beforeOid` and `force=false`.
  Base64 in blob requests is only the transport envelope; repository bodies remain
  raw binary, and raw content reads are pinned to a fixed branch head.
- WebDAV creates immutable objects and the initial manifest with `If-None-Match: *`.
  Later manifest writes require `If-Match` with a strong ETag. Stop publication when
  a strong condition is unavailable; do not fall back to unconditional writes.
- Playback and traffic statistics use delayed batch writes. Playback stats flush at
  important player/activity lifecycle points, while traffic accumulators flush when
  a request or download attempt ends; playback totals and daily buckets are retained
  without count eviction. Sync merging must preserve aggregate totals, daily buckets,
  and legacy bucket-only lifting; do not trim a visible window before lifting totals.
- Platform cookies/auth data, GitHub tokens, and WebDAV passwords are encrypted
  with `Android Keystore + EncryptedSharedPreferences`.
- `DataStore` stores regular settings and non-sensitive state, not platform login credentials.
- Long-form progress memory applies only to tracks at least 15 minutes long. Positions
  below 5 seconds are ignored, positions within 30 seconds of the end are cleared,
  explicit playback positions win, and the persisted field is `resumePositionMs`.
  This is not third-party playback history.
- BilibiliSponsorBlock is disabled by default. It sends only a SHA-256 prefix of the
  current BV ID and performs skips locally; it stays disabled during Listen Together,
  and its public results must not enter sync or room state.
- 32-bit high-resolution system output preserves the high-precision pipeline on
  regular Android system output and bypasses loudness normalization, channel
  balance, audio visualization, and in-app speed processing. Check settings copy
  and tests together when changing it.
- USB exclusive playback depends on a compatible **UAC1.0** or
  **UAC2.0 Type I PCM** DAC, the foreground service, wake locks, and the system
  background policy. The in-app background-permission prompt is not decorative,
  so screen-off behavior must stay in scope.
- USB settings include bit-perfect volume mode: software gain remains at 0 dB and
  the DAC hardware controls volume. Do not treat it as ordinary app/system volume.
- USB attach handling is a separate setting. When disabled, both the Activity alias
  and playback-service broadcast entry must skip `USB_DEVICE_ATTACHED`; do not only
  hide the setting or change one entry point.
- If a foreground/background USB runtime report returns `native_refresh_deferred`,
  the player retries it only within a bounded budget; other invalid reports remain
  fail-closed.
- Local scan results may return quick metadata first and then hydrate richer
  title/artist/album/cover data in the background. Do not assume the first scan
  result is the final local metadata shape.
- When `shareAudioLinks=false` in Listen Together, room snapshots and queue items
  must not expose `streamUrl`. Turning the setting off must also clear any cached
  shared candidates immediately, and `REQUEST_LINK` must be rejected.
- Listen Together repeat/shuffle changes use `PLAYBACK_MODE` /
  `REQUEST_PLAYBACK_MODE`. Member controls must validate the target stable track
  key, reject older `clientInstanceId`/`clientSequence`/`clientTimeMs` events, and
  limit `REQUEST_SET_TRACK` to the current queue.
- The current Listen Together track keeps at most three deduplicated HTTP(S) candidates.
  Listeners resolve their own quality policy first; candidates are only a session-scoped
  fallback and must never be written to normal song or offline caches. Server position is
  projected from track duration, with single-track repeat wrapping by duration; rejoining
  with the same member credential must not trigger new-member auto-pause.

---

### Extension Paths

#### 1. Add an Explore search source

Use this when integrating a new platform into `Explore` search or discovery.

1. Implement the client under `platform/<platform>/api` in `:platform`, with caching and business orchestration in the corresponding responsibility packages of the same library. Maintain protocol-to-business package dependency rules.
2. Add request, pagination, and state mapping in `ExploreViewModel`.
3. Add platform tabs and result UI in `ExploreScreen` / host screens.
4. If playback is needed, connect the platform to `PlayerManager` playback resolution.
5. If downloads are needed, complete `AudioDownloadManager` and download metadata mapping.

#### 2. Add a playback metadata completion source

Use this for cover, lyrics, and track metadata completion, not for `Explore`.

1. Implement the `SearchApi` contract under `platform/search/api` in `:platform`; shared music DTOs remain in `:model`.
2. Register the singleton in `AppContainer`.
3. Register routing in the provider for `AppContainer.searchManager`; maintain and test matching and fallback rules under `platform/lyrics` in `:platform`.
4. Add `MusicPlatform`, string resources, and debug probes as needed.

#### 3. Add an online playback platform

1. Use `platform/bilibili/` or `platform/youtube/` in `:platform` as a reference for client and playback repository design.
2. Extend `core/player/engine/datasource/ConditionalHttpDataSourceFactory.kt`
   if special headers are needed.
3. Add the platform under `core/player/url/` and its matching `resolver/` path.
4. Keep downloads, lyrics, covers, and stats separated from transient playback cache.
5. If NetEase Liked Songs sync should support the new source, provide stable
   NetEase song IDs or a verified mapping and reuse candidate validation in
   `LocalPlaylistRepository`.

#### 4. Modify NetEase playback fallback

1. The entry point is the NetEase URL resolution flow in
   `core/player/url/PlayerManagerUrlExtensions.kt`.
2. Matching and scoring live in
   `core/player/resolver/netease/PlayerManagerNeteaseAutoSourceSwitch.kt`.
3. Playback fallback is only for restricted, missing-URL, or preview-only NetEase
   playback. Do not turn it into cross-platform aggregate search.
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
   `SettingsDownloadDirectoryPreflight.kt` owns directory probe timeouts and
   Provider failure classification; the screen consumes the result.
   `SettingsNavigationSearch.kt` owns page switching and search entry points.
   `SettingsThemeControls.kt` owns theme-mode and palette controls, including
   change requests and animation origins. `SettingsPlaybackControlLayout.kt`
   owns playback-control layout dialogs and preference decisions.
   `SettingsPersonalizationContent.kt` subscribes to settings per appearance card.
6. When adding or renaming a setting, update localized strings,
   `SettingsSearchIndex.kt` keywords, Settings page visibility/filtering tests,
   and `AutoSettingsGeneratedTest`.
7. If a setting controls an Activity alias, playback service, system entry point,
   or pre-startup behavior, verify that generated keys, handwritten setters,
   startup snapshots, and the actual entry point all read the same preference.
8. For complex preference models, prefer extracting normalization, bounds, and
   layout math into unit-testable functions instead of keeping the behavior
   implicit inside Compose.
9. Explore search history is stored by `ExploreSearchHistoryRepository`. When
   `explore_search_history_enabled` is off, Explore must hide the history and stop
   new records without silently deleting existing entries on toggle.
10. Lyrics font size is now split between cover and lyrics pages, with separate
   lyric and translation scales. When touching related UI, update
   `SettingsRepository.lyricFontScalesFlow`, `setLyricFontScale(target, scale)`,
   and the matching preview/playback call sites together.

#### 6. Modify USB exclusive playback

1. Read `core/player/usb/sink/UsbExclusiveAudioSink.kt`,
   `core/player/usb/transport/`, `core/player/usb/session/`,
   `core/player/policy/usb/UsbAudioSinkReconfigurationCoordinator.kt`,
   `core/player/watchdog/PlayerManagerStartupWatchdogExtensions.kt`,
   `core/player/lifecycle/PlayerManagerLifecycleExtensions.kt`, and related tests first.
2. The current USB-exclusive implementation supports **UAC1.0** and compatible
   **UAC2.0 Type I PCM** devices. If support expands to more complex UAC2.0
   topologies or non-Type-I PCM devices, update the docs, boundaries,
   diagnostics, and compatibility assumptions together.
3. Consider device selection, sample-rate/bit-depth policies, 32-bit PCM,
   software PCM-float conversion, UAC2 clock topology, explicit-feedback
   endpoints, foreground/background buffers, wake locks, background-permission
   prompts, and the system-fallback path together. Implicit feedback is not a
   supported candidate yet.
4. When changing automatic recovery, keep-alive logic, or background audits,
   validate foreground playback, screen-off background playback, USB attach/detach,
   and Android system fallback paths.
5. When changing feedback clocks, long-gap reacquisition, coordinated
   reconfiguration, dynamic transfer scaling, backpressure recovery, or candidate
   bit-depth fallback, also check
   `UsbExclusiveOutputFormatResolverTest`, `UsbExclusivePcmWritePlannerTest`,
   `UsbExclusiveSessionControllerReusePolicyTest`,
   `UsbAudioSinkReconfigurationCoordinatorTest`, and native USB feedback/PCM/UAC tests.
6. Runtime Report v2 parsing must remain fail-closed. When changing feedback
   endpoint, state, holdover, recovery-action, or generation fields, update the
   Kotlin parser and boundary tests together.
7. If error semantics or recovery behavior changes, update the Settings / Debug
   diagnostics surfaces and the matching tests.
8. If USB attach behavior changes, check `UsbDeviceAttachHandling.kt`,
   `AudioPlayerService.kt`, the Activity alias in `AndroidManifest.xml`, and
   `AutoSettingsSchema.kt` setting generation together.
9. Native changes should run all three host gates plus the four-ABI Android
   compile. Host models, ABI compilation, and real-DAC validation are separate gates.

#### 7. Modify GitHub / WebDAV sync

1. Understand `data/model/sync/SyncDataModels.kt` and
   `data/sync/codec/SyncDataSerializer.kt` in `:sync` compatibility first. Shared payload
   models must not move back into the GitHub provider package.
2. Sync data includes playlists, favorite playlists, recent plays, deletion records,
   playback stats, and independent lyric overrides. V4 uses a Protobuf record stream,
   ZSTD content chunks, and a root manifest, keeping the `neriplayer-sync-v3.manifest` filename.
   V3 archives are read only for migration. Legacy JSON, raw GZIP, and Base64 are read
   only when the manifest is absent. GitHub Git Data API blob requests use Base64 only
   as a transport envelope; repository bodies remain raw binary. Migration preserves all legacy lyrics without a lossy option;
   see `modules/sync/LYRIC_SYNC.md`.
3. `songOrderVersion=0` represents legacy order, while `songOrderVersion=1`
   represents current display order. Serialization, merging, and local restoration
   must preserve the migration path for older data.
4. Playlist membership uses `syncMembershipTokens` / `removedMembershipTokens`
   for observed-remove semantics. New fields must remain readable when legacy JSON
   or ProtoBuf payloads omit them; tokenized membership must not fall back to a
   timestamp-only deletion decision.
   Deletion undo, backup restore, and cross-device sync should be tested together
   so stale deletion records cannot remove restored membership again.
5. Missing-field or malformed snapshots must be cleaned before merging. `SyncSong`
   needs at least one of id, audioId, or mediaUri; deletion records also need a
   valid deletion time; songs with missing `addedAt` are low-priority display items.
6. `CoverUrlMapper.kt` lives in provider-neutral `data/sync/`. Both GitHub and WebDAV
   use `data/sync/merge/engine/SyncDataMerger.kt` for initial uploads and merging snapshots.
   Shared components own business rules; the host resolves messages and system
   playlists. `SyncSession` coordinates conflict retries, local mutation-version checks,
   and persistence confirmation; backends provide transport and remote-version interfaces.
7. Do not break the delayed sync, periodic sync, validated-network checks, or retry
   behavior in `GitHubSyncWorker.kt` / `WebDavSyncWorker.kt`. GitHub must publish the
   manifest against a fixed branch head with an atomic `beforeOid` non-force update,
   failing on conflicts. Later WebDAV manifest writes require `If-Match` with a strong
   ETag; stop publication when safe conditional writes are unavailable.
8. Sensitive data must go through `SecureTokenStorage.kt` or `WebDavStorage.kt`.
   Do not store it in `DataStore` or plaintext JSON.
9. Preference file names, keys, Worker class names, work names, and input keys are upgrade contracts and must remain stable across module moves.
   Device ID reads and first creation share one lock; causal counters and deletion state retain atomic commits.
   Every new sync file enters the directory-wide CRAP gate, failing when any method exceeds 9.

```bash
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :local:verifySyncIntegrationCrap
./gradlew :local:verifyCrap :local:lintDebug
```

#### 8. Modify download storage

1. Read `ManagedDownloadStorage.kt`, `naming/ManagedDownloadNaming.kt`,
   `task/DownloadTaskStore.kt`, `policy/DownloadLifecyclePolicies.kt`, and related
   unit tests first.
2. Consider app-managed storage, SAF custom directories, migration, legacy names,
   metadata files, and `.nomedia`.
3. Download tasks write to `files/download_staging/` before being committed to
   the final directory. `.resume.json` and `.hls.json` are part of resume
   recovery and should not be treated as disposable temp files.
4. Default download concurrency is **6**, configurable from **1-8**.
   When changing concurrency, retry, or network recovery, check
   `DownloadParallelism.kt`, `AudioDownloadManager.kt`, and `GlobalDownloadManager.kt`.
5. Changes to migration, delete semantics, resume checkpoints, or sidecar writes
   must update or add unit tests.

#### 9. Modify lyrics display, sharing, or phonetics

1. Now Playing lyrics mainly live in `ui/component/lyrics/AdvancedLyricsView.kt`,
   `ui/component/lyrics/SyncedLyricsView.kt`, and `NowPlayingScreen.kt`.
2. The full-screen Lyrics page lives in `LyricsScreen.kt`, and lyric sharing reuses
   `LyricShareSheet.kt`.
3. Phonetic display is controlled by the `lyric_translation_use_phonetic` setting,
   requires lyric translation to be enabled, and only works when the current lyrics
   include phonetic data.
4. Japanese lyric translation spacing must distinguish kana from ordinary CJK text.
   When changing it, check both app-side `resolveLyricTranslationExtraGap` and the
   `:accompanist-lyrics-ui` submodule's `resolveJapaneseLyricTranslationTopPadding`.
5. Long-press opens the lyric sharing sheet. When changing gestures, also check
   click-to-seek, manual lyric offset, and advanced lyric viewport scrolling.
6. Lyric cards are shared through `FileProvider` cache files. If the output location
   changes, update `file_paths.xml` and cache cleanup behavior as well.

#### 10. Modify storage usage and cache cleanup

1. Entry points are `data/local/storage/StorageUsageAnalyzer.kt` and
   `SettingsStorageCacheSection.kt`.
2. When adding a cache directory, decide whether it belongs to cleanable cache,
   downloaded content, diagnostics, or app data.
3. Cleanup actions must target regenerable content only. Downloaded songs,
   downloaded lyrics, download indexes, and auth data must not be removed by
   normal cache cleanup.
4. Download staging contains working files, resume state, and HLS checkpoints;
   normal cache cleanup must preserve them. Download recovery handles these files;
   a UI task-state snapshot cannot authorize deleting the entire staging directory.

#### 11. Modify NetEase playlist detail cache

1. The cache entry point is `NeteasePlaylistCacheRepository.kt`; page state is in
   `NeteaseCollectionDetailViewModel.kt`.
2. The cache signature is based on track count and recent track IDs, mainly to
   decide whether the track list can be reused.
3. Network or parse failures may fall back to cache, but manual refresh should keep
   force-refresh semantics.
4. Album details do not use this playlist cache, so keep the data models separated.

#### 12. Modify Lyricon integration

1. SDK integration lives in `modules/lyrics/src/main/java/moe/ouom/neriplayer/lyrics/lyricon/`; output controllers live under `lyrics/output/` in the same module. Playback supplies lyrics, preferences, progress, and lifecycle snapshots rather than managing SDKs.
2. The setting key is `lyricon_enabled`, and playback lifecycle keeps it in sync.
3. Lyrics use `LyricEntry`; word-level data comes from `WordTiming`, and
   translations are matched to original lines by timestamp tolerance.
4. Lyricon/SuperLyric position updates use an independent 200 ms feed anchored to
   elapsed realtime; preserve foreground/background timing when changing progress.
5. Keep Lyricon, SuperLyric, status-bar lyrics, advanced Now Playing lyrics,
   and external Bluetooth lyrics compatible when changing lyric structures.
6. Original and translated Bluetooth lyrics are independent switches. When both
   are active, update title/artist through one atomic snapshot and keep tests for
   track identity, field bounds, whitespace normalization, and duplicate suppression.
7. `bluetooth_metadata_mode` applies to actual Bluetooth output and preserves Dynamic Island
   behavior otherwise. The runtime's `service/car` browser uses a private service binder to
   share the existing framework session. Browsing must not promote a foreground service or
   resume playback; an explicit playback action starts the private playback service.
   Resolve media IDs against the current library. Artwork uses a restricted read-only provider
   with a bounded JPEG cache; clients cannot supply arbitrary files or remote URLs.
   Compat is limited to the browse/search bridge for the existing session.

#### 13. Modify Listen Together

1. Client sessions, HTTP/WebSocket transport, codecs, and bounded reading live together in `modules/listentogether`, under the existing `data/ltw`, `api/ltw`, and `listentogether/protocol` packages. Protocol models remain in `modules/model`. App binds platform capabilities under `core/di/ltw`; player adapters live in `modules/playback/runtime` under `core/player/ltw`.
2. Server logic is under `np-submodule/NeriPlayer-LTW`.
3. Protocol field changes must stay compatible across the Android client and Worker,
   and tests must be updated.
4. When `shareAudioLinks=false`, HTTP and WebSocket room snapshots must not expose
   `track.streamUrl` or `queue[*].streamUrl`, and turning the setting off must
   clear any cached shared candidates immediately.
5. `REQUEST_LINK` / `LINK_READY`, member control, controller-offline recovery,
   and version-gated updates must be reviewed together so older state cannot
   overwrite newer room state.
6. Repeat/shuffle mode uses `PLAYBACK_MODE` / `REQUEST_PLAYBACK_MODE`. Member
   requests and `LINK_READY` must validate the target stable track key so async
   results cannot land on the wrong track.
7. First joins require `joinSecret`; member reconnects require `memberSecret`.
   Never persist either secret in sanitized room state or logs, and treat invite URI
   `secret` values as sensitive input.
8. Control events may carry `clientInstanceId`, `clientSequence`, and `clientTimeMs`.
   The Worker filters stale order, and `REQUEST_SET_TRACK` can only select an item
   already in the current queue. Keep the Android compatibility path aligned.
9. Explicit member departure uses `/api/rooms/:roomId/leave`; the Worker removes
   the member and broadcasts `MEMBER_LEFT`. A normal WebSocket close is transport
   churn and must preserve the credential-bound member for reconnects; an explicit
   controller departure closes the room.
10. Treat the 6-character room ID, 1-24 character nickname, queue limit 2000,
   and request de-duplication as protocol boundaries, not just UI validation details.
11. Settings support custom server URLs and availability tests. Do not hard-code a single server.
12. HTTP control fallback responses must match the server, room, user, and credential snapshot. Leaving, disconnecting, or changing sessions cancels old work; cancellation must not enter error or reconnect handling.
13. Queue mutation construction and replay must preserve duplicate occurrence identity, consecutive insertion order, and the current track. Run the module round-trip tests after changes.

```bash
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
./gradlew :app:testDebugUnitTest --tests "*ListenTogether*"
```

#### 14. Modify main navigation and glass transitions

1. The production main-tab path is shared by `NeriApp.kt` and
   `MainTabLayerHost.kt`; tab order and direction come from
   `resolveMainTabTransitionDirection`.
2. Keep both outgoing and incoming scenes alive during transitions and preserve
   per-tab state with `SaveableStateHolder`. Rapid reverse or repeated requests
   must not clear the old scene before settlement.
3. Each scene owns a separate `MainTabGlassOwner`. Advanced Glass changes must
   keep only visible owners in composition while detail-page handoff remains
   owned by the navigation layer.
4. `coherent_feedback_enabled` defaults to off. Detail pages use a drawer-style
   foreground rise over a slightly recessed background until coherent feedback
   is explicitly enabled.
5. Cover forward, reverse, interrupted, repeated-request, state-restoration, and
   startup-first-frame behavior. Geometry tests must not treat an unlaid-out
   `Rect(0, 0, 0, 0)` scene as a real overlap.
6. At minimum, check `NeriAppMainTabTransitionPolicyTest`,
   `AdvancedGlassNavigationTransitionTest`, `NeriAppNavigationTransitionTest`,
   and `HostNavigationTransitionGeometryTest`.

#### 15. Modify home-screen widgets, launcher shortcuts, or global feedback

1. Widget entry points live in `widget/PlaybackWidgetProviders.kt`; state and
   color extraction live in `PlaybackWidgetState.kt` and `PlaybackWidgetVisuals.kt`.
2. RemoteViews layouts have both regular and API 31 variants. Visual, clipping,
   or preview changes should keep `layout/`, `layout-v31/`, `xml/`, and
   `xml-v31/` aligned.
3. Playback widget actions end in `AudioPlayerService`. New actions must check
   foreground-service start policy, MediaSession refreshes, and empty/buffering
   feedback behavior.
4. Launcher shortcuts are mapped by `navigation/LauncherShortcuts.kt`. New
   shortcuts should update `res/xml/shortcuts.xml`, localized strings, and
   `LauncherShortcutsTest`.
5. Prefer `AppFeedback` / `ViewSnackbar` for global Snackbar behavior. Avoid
   page-local Snackbar hosts that cannot surface above app overlays.
6. Batch playlist export, deletion undo, and sync deletion records are linked.
   Changes in any of these areas should also check `PlaylistExportSheetTest`,
   `AppFeedbackPolicyTest`, `LocalPlaylistRepositoryTest`, and
   `SyncPlaylistDeletionPolicyTest`.

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

Automatic APK packaging waits for the existing JVM, lint, Worker, and emulator jobs and runs the Native Release host check before building. Manual Release verifies the actual `target_ref` checkout with JVM tests, lint, available module-boundary checks, and the Native Release host check before building signed APKs; reports are named with that checkout's SHA. Historical refs use their own native test layout, and a missing configuration in an existing test facility fails verification. Additional emulator, sanitizer, and physical DAC validation follows the risk of the change; physical hardware is not a prerequisite for every release.

Before submitting, consider at least these checks:

1. Debug build:
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. Unit tests:
   ```bash
   ./gradlew :app:verifyCrap
   ```
3. If you changed auth-dependent flows, playback resolution, or other integration-heavy
   behavior, optional smoke tests are available:
   ```bash
   ./gradlew :app:testDebugUnitTest -DrunNeteaseSmoke=true
   ./gradlew :platform:testDebugUnitTest \
     -DrunYouTubePlaybackSmoke=true \
     -DyoutubeSmokeVideoId=VIDEO_ID
   ```
   Replace `VIDEO_ID` with a real video ID. Add `-DyoutubeSmokeForceRefresh=true`
   or `-DyoutubeSmokeCookieFile=/absolute/path/to/cookies.json` when needed.
4. If you changed resources, UI, navigation, settings, sync, or storage logic:
   ```bash
   ./gradlew :app:lintDebug
   ```
5. If you changed Compose UI, permissions, Activity, or login flows:
   ```bash
   ./gradlew :app:connectedDebugAndroidTest
   ```
6. If you changed native USB code, match the dedicated Android Native CI gates:
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
   The host gate runs one fixed CTest inventory. The Android build must also
   produce non-empty `lib_neri.so` outputs for `arm64-v8a`, `armeabi-v7a`,
   `x86`, and `x86_64`.
7. If you changed the Listen Together Worker:
   ```bash
   npm ci --prefix np-submodule/NeriPlayer-LTW
   npm run check --prefix np-submodule/NeriPlayer-LTW
   ```
   `npm run check` runs the Node.js version check, `node --check`, protocol tests, and
   `wrangler deploy --dry-run`. Protocol or room-state changes still need real
   create/join/WebSocket flow verification.
8. Add unit tests to the owning module's `src/test/`; keep host integration tests in `app/src/test/`.
   Add device or Compose UI tests to the owning module's `src/androidTest/`; keep app-host integration tests under `app/src/androidTest/`.
9. If behavior changes affect README, settings copy, user flows, or sync formats,
   update documentation in the same PR.

CRAP gate and responsibility boundaries:

```bash
./gradlew verifyModularization
```

The task checks module boundaries, runs app and owned-library lint and JVM tests,
and combines JaCoCo coverage to report every JVM method mapped to app or library
main source, with a separate list of scores above 8. In
`config/quality/crap-scope.json`, `source_patterns` covers complete source files
and `method_scopes` covers affected methods in the original entry points.
Any scoped method with CRAP above 9 fails the gate; methods outside the scope
remain in the full report. JaCoCo complexity coverage approximates path coverage;
the score does not cover native code or replace a coupling review.
Both `:app:check` and Android CI run the gate. Reports are under
`app/build/reports/crap/`; see the [quality guide](tools_pub/quality/README_EN.md)
for the calculation and prerequisites.

Both `:playback:logic` and `:playback:runtime` expose `verifyCrap` through `build-logic.android.module-quality`.
Each gate uses module-local coverage and the matching rules from the shared scope; an empty selected scope fails.
The policy, runtime, PCM, host interface, USB policy, and widget presentation packages include new files automatically.
App player adapters participate in the combined app gate. Run `:playback:runtime:connectedDebugAndroidTest` for
the migrated lyrics rendering, Room queue, decoder, and playback range instrumentation tests.

`OwnedMainSourceLineBudgetTest` keeps the checked owned main-source files and
components strictly below 2000 physical lines.
It also checks owned `.cpp` / `.h` files under USB `exclusive/`.
Update its file list when adding components. Third-party libusb and test files
are outside this line limit.

`LocalManagementLineBudgetTest` applies the same line limit to download,
local-data, and Library code and related tests. When moving an entry point or
splitting a directory, update its required paths and scanned scope.

Extract state, async jobs, and cleanup into the component responsible for them,
and access external capabilities through narrow interfaces. Player and global
service access for the extracted `PlayerManager` components belongs in their
`PlayerManager*Port` adapters. Listen Together components under `:listentogether`'s `session/` own
membership, room state, connection recovery, and control results; platform and player implementations use injected host interfaces. Wake locks are released with the session, async owners cancel their jobs, and components cannot read the global container.
`NowPlayingScreen`, `SettingsScreen`, and `NeriApp` compose pages and feature
components; editing sessions, directory selection, settings domain bindings,
and navigation effects belong to the corresponding components. Do not move
logic into extension files with the original entry point as receiver or make
new components read its internal state.

Storage code in `:local` uses `source`, `scan`, `accounting`, `cleanup`, and `policy` packages; its contracts belong to `:model` under `storage`.
For storage analysis, `StorageUsageScanner` collects snapshots through data-source
interfaces and `StorageUsagePresenter` reads only snapshots and string resources.
`StorageCacheCleaner` uses file and platform cleanup ports; Room and global
service access stays in `StorageUsageAndroid.kt`. Keep this dependency direction
and include new components in the full-file CRAP gate.

Existing focused tests cover areas such as:

- YouTube login, cookie rotation, anonymous sessions, challenge parsing, PoToken,
  playback, Range/Seek policy, expedited long-seek recovery, and prefetching
- NetEase lyrics, local smoke tests, playback fallback, and playback response parsing
- USB-exclusive keep-alive, startup watchdogs, foreground/background recovery,
  32-bit/float output, UAC2 explicit feedback, long-gap clock reacquisition,
  coordinated reconfiguration, Runtime Report v2, deferred-refresh retry,
  backpressure recovery, USB attach handling, and
  audio-focus policies
- Dual-scene main-tab transitions, rapid reverse switching, drawer/coherent
  detail feedback, glass-owner isolation, and unlaid-out scene geometry filtering
- Home-screen widget state/color/RemoteViews resources, launcher shortcut mapping,
  and app-wide Snackbar overlays
- Download metadata, naming, directory migration, snapshot caches, `.nomedia`, delete semantics, and startup recovery
- Startup stages, notification permission, playback-service startup, history recording, and safe-mode recovery planning
- Local scanning, metadata hydration, cover fallback resolution, system-playlist de-duplication, and stable playlist order
- GitHub/WebDAV sync serialization, missing-field snapshot cleanup,
  legacy playlist-order migration, deletion policy, playback-stat rolling windows,
  aggregate-total merging, legacy bucket-only compatibility,
  WebDAV concurrency fallback, atomic writes, and upload retry
- Long-form progress thresholds, explicit-position precedence, BilibiliSponsorBlock
  local skips, and its Listen Together disable policy
- Listen Together base URL validation, version gating, repeat/shuffle modes,
  stable-track-key target validation, session-only playback candidates, invite/member secrets,
  explicit leave/reconnect behavior, event ordering, playback sync planning,
  session control/cancellation, and protocol compatibility
- Lyrics UI, Japanese kana translation spacing, word timing, external Bluetooth lyrics,
  playback sound controls, and playback policies
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
- By submitting contributions, you agree to distribute your changes at least
  under GPL-3.0.
- The alternative license in `modules/native/src/main/cpp/README.md` covers only the listed
  NeriPlayer-owned native source, not third-party code or other repository content.
- A native PR does not itself grant the alternative license. Contributors who
  agree to dual licensing must record the README's statement in the PR, commit,
  or another auditable form accepted by the copyright holder.
- An external native contribution without that explicit dual-license grant can
  still be accepted under GPL-3.0, but it is excluded from the attribution-based
  closed-source exception.

---

### Communication

- [Issues](https://github.com/cwuom/NeriPlayer/issues): bugs, feature requests, and discussions
- [README_EN.md](./README_EN.md): features and usage
- [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md): community code of conduct

If you plan a large structural change, open an Issue first to align direction.
