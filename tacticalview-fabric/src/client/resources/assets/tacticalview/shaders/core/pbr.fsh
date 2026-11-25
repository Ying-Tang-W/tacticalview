#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:light.glsl>

uniform sampler2D TvBaseColor;
uniform sampler2D TvMetalRough;
uniform sampler2D TvNormal;
uniform sampler2D TvEmissive;
uniform sampler2D TvLightmap;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec3 viewPosition;
layout(location = 4) in vec3 viewNormal;
layout(location = 5) in vec4 viewTangent;

layout(location = 0) out vec4 fragColor;

// 约定（借用原版 DynamicTransforms 里现成的两个槽位，不用额外声明 uniform）：
//   ColorModulator.xy = 方块光 / 天空光 (0..1)
//   ModelOffset.xy    = glTF 材质的 metallicFactor / roughnessFactor
const float PI = 3.14159265359;

vec3 toLinear(vec3 c) { return pow(c, vec3(2.2)); }

float distributionGGX(float NdotH, float a) {
    float a2 = a * a;
    float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d);
}

float geometrySmith(float NdotV, float NdotL, float roughness) {
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    float gv = NdotV / (NdotV * (1.0 - k) + k);
    float gl = NdotL / (NdotL * (1.0 - k) + k);
    return gv * gl;
}

vec3 fresnelSchlick(float cosTheta, vec3 f0) {
    return f0 + (1.0 - f0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 shadeLight(vec3 L, vec3 N, vec3 V, vec3 albedo, float metallic, float roughness, vec3 f0, vec3 radiance) {
    vec3 H = normalize(L + V);
    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 1e-3);
    float NdotH = max(dot(N, H), 0.0);
    float a = max(roughness * roughness, 0.04);
    vec3 F = fresnelSchlick(max(dot(H, V), 0.0), f0);
    float D = distributionGGX(NdotH, a);
    float G = geometrySmith(NdotV, NdotL, roughness);
    vec3 spec = D * G * F / max(4.0 * NdotV * NdotL, 1e-3);
    vec3 diffuse = (1.0 - F) * (1.0 - metallic) * albedo / PI;
    return (diffuse + spec) * radiance * NdotL;
}

void main() {
    vec4 base = texture(TvBaseColor, texCoord0);
    if (base.a < 0.5) {
        discard; // 头发/睫毛等镂空部分
    }
    vec3 albedo = toLinear(base.rgb);
    vec4 mr = texture(TvMetalRough, texCoord0);
    float roughness = clamp(mr.g * ModelOffset.y, 0.04, 1.0);
    float metallic = clamp(mr.b * ModelOffset.x, 0.0, 1.0);
    vec3 emissive = toLinear(texture(TvEmissive, texCoord0).rgb);

    vec3 N = normalize(viewNormal);
    vec3 T = normalize(viewTangent.xyz - N * dot(N, viewTangent.xyz));
    vec3 B = cross(N, T) * viewTangent.w;
    vec3 tn = texture(TvNormal, texCoord0).xyz * 2.0 - 1.0;
    N = normalize(mat3(T, B, N) * tn);
    vec3 V = normalize(-viewPosition);
    // 薄刀片是双面的：背面时翻转法线
    if (!gl_FrontFacing) {
        N = -N;
    }

    // 原版光照贴图：xy 是 方块光/天空光，直接复用原版的昼夜、夜视、维度色调
    vec2 lightUv = clamp(ColorModulator.xy * (15.0 / 16.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    vec3 lightmap = toLinear(texture(TvLightmap, lightUv).rgb);

    vec3 f0 = mix(vec3(0.04), albedo, metallic);
    vec3 color = vec3(0.0);
    // 用原版两个方向光做主光，强度跟着光照贴图走（夜里自然变暗）
    color += shadeLight(normalize(Light0_Direction), N, V, albedo, metallic, roughness, f0, lightmap * 2.2);
    color += shadeLight(normalize(Light1_Direction), N, V, albedo, metallic, roughness, f0, lightmap * 1.0);
    // 环境光：漫反射 + 粗糙度相关的弱镜面
    float hemi = 0.65 + 0.35 * N.y;
    vec3 ambientSpec = fresnelSchlick(max(dot(N, V), 0.0), f0) * (1.0 - roughness) * 0.25;
    color += (albedo * (1.0 - metallic) * 0.55 + ambientSpec) * lightmap * hemi;
    color += emissive;

    // 简单色调映射并回到显示空间（原版帧缓冲不是线性的）
    color = color / (color + vec3(1.0)) * 1.15;
    vec4 outColor = vec4(pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
    fragColor = apply_fog(outColor, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
