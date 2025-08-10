package moe.ouom.neriplayer.util

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
 * File: moe.ouom.neriplayer.util/Ext
 * Created: 2025/8/10
 */
import java.io.ByteArrayOutputStream
import java.io.InputStream

fun InputStream.readBytesCompat(bufferSize: Int = 8 * 1024): ByteArray {
    ByteArrayOutputStream().use { out ->
        val buf = ByteArray(bufferSize)
        while (true) {
            val n = this.read(buf)
            if (n == -1) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
