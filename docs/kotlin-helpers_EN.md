[English](./kotlin-helpers_EN.md) | [中文](./kotlin-helpers.md)

# Kotlin Helper Usage Guidelines

Helpers handle repetitive mechanical operations. Business call sites remain
responsible for deciding when requests become stale, how errors fall back, and
when to switch playback paths.

## Request generations

[`RequestGeneration`](../modules/common/src/main/java/moe/ouom/neriplayer/common/concurrent/RequestGeneration.kt)
checks whether an asynchronous result still belongs to the current request generation.

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

- `advance()` starts a new generation and invalidates all tickets previously issued
  by that instance. When also canceling old jobs, advance the generation first so
  their cleanup cannot change the new state.
- `capture()` returns the current generation's ticket without invalidating other
  requests. Pagination, likes, and replies on the comments page share one generation.
- Use separate instances for independent business flows. Tickets use object
  identity; they do not reuse numeric identifiers and must not be serialized to
  disk or sent over the network.
- Ticket reads are visible across threads, but checking `isCurrent` and then
  writing state is not atomic. State commits must still follow the existing
  main-thread or locking requirements.
- This helper does not cancel coroutines, detect duplicate requests, or commit
  state. Keep using `SongMetadataRequestCoordinator` for grouping requests by
  song and prioritizing manual actions, and `ManualSearchRequestCoordinator` for
  search deduplication.

See [`CommentViewModel`](../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/CommentViewModel.kt)
and [`NowPlayingViewModel`](../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/NowPlayingViewModel.kt)
for actual call sites.

## Time and capacity

[`MeasureUnits`](../modules/common/src/main/java/moe/ouom/neriplayer/common/units/MeasureUnits.kt)
provides singular time extensions. Prefer this form in ordinary Kotlin expressions:

```kotlin
val timeoutMs = 5.minute
val retentionMs = 2.hour
val refreshDelayMs = 30.second
val maxAgeMs = 7.day
val totalMillis = minutes.coerceAtLeast(0).minute
```

The extensions accept an `Int` count and return a `Long` in milliseconds. They
convert to `Long` before multiplying to avoid `Int` multiplication overflow.
Negative values are converted as signed time differences; the call site decides
whether they are allowed. A `day` is always 24 hours, not a calendar day.

`const val` declarations and annotation default values require compile-time
constants. Keep using the unit constants in those contexts:

```kotlin
private const val RETRY_WINDOW_MS = 2 * MINUTE_MS
private const val CHUNK_SIZE_BYTES = 4 * MEBIBYTE_BYTES
```

Keep unit suffixes such as `Ms`, `Nanos`, and `Bytes` on variables and parameters.
Business defaults belong to their corresponding policies; for example, use
`CacheSizePolicy.DEFAULT_CACHE_SIZE_BYTES` for the default cache capacity.

The input to
[`elapsedMillisSince`](../modules/common/src/main/java/moe/ouom/neriplayer/common/time/MonotonicTime.kt)
must come from `System.nanoTime()`.

```kotlin
val startedAtNanos = System.nanoTime()
performOperation()
val elapsedMs = elapsedMillisSince(startedAtNanos)
```

Do not pass `currentTimeMillis()`, `elapsedRealtime()`, or a value already converted
to milliseconds. The call site decides whether to clamp negative results to zero.

## Player reads

[`PlayerReadExtensions`](../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/PlayerReadExtensions.kt)
provides `currentPositionMsOr(fallbackMs)`, `durationMsOr(fallbackMs)`, and properties
that default to a zero fallback.

```kotlin
val positionMs = player.currentPositionMsOr(-1L)
val durationMs = player.durationMsOr(previousDurationMs)
```

Successful reads clamp negative values to zero. The caller's fallback is returned
only when the read throws. Diagnostic code that needs the player's original
negative values should keep reading them directly. These extensions do not switch
threads; callers must still follow the Player's application-thread requirements.

## Coroutine results

[`runCatchingNonCancellation`](../modules/common/src/main/java/moe/ouom/neriplayer/common/coroutines/RunCatchingNonCancellation.kt)
converts ordinary `Exception` instances into a `Result`, propagates
`CancellationException`, and does not catch `Error`.

```kotlin
return runCatchingNonCancellation {
    repository.read()
}.getOrElse { error ->
    reportFailure(error)
    fallback
}
```

Call sites provide logging, fallbacks, retries, and dispatchers. Keep explicit
`try/catch/finally` when the code has a specific recovery order, resource cleanup,
or distinct exception categories.

## PCM requirements

[`pcmAudioRequirements`](../modules/playback/logic/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PlaybackAudioOffloadPolicy.kt)
maps the existing source compatibility rules and audio processing conditions to a
`Set<PcmAudioRequirement>`. Callers use `isNotEmpty()` to decide whether to disable
audio offload and can log all reasons.

When adding a condition, update the corresponding rule in
[`PcmAudioRequirements`](../modules/playback/logic/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PcmAudioRequirements.kt),
keep `pcmAudioRequirements` as the common entry point for returning reasons, and
add tests for the corresponding reason. An empty set only means the current policy
has no PCM requirement; actual offload support still depends on Media3 and the device.
Playback call sites retain the ordering of audio path changes.

## JSON object array mapping

[`mapObjectsNotNull`](../modules/common/src/main/java/moe/ouom/neriplayer/common/json/JsonArrayExtensions.kt)
is for JSON arrays where skipping non-object elements is allowed. It centralizes
indexed iteration and the `optJSONObject` check.

```kotlin
val artists = array.mapObjectsNotNull { item ->
    val id = item.optLong("id", 0L)
    val name = item.optString("name", "").trim()
    if (id <= 0L || name.isBlank()) return@mapObjectsNotNull null
    NeteaseArtistSummary(id = id, name = name)
}
```

JSON nulls, strings, numbers, and nested arrays are skipped. Objects reach the
mapping function in their original order, and a null mapping result omits that
record. Duplicate objects are preserved. Exceptions thrown by the mapping
function propagate instead of disguising API errors as an empty list.

Required-field validation, defaults, deduplication, and pagination rules stay at
the call site. The caller also decides whether a missing array should become an
empty list. Keep strict parsing for protocols or persistence formats that require
every record to be valid.

## Merging lists by identity

[`mergeDistinctBy`](../modules/common/src/main/java/moe/ouom/neriplayer/common/collections/CollectionMerge.kt)
reads the existing list before the incoming list and keeps the first item for
each identity key supplied by the caller.

```kotlin
val merged = existing.mergeDistinctBy(incoming, limit = 100) { it.id }
```

- The first item for an identity wins, including duplicates within the existing
  list. The function does not modify its inputs.
- `limit` bounds the number of distinct results. Reading stops as soon as the
  limit is reached. Zero produces an empty result; a negative value throws
  `IllegalArgumentException`.
- Call sites still own the next-page flag, server totals, empty-page handling,
  and identity-key selection.
- Keep the business-specific merge strategy when duplicates must be preserved or
  new data must update existing items. Duplicate songs in a playback queue have
  their own meaning, so this helper does not apply there.

Comments, NetEase home recommendations, and Bilibili favorites pagination already
use this helper. Explore search retains its existing rules for handling items
already present.
