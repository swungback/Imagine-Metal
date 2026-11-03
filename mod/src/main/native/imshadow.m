// Imagine Metal: the sun/moon shadow map (imaginemetal.Shadows on the Java side).
//
// Two cascades in one Depth32Float 2D array (slice 0 near, slice 1 far), each drawn every frame in a depth-only render encoder of its own on the frame's command buffer, before the
// level's passes: the terrain units OwnTerrain.shadowCull listed for the light (its lists and indirect arguments: solid at 0,
// cutout at 20 bytes), through im_shadow_vs / im_shadow_cutout_fs (shadow.metal, built into the terrain library). Receivers
// (own_fs, lighting.metal imShadow) bind the map at fragment texture 20 with the compare sampler at sampler 13 (mcown.m).
// Standard depth: 0 nearest the light, cleared to 1; the compare sampler is LessEqual (lit when receiver depth <= stored).
// Manual retain/release (the library builds with -fno-objc-arc).
#import "mcmetal.h"
#include <string.h>

void mc_im_shadow_bind(id<MTLTexture> map, id<MTLSamplerState> cmp);  // mcown.m

typedef struct {
	id<MTLDevice> device;
	id<MTLTexture> map;
	id<MTLSamplerState> compare, atlasSampler;
	id<MTLDepthStencilState> depth;
	id<MTLRenderPipelineState> solid, cutout;
	id<MTLBuffer> quadIndices;  // {0, 1, 2, 2, 3, 0} + 4q for 64 quads (the terrain's draw units)
	int resolution;
} ImShadow;

static void imErr(NSError *e, const char *what, char *err, int cap) {
	if (!err || cap <= 0) return;
	snprintf(err, (size_t) cap, "%s: %s", what, e ? [[e localizedDescription] UTF8String] : "unknown error");
}

ImShadow *im_shadow_new(Ctx *ctx, int resolution, char *err, int errCap) {
	@autoreleasepool {
		ImShadow *s = calloc(1, sizeof(ImShadow));
		s->device = ctx->device;
		s->resolution = resolution;
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:resolution
			height:resolution mipmapped:NO];
		td.textureType = MTLTextureType2DArray;
		td.arrayLength = 2;
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
		s->map = [ctx->device newTextureWithDescriptor:td];
		s->map.label = @"Imagine Metal shadow map";
		MTLSamplerDescriptor *sd = [[MTLSamplerDescriptor new] autorelease];
		sd.minFilter = MTLSamplerMinMagFilterLinear;
		sd.magFilter = MTLSamplerMinMagFilterLinear;
		sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.compareFunction = MTLCompareFunctionLessEqual;
		s->compare = [ctx->device newSamplerStateWithDescriptor:sd];
		MTLSamplerDescriptor *ad = [[MTLSamplerDescriptor new] autorelease];
		ad.minFilter = MTLSamplerMinMagFilterNearest;
		ad.magFilter = MTLSamplerMinMagFilterNearest;
		s->atlasSampler = [ctx->device newSamplerStateWithDescriptor:ad];
		MTLDepthStencilDescriptor *dd = [[MTLDepthStencilDescriptor new] autorelease];
		dd.depthCompareFunction = MTLCompareFunctionLess;
		dd.depthWriteEnabled = YES;
		s->depth = [ctx->device newDepthStencilStateWithDescriptor:dd];
		uint16_t idx[64 * 6];
		for (int q = 0; q < 64; q++) {
			uint16_t b = (uint16_t) (q * 4);
			uint16_t k[6] = {b, (uint16_t) (b + 1), (uint16_t) (b + 2), (uint16_t) (b + 2), (uint16_t) (b + 3), b};
			memcpy(idx + q * 6, k, sizeof k);
		}
		s->quadIndices = [ctx->device newBufferWithBytes:idx length:sizeof idx options:MTLResourceStorageModeShared];
		if (!s->map || !s->compare || !s->depth || !s->quadIndices) {
			snprintf(err, (size_t) errCap, "shadow map resources could not be created");
			return NULL;
		}
		return s;
	}
}

// The casters' pipelines from the terrain library lib (compact / fat as it was built). 0 = ready, 1 = failed (err filled).
int im_shadow_pipelines(ImShadow *s, id<MTLLibrary> lib, int compact, int fat, char *err, int errCap) {
	if (!s || !lib) {
		snprintf(err, (size_t) errCap, "no terrain library");
		return 1;
	}
	if (s->solid) return 0;
	@autoreleasepool {
		MTLFunctionConstantValues *cv = [[MTLFunctionConstantValues new] autorelease];
		bool c = compact != 0, f = fat != 0;
		[cv setConstantValue:&c type:MTLDataTypeBool atIndex:1];
		[cv setConstantValue:&f type:MTLDataTypeBool atIndex:2];
		NSError *e = nil;
		id<MTLFunction> vs = [[lib newFunctionWithName:@"im_shadow_vs" constantValues:cv error:&e] autorelease];
		if (!vs) {
			imErr(e, "im_shadow_vs", err, errCap);
			return 1;
		}
		id<MTLFunction> fs = [[lib newFunctionWithName:@"im_shadow_cutout_fs"] autorelease];
		if (!fs) {
			snprintf(err, (size_t) errCap, "im_shadow_cutout_fs missing from the terrain library");
			return 1;
		}
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.label = @"Imagine Metal shadow casters (solid)";
		pd.vertexFunction = vs;
		pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
		pd.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle;
		id<MTLRenderPipelineState> solid = [s->device newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!solid) {
			imErr(e, "shadow solid pipeline", err, errCap);
			return 1;
		}
		pd.label = @"Imagine Metal shadow casters (cutout)";
		pd.fragmentFunction = fs;
		id<MTLRenderPipelineState> cutout = [s->device newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!cutout) {
			[solid release];
			imErr(e, "shadow cutout pipeline", err, errCap);
			return 1;
		}
		s->solid = solid;
		s->cutout = cutout;
	}
	return 0;
}

// One cascade's shadow pass (cascade: the map's slice). frame: ImShadowFrame (shadow.metal) with listBase 0; the terrain's buffers from shadowBuffers; atlas
// the block atlas (nil: the cutout layer is drawn as solid). Binds the map for the terrain's receivers afterwards.
void im_shadow_render(Enc *enc, ImShadow *s, const void *frame, int frameBytes, id<MTLBuffer> arena, id<MTLBuffer> sections,
	id<MTLBuffer> recs, id<MTLBuffer> lists, id<MTLBuffer> args, int listCap, id<MTLTexture> atlas, float slopeScale, int cascade) {
	if (!s || !s->solid || !arena || !args || frameBytes > 256 || cascade < 0 || cascade > 1) return;
	id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
	@autoreleasepool {
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.depthAttachment.texture = s->map;
		rp.depthAttachment.slice = (NSUInteger) cascade;
		rp.depthAttachment.loadAction = MTLLoadActionClear;
		rp.depthAttachment.clearDepth = 1.0;
		rp.depthAttachment.storeAction = MTLStoreActionStore;
		rp.renderTargetWidth = (NSUInteger) s->resolution;
		rp.renderTargetHeight = (NSUInteger) s->resolution;
		id<MTLRenderCommandEncoder> r = [cmd renderCommandEncoderWithDescriptor:rp];
		r.label = @"Imagine Metal shadow map";
		[r setViewport:(MTLViewport) {0, 0, (double) s->resolution, (double) s->resolution, 0, 1}];
		[r setDepthStencilState:s->depth];
		[r setCullMode:MTLCullModeNone];               // only light-facing units are listed; plants are two-sided
		[r setDepthClipMode:MTLDepthClipModeClamp];    // casters between the light and the near plane still cast
		[r setDepthBias:0 slopeScale:slopeScale clamp:0.01f];
		[r setVertexBuffer:arena offset:0 atIndex:16];
		[r setVertexBuffer:sections offset:0 atIndex:17];
		[r setVertexBuffer:recs offset:0 atIndex:18];
		[r setVertexBuffer:lists offset:0 atIndex:19];
		if (atlas) {
			[r setFragmentTexture:atlas atIndex:0];
			[r setFragmentSamplerState:s->atlasSampler atIndex:0];
		}
		uint8_t f[256];
		memcpy(f, frame, (size_t) frameBytes);
		for (int layer = 0; layer < 2; layer++) {
			// ImShadowFrame.listBase sits after the matrix (64), camBlock (16), camOffset (16), reserved (16)
			uint32_t listBase = (uint32_t) (layer * listCap);
			memcpy(f + 112, &listBase, 4);
			[r setRenderPipelineState:layer == 1 && atlas ? s->cutout : s->solid];
			[r setVertexBytes:f length:(NSUInteger) frameBytes atIndex:20];
			[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:s->quadIndices indexBufferOffset:0
				indirectBuffer:args indirectBufferOffset:(NSUInteger) layer * 20];
		}
		[r endEncoding];
	}
	mc_im_shadow_bind(s->map, s->compare);
}
