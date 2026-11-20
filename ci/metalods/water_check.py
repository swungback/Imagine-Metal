#!/usr/bin/env python3
"""Compile the shader's actual pure water/fog math as C++ and check continuity invariants.
This is a CPU math/ABI check, not a Metal shader or pipeline compilation.
"""
from pathlib import Path
import re, subprocess, tempfile
root=Path(__file__).resolve().parents[2]
s=(root/'mod/src/main/resources/imaginemetal/shaders/lighting.metal').read_text()
water=s[s.index('static inline float imWaterRippleLuma'):s.index('// END shared water math')]
fog=s[s.index('static float imLodFogAmount'):s.index('static float3 imLodFogColor')]
# Only adapt vector construction / Metal address-space syntax; math bodies are unmodified.
reflection=s[s.index('static bool imReflectionClearlyInFront'):s.index('// Screen-space reflection through')]
code=(water+fog+reflection).replace('float3(', 'make3(').replace('constant ', 'const ')
pre=r'''
#include <cmath>
#include <algorithm>
#include <cassert>
#include <cstdio>
using std::exp; using std::exp2;
float max(float a,float b){return std::max(a,b);}
float min(float a,float b){return std::min(a,b);}
typedef float float3 __attribute__((ext_vector_type(3)));
typedef float float4 __attribute__((ext_vector_type(4)));
float3 make3(float x,float y,float z){return {x,y,z};}
float clamp(float x,float a,float b){return max(a,min(b,x));}
float saturate(float x){return clamp(x,0.f,1.f);}
float dot(float3 a,float3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
float length(float3 a){return std::sqrt(dot(a,a));}
float mix(float a,float b,float t){return a+(b-a)*t;}
float3 mix(float3 a,float3 b,float t){return a+(b-a)*t;}
float3 max(float3 a,float b){return {max(a.x,b),max(a.y,b),max(a.z,b)};}
float3 pow(float3 a,float b){return {std::pow(a.x,b),std::pow(a.y,b),std::pow(a.z,b)};}
float3 exp(float3 a){return {std::exp(a.x),std::exp(a.y),std::exp(a.z)};}
struct ImFrame{float4 lodFog, sunDir, flags, fogParams;};
bool close(float a,float b){return std::abs(a-b)<2e-6;}
'''
main=r'''
int main(){
 // A skipped sample has its reconstructed surface farther away than the ray.
 for(float nearZ : {.05f, .1f, .5f}) for(float farZ : {128.f, 1024.f, 8192.f}) {
   float a=-nearZ/(farZ-nearZ), b=(nearZ*farZ)/(farZ-nearZ);
   for(int ray=1;ray<=48;ray++) for(int sample=0;sample<=128;sample++) {
     float distance=nearZ*std::pow(farZ/nearZ,ray/48.f);
     float depth=sample/128.f;
     float4 clip={distance*.35f,-distance*.2f,a*distance+b,distance};
     if(imReflectionClearlyInFront(clip,depth,1.f/b)) {
       float surface=b/(depth-a);
       assert(surface > distance);
     }
   }
 }
 assert(!imReflectionClearlyInFront(float4{0, 0, .05f, 1}, .05f, 20.f));
 assert(!imReflectionClearlyInFront(float4{0, 0, .5f, 100}, 0.f, 20.f));
 int cases=0;
 for(float r: {0.f,.2f,.8f,1.f}) for(float g:{0.f,.4f,1.f}) for(float b:{0.f,.6f,1.f})
 for(float t:{0.f,.25f,.62f,1.f}) for(float v:{-1.f,0.f,.5f,1.f}) {
   float3 tint={r,g,b}, tex={t,t,t};
   float3 c=imWaterBodyColor(tint,tex);
   // Independent legacy near-water calculation: refactoring must preserve its appearance.
   tint.g=max(tint.g,.39f); tint/=max(max(tint.r,max(tint.g,tint.b)),.001f);
   tint=tint*.5f+make3(.3f,.9f,.85f)*.5f;
   float l=.62f*.65f+t*.35f;
   float3 expected=tint*(.34f+.3f*l);
   for(int k=0;k<3;k++) assert(close(c[k],expected[k])&&std::isfinite(c[k]));
   float f=1.f-saturate(v);float a=imWaterSurfaceAlpha(tex,v);
   float old=.05f+.1f*l*l;old=old+(1.f-old)*f*f*f*f*.9f;
   assert(close(a,old)&&a>=0.f&&a<=1.f);
   // Deep-water compositing is a neutral alpha, never a red/green/blue-specific gain.
   float alpha=max(a,imWaterVolumeAlpha(64.f));
   for(int k=0;k<3;k++) assert(std::isfinite(c[k]*std::pow(alpha,1.f/2.2f)));
   cases++;
 }
 for(float d: {0.f,.5f,4.f,16.f,64.f}) {
   float3 dst={.3f,.7f,.8f},biome={.2f,.4f,.9f};
   float3 got=imWaterTransmitted(dst,biome,d,true);
   biome.g=max(biome.g,.39f);float3 wn=biome/max(max(biome.r,max(biome.g,biome.b)),.001f);
   wn=mix(wn,make3(.45f,.95f,.85f),.55f);
   float3 expected=pow(dst,2.2f)*(1.f+.8f*saturate(d/10.f))*exp(-((1.f-wn)*.26f+.02f)*d);
   for(int k=0;k<3;k++)assert(close(got[k],expected[k]));
 }
 float prior=0.f;
 for(int d=0;d<=128;d++){float a=imWaterVolumeAlpha(float(d));assert(a>=prior&&a<=.72f);prior=a;}
 assert(imWaterVolumeAlpha(-1.f)==0.f);
 ImFrame f{};f.lodFog.x=1.f;f.sunDir.w=1.f;f.flags.z=1.f;f.fogParams={2048.f,64.f,1.f,1.f};
 assert(imLodFogAmount(make3(24.f,0.f,0.f),f)==0.f);
 prior=0.f;
 for(int d=24;d<=2048;d++){float a=imLodFogAmount(make3(float(d),0.f,0.f),f);assert(a>=prior&&a<1.f);prior=a;}
 f.lodFog.x=0.f;assert(imLodFogAmount(make3(256.f,0.f,0.f),f)==0.f);
 f.lodFog.x=1.f;f.sunDir.w=0.f;assert(imLodFogAmount(make3(256.f,0.f,0.f),f)==0.f);
 f.sunDir.w=1.f;f.flags.y=2.f;assert(imLodFogAmount(make3(256.f,0.f,0.f),f)==0.f);
 printf("PASS: %d water cases; volume/fog bounds, continuity, underwater/debug gates\n",cases);
}
'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d);(p/'check.cpp').write_text(pre+code+main)
 subprocess.run(['clang++','-std=c++17','-Wno-psabi',str(p/'check.cpp'),'-o',str(p/'check')],check=True)
 subprocess.run([str(p/'check')],check=True)
# Appending preserves every existing offset, including TAA at 816; verify total frame size.
body=s[s.index('struct ImFrame {'):s.index('\n};',s.index('struct ImFrame {'))]
fields=re.findall(r'^\s*(float4x4|float4|int4)\s+\w+;',body,re.M)
size=sum(64 if t=='float4x4' else 16 for t in fields)
j=(root/'mod/src/main/java/imaginemetal/ImagineFrame.java').read_text()
n=(root/'mod/src/main/native/mcown.m').read_text()
assert size==int(re.search(r'SIZE = (\d+)',j)[1])==int(re.search(r'imFrame\[(\d+)\]',n)[1])==944
print('PASS: Java/native/MSL ImFrame layout is 944 bytes')
