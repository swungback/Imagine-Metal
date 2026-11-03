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
} ImBloomParams;

typedef struct {
	id<MTLDevice> device;
	id<MTLLibrary> lib;
	id<MTLRenderPipelineState> prefilter, down, up, composite;
	MTLPixelFormat compositeFormat;
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

static void imbPass(id<MTLCommandBuffer> cmd, id<MTLTexture> target, MTLLoadAction load, id<MTLRenderPipelineState> ps,
	id<MTLTexture> src, id<MTLSamplerState> s, const ImBloomParams *p, NSString *label) {
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
	[r setFragmentSamplerState:s atIndex:0];
	[r setFragmentBytes:p length:sizeof *p atIndex:0];
	[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[r endEncoding];
}

// This frame's bloom on target (the game's main image, drawn and read back in place). 0 = done, 1 = failed (err filled).
int im_bloom_render(Enc *enc, ImBloom *b, id<MTLTexture> target, float strength, float threshold, float knee, float haze, char *err,
	int errCap) {
	if (!b || !target) return 0;
	if (!(target.usage & MTLTextureUsageShaderRead) || target.textureType != MTLTextureType2D || target.sampleCount != 1) {
		snprintf(err, (size_t) errCap, "the main image can't be read by shaders (usage %lu, type %lu)", (unsigned long) target.usage,
			(unsigned long) target.textureType);
		return 1;
	}
	if (target.width < 16 || target.height < 16) return 0;
	if (!b->composite || b->compositeFormat != target.pixelFormat) {
		[b->composite release];
		b->composite = imbPipeline(b, @"im_bloom_composite", target.pixelFormat, NO, err, errCap);
		b->compositeFormat = target.pixelFormat;
		if (!b->composite) return 1;
	}
	imbChain(b, (int) target.width, (int) target.height);
	id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
	@autoreleasepool {
		ImBloomParams p = {{1.0f / (float) target.width, 1.0f / (float) target.height, threshold, knee}, {strength, (float) b->levels, haze, 0}};
		imbPass(cmd, b->views[0], MTLLoadActionDontCare, b->prefilter, target, b->linear, &p, @"Imagine Metal bloom prefilter");
		for (int i = 1; i < b->levels; i++) {
			id<MTLTexture> src = b->views[i - 1];
			p.texel[0] = 1.0f / (float) src.width;
			p.texel[1] = 1.0f / (float) src.height;
			imbPass(cmd, b->views[i], MTLLoadActionDontCare, b->down, src, b->linear, &p, @"Imagine Metal bloom down");
		}
		for (int i = b->levels - 2; i >= 0; i--) {
			id<MTLTexture> src = b->views[i + 1];
			p.texel[0] = 1.0f / (float) src.width;
			p.texel[1] = 1.0f / (float) src.height;
			imbPass(cmd, b->views[i], MTLLoadActionLoad, b->up, src, b->linear, &p, @"Imagine Metal bloom up");
		}
		p.texel[0] = 1.0f / (float) b->views[0].width;
		p.texel[1] = 1.0f / (float) b->views[0].height;
		imbPass(cmd, target, MTLLoadActionLoad, b->composite, b->views[0], b->linear, &p, @"Imagine Metal bloom composite");
	}
	return 0;
}
