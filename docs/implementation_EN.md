# Implementation Notes

This document continues from [README_EN.md](../README_EN.md) and covers build versions, module boundaries and key implementation paths.

- Installation, capability boundaries and privacy: see the README.
- Extension paths, tests and submission rules: see [CONTRIBUTING_EN.md](../CONTRIBUTING_EN.md).

## Build and versions

| Item | Version |
| :--- | :--- |
| `compileSdk` / `targetSdk` / `minSdk` | `37` / `36` / `28` |
| Java / Kotlin JVM | 17 |
| Kotlin / KSP | `2.4.10` / `2.3.10` |
| AGP / Gradle | `9.4.1` / `9.6.1` |
| NDK | `27.0.12077973` |
| CMake | `3.22.1`, set by the Android build script |

- Version name format: `<git_short_hash>.<MMddHHmm>`.
- Release APK filename: `NeriPlayer-<versionName>[-abi].apk`.
- The default Release APK contains `arm64-v8a` only. Add `-PbuildAllReleaseAbis=true` for multi-ABI output.

`.github/workflows/android_native_ci.yml` runs on native changes:

- Three host CTest profiles: Release + `-Werror`, ASan + UBSan, and TSan.
- Separate compilation for four Android ABIs: `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`.

## Module layout

The project owns 14 Android libraries, organized by business domain and registered in [gradle/owned-modules.txt](../gradle/owned-modules.txt).

- Top-level domains live under `modules/<domain>`.
- Playback and downloads are each split into a rules build (`logic`) and a runtime build (`runtime`).
- Remote music, lyric and metadata sources live in `:platform`, where `api` holds the internal request-protocol layer.
- Public capability contracts are expressed through models and host interfaces.

| Module | Responsibility |
| --- | --- |
| [`:common`](../modules/common/README.md) | Utilities, logging, shared text/icons, locale support, and test fixtures. |
| [`:network`](../modules/network/README.md) | HTTP, Range handling, network parsing, and Web login infrastructure. |
| [`:model`](../modules/model/README.md) | Business models, enums, and cross-module state contracts, with no project implementation dependencies. |
| [`:database`](../modules/database/README.md) | Room database, entities, DAOs, historical schemas, and upgrades. |
| [`:native`](../modules/native/README.md) | Native crash handling, DSP effects, USB protocols and PCM calculations, exclusive transport, and third-party libusb; builds and packages `lib_neri.so`. |
| [`:playback:logic`](../modules/playback/logic/README.md) | Policy decisions, runtime coordinators, PCM/audio processing, and queue rules, with package-level isolation. |
| [`:playback:runtime`](../modules/playback/runtime/README.md) | Media3 engine, service, source resolution, USB output, effects, floating/Bluetooth lyrics, and host integration. |
| [`:download:logic`](../modules/download/logic/README.md) | Admission, state transitions, retries, scheduling, permits, ownership, commit rules, and metadata codecs. |
| [`:download:runtime`](../modules/download/runtime/README.md) | Execution, Room queues, managed files, migration/recovery, Workers, and JobService. |
| [`:lyrics`](../modules/lyrics/README.md) | LRC/YRC/TTML parsing, transforms, timelines, translation alignment, time offsets, and Lyricon/SuperLyric output coordination. |
| [`:platform`](../modules/platform/README.md) | Protocols, accounts, caches and business services for Bilibili, NetEase, YouTube and Subsonic music server (preview; not sufficiently tested), plus comments, lyric sources, metadata search, matching, and fallback. |
| [`:local`](../modules/local/README.md) | Settings, local media/playlists, history/statistics, backups, traffic, storage accounting, scans, cleanup, and Android sync host adapters. |
| [`:sync`](../modules/sync/README.md) | GitHub/WebDAV transport, encrypted credential state, sessions, compatible codecs, merges, concurrency protection, and Worker policies. |
| [`:listentogether`](../modules/listentogether/README.md) | Protocol, HTTP/WebSocket transport, identity rules, sessions, reconnects, controls, and playback synchronization. |

These build units are not counted among the 14 owned libraries:

- `:app`: Android entry points, Compose screens and cross-domain assembly.
- `:ksp-annotations` / `:ksp-processor`: settings metadata generation.
- `:accompanist-lyrics-core` / `:accompanist-lyrics-ui`: upstream lyric modules.
- `:hidden-api`: compile-only API stubs for the playback runtime, not packaged in the app.
- `build-logic`: build tooling.
- `np-submodule/NeriPlayer-LTW`: the Listen Together Cloudflare Workers server.
- `np-submodule/miuix`: vendored upstream source and docs, outside the app build graph.

### Dependency rules

Dependencies follow responsibilities, not directory categories.

- `:model` has no project implementation dependencies, and `:common` has no business-domain dependencies.
- Database, storage calculations, lyric parsing, and playback/download rules do not depend on repositories or Android runtime implementations.
- Repositories inside `:platform` consume their own protocols. APIs do not read account repositories, Room, or player state, and each platform keeps its package dependency boundary.
- `:local` consumes the database and domain services. Account, credential-refresh, and storage capabilities are injected through narrow interfaces.
- No library depends on app, and the dependency graph is acyclic.

Playback policy and lyrics boundaries:

- Policy has nine leaf packages: `audio`, `command`, `offload`, `pending`, `progress`, `service`, `skip`, `storage` and `wake`. It does not reference runtime, audio or queue implementations.
- USB policy lives in `:playback:runtime`.
- `:platform` depends on and exports the parsing capabilities of `:lyrics`. `:lyrics` does not depend on platform.
- Lyric source repositories, matching and fallback live in platform.
- The lyrics library owns the Lyricon SDK, position feeds and asynchronous request generations.
- Playback supplies lyrics, preference snapshots and lifecycle inputs through a narrow loading port.

### Contracts and directory ownership

- Shared contracts live in `modules/model`, using packages such as `data.model.<business>`.
- Screen-local state stays with the UI, Room entities with the database, and private algorithm records with their implementation.
- Module directories and Kotlin packages express different scopes. Packages match their internal source directories.
- When renaming classes, check the actual save and read paths.
- Persisted Worker tasks, JNI exports and Android component entry points keep compatible names.
- Fields, storage keys and historical schemas do not change with directory reorganization.

Resource ownership:

- Each module owns its tests, resources and consumer R8 rules. Host integration tests stay in app.
- `:local` owns Room business mappings and local repository orchestration. Platform cache adapters belong to `:platform`.
- Historical schemas live in `modules/database/schemas`.
- Settings KSP runs in `:local`. Compose settings rendering lives in [app settings screens](../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/).
- App has no production `data` directory. Download upgrades and dependency assembly live in `core/startup/legacy` and `core/integration`.

### Quality gates

`./gradlew verifyModularization` checks, in order:

- Structure and JVM tests.
- Combined CRAP coverage.
- Domain dependencies and lint.

`:native` participates in registration, structural checks and lint. This pure-native module produces no JaCoCo data. Native host tests and the four-ABI build run through the independent CI and module tasks described in the [:native README](../modules/native/README.md).

Each module README lists focused tasks, for example:

```bash
./gradlew :platform:verifyCrap :platform:verifyDomainDependencies :platform:lintDebug
./gradlew :lyrics:verifyCrap :lyrics:lintDebug
./gradlew :playback:logic:verifyCrap :playback:runtime:verifyCrap
./gradlew :sync:verifyCrap :sync:verifyDomainDependencies :sync:lintDebug
./gradlew :listentogether:verifyCrap :listentogether:verifyDomainDependencies :listentogether:lintDebug
python3 -B tools_pub/quality/module_boundaries.py
```

- Independent and aggregate gates share the same scope and threshold. A checked method with a CRAP score above 9 fails.
- Library source files stay below 2000 lines, with at most 16 direct Kotlin/Java files per production directory.
- App packages the FFmpeg AAR. `lib_neri.so` comes from the `:native` AAR and is packaged with app.
- `:playback:runtime` owns the playback service and Kotlin implementation. App assembles them through `core/di/player`, `core/di/ltw` and similar interfaces.

For extension rules, see the [contribution guide](../CONTRIBUTING_EN.md#project-layout) and the [quality guide](../tools_pub/quality/README_EN.md).

## Entry point and navigation

### App entry points

- `MainActivity` is the main UI entry point.
- It handles startup, notification permission, external audio imports, USB attach events and `neriplayer://listen-together/join` links.
- Web sign-in uses separate Activities.
- The Bluetooth validation entry covers car capability validation, and `CarMediaBrowserService` covers library browsing.
- `LauncherShortcuts` maps app-icon shortcuts to continue playback, Explore, Library and shuffle favorites.
- Shortcuts go only to a main tab or the playback service entry, using normal navigation state recovery.

### Safe Mode

If the previous launch ended with a JVM/native crash or system ANR, the app enters `Safe Mode` first. It offers:

- Log preview and export
- Configuration export
- Sign-in reset
- Settings reset
- Return to normal startup

### Main navigation

- The main UI is **Compose NavHost + dynamic bottom bar**. The primary tabs are `Home / Explore / Library / Settings`.
- `Home` appears according to Home card availability. `Debug` appears only after developer mode is enabled.
- Turning off Bilibili music in Home & startup hides music charts and popular videos on Home and stops loading both sections. The setting persists across restarts.
- `MainTabLayerHost` keeps outgoing and incoming main-tab scenes. Each scene owns its saved state and advanced-glass owner, and a reversed switch continues from the current progress.
- Tab transitions: tablets use centered scale plus fade, phones use horizontal translation.
- Detail pages open with the background slightly recessed and the foreground rising from below. With Coherent feedback on, background and detail move together.
- `Library` uses paged navigation for Local, Favorites, Music Server (preview), NetEase, YouTube Music and Bilibili, with Recent Plays and Playback Stats entries.
- The YouTube Music entry can be disabled in Settings.
- Local Library and Favorites have secondary categories:

| Page | Categories |
| :--- | :--- |
| Local Library | Playlists / Artists |
| Favorites | Playlists / Artists / Hot |
| NetEase | Playlists / Albums (cloud entry when signed in) |

- `LocalArtistDetailScreen` serves local artist pages with play-all, multi-select, playlist export and batch downloads for online songs.
- `NeteaseArtistDetailScreen` serves NetEase artist pages with song/album paging and follow state.

### Now Playing

- `Now Playing` is a full-screen layer above main navigation, not a regular route.
- A `Mini Player` stays at the bottom and supports horizontal swipe to change tracks.
- Artwork stays square.
- Opening Now Playing dismisses underlying input focus and the text selection menu.
- Song and lyrics editing sheets arrange inputs and actions by available height. When a keyboard or large text reduces space, all actions stay reachable by scrolling.

### Tablet adaptation

- Now Playing, Lyrics, artist detail and Settings limit content width on tablets and adjust the bottom action area.
- Landscape uses a left navigation rail, and portrait uses bottom navigation.
- The rail shows a label only for the selected item, and its option group is vertically centered.
- Landscape hides system navigation. An edge swipe reveals it temporarily, and portrait restores it.
- The tablet mini player adds previous and next buttons. Narrow windows keep only play/pause and swipe.
- Portrait Now Playing limits artwork and control widths to leave room for lyrics.
- The portrait Lyrics page uses a centered reading pane and compact controls.
- The player overlay covers the rail during transitions, keeping the underlying page width and blurred background stable.
- Without manual settings, controls sit at the bottom with a progress bar. Lyrics and translations default to 125% and 115% of phone sizes.
- Tablets follow system rotation.
- Landscape Now Playing uses spacious two-pane layouts and always shows the full lyrics pane, without swipe switching of lyric styles.
- Landscape audio specification display follows the display setting.

### Phone landscape

- Phones allow landscape only in Now Playing. Returning to portrait stays portrait until Now Playing is reopened.
- Artwork, lyrics and controls adapt to the available height, using compact two-pane layouts.
- The compact layout hides the auxiliary toolbar and shrinks playback buttons.
- Playback controls and progress sit at the bottom right, and track actions at the top right.
- Tablets and portrait use the playback control position preference.
- The cover page and lyrics page share one layout, each keeping its own font and button-size settings. Horizontal swipes switch between them.
- The lyrics pane is transparent, artwork has vertical spacing, and audio specifications are hidden.
- In short landscape windows, the song edit sheet uses a compact cover and a separately scrolling form. Scrolling the form does not drag the sheet.

### Onboarding and tablet detail pages

- Tablet onboarding and disclaimer screens use a guidance/settings two-pane layout in both orientations, with scrollable content and fixed actions.
- Tablet NetEase artist detail uses a profile/works two-pane layout, with the follow button above the biography.
- Playback statistics use separate overview cards and rankings, adapting to narrow windows.
- Tablet comment sheets leave space at the top. Landscape headers and composers are compact to keep more of the comment list visible.

## Playback, cache, and service

### Playback core

- Playback is based on Media3 ExoPlayer and managed by `PlayerManager`.
- `AudioPlayerService` provides foreground playback, media notifications, MediaSession and basic transport controls.
- Player code is split by responsibility across `playback/`, `url/`, `resolver/`, `service/`, `effects/`, `lifecycle/`, `watchdog/` and `usb/`.
- Shared song and playback state contracts are maintained in `:model`.
- Playback state is persisted periodically, restoring the queue and state after a process restart.
- The player layer handles the sleep timer, fade-in/fade-out, crossfade-next and playback mode recovery.
- Preemptive audio focus, mixed playback, pause on Bluetooth disconnect and USB exclusive playback take effect early in player startup through playback preference snapshots.

### Bilibili

- `ConditionalHttpDataSourceFactory` attaches `Referer` and `User-Agent` to media CDN requests.
- Cookies go only to trusted HTTPS CDN hosts.
- The overseas COS CDN uses `Range: bytes=0-` when the whole audio is requested from the start.
- The Akamai backup CDN uses public request headers.
- After the last candidate fails with an HTTP error, the player refreshes the stream URLs once, until playback advances or a new playback request starts.

### YouTube Music

- Playback includes platform range-transfer compatibility, a seek refresh policy and prefetching.
- Playback resolution reuses login cookies, anonymous sessions, bootstrap, `player.js` and PoToken caches.
- A rejected playback candidate falls back to EJS/HLS.
- Large seeks in long audio use a shorter startup-recovery window, skipping the normal playback-start watchdog timeout.

### NetEase

- Playback lowers the quality automatically when permission, a playable result or a full track is unavailable.
- If the track is still unplayable, a Bilibili source or local audio is matched automatically, according to settings.

### USB exclusive playback

Supported devices and options:

- Devices: **UAC1.0** and compatible **UAC2.0 Type I PCM**.
- Options: device, sample rate, bit depth, buffer policy, compatibility toggles, 32-bit PCM, software PCM-float conversion, and background buffer.
- Native exclusive output tries the exact source rate first. If that format is unavailable and compatibility fallback is on, it tries a rate the device reports as compatible.
- With bit-perfect volume on, software gain is fixed at 0 dB.

UAC2 asynchronous explicit-feedback topologies:

- The clock chain, feedback endpoint and report period are resolved.
- Runtime Report v2 exposes feedback state, endpoint, rate, holdover and long-gap reacquisition counters.

Safeguards in the player layer:

- Startup watchdogs, foreground/background health audits and keep-alive checks.
- Generation-coordinated AudioSink reconfiguration.
- Dynamic transfer scaling and native-transfer backpressure recovery.
- A bounded number of retries when a deferred native runtime refresh is read in foreground or background.
- System-output fallback when needed.
- Wake behavior chosen by network or local source.
- USB exclusive playback restored after foreground/background switches.

USB attach handling is controlled by a setting. When it is off, both the Activity alias and the playback service ignore the `USB_DEVICE_ATTACHED` wake path.

## Search and data sources

### UI search

- `Explore` integrates NetEase, Bilibili and YouTube Music as separate sources, without mixing results.
- NetEase can switch among songs, playlists and artists. NetEase and Bilibili support lazy pagination.
- Link recognition accepts direct links and share text containing short links.
- Bilibili video links keep the `p` / `cid` part target.
- Bilibili collection shares prefer the UGC season from video details and fall back to the linked `season_id`.
- Search history is stored locally. Display and recording can be turned off independently under Settings > General.

### Metadata and encyclopedia

- The playback screen uses `SearchManager` with NetEase and QQ Music to fill in cover, lyrics and track metadata.
- `NeteaseSongEncyclopediaRepository` loads the encyclopedia by NetEase song identity and caches it in memory with an expiry.
- A track change cancels old requests and validates request generations, so stale results never replace the current song.
- When optional data such as artists or credits fails to load, only that content degrades.
- Local listening statistics and the online encyclopedia are shown separately.

### Lyrics

Capabilities:

- Original, translated, phonetic and word-timed lyrics, lyric sharing and manual editing.
- Besides platform lyrics, LRCLIB is integrated as an external lyrics client.

The Match entry in the lyrics editor:

- Search keywords can be edited, or a source can be chosen before searching.
- Results are cached temporarily per source, and switching the source filter reuses the cache.
- Title, credit and production-info lines are stripped automatically.
- Candidates are ranked by title, artist, album, duration and lyric type, with word-timed lyrics first and regular candidates retained.

Source preference (`Settings > Lyrics Settings > Source Preference`):

- The default source can be `Automatic` / `NetEase` / `Kugou` / `QQ Music` / `LRCLIB` / `AMLL TTML`.
- `Prefer Word-Timed Lyrics` can be toggled.
- Defaults are `Automatic` with word-timed preference on.
- Searching a specific source by title and artist validates track duration. An unknown or mismatched duration falls back to automatic lyrics.
- Online tracks show cached lyrics first and query the chosen source in the background.
- A match replaces the lyrics, and a miss keeps the existing ones.
- Changing preferences clears cached results immediately.

Other lyric sources:

- **Bilibili CC subtitles**: a standalone setting, on by default, effective after signing in to Bilibili.
- **Phonetic completion**: when the active source has no phonetics, they are filled in from NetEase while the current source's original and translated text is kept.
- Phonetics supplied by the source, and phonetics edited or cleared by the user, take precedence over completion.

**Default lyric offsets**: the `Lyric Offset` card adjusts offsets for 6 sources, with per-source reset and reset all.

| Lyric Source | Default Offset |
| :--- | :--- |
| NetEase | `1000 ms` |
| QQ Music | `500 ms` |
| Kugou / LRCLIB / AMLL TTML / Bilibili CC | `0 ms` |

### Lyricon integration

- `LyriconManager` sends the current song, playback state, position, word-timed lyrics and translated lyrics to Lyricon and SuperLyric.
- Position is pushed by an independent 200 ms feed loop and calibrated against an elapsed-realtime anchor.
- Translation lines are matched by timing, and lyric-card caching is kept.
- Song sharing can produce Xiaomi Super Island URLs.
- Status-bar lyrics depend on vendor support and target selected devices.

### NetEase

- **Artist entry points**: search, Home, playlist/album detail pages and Now Playing keep `neteaseArtists` metadata, used to open artist detail pages.
- **Playlist cache**: `NeteasePlaylistCacheRepository` caches playlist headers, tracks, recent-track signatures and save time.
- `NeteaseCollectionDetailViewModel` publishes cached data before refreshing the network, and reuses the cache when the signature is unchanged or the network or parsing fails.
- **Cloud storage**: `NeteaseCloudViewModel` pages through `/weapi/v1/cloud/get`.
- Each item becomes a playable `SongItem` from `simpleSong`, falling back to file name and item metadata.
- Cloud tracks resolve playback URLs the same way as regular NetEase tracks.

## Local data and security

### Local storage

- General settings use `DataStore`. KSP generates setting keys, backup allowlists and settings UI metadata.
- Theme mode is managed by `ThemeMode`: light, dark, and Auto (follow system).
- Platform cookies, YouTube auth data, GitHub tokens and WebDAV passwords are stored encrypted with `Android Keystore + EncryptedSharedPreferences`.
- Local playlists, recent plays, playback stats and favorite snapshots persist mainly through `Room`. `:database` owns entities, DAOs and migrations.
- Legacy JSON serves as an upgrade-import and failure-fallback path, and fallback files use atomic writes.
- Playlist JSON import/export and remote sync formats are maintained by their own flows.
- Favorites and playback stats first load on IO coroutines.
- If the primary store fails to read, the original data is kept. Related writes, backups and sync wait for a successful load and can retry on later operations.

### Sync architecture

- Sync payload models shared by GitHub and WebDAV live in `:model` under `data/model/sync/`.
- `:sync` owns transport, encrypted credential state, sessions, compatible codecs, sanitization, merging and scheduling policies.
- Android repository and WorkManager adapters belong to `:local`.
- GitHub/WebDAV managers connect to the shared session through backend interfaces.
- Deletion records and undo operations share one merge policy, so a locally restored song is not removed again by a stale deletion record.
- The device identifier is a locally generated UUID, not `ANDROID_ID`.

Automatic sync:

- With auto sync enabled for a configured provider, opening the app immediately queues persistent sync work, which also runs during playback.
- When both providers are enabled, WebDAV starts 10 seconds later.
- The auto sync setting and sync-format upgrade approval decide whether the work may run.

### GitHub sync and the V4 archive

- Archives use a Protobuf record stream with ZSTD content chunks, each compressed object at most 2 MiB.
- A root manifest commits the complete snapshot, and unchanged chunks are reused without re-uploading.
- Repositories store raw binary bodies, read through the raw endpoint pinned to a fixed remote commit.
- Base64 in API requests is only a transport envelope.

The commit path depends on the current changes, not on total archive size:

- Eligible commits within the fast-path budgets publish atomically via GraphQL `createCommitOnBranch` with `expectedHeadOid`.
- Other commits first write binary blobs, trees and commits through the Git Data API.
- The default branch then advances atomically via GraphQL `updateRefs` (`beforeOid`, `force=false`).

### Sync format upgrade

- New installations default to V4.
- Existing installations with a configured sync target show the upgrade dialog on first launch of the new version.
- Newly configured targets prompt for an upgrade when traditional cloud data or V3 is detected.
- The new format is not backward compatible. Update every participating device before confirming.
- Confirming syncs immediately and migrates that target's cloud data, preserving all legacy lyrics.
- After a target reaches V4, its upgrade prompt disappears. Failures and cancellations can be retried.
- While an upgrade is deferred, the app stays usable and sync for that target is paused.
- The client cannot verify the versions of offline devices automatically.

## Downloads, local import, and backups

### Download flow

- Downloads use a shared `OkHttpClient`, not the system `DownloadManager`.
- Default concurrency is **6**, adjustable in Settings up to **8**.
- Files are written first to working files under `files/download_staging/`, then committed to the app-managed directory or a user-selected SAF directory.
- Audio metadata is prepared before commit. Lyrics, covers, `.npmeta.json` and audio tags are written after.
- `DownloadExecutionRoomStore` persists download operations and recovery state through Room.
- `DownloadTaskStore` owns in-memory task presentation and progress.
- On startup, `GlobalDownloadManager` waits for the existing queue to settle before restoring unfinished tasks, so old and new queues do not overwrite each other.
- Completed audio found quickly through the download index or cached snapshot is settled as complete, skipping repeated media requests and SAF probes.

### Resume

Downloads resume automatically, by transfer type:

- **Direct HTTP transfers**: read the working file size and append `Range: bytes=<offset>-`.
- **Chunked range transfers**: resume by byte offset, for platform candidates that need explicit Range requests.
- **HLS downloads**: record the segment index and downloaded byte count, and resume from the `.hls.json` checkpoint.

Recovery and cancellation:

- Each working file also stores `.resume.json` metadata, used to rebuild pending tasks after an app restart.
- `GlobalDownloadManager` scans and restores unfinished downloads on startup.
- Retries after network fluctuation keep the downloaded part.
- When Wi-Fi loss puts a task in `WAITING_NETWORK`, its checkpoint is kept and it continues after the network recovers or the user confirms.
- Manual cancellation cleans up working files and rolls back partially written audio and sidecars.

### Storage and directories

- The download directory index keeps a snapshot cache and sidecar references, reducing SAF directory walks.
- An empty directory read does not clear an existing index.
- SAF access is much slower than the app-private directory, so switch to a custom directory only when needed.
- `StorageUsageAnalyzer` groups usage into cleanable cache, downloaded content, diagnostics and app data.
- Cache cleanup covers only regenerable cache and share staging. Download working files, recovery state and downloaded songs are untouched.

### Local import and sharing

- `LocalAudioImportManager` imports external audio and scans device music.
- It also copies nearby `lrc/txt` lyrics and `cover/folder/front` images.
- Sharing a local song exposes a controlled directory URI directly when possible.
- A SAF/content URI that cannot be exposed is copied to `cache/shared_local_media` first, which is cleanable cache.
- Local scan previews can filter to tracks that already have metadata.
- After a local playlist is created or enriched, the app fills in titles, artists, albums and covers in the background, keeping edited local metadata.
- Download "metadata post-processing" has its own toggle. When off, management metadata is still saved, but tags, lyrics and covers are no longer written into the audio file.

### Backup

- `BackupManager` supports playlist JSON backup, import and diff analysis.
- Configuration exports exclude login state by default. You can include music-service authorization and GitHub/WebDAV credentials, and optionally encrypt the file with a password.
- Imports accept legacy plaintext files, and encrypted files require the original password.
- Files without login state keep the device's existing accounts and sync credentials.

---

For release build and signing details, see [CONTRIBUTING_EN.md](../CONTRIBUTING_EN.md#release-build).
