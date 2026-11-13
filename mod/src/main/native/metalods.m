// MetaLODS (Imagine Metal's voxel far terrain, imaginemetal.metalods): its library, pipeline and one instanced draw into the
// open G-buffer pass (mcown.m's deferred lighting then lights it with the real terrain). Shared buffers only: on Apple
// silicon the CPU writes what the GPU reads, no uploads. Java keeps frames in flight apart (rings, freed pages held back).
#include "mcmetal.h"
#include <stdio.h>
#include <string.h>

int mco_im_g_open(Enc *enc);
void mco_im_g_mark(void);
const uint8_t *mco_im_frame(void);

typedef struct {
	Ctx *ctx;
	id<MTLLibrary> lib;
	id<MTLFunction> vs, fs[2];  // fs[1]: with the real terrain's mask (ML_MASK), fs[0]: without
	id<MTLRenderPipelineState> pso[2];
	MTLPixelFormat formats[5], depth;
	id<MTLDepthStencilState> ds;
	id<MTLSamplerState> smp;
	id<MTLBuffer> idx;  // {0 1 2 2 1 3} + 4q for a page's 256 quads
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
		m->vs = [lib newFunctionWithName:@"ml_vs"];
		for (int i = 0; i < 2; i++) {
			MTLFunctionConstantValues *cv = [[MTLFunctionConstantValues new] autorelease];
			bool on = i == 1;
			[cv setConstantValue:&on type:MTLDataTypeBool atIndex:0];
			m->fs[i] = [lib newFunctionWithName:@"ml_fs" constantValues:cv error:&e];
		}
		if (!m->vs || !m->fs[0] || !m->fs[1]) {
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
		for (int i = 0; i < 2; i++) {
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
		pd.vertexFunction = m->vs;
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
int mml_draw(MetaLods *m, Enc *enc, const void *frame, int frameLength, id<MTLBuffer> visible, uint64_t visOffset, int count,
	id<MTLBuffer> pages, id<MTLBuffer> quads, id<MTLBuffer> models, id<MTLBuffer> biomes, const void *mask, int maskLength,
	id<MTLTexture> atlas, int masked, char *err, int cap) {
	if (!m || count <= 0 || !mco_im_g_open(enc) || !atlas) return 0;
	id<MTLRenderCommandEncoder> r = enc->render;
	id<MTLRenderPipelineState> pso = mlPipeline(m, enc, masked ? 1 : 0, err, cap);
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
	[r setFragmentTexture:atlas atIndex:16];
	[r setFragmentSamplerState:m->smp atIndex:14];
	[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:256 * 6 indexType:MTLIndexTypeUInt16 indexBuffer:m->idx indexBufferOffset:0
		instanceCount:(NSUInteger) count];
	[r popDebugGroup];
	mco_im_g_mark();
	m->draws++;
	m->instances += (uint64_t) count;
	return 1;
}
