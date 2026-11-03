// Imagine Metal: the reflections' history (imaginemetal.Reflections on the Java side; traced in lighting.metal imReflect).
//
// Once a frame, after the level has drawn (before the bloom), its picture and depth are copied here: the picture with a chain of
// smaller copies (mipmaps), so rough surfaces can take a blurred reflection for the price of one read. The next frame's terrain
// traces its reflections through these (reprojected to where the camera was), so no extra full-screen pass is needed.
// Manual retain/release (the library builds with -fno-objc-arc).
#import "mcmetal.h"
#include <string.h>

void mc_im_ssr_bind(id<MTLTexture> color, id<MTLTexture> depth, id<MTLSamplerState> sampler);  // mcown.m

typedef struct {
	id<MTLDevice> device;
	id<MTLTexture> color, depth;
	id<MTLSamplerState> linear;
} ImSsr;

ImSsr *im_ssr_new(Ctx *ctx) {
	@autoreleasepool {
		ImSsr *s = calloc(1, sizeof(ImSsr));
		s->device = ctx->device;
		MTLSamplerDescriptor *sd = [[MTLSamplerDescriptor new] autorelease];
		sd.minFilter = MTLSamplerMinMagFilterLinear;
		sd.magFilter = MTLSamplerMinMagFilterLinear;
		sd.mipFilter = MTLSamplerMipFilterLinear;
		sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
		s->linear = [ctx->device newSamplerStateWithDescriptor:sd];
		return s;
	}
}

static int imssrLevels(NSUInteger w, NSUInteger h) {
	int n = 1;
	while ((w > 8 || h > 8) && n < 7) {
		w = (w + 1) / 2;
		h = (h + 1) / 2;
		n++;
	}
	return n;
}

// Copies color (the level's picture) and depth (its depth) into the history and binds it for the terrain. Returns 0, or 1 with err.
int im_ssr_capture(Enc *enc, ImSsr *s, id<MTLTexture> color, id<MTLTexture> depth, char *err, int errCap) {
	if (!s || !color || !depth) return 0;
	@autoreleasepool {
		if (!s->color || s->color.width != color.width || s->color.height != color.height || s->color.pixelFormat != color.pixelFormat) {
			[s->color release];
			MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:color.pixelFormat width:color.width
				height:color.height mipmapped:NO];
			td.mipmapLevelCount = (NSUInteger) imssrLevels(color.width, color.height);
			td.storageMode = MTLStorageModePrivate;
			td.usage = MTLTextureUsageShaderRead;
			s->color = [s->device newTextureWithDescriptor:td];
			s->color.label = @"Imagine Metal reflection history";
		}
		if (!s->depth || s->depth.width != depth.width || s->depth.height != depth.height || s->depth.pixelFormat != depth.pixelFormat) {
			[s->depth release];
			MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:depth.pixelFormat width:depth.width
				height:depth.height mipmapped:NO];
			td.storageMode = MTLStorageModePrivate;
			td.usage = MTLTextureUsageShaderRead;
			s->depth = [s->device newTextureWithDescriptor:td];
			s->depth.label = @"Imagine Metal reflection depth";
		}
		if (!s->color || !s->depth) {
			snprintf(err, (size_t) errCap, "reflection history textures could not be created");
			return 1;
		}
		id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
		id<MTLBlitCommandEncoder> b = [cmd blitCommandEncoder];
		b.label = @"Imagine Metal reflection history";
		MTLOrigin o = {0, 0, 0};
		[b copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:o sourceSize:MTLSizeMake(color.width, color.height, 1)
			toTexture:s->color destinationSlice:0 destinationLevel:0 destinationOrigin:o];
		[b copyFromTexture:depth sourceSlice:0 sourceLevel:0 sourceOrigin:o sourceSize:MTLSizeMake(depth.width, depth.height, 1)
			toTexture:s->depth destinationSlice:0 destinationLevel:0 destinationOrigin:o];
		if (s->color.mipmapLevelCount > 1) [b generateMipmapsForTexture:s->color];
		[b endEncoding];
	}
	mc_im_ssr_bind(s->color, s->depth, s->linear);
	return 0;
}
