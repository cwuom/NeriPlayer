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
import android.content.res.Resources;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;

import com.google.android.material.appbar.MaterialToolbar;

import moe.ouom.neriplayer.R;

@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
public class BgEffectPainter {
    private float[] bound;
    RuntimeShader mBgRuntimeShader;
    Context mContext;
    Resources mResources;
    private float[] uResolution;
    private float uAnimTime = ((float) System.nanoTime()) / 1.0E9f;
    private float[] uBgBound = {0.0f, 0.4489f, 1.0f, 0.5511f};
    private final float uTranslateY = 0.0f;
    private float[] uPoints = {0.67f, 0.42f, 1.0f, 0.69f, 0.75f, 1.0f, 0.14f, 0.71f, 0.95f, 0.14f, 0.27f, 0.8f};
    private float[] uColors = {0.57f, 0.76f, 0.98f, 1.0f, 0.98f, 0.85f, 0.68f, 1.0f, 0.98f, 0.75f, 0.93f, 1.0f, 0.73f, 0.7f, 0.98f, 1.0f};
    private final float uAlphaMulti = 1.0f;
    private final float uNoiseScale = 1.5f;
    private final float uPointOffset = 0.1f;
    private final float uPointRadiusMulti = 1.0f;
    private float uSaturateOffset = 0.2f;
    private float uLightOffset = 0.1f;
    private final float uAlphaOffset = 0.5f;
    private final float uShadowColorMulti = 0.3f;
    private final float uShadowColorOffset = 0.3f;
    private final float uShadowNoiseScale = 5.0f;
    private final float uShadowOffset = 0.01f;

    public BgEffectPainter(Context context) {
        mContext = context;
        mResources = context.getResources();
        String loadShader = loadShader();
        mBgRuntimeShader = new RuntimeShader(loadShader);
        mBgRuntimeShader.setFloatUniform("uTranslateY", uTranslateY);
        mBgRuntimeShader.setFloatUniform("uPoints", uPoints);
        mBgRuntimeShader.setFloatUniform("uColors", uColors);
        mBgRuntimeShader.setFloatUniform("uNoiseScale", uNoiseScale);
        mBgRuntimeShader.setFloatUniform("uPointOffset", uPointOffset);
        mBgRuntimeShader.setFloatUniform("uPointRadiusMulti", uPointRadiusMulti);
        mBgRuntimeShader.setFloatUniform("uSaturateOffset", uSaturateOffset);
        mBgRuntimeShader.setFloatUniform("uShadowColorMulti", uShadowColorMulti);
        mBgRuntimeShader.setFloatUniform("uShadowColorOffset", uShadowColorOffset);
        mBgRuntimeShader.setFloatUniform("uShadowOffset", uShadowOffset);
        mBgRuntimeShader.setFloatUniform("uBound", uBgBound);
        mBgRuntimeShader.setFloatUniform("uAlphaMulti", uAlphaMulti);
        mBgRuntimeShader.setFloatUniform("uLightOffset", uLightOffset);
        mBgRuntimeShader.setFloatUniform("uAlphaOffset", uAlphaOffset);
        mBgRuntimeShader.setFloatUniform("uShadowNoiseScale", uShadowNoiseScale);
    }

    public RenderEffect getRenderEffect() {
        return RenderEffect.createRuntimeShaderEffect(mBgRuntimeShader, "uTex");
    }

    public void updateMaterials() {
        mBgRuntimeShader.setFloatUniform("uAnimTime", uAnimTime);
        mBgRuntimeShader.setFloatUniform("uResolution", uResolution);
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
        mBgRuntimeShader.setFloatUniform("uPoints", fArr);
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
        setPoints(new float[]{0.67f, 0.42f, 1.0f, 0.69f, 0.75f, 1.0f, 0.14f, 0.71f, 0.95f, 0.14f, 0.27f, 0.8f});
        setColors(new float[]{0.57f, 0.76f, 0.98f, 1.0f, 0.98f, 0.85f, 0.68f, 1.0f, 0.98f, 0.75f, 0.93f, 1.0f, 0.73f, 0.7f, 0.98f, 1.0f});
        setBound(fArr);
    }

    public void setPhoneDark(float[] fArr) {
        setLightOffset(-0.1f);
        setSaturateOffset(0.2f);
        setPoints(new float[]{0.63f, 0.5f, 0.88f, 0.69f, 0.75f, 0.8f, 0.17f, 0.66f, 0.81f, 0.14f, 0.24f, 0.72f});
        setColors(new float[]{0.0f, 0.31f, 0.58f, 1.0f, 0.53f, 0.29f, 0.15f, 1.0f, 0.46f, 0.06f, 0.27f, 1.0f, 0.16f, 0.12f, 0.45f, 1.0f});
        setBound(fArr);
    }

    public void setPadLight(float[] fArr) {
        setLightOffset(0.1f);
        setSaturateOffset(0.0f);
        setPoints(new float[]{0.67f, 0.37f, 0.88f, 0.54f, 0.66f, 1.0f, 0.37f, 0.71f, 0.68f, 0.28f, 0.26f, 0.62f});
        setColors(new float[]{0.57f, 0.76f, 0.98f, 1.0f, 0.98f, 0.85f, 0.68f, 1.0f, 0.98f, 0.75f, 0.93f, 0.95f, 0.73f, 0.7f, 0.98f, 0.9f});
        setBound(fArr);
    }

    public void setPadDark(float[] fArr) {
        setLightOffset(-0.1f);
        setSaturateOffset(0.2f);
        setPoints(new float[]{0.55f, 0.42f, 1.0f, 0.56f, 0.75f, 1.0f, 0.4f, 0.59f, 0.71f, 0.43f, 0.09f, 0.75f});
        setColors(new float[]{0.0f, 0.31f, 0.58f, 1.0f, 0.53f, 0.29f, 0.15f, 1.0f, 0.46f, 0.06f, 0.27f, 1.0f, 0.16f, 0.12f, 0.45f, 1.0f});
        setBound(fArr);
    }

    public void setResolution(float[] fArr) {
        this.uResolution = fArr;
    }

    private String loadShader() {
        return "uniform vec2 uResolution;\n" +
                "uniform shader uTex;\n" +
                "uniform shader uTexBitmap;\n" +
                "uniform vec2 uTexWH;\n" +
                "//uniform shader uPerlinTex;\n" +
                "\n" +
                "// 新版参数\n" +
                "uniform float uAnimTime;\n" +
                "uniform vec4 uBound;\n" +
                "uniform float uTranslateY;\n" +
                "uniform vec3 uPoints[4];\n" +
                "uniform vec4 uColors[4];\n" +
                "uniform float uAlphaMulti;\n" +
                "uniform float uNoiseScale;\n" +
                "uniform float uPointOffset;\n" +
                "uniform float uPointRadiusMulti;\n" +
                "uniform float uSaturateOffset;\n" +
                "uniform float uLightOffset;\n" +
                "uniform float uAlphaOffset;\n" +
                "uniform float uShadowColorMulti;\n" +
                "uniform float uShadowColorOffset;\n" +
                "uniform float uShadowNoiseScale;\n" +
                "uniform float uShadowOffset;\n" +
                "\n" +
                "vec3 hsl2rgb(in vec3 c)\n" +
                "{\n" +
                "    vec3 rgb = clamp(abs(mod(c.x*6.0+vec3(0.0, 4.0, 2.0), 6.0)-3.0)-1.0, 0.0, 1.0);\n" +
                "\n" +
                "    return c.z + c.y * (rgb-0.5)*(1.0-abs(2.0*c.z-1.0));\n" +
                "}\n" +
                "\n" +
                "vec3 HueShift (in vec3 Color, in float Shift)\n" +
                "{\n" +
                "    vec3 P = vec3(0.55735)*dot(vec3(0.55735), Color);\n" +
                "\n" +
                "    vec3 U = Color-P;\n" +
                "\n" +
                "    vec3 V = cross(vec3(0.55735), U);\n" +
                "\n" +
                "    Color = U*cos(Shift*6.2832) + V*sin(Shift*6.2832) + P;\n" +
                "\n" +
                "    return vec3(Color);\n" +
                "}\n" +
                "\n" +
                "vec3 rgb2hsl(in vec3 c){\n" +
                "    float h = 0.0;\n" +
                "    float s = 0.0;\n" +
                "    float l = 0.0;\n" +
                "    float r = c.r;\n" +
                "    float g = c.g;\n" +
                "    float b = c.b;\n" +
                "    float cMin = min(r, min(g, b));\n" +
                "    float cMax = max(r, max(g, b));\n" +
                "\n" +
                "    l = (cMax + cMin) / 2.0;\n" +
                "    if (cMax > cMin) {\n" +
                "        float cDelta = cMax - cMin;\n" +
                "\n" +
                "        //s = l < .05 ? cDelta / ( cMax + cMin ) : cDelta / ( 2.0 - ( cMax + cMin ) ); Original\n" +
                "        s = l < .0 ? cDelta / (cMax + cMin) : cDelta / (2.0 - (cMax + cMin));\n" +
                "\n" +
                "        if (r == cMax) {\n" +
                "            h = (g - b) / cDelta;\n" +
                "        } else if (g == cMax) {\n" +
                "            h = 2.0 + (b - r) / cDelta;\n" +
                "        } else {\n" +
                "            h = 4.0 + (r - g) / cDelta;\n" +
                "        }\n" +
                "\n" +
                "        if (h < 0.0) {\n" +
                "            h += 6.0;\n" +
                "        }\n" +
                "        h = h / 6.0;\n" +
                "    }\n" +
                "    return vec3(h, s, l);\n" +
                "}\n" +
                "\n" +
                "vec3 rgb2hsv(vec3 c)\n" +
                "{\n" +
                "    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);\n" +
                "    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));\n" +
                "    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));\n" +
                "\n" +
                "    float d = q.x - min(q.w, q.y);\n" +
                "    float e = 1.0e-10;\n" +
                "    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);\n" +
                "}\n" +
                "\n" +
                "vec3 hsv2rgb(vec3 c)\n" +
                "{\n" +
                "    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);\n" +
                "    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);\n" +
                "    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);\n" +
                "}\n" +
                "\n" +
                "float hash(vec2 p) {\n" +
                "    vec3 p3 = fract(vec3(p.xyx) * 0.13);\n" +
                "    p3 += dot(p3, p3.yzx + 3.333);\n" +
                "    return fract((p3.x + p3.y) * p3.z);\n" +
                "}\n" +
                "\n" +
                "float perlin(vec2 x) {\n" +
                "    vec2 i = floor(x);\n" +
                "    vec2 f = fract(x);\n" +
                "\n" +
                "    float a = hash(i);\n" +
                "    float b = hash(i + vec2(1.0, 0.0));\n" +
                "    float c = hash(i + vec2(0.0, 1.0));\n" +
                "    float d = hash(i + vec2(1.0, 1.0));\n" +
                "\n" +
                "    vec2 u = f * f * (3.0 - 2.0 * f);\n" +
                "    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;\n" +
                "}\n" +
                "\n" +
                "vec4 srcOver(vec4 src, vec4 dst){\n" +
                "    return src + dst * (1.0 - src.a);\n" +
                "}\n" +
                "\n" +
                "vec4 blendSrcOver(vec4 src, vec4 dst) {\n" +
                "    if (src.a == 0.0) {\n" +
                "        return dst;\n" +
                "    }\n" +
                "\n" +
                "    float srcAlpha = src.a;\n" +
                "    float dstAlpha = dst.a * (1.0 - srcAlpha);\n" +
                "    float outAlpha = srcAlpha + dstAlpha;\n" +
                "\n" +
                "    if (outAlpha == 0.0) {\n" +
                "        return vec4(0, 0, 0, 0);\n" +
                "    }\n" +
                "\n" +
                "    vec4 outColor = (src * srcAlpha + dst * dstAlpha) / outAlpha;\n" +
                "    return vec4(outColor.rgb, outAlpha);\n" +
                "}\n" +
                "\n" +
                "float gradientNoise(in vec2 uv)\n" +
                "{\n" +
                "    return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));\n" +
                "}\n" +
                "\n" +
                "vec4 main(vec2 fragCoord){\n" +
                "\n" +
                "    vec2 vUv = fragCoord/uResolution;\n" +
                "    vUv.y = 1.0-vUv.y;\n" +
                "    vec2 uv = vUv;\n" +
                "    uv -= vec2(0., uTranslateY);\n" +
                "\n" +
                "    uv.xy -= uBound.xy;\n" +
                "    uv.xy /= uBound.zw;\n" +
                "\n" +
                "    vec3 hsv;\n" +
                "\n" +
                "//    vec4 color = vec4(1, 1, 1, 0.);\n" +
                "    vec4 color = vec4(0.0);\n" +
                "\n" +
                "    float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));\n" +
                "//    float noiseValue = uPerlinTex.eval(vUv * vec2(128.0) + vec2(-uAnimTime, -uAnimTime)*50.0).r;\n" +
                "\n" +
                "    // draw circles\n" +
                "    for (int i = 0; i < 4; i++){\n" +
                "        vec4 pointColor = uColors[i];\n" +
                "        pointColor.rgb *= pointColor.a;\n" +
                "        vec2 point = uPoints[i].xy;\n" +
                "        float rad = uPoints[i].z * uPointRadiusMulti;\n" +
                "\n" +
                "        point.x += sin(uAnimTime + point.y) * uPointOffset;\n" +
                "        point.y += cos(uAnimTime + point.x) * uPointOffset;\n" +
                "\n" +
                "        float d = distance(uv, point);\n" +
                "        float pct = smoothstep(rad, 0., d);\n" +
                "        //float pct = smoothstep(rad, rad - 0.01, d);\n" +
                "\n" +
                "        // color = blendSrcOver(color, pointColor);\n" +
                "        // color = blendSrcOver(pointColor, color);\n" +
                "\n" +
                "        color.rgb = mix(color.rgb, pointColor.rgb, pct);\n" +
                "\n" +
                "        // color.a += (1. - color.a) * pointColor.a;\n" +
                "        color.a = mix(color.a, pointColor.a, pct);\n" +
                "    }\n" +
                "\n" +
                "    float oppositeNoise = smoothstep(0., 1., noiseValue);\n" +
                "    color.rgb /= color.a;\n" +
                "    hsv = rgb2hsv(color.rgb);\n" +
                "    hsv.y = mix(hsv.y, 0.0, oppositeNoise * uSaturateOffset);\n" +
                "//    hsv.y += oppositeNoise * uSaturateOffset;\n" +
                "    color.rgb = hsv2rgb(hsv);\n" +
                "\n" +
                "    color.rgb += oppositeNoise * uLightOffset;\n" +
                "//    color.rgb = mix(color.rgb, min(color.rgb + oppositeNoise * uLightOffset, vec3(1.)), oppositeNoise);\n" +
                "    // color.a += noiseValue * uAlphaOffset;\n" +
                "\n" +
                "    color.a = clamp(color.a, 0., 1.);\n" +
                "    color.a *= uAlphaMulti;\n" +
                "\n" +
                "    vec4 texColor = uTexBitmap.eval(vec2(vUv.x, 1.0 - vUv.y)*uTexWH);\n" +
                "   vec4 uiColor = uTex.eval(vec2(vUv.x, 1.0 - vUv.y)*uResolution);\n" +
                "\n" +
                "    vec4 fragColor;\n" +
                "\n" +
                "    // 显示uBound区域\n" +
                "    //float debugColor = 1.;\n" +
                "    //debugColor *= step(0., uv.x);\n" +
                "    //debugColor *= step(0., uv.y);\n" +
                "    //debugColor *= step(uv.x, 1.);\n" +
                "    //debugColor *= step(uv.y, 1.);\n" +
                "    //color = mix(color, vec4(1., 0., 0., 1.), debugColor * 0.5);\n" +
                "\n" +
                "    color += (10.0 / 255.0) * gradientNoise(fragCoord.xy) - (5.0 / 255.0);\n" +
                "\n" +
                "    if (uiColor.a < 0.01) {\n" +
                "        fragColor = color;\n" +
                "    } else {\n" +
                "        fragColor = uiColor;\n" +
                "    }\n" +
                "\n" +
                "    //        return vec4(0.0);\n" +
                "    //        return vec4(vUv,0.0,1.0);\n" +
                "    //        return vec4(abs(sin(uAnimTime)).rrr, 1.0);\n" +
                "    //            return texColor;\n" +
                "//    return vec4(noiseValue.rrr,1.0);\n" +
                "    return vec4(fragColor.rgb*fragColor.a, fragColor.a);\n" +
                "    return vec4(color.rgb*color.a, color.a);\n" +
                "}";
    }

    public void showRuntimeShader(Context context, View view, MaterialToolbar actionBar, boolean isNightMode) {
        calcAnimationBound(context, view, actionBar);
        if (isNightMode){
            setPhoneDark(this.bound);
        } else {
            setPhoneLight(this.bound);
        }

    }

    public void showRuntimeShader(Context context, View view) {
        calcAnimationBound(context, view, null);
        if (isNightMode(context)) {
            setPhoneDark(this.bound);
        } else {
            setPhoneLight(this.bound);
        }
    }

    public void calcAnimationBound(Context context, View view, MaterialToolbar actionBar) {
        float heightDp = 416;
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
