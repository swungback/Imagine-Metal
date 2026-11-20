#!/usr/bin/env python3
from zipfile import ZipFile
from pathlib import Path
import tempfile, os, subprocess, sys
root=Path(__file__).resolve().parents[2]
home=Path(os.environ.get('GRADLE_USER_HOME',root/'../gradle-home')).resolve()
jars=list((home/'caches/modules-2/files-2.1').rglob('*.jar'))+list((home/'caches/fabric-loom/minecraftMaven').rglob('*.jar'))+list((root/'.gradle').rglob('*.jar'))
cp=os.pathsep.join([str(root/'mod/build/classes/java/main'),str(root/'mod/build/resources/main')]+list(map(str,jars)))
java=Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory(prefix='pack-router-') as tmp:
 d=Path(tmp);pack=d/'pack';pack.mkdir()
 with ZipFile(sys.argv[1]) as z:
  for n in z.namelist():
   if n.startswith('data/') and not n.endswith('/') and '..' not in Path(n).parts:
    p=pack/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(n))
 subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(d),str(root/'ci/metalods/PackRouterCheck.java')],check=True)
 result=subprocess.run([str(java/'java'),'--enable-native-access=ALL-UNNAMED','-Dmcopt.lod.nativeNoise=neon','-Xmx1g','-cp',str(d)+os.pathsep+cp,'mcopt.metal.lod.PackRouterCheck',str(pack)]+sys.argv[2:])
 sys.exit(result.returncode)
