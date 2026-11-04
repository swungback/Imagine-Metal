#pragma clang diagnostic ignored "-Wmissing-prototypes"

#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

struct Projection
{
    float4x4 ProjMat;
};

struct DynamicTransforms
{
    float4x4 ModelViewMat;
    float4x4 TextureMat;
    float4 ColorModulator;
    float3 ModelOffset;
};

struct Lighting
{
    float3 Light0_Direction;
    float3 Light1_Direction;
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

struct main0_out
{
    float sphericalVertexDistance [[user(locn0)]];
    float cylindricalVertexDistance [[user(locn1)]];
    float4 vertexColor [[user(locn2)]];
    float2 texCoord0 [[user(locn6)]];
    float4 gl_Position [[position]];
};

struct main0_in
{
    float3 Position [[attribute(0)]];
    float4 Color [[attribute(1)]];
    float2 UV0 [[attribute(2)]];
    float3 Normal [[attribute(5)]];
};

static inline __attribute__((always_inline))
float fog_spherical_distance(thread const float3& pos)
{
    return length(pos);
}

static inline __attribute__((always_inline))
float fog_cylindrical_distance(thread const float3& pos)
{
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return fast::max(distXZ, distY);
}

static inline __attribute__((always_inline))
float2 minecraft_compute_light(thread const float3& lightDir0, thread const float3& lightDir1, thread const float3& normal)
{
    return float2(dot(lightDir0, normal), dot(lightDir1, normal));
}

static inline __attribute__((always_inline))
float4 minecraft_mix_light_separate(thread const float2& light, thread const float4& color)
{
    float2 lightValue = fast::max(float2(0.0), light);
    float lightAccum = fast::min(1.0, ((lightValue.x + lightValue.y) * 0.60000002384185791015625) + 0.4000000059604644775390625);
    return float4(color.xyz * lightAccum, color.w);
}

static inline __attribute__((always_inline))
float4 minecraft_mix_light(thread const float3& lightDir0, thread const float3& lightDir1, thread const float3& normal, thread const float4& color)
{
    float3 param = lightDir0;
    float3 param_1 = lightDir1;
    float3 param_2 = normal;
    float2 light = minecraft_compute_light(param, param_1, param_2);
    float2 param_3 = light;
    float4 param_4 = color;
    return minecraft_mix_light_separate(param_3, param_4);
}

vertex main0_out main0(main0_in in [[stage_in]], constant Projection& _122 [[buffer(0)]], constant DynamicTransforms& _128 [[buffer(1)]], constant Lighting& _154 [[buffer(2)]])
{
    main0_out out = {};
    out.gl_Position = (_122.ProjMat * _128.ModelViewMat) * float4(in.Position, 1.0);
    float3 param = in.Position;
    out.sphericalVertexDistance = fog_spherical_distance(param);
    float3 param_1 = in.Position;
    out.cylindricalVertexDistance = fog_cylindrical_distance(param_1);
    float3 param_2 = _154.Light0_Direction;
    float3 param_3 = _154.Light1_Direction;
    float3 param_4 = in.Normal;
    float4 param_5 = in.Color;
    out.vertexColor = minecraft_mix_light(param_2, param_3, param_4, param_5);
    out.texCoord0 = in.UV0;
    out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
    return out;
}

