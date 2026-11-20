package mcopt.metal.compat;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Server-thread chunk waits that only a look-ahead caused (see PolymerPredictMixin). -Dmcopt.chunkWaitGuard=false: off. */
public final class ChunkWaitGuard {
	private ChunkWaitGuard() {
	}

	public static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("mcopt.chunkWaitGuard", "true"));
	/** Ray casts answered with a miss because a chunk on the ray wasn't loaded (each one a wait avoided). */
	public static final AtomicLong skipped = new AtomicLong();

	/** A miss when any chunk the player's reach ray touches isn't loaded on the server, else null (cast as usual). */
	public static @Nullable BlockHitResult missIfUnloaded(ServerPlayer player) {
		if (!ON) return null;
		ServerLevel level = player.level();
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1f).scale(player.blockInteractionRange() + 1.0));
		int x0 = (int) Math.floor(Math.min(eye.x, end.x)) >> 4, x1 = (int) Math.floor(Math.max(eye.x, end.x)) >> 4;
		int z0 = (int) Math.floor(Math.min(eye.z, end.z)) >> 4, z1 = (int) Math.floor(Math.max(eye.z, end.z)) >> 4;
		var source = level.getChunkSource();
		for (int cz = z0; cz <= z1; cz++) for (int cx = x0; cx <= x1; cx++) {
			if (source.getChunkNow(cx, cz) == null) {
				skipped.incrementAndGet();
				return BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end));
			}
		}
		return null;
	}
}
