[English](./kotlin-helpers_EN.md) | [中文](./kotlin-helpers.md)

# Kotlin Helper Usage Guidelines

Helpers handle repetitive mechanical work. Call sites decide when a request becomes stale, how errors fall back, and when to switch playback paths.

## Request generations

[`RequestGeneration`](../modules/common/src/main/java/moe/ouom/neriplayer/common/concurrent/RequestGeneration.kt) tells whether an asynchronous result still belongs to the current request generation.

```kotlin
private val requests = RequestGeneration()

fun refresh() {
    val request = requests.advance()
    loadJob?.cancel()
    loadJob = scope.launch {
        val result = load()
        if (!request.isCurrent) return@launch
        state.value = result
    }
}
```

- `advance()` starts a new generation and invalidates every ticket the instance has issued.
- When also canceling the old job, call `advance()` first so the old job's cleanup cannot overwrite the new state.
- `capture()` returns a ticket for the current generation without invalidating other requests. Pagination, likes, and replies on the comments page share one generation.
- Independent business flows use separate instances.
- Tickets are distinguished by object identity, never reuse numbers, and are not serialized to disk or the network.
- Ticket reads are visible across threads, but checking `isCurrent` and then writing state is not atomic. State commits must stay on the main thread or under a lock.
- The helper only checks generations. It does not cancel coroutines, deduplicate requests, or commit state.
- Use `SongMetadataRequestCoordinator` for per-song grouping and manual priority, and `ManualSearchRequestCoordinator` for search deduplication.

For call sites, see [`CommentViewModel`](../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/CommentViewModel.kt) and [`NowPlayingViewModel`](../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/NowPlayingViewModel.kt).

## Time and capacity

[`MeasureUnits`](../modules/common/src/main/java/moe/ouom/neriplayer/common/units/MeasureUnits.kt) provides singular time extensions for ordinary expressions:

```kotlin
val timeoutMs = 5.minute
val retentionMs = 2.hour
val refreshDelayMs = 30.second
val maxAgeMs = 7.day
val totalMillis = minutes.coerceAtLeast(0).minute
```

- The extensions take an `Int` count and return a `Long` in milliseconds.
- The value is converted to `Long` before multiplying, so `Int` cannot overflow.
- Negative values convert as signed time differences. The call site decides whether they are allowed.
- A `day` is always 24 hours, not a calendar day.

`const val` and annotation defaults need compile-time constants, so use the unit constants:

```kotlin
private const val RETRY_WINDOW_MS = 2 * MINUTE_MS
private const val CHUNK_SIZE_BYTES = 4 * MEBIBYTE_BYTES
```

- Variable and parameter names carry unit suffixes such as `Ms`, `Nanos`, and `Bytes`.
- Business defaults live in their own policy, for example `CacheSizePolicy.DEFAULT_CACHE_SIZE_BYTES` for the default cache capacity.

The input of [`elapsedMillisSince`](../modules/common/src/main/java/moe/ouom/neriplayer/common/time/MonotonicTime.kt) must come from `System.nanoTime()`.

```kotlin
val startedAtNanos = System.nanoTime()
performOperation()
val elapsedMs = elapsedMillisSince(startedAtNanos)
```

- Do not pass `currentTimeMillis()`, `elapsedRealtime()`, or a value already converted to milliseconds.
- The call site decides whether to clamp negative results to zero.

## Player reads

[`PlayerReadExtensions`](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/PlayerReadExtensions.kt) provides:

- `currentPositionMsOr(fallbackMs)`
- `durationMsOr(fallbackMs)`
- Properties of the same names with a fallback of 0

These extensions are `internal` to `:playback:runtime`. The app and other libraries read state through the player host interfaces.

```kotlin
val positionMs = player.currentPositionMsOr(-1L)
val durationMs = player.durationMsOr(previousDurationMs)
```

- A successful read clamps negative values to zero.
- A read that throws returns the caller's fallback.
- Diagnostic code that needs the raw negative value reads the Player directly.
- The extensions do not switch threads. Callers must run on the thread the Player requires.

## Coroutine results

[`runCatchingNonCancellation`](../modules/common/src/main/java/moe/ouom/neriplayer/common/coroutines/RunCatchingNonCancellation.kt) handles exceptions as follows:

- An ordinary `Exception` becomes a `Result`.
- `CancellationException` propagates.
- `Error` is not caught.

Both the helper and its block are `suspend`, so call it from a coroutine or suspending function. It does not switch dispatchers.

```kotlin
return runCatchingNonCancellation {
    repository.read()
}.getOrElse { error ->
    reportFailure(error)
    fallback
}
```

- The call site supplies logging, fallbacks, retries, and the dispatcher.
- Code with a specific recovery order, resource cleanup, or exception categories uses an explicit `try/catch/finally`.

## PCM requirements

[`pcmAudioRequirements`](../modules/playback/logic/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PlaybackAudioOffloadPolicy.kt) maps source compatibility rules and audio processing conditions to a `Set<PcmAudioRequirement>`.

- Callers use `isNotEmpty()` to decide whether to disable offload, and can log every reason.
- An empty set means the current policy has no PCM requirement. Actual offload support is decided by Media3 and the device.
- The playback call site decides the order of audio path switches.

To add a condition:

1. Update the check in the matching rule of [`PcmAudioRequirements`](../modules/playback/logic/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PcmAudioRequirements.kt).
2. Return the reason through `pcmAudioRequirements`.
3. Add a test for that reason.

Existing reasons include `AUDIO_EFFECTS` and `OFFLOAD_STALL_FALLBACK`:

- `AUDIO_EFFECTS`: the caller passes `audioEffectsActive` to say whether effects actually take effect.
- `OFFLOAD_STALL_FALLBACK`: keeps PCM active after a silent offload recovery in the current playback session. Recomputing from user settings alone must not clear it.

## JSON object array mapping

[`mapObjectsNotNull`](../modules/common/src/main/java/moe/ouom/neriplayer/common/json/JsonArrayExtensions.kt) serves JSON arrays whose non-object members may be skipped. It centralizes indexed iteration and the `optJSONObject` check.

```kotlin
val artists = array.mapObjectsNotNull { item ->
    val id = item.optLong("id", 0L)
    val name = item.optString("name", "").trim()
    if (id <= 0L || name.isBlank()) return@mapObjectsNotNull null
    NeteaseArtistSummary(id = id, name = name)
}
```

- JSON nulls, strings, numbers, and nested arrays are skipped.
- Objects reach the mapping function in order. A null result omits the entry, and duplicate objects are kept.
- Exceptions from the mapping function propagate, so API errors never turn into an empty list.
- Required-field validation, defaults, deduplication, and pagination rules belong to the call site.
- The caller decides whether a missing array becomes an empty list.
- Protocols or persistence formats that require every record to be valid use strict parsing.

## Merging lists by identity

[`mergeDistinctBy`](../modules/common/src/main/java/moe/ouom/neriplayer/common/collections/CollectionMerge.kt) reads the existing list, then the incoming list, and keeps the first item for each identity key the caller supplies.

```kotlin
val merged = existing.mergeDistinctBy(incoming, limit = 100) { it.id }
```

- The first item of an identity wins, including duplicates inside the existing list.
- The function does not modify its inputs.
- `limit` caps the number of distinct results, and reading stops once it is reached.
- A `limit` of 0 returns an empty result, and a negative value throws `IllegalArgumentException`.
- The call site owns the next-page flag, server totals, empty-page handling, and identity-key choice.
- When duplicates must be kept or new data must update old items, use the business-specific merge strategy.
- Duplicate songs in a playback queue have their own meaning, so this helper does not apply to them.

Comments, NetEase home songs, and Bilibili favorites pagination use this helper. Explore search applies its own rules for items already present.
