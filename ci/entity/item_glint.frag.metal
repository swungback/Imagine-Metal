#pragma clang diagnostic ignored "-Wmissing-prototypes"

#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

struct Globals
{
    packed_int3 CameraBlockPos;
    float GlintAlpha;
    packed_float3 CameraOffset;
    float GameTime;
    float2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

struct Fog
{
    float4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

struct DynamicTransforms
{
    float4x4 ModelViewMat;
    float4x4 TextureMat;
    float4 ColorModulator;
    float3 ModelOffset;
};

struct main0_out
{
    float4 fragColor [[color(0)]];
};

struct main0_in
{
    float sphericalVertexDistance [[user(locn0)]];
    float cylindricalVertexDistance [[user(locn1)]];
    float4 vertexColor [[user(locn2)]];
    float4 lightMapColor [[user(locn3)]];
    float4 overlayColor [[user(locn4)]];
    float2 texCoord0 [[user(locn5)]];
    float2 texCoordGlint [[user(locn6)]];
};

static inline __attribute__((always_inline))
float linear_fog_value(thread const float& vertexDistance, thread const float& fogStart, thread const float& fogEnd)
{
    if (vertexDistance <= fogStart)
    {
        return 0.0;
    }
    else
    {
        if (vertexDistance >= fogEnd)
        {
            return 1.0;
        }
    }
    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

static inline __attribute__((always_inline))
float total_fog_value(thread const float& sphericalVertexDistance, thread const float& cylindricalVertexDistance, thread const float& environmentalStart, thread const float& environmantalEnd, thread const float& renderDistanceStart, thread const float& renderDistanceEnd)
{
    float param = sphericalVertexDistance;
    float param_1 = environmentalStart;
    float param_2 = environmantalEnd;
    float param_3 = cylindricalVertexDistance;
    float param_4 = renderDistanceStart;
    float param_5 = renderDistanceEnd;
    return fast::max(linear_fog_value(param, param_1, param_2), linear_fog_value(param_3, param_4, param_5));
}

static inline __attribute__((always_inline))
float4 apply_fog(thread const float4& inColor, thread const float& sphericalVertexDistance, thread const float& cylindricalVertexDistance, thread const float& environmentalStart, thread const float& environmantalEnd, thread const float& renderDistanceStart, thread const float& renderDistanceEnd, thread const float4& fogColor)
{
    float param = sphericalVertexDistance;
    float param_1 = cylindricalVertexDistance;
    float param_2 = environmentalStart;
    float param_3 = environmantalEnd;
    float param_4 = renderDistanceStart;
    float param_5 = renderDistanceEnd;
    float fogValue = total_fog_value(param, param_1, param_2, param_3, param_4, param_5);
    return float4(mix(inColor.xyz, fogColor.xyz, float3(fogValue * fogColor.w)), inColor.w);
}

static inline __attribute__((always_inline))
float4 calculateFinalColor(thread float4& color, thread float4& overlayColor, thread float4& lightMapColor, constant Globals& v_147, texture2d<float> GlintSampler, sampler GlintSamplerSmplr, thread float2& texCoordGlint, constant Fog& v_179, thread float& sphericalVertexDistance, thread float& cylindricalVertexDistance)
{
    float4 _121 = color;
    float3 _127 = mix(overlayColor.xyz, _121.xyz, float3(overlayColor.w));
    color.x = _127.x;
    color.y = _127.y;
    color.z = _127.z;
    color *= lightMapColor;
    float4 glintColor = GlintSampler.sample(GlintSamplerSmplr, texCoordGlint) * v_147.GlintAlpha;
    float4 _167 = color;
    float3 _169 = _167.xyz + (glintColor.xyz * glintColor.xyz);
    color.x = _169.x;
    color.y = _169.y;
    color.z = _169.z;
    float4 fogColor = v_179.FogColor;
    float4 param = color;
    float param_1 = sphericalVertexDistance;
    float param_2 = cylindricalVertexDistance;
    float param_3 = v_179.FogEnvironmentalStart;
    float param_4 = v_179.FogEnvironmentalEnd;
    float param_5 = v_179.FogRenderDistanceStart;
    float param_6 = v_179.FogRenderDistanceEnd;
    float4 param_7 = fogColor;
    return apply_fog(param, param_1, param_2, param_3, param_4, param_5, param_6, param_7);
}

fragment main0_out main0(main0_in in [[stage_in]], constant Globals& v_147 [[buffer(0)]], constant Fog& v_179 [[buffer(1)]], constant DynamicTransforms& _223 [[buffer(2)]], texture2d<float> GlintSampler [[texture(0)]], texture2d<float> Sampler0 [[texture(1)]], sampler GlintSamplerSmplr [[sampler(0)]], sampler Sampler0Smplr [[sampler(1)]])
{
    main0_out out = {};
    float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0);
    color *= (in.vertexColor * _223.ColorModulator);
    color.w = fast::max(color.w, v_147.GlintAlpha);
    float4 param = color;
    float4 _239 = calculateFinalColor(param, in.overlayColor, in.lightMapColor, v_147, GlintSampler, GlintSamplerSmplr, in.texCoordGlint, v_179, in.sphericalVertexDistance, in.cylindricalVertexDistance);
    out.fragColor = _239;
    return out;
}

