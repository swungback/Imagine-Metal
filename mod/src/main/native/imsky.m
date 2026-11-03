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
	id<MTLRenderPipelineState> pipeline[2];  // 0 the sky (replaces every pixel), 1 clouds and aurora (blended over)
	MTLPixelFormat format[2];
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
int im_sky_draw(Enc *enc, ImSky *s, id<MTLTexture> target, const void *frame, int frameBytes, const float *rot, int mode, char *err, int errCap) {
	if (!s || !target || mode < 0 || mode > 1) return 0;
	if (mode == 1 && (!s->clouds || !s->noise)) return 0;
	if (!s->pipeline[mode] || s->format[mode] != target.pixelFormat) {
		@autoreleasepool {
			[s->pipeline[mode] release];
			s->pipeline[mode] = nil;
			MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
			pd.label = mode == 0 ? @"Imagine Metal sky" : @"Imagine Metal clouds";
			pd.vertexFunction = [[s->lib newFunctionWithName:@"im_sky_vs"] autorelease];
			pd.fragmentFunction = [[s->lib newFunctionWithName:mode == 0 ? @"im_sky_fs" : @"im_clouds_fs"] autorelease];
			pd.colorAttachments[0].pixelFormat = target.pixelFormat;
			if (mode == 1) {
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
		id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
		r.label = @"Imagine Metal sky";
		[r setViewport:(MTLViewport) {0, 0, (double) target.width, (double) target.height, 0, 1}];
		[r setRenderPipelineState:s->pipeline[mode]];
		if (mode == 1) {
			[r setFragmentTexture:s->clouds atIndex:0];
			[r setFragmentTexture:s->noise atIndex:1];
			[r setFragmentSamplerState:s->repeat atIndex:0];
		}
		[r setCullMode:MTLCullModeNone];
		[r setFragmentBytes:frame length:(NSUInteger) frameBytes atIndex:0];
		[r setFragmentBytes:rot length:16 * sizeof(float) atIndex:1];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
	}
	return 0;
}
