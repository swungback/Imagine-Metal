// Imagine Metal: the coloured light grid (imaginemetal.LightVolume on the Java side; the kernel is light.metal).
//
// seeds: RGBA8 3D texture the Java side fills (the blocks' emission and opacity, by section); light[2]: RGBA16Float 3D textures,
// the spread light, ping-ponged by im_light_step once a frame on the frame's command buffer before the level's passes. The latest
// one is bound for the terrain's fragment stage at texture 21 with a linear sampler at 12 (mcown.m).
// Manual retain/release (the library builds with -fno-objc-arc).
#import "mcmetal.h"
#include <string.h>

void mc_im_light_bind(id<MTLTexture> volume, id<MTLSamplerState> sampler, id<MTLTexture> seeds);  // mcown.m

#define IM_LIGHT_PENDING 160
typedef struct {
	id<MTLDevice> device;
	id<MTLComputePipelineState> step;
	id<MTLTexture> seeds, light[2];
	id<MTLSamplerState> linear;
	int sx, sy, sz, cur;
	// uploads waiting for the next step: copied into their own buffers and blitted into seeds on the frame's command buffer, so
	// a frame still in flight on the GPU never reads seeds written for a later frame (that showed lights a section off for a
	// frame, flickering, while moving)
	id<MTLBuffer> pendBuf[IM_LIGHT_PENDING];
	MTLRegion pendRegion[IM_LIGHT_PENDING];
	int pending;
} ImLight;

ImLight *im_light_new(Ctx *ctx, const char *source, int sx, int sy, int sz, char *err, int errCap) {
	@autoreleasepool {
		NSError *e = nil;
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		id<MTLLibrary> lib = [[ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e] autorelease];
		if (!lib) {
			snprintf(err, (size_t) errCap, "light shaders: %s", e ? [[e localizedDescription] UTF8String] : "unknown error");
			return NULL;
		}
		id<MTLFunction> fn = [[lib newFunctionWithName:@"im_light_step"] autorelease];
		id<MTLComputePipelineState> step = fn ? [ctx->device newComputePipelineStateWithFunction:fn error:&e] : nil;
		if (!step) {
			snprintf(err, (size_t) errCap, "light step: %s", e ? [[e localizedDescription] UTF8String] : "missing");
			return NULL;
		}
		ImLight *l = calloc(1, sizeof(ImLight));
		l->device = ctx->device;
		l->step = step;
		l->sx = sx;
		l->sy = sy;
		l->sz = sz;
		MTLTextureDescriptor *td = [[MTLTextureDescriptor new] autorelease];
		td.textureType = MTLTextureType3D;
		td.width = (NSUInteger) sx;
		td.height = (NSUInteger) sy;
		td.depth = (NSUInteger) sz;
		td.pixelFormat = MTLPixelFormatRGBA8Unorm;
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageShaderRead;
		l->seeds = [ctx->device newTextureWithDescriptor:td];
		td.pixelFormat = MTLPixelFormatRGBA16Float;
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
		for (int i = 0; i < 2; i++) l->light[i] = [ctx->device newTextureWithDescriptor:td];
		MTLSamplerDescriptor *sd = [[MTLSamplerDescriptor new] autorelease];
		sd.minFilter = MTLSamplerMinMagFilterLinear;
		sd.magFilter = MTLSamplerMinMagFilterLinear;
		sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.rAddressMode = MTLSamplerAddressModeClampToEdge;
		l->linear = [ctx->device newSamplerStateWithDescriptor:sd];
		if (!l->seeds || !l->light[0] || !l->light[1] || !l->linear) {
			snprintf(err, (size_t) errCap, "light grid textures could not be created");
			return NULL;
		}
		l->seeds.label = @"Imagine Metal light seeds";
		l->light[0].label = @"Imagine Metal light A";
		l->light[1].label = @"Imagine Metal light B";
		return l;
	}
}

// Queues a box of seed voxels (4 bytes each, x fastest, then y, then z) at (x, y, z), size (w, h, d) for the next step: the bytes
// are copied now, the seeds texture is written on the GPU's timeline just before that step reads it.
void im_light_upload(ImLight *l, int x, int y, int z, int w, int h, int d, const void *bytes) {
	if (!l || w <= 0 || h <= 0 || d <= 0) return;
	// a whole-box upload replaces everything still waiting
	if (x == 0 && y == 0 && z == 0 && w == l->sx && h == l->sy && d == l->sz) {
		for (int i = 0; i < l->pending; i++) [l->pendBuf[i] release];
		l->pending = 0;
	}
	if (l->pending >= IM_LIGHT_PENDING) return;  // (can't happen with the Java side's budget; the next full upload repairs it)
	id<MTLBuffer> b = [l->device newBufferWithBytes:bytes length:(NSUInteger) w * h * d * 4 options:MTLResourceStorageModeShared];
	if (!b) return;
	l->pendBuf[l->pending] = b;
	l->pendRegion[l->pending] = MTLRegionMake3D((NSUInteger) x, (NSUInteger) y, (NSUInteger) z, (NSUInteger) w, (NSUInteger) h, (NSUInteger) d);
	l->pending++;
}

// One spread step (shift: how far the box moved since the last step, in voxels; reset: start from nothing). Binds the result for
// the terrain.
void im_light_step(Enc *enc, ImLight *l, int shiftX, int shiftY, int shiftZ, int reset) {
	if (!l) return;
	id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
	if (l->pending > 0) {
		@autoreleasepool {
			id<MTLBlitCommandEncoder> bl = [cmd blitCommandEncoder];
			bl.label = @"Imagine Metal light seeds";
			for (int i = 0; i < l->pending; i++) {
				MTLRegion r = l->pendRegion[i];
				[bl copyFromBuffer:l->pendBuf[i] sourceOffset:0 sourceBytesPerRow:r.size.width * 4
					sourceBytesPerImage:r.size.width * r.size.height * 4 sourceSize:r.size toTexture:l->seeds destinationSlice:0
					destinationLevel:0 destinationOrigin:r.origin];
				[l->pendBuf[i] release];  // (the command buffer keeps it alive until it has run)
			}
			[bl endEncoding];
		}
		l->pending = 0;
	}
	@autoreleasepool {
		// reset (the first step, a jump): the previous volume is read shifted wholly out of the box, so it counts as empty
		if (reset) shiftX = l->sx * 4;
		id<MTLComputeCommandEncoder> c = [cmd computeCommandEncoder];
		c.label = @"Imagine Metal light step";
		int next = 1 - l->cur;
		[c setComputePipelineState:l->step];
		[c setTexture:l->seeds atIndex:0];
		[c setTexture:l->light[l->cur] atIndex:1];
		[c setTexture:l->light[next] atIndex:2];
		int shift[4] = {shiftX, shiftY, shiftZ, 0};
		[c setBytes:shift length:sizeof shift atIndex:0];
		[c dispatchThreads:MTLSizeMake((NSUInteger) l->sx, (NSUInteger) l->sy, (NSUInteger) l->sz) threadsPerThreadgroup:MTLSizeMake(8, 8, 4)];
		[c endEncoding];
		l->cur = next;
	}
	mc_im_light_bind(l->light[l->cur], l->linear, l->seeds);
}
