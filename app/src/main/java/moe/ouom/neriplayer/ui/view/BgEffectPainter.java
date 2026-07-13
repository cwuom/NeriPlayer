package moe.ouom.neriplayer.ui.view;

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
 * File: moe.ouom.neriplayer.ui.view/BgEffectPainter
 * Created: 2025/8/8
 *
 * ! Reference: https://github.com/ReChronoRain/HyperCeiler !
 */

import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;

import com.google.android.material.appbar.MaterialToolbar;

import org.intellij.lang.annotations.Language;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import moe.ouom.neriplayer.R;
@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
public class BgEffectPainter {
    private static final String TAG = "BgEffectPainter";
    private static final int POINT_COUNT = 5;
    private static final int POINT_STRIDE = 3;
    private float[] bound;
    final RuntimeShader mBgRuntimeShader;
    final Context mContext;
    private final float[] uResolution = {1.0f, 1.0f};
    private float uAnimTime = ((float) System.nanoTime()) / 1.0E9f;
    private float[] uBgBound = {0.0f, 0.4489f, 1.0f, 0.5511f};
    private float[] uPoints = {
            0.52f, 0.46f, 0.92f,
            0.14f, 0.32f, 0.74f,
            0.92f, 0.30f, 0.76f,
            0.26f, 0.88f, 0.80f,
            0.84f, 0.86f, 0.84f
    };
    private float[] uColors = {
            0.68f, 0.82f, 0.98f, 1.0f,
            0.96f, 0.85f, 0.74f, 1.0f,
            0.94f, 0.76f, 0.88f, 1.0f,
            0.74f, 0.72f, 0.94f, 1.0f,
            0.80f, 0.88f, 0.92f, 1.0f
    };
    private float uSaturateOffset = 0.2f;
    private float uLightOffset = 0.1f;
    private float uMusicLevel = 0f;
    private float uBeat = 0f;
    private float uPointOffset = 0.1f;
    private float uPointRadiusMulti = 1.0f;
    private float uLevelEase = 0f;
    private float uBeatEase = 0f;
    private float uMotionEase = 0f;
    private float uZoom = 1f;
    private float uColorPulse = 0f;
    private final float[] uGlobalMotion = {0f, 0f};
    private final float[] uAnimatedPoints = new float[POINT_COUNT * POINT_STRIDE];
    private boolean reactiveUniformsDirty = true;


    public BgEffectPainter(Context context) {
        mContext = context;
        @Language("AGSL") String loadShader = loadShader();
        mBgRuntimeShader = new RuntimeShader(loadShader);
        mBgRuntimeShader.setFloatUniform("uTranslateY", 0.0f);
        mBgRuntimeShader.setFloatUniform("uColors", uColors);
        mBgRuntimeShader.setFloatUniform("uSaturateOffset", uSaturateOffset);
        mBgRuntimeShader.setFloatUniform("uBound", uBgBound);
        mBgRuntimeShader.setFloatUniform("uAlphaMulti", 1.0f);
        mBgRuntimeShader.setFloatUniform("uLightOffset", uLightOffset);
        mBgRuntimeShader.setFloatUniform("uResolution", uResolution);
        updateReactiveUniforms();
        updateGlobalMotionUniform();
        updateAnimatedPoints();
    }

    public void setReactive(float level, float beat) {
        float boundedLevel = Math.max(0f, Math.min(1f, level));
        float boundedBeat = Math.max(0f, Math.min(1f, beat));
        if (Float.compare(uMusicLevel, boundedLevel) == 0 && Float.compare(uBeat, boundedBeat) == 0) {
            return;
        }
        uMusicLevel = boundedLevel;
        uBeat = boundedBeat;
        reactiveUniformsDirty = true;
    }

    public void updateMaterials() {
        mBgRuntimeShader.setFloatUniform("uAnimTime", uAnimTime);
        if (reactiveUniformsDirty) {
            updateReactiveUniforms();
        }
        updateGlobalMotionUniform();
        updateAnimatedPoints();
    }

    public RenderEffect getRenderEffect() {
        return RenderEffect.createRuntimeShaderEffect(mBgRuntimeShader, "uTex");
    }

    public void setAnimTime(float f) {
        uAnimTime = f;
    }

    public void setColors(float[] fArr) {
        uColors = fArr;
        mBgRuntimeShader.setFloatUniform("uColors", fArr);
    }

    public void setPoints(float[] fArr) {
        uPoints = fArr;
        updateAnimatedPoints();
    }

    public void setBound(float[] fArr) {
        this.uBgBound = fArr;
        this.mBgRuntimeShader.setFloatUniform("uBound", fArr);
    }

    public void setLightOffset(float f) {
        this.uLightOffset = f;
        this.mBgRuntimeShader.setFloatUniform("uLightOffset", f);
    }

    public void setSaturateOffset(float f) {
        this.uSaturateOffset = f;
        this.mBgRuntimeShader.setFloatUniform("uSaturateOffset", f);
    }

    public void setPhoneLight(float[] fArr) {
        setLightOffset(0.1f);
        setSaturateOffset(0.2f);
        setPoints(new float[]{
                0.52f, 0.46f, 0.92f,
                0.14f, 0.32f, 0.74f,
                0.92f, 0.30f, 0.76f,
                0.26f, 0.88f, 0.80f,
                0.84f, 0.86f, 0.84f
        });
        setColors(new float[]{
                0.68f, 0.82f, 0.98f, 1.0f,
                0.96f, 0.85f, 0.74f, 1.0f,
                0.94f, 0.76f, 0.88f, 1.0f,
                0.74f, 0.72f, 0.94f, 1.0f,
                0.80f, 0.88f, 0.92f, 1.0f
        });
        setBound(fArr);
    }

    public void setPhoneDark(float[] fArr) {
        setLightOffset(-0.1f);
        setSaturateOffset(0.2f);
        setPoints(new float[]{
                0.52f, 0.48f, 0.90f,
                0.14f, 0.32f, 0.72f,
                0.92f, 0.28f, 0.76f,
                0.24f, 0.88f, 0.78f,
                0.86f, 0.86f, 0.82f
        });
        setColors(new float[]{
                0.07f, 0.27f, 0.42f, 1.0f,
                0.35f, 0.24f, 0.20f, 1.0f,
                0.34f, 0.12f, 0.26f, 1.0f,
                0.17f, 0.14f, 0.34f, 1.0f,
                0.18f, 0.34f, 0.36f, 1.0f
        });
        setBound(fArr);
    }


    public void setResolution(float width, float height) {
        if (uResolution[0] == width && uResolution[1] == height) {
            return;
        }
        uResolution[0] = width;
        uResolution[1] = height;
        mBgRuntimeShader.setFloatUniform("uResolution", uResolution);
    }

    public void setResolution(float[] fArr) {
        if (fArr == null || fArr.length < 2) {
            return;
        }
        setResolution(fArr[0], fArr[1]);
    }

    private void updateReactiveUniforms() {
        uLevelEase = smoothStep(0.04f, 0.82f, uMusicLevel);
        uBeatEase = smoothStep(0.03f, 0.62f, uBeat);
        uMotionEase = clamp01(0.42f * uLevelEase + 0.82f * uBeatEase);
        uZoom = 1.0f + 0.024f * uLevelEase + 0.105f * uBeatEase;
        uColorPulse = clamp01(0.68f * uLevelEase + 0.32f * uBeatEase);
        mBgRuntimeShader.setFloatUniform("uLevelEase", uLevelEase);
        mBgRuntimeShader.setFloatUniform("uBeatEase", uBeatEase);
        mBgRuntimeShader.setFloatUniform("uMotionEase", uMotionEase);
        mBgRuntimeShader.setFloatUniform("uZoom", uZoom);
        mBgRuntimeShader.setFloatUniform("uColorPulse", uColorPulse);
        reactiveUniformsDirty = false;
    }

    private void updateGlobalMotionUniform() {
        if (uMotionEase == 0f && uGlobalMotion[0] == 0f && uGlobalMotion[1] == 0f) {
            return;
        }
        uGlobalMotion[0] = uMotionEase * 0.0060f * (float) Math.sin(uAnimTime * 1.9f);
        uGlobalMotion[1] = uMotionEase * 0.0060f * (float) Math.cos(uAnimTime * 1.6f);
        mBgRuntimeShader.setFloatUniform("uGlobalMotion", uGlobalMotion);
    }

    private void updateAnimatedPoints() {
        float pointOffset = uPointOffset + 0.022f * uLevelEase + 0.108f * uBeatEase;
        float radiusMulti = uPointRadiusMulti * (1.0f + 0.045f * uLevelEase + 0.220f * uBeatEase);
        for (int i = 0; i < POINT_COUNT; i++) {
            int offset = i * POINT_STRIDE;
            float x = uPoints[offset];
            float y = uPoints[offset + 1];
            float radius = uPoints[offset + 2] * radiusMulti;

            x += (float) Math.sin(uAnimTime + y) * pointOffset;
            y += (float) Math.cos(uAnimTime + x) * pointOffset;

            float pushX = x - 0.5f + 1.0e-4f;
            float pushY = y - 0.5f + 1.0e-4f;
            float pushLength = (float) Math.sqrt(pushX * pushX + pushY * pushY);
            float pushScale = pushLength > 0f ? uBeatEase * 0.118f / pushLength : 0f;

            uAnimatedPoints[offset] = x + pushX * pushScale;
            uAnimatedPoints[offset + 1] = y + pushY * pushScale;
            uAnimatedPoints[offset + 2] = radius;
        }
        mBgRuntimeShader.setFloatUniform("uPoints", uAnimatedPoints);
    }

    private static float smoothStep(float edge0, float edge1, float value) {
        float t = clamp01((value - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private String loadShader() {
        StringBuilder shaderCode = new StringBuilder();
        try (InputStream is = mContext.getAssets().open("hyper_background_effect.glsl");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                shaderCode.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to load shader asset", e);
        }
        return shaderCode.toString();
    }

    public void showRuntimeShader(Context context, View view, MaterialToolbar actionBar, boolean isNightMode) {
        calcAnimationBound(context, view, actionBar);
        if (isNightMode){
            setPhoneDark(this.bound);
        } else {
            setPhoneLight(this.bound);
        }

    }


    public void calcAnimationBound(Context context, View view, MaterialToolbar actionBar) {
        float heightDp = (float) (416 * 1.3);
        float heightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, heightDp, context.getResources().getDisplayMetrics());

        float height = (actionBar != null ? actionBar.getHeight() : 0.0f) + heightPx;
        float height2 = height / ((ViewGroup) view.getParent()).getHeight();
        float width = ((ViewGroup) view.getParent()).getWidth();

        if (width <= height) {
            this.bound = new float[]{0.0f, 1.0f - height2, 1.0f, height2};
        } else {
            this.bound = new float[]{((width - height) / 2.0f) / width, 1.0f - height2, height / width, height2};
        }
    }

    public static boolean isNightMode(Context context) {
        return context.getResources().getBoolean(R.bool.is_night_mode);
    }

}
