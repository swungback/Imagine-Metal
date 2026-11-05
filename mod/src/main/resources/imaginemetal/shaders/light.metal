// Imagine Metal: the coloured light grid (imlight.m, imaginemetal.LightVolume).
//
// A box of voxels around the player, one per block. seeds (RGBA8): per voxel the colour of the light the block gives off (rgb,
// brightest channel 1) and in a: bit 7 set if the block stops light, bit 6 stained glass (rgb its colour), bits 0-3 its light level. Light spreads as Minecraft's own
// does: one block a frame, one level weaker per block, stopped by solid blocks, so it reaches exactly as far as normal block light
// and bends around corners the same way. Where lights meet, their colours mix by how strong each one is there (squared, so the
// nearer, stronger light leads). Stored: rgb the mixed colour, a the strength (level / 15). The terrain takes the hue from it;
// how bright block light is stays Minecraft's own light level.
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
	uint bits = uint(s.a * 255.0 + 0.5);
	float level = float(bits & 15u) / 15.0;
	// a solid block stops light, unless it gives off light itself (glowstone, sea lanterns, froglights...)
	if ((bits & 128u) != 0u && level == 0.0) {
		next.write(half4(0.0h, 0.0h, 0.0h, -1.0h), id);  // (a < 0: solid, "no data" to the terrain's filter, not "no light")
		return;
	}
	float strength = level;
	float3 colour = s.rgb * (level * level);
	float weight = level * level;
	int3 c = v + shift.xyz;
	const int3 dirs[6] = {int3(1, 0, 0), int3(-1, 0, 0), int3(0, 1, 0), int3(0, -1, 0), int3(0, 0, 1), int3(0, 0, -1)};
	float4 nb[6];
	for (int i = 0; i < 6; i++) {
		int3 q = c + dirs[i];
		nb[i] = any(q < 0) || any(q >= size) ? float4(0.0) : float4(prev.read(uint3(q)));
		strength = max(strength, nb[i].a - 1.0 / 15.0);
	}
	// (the colour comes from upstream, the neighbours the light arrives from: each level dimmer counts an eighth as much. Mixed
	// evenly, light flowed back the way it came: the colour stained glass gave it spread back into the room the light came from)
	for (int i = 0; i < 6; i++) {
		float a = nb[i].a - 1.0 / 15.0;
		if (a <= 0.0) continue;
		float w = a * a * exp2(-(strength - a) * 45.0);
		colour += nb[i].rgb * w;
		weight += w;
	}
	float3 hue = weight > 0.0 ? colour / weight : float3(0.0);
	// stained glass (bit 6, rgb its colour): the light passing through takes its colour, as through a coloured filter
	if ((bits & 64u) != 0u && level == 0.0) {
		hue = mix(hue, hue * s.rgb, 0.72);  // (most of the way: Reimagined's colour, a little of the light's own kept)
		float m = max(max(hue.r, hue.g), hue.b);
		hue = m > 1e-4 ? hue / m : hue;
	}
	next.write(half4(half3(hue), half(strength)), id);
}
