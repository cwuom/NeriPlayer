# Implementation Notes

This document was split out of [README_EN.md](../README_EN.md): it covers build versions, module boundaries and key implementation paths.
For installation, capability boundaries and privacy see the README; for extension paths, tests and submission rules see [CONTRIBUTING_EN.md](../CONTRIBUTING_EN.md).

## Build and versions

- `compileSdk = 37`
- `targetSdk = 36`
- `minSdk = 28`
- Java 17 / Kotlin JVM 17; Kotlin `2.4.10` / KSP `2.3.10`
- AGP `9.4.1` / Gradle `9.6.1`
- NDK `27.0.12077973`
- CMake `3.22.1`, pinned by the Android build script
- Version name format: `<git_short_hash>.<MMddHHmm>`
- Release APK filename: `NeriPlayer-<versionName>[-abi].apk`
- Release APKs include `arm64-v8a` only by default. Use `-PbuildAllReleaseAbis=true`
  for multi-ABI output.
- `.github/workflows/android_native_ci.yml` runs Release + `-Werror`,
  ASan+UBSan, and TSan host CTest profiles for native changes, then separately
  compiles `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` Android ABIs.

## Module layout

The project owns 14 Android libraries, registered once in [gradle/owned-modules.txt](../gradle/owned-modules.txt). Top-level directories name business domains; playback and downloads retain separate rule and runtime build boundaries. Remote music, lyric, and metadata sources belong to `:platform`, with `api` describing its internal protocol responsibility. Shared models and host interfaces define public capability contracts.

| Module | Responsibility |
| --- | --- |
| [`:common`](../modules/common/README.md) | utilities, logging, shared text/icons, locale support, and test fixtures. |
| [`:network`](../modules/network/README.md) | HTTP, Range handling, network parsing, and Web login infrastructure. |
| [`:model`](../modules/model/README.md) | business models, enums, and cross-module state contracts without project implementation dependencies. |
| [`:database`](../modules/database/README.md) | Room database, entities, DAOs, historical schemas, and upgrades. |
| [`:native`](../modules/native/README.md) | Native crash handling, DSP effects, USB protocols and PCM calculations, exclusive transport, and third-party libusb; builds and packages `lib_neri.so`. |
| [`:playback:logic`](../modules/playback/logic/README.md) | policy decisions, runtime coordinators, PCM/audio processing, and queue rules with package-level isolation. |
| [`:playback:runtime`](../modules/playback/runtime/README.md) | Media3 engine, service, source resolution, USB output, effects, floating/Bluetooth lyrics, and host integration. |
| [`:download:logic`](../modules/download/logic/README.md) | admission, state transitions, retries, scheduling, permits, ownership, commit rules, and metadata codecs. |
| [`:download:runtime`](../modules/download/runtime/README.md) | execution, Room queues, managed files, migration/recovery, Workers, and JobService. |
| [`:lyrics`](../modules/lyrics/README.md) | LRC/YRC/TTML parsing, transforms, timelines, translation alignment, time offsets, and Lyricon/SuperLyric output coordination. |
| [`:platform`](../modules/platform/README.md) | Bilibili, NetEase, YouTube, and Subsonic music server (preview; not sufficiently tested) protocols, accounts, caches, and business services, plus comments, lyric sources, metadata search, matching, and fallback. |
| [`:local`](../modules/local/README.md) | settings, local media/playlists, history/statistics, backups, traffic, storage accounting, scans, cleanup, and Android sync host adapters. |
| [`:ltwlan`](../modules/ltwlan/README.md) | LAN rooms, BeatSync timing, discovery, permissions and device media volume. |
| [`:sync`](../modules/sync/README.md) | GitHub/WebDAV transport, encrypted credential state, sessions, compatible codecs, merges, concurrency protection, and Worker policies. |
| [`:listentogether`](../modules/listentogether/README.md) | protocol, HTTP/WebSocket transport, identity rules, sessions, reconnects, controls, and playback synchronization. |

`:app` owns Android entry points, Compose screens, and cross-domain assembly. `:ksp-annotations` / `:ksp-processor` generate settings metadata; `:accompanist-lyrics-core` / `:accompanist-lyrics-ui` are upstream lyric modules. `:hidden-api` supplies compile-only API stubs for playback runtime and is not packaged in the app. `build-logic` supplies build tooling. `np-submodule/NeriPlayer-LTW` is the Cloudflare Workers server; the vendored `np-submodule/miuix` source/docs tree is outside the app graph. These are not part of the 14 owned Android libraries.

Dependencies follow responsibilities rather than directory categories. `:model` has no project implementation dependencies, and `:common` has no business-domain dependencies. Database, storage calculations, lyric parsing, and playback/download rules must not depend on repository or Android runtime implementations. Repositories inside `:platform` consume their corresponding protocols; APIs cannot read account repositories, Room, or player state, and each platform retains its package dependency boundary. `:local` consumes the database and domain services. Clients receive account, credential-refresh, and storage capabilities through narrow injected interfaces. No library may depend on app, and the graph must remain acyclic.

Playback policy covers nine leaf packages: `audio`, `command`, `offload`, `pending`, `progress`, `service`, `skip`, `storage`, and `wake`. Policy cannot reference runtime, audio, or queue implementations; USB policy remains in `:playback:runtime`. `:platform` depends on and exports the parsing capabilities of `:lyrics`. Source repositories, matching, and fallback stay in platform; `:lyrics` does not depend on platform. The lyrics library owns external SDKs, position feeds, and asynchronous request generations; playback provides lyrics through a narrow loading port, plus preference snapshots and lifecycle inputs.

Shared contracts live in `modules/model`, retaining existing packages such as `data.model.<business>`. Screen-local state stays with UI, Room entities with the database, and private algorithm records with their implementation. Module directories and Kotlin packages express different scopes. Packages match their internal source directories. Class renames require checking actual save and read paths: persisted Workers, JNI exports, and Android component entry points must remain compatible, while fields, storage keys, and historical schemas remain unchanged.

Modules own tests, resources, and consumer R8 rules; host integration tests stay in app. `:local` owns Room business mappings and local repository orchestration, while platform cache adapters belong to `:platform`. Historical schemas live in `modules/database/schemas`. Settings KSP runs in `:local`; Compose settings rendering stays in [app settings screens](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/). App has no production `data` directory; download upgrades and dependency assembly use `core/startup/legacy` and `core/integration`.

Run `./gradlew verifyModularization` for structural boundaries, JVM tests, combined CRAP coverage, domain dependencies, and lint. `:native` participates in registration, structural checks, and lint. This pure-native module produces no JaCoCo data; coverage remains complete for existing JVM modules. Native host tests and all four ABI builds use the independent CI and module tasks documented in the [:native README](../modules/native/README.md). Each module README lists focused tasks, for example:

```bash
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :playback:logic:verifyCrap :playback:runtime:verifyCrap
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
python3 -B tools_pub/quality/module_boundaries.py
```

Independent and aggregate gates retain the same scopes and threshold: a scoped CRAP score above 9 fails. Library files must stay below 2000 lines, with at most 16 direct Kotlin/Java files per production directory. App packages the FFmpeg AAR and consumes `lib_neri.so` from the `:native` AAR; `:playback:runtime` owns the service and Kotlin implementation. App assembles capabilities in `core/di/player` and `core/di/ltw`. See the [contribution guide](../CONTRIBUTING_EN.md#project-layout) and [quality guide](../tools_pub/quality/README_EN.md).

## Entry point and navigation

- `MainActivity` is the main UI entry point. It handles startup, notification
  permission, external audio imports, USB attach events, and
  `neriplayer://listen-together/join` links.
  Web sign-in uses separate Activities; Bluetooth validation and `CarMediaBrowserService`
  handle car capability validation and library browsing respectively.
- If the previous launch ended with a JVM/native crash or system ANR, the app
  enters `Safe Mode` first and exposes only recovery and export actions.
- The main UI is **Compose NavHost + dynamic bottom bar**:
  `Home / Explore / Library / Settings` are the primary tabs.
- `Home` is displayed dynamically based on available Home cards. `Debug` appears
  only after enabling developer mode.
- Turn off Bilibili music in Home & startup to hide music charts and popular videos
  on Home and stop loading both sections. The preference persists across restarts.
- `MainTabLayerHost` retains outgoing and incoming main-tab scenes. Tablets use
  centered scale and opacity animations, while phones retain horizontal translation. Each scene owns
  saved state and its own
  advanced-glass owner, and interrupted reverse switches continue from the
  current transition progress.
- Detail pages use a drawer-style rise over a slightly recessed background by
  default. Enabling Coherent feedback switches to a coupled background/detail
  handoff.
- `Now Playing` is a full-screen layer above main navigation, with a persistent
  bottom `Mini Player`. The Mini Player supports horizontal swipe for previous/next.
- `LauncherShortcuts` maps app-icon shortcuts to continue playback, Explore,
  Library, and shuffle-favorites requests without bypassing normal navigation
  or playback-service state recovery.
- `Library` uses paged navigation for Local, Favorites, Music Server (preview), NetEase, YouTube Music, and Bilibili; the YouTube Music entry can be disabled in Settings. Recent Plays and Playback Stats are also available.
- Local Library has playlist/artist categories; Favorites has playlist/artist/Hot
  categories; NetEase has playlist/album categories.
- `LocalArtistDetailScreen` handles local artist pages with play-all,
  multi-select, playlist export, and batch downloads for online songs.
  `NeteaseArtistDetailScreen` handles NetEase artist songs/albums and follow state.
- Tablet layouts constrain content width and adjust bottom control
  areas on Now Playing, Lyrics, artist detail, and Settings pages to avoid overly
  wide content and scattered controls.
- Tablet landscape uses a left navigation rail, while portrait keeps bottom navigation.
  Only the selected rail item shows its label. Landscape hides system navigation;
  an edge swipe reveals it temporarily, and portrait restores it.
  Tablet mini players add previous and next buttons; narrow windows retain play/pause and swipe controls.
  Portrait playback limits artwork and control widths to leave more space for lyrics.
  The full Lyrics page uses a centered reading pane and compact controls in portrait.
  Rail destinations are vertically centered. The player overlay naturally covers and reveals the rail during transitions,
  preserving the underlying page width and blurred background.
  Tablets default to bottom playback controls with progress, with lyrics at 125% and
  translations at 115% of phone defaults. Saved font sizes and control positions remain in effect.
- Tablets follow system rotation, while phones allow landscape only in Now Playing.
  Landscape playback adapts artwork, lyrics, and controls to the available height:
  tablets use spacious panes and short phone windows use compact panes with square
  artwork. Phone landscape hides auxiliary
  tools and uses smaller playback buttons; returning to portrait keeps it locked until
  Now Playing is reopened. Landscape lyrics use a transparent pane and leave vertical
  space around the artwork. Only phone landscape hides audio specifications; tablets
  follow existing display preferences. Opening playback dismisses the
  underlying input focus and text selection menu.
  Phone landscape places playback controls and progress at the bottom right and track
  actions at the top right; tablets and portrait keep control placement preferences.
  Phone landscape cover and lyrics pages share the same layout while retaining separate
  font and button-size preferences; horizontal swipes switch between the two pages.
  Tablet landscape keeps a full lyrics pane without swipe-based lyric-style switching.
  Short phone landscape editing keeps a compact cover beside a scrollable form;
  scrolling the fields does not drag the sheet. Song and lyrics editing adapt to
  available height, with scrollable access to all actions when a keyboard or large text reduces space.
- Tablet onboarding and disclaimer screens place guidance beside settings in both
  orientations, with scrollable content and fixed actions.
- Tablet NetEase artist detail places the profile beside songs and albums; playback
  statistics use separate overview cards and rankings, adapting to narrower windows.
  Artist artwork is smaller, with the follow action above the biography and a compact
  gap between the works pane and the mini player.
  Tablet comments leave space above the sheet, with compact landscape headers and
  composers to keep more of the comment list visible.

## Playback, cache, and service

- Playback is based on Media3 ExoPlayer and managed by `PlayerManager`.
- `AudioPlayerService` provides foreground playback, media notifications,
  MediaSession, and basic transport controls.
- Bilibili playback uses `ConditionalHttpDataSourceFactory` to append
  `Referer / User-Agent / Cookie`.
- YouTube Music playback includes platform range-transfer compatibility, seek refresh policy,
  and prefetching. Login cookies, anonymous sessions, bootstrap, `player.js`, and
  PoToken caches are reused, with EJS/HLS fallback after playback-candidate rejection.
  Large seeks in long YouTube audio use a shorter startup-recovery window instead
  of waiting for the normal playback-start watchdog timeout.
- NetEase playback automatically tries lower quality when the current quality is
  unavailable, and can switch to a matched Bilibili or local-audio fallback candidate
  for restricted or preview-only tracks.
- Playback state is persisted periodically for queue and state recovery.
- Player code is split by responsibility across `playback/`, `url/`, `resolver/`,
  `service/`, `effects/`, `lifecycle/`, `watchdog/`, and `usb/`. Shared song
  and playback state contracts are maintained in `:model`.
- Sleep timer, fade-in/fade-out, crossfade-next, and playback mode recovery are
  handled in the player layer.
- Preemptive audio focus, mixed playback, pause on Bluetooth disconnect, and
  USB exclusive playback are stored in playback preference snapshots so they are
  available early in player startup.
- USB exclusive playback currently supports **UAC1.0** and compatible
  **UAC2.0 Type I PCM** devices, with device selection, sample-rate/bit-depth/
  buffer policies, compatibility toggles, and background buffer tuning.
  It can use 32-bit PCM and software PCM-float conversion. Native exclusive
  output tries the exact source rate first and only tries a reported compatible
  rate when the exact format is unavailable and compatibility fallback is enabled.
  Compatible UAC2 asynchronous explicit-feedback topologies resolve the clock
  chain, feedback endpoint, and report period. Runtime Report v2 exposes feedback
  state, endpoint, rate, holdover, and long-gap reacquisition counters. To reduce
  stuck states, the player layer also includes startup watchdogs,
  foreground/background health audits, keep-alive checks, generation-coordinated
  AudioSink reconfiguration, dynamic transfer scaling, native-transfer
  backpressure recovery, and system-output fallback. A deferred native runtime
  refresh is retried only within a bounded budget. Wake behavior follows the
  network or local source, foreground recovery can restore USB-exclusive playback,
  and bit-perfect volume keeps software gain at 0 dB. USB attach handling is
  controlled by a setting; when disabled, both the Activity alias and playback
  service ignore the `USB_DEVICE_ATTACHED` wake path.

## Search and data sources

- **UI search**:
  `Explore` integrates NetEase, Bilibili, and YouTube Music as separate sources.
  NetEase can switch among songs, playlists, and artists; NetEase and Bilibili
  support lazy pagination; Link Recognition accepts direct links and share text
  containing short links. Bilibili video links preserve `p` / `cid` part targets,
  while collection shares prefer the UGC season from video details and fall back
  to the linked `season_id` when needed. Search history is local and can be independently
  hidden and disabled under Settings > General.
- **Metadata completion**:
  the playback screen uses `SearchManager` with NetEase and QQ Music for cover,
  lyrics, and track metadata.
- **Song encyclopedia**:
  `NeteaseSongEncyclopediaRepository` resolves NetEase song identity and caches
  results in memory with an expiry. Track changes cancel old requests and validate
  request generations so stale results cannot replace the current song. Failures in
  optional artist or credit data degrade that content separately; local listening
  statistics remain distinct from online encyclopedia data.
- **Lyrics sources and matching**:
  - **Capabilities & editor matching**: The player supports original, translated, phonetic, and word-timed lyrics, plus lyric sharing and manual editing. Alongside platform lyrics, LRCLIB is integrated as an external client. The lyrics editor provides a dedicated Match entry supporting custom keywords, source selection prior to searching, and per-source temporary caching (changing the source filter updates candidates locally without re-requesting). Matched lyrics strip title, credit, and production-info lines and rank candidates by title, artist, album, duration, and lyric type (preferring word-level lyrics while retaining regular candidates).
  - **Source preferences & fallback**: `Settings > Lyrics Settings > Source Preference` selects the default source (`Automatic` / `NetEase` / `Kugou` / `QQ Music` / `LRCLIB` / `AMLL TTML`) and toggles `Prefer Word-Timed Lyrics` (defaulting to `Automatic` with word-level preference enabled). Searching a specific source by title and artist validates track duration, falling back to automatic lyrics when duration is unknown or mismatched. Online tracks display cached lyrics first and query the preferred source in the background, replacing the lyrics on match or retaining existing lyrics on miss. Changing preferences clears cached results immediately.
  - **Bilibili CC subtitles**: Controlled by a standalone setting, enabled by default, and requires a signed-in Bilibili account.
  - **Phonetic completion**: When the active lyric source lacks phonetic lyrics, the player attempts to backfill phonetics from NetEase while preserving the current source's original and translated text; native source phonetics and user edits/clears always take precedence.
  - **Default lyric offsets**: The `Lyric Offset` card adjusts default offsets across 6 sources with individual or batch reset:

| Lyric Source | Default Offset |
| :--- | :--- |
| NetEase | `1000 ms` |
| QQ Music | `500 ms` |
| Kugou / LRCLIB / AMLL TTML / Bilibili CC | `0 ms` |

- **Lyricon integration**:
  `LyriconManager` outputs the current song, playback state, position,
  word-level lyrics, and translated lyrics to Lyricon and SuperLyric. Position is
  sent through an independent 200 ms feed loop anchored to elapsed realtime. Translation
  lines are matched by timing, lyric-card caching is retained, and song sharing can produce
  Xiaomi Super Island URLs.
  Status-bar lyrics depend on vendor support and currently target select devices.
- **Artist entry points**:
  NetEase search, Home, playlist/album detail pages, and Now Playing try to keep
  `neteaseArtists` metadata so the UI can open NetEase artist detail pages.
- **NetEase playlist cache**:
  `NeteasePlaylistCacheRepository` caches playlist headers, tracks, recent-track
  signatures, and save time. `NeteaseCollectionDetailViewModel` can publish cached
  data before refreshing the network and reuse the cache when the signature is
  unchanged or a network/parse failure occurs.

## Local data and security

- General settings use `DataStore`. KSP generates setting keys, backup allowlists,
  and settings UI metadata.
- Theme mode is represented by `ThemeMode`, with light, dark, and Auto
  follow-system behavior.
- Platform cookies, YouTube auth data, GitHub tokens, and WebDAV passwords are
  stored locally with `Android Keystore + EncryptedSharedPreferences`.
- Local playlists, recent plays, playback stats, and favorite snapshots primarily
  persist through `Room`; `:database` owns entities, DAOs, and migrations.
- Legacy JSON remains an upgrade-import and failure-fallback path. Playlist JSON
  import/export and remote sync formats are maintained by their own flows;
  fallback files still use atomic writes.
- Favorites and playback stats initially load on IO coroutines. A primary-store
  read failure preserves original data; related writes, backups, and sync require
  a complete successful load and can retry on later operations.
- Sync payloads shared by GitHub and WebDAV live in `:model` under `data/model/sync/`.
  `:sync` owns transport, encrypted credential state, sessions, compatible codecs,
  sanitization, merging, conflict handling, and Worker policies. Android repository
  and WorkManager adapters belong to `:local`.
  GitHub/WebDAV managers connect to the shared session through backend interfaces; app has no sync implementation tree.
  Deletion records and undo operations participate in the same merge policy so
  locally restored songs are not removed again by stale deletion records on the
  next sync.
- GitHub/WebDAV sync uses a locally generated UUID as the device identifier,
  not `ANDROID_ID`.
- Opening the app immediately queues persistent sync work for configured providers with auto sync enabled, including during playback. When both providers are enabled, WebDAV starts with a 10-second delay. Automatic settings and sync-format upgrade approval still control execution.
- GitHub transport and V4 archive format:
  - Sync archives use a Protobuf record stream with ZSTD content chunks capped at 2 MiB each. A root manifest commits the complete snapshot, reusing unchanged chunks without re-uploading.
  - Eligible commits within the fast-path budgets publish atomically via GraphQL `createCommitOnBranch` with `expectedHeadOid`. Other commits write binary blobs, trees, and commits via the Git Data API, then advance the default branch atomically via GraphQL `updateRefs` (`beforeOid`, `force=false`). Selection depends on the current changes, rather than the total archive size.
  - Repositories store raw binary bodies read via the raw endpoint pinned to a fixed remote commit; Base64 is used only as the API transport envelope.
- New installations default to V4. Existing installations with configured sync targets show the upgrade dialog on their first updated launch;
  newly configured targets prompt when traditional cloud data or V3 is detected. The format is not backward compatible:
  update every participating device before confirming. Confirmation immediately syncs and migrates that target.
  Migration preserves all legacy lyrics; the permanent lossy compression option has been removed.
  The upgrade prompt disappears after that target reaches V4; failures and cancellations remain retryable.
  Deferring keeps the app usable while sync for targets awaiting upgrade stays paused. Offline device versions
  cannot be verified automatically.

## Downloads, local import, and backups

- Downloads use a shared `OkHttpClient`, not the system `DownloadManager`.
- Default download concurrency is **6**, configurable up to **8** in Settings.
- Downloads are first written into `files/download_staging/` working files, then
  committed into the app-managed directory or a user-selected SAF directory.
  Audio metadata is prepared before commit, and lyrics, covers, `.npmeta.json`,
  and audio tags are written after the audio file is finalized.
- `DownloadExecutionRoomStore` persists download operations and recovery state
  through Room; `DownloadTaskStore` owns in-memory task presentation and progress.
  On startup, `GlobalDownloadManager` waits for active/queued work to settle before restoring
  unfinished tasks, so stale queues and new requests do not overwrite each other.
- If completed audio can be found quickly through the download index or cached
  snapshot, the task is settled as complete without re-fetching the stream or
  repeatedly probing SAF storage.
- Downloads support **automatic resume**, but the strategy depends on transport type:
  - **Direct HTTP transfers** resume through `Range: bytes=<offset>-`
  - **Chunked range transfers** resume by byte offset, mainly for platform candidates
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
  directory walks. An empty SAF listing cannot clear an existing directory index.
  Android SAF access is still much slower than the app-private directory, so custom
  directories are recommended only when they are really needed.
- `StorageUsageAnalyzer` groups storage into cleanable cache, downloaded content,
  diagnostics, and app data. Cache cleanup removes regenerable cache and share
  staging while preserving download working files, recovery state, and downloaded songs.
- `LocalAudioImportManager` imports external audio, scans device music, and copies
  nearby `lrc/txt` lyrics and `cover/folder/front` images.
- Local-song sharing exposes a controlled directory URI directly when possible;
  SAF/content URIs that cannot be exposed are staged under
  `cache/shared_local_media`, which is cleanable cache.
- Local scan previews can filter to tracks that already have metadata. After a
  local playlist is created or enriched, the app can continue hydrating titles,
  artists, albums, and covers in the background while preserving edited local metadata.
- Download "metadata post-processing" can be disabled separately. When disabled,
  NeriPlayer still keeps management metadata, but stops writing tags, lyrics,
  and covers back into the audio file itself.
- `BackupManager` supports playlist JSON export/import and diff analysis.
- Configuration exports exclude login state by default. You can include music-service authorization
  and GitHub/WebDAV credentials, and optionally encrypt the file with a password.
  Imports accept legacy plaintext files; encrypted files require the original password. Files without
  login state preserve existing accounts and sync credentials on the device.

---

For release build and signing details, see [CONTRIBUTING_EN.md](../CONTRIBUTING_EN.md#release-build).
