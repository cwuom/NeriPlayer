package moe.ouom.neriplayer.data.sync.remote

import moe.ouom.neriplayer.data.model.sync.transport.WebDavArchiveEntry
import moe.ouom.neriplayer.data.model.sync.transport.WebDavArchiveObjectPolicy

data class WebDavArchiveGcCandidate(val path: String, val etag: String, val firstSeenMs: Long, val observedAgeMs: Long = 0)
data class WebDavArchiveGcState(val version: Int = 1, val wallMs: Long = 0, val uptimeMs: Long = 0,
    val candidates: List<WebDavArchiveGcCandidate> = emptyList())

object WebDavArchiveGcJournal {
    const val GRACE_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_CANDIDATES = 1024

    fun observe(state: WebDavArchiveGcState, entries: List<WebDavArchiveEntry>, protectedPaths: Set<String>,
        wallMs: Long, uptimeMs: Long): WebDavArchiveGcState {
        require(wallMs > 0L && uptimeMs >= 0L) { "Invalid archive observation clock" }
        val prior = if (stableClock(state, wallMs, uptimeMs)) state.candidates.associateBy { it.path } else emptyMap()
        val candidates = entries.asSequence().filter { it.path !in protectedPaths }.map { entry ->
            val previous = prior[entry.path]
            if (previous == null || previous.etag != entry.etag) WebDavArchiveGcCandidate(entry.path, entry.etag, wallMs)
            else age(previous, state, wallMs, uptimeMs)
        }.sortedWith(compareBy<WebDavArchiveGcCandidate> { it.firstSeenMs }.thenBy { it.path }).take(MAX_CANDIDATES).toList()
        return WebDavArchiveGcState(wallMs = wallMs, uptimeMs = uptimeMs, candidates = candidates)
    }

    fun protect(state: WebDavArchiveGcState, paths: Set<String>): WebDavArchiveGcState =
        state.copy(candidates = state.candidates.filter { it.path !in paths })

    fun eligible(state: WebDavArchiveGcState): List<WebDavArchiveEntry> = state.candidates
        .filter { it.observedAgeMs >= GRACE_MS }.take(32).map { WebDavArchiveEntry(it.path, it.etag) }

    private fun age(candidate: WebDavArchiveGcCandidate, state: WebDavArchiveGcState, wallMs: Long, uptimeMs: Long): WebDavArchiveGcCandidate {
        val elapsed = minOf(wallMs - state.wallMs, uptimeMs - state.uptimeMs)
        val increment = minOf(elapsed, GRACE_MS - candidate.observedAgeMs)
        return candidate.copy(observedAgeMs = candidate.observedAgeMs + increment)
    }

    fun valid(state: WebDavArchiveGcState): Boolean = validHeader(state) && validCandidates(state)

    private fun validHeader(state: WebDavArchiveGcState): Boolean = state.version == 1 && state.wallMs >= 0 && state.uptimeMs >= 0

    private fun validCandidates(state: WebDavArchiveGcState): Boolean =
        state.candidates.size <= MAX_CANDIDATES && state.candidates.map { it.path }.distinct().size == state.candidates.size &&
        state.candidates.all { validCandidate(it, state.wallMs) }

    private fun validCandidate(candidate: WebDavArchiveGcCandidate, wallMs: Long): Boolean =
        validIdentity(candidate) && candidate.firstSeenMs in 1L..wallMs &&
            candidate.observedAgeMs in 0L..minOf(GRACE_MS, wallMs - candidate.firstSeenMs)

    private fun validIdentity(candidate: WebDavArchiveGcCandidate): Boolean =
        WebDavArchiveObjectPolicy.isOwnedPath(candidate.path) && WebDavArchiveObjectPolicy.isStrongETag(candidate.etag)

    private fun stableClock(state: WebDavArchiveGcState, wallMs: Long, uptimeMs: Long): Boolean {
        if (!valid(state) || state.wallMs == 0L || wallMs < state.wallMs || uptimeMs < state.uptimeMs) return false
        val wallDelta = wallMs - state.wallMs
        val uptimeDelta = uptimeMs - state.uptimeMs
        return kotlin.math.abs(wallDelta - uptimeDelta) <= 5 * 60 * 1000L
    }
}
