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
		id<MTLBlitCommandEncoder> b = [imt_blit(cmd, IMT_CAPTURE) autorelease];
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
// The air pass (Build B1; air.metal im_air_fs in the terrain library): the haze and the fade into the sky at the edge of the render
// distance laid over the opaque world once per pixel, from the depth copy just taken, instead of in every terrain fragment.
// Its pipeline per target format, made on first use; nil (said once) if it can't be.
static id<MTLRenderPipelineState> imAirPso;
static MTLPixelFormat imAirFormat;
static int imAirFailed;
static id<MTLRenderPipelineState> imAirPipeline(ImSsr *s, id<MTLLibrary> lib, MTLPixelFormat format) {
	if (imAirPso && imAirFormat == format) return imAirPso;
	if (imAirFailed || !lib) return nil;
	id<MTLFunction> vs = [[lib newFunctionWithName:@"im_air_vs"] autorelease], fs = [[lib newFunctionWithName:@"im_air_fs"] autorelease];
	if (!vs || !fs) {
		imAirFailed = 1;
		fprintf(stderr, "[Imagine Metal] air pass off: im_air_vs / im_air_fs missing from the terrain library\n");
		return nil;
	}
	MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
	pd.label = @"Imagine Metal air";
	pd.vertexFunction = vs;
	pd.fragmentFunction = fs;
	pd.colorAttachments[0].pixelFormat = format;
	NSError *e = nil;
	id<MTLRenderPipelineState> pso = [s->device newRenderPipelineStateWithDescriptor:pd error:&e];
	if (!pso) {
		imAirFailed = 1;
		fprintf(stderr, "[Imagine Metal] air pass off: %s\n", e ? e.description.UTF8String : "no pipeline");
		return nil;
	}
	[imAirPso release];
	imAirPso = pso;
	imAirFormat = format;
	return pso;
}

// (Build C1) The clouds after the opaque world: set by the Java side (imaginemetal.Reflections.captureUnder) right before
// im_under_capture, which draws them once (after the air pass, before the colour copy) and forgets them. sky: imsky.m's state;
// target: the level's picture; rot: 16 floats, clip -> view direction; clipInv: 16 floats, this frame's inverse terrain transform.
int im_sky_draw_late(Enc *enc, void *sky, id<MTLTexture> target, id<MTLTexture> depth, const void *frame, int frameBytes, const float *rot, const float *late,
	char *err, int errCap);  // imsky.m
static void *imCloudSky;
static id<MTLTexture> imCloudTarget;
static float imCloudRot[16], imCloudLate[20];
static int imCloudSet, imCloudFailed;

void im_under_clouds(ImSsr *s, void *sky, id<MTLTexture> target, const float *rot, const float *clipInv) {
	imCloudSet = 0;
	if (!s || !sky || !target || !rot || !clipInv || imCloudFailed) return;
	imCloudSky = sky;
	imCloudTarget = target;  // (not retained: used by the im_under_capture call that follows, or dropped)
	memcpy(imCloudRot, rot, sizeof imCloudRot);
	memcpy(imCloudLate, clipInv, 16 * sizeof(float));
	imCloudSet = 1;
}

// air: this frame's inverse clip (16 floats, camera-relative world <- clip) when the air pass is wanted, else NULL; lib the terrain
// library, frame the ImFrame bytes. Returns 0 (copied or skipped), 1 with err, 2 copied and the open pass reopened; + 4 when the air
// pass was drawn; + 8 when the clouds were (im_under_clouds).
int im_under_capture(Enc *enc, ImSsr *s, id<MTLTexture> color, id<MTLTexture> depth, id<MTLTexture> airTarget, id<MTLLibrary> lib, const float *air,
	const void *frame, int frameBytes, char *err, int errCap) {
	// (a pass still open on the encoder is closed by mc_frame_cmd, as for the history: skipping here left the copy never taken,
	// and the water read an empty texture, black, as what's under it)
	// (color nil: the depth alone, for the mob shadows' trace)
	int clouds = imCloudSet;
	imCloudSet = 0;
	if (!s || !depth) return 0;
	int reopen = 0, aired = 0, clouded = 0;
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
		id<MTLBlitCommandEncoder> b = [imt_blit(cmd, IMT_CAPTURE) autorelease];
		b.label = @"Imagine Metal under the water";
		MTLOrigin o = {0, 0, 0};
		[b copyFromTexture:depth sourceSlice:0 sourceLevel:0 sourceOrigin:o sourceSize:MTLSizeMake(depth.width, depth.height, 1)
			toTexture:s->underDepth destinationSlice:0 destinationLevel:0 destinationOrigin:o];
		id<MTLRenderPipelineState> airPso = air && airTarget && frame && frameBytes > 0 && airTarget.width == depth.width
			&& airTarget.height == depth.height ? imAirPipeline(s, lib, airTarget.pixelFormat) : nil;
		clouds = clouds && frame && frameBytes > 0 && imCloudTarget.width == depth.width && imCloudTarget.height == depth.height;
		if (airPso || clouds) {
			[b endEncoding];
			b = nil;
		}
		if (airPso) {
			// (the air over the opaque world, before the colour copy below, so what's seen through water has it too)
			MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
			rp.colorAttachments[0].texture = airTarget;
			rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
			rp.colorAttachments[0].storeAction = MTLStoreActionStore;
			imt_rp(rp, IMT_TERRAIN);
			id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
			r.label = @"Imagine Metal air";
			float ap[20];
			memcpy(ap, air, 64);
			ap[16] = (float) airTarget.width;
			ap[17] = (float) airTarget.height;
			ap[18] = ap[19] = 0;
			[r setRenderPipelineState:airPso];
			[r setCullMode:MTLCullModeNone];
			[r setFragmentTexture:s->underDepth atIndex:0];
			[r setFragmentBytes:frame length:(NSUInteger) frameBytes atIndex:0];
			[r setFragmentBytes:ap length:sizeof ap atIndex:1];
			[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
			[r endEncoding];
			aired = 4;
		}
		if (clouds) {
			// (Build C1: the clouds over the opaque world and its air, behind what's nearer than they are)
			imCloudLate[16] = (float) imCloudTarget.width;
			imCloudLate[17] = (float) imCloudTarget.height;
			imCloudLate[18] = imCloudLate[19] = 0;
			char cerr[512];
			cerr[0] = 0;
			if (im_sky_draw_late(enc, imCloudSky, imCloudTarget, s->underDepth, frame, frameBytes, imCloudRot, imCloudLate, cerr, (int) sizeof cerr) == 0) {
				clouded = 8;
			} else {
				imCloudFailed = 1;
				fprintf(stderr, "[Imagine Metal] clouds after the land off (drawn in the sky pass again): %s\n", cerr);
			}
		}
		if ((airPso || clouds) && color) {
			b = [imt_blit(cmd, IMT_CAPTURE) autorelease];
			b.label = @"Imagine Metal under the water";
		}
		if (color)
			[b copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:o sourceSize:MTLSizeMake(color.width, color.height, 1)
				toTexture:s->under destinationSlice:0 destinationLevel:0 destinationOrigin:o];
		[b endEncoding];
		if (reopen) mc_render_reopen(enc);
	}
	mc_im_under_bind(s->under, s->underDepth);
	return (reopen ? 2 : 0) + aired + clouded;
}

void mc_im_pbr_bind(id<MTLTexture> n, id<MTLTexture> s);  // mcown.m

// (Build B1) The generated normals' bevels baked per atlas texel (PbrAtlas.bake; lighting.metal imGenNormal reads one texel of it
// instead of the atlas's neighbours): RGBA8 the atlas's size, bound where a resource pack's normal map would be (texture 27; a
// pack's maps replace it). Returns 1 made, 0 not.
int im_bevel_upload(Ctx *ctx, int w, int h, const void *px) {
	if (!ctx || w <= 0 || h <= 0 || !px) return 0;
	@autoreleasepool {
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:(NSUInteger) w
			height:(NSUInteger) h mipmapped:NO];
		td.storageMode = MTLStorageModeShared;
		td.usage = MTLTextureUsageShaderRead;
		id<MTLTexture> t = [ctx->device newTextureWithDescriptor:td];
		if (!t) return 0;
		[t replaceRegion:MTLRegionMake2D(0, 0, (NSUInteger) w, (NSUInteger) h) mipmapLevel:0 withBytes:px bytesPerRow:(NSUInteger) w * 4];
		t.label = @"Imagine Metal baked bevels";
		mc_im_pbr_bind(t, t);
	}
	return 1;
}

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
