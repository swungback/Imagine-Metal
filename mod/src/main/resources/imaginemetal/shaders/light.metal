// Imagine Metal: the coloured light grid (imlight.m, imaginemetal.LightVolume).
//
// A box of voxels around the player, one per block. seeds: per voxel the light a block gives off (rgb: its colour times its
// light level / 15) and whether it stops light (a: 1 for a solid block). Every frame each open voxel takes its own light plus a
// share of its six neighbours' light from the frame before, so light spreads one block a frame, bends around corners, stops at
// walls, and mixes where two colours meet (weighted by how near each is). The terrain reads only the hue from it; how bright block
// light is comes from Minecraft's own light levels, so the coloured light reaches exactly as far as normal light does.
//
// shift: when the box moves (the player crossed into another section), how far, in voxels: the previous frame is read shifted by
// it, so what has already spread stays in place in the world instead of starting over.
#include <metal_stdlib>
using namespace metal;

kernel void im_light_step(texture3d<float, access::read> seeds [[texture(0)]], texture3d<half, access::read> prev [[texture(1)]],
	texture3d<half, access::write> next [[texture(2)]], constant int4 &shift [[buffer(0)]], uint3 id [[thread_position_in_grid]]) {
	int3 size = int3(next.get_width(), next.get_height(), next.get_depth());
	int3 v = int3(id);
	if (any(v >= size)) return;
	float4 s = seeds.read(id);
	// a solid block stops light, unless it gives off light itself (glowstone, sea lanterns, froglights...)
	if (s.a > 0.5 && all(s.rgb == 0.0)) {
		next.write(half4(0.0h), id);
		return;
	}
	int3 c = v + shift.xyz;
	float3 sum = float3(0.0);
	const int3 dirs[6] = {int3(1, 0, 0), int3(-1, 0, 0), int3(0, 1, 0), int3(0, -1, 0), int3(0, 0, 1), int3(0, 0, -1)};
	for (int i = 0; i < 6; i++) {
		int3 q = c + dirs[i];
		if (all(q >= 0) && all(q < size)) sum += float3(prev.read(uint3(q)).rgb);
	}
	// 0.99: light keeps most of its strength from block to block (a reach of tens of blocks before the half floats run out), so
	// the hue is known everywhere Minecraft's light reaches; the brightness itself is not used
	float3 l = s.rgb * 4.0 + sum * (0.99 / 6.0);
	next.write(half4(half3(min(l, float3(60000.0))), 1.0h), id);
}
