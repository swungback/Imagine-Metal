#!/usr/bin/env python3
"""CPU ownership math and coverage-policy guards; not GPU/rasterization validation."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
shader = (root / 'mod/src/main/resources/imaginemetal/shaders/metalods.metal').read_text()
math = shader.split('// BEGIN mask ownership math')[1].split('// END mask ownership math')[0]
math = math[math.index('static inline'):].replace('float2(', 'make2(')
pre = r'''
#include <cmath>
#include <cassert>
#include <cstdio>
#include <initializer_list>
using uint = unsigned;
typedef float float2 __attribute__((ext_vector_type(2)));
float2 make2(float a, float b){return {a,b};}
float2 make2(float a){return {a,a};}
'''
main = r'''
int main(){
 int cases=0;
 for(int boundary: {-1024,-32,-16,0,16,32,1024})
 for(uint face: {0u,1u,4u,5u}) for(bool water: {false,true})
 for(int level=0;level<=5;level++) {
   float2 offset=mlMaskOffset(face,water);
   int axis=face<2?0:1;
   bool positive=face==0||face==4;
   int expected=boundary/16+((positive!=water)?-1:0);
   int actual=int(std::floor((boundary+offset[axis])/16.0));
   assert(actual==expected);
   assert(offset[1-axis]==0);
   // Ownership stays local to the plane at every coarse/fine cell size.
   assert(std::abs(offset[axis]) < (1<<level));
   cases++;
 }
 for(uint face:{2u,3u}) for(bool water:{false,true}) {
   float2 offset=mlMaskOffset(face,water);assert(offset.x==0&&offset.y==0);
 }
 printf("PASS: %d mask ownership cases, four edges, negative coordinates, levels 0..5\n",cases);
}
'''
with tempfile.TemporaryDirectory() as tmp:
    p = Path(tmp)
    (p / 'check.cpp').write_text(pre + math + main)
    subprocess.run(['clang++', '-std=c++17', str(p / 'check.cpp'), '-o', str(p / 'check')], check=True)
    subprocess.run([str(p / 'check')], check=True)
# A coarse fallback must not be removed solely because it is close to the camera.
assert 'in.level >= 2u && length(in.rel.xz)' not in shader
world = (root / 'mod/src/main/java/imaginemetal/metalods/MetaWorld.java').read_text()
for line in world.splitlines():
    if 'this.allMasked(' in line:
        assert 'MetaSettings.hideUnderReal && MetaSettings.coverageDebug != 2' in line
assert 'if (all || !c.ready())' in world  # Ready parent retained while a child is missing.
print('PASS: coarse fallback and mask-off traversal policy guards (source checks)')

# World transitions must not interrupt shared ZIP resource readers or publish stale GPU heights.
assert 't.interrupt()' not in world
assert 'synchronized (this.publicationLock)' in world
assert 'previous.levels()[0].sameData(rc.levels()[0])' in world
hook=(root/'mod/src/main/java/mcopt/metal/mixin/own/OwnSolidExtractorMixin.java').read_text()
assert 'MetaLods.levelChanged(level)' in hook
noise=(root/'mod/src/main/java/mcopt/metal/lod/LodNoise.java').read_text()
assert '!worldRouter && cheese.isPresent()' in noise
print('PASS: lifecycle, deduplication and world-router policy guards (source checks)')
