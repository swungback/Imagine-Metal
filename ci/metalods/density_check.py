#!/usr/bin/env python3
"""Test production batching against Minecraft's actual interpolator after dependencies have been resolved."""
from pathlib import Path
import os, platform, subprocess, sys, tempfile
root=Path(__file__).resolve().parents[2]
home=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))
jars=list((home/'caches/modules-2/files-2.1').rglob('*.jar'))
jars+=list((home/'caches/fabric-loom/minecraftMaven').rglob('*.jar'))
jars+=list((root/'.gradle').rglob('*.jar'))
if not any('minecraft-merged-deobf' in p.name for p in jars):
    raise SystemExit('Resolve Minecraft dependencies with :mod:compileJava first')
cp=os.pathsep.join([str(root/'mod/build/classes/java/main')]+list(map(str,jars)))
java=Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory() as d:
    subprocess.run([str(java/'javac'),'--release','25','-cp',cp,'-d',d,
        str(root/'mod/src/main/java/mcopt/metal/lod/LodDensityBatch.java'),
        str(root/'mod/src/main/java/mcopt/metal/lod/LodSolidSampler.java'),
        str(root/'mod/src/main/java/mcopt/metal/lod/LodSurfaceSampler.java'),
        str(root/'mod/src/main/java/mcopt/metal/lod/LodLatticeSampler.java'),
        str(root/'mod/src/main/java/mcopt/metal/lod/LodNativeNoise.java'),
        str(root/'ci/metalods/RouterCheck.java'),
        str(root/'ci/metalods/DensityBatchCheck.java')],check=True)
    subprocess.run([str(java/'java'),'-Xmx128m','-cp',d+os.pathsep+cp,'mcopt.metal.lod.DensityBatchCheck'],check=True)
    router_cp=d+os.pathsep+str(root/'mod/build/resources/main')+os.pathsep+cp
    subprocess.run([str(java/'java'),'-Dmcopt.lod.nativeNoise=plain','-Xmx512m','-cp',router_cp,'mcopt.metal.lod.RouterCheck']+sys.argv[1:],check=True)
    if sys.platform == 'darwin' and platform.machine() in ('arm64','aarch64'):
        subprocess.run([str(java/'java'),'--enable-native-access=ALL-UNNAMED','-Xmx512m',
            '-Dmcopt.lod.nativeNoise=neon','-cp',router_cp,'mcopt.metal.lod.RouterCheck']+sys.argv[1:],check=True)
