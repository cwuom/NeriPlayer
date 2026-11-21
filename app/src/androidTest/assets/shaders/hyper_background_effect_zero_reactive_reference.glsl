uniform vec2 uResolution;
uniform shader uTex;

// 新版参数
uniform float uAnimTime;
uniform vec4 uBound;
uniform float uTranslateY;
uniform vec3 uPoints[5];
uniform vec4 uColors[5];
uniform float uAlphaMulti;
uniform float uSaturateOffset;
uniform float uLightOffset;
uniform float uLevelEase;
uniform float uBeatEase;
uniform float uMotionEase;
uniform float uZoom;
uniform float uColorPulse;
uniform vec2 uGlobalMotion;

vec3 rgb2hsv(vec3 c)
{
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));

    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c)
{
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

float gradientNoise(in vec2 uv)
{
    return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
}

vec4 main(vec2 fragCoord){
    vec2 vUv = fragCoord/uResolution;
    vUv.y = 1.0-vUv.y;
    vec2 uv = vUv;
    uv -= vec2(0., uTranslateY);

    float levelEase = uLevelEase;
    float beatEase = uBeatEase;
    float motionEase = uMotionEase;
    float zoom = uZoom;
    vec2  center = vec2(0.5);
    uv = (uv - center) / zoom + center;

    float beatWave = sin((vUv.y + uAnimTime * 0.12) * 6.2832) *
        cos((vUv.x - uAnimTime * 0.10) * 6.2832);
    float radialPulse = sin((distance(vUv, center) * 5.5 - uAnimTime * 0.18) * 6.2832);
    float ribbonWave = sin(((vUv.x * 1.7 + vUv.y * 2.3) - uAnimTime * 0.12) * 6.2832);
    uv += beatEase * 0.0110 * vec2(beatWave, -beatWave);
    uv += beatEase * 0.0085 * normalize(vUv - center + vec2(1e-4)) * radialPulse;
    uv += motionEase * 0.0062 * vec2(ribbonWave, -ribbonWave * 0.75);
    uv += uGlobalMotion;

    uv.xy -= uBound.xy;
    uv.xy /= uBound.zw;

    vec3 hsv;
    vec4 color = vec4(0.0);

    vec4 colorAccum = vec4(0.0);
    float weightSum = 0.0;

    // blend soft color fields
    for (int i = 0; i < 5; i++){
        vec4 pointColor = uColors[i];
        pointColor.rgb *= pointColor.a;
        vec2 point = uPoints[i].xy;
        float rad = uPoints[i].z;

        vec2 delta = uv - point;
        float radiusSq = max(rad * rad, 1e-4);
        float weight = 1.0 / (1.0 + dot(delta, delta) / radiusSq * 9.3);
        weight *= weight;

        colorAccum += pointColor * weight;
        weightSum += weight;
    }

    color = colorAccum / max(weightSum, 1e-5);
    color.rgb /= max(color.a, 1e-5);
    hsv = rgb2hsv(color.rgb);

    // 颜色响应跟随低通后的能量，保留律动但避开高频闪烁
    float colorPulse = uColorPulse;
    float boostedSaturation = hsv.y * (1.16 + 0.06 * colorPulse) + 0.030 * colorPulse * uSaturateOffset;
    float darkSaturationLimit = mix(0.66, 0.82, smoothstep(0.34, 0.74, hsv.z));
    hsv.y = clamp(min(boostedSaturation, darkSaturationLimit), 0.0, 1.0);
    hsv.z = clamp((hsv.z - 0.5) * (1.26 + 0.06 * colorPulse) + 0.5 + 0.018 * colorPulse, 0.0, 1.0);
    color.rgb = hsv2rgb(hsv);
    color.rgb += 0.010 * colorPulse * uLightOffset;
    color.rgb *= mix(0.68, 1.10, smoothstep(0.10, 0.92, vUv.y));
    color.rgb = clamp(color.rgb, 0.0, 1.0);

    // 透明度保持稳定，避免音频脉冲造成整屏闪烁
    color.a = clamp(color.a, 0., 1.);
    color.a *= uAlphaMulti;

    vec4 uiColor  = uTex.eval(vec2(vUv.x, 1.0 - vUv.y)*uResolution);

    // 颗粒
    float dither = (gradientNoise(fragCoord.xy) - 0.5) * (5.0 / 255.0);
    color.rgb = clamp(color.rgb + dither, 0.0, 1.0);

    vec4 fragColor = (uiColor.a < 0.01) ? color : uiColor;
    return vec4(fragColor.rgb*fragColor.a, fragColor.a);
}
