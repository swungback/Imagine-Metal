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
	id<MTLRenderPipelineState> pipeline;
	MTLPixelFormat format;
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

// Draws the sky into target. frame: ImFrame (frameBytes); rot: 16 floats, clip -> view direction (column-major).
int im_sky_draw(Enc *enc, ImSky *s, id<MTLTexture> target, const void *frame, int frameBytes, const float *rot, char *err, int errCap) {
	if (!s || !target) return 0;
	if (!s->pipeline || s->format != target.pixelFormat) {
		@autoreleasepool {
			[s->pipeline release];
			s->pipeline = nil;
			MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
			pd.label = @"Imagine Metal sky";
			pd.vertexFunction = [[s->lib newFunctionWithName:@"im_sky_vs"] autorelease];
			pd.fragmentFunction = [[s->lib newFunctionWithName:@"im_sky_fs"] autorelease];
			pd.colorAttachments[0].pixelFormat = target.pixelFormat;
			NSError *e = nil;
			s->pipeline = [s->device newRenderPipelineStateWithDescriptor:pd error:&e];
			s->format = target.pixelFormat;
			if (!s->pipeline) {
				snprintf(err, (size_t) errCap, "sky pipeline: %s", e ? [[e localizedDescription] UTF8String] : "unknown error");
				return 1;
			}
		}
	}
	id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
	@autoreleasepool {
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = target;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;  // (every pixel is written)
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
		r.label = @"Imagine Metal sky";
		[r setViewport:(MTLViewport) {0, 0, (double) target.width, (double) target.height, 0, 1}];
		[r setRenderPipelineState:s->pipeline];
		[r setCullMode:MTLCullModeNone];
		[r setFragmentBytes:frame length:(NSUInteger) frameBytes atIndex:0];
		[r setFragmentBytes:rot length:16 * sizeof(float) atIndex:1];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
	}
	return 0;
}
