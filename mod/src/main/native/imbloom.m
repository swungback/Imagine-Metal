// Imagine Metal: bloom (imaginemetal.Bloom on the Java side; the shaders are bloom.metal).
//
// Once per frame after the level's passes, on the frame's command buffer: the main image's bright parts at half size, a chain
// of smaller copies (RGBA16Float, one texture with a mip per level, each level drawn through its own view), blurred back up
// the chain, and added to the main image. Every step is a small render encoder of its own with one full-target triangle.
// Manual retain/release (the library builds with -fno-objc-arc).
#import "mcmetal.h"
#include <string.h>

#define IM_BLOOM_MAX 7

typedef struct {
	float texel[4];
	float mix[4];
	float grade[4];
	float shaft[4];       // xy the sun on screen (uv), z strength (0: no light shafts), w 1 / aspect
	float shaftColor[4];  // rgb the light's colour (linear), w ambient occlusion strength (0 off)
	float clip[16];       // this frame's camera-relative world -> clip (the ambient occlusion)
	float clipInv[16];
	float prevClip[16];   // the last frame's transform, from this frame's camera-relative world (the shafts' history)
	float taa[4];         // TAA: xy the jitter in uv, z 1 = the history is usable
	float taaPrev[4];     // TAA: xy the last frame's jitter in uv
} ImBloomParams;

id<MTLTexture> mc_im_ssr_depth(void);  // mcown.m: this frame's depth copy (Reflections), nil before the first
id<MTLTexture> mc_im_ssr_color(void);    // mcown.m: this frame's picture with its blurred mips (Reimagined's bloom fog reads it)
id<MTLSamplerState> mc_im_ssr_sampler(void);
id<MTLTexture> mc_im_shadow_map(void);  // mcown.m: the sun's shadow maps (two cascades), nil before the first
id<MTLSamplerState> mc_im_shadow_cmp(void);
const void *mc_im_frame_bytes(int *length);  // mcown.m: this frame's ImFrame (lighting.metal)

typedef struct {
	id<MTLDevice> device;
	id<MTLLibrary> lib;
	id<MTLRenderPipelineState> prefilter, down, up, composite, resolve, shafts;
	id<MTLTexture> shaftTex;  // the light shafts at half size (r the light, g the distance it was traced to)
	id<MTLTexture> shaftHist[2];  // their history, ping-ponged: this frame's result blended over the last
	int shaftIdx, shaftValid;
	float shaftMode;          // the last frame's mode (shaft[0] < -1.5: the Nether's storm): a change drops the history
	float shaft[8];           // im_bloom_shafts: sun uv, strength, colour
	float taaJitter[3];       // im_bloom_taa: jitter (uv), 1 = on
	float taaPrevJ[2];        // the last frame's
	id<MTLRenderPipelineState> taa;
	MTLPixelFormat taaFormat;
	id<MTLTexture> taaCur, taaHist[2];
	int taaIdx, taaValid;
	float fog[3];             // im_bloom_fog: Reimagined's bloom fog strength, 1 = eyes under water, the rainbow's strength
	id<MTLRenderPipelineState> ao;
	id<MTLTexture> aoTex;     // the ambient occlusion at half size
	float aoStrength, aoClip[48];
	MTLPixelFormat compositeFormat, resolveFormat;
	id<MTLTexture> chain;
	id<MTLTexture> views[IM_BLOOM_MAX];
	int levels, width, height;  // of the main image the chain was made for
	id<MTLSamplerState> linear;
} ImBloom;

static void imbErr(NSError *e, const char *what, char *err, int cap) {
	if (!err || cap <= 0) return;
	snprintf(err, (size_t) cap, "%s: %s", what, e ? [[e localizedDescription] UTF8String] : "unknown error");
}

static id<MTLRenderPipelineState> imbPipeline(ImBloom *b, NSString *fragment, MTLPixelFormat format, BOOL additive, char *err, int cap) {
	@autoreleasepool {
		id<MTLFunction> vs = [[b->lib newFunctionWithName:@"im_bloom_vs"] autorelease];
		id<MTLFunction> fs = [[b->lib newFunctionWithName:fragment] autorelease];
		if (!vs || !fs) {
			snprintf(err, (size_t) cap, "bloom: %s missing", [fragment UTF8String]);
			return nil;
		}
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.label = [@"Imagine Metal " stringByAppendingString:fragment];
		pd.vertexFunction = vs;
		pd.fragmentFunction = fs;
		pd.colorAttachments[0].pixelFormat = format;
		if (additive) {
			pd.colorAttachments[0].blendingEnabled = YES;
			pd.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOne;
			pd.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOne;
			pd.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorZero;
			pd.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;
		}
		NSError *e = nil;
		id<MTLRenderPipelineState> ps = [b->device newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!ps) imbErr(e, [fragment UTF8String], err, cap);
		return ps;
	}
}

// The bloom state with its shaders compiled from source (UTF-8). NULL on failure (err filled).
ImBloom *im_bloom_new(Ctx *ctx, const char *source, char *err, int errCap) {
	@autoreleasepool {
		ImBloom *b = calloc(1, sizeof(ImBloom));
		b->device = ctx->device;
		NSError *e = nil;
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		b->lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!b->lib) {
			imbErr(e, "bloom shaders", err, errCap);
			free(b);
			return NULL;
		}
		b->prefilter = imbPipeline(b, @"im_bloom_prefilter", MTLPixelFormatRGBA16Float, NO, err, errCap);
		b->down = b->prefilter ? imbPipeline(b, @"im_bloom_down", MTLPixelFormatRGBA16Float, NO, err, errCap) : nil;
		b->up = b->down ? imbPipeline(b, @"im_bloom_up", MTLPixelFormatRGBA16Float, YES, err, errCap) : nil;
		if (!b->up) {
			[b->prefilter release];
			[b->down release];
			[b->lib release];
			free(b);
			return NULL;
		}
		MTLSamplerDescriptor *sd = [[MTLSamplerDescriptor new] autorelease];
		sd.minFilter = MTLSamplerMinMagFilterLinear;
		sd.magFilter = MTLSamplerMinMagFilterLinear;
		sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
		b->linear = [ctx->device newSamplerStateWithDescriptor:sd];
		return b;
	}
}

static void imbChain(ImBloom *b, int width, int height) {
	if (b->chain && b->width == width && b->height == height) return;
	for (int i = 0; i < IM_BLOOM_MAX; i++) {
		[b->views[i] release];
		b->views[i] = nil;
	}
	[b->chain release];
	b->chain = nil;
	b->width = width;
	b->height = height;
	int w = width / 2 > 1 ? width / 2 : 1, h = height / 2 > 1 ? height / 2 : 1;
	int levels = 1;
	while (levels < IM_BLOOM_MAX && (w >> levels) >= 8 && (h >> levels) >= 8) levels++;
	@autoreleasepool {
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:(NSUInteger) w
			height:(NSUInteger) h mipmapped:YES];
		td.mipmapLevelCount = (NSUInteger) levels;
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead | MTLTextureUsagePixelFormatView;
		b->chain = [b->device newTextureWithDescriptor:td];
		b->chain.label = @"Imagine Metal bloom";
		for (int i = 0; i < levels; i++) {
			b->views[i] = [b->chain newTextureViewWithPixelFormat:MTLPixelFormatRGBA16Float textureType:MTLTextureType2D
				levels:NSMakeRange((NSUInteger) i, 1) slices:NSMakeRange(0, 1)];
		}
	}
	b->levels = levels;
}

// This frame's light shafts (imaginemetal.Bloom): the sun's place on screen (uv; may be off it), strength (0 off), its light's colour.
void im_bloom_shafts(ImBloom *b, float sx, float sy, float strength, float r, float g, float bl, float reach) {
	if (!b) return;
	b->shaft[6] = reach;
	b->shaft[0] = sx;
	b->shaft[1] = sy;
	b->shaft[2] = strength;
	b->shaft[3] = r;
	b->shaft[4] = g;
	b->shaft[5] = bl;
}

// This frame's TAA (imaginemetal.Bloom): the terrain's jitter as a uv offset, and whether it's on.
void im_bloom_taa(ImBloom *b, float jx, float jy, float on) {
	if (!b) return;
	b->taaJitter[0] = jx;
	b->taaJitter[1] = jy;
	b->taaJitter[2] = on;
}

static id<MTLRenderPipelineState> imbTaaPipeline(ImBloom *b, MTLPixelFormat format, char *err, int cap) {
	@autoreleasepool {
		id<MTLFunction> vs = [[b->lib newFunctionWithName:@"im_bloom_vs"] autorelease];
		id<MTLFunction> fs = [[b->lib newFunctionWithName:@"im_bloom_taa"] autorelease];
		if (!vs || !fs) {
			snprintf(err, (size_t) cap, "taa: missing");
			return nil;
		}
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.label = @"Imagine Metal TAA";
		pd.vertexFunction = vs;
		pd.fragmentFunction = fs;
		pd.colorAttachments[0].pixelFormat = format;
		pd.colorAttachments[1].pixelFormat = format;
		NSError *e = nil;
		id<MTLRenderPipelineState> ps = [b->device newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!ps) imbErr(e, "taa", err, cap);
		return ps;
	}
}

static id<MTLTexture> imbTex(ImBloom *b, MTLPixelFormat format, NSUInteger w, NSUInteger h, NSString *label) {
	MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
	td.storageMode = MTLStorageModePrivate;
	td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
	id<MTLTexture> t = [b->device newTextureWithDescriptor:td];
	t.label = label;
	return t;
}

// This frame's bloom fog (imaginemetal.Bloom): how much distant things dissolve into a soft glow (rain, night, caves, water).
void im_bloom_fog(ImBloom *b, float strength, float water, float rainbow) {
	if (!b) return;
	b->fog[0] = strength;
	b->fog[1] = water;
	b->fog[2] = rainbow;
}

// This frame's ambient occlusion (imaginemetal.Bloom): strength (0 off) and the terrain's transform and its inverse (32 floats).
void im_bloom_ao(ImBloom *b, float strength, const float *clips) {
	if (!b) return;
	b->aoStrength = strength;
	if (clips) memcpy(b->aoClip, clips, sizeof b->aoClip);
}

static void imbPass3(id<MTLCommandBuffer> cmd, id<MTLTexture> target, MTLLoadAction load, id<MTLRenderPipelineState> ps,
	id<MTLTexture> src, id<MTLSamplerState> s, const ImBloomParams *p, NSString *label, id<MTLTexture> second, id<MTLTexture> third,
	id<MTLTexture> fourth, id<MTLTexture> fifth, id<MTLTexture> sixth, id<MTLSamplerState> s2, const void *frame, int frameLen) {
	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = target;
	rp.colorAttachments[0].loadAction = load;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
	r.label = label;
	[r setViewport:(MTLViewport) {0, 0, (double) target.width, (double) target.height, 0, 1}];
	[r setRenderPipelineState:ps];
	[r setCullMode:MTLCullModeNone];
	[r setFragmentTexture:src atIndex:0];
	if (second) [r setFragmentTexture:second atIndex:1];
	if (third) [r setFragmentTexture:third atIndex:2];
	if (fourth) [r setFragmentTexture:fourth atIndex:3];
	if (fifth) [r setFragmentTexture:fifth atIndex:4];
	if (sixth) [r setFragmentTexture:sixth atIndex:5];
	[r setFragmentSamplerState:(s2 ? s2 : s) atIndex:1];
	[r setFragmentSamplerState:s atIndex:0];
	[r setFragmentBytes:p length:sizeof *p atIndex:0];
	if (frame && frameLen > 0) [r setFragmentBytes:frame length:(NSUInteger) frameLen atIndex:1];
	[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[r endEncoding];
}

static void imbPass(id<MTLCommandBuffer> cmd, id<MTLTexture> target, MTLLoadAction load, id<MTLRenderPipelineState> ps,
	id<MTLTexture> src, id<MTLSamplerState> s, const ImBloomParams *p, NSString *label, id<MTLTexture> second) {
	imbPass3(cmd, target, load, ps, src, s, p, label, second, nil, nil, nil, nil, nil, NULL, 0);
}

static int imbCheck(id<MTLTexture> t, const char *what, char *err, int errCap) {
	if (!(t.usage & MTLTextureUsageShaderRead) || t.textureType != MTLTextureType2D || t.sampleCount != 1) {
		snprintf(err, (size_t) errCap, "the %s can't be read by shaders (usage %lu, type %lu)", what, (unsigned long) t.usage, (unsigned long) t.textureType);
		return 1;
	}
	return 0;
}

// This frame's bloom and HDR resolve. src: the picture as drawn; dst: the game's main image. src == dst: the glow is added in
// place (no HDR image). Otherwise src is the HDR image: the glow is added to it and the result mapped into dst, which is written
// whole (strength 0: the mapping alone, no glow). 0 = done, 1 = failed (err filled).
int im_bloom_render(Enc *enc, ImBloom *b, id<MTLTexture> src, id<MTLTexture> dst, float strength, float threshold, float knee, float haze,
	float toe, char *err, int errCap) {
	if (!b || !src || !dst) return 0;
	if (imbCheck(src, "picture", err, errCap)) return 1;
	if (src.width < 16 || src.height < 16) return 0;
	BOOL inPlace = src == dst;
	if (inPlace && strength == 0.0f) return 0;
	if (inPlace && (!b->composite || b->compositeFormat != dst.pixelFormat)) {
		[b->composite release];
		b->composite = imbPipeline(b, @"im_bloom_composite", dst.pixelFormat, NO, err, errCap);
		b->compositeFormat = dst.pixelFormat;
		if (!b->composite) return 1;
	}
	if (!inPlace && (!b->resolve || b->resolveFormat != dst.pixelFormat)) {
		[b->resolve release];
		b->resolve = imbPipeline(b, @"im_bloom_resolve", dst.pixelFormat, NO, err, errCap);
		b->resolveFormat = dst.pixelFormat;
		if (!b->resolve) return 1;
	}
	imbChain(b, (int) src.width, (int) src.height);
	id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
	@autoreleasepool {
		ImBloomParams p = {{1.0f / (float) src.width, 1.0f / (float) src.height, threshold, knee}, {strength, (float) b->levels, haze, 0}, {toe, 0, 0, 0},
			{b->shaft[0], b->shaft[1], 0, b->shaft[6]}, {b->shaft[3], b->shaft[4], b->shaft[5], 0}};
		memcpy(p.clip, b->aoClip, sizeof p.clip);
		memcpy(p.clipInv, b->aoClip + 16, sizeof p.clipInv);
		memcpy(p.prevClip, b->aoClip + 32, sizeof p.prevClip);
		// the light shafts (HDR path, volumetric): along each pixel's view ray, how much of the air the sun reaches (the shadow
		// maps), from the depth Reflections copied this frame
		id<MTLTexture> depth = mc_im_ssr_depth();
		id<MTLTexture> smap = mc_im_shadow_map();
		id<MTLSamplerState> scmp = mc_im_shadow_cmp();
		int frameLen = 0;
		const void *frame = mc_im_frame_bytes(&frameLen);
		id<MTLTexture> shafts = nil;
		if (!(b->shaft[2] > 0.0f)) b->shaftValid = 0;
		// (the Nether's storm needs no shadow maps; the light shafts do)
		BOOL storm = b->shaft[0] < -1.5f;
		if ((b->shaftMode < -1.5f) != storm) b->shaftValid = 0;
		b->shaftMode = b->shaft[0];
		if (!inPlace && b->shaft[2] > 0.0f && depth && (storm || (smap && scmp)) && frame && depth.width == src.width && depth.height == src.height) {
			if (!b->shafts) b->shafts = imbPipeline(b, @"im_bloom_shafts", MTLPixelFormatRG16Float, NO, err, errCap);
			NSUInteger sw = src.width / 2 > 1 ? src.width / 2 : 1, sh = src.height / 2 > 1 ? src.height / 2 : 1;
			if (b->shafts && (!b->shaftHist[0] || b->shaftHist[0].width != sw || b->shaftHist[0].height != sh)) {
				for (int k = 0; k < 2; k++) {
					[b->shaftHist[k] release];
					MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRG16Float width:sw height:sh
						mipmapped:NO];
					td.storageMode = MTLStorageModePrivate;
					td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
					b->shaftHist[k] = [b->device newTextureWithDescriptor:td];
					b->shaftHist[k].label = @"Imagine Metal light shafts";
				}
				b->shaftValid = 0;
			}
			if (b->shafts && b->shaftHist[0] && b->shaftHist[1]) {
				b->shaftIdx ^= 1;
				b->shaftTex = b->shaftHist[b->shaftIdx];
				id<MTLTexture> prev = b->shaftHist[b->shaftIdx ^ 1];
				p.mix[3] = b->shaftValid ? 1.0f : 0.0f;  // (the history is usable)
				MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
				rp.colorAttachments[0].texture = b->shaftTex;
				rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
				rp.colorAttachments[0].storeAction = MTLStoreActionStore;
				id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
				r.label = @"Imagine Metal light shafts";
				[r setViewport:(MTLViewport) {0, 0, (double) b->shaftTex.width, (double) b->shaftTex.height, 0, 1}];
				[r setRenderPipelineState:b->shafts];
				[r setCullMode:MTLCullModeNone];
				[r setFragmentTexture:depth atIndex:1];
				[r setFragmentTexture:prev atIndex:2];
				[r setFragmentTexture:smap atIndex:4];
				[r setFragmentSamplerState:b->linear atIndex:0];
				[r setFragmentSamplerState:scmp atIndex:1];
				[r setFragmentBytes:&p length:sizeof p atIndex:0];
				[r setFragmentBytes:frame length:(NSUInteger) frameLen atIndex:1];
				[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
				[r endEncoding];
				shafts = b->shaftTex;
				p.shaft[2] = b->shaft[2];
				p.mix[3] = 0.0f;
				b->shaftValid = 1;
			}
			err[0] = 0;  // (a failed shafts pipeline only turns the shafts off)
		}
		// the ambient occlusion (HDR path): from the same depth copy, at half size
		id<MTLTexture> aoT = nil;
		if (!inPlace && b->aoStrength > 0.0f && depth && depth.width == src.width && depth.height == src.height) {
			if (!b->ao) b->ao = imbPipeline(b, @"im_bloom_ao", MTLPixelFormatR16Float, NO, err, errCap);
			NSUInteger aw = src.width / 2 > 1 ? src.width / 2 : 1, ah = src.height / 2 > 1 ? src.height / 2 : 1;
			if (b->ao && (!b->aoTex || b->aoTex.width != aw || b->aoTex.height != ah)) {
				[b->aoTex release];
				MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR16Float width:aw height:ah
					mipmapped:NO];
				td.storageMode = MTLStorageModePrivate;
				td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
				b->aoTex = [b->device newTextureWithDescriptor:td];
				b->aoTex.label = @"Imagine Metal ambient occlusion";
			}
			if (b->ao && b->aoTex) {
				imbPass(cmd, b->aoTex, MTLLoadActionDontCare, b->ao, src, b->linear, &p, @"Imagine Metal ambient occlusion", depth);
				aoT = b->aoTex;
				p.shaftColor[3] = b->aoStrength;
			}
			err[0] = 0;
		}
		if (strength != 0.0f) {
			imbPass(cmd, b->views[0], MTLLoadActionDontCare, b->prefilter, src, b->linear, &p, @"Imagine Metal bloom prefilter", nil);
			for (int i = 1; i < b->levels; i++) {
				id<MTLTexture> s = b->views[i - 1];
				p.texel[0] = 1.0f / (float) s.width;
				p.texel[1] = 1.0f / (float) s.height;
				imbPass(cmd, b->views[i], MTLLoadActionDontCare, b->down, s, b->linear, &p, @"Imagine Metal bloom down", nil);
			}
			for (int i = b->levels - 2; i >= 0; i--) {
				id<MTLTexture> s = b->views[i + 1];
				p.texel[0] = 1.0f / (float) s.width;
				p.texel[1] = 1.0f / (float) s.height;
				imbPass(cmd, b->views[i], MTLLoadActionLoad, b->up, s, b->linear, &p, @"Imagine Metal bloom up", nil);
			}
		}
		p.texel[0] = 1.0f / (float) b->views[0].width;
		p.texel[1] = 1.0f / (float) b->views[0].height;
		if (inPlace) imbPass(cmd, dst, MTLLoadActionLoad, b->composite, b->views[0], b->linear, &p, @"Imagine Metal bloom composite", nil);
		else {
			// (the bloom fog: the picture's blurred copy and the depth, when Reflections copied them this frame at this size)
			id<MTLTexture> hc = mc_im_ssr_color();
			id<MTLSamplerState> hs = mc_im_ssr_sampler();
			BOOL fogOk = b->fog[0] > 0.0f && hc && hs && depth && hc.width == src.width && hc.height == src.height && hc.mipmapLevelCount > 4
				&& depth.width == src.width && depth.height == src.height;
			BOOL depthOk = depth && depth.width == src.width && depth.height == src.height;
			p.grade[1] = fogOk ? b->fog[0] : 0.0f;
			p.grade[2] = b->fog[1];
			p.grade[3] = depthOk && frame ? b->fog[2] : 0.0f;
			p.texel[2] = depthOk ? 1.0f : 0.0f;  // (the resolve doesn't use the threshold: here, whether the depth is bound)
			// TAA: the resolve into a frame of its own, then blended with the history into the screen
			BOOL taaOn = b->taaJitter[2] > 0.5f && depthOk;
			if (taaOn) {
				if (!b->taa || b->taaFormat != dst.pixelFormat) {
					[b->taa release];
					b->taa = imbTaaPipeline(b, dst.pixelFormat, err, errCap);
					b->taaFormat = dst.pixelFormat;
					b->taaValid = 0;
					if (!b->taa) {
						b->taaJitter[2] = 0.0f;
						err[0] = 0;
						taaOn = NO;
					}
				}
			}
			if (taaOn && (!b->taaCur || b->taaCur.width != dst.width || b->taaCur.height != dst.height || b->taaCur.pixelFormat != dst.pixelFormat)) {
				[b->taaCur release];
				[b->taaHist[0] release];
				[b->taaHist[1] release];
				b->taaCur = imbTex(b, dst.pixelFormat, dst.width, dst.height, @"Imagine Metal TAA frame");
				b->taaHist[0] = imbTex(b, dst.pixelFormat, dst.width, dst.height, @"Imagine Metal TAA history");
				b->taaHist[1] = imbTex(b, dst.pixelFormat, dst.width, dst.height, @"Imagine Metal TAA history");
				b->taaValid = 0;
			}
			if (!taaOn) b->taaValid = 0;
			imbPass3(cmd, taaOn ? b->taaCur : dst, MTLLoadActionDontCare, b->resolve, b->views[0], b->linear, &p, @"Imagine Metal HDR resolve", src,
				shafts ? shafts : b->views[0], aoT ? aoT : b->views[0], fogOk ? hc : b->views[0], depthOk ? depth : nil, fogOk ? hs : nil,
				frame, frameLen);
			if (taaOn) {
				id<MTLTexture> prev = b->taaHist[b->taaIdx], next = b->taaHist[b->taaIdx ^ 1];
				p.taa[0] = b->taaJitter[0];
				p.taa[1] = b->taaJitter[1];
				p.taa[2] = b->taaValid ? 1.0f : 0.0f;
				p.taaPrev[0] = b->taaPrevJ[0];
				p.taaPrev[1] = b->taaPrevJ[1];
				b->taaPrevJ[0] = b->taaJitter[0];
				b->taaPrevJ[1] = b->taaJitter[1];
				MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
				rp.colorAttachments[0].texture = dst;
				rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
				rp.colorAttachments[0].storeAction = MTLStoreActionStore;
				rp.colorAttachments[1].texture = next;
				rp.colorAttachments[1].loadAction = MTLLoadActionDontCare;
				rp.colorAttachments[1].storeAction = MTLStoreActionStore;
				id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
				r.label = @"Imagine Metal TAA";
				[r setViewport:(MTLViewport) {0, 0, (double) dst.width, (double) dst.height, 0, 1}];
				[r setRenderPipelineState:b->taa];
				[r setCullMode:MTLCullModeNone];
				[r setFragmentTexture:b->taaCur atIndex:0];
				[r setFragmentTexture:prev atIndex:1];
				[r setFragmentTexture:depth atIndex:2];
				[r setFragmentSamplerState:b->linear atIndex:0];
				[r setFragmentBytes:&p length:sizeof p atIndex:0];
				[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
				[r endEncoding];
				b->taaIdx ^= 1;
				b->taaValid = 1;
			}
		}
	}
	return 0;
}
