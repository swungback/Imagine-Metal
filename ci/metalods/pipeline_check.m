#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

// Compile the actual generated libraries and construct the exact five-attachment
// pipelines, including every LOD mask/water specialization. Does not launch Minecraft.
static id<MTLRenderPipelineState> pipeline(id<MTLDevice> device, id<MTLFunction> vs,
        id<MTLFunction> fs, BOOL lighting) {
    if (!vs || !fs) { fprintf(stderr, "missing specialized function\n"); exit(1); }
    MTLRenderPipelineDescriptor *p = [MTLRenderPipelineDescriptor new];
    p.vertexFunction = vs; p.fragmentFunction = fs;
    MTLPixelFormat formats[] = {MTLPixelFormatRGBA16Float, MTLPixelFormatRGBA8Unorm,
        MTLPixelFormatRGBA8Unorm, MTLPixelFormatRGBA16Unorm, MTLPixelFormatR32Float};
    for (int i = 0; i < 5; i++) {
        p.colorAttachments[i].pixelFormat = formats[i];
        p.colorAttachments[i].writeMask = (lighting ? i == 0 : i != 0) ? MTLColorWriteMaskAll : MTLColorWriteMaskNone;
    }
    p.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    p.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle;
    NSError *e = nil;
    id<MTLRenderPipelineState> state = [device newRenderPipelineStateWithDescriptor:p error:&e];
    if (!state) { fprintf(stderr, "pipeline failed: %s\n", e.description.UTF8String); exit(1); }
    return state;
}

static void waterPixels(id<MTLDevice> device, id<MTLLibrary> lib) {
    NSError *e = nil;
    id<MTLFunction> vs = [lib newFunctionWithName:@"fixture_vs"];
    id<MTLRenderPipelineState> probe = pipeline(device, [lib newFunctionWithName:@"fixture_probe_vs"],
        [lib newFunctionWithName:@"fixture_probe_fs"], YES);
    MTLPixelFormat formats[] = {MTLPixelFormatRGBA16Float, MTLPixelFormatRGBA8Unorm,
        MTLPixelFormatRGBA8Unorm, MTLPixelFormatRGBA16Unorm, MTLPixelFormatR32Float};
    id<MTLTexture> textures[5];
    for (int i = 0; i < 5; i++) {
        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:formats[i] width:16 height:16 mipmapped:NO];
        td.storageMode = i == 0 ? MTLStorageModePrivate : MTLStorageModeMemoryless;
        td.usage = MTLTextureUsageRenderTarget;
        textures[i] = [device newTextureWithDescriptor:td];
        if (!textures[i]) { fprintf(stderr, "fixture attachment allocation failed\n"); exit(1); }
    }
    MTLTextureDescriptor *dd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:16 height:16 mipmapped:NO];
    dd.storageMode = MTLStorageModePrivate; dd.usage = MTLTextureUsageRenderTarget;
    id<MTLTexture> depth = [device newTextureWithDescriptor:dd];
    id<MTLBuffer> readback = [device newBufferWithLength:4096 options:MTLResourceStorageModeShared];
    id<MTLCommandQueue> queue = [device newCommandQueue];
    id<MTLTexture> atlas = [device newTextureWithDescriptor:[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO]];
    MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
    id<MTLSamplerState> smp = [device newSamplerStateWithDescriptor:sd];
    MTLDepthStencilDescriptor *dsd = [MTLDepthStencilDescriptor new];
    dsd.depthCompareFunction = MTLCompareFunctionGreaterEqual; dsd.depthWriteEnabled = YES;
    id<MTLDepthStencilState> waterDepth = [device newDepthStencilStateWithDescriptor:dsd];
    dsd.depthCompareFunction = MTLCompareFunctionAlways; dsd.depthWriteEnabled = NO;
    id<MTLDepthStencilState> probeDepth = [device newDepthStencilStateWithDescriptor:dsd];
    for (int masked = 0; masked < 2; masked++) {
        MTLFunctionConstantValues *cv = [MTLFunctionConstantValues new]; bool w = true, m = masked != 0;
        [cv setConstantValue:&w type:MTLDataTypeBool atIndex:1];
        [cv setConstantValue:&m type:MTLDataTypeBool atIndex:0];
        id<MTLFunction> fs = [lib newFunctionWithName:@"ml_fs" constantValues:cv error:&e];
        id<MTLRenderPipelineState> water = pipeline(device, vs, fs, NO);
        for (int face = 0; face < 6; face++) {
            MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
            for (int i = 0; i < 5; i++) {
                rp.colorAttachments[i].texture = textures[i];
                rp.colorAttachments[i].loadAction = MTLLoadActionClear;
                rp.colorAttachments[i].storeAction = i == 0 ? MTLStoreActionStore : MTLStoreActionDontCare;
            }
            rp.colorAttachments[0].clearColor = MTLClearColorMake(.2,.3,.4,1);
            rp.colorAttachments[4].clearColor = MTLClearColorMake(.25,0,0,0);
            rp.depthAttachment.texture = depth; rp.depthAttachment.loadAction = MTLLoadActionClear;
            rp.depthAttachment.storeAction = MTLStoreActionDontCare; rp.depthAttachment.clearDepth = .25;
            id<MTLCommandBuffer> cb = [queue commandBuffer];
            id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
            [r setRenderPipelineState:water]; [r setDepthStencilState:waterDepth];
            uint8_t frame[144] = {0}; uint32_t model[16] = {0};
            model[8] = model[9] = 0x4080a0; model[10] = 1u << 17;
            uint32_t biome[4] = {0xffffff,0xffffff,0x3f76e4,0xffffff}, mask[4] = {0};
            [r setFragmentBytes:frame length:sizeof frame atIndex:0];
            [r setFragmentBytes:model length:sizeof model atIndex:4];
            [r setFragmentBytes:biome length:sizeof biome atIndex:5];
            [r setFragmentBytes:mask length:sizeof mask atIndex:6];
            [r setFragmentTexture:atlas atIndex:16]; [r setFragmentSamplerState:smp atIndex:14];
            // Two water surfaces, same pixel: the second must retain distance to the opaque bed.
            float config[4] = {.5f,20.f,(float)face,0};
            [r setVertexBytes:config length:sizeof config atIndex:7];
            [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            config[0] = .75f; config[1] = 40.f/3.f;
            [r setVertexBytes:config length:sizeof config atIndex:7];
            [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [r setRenderPipelineState:probe]; [r setDepthStencilState:probeDepth];
            [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [r endEncoding];
            id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
            [blit copyFromTexture:textures[0] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
                sourceSize:MTLSizeMake(16,16,1) toBuffer:readback destinationOffset:0 destinationBytesPerRow:256 destinationBytesPerImage:4096];
            [blit endEncoding]; [cb commit]; [cb waitUntilCompleted];
            if (cb.status == MTLCommandBufferStatusError) { fprintf(stderr,"GPU failed: %s\n",cb.error.description.UTF8String); exit(1); }
            _Float16 *pixel = (_Float16 *)((uint8_t *)readback.contents + 8*256 + 8*8);
            float want[] = {(40.f/3.f)*2.f/64.f,.75f,.2f,128.f/255.f};
            for (int c = 0; c < 4; c++) if (fabsf((float)pixel[c] - want[c]) > .002f) {
                fprintf(stderr,"water pixel mask=%d face=%d channel=%d: %f expected %f\n",masked,face,c,(float)pixel[c],want[c]); exit(1);
            }
            if (masked && face == 5) {
                // Exercise the production masked fragment's dither branch over
                // an existing opaque bed, not merely a separate noise shader.
                ((int32_t *)frame)[19] = 16;
                ((float *)frame)[23] = 32.f;
                int32_t *fm = (int32_t *)(frame + 112);
                fm[0] = fm[1] = -4; fm[2] = 8; fm[3] = 1;
                ((float *)frame)[32] = 1.f;
                uint32_t fullMask[8]; for (int k=0;k<8;k++) fullMask[k]=0xffffffffu;
                rp.colorAttachments[2].clearColor=MTLClearColorMake(0,0,0,0);
                rp.colorAttachments[3].clearColor=MTLClearColorMake(0,0,0,0);
                cb = [queue commandBuffer]; r = [cb renderCommandEncoderWithDescriptor:rp];
                [r setRenderPipelineState:water]; [r setDepthStencilState:waterDepth];
                [r setFragmentBytes:frame length:sizeof frame atIndex:0];
                [r setFragmentBytes:model length:sizeof model atIndex:4];
                [r setFragmentBytes:biome length:sizeof biome atIndex:5];
                [r setFragmentBytes:fullMask length:sizeof fullMask atIndex:6];
                [r setFragmentTexture:atlas atIndex:16]; [r setFragmentSamplerState:smp atIndex:14];
                config[0]=.5f; config[1]=24.f;
                [r setVertexBytes:config length:sizeof config atIndex:7];
                [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
                [r setRenderPipelineState:probe]; [r setDepthStencilState:probeDepth];
                [r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3]; [r endEncoding];
                blit=[cb blitCommandEncoder];
                [blit copyFromTexture:textures[0] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
                    sourceSize:MTLSizeMake(16,16,1) toBuffer:readback destinationOffset:0 destinationBytesPerRow:256 destinationBytesPerImage:4096];
                [blit endEncoding]; [cb commit]; [cb waitUntilCompleted];
                if(cb.status==MTLCommandBufferStatusError){fprintf(stderr,"dither GPU failure\n");exit(1);}
                int far=0;
                for(int py=0;py<16;py++)for(int px=0;px<16;px++) {
                    _Float16 *v=(_Float16 *)((uint8_t *)readback.contents+py*256+px*8);
                    BOOL lod=v[3]>.25f; far+=lod;
                    if(fabsf((float)v[1]-(lod?.5f:.25f))>.002f || fabsf((float)v[2]-.2f)>.002f
                        || fabsf((float)v[0]-(lod?.375f:0.f))>.002f){fprintf(stderr,"dither pixel ownership failed %d,%d: %f %f %f %f lod=%d\n",px,py,(float)v[0],(float)v[1],(float)v[2],(float)v[3],lod);exit(1);}
                }
                if(far<95||far>160){fprintf(stderr,"dither distribution failed: %d/256\n",far);exit(1);}
                printf("PASS GPU production LOD edge dither: %d/256 far pixels, opaque bed preserved elsewhere\n",far);
            }
        }
    }
    printf("PASS GPU water pixels: 12 mask/face cases, stacked thickness, retained bed colour, memoryless G-buffer\n");
}

int main(int argc, const char **argv) { @autoreleasepool {
    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    if (!device) { fprintf(stderr, "Metal device unavailable\n"); return 2; }
    MTLCompileOptions *options = [MTLCompileOptions new];
    options.languageVersion = MTLLanguageVersion3_1; options.mathMode = MTLMathModeSafe;
    for (int a = 1; a < argc; a++) {
        NSString *path = [NSString stringWithUTF8String:argv[a]];
        NSError *e = nil;
        NSString *source = [NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:&e];
        if ([path hasSuffix:@"metalods.metal"]) source = [source stringByAppendingString:@"\n"
            "vertex MLOut fixture_vs(uint id [[vertex_id]], constant float4 &cfg [[buffer(7)]]) {\n"
            " MLOut o; float2 p = float2((id << 1) & 2, id & 2); o.pos = float4(p*2.0-1.0,cfg.x,1);\n"
            " o.rel=float3(0,0,-cfg.y); o.id=0; o.face=uint(cfg.z); o.light=255; o.biome=0; o.level=0; o.ao=255; o.qv=0; o.uv=0; return o; }\n"
            "struct FixtureProbeOut { float4 pos [[position]]; };\n"
            "vertex FixtureProbeOut fixture_probe_vs(uint id [[vertex_id]]) { FixtureProbeOut o; float2 p=float2((id<<1)&2,id&2);o.pos=float4(p*2.0-1.0,0,1);return o;}\n"
            "fragment float4 fixture_probe_fs(FixtureProbeOut in [[stage_in]], float4 c [[color(0)]], float4 g2 [[color(2)]], float4 g3 [[color(3)]], float d [[color(4)]]) {return float4(g3.b,d,c.r,g2.a);}\n"];
        id<MTLLibrary> lib = source ? [device newLibraryWithSource:source options:options error:&e] : nil;
        if (!lib) { fprintf(stderr, "compile failed %s: %s\n", argv[a], e.description.UTF8String); return 1; }
        if ([path hasSuffix:@"metalods.metal"]) {
            for (int water = 0; water < 2; water++) for (int mask = 0; mask < 2; mask++) {
                MTLFunctionConstantValues *cv = [MTLFunctionConstantValues new];
                bool w = water != 0, m = mask != 0;
                [cv setConstantValue:&w type:MTLDataTypeBool atIndex:1];
                [cv setConstantValue:&m type:MTLDataTypeBool atIndex:0];
                id<MTLFunction> vs = [lib newFunctionWithName:@"ml_vs" constantValues:cv error:&e];
                id<MTLFunction> fs = [lib newFunctionWithName:@"ml_fs" constantValues:cv error:&e];
                pipeline(device, vs, fs, NO);
                printf("PASS LOD pipeline water=%d mask=%d\n", water, mask);
            }
            waterPixels(device, lib);
        } else {
            MTLFunctionConstantValues *tc = [MTLFunctionConstantValues new];
            bool no = false, yes = true; int mode = 0; float half = .5f;
            for (int i = 0; i <= 25; i++) if (i != 5 && i != 7 && i != 17 && i != 22 && i != 23 && i != 24)
                [tc setConstantValue:&no type:MTLDataTypeBool atIndex:i];
            [tc setConstantValue:&half type:MTLDataTypeFloat atIndex:5];
            [tc setConstantValue:&mode type:MTLDataTypeInt atIndex:7];
            [tc setConstantValue:&yes type:MTLDataTypeBool atIndex:1];
            [tc setConstantValue:&yes type:MTLDataTypeBool atIndex:3];
            for (int i = 40; i <= 53; i++) [tc setConstantValue:&yes type:MTLDataTypeBool atIndex:i];
            for (NSString *name in @[@"own_fs_g", @"own_fs_alpha", @"own_fs_tr"]) {
                id<MTLFunction> stage = [lib newFunctionWithName:name constantValues:tc error:&e];
                if (!stage) { fprintf(stderr,"near dither specialization failed %s: %s\n",name.UTF8String,e.description.UTF8String); return 1; }
                printf("PASS near dither stage %s\n",name.UTF8String);
            }
            for (int features = 0; features < 2; features++) {
                MTLFunctionConstantValues *cv = [MTLFunctionConstantValues new];
                bool on = features != 0;
                for (int i = 40; i <= 53; i++) [cv setConstantValue:&on type:MTLDataTypeBool atIndex:i];
                id<MTLFunction> vs = [lib newFunctionWithName:@"im_defer_vs"];
                id<MTLFunction> fs = [lib newFunctionWithName:@"im_defer_fs" constantValues:cv error:&e];
                pipeline(device, vs, fs, YES);
                printf("PASS deferred pipeline features=%d %s\n", features, argv[a]);
            }
        }
    }
    return 0;
}}
