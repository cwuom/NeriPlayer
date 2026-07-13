package moe.ouom.neriplayer.ui.view

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
 */

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class HyperBackgroundShaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var effectPainter: BgEffectPainter? = null
        set(value) {
            if (field === value) return
            field = value
            shaderPaint.shader = value?.mBgRuntimeShader?.apply {
                setInputShader(
                    "uTex",
                    LinearGradient(
                        0f,
                        0f,
                        1f,
                        0f,
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP
                    )
                )
            }
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val painter = effectPainter ?: return
        if (!canvas.isHardwareAccelerated || width <= 0 || height <= 0) return
        painter.setResolution(width.toFloat(), height.toFloat())
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)
    }
}
