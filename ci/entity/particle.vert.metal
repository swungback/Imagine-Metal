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
    float2 texCoord0 [[user(locn2)]];
    float4 vertexColor [[user(locn3)]];
    float4 gl_Position [[position]];
};

struct main0_in
{
    float3 Position [[attribute(0)]];
    float2 UV0 [[attribute(1)]];
    float4 Color [[attribute(2)]];
    int2 UV2 [[attribute(3)]];
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
float4 sample_lightmap(texture2d<float> lightMap, sampler lightMapSmplr, thread const int2& uv)
{
    return lightMap.sample(lightMapSmplr, fast::clamp((float2(uv) / float2(256.0)) + float2(0.03125), float2(0.03125), float2(0.96875)), level(0.0));
}

vertex main0_out main0(main0_in in [[stage_in]], constant Projection& _74 [[buffer(0)]], constant DynamicTransforms& _80 [[buffer(1)]], texture2d<float> Sampler2 [[texture(0)]], sampler Sampler2Smplr [[sampler(0)]])
{
    main0_out out = {};
    out.gl_Position = (_74.ProjMat * _80.ModelViewMat) * float4(in.Position, 1.0);
    float3 param = in.Position;
    out.sphericalVertexDistance = fog_spherical_distance(param);
    float3 param_1 = in.Position;
    out.cylindricalVertexDistance = fog_cylindrical_distance(param_1);
    out.texCoord0 = in.UV0;
    int2 param_2 = in.UV2;
    out.vertexColor = in.Color * sample_lightmap(Sampler2, Sampler2Smplr, param_2);
    out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
    return out;
}

