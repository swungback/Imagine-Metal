// Imagine Metal: the sky pass (imaginemetal.Sky on the Java side; the shaders are lighting.metal + sky.metal).
//
// One full-screen triangle into the level's picture (the HDR image while it draws), loading what's there (the level's clear,
// flushed by the Java side first), on the frame's command buffer, before Minecraft's sky pass draws the sun, moon and stars.
// Manual retain/release (the library builds with -fno-objc-arc).
#import "mcmetal.h"
#include <string.h>

typedef struct {
	id<MTLDevice> device;
	id<MTLLibrary> lib;
	// 0 the sky (replaces every pixel), 1 clouds and aurora (blended over), 2 the End's sky (where the depth is clear),
	// 3 clouds and aurora after the opaque world (Build C1: blended over, behind what the depth copy holds)
	id<MTLRenderPipelineState> pipeline[4];
	MTLPixelFormat format[4];
	id<MTLTexture> clouds, noise;
	id<MTLSamplerState> repeat;
} ImSky;

ImSky *im_sky_new(Ctx *ctx, const char *source, char *err, int errCap) {
	@autoreleasepool {
		NSError *e = nil;
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) {
			snprintf(err, (size_t) errCap, "sky shaders: %s", e ? [[e localizedDescription] UTF8String] : "unknown error");
			return NULL;
		}
		ImSky *s = calloc(1, sizeof(ImSky));
		s->device = ctx->device;
		s->lib = lib;
		return s;
	}
}

// The cloud map (w x h) and the aurora's noise (nw x nh), RGBA8 each, once.
int im_sky_textures(ImSky *s, const void *clouds, int w, int h, const void *noise, int nw, int nh) {
	if (!s) return 1;
	@autoreleasepool {
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:(NSUInteger) w
			height:(NSUInteger) h mipmapped:NO];
		td.storageMode = MTLStorageModeShared;
		td.usage = MTLTextureUsageShaderRead;
		[s->clouds release];
		s->clouds = [s->device newTextureWithDescriptor:td];
		[s->clouds replaceRegion:MTLRegionMake2D(0, 0, (NSUInteger) w, (NSUInteger) h) mipmapLevel:0 withBytes:clouds bytesPerRow:(NSUInteger) w * 4];
		td.width = (NSUInteger) nw;
		td.height = (NSUInteger) nh;
		[s->noise release];
		s->noise = [s->device newTextureWithDescriptor:td];
		[s->noise replaceRegion:MTLRegionMake2D(0, 0, (NSUInteger) nw, (NSUInteger) nh) mipmapLevel:0 withBytes:noise bytesPerRow:(NSUInteger) nw * 4];
		if (!s->repeat) {
			MTLSamplerDescriptor *sd = [[MTLSamplerDescriptor new] autorelease];
			sd.minFilter = MTLSamplerMinMagFilterLinear;
			sd.magFilter = MTLSamplerMinMagFilterLinear;
			sd.sAddressMode = MTLSamplerAddressModeRepeat;
			sd.tAddressMode = MTLSamplerAddressModeRepeat;
			s->repeat = [s->device newSamplerStateWithDescriptor:sd];
		}
		return s->clouds && s->noise && s->repeat ? 0 : 1;
	}
}

// Draws the sky into target. frame: ImFrame (frameBytes); rot: 16 floats, clip -> view direction (column-major).
// mode 0: the sky (every pixel replaced); mode 1: the clouds and the aurora, blended over what's there (premultiplied alpha).
static int im_sky_draw_any(Enc *enc, ImSky *s, id<MTLTexture> target, id<MTLTexture> depth, const void *frame, int frameBytes, const float *rot, int mode,
	const float *late, char *err, int errCap);

int im_sky_draw(Enc *enc, ImSky *s, id<MTLTexture> target, const void *frame, int frameBytes, const float *rot, int mode, char *err, int errCap) {
	if (mode < 0 || mode > 1) return 0;
	return im_sky_draw_any(enc, s, target, nil, frame, frameBytes, rot, mode, NULL, err, errCap);
}

// (Build C1) The clouds and the aurora after the opaque world (mode 3), from imssr.m im_under_capture: depth the copy just taken
// there, late 20 floats (sky.metal ImCloudLate: this frame's inverse clip, the target's size). No pass may be open on the encoder.
// Returns 0 drawn, 1 with err.
int im_sky_draw_late(Enc *enc, void *sky, id<MTLTexture> target, id<MTLTexture> depth, const void *frame, int frameBytes, const float *rot, const float *late,
	char *err, int errCap) {
	ImSky *s = (ImSky *) sky;
	if (!s || !target || !depth || !late || !rot || !s->clouds || !s->noise) {
		snprintf(err, (size_t) errCap, "clouds after the land: nothing to draw them with");
		return 1;
	}
	return im_sky_draw_any(enc, s, target, depth, frame, frameBytes, rot, 3, late, err, errCap);
}

id<MTLTexture> mc_im_ssr_depth(void);  // mcown.m: this frame's depth copy (Reflections), nil before the first

// The End's sky after the level has drawn: over the pixels whose depth (this frame's copy, Reflections) is still clear (mode 2).
// Returns 0 drawn, 1 with err, 2 no depth of this size (the caller draws it the old way).
int im_sky_draw_end(Enc *enc, ImSky *s, id<MTLTexture> target, const void *frame, int frameBytes, const float *rot, char *err, int errCap) {
	id<MTLTexture> depth = mc_im_ssr_depth();
	if (!depth || !target || depth.width != target.width || depth.height != target.height) return 2;
	return im_sky_draw_any(enc, s, target, depth, frame, frameBytes, rot, 2, NULL, err, errCap);
}

static int im_sky_draw_any(Enc *enc, ImSky *s, id<MTLTexture> target, id<MTLTexture> depth, const void *frame, int frameBytes, const float *rot, int mode,
	const float *late, char *err, int errCap) {
	if (!s || !target) return 0;
	if ((mode == 1 || mode == 3) && (!s->clouds || !s->noise)) return 0;
	if (!s->pipeline[mode] || s->format[mode] != target.pixelFormat) {
		@autoreleasepool {
			[s->pipeline[mode] release];
			s->pipeline[mode] = nil;
			MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
			pd.label = mode == 1 || mode == 3 ? @"Imagine Metal clouds" : @"Imagine Metal sky";
			pd.vertexFunction = [[s->lib newFunctionWithName:@"im_sky_vs"] autorelease];
			pd.fragmentFunction = [[s->lib newFunctionWithName:mode == 0 ? @"im_sky_fs" : mode == 2 ? @"im_sky_end_fs" : mode == 3 ? @"im_clouds_late_fs"
				: @"im_clouds_fs"] autorelease];
			pd.colorAttachments[0].pixelFormat = target.pixelFormat;
			if (mode == 1 || mode == 3) {
				pd.colorAttachments[0].blendingEnabled = YES;
				pd.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOne;
				pd.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
				pd.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorZero;
				pd.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;
			}
			NSError *e = nil;
			s->pipeline[mode] = [s->device newRenderPipelineStateWithDescriptor:pd error:&e];
			s->format[mode] = target.pixelFormat;
			if (!s->pipeline[mode]) {
				snprintf(err, (size_t) errCap, "sky pipeline: %s", e ? [[e localizedDescription] UTF8String] : "unknown error");
				return 1;
			}
		}
	}
	id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
	@autoreleasepool {
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = target;
		rp.colorAttachments[0].loadAction = mode == 0 ? MTLLoadActionDontCare : MTLLoadActionLoad;  // (the sky writes every pixel)
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		imt_rp(rp, mode == 1 || mode == 3 ? IMT_CLOUDS : IMT_SKY);
		id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
		r.label = @"Imagine Metal sky";
		[r setViewport:(MTLViewport) {0, 0, (double) target.width, (double) target.height, 0, 1}];
		[r setRenderPipelineState:s->pipeline[mode]];
		if (mode == 1 || mode == 3) {
			[r setFragmentTexture:s->clouds atIndex:0];
			[r setFragmentTexture:s->noise atIndex:1];
			[r setFragmentSamplerState:s->repeat atIndex:0];
		}
		if (mode == 2 || mode == 3) [r setFragmentTexture:depth atIndex:2];
		if (mode == 3) [r setFragmentBytes:late length:20 * sizeof(float) atIndex:2];
		[r setCullMode:MTLCullModeNone];
		[r setFragmentBytes:frame length:(NSUInteger) frameBytes atIndex:0];
		[r setFragmentBytes:rot length:16 * sizeof(float) atIndex:1];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
	}
	return 0;
}
