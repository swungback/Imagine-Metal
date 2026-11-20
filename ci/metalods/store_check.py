#!/usr/bin/env python3
from pathlib import Path
import tempfile,os,subprocess
root=Path(__file__).resolve().parents[2]
home=Path(os.environ.get('GRADLE_USER_HOME',root/'../gradle-home')).resolve()
jars=list((home/'caches/modules-2/files-2.1').rglob('*.jar'))+list((home/'caches/fabric-loom/minecraftMaven').rglob('*.jar'))
cp=os.pathsep.join([str(root/'mod/build/classes/java/main')]+list(map(str,jars)))
java=Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory(prefix='metalods-cache-') as tmp:
 d=Path(tmp);p=d/'imaginemetal/metalods/MetaPalette.java';p.parent.mkdir(parents=True)
 p.write_text('''package imaginemetal.metalods;final class MetaPalette {
 static int kind(int id){return id;}static String blockKey(int id){return "block"+id;}static String biomeKey(int id){return "biome"+id;}
 static int blockFromKey(String key){return Integer.parseInt(key.substring(5));}
 static int biomeFromKey(String key,net.minecraft.core.RegistryAccess a){return Integer.parseInt(key.substring(5));}}
 ''')
 subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(d),str(p),str(root/'mod/src/main/java/imaginemetal/metalods/MetaStore.java'),str(root/'ci/metalods/StoreCheck.java'),str(root/'ci/metalods/StreamsCheck.java'),str(root/'mod/src/main/java/mcopt/metal/lod/LodStreams.java'),str(root/'ci/metalods/StreamsReadyCheck.java')],check=True)
 for cls,extra in [('imaginemetal.metalods.StoreCheck',[str(d/'store')]),('mcopt.metal.lod.StreamsCheck',[]),('mcopt.metal.lod.StreamsReadyCheck',[])]:
  subprocess.run([str(java/'java'),'-cp',str(d)+os.pathsep+cp,cls]+extra,check=True)
