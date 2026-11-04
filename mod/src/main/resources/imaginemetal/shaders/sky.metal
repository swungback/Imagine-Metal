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

// ---- stars (Complementary Reimagined's, lib/atmospherics/stars.glsl: a field of pixel stars on a flattened dome, drifting
// slowly) with a twinkle (after Euphoria Patches: each star its own slow flicker) and shooting stars ----
static inline float imStarNoise(float2 p) {
	return fract(sin(dot(p, float2(12.9898, 4.1414))) * 43758.54953);
}

static float3 imStars(float3 d, constant ImFrame &f) {
	if (d.y <= 0.0) return float3(0.0);
	float2 sc = d.xz / (d.y + length(d.xz) * 0.5);
	sc.x += 0.006 * f.world.w;
	sc *= 0.2;
	float2 cell = floor(sc * 1024.0) / 1024.0;
	float star = imStarNoise(cell) * imStarNoise(cell + 0.1) * imStarNoise(cell + 0.23);
	star = max(star - 0.7, 0.0);
	star *= star;
	if (star <= 0.0) return float3(0.0);
	float h = imStarNoise(cell + 0.37);
	float tw = 0.5 + 0.5 * sin(f.world.w * (1.3 + 2.6 * h) + h * 40.0) * sin(f.world.w * (0.7 + 1.1 * h) + h * 17.0);
	star *= tw;
	float vdots = dot(d, f.sunDir.xyz);
	star *= min(d.y * 3.0, 1.0) * max(1.0 - pow(abs(vdots) * 1.002, 100.0), 0.0);
	float inv = f.sunsetDown.w * f.sunsetDown.w;
	inv *= inv;
	star *= (1.0 - f.dayUp.w) * inv * inv * (1.0 - 0.5 * f.nightMiddle.w);
	// (a little colour each: most white-blue, some warmer)
	float3 tint = mix(float3(0.38, 0.4, 0.5), float3(0.5, 0.42, 0.34), step(0.8, h));
	return 70.0 * star * tint;
}

// One shooting star's slot (period seconds): most slots have one, at a random place high in the sky, crossing a short arc in
// under a second, a bright head and a fading tail.
static float imShootingStar(float3 d, float t, float period, float seed) {
	float slot = floor(t / period);
	float r0 = imStarNoise(float2(slot, seed)), r1 = imStarNoise(float2(slot + 0.31, seed)), r2 = imStarNoise(float2(slot + 0.67, seed));
	float r3 = imStarNoise(float2(slot + 0.13, seed + 1.7));
	if (r3 > 0.75) return 0.0;
	float life = 0.9;
	float age = (t - slot * period - r3 * (period - life)) / life;
	if (age < 0.0 || age > 1.0) return 0.0;
	float az = r0 * 6.2832, el = 0.45 + 0.6 * r1;
	float3 s = float3(cos(az) * cos(el), sin(el), sin(az) * cos(el));
	float3 e1 = normalize(cross(s, float3(0.0, 1.0, 0.0)));
	float3 e2 = cross(e1, s);
	float ang = r2 * 6.2832;
	float3 v = normalize(e1 * cos(ang) + e2 * sin(ang) * 0.5 - s * 0.0);
	v.y = -abs(v.y) - 0.15;  // (falling)
	v = normalize(v - s * dot(v, s));
	float travel = 0.35;
	float3 head = normalize(s + v * (travel * age));
	float3 tail = normalize(s + v * (travel * max(age - 0.25, 0.0)));
	float3 ab = head - tail;
	float k = saturate(dot(d - tail, ab) / max(dot(ab, ab), 1e-8));
	float dist = length(d - (tail + ab * k));
	float w = 0.003;
	float line = saturate(1.0 - dist / w);
	float fade = sin(age * 3.14159);
	return line * line * k * k * fade * step(0.0, dot(d, s));
}

// rot: clip -> view direction (the inverse of the camera's rotation and projection, no translation)
// The End's black hole (Settings.endStyle 1), after IterationT's End (its look studied, written here from scratch): a black sky
// of small stars of every star colour, a black hole low over the islands seen from eight units away, its light bent by its
// gravity (each step of the ray turned toward it by 1/r^2), a thin hot accretion disk tilted almost edge-on that the bending
// wraps over the top and under the bottom of the shadow, white-blue at its inner edge and orange outward, brighter on the side
// turning toward us; a bright glow hugging the shadow. Beside it a vast ringed planet, its rings seen nearly edge-on as a band
// of streaks across the sky, lit and shadowed by the black hole's light. rgb: light (linear, through the tone curve as the
// terrain's); hdr: the brightest parts, added after it so the bloom haloes them.

// a black body's colour at temperature t (kelvin; Planckian locus, normalized to its brightest channel)
static float3 imBlackbody(float t) {
	t = clamp(t, 1000.0, 40000.0) / 100.0;
	float3 c;
	c.r = t <= 66.0 ? 1.0 : saturate(1.292936 * pow(t - 60.0, -0.1332047592));
	c.g = t <= 66.0 ? saturate(0.3900816 * log(t) - 0.6318414) : saturate(1.1298909 * pow(t - 60.0, -0.0755148492));
	c.b = t >= 66.0 ? 1.0 : (t <= 19.0 ? 0.0 : saturate(0.5432068 * log(t - 10.0) - 1.1962541));
	return c * c;  // (to linear, roughly)
}

static inline float imBhHash(float3 q) {
	uint3 u = uint3(int3(floor(q)));
	uint h = u.x * 1597334677u ^ u.y * 3812015801u ^ u.z * 2654435761u;
	h = (h ^ (h >> 16)) * 2246822519u;
	h ^= h >> 13;
	return float(h & 0xffffffu) / 16777216.0;
}

static inline float imBhNoise2(float2 q) {
	float2 i = floor(q), u = q - i;
	u = u * u * (3.0 - 2.0 * u);
	float a = imBhHash(float3(i, 7.0)), b = imBhHash(float3(i + float2(1, 0), 7.0));
	float c = imBhHash(float3(i + float2(0, 1), 7.0)), d = imBhHash(float3(i + float2(1, 1), 7.0));
	return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

static inline float imMiePhase(float g, float nu) {
	float gg = g * g;
	return 0.1193662 * (1.0 - gg) / (2.0 + gg) * (1.0 + nu * nu) * pow(1.0 + gg - 2.0 * g * nu, -1.5);
}

static float3 imBlackHoleSky(float3 d, constant ImFrame &f, thread float3 &hdr) {
	float3 B = normalize(f.lightDir.xyz);
	float3 R = normalize(cross(B, float3(0.0, 1.0, 0.0)));
	float3 U = cross(R, B);
	float t = f.world.w;
	hdr = float3(0.0);

	// the black hole's own frame: the singularity at the origin, the eye 8 units back along the view to it; the disk's normal
	// tilted 0.1 rad toward us and rolled 0.35 rad
	const float tilt = 0.1, roll = -0.35;
	float3 Ur = U * cos(roll) + R * sin(roll), Rr = R * cos(roll) - U * sin(roll);
	float3 Nd = normalize(Ur * cos(tilt) - B * sin(tilt));
	float3 Xd = Rr;
	float3 Zd = normalize(cross(Xd, Nd));

	float3 col = float3(0.0), res = float3(0.0);
	float trans = 1.0;
	float3 rd = d;
	float near = dot(d, B);
	if (near > 0.82) {
		const int STEPS = 44;
		const float stepLen = 0.23;
		const float inner = 0.5, width = 3.5;
		float noise = fract(dot(d.xy * 4093.0 + d.z * 811.0, float2(0.06711056, 0.00583715)) * 52.9829189);
		float3 pos = -B * 8.0 + rd * (3.0 + stepLen * noise);
		for (int i = 0; i < STEPS; i++) {
			if (trans < 0.001) break;
			float r2 = dot(pos, pos);
			rd = normalize(rd - pos * (rsqrt(r2) / (r2 + 1e-6)) * 0.06);
			pos += rd * stepLen;
			float3 dp = float3(dot(pos, Xd), dot(pos, Nd), dot(pos, Zd));
			float r = length(dp);
			float h = dp.y;
			float rg = 1.0 - saturate((r - inner) / width * 0.5);
			float thick = 0.1 * rg;
			float fr = abs(r - inner) + 0.4;
			fr *= fr;
			float bloom = 1.0 / (h * h * 40.0 + fr * fr * 0.04 + 0.00002) * saturate(2.0 - abs(h) / max(thick, 1e-4));
			bloom *= bloom;
			float x4 = rg * rg;
			x4 *= x4;
			float dr = 12.207 * x4 * (1.0 - rg);
			float dens = saturate(saturate(dr * saturate(1.0 - abs(h) / max(thick, 1e-4)) * 0.7) + bloom * 0.1);
			if (dens > 0.0001) {
				// (rings of denser and thinner gas, drifting slowly)
				float2 q = float2(r * 3.5 + t * 0.03, h * 0.35);
				float fbm = imBhNoise2(q) * 0.5 + imBhNoise2(q * 2.5 + 3.1) * 0.435 + imBhNoise2(q * 6.25 + 7.7) * 0.378;
				fbm = fbm * 0.75 + 0.11;
				fbm *= fbm;
				fbm *= fbm;
				dens *= fbm * dr;
				float gr = 1.0 - rg;
				gr *= gr;
				float glow = 1.0 / (gr * gr * 400.0 + 0.002);
				float3 gc = imBlackbody(2700.0 + glow * 50.0) * glow;
				float ph = atan2(-dp.z, -dp.x);
				gc *= sin(ph - 1.07) * 0.75 + 1.0;  // (Doppler)
				float st = exp2(-dens * 7.0);
				trans *= st;
				res += (1.0 - st) * trans * gc;
			}
			// the glow close around the shadow
			float L = length(dp - float3(0.0, 0.05, 0.0));
			float td = length(float2(L - 1.0, L - 0.01));
			if (r > 0.5) res += float3(0.78, 0.86, 1.0) * (0.1 / (pow(td, 3.5) + 0.001)) * trans;
		}
		res *= 1.0 / float(STEPS);
		res *= smoothstep(0.82, 0.88, near);
		rd = normalize(mix(d, rd, smoothstep(0.82, 0.9, near)));
	}

	// the stars (seen through the bent light): a grid on the sphere, a few cells lit, each its black body's colour
	{
		float3 sp = rd * 384.0;
		float3 cell = floor(sp), fq = sp - cell;
		float rr = dot(fq - 0.5, fq - 0.5);
		float h1 = imBhHash(cell), h2 = imBhHash(cell + 31.7);
		float c = saturate((h1 - 0.993) / 0.007);
		col += 0.06 * saturate(1.0 - rr / 0.25) * c * c * imBlackbody(mix(4000.0, 8000.0, h2));
	}

	// the ringed planet: its centre beside the black hole, 1.75 of its radii away (so it fills a wide part of the sky); its rings
	// from 1.6 to 2.6 radii in a plane passing just beside the eye, so they cross the sky as a band of thin streaks
	float3 P = normalize(B * 0.55 - R * 0.78 + U * 0.08);
	float3 C = P * 1.75;
	float3 Nr = normalize(U * 0.97 + R * 0.12 + B * 0.2);
	Nr = normalize(Nr - P * dot(Nr, P));
	float hOff = 0.014;  // (the eye's height over the ring plane, in planet radii)
	float3 Cr = C - Nr * (dot(C, Nr) + hOff);  // (the plane through the planet's centre, moved so the eye is hOff above it)
	float b = dot(rd, C);
	float disc = b * b - (dot(C, C) - 1.0);
	float tPlanet = disc > 0.0 ? b - sqrt(disc) : -1.0;
	float VdotL = dot(rd, B);
	float mie = imMiePhase(0.8, VdotL);
	if (tPlanet > 0.0) {
		float3 Xp = rd * tPlanet;
		float3 Np = normalize(Xp - C);
		float nl = saturate(dot(Np, B)), nv = saturate(dot(Np, -rd));
		float3 alb = float3(0.98, 0.87, 0.55);
		// (a bright, rough planet: its lit side's light spread like a thick cloud deck's)
		float3 surf = alb * nl * (0.12 / (nl + nv + 0.2));
		// (the rings' shadow on it)
		float tr = dot(Cr - Xp, Nr) / dot(B, Nr);
		if (tr > 0.0) {
			float rho = length(Xp + B * tr - Cr);
			if (rho > 1.6 && rho < 2.6) surf *= 0.35 + 0.65 * imBhNoise2(float2(rho * 9.0, 0.0));
		}
		col = surf * 0.6 + float3(0.012, 0.012, 0.014);
		// (its atmosphere's rim, toward the light)
		float rim = pow(1.0 - nv, 6.0) * (0.3 + 0.7 * saturate(dot(Np, B) + 0.3));
		col += float3(0.65, 0.7, 1.0) * rim * 0.25;
		hdr += float3(0.65, 0.7, 1.0) * pow(1.0 - nv, 24.0) * saturate(dot(Np, B) + 0.2) * 0.8;
	} else {
		// the atmosphere's glow just outside the rim
		float edge = disc + 0.06;
		if (edge > 0.0) col += float3(0.65, 0.7, 1.0) * saturate(edge / 0.06) * saturate(edge / 0.06) * 0.03 * (0.3 + mie * 6.0);
	}
	// the rings
	{
		float dn = dot(rd, Nr);
		float tr = abs(dn) > 1e-5 ? dot(Cr, Nr) / dn : -1.0;
		if (tr > 0.0 && (tPlanet < 0.0 || tr < tPlanet)) {
			float3 X = rd * tr - Cr;
			float rho = length(X);
			if (rho > 1.6 && rho < 2.6) {
				float pos = rho * 0.5 + 0.69, acc = 0.0, al = 0.5;
				for (int i = 0; i < 5; i++) {
					acc += al * imBhNoise2(float2(pos * 64.0, 3.0));
					pos *= 4.0;
					al *= 0.5;
				}
				float ring = pow(saturate(acc + 0.025 - 0.1) * 1.5, 3.0) * smoothstep(1.6, 1.76, rho);
				// (in the planet's shadow: dark)
				float3 Xc = rd * tr - C;
				float sd = length(cross(B, Xc));
				ring *= 0.98 * max(smoothstep(0.8, 1.2, sd), step(0.0, dot(B, Xc))) + 0.02;
				col *= exp2(-ring * 3.0);
				ring *= 1.0 + mie * 10.0;
				float3 rc = float3(1.0, 0.85, 0.6) * ring * 0.09;
				col += rc;
				hdr += rc * 0.4;
			}
		}
	}

	col = col * trans + res;
	hdr += min(res * 0.35, float3(3.0));
	return col;
}

static float4 imSkyPixel(ImSkyOut in, constant ImFrame &f, constant float4x4 &rot);

fragment float4 im_sky_fs(ImSkyOut in [[stage_in]], constant ImFrame &f [[buffer(0)]], constant float4x4 &rot [[buffer(1)]]) {
	return imSkyPixel(in, f, rot);
}

// The End's sky, drawn once the level has (Sky.drawEndLate): only where nothing was drawn (the depth still at its clear), so no
// pass of Minecraft's can clear over it, and the bloom that follows picks up the black hole's glow.
fragment float4 im_sky_end_fs(ImSkyOut in [[stage_in]], constant ImFrame &f [[buffer(0)]], constant float4x4 &rot [[buffer(1)]],
	depth2d<float> depth [[texture(2)]]) {
	uint2 px = min(uint2(in.pos.xy), uint2(depth.get_width() - 1, depth.get_height() - 1));
	if (depth.read(px) < 0.99999) discard_fragment();
	return imSkyPixel(in, f, rot);
}

static float4 imSkyPixel(ImSkyOut in, constant ImFrame &f, constant float4x4 &rot) {
	// (the backend flips vertex y so its images keep GL's row order: this pixel is where clip y = -ndc.y lands)
	float4 q = rot * float4(in.ndc.x, -in.ndc.y, 0.5, 1.0);
	float3 d = normalize(q.xyz / q.w);
	// (through the same exposure, tone curve and grade as the terrain, as Reimagined's sky goes through its tonemap with the rest)
	if (f.flags.x == 2 && f.nether.x > 0.5) {
		float3 hdr;
		float3 bh = imBlackHoleSky(d, f, hdr);
		return float4(pow(imPresent(bh, f), 1.0 / 2.2) + hdr, 1.0);
	}
	if (f.flags.x == 2) {
		// the End (Reimagined's): a deep violet sky full of tiny stars, thickest toward the horizon, both above and below
		float3 endSky = float3(0.095, 0.07, 0.15) * 1.65;
		float2 sc = (0.65 * d / (abs(d.y) + length(d.xz))).xz * 0.5;
		if (d.y < 0.0) sc += 100.0;
		float2 cell = floor(sc * 1024.0) / 1024.0;
		float st = imStarNoise(cell) * imStarNoise(cell + 0.1) * imStarNoise(cell + 0.23);
		st = max(st - 0.7, 0.0);
		st *= st;
		float au = abs(d.y), vu2 = (1.0 - au) * (1.0 - au);
		float3 stars = st * endSky * 3000.0 * (min(vu2 + 0.015, 0.05) + 0.015) * smoothstep(0.0, 0.25, au);
		return float4(pow(imPresent(endSky + stars, f), 1.0 / 2.2), 1.0);
	}
	float3 sky = imSkyColor(d, f, true);
	uint st = uint(f.auroraLight.w + 0.5);
	if (st != 0u && d.y > 0.0 && f.flags.x == 0) {
		if ((st & 1u) != 0u) sky += imStars(d, f);
		if ((st & 2u) != 0u) {
			float inv = f.sunsetDown.w * f.sunsetDown.w;
			float vis = (1.0 - f.dayUp.w) * inv * inv * (1.0 - f.nightMiddle.w);
			if (vis > 0.0) {
				float t = f.world.w;
				float ss = imShootingStar(d, t, 9.0, 0.0) + imShootingStar(d, t + 4.0, 13.0, 3.0);
				sky += float3(0.9, 0.95, 1.0) * (14.0 * ss * vis);
			}
		}
	}
	float3 col = pow(imPresent(sky, f), 1.0 / 2.2);
	// the sun's glare (Reimagined's sun: white-hot, the bloom throwing a wide halo in its light's colour): a core past white under
	// Minecraft's sun square (drawn over this), so the bloom picks it up; gone in rain and once it has set
	if (f.flags.x == 0 && f.lightDir.w > 0.5) {
		float vs = saturate(dot(d, f.sunDir.xyz));
		float core = smoothstep(0.9965, 0.9992, vs);
		float halo = pow(vs, 600.0) * 0.6;
		float k = (core * 2.5 + halo) * (1.0 - f.dayUp.w) * smoothstep(-0.02, 0.06, f.sunDir.y);
		float3 lc = f.lightColor.rgb / max(max(max(f.lightColor.r, f.lightColor.g), f.lightColor.b), 1e-3);
		col += mix(lc, float3(1.0), core * 0.6) * k;
	}
	return float4(col, 1.0);
}

// ---- clouds (Complementary Reimagined's blocky clouds, lib/atmospherics/clouds/reimaginedClouds.glsl) and the aurora ----
// Minecraft's own cloud map (one texel 12 blocks) at Minecraft's cloud height, each cell traced as a block with rounded edges and
// a rounded top and bottom (SixthSurge's rounded coordinates); lit from the top down, darker where more cloud lies toward the
// light; blended toward the sky behind with distance. Drawn after Minecraft's sun, moon and stars, so clouds pass in front of them.

constant float IM_CLOUD_STRETCH = 4.0;   // half the clouds' thickness (blocks)
constant float IM_CLOUD_ROUND = 0.125;

static inline float imCloudMap(texture2d<float> ct, sampler s, float2 texel) {
	// (wrapped into one positive copy of the map first: the old mirroring of negative coordinates turned the map around the
	// moment the camera crossed the map's edge, every 3072 blocks, and the clouds jumped)
	float2 sz = float2(ct.get_width(), ct.get_height());
	float2 c = texel + 0.5;
	c = c - floor(c / sz) * sz + sz;
	float2 i = floor(c), fr = c - i;
	fr = smoothstep(0.5 - IM_CLOUD_ROUND, 0.5 + IM_CLOUD_ROUND, fr);
	c = i + fr - 0.5;
	return ct.sample(s, c / sz).r;
}

static float4 imClouds(float3 d, constant ImFrame &f, texture2d<float> ct, sampler s, float dither) {
	float camY = f.fogParams.y, alt = f.cloudPos.z;
	float hi = alt + IM_CLOUD_STRETCH, lo = alt - IM_CLOUD_STRETCH;
	if (abs(d.y) < 1e-4) return float4(0.0);
	float tLo = (lo - camY) / d.y, tHi = (hi - camY) / d.y;
	float tMin = max(min(tLo, tHi), 0.0), tMax = max(tLo, tHi);
	if (tMax <= 0.0) return float4(0.0);
	const float reach = 4000.0;
	tMax = min(tMax, reach / max(length(d.xz), 1e-3));
	if (tMax <= tMin) return float4(0.0);
	int count = clamp(int((tMax - tMin) / 8.0), 12, 40);
	float stepLen = (tMax - tMin) / float(count);
	float t = tMin + stepLen * dither;
	float rain = f.dayUp.w, sunVis = f.nightMiddle.w, noon = f.dayDown.w, shadowTime = f.nightDown.w, sunFactor = f.dayMiddle.w;
	for (int i = 0; i < count; i++, t += stepLen) {
		float3 p = d * t;  // camera-relative
		float y = camY + p.y;
		float2 texel = f.cloudPos.xy + p.xz / 12.0;
		float th = saturate(abs(alt - y) / IM_CLOUD_STRETCH);
		th = th * th;
		th = th * th;
		th = th * th;
		if (imCloudMap(ct, s, texel) <= th * 0.5 + 0.25) continue;
		// lit from the top down, and less where more cloud lies toward the light (two looks along it)
		float shade = pow(max(1.0 - (hi - y) / (IM_CLOUD_STRETCH * 2.0), 0.0), 0.8);
		float shadeM = 1.0 - shade * shade;
		float2 toLight = f.lightDir.xz * (0.08 * shadowTime);  // (Reimagined's: in cloud-map texels)
		float light = 2.0;
		light -= imCloudMap(ct, s, texel + toLight * (1.0 + dither)) * shadeM;
		light -= imCloudMap(ct, s, texel + toLight * (1.0 + 2.0 * dither)) * shadeM;
		float VdotS = dot(d, f.sunDir.xyz);
		float vs = max(sunVis > 0.5 ? VdotS : -VdotS, 0.0);
		shade = (vs * shadowTime * 0.25 + 0.5 * shade + 0.08) * light;
		float3 rainCol = mix(f.nightMiddle.rgb, f.dayMiddle.rgb, sunFactor);
		float3 amb = mix(f.ambientUp.rgb * (sunVis * sunVis * (0.55 + float3(0.2, 0.1, 0.0) * noon) + 0.35), rainCol * 0.5, rain) * (1.0 - 0.25 * rain);
		float3 lit = mix(f.lightColor.rgb * 1.3, rainCol * 0.45, noon * rain);
		float3 col = amb * 0.95 * (1.0 - 0.35 * shade) + lit * 0.9 * (0.1 + shade);
		float dxz = length(p.xz);
		float ratio = saturate((reach - dxz) / reach);
		float fogF = ratio * ratio * 0.707;
		col = mix(imSkyColor(d, f, false), col, fogF * 0.707);
		float a = sqrt(min(ratio, 0.75) * 1.33333);
		return float4(col, a);
	}
	return float4(0.0);
}

// Reimagined's aurora (lib/atmospherics/auroraBorealis.glsl, its second style): curtains of noise on a plane above, pink-violet
// below, green at their height. At night in cold biomes (f.aurora.x its visibility).
static float3 imAurora(float3 d, constant ImFrame &f, texture2d<float> nt, sampler s, float dither) {
	float u = saturate(d.y * 1.5 - 0.225);
	float vis = u * (2.0 - u) * f.aurora.x * (1.0 - d.y * 0.9);
	if (vis <= 0.0) return float3(0.0);
	float2 w = d.xz / max(d.y, 0.05);
	float3 acc = float3(0.0);
	const int N = 16;
	for (int i = 0; i < N; i++) {
		float cur = (float(i) + dither + 5.0) / float(N + 5);
		cur *= cur;
		float2 pp = w * (0.8 + cur) * 11.0 + f.aurora.zw;
		if (f.auroraLow.w > 0.5) pp = floor(pp);  // (Reimagined's pixel style: curtains in blocky steps)
		pp *= 0.0007;
		float n = nt.sample(s, pp).r;
		n = 1.0 - 2.0 * abs(n - 0.5);
		n *= n;
		n *= n;
		n *= n;
		n *= n;
		n *= nt.sample(s, pp * 3.0 + f.aurora.y).b;
		n *= nt.sample(s, pp * 5.0 - f.aurora.y).b;
		float cm = 1.0 - cur;
		float cm2 = cm * cm;
		acc += n * cm * mix(f.auroraLow.rgb, f.auroraHigh.rgb, cm2 * cm2);
	}
	return acc * ((f.auroraLow.w > 0.5 ? 1.3 : 1.8) * f.auroraHigh.w * vis / float(N));
}

fragment float4 im_clouds_fs(ImSkyOut in [[stage_in]], constant ImFrame &f [[buffer(0)]], constant float4x4 &rot [[buffer(1)]],
	texture2d<float> clouds [[texture(0)]], texture2d<float> noise [[texture(1)]], sampler rs [[sampler(0)]]) {
	float4 q = rot * float4(in.ndc.x, -in.ndc.y, 0.5, 1.0);
	float3 d = normalize(q.xyz / q.w);
	float dither = fract(52.9829189 * fract(dot(in.pos.xy, float2(0.06711056, 0.00583715))));
	float3 aurora = f.aurora.x > 0.0 ? imAurora(d, f, noise, rs, dither) : float3(0.0);
	float4 c = f.cloudPos.w > 0.5 ? imClouds(d, f, clouds, rs, dither) : float4(0.0);
	c.rgb += aurora * 0.1;
	// premultiplied: the clouds over what's there, the aurora added behind them
	float3 col = pow(imPresent(c.rgb, f), 1.0 / 2.2) * c.a + pow(imPresent(aurora, f), 1.0 / 2.2) * (1.0 - c.a);
	if (c.a <= 0.0 && max(max(aurora.r, aurora.g), aurora.b) <= 0.0) discard_fragment();
	return float4(col, c.a);
}
