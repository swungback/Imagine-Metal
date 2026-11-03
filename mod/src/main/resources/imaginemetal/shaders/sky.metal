// Imagine Metal: the sky (imsky.m, imaginemetal.Sky). Compiled after lighting.metal (ImFrame, imSkyColor).
//
// Drawn once per frame into the level's picture at the start of Minecraft's sky pass, in place of its flat sky disc and sunset fan
// (SkyRendererMixin): one triangle over the screen, every pixel the sky colour in its view direction. Minecraft's sun, moon and
// stars are drawn over it as usual, then the level. About 0.1 ms on an M1 at 1440x900.

struct ImSkyOut {
	float4 pos [[position]];
	float2 ndc;
};

vertex ImSkyOut im_sky_vs(uint vid [[vertex_id]]) {
	float2 p = float2((vid << 1) & 2, vid & 2);
	ImSkyOut o;
	o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0);
	o.ndc = p * 2.0 - 1.0;
	return o;
}

// rot: clip -> view direction (the inverse of the camera's rotation and projection, no translation)
fragment float4 im_sky_fs(ImSkyOut in [[stage_in]], constant ImFrame &f [[buffer(0)]], constant float4x4 &rot [[buffer(1)]]) {
	// (the backend flips vertex y so its images keep GL's row order: this pixel is where clip y = -ndc.y lands)
	float4 q = rot * float4(in.ndc.x, -in.ndc.y, 0.5, 1.0);
	float3 d = normalize(q.xyz / q.w);
	// (through the same exposure, tone curve and grade as the terrain, as Reimagined's sky goes through its tonemap with the rest)
	return float4(pow(imPresent(imSkyColor(d, f, true), f), 1.0 / 2.2), 1.0);
}
