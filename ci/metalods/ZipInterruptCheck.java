import java.nio.file.*;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
public class ZipInterruptCheck {
 public static void main(String[] args) throws Exception {
  Path dir=Files.createTempDirectory("im-zip-check");
  for(boolean interrupt:new boolean[]{false,true}) {
   Path zip=dir.resolve(interrupt?"interrupted.zip":"cooperative.zip");
   URI uri=URI.create("jar:"+zip.toUri());
   try(var fs=FileSystems.newFileSystem(uri,Map.of("create","true"))) { Files.writeString(fs.getPath("/resource.txt"),"texture or registry data"); }
   try(var fs=FileSystems.newFileSystem(uri,Map.of())) {
    AtomicReference<Throwable> failure=new AtomicReference<>();
    Thread t=new Thread(()->{try {if(interrupt)Thread.currentThread().interrupt();Files.readString(fs.getPath("/resource.txt"));}catch(Throwable e){failure.set(e);}});
    t.start();t.join();
    boolean readable;
    try{Files.readString(fs.getPath("/resource.txt"));readable=true;}catch(java.nio.channels.ClosedChannelException e){readable=false;}
    if(readable==interrupt)throw new AssertionError("unexpected shared-channel state");
    System.out.println((interrupt?"Interrupted":"Cooperative")+" reader: shared resource remains readable="+readable+", worker error="+failure.get());
   }
   Files.delete(zip);
  }
  Files.delete(dir);
 }
}
