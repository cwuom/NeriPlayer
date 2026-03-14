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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.CachePolicy
import moe.ouom.neriplayer.R
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import androidx.compose.material3.MaterialTheme
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
        val backgroundBaseColor = MaterialTheme.colorScheme.background

        val imageRequest = ImageRequest.Builder(context)
            .data(imageUri.toUri())
            .crossfade(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .transformations(
                if (blur > 0f) {
                    listOf(BlurTransformation(context, radius = blur))
                } else {
                    emptyList()
                }
            )
            .build()

        Box(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBaseColor)
            )
            AsyncImage(
                model = imageRequest,
                contentDescription = context.getString(R.string.cd_app_background),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(alpha)
            )
        }
    }
}

/**
 * 一个利用 RenderScript 实现高性能高斯模糊的 Coil Transformation
 *
 * @param context Context
 * @param radius 模糊强度, 建议范围 [0, 500]。内部会根据强度调整半径与缩放。
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
            val strength = radius.coerceAtLeast(0f)
            if (strength <= 0f) return input

            val maxRadius = 25f
            val scaleFactor = (strength / maxRadius).coerceAtLeast(1f).coerceAtMost(20f)
            val targetBitmap = if (scaleFactor > 1f) {
                val targetWidth = (input.width / scaleFactor).toInt().coerceAtLeast(1)
                val targetHeight = (input.height / scaleFactor).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(input, targetWidth, targetHeight, true)
            } else {
                input
            }

            val inputAllocation = Allocation.createFromBitmap(rs, targetBitmap)
            val outputAllocation = Allocation.createTyped(rs, inputAllocation.type)

            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

            script.setRadius(strength.coerceIn(0.1f, maxRadius))
            script.setInput(inputAllocation)
            script.forEach(outputAllocation) // 执行模糊处理

            val baseConfig = input.config ?: Bitmap.Config.ARGB_8888
            val intermediate = createBitmap(targetBitmap.width, targetBitmap.height, baseConfig)
            outputAllocation.copyTo(intermediate)

            return if (scaleFactor > 1f) {
                Bitmap.createScaledBitmap(intermediate, input.width, input.height, true)
            } else {
                intermediate
            }
        } finally {
            rs?.destroy()
        }
    }
}
