package moe.ouom.neriplayer.core.player.state

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.player.state/PlayerBlocking
 * Updated: 2026/3/23
 */

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun <T> blockingIo(timeoutMs: Long = DEFAULT_BLOCKING_IO_TIMEOUT_MS, block: suspend () -> T): T {
    val resultRef = AtomicReference<Result<T>?>(null)
    val latch = CountDownLatch(1)
    val job = CoroutineScope(Dispatchers.IO).launch {
        try {
            resultRef.set(runCatching { block() })
        } finally {
            latch.countDown()
        }
    }
    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        job.cancel()
        throw TimeoutException("blockingIo timed out after ${timeoutMs}ms")
    }
    return resultRef.get()?.getOrThrow()
        ?: error("blockingIo completed without a result")
}

private const val DEFAULT_BLOCKING_IO_TIMEOUT_MS = 3_000L
