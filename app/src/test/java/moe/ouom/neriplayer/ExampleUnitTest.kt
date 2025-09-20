package moe.ouom.neriplayer

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
 * File: moe.ouom.neriplayer/ExampleUnitTest
 * Created: 2025/8/20
 */

import moe.ouom.neriplayer.util.formatDurationSec
import moe.ouom.neriplayer.util.formatFileSize
import moe.ouom.neriplayer.util.formatPlayCount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests covering core formatter helpers.
 */
class ExampleUnitTest {

    @Test
    fun `formatPlayCount rounds to friendly units`() {
        assertEquals("9.9万", formatPlayCount(99_000))
        assertEquals("1.0亿", formatPlayCount(100_000_000))
        assertEquals("500", formatPlayCount(500))
    }

    @Test
    fun `formatFileSize chooses appropriate suffix`() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1.0 KB", formatFileSize(1_024))
        assertEquals("2.0 MB", formatFileSize(2_097_152))
    }

    @Test
    fun `formatDurationSec adds hours when necessary`() {
        assertEquals("00:00", formatDurationSec(0))
        assertEquals("03:25", formatDurationSec(205))
        assertEquals("1:05:07", formatDurationSec(3_907))
    }
}
