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
	id<MTLTexture> under, underDepth;  // (the picture and depth before the translucent pass: refraction and foam under water)
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

void mc_im_under_bind(id<MTLTexture> color, id<MTLTexture> depth);  // mcown.m

// Before the translucent pass (no pass open on the encoder): the opaque picture and its depth, for water's refraction and its foam
// along the shore. Returns 0 (copied or skipped), 1 with err.
int im_under_capture(Enc *enc, ImSsr *s, id<MTLTexture> color, id<MTLTexture> depth, char *err, int errCap) {
	// (a pass still open on the encoder is closed by mc_frame_cmd, as for the history: skipping here left the copy never taken,
	// and the water read an empty texture, black, as what's under it)
	// (color nil: the depth alone, for the mob shadows' trace)
	if (!s || !depth) return 0;
	int reopen = 0;
	@autoreleasepool {
		if (color && (!s->under || s->under.width != color.width || s->under.height != color.height || s->under.pixelFormat != color.pixelFormat)) {
			[s->under release];
			MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:color.pixelFormat width:color.width
				height:color.height mipmapped:NO];
			td.storageMode = MTLStorageModePrivate;
			td.usage = MTLTextureUsageShaderRead;
			s->under = [s->device newTextureWithDescriptor:td];
			s->under.label = @"Imagine Metal under the water";
		}
		if (color && !s->under) {
			snprintf(err, (size_t) errCap, "under-water texture could not be created");
			return 1;
		}
		if (!s->underDepth || s->underDepth.width != depth.width || s->underDepth.height != depth.height || s->underDepth.pixelFormat != depth.pixelFormat) {
			[s->underDepth release];
			MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:depth.pixelFormat width:depth.width
				height:depth.height mipmapped:NO];
			td.storageMode = MTLStorageModePrivate;
			td.usage = MTLTextureUsageShaderRead;
			s->underDepth = [s->device newTextureWithDescriptor:td];
			s->underDepth.label = @"Imagine Metal under the water depth";
		}
		if (!s->underDepth) {
			snprintf(err, (size_t) errCap, "under-water depth could not be created");
			return 1;
		}
		// (a pass open here is the one the translucent terrain draws into: it is reopened after the copy, contents loaded back,
		// and the Java side binds its state again. Left closed, every draw after the copy went to no encoder: the water, the
		// nether portal, the translucent torches and the selection outline drew nothing)
		reopen = mc_render_is_open(enc);
		id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
		id<MTLBlitCommandEncoder> b = [cmd blitCommandEncoder];
		b.label = @"Imagine Metal under the water";
		MTLOrigin o = {0, 0, 0};
		if (color)
			[b copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:o sourceSize:MTLSizeMake(color.width, color.height, 1)
				toTexture:s->under destinationSlice:0 destinationLevel:0 destinationOrigin:o];
		[b copyFromTexture:depth sourceSlice:0 sourceLevel:0 sourceOrigin:o sourceSize:MTLSizeMake(depth.width, depth.height, 1)
			toTexture:s->underDepth destinationSlice:0 destinationLevel:0 destinationOrigin:o];
		[b endEncoding];
		if (reopen) mc_render_reopen(enc);
	}
	mc_im_under_bind(s->under, s->underDepth);
	return reopen ? 2 : 0;
}

void mc_im_pbr_bind(id<MTLTexture> n, id<MTLTexture> s);  // mcown.m

// A PBR resource pack's maps as the block atlas lays them out (PbrAtlas): RGBA8 each, uploaded once per resource reload.
// Returns 1 made, 0 not.
int im_pbr_upload(Ctx *ctx, int w, int h, const void *n, const void *s) {
	if (!ctx || w <= 0 || h <= 0 || !n || !s) return 0;
	@autoreleasepool {
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:(NSUInteger) w
			height:(NSUInteger) h mipmapped:NO];
		td.storageMode = MTLStorageModeShared;
		td.usage = MTLTextureUsageShaderRead;
		id<MTLTexture> tn = [ctx->device newTextureWithDescriptor:td];
		id<MTLTexture> ts = [ctx->device newTextureWithDescriptor:td];
		if (!tn || !ts) return 0;
		MTLRegion r = MTLRegionMake2D(0, 0, (NSUInteger) w, (NSUInteger) h);
		[tn replaceRegion:r mipmapLevel:0 withBytes:n bytesPerRow:(NSUInteger) w * 4];
		[ts replaceRegion:r mipmapLevel:0 withBytes:s bytesPerRow:(NSUInteger) w * 4];
		tn.label = @"Imagine Metal PBR normals";
		ts.label = @"Imagine Metal PBR specular";
		mc_im_pbr_bind(tn, ts);
	}
	return 1;
}
