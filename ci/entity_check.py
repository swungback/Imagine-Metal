# Imagine Metal: the CI's check of imaginemetal.EntityShade's patch (a Python mirror of its string edits, kept in step with it).
# Patches Minecraft's entity, item and particle shaders as SPIRV-Cross translates them (ci/entity/*.metal, made from the game's
# GLSL with glslang + spirv-cross) and writes them out for xcrun metal to compile: a patch that wouldn't compile fails the build.
import os, re, sys

R = sys.argv[1]  # mod/src/main/resources
OUT = sys.argv[2]
lighting = open(os.path.join(R, 'imaginemetal/shaders/lighting.metal')).read()
VS_MAIN = re.compile(r'vertex\s+main0_out\s+main0\s*\(\s*main0_in\s+in\s*\[\[stage_in\]\]')
FS_MAIN = re.compile(r'fragment\s+main0_out\s+main0\s*\(\s*main0_in\s+in\s*\[\[stage_in\]\]')
MEMBERS = '    float3 imPos [[user(locn20)]];\n    float3 imNrm [[user(locn21)]];\n    float2 imLm [[user(locn22)]];\n    float imHand [[user(locn23)]];'

def add_members(src, struct):
    s = src.find('struct ' + struct)
    if s < 0: return None
    b = src.find('{', s)
    return src[:b + 1] + '\n' + MEMBERS + src[b + 1:]

def before_last_return(src, code):
    r = src.rfind('return out;')
    if r < 0: return None
    return src[:r] + code + '\n    ' + src[r:]

def patch_vs(vs, particle):
    m = VS_MAIN.search(vs)
    if not m: return None
    v = vs[:m.end()] + ', constant float4& imOn [[buffer(25)]]' + vs[m.end():]
    v = add_members(v, 'main0_out')
    if v is None: return None
    normal, uv2, color = 'in.Normal' in v, 'in.UV2' in v, 'in.Color' in v
    c = '    out.imPos = in.Position;\n'
    c += '    out.imNrm = ' + ('in.Normal' if normal and not particle else 'float3(0.0)') + ';\n'
    c += '    out.imLm = ' + ('float2(in.UV2) / 240.0' if uv2 else 'float2(1.0)') + ';\n'
    pm = re.search(r'constant\s+Projection&\s+(\w+)', v)
    persp = (pm.group(1) + '.ProjMat[2][3] != 0.0') if pm else 'false'
    c += '    out.imHand = imOn.x > 1.5 && ' + persp + ' ? 1.0 : 0.0;\n'
    c += '    if (imOn.x > 0.5 && (imOn.x < 1.5 || out.imHand > 0.5)) {\n'
    if color:
        if 'out.vertexPerFaceColorBack' in v: c += '        out.vertexPerFaceColorBack = in.Color;\n        out.vertexPerFaceColorFront = in.Color;\n'
        if 'out.vertexColor' in v: c += '        out.vertexColor = in.Color;\n'
    elif particle: return None
    if 'out.lightMapColor' in v: c += '        out.lightMapColor = float4(1.0);\n'
    c += '    }'
    return before_last_return(v, c)

def patch_fs(fs):
    m = FS_MAIN.search(fs)
    if not m: return None
    f = fs[:m.end()] + (', constant float4& imOn [[buffer(25)]], constant ImFrame& imf [[buffer(30)]]'
        ', depth2d_array<float> imShadowMap [[texture(20)]], sampler imShadowCmp [[sampler(13)]]'
        ', texture3d<float> imLightVol [[texture(21)]], sampler imLightSampler [[sampler(12)]]') + fs[m.end():]
    f = add_members(f, 'main0_in')
    if f is None: return None
    f = before_last_return(f, '    if (imOn.x > 0.5 && imOn.x < 1.5) out.fragColor.xyz = imShadeEntity(out.fragColor.xyz, in.imPos, in.imNrm, in.imLm, imf,'
        ' imShadowMap, imShadowCmp, imLightVol, imLightSampler, 0.0);\n'
        '    else if (imOn.x > 1.5 && in.imHand > 0.5) out.fragColor.xyz = imShadeEntity(out.fragColor.xyz, float3(0.0, -0.4, 0.001),'
        ' float3(0.0), in.imLm, imf, imShadowMap, imShadowCmp, imLightVol, imLightSampler, 1.0);')
    if f is None: return None
    return lighting + '\n// ---- Minecraft\'s shader (SPIRV-Cross), patched ----\n' + f

src = os.path.join(os.path.dirname(__file__), 'entity')
os.makedirs(OUT, exist_ok=True)
names = sorted({n.split('.')[0] for n in os.listdir(src)})
patched = 0
for n in names:
    vs = open(os.path.join(src, n + '.vert.metal')).read()
    fs = open(os.path.join(src, n + '.frag.metal')).read()
    lm = 'out.lightMapColor' in vs
    particle = not lm and 'sample_lightmap(' in vs and 'out.vertexColor' in vs
    if (not lm and not particle) or 'out.fragColor' not in fs or 'in.Position' not in vs:
        print('kept:', n); continue
    v = patch_vs(vs, particle); f = patch_fs(fs) if v else None
    if not v or not f:
        print('NOT PATCHED (unexpected shape):', n); sys.exit(1)
    open(os.path.join(OUT, n + '.vs.metal'), 'w').write(v)
    open(os.path.join(OUT, n + '.fs.metal'), 'w').write(f)
    patched += 1
    print('patched:', n)
if patched < 4: sys.exit('too few patched')
