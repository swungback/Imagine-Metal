// MetaLODS (Imagine Metal's voxel far terrain, imaginemetal.metalods): its library, pipeline and one instanced draw into the
// open G-buffer pass (mcown.m's deferred lighting then lights it with the real terrain). Shared buffers only: on Apple
// silicon the CPU writes what the GPU reads, no uploads. Java keeps frames in flight apart (rings, freed pages held back).
#include "mcmetal.h"
#include <stdio.h>
#include <string.h>

int mco_im_g_open(Enc *enc);
void mco_im_g_mark(void);
const uint8_t *mco_im_frame(void);
void mco_im_bind_lod_fade(id<MTLRenderCommandEncoder> r, NSUInteger index);
id<MTLTexture> im_shadow_map_texture(void *s);  // imshadow.m
int im_shadow_resolution(void *s);

typedef struct {
	Ctx *ctx;
	id<MTLLibrary> lib;
	id<MTLFunction> vs[2], fs[4];  // fs[1]: with the real terrain's mask (ML_MASK), fs[0]: without
	id<MTLRenderPipelineState> pso[4];
	MTLPixelFormat formats[5], depth;
	id<MTLDepthStencilState> ds;
	id<MTLSamplerState> smp;
	id<MTLBuffer> idx;  // {0 1 2 2 1 3} + 4q for a page's 256 quads
	// (s1120) the LODs as shadow casters: depth only (plain) and with the real terrain's mask (masked); made on first use
	id<MTLRenderPipelineState> shadowPso[2];
	id<MTLDepthStencilState> shadowDs;
	int shadowFailed;
	uint64_t shadowDraws;
	uint64_t draws, instances;
} MetaLods;

static void mlErr(char *err, int cap, const char *what, NSError *e) {
	if (err && cap > 0) snprintf(err, cap, "%s: %s", what, e ? e.description.UTF8String : "unknown error");
}

void *mml_new(Enc *enc, const char *source, char *err, int cap) {
	if (!enc || !enc->ctx) {
		if (err && cap > 0) snprintf(err, cap, "no encoder");
		return NULL;
	}
	@autoreleasepool {
		Ctx *ctx = enc->ctx;
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_1;
		o.mathMode = MTLMathModeSafe;
		NSError *e = nil;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) {
			mlErr(err, cap, "metalods.metal", e);
			return NULL;
		}
		MetaLods *m = calloc(1, sizeof(MetaLods));
		m->ctx = ctx;
		m->lib = lib;
		for (int water = 0; water < 2; water++) {
			MTLFunctionConstantValues *cv = [[MTLFunctionConstantValues new] autorelease];
			bool on = water != 0;
			[cv setConstantValue:&on type:MTLDataTypeBool atIndex:1];
			m->vs[water] = [lib newFunctionWithName:@"ml_vs" constantValues:cv error:&e];
		}
		for (int i = 0; i < 4; i++) {
			MTLFunctionConstantValues *cv = [[MTLFunctionConstantValues new] autorelease];
			bool on = (i & 1) != 0, water = (i & 2) != 0;
			[cv setConstantValue:&on type:MTLDataTypeBool atIndex:0];
			[cv setConstantValue:&water type:MTLDataTypeBool atIndex:1];
			m->fs[i] = [lib newFunctionWithName:@"ml_fs" constantValues:cv error:&e];
		}
		if (!m->vs[0] || !m->vs[1] || !m->fs[0] || !m->fs[1] || !m->fs[2] || !m->fs[3]) {
			if (err && cap > 0) snprintf(err, cap, "metalods.metal: missing ml_vs / ml_fs");
			return NULL;
		}
		MTLDepthStencilDescriptor *dd = [[MTLDepthStencilDescriptor new] autorelease];
		dd.depthCompareFunction = MTLCompareFunctionGreaterEqual;  // reversed Z, as the game's projection
		dd.depthWriteEnabled = YES;
		m->ds = [ctx->device newDepthStencilStateWithDescriptor:dd];
		MTLSamplerDescriptor *sd = [[MTLSamplerDescriptor new] autorelease];
		sd.minFilter = MTLSamplerMinMagFilterNearest;
		sd.magFilter = MTLSamplerMinMagFilterNearest;
		sd.mipFilter = MTLSamplerMipFilterLinear;
		sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
		m->smp = [ctx->device newSamplerStateWithDescriptor:sd];
		uint16_t ix[256 * 6];
		for (int q = 0; q < 256; q++) {
			uint16_t b = (uint16_t) (q * 4);
			ix[q * 6 + 0] = b;
			ix[q * 6 + 1] = b + 1;
			ix[q * 6 + 2] = b + 2;
			ix[q * 6 + 3] = b + 2;
			ix[q * 6 + 4] = b + 1;
			ix[q * 6 + 5] = b + 3;
		}
		m->idx = [ctx->device newBufferWithBytes:ix length:sizeof ix options:MTLResourceStorageModeShared];
		return m;
	}
}

// A shared buffer, CPU written, GPU read, not hazard tracked (the caller rings what changes per frame).
void *mml_buffer(Enc *enc, uint64_t size) {
	if (!enc || !enc->ctx) return NULL;
	return [enc->ctx->device newBufferWithLength:size options:MTLResourceStorageModeShared | MTLResourceHazardTrackingModeUntracked];
}

void *mml_contents(id<MTLBuffer> b) {
	return b.contents;
}

void mml_release(id obj) {
	[obj release];
}

int mml_g_open(Enc *enc) {
	return mco_im_g_open(enc);
}

static id<MTLRenderPipelineState> mlPipeline(MetaLods *m, Enc *enc, int masked, char *err, int cap) {
	MTLPixelFormat f[5];
	for (int i = 0; i < 5; i++) f[i] = enc->colors[i] ? enc->colors[i].pixelFormat : MTLPixelFormatInvalid;
	MTLPixelFormat d = enc->depth ? enc->depth.pixelFormat : MTLPixelFormatInvalid;
	if (memcmp(f, m->formats, sizeof f) != 0 || d != m->depth) {
		// (the attachments changed: both variants are made again)
		for (int i = 0; i < 4; i++) {
			[m->pso[i] release];
			m->pso[i] = nil;
		}
		memcpy(m->formats, f, sizeof f);
		m->depth = d;
	}
	if (m->pso[masked]) return m->pso[masked];
	@autoreleasepool {
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.label = @"MetaLODS";
		pd.vertexFunction = m->vs[masked >> 1];
		pd.fragmentFunction = m->fs[masked];
		for (int i = 0; i < 5; i++) {
			if (f[i] == MTLPixelFormatInvalid) continue;
			pd.colorAttachments[i].pixelFormat = f[i];
			pd.colorAttachments[i].writeMask = i == 0 ? MTLColorWriteMaskNone : MTLColorWriteMaskAll;
		}
		pd.depthAttachmentPixelFormat = d;
		pd.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle;
		NSError *e = nil;
		id<MTLRenderPipelineState> pso = [m->ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!pso) {
			mlErr(err, cap, "MetaLODS pipeline", e);
			return nil;
		}
		m->pso[masked] = pso;
		return pso;
	}
}

// The far terrain into the open G-buffer pass: `count` pages from visible[visOffset..]. frame: MLFrame (144 bytes; its TAA
// offset is filled in here from this frame's ImFrame). 1 drawn, 0 nothing to draw into, -1 err set.
int mml_draw(MetaLods *m, Enc *enc, const void *frame, int frameLength, id<MTLBuffer> visible, uint64_t visOffset, int count, int quadCapacity,
	id<MTLBuffer> pages, id<MTLBuffer> quads, id<MTLBuffer> models, id<MTLBuffer> biomes, const void *mask, int maskLength,
	id<MTLTexture> atlas, int masked, char *err, int cap) {
	if (!m || count <= 0 || !mco_im_g_open(enc) || !atlas) return 0;
	id<MTLRenderCommandEncoder> r = enc->render;
	id<MTLRenderPipelineState> pso = mlPipeline(m, enc, masked & 3, err, cap);
	if (!pso) return -1;
	uint8_t f[256];
	if (frameLength > (int) sizeof f) frameLength = (int) sizeof f;
	memcpy(f, frame, (size_t) frameLength);
	if (frameLength >= 112) memcpy(f + 96, mco_im_frame() + 816, 16);
	[r pushDebugGroup:@"MetaLODS"];
	[r setRenderPipelineState:pso];
	[r setDepthStencilState:m->ds];
	[r setCullMode:MTLCullModeNone];
	[r setTriangleFillMode:MTLTriangleFillModeFill];
	[r setDepthBias:0 slopeScale:0 clamp:0];
	[r setVertexBytes:f length:(NSUInteger) frameLength atIndex:0];
	[r setVertexBuffer:visible offset:visOffset atIndex:1];
	[r setVertexBuffer:pages offset:0 atIndex:2];
	[r setVertexBuffer:quads offset:0 atIndex:3];
	[r setVertexBuffer:models offset:0 atIndex:4];  // (the vertex stage: water's lower top)
	[r setFragmentBytes:f length:(NSUInteger) frameLength atIndex:0];
	[r setFragmentBuffer:models offset:0 atIndex:4];
	[r setFragmentBuffer:biomes offset:0 atIndex:5];
	[r setFragmentBytes:mask length:(NSUInteger) (maskLength > 0 ? maskLength : 4) atIndex:6];
	mco_im_bind_lod_fade(r, 7);
	[r setFragmentTexture:atlas atIndex:16];
	[r setFragmentSamplerState:m->smp atIndex:14];
	[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:(NSUInteger)quadCapacity * 6 indexType:MTLIndexTypeUInt16 indexBuffer:m->idx indexBufferOffset:0
		instanceCount:(NSUInteger) count];
	[r popDebugGroup];
	mco_im_g_mark();
	m->draws++;
	m->instances += (uint64_t) count;
	return 1;
}

// (s1120) MetaLODS' land as casters into one cascade of the sun/moon shadow map, after the terrain's own casters (imshadow.m
// im_shadow_render cleared and drew it; this loads it): Jack's pop-in, real chunks casting where the LODs they replaced didn't.
// groups: n entries of 4 ints (offset into visible, page count, quads per page, masked: 1 = the real terrain's mask applies);
// frame: MLFrame with the cascade's light clip. Depth state and bias as the terrain's casters. 1 drawn, 0 nothing, -1 err set.
int mml_shadow(MetaLods *m, Enc *enc, void *shadow, int cascade, const void *frame, int frameLength, id<MTLBuffer> visible,
	const int32_t *groups, int n, id<MTLBuffer> pages, id<MTLBuffer> quads, const void *mask, int maskLength, float slopeScale,
	char *err, int cap) {
	if (!m || !shadow || n <= 0 || m->shadowFailed) return 0;
	id<MTLTexture> map = im_shadow_map_texture(shadow);
	int res = im_shadow_resolution(shadow);
	if (!map || res <= 0 || cascade < 0 || cascade >= (int) map.arrayLength) return 0;
	@autoreleasepool {
		if (!m->shadowPso[0]) {
			id<MTLFunction> vs = [[m->lib newFunctionWithName:@"ml_shadow_vs"] autorelease];
			id<MTLFunction> fs = [[m->lib newFunctionWithName:@"ml_shadow_fs"] autorelease];
			if (!vs || !fs) {
				m->shadowFailed = 1;
				if (err && cap > 0) snprintf(err, cap, "metalods.metal: missing ml_shadow_vs / ml_shadow_fs");
				return -1;
			}
			MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
			pd.label = @"MetaLODS shadow casters";
			pd.vertexFunction = vs;
			pd.depthAttachmentPixelFormat = map.pixelFormat;
			pd.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle;
			NSError *e = nil;
			m->shadowPso[0] = [m->ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
			pd.label = @"MetaLODS shadow casters (masked)";
			pd.fragmentFunction = fs;
			if (m->shadowPso[0]) m->shadowPso[1] = [m->ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
			MTLDepthStencilDescriptor *dd = [[MTLDepthStencilDescriptor new] autorelease];
			dd.depthCompareFunction = MTLCompareFunctionLess;  // (standard depth, as imshadow.m)
			dd.depthWriteEnabled = YES;
			m->shadowDs = [m->ctx->device newDepthStencilStateWithDescriptor:dd];
			if (!m->shadowPso[0] || !m->shadowPso[1] || !m->shadowDs) {
				m->shadowFailed = 1;
				mlErr(err, cap, "MetaLODS shadow pipeline", e);
				return -1;
			}
		}
		uint8_t f[256];
		if (frameLength > (int) sizeof f) frameLength = (int) sizeof f;
		memcpy(f, frame, (size_t) frameLength);
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.depthAttachment.texture = map;
		rp.depthAttachment.slice = (NSUInteger) cascade;
		rp.depthAttachment.loadAction = MTLLoadActionLoad;
		rp.depthAttachment.storeAction = MTLStoreActionStore;
		rp.renderTargetWidth = (NSUInteger) res;
		rp.renderTargetHeight = (NSUInteger) res;
		imt_rp(rp, IMT_SHADOW);
		id<MTLRenderCommandEncoder> r = [mc_frame_cmd(enc) renderCommandEncoderWithDescriptor:rp];
		r.label = @"MetaLODS shadow casters";
		[r setViewport:(MTLViewport) {0, 0, (double) res, (double) res, 0, 1}];
		[r setDepthStencilState:m->shadowDs];
		[r setCullMode:MTLCullModeNone];
		[r setDepthClipMode:MTLDepthClipModeClamp];
		[r setDepthBias:0 slopeScale:slopeScale clamp:0.01f];
		[r setVertexBytes:f length:(NSUInteger) frameLength atIndex:0];
		[r setVertexBuffer:pages offset:0 atIndex:2];
		[r setVertexBuffer:quads offset:0 atIndex:3];
		[r setFragmentBytes:f length:(NSUInteger) frameLength atIndex:0];
		[r setFragmentBytes:mask length:(NSUInteger) (maskLength > 0 ? maskLength : 4) atIndex:6];
		int drawn = 0;
		for (int i = 0; i < n; i++) {
			int off = groups[i * 4], count = groups[i * 4 + 1], cap4 = groups[i * 4 + 2], masked = groups[i * 4 + 3] != 0;
			if (count <= 0 || cap4 <= 0 || cap4 > 256) continue;
			[r setRenderPipelineState:m->shadowPso[masked ? 1 : 0]];
			[r setVertexBuffer:visible offset:(NSUInteger) off * 4 atIndex:1];
			[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:(NSUInteger) cap4 * 6 indexType:MTLIndexTypeUInt16 indexBuffer:m->idx
				indexBufferOffset:0 instanceCount:(NSUInteger) count];
			drawn = 1;
		}
		[r endEncoding];
		m->shadowDraws++;
		return drawn;
	}
}
