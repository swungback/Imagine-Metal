package mcopt.metal.lod;

/** Geometry for estimated crowns. Leaves occupy the upper crown; only the
 * jittered tree centre gets a trunk. It is not a solid leaf column to ground. */
public final class CanopyShape {
    private CanopyShape() {}
    static int mix(int h) {h^=h>>>16;h*=0x7feb352d;h^=h>>>15;h*=0x846ca68b;return h^(h>>>16);}
    public static int crownBase(int ground,int top,int cell) {
        int crown=Math.max(1,Math.min(top-ground,(top-ground+2)/3));
        return Math.max(ground+1,top-crown+1);
    }
    public static boolean trunkCell(int x,int z,int cell) {
        if(cell>2)return false;
        int px=Math.floorDiv(x,5),pz=Math.floorDiv(z,5);
        int h=mix(px*73428767^pz*912931);
        int cx=px*5+1+((h>>>16)&1)+((h>>>17)&1);
        int cz=pz*5+1+((h>>>18)&1)+((h>>>19)&1);
        int x0=x-cell/2,z0=z-cell/2;
        return cx>=x0&&cx<x0+cell&&cz>=z0&&cz<z0+cell;
    }
}
