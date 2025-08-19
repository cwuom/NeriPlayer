package moe.ouom.neriplayer.ui

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
 * File: moe.ouom.neriplayer.ui/CustomBackground
 * Created: 2025/8/19
 */

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import androidx.core.graphics.createBitmap


@Composable
fun CustomBackground(
    imageUri: String?,
    blur: Float,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    if (imageUri != null) {
        val context = LocalContext.current

        val imageRequest = ImageRequest.Builder(context)
            .data(imageUri.toUri())
            .crossfade(true)
            .transformations(
                if (blur > 0f) {
                    listOf(BlurTransformation(context, radius = blur))
                } else {
                    emptyList()
                }
            )
            .build()

        AsyncImage(
            model = imageRequest,
            contentDescription = "App Background",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .fillMaxSize()
                .alpha(alpha)
        )
    }
}

/**
 * 一个利用 RenderScript 实现高性能高斯模糊的 Coil Transformation
 *
 * @param context Context
 * @param radius 模糊半径, 有效范围是 (0, 25]. 会被自动限制在该范围内
 */
class BlurTransformation(
    private val context: Context,
    private val radius: Float,
) : Transformation {

    /**
     * 为 Coil 缓存提供一个唯一的 Key
     * 这样不同模糊半径的图片缓存不会冲突
     */
    override val cacheKey: String = "${this::class.java.name}-$radius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        var rs: RenderScript? = null
        try {
            rs = RenderScript.create(context)

            val inputAllocation = Allocation.createFromBitmap(rs, input)
            val outputAllocation = Allocation.createTyped(rs, inputAllocation.type)

            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

            script.setRadius(radius.coerceIn(0.1f, 25.0f))
            script.setInput(inputAllocation)
            script.forEach(outputAllocation) // 执行模糊处理

            val output = input.config?.let { createBitmap(input.width, input.height, it) }
            outputAllocation.copyTo(output)

            return output!!
        } finally {
            rs?.destroy()
        }
    }
}