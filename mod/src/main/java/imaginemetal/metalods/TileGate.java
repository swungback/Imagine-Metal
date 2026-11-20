package imaginemetal.metalods;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Coalesce tile generation without parking workers behind a sibling column.
 * Ownership lasts through publication of all four columns. */
final class TileGate {
    private final Set<Long> active = ConcurrentHashMap.newKeySet();

    boolean enter(long tile) { return active.add(tile); }
    void leave(long tile) { active.remove(tile); }
}
