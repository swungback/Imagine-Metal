#!/usr/bin/env python3
from pathlib import Path
import os,tempfile,subprocess
root=Path(__file__).resolve().parents[2];home=Path(os.environ.get('GRADLE_USER_HOME',root/'../gradle-home')).resolve()
jars=list((home/'caches/modules-2/files-2.1').rglob('*.jar'))+list((home/'caches/fabric-loom/minecraftMaven').rglob('*.jar'))
cp=os.pathsep.join([str(root/'mod/build/classes/java/main')]+list(map(str,jars)));java=Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory() as tmp:
 d=Path(tmp);p=d/'imaginemetal/metalods/MetaPalette.java';p.parent.mkdir(parents=True)
 p.write_text('''package imaginemetal.metalods; import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.BlockState;
 final class MetaPalette {static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> BIOME;
 static java.util.List<BlockState> states=new java.util.ArrayList<>(java.util.List.of(Blocks.AIR.defaultBlockState()));
 static int id(BlockState s){int i=states.indexOf(s);if(i<0){i=states.size();states.add(s);}return i;}
 static BlockState state(int i){return states.get(i);}static int kind(int i){var s=state(i);return s.isAir()?0:s.getBlock()==Blocks.WATER?1:s.getBlock() instanceof LeavesBlock?2:3;}
 static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biomeHolder(int i){return BIOME;}}
 ''')
 subprocess.run([str(java/'javac'),'-cp',cp,'-d',tmp,str(p),str(root/'mod/src/main/java/imaginemetal/metalods/MetaCanopy.java'),str(root/'ci/metalods/CanopyRepairCheck.java'),str(root/'ci/metalods/BiomeCanopyCheck.java')],check=True)
 for cls in ['mcopt.metal.lod.BiomeCanopyCheck','imaginemetal.metalods.CanopyRepairCheck']:
  subprocess.run([str(java/'java'),'-cp',tmp+os.pathsep+cp,cls],check=True)
