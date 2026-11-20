#!/usr/bin/env python3
from pathlib import Path
root=Path(__file__).resolve().parents[2]
lighting=(root/'mod/src/main/resources/imaginemetal/shaders/lighting.metal').read_text()
lod=(root/'mod/src/main/resources/imaginemetal/shaders/metalods.metal').read_text()
extract=lambda s:s.split('// BEGIN LOD edge dither math')[1].split('// END LOD edge dither math')[0]
assert extract(lighting)==extract(lod),'near/far noise or distance functions diverged'
terrain=(root/'mod/src/main/resources/mcopt/own/terrain.metal').read_text()
for name,next_name in [('own_fs_g','own_fs_tr'),('own_fs_alpha',None)]:
 body=terrain[terrain.index('fragment '+('ImGOut ' if name=='own_fs_g' else 'void ')+name):]
 assert 'imLodNearVisible(in.imPos, in.pos.xy, imf, imChunkFade)' in body
assert 'imLodNearVisible(in.imPos, in.pos.xy, imf, imChunkFade)' in terrain[terrain.index('static float4 ownFsBody'):terrain.index('fragment float4 own_fs(')]
def noise(x,y):
 h=((x*0x9e3779b9)^(y*0x85ebca6b))&0xffffffff
 h=((h^(h>>16))*0x7feb352d)&0xffffffff
 h=((h^(h>>15))*0x846ca68b)&0xffffffff
 return ((h^(h>>16))>>8)/16777216
for amount in [0,.1,.25,.5,.75,.9,1]:
 n=0
 for y in range(128):
  for x in range(128):
   near=noise(x,y)>=amount;far=noise(x,y)<amount
   assert near!=far,'hole or double ownership'
   n+=far
 assert abs(n/16384-amount)<.015
for band in [0., .25, .5, 1.]:
 for chunk in [0., .1, .5, .9, 1.]:
  for y in range(16):
   for x in range(16):
    n=noise(x,y)
    combined=max(band,chunk)
    assert (n>=combined)!=(n<combined), 'overlapping distance and arrival fades must share ownership'
assert 'imLodChunkAmount(in.rel.xz, chunkFade)' in lod
assert 'imLodChunkAmount(rel.xz, fade)' in lighting
world=(root/'mod/src/main/java/imaginemetal/metalods/MetaWorld.java').read_text()
assert 'boolean covered = this.transitionCovered();' in world
assert 'transitionTarget = covered ? this.transitionCandidate : 0f;' in world
entry=(root/'mod/src/main/java/imaginemetal/metalods/MetaLods.java').read_text()
assert 'preparedPages = w.prepare' in entry and 'Deferred.wanted()' in entry
assert 'if (preparedPages < 0)' in entry and 'lodTransition(0)' in entry
print('PASS 114688 complementary near/LOD pixel decisions; identical helpers; opaque/cutout-depth/translucent calls; readiness/deferred/first-frame guards')
