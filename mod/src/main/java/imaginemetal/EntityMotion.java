package imaginemetal;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.system.MemoryUtil;

/**
 * (s1125b) The mobs' own motion for the upscaler (bloom.metal ImbEnts, imbloom.m im_bloom_entities). Each frame: every mob near
 * the camera, where it is drawn this frame and where it was drawn the last (the renderer's own interpolation: lerp(xOld, x) and
 * the body's rotLerp), so the motion pass can give a mob's pixels the mob's motion. Before, they had the camera's motion only,
 * and the reactive mask dropped their history every frame instead (Jack: mobs shake, still ones too, worse far away).
 * Per mob 16 floats: the box (camera-relative, the hitbox plus half a block for heads, tails and held things) with the turn's cos
 * and sin in the w's, where it is now, where it was (both from THIS frame's camera, as the motion pass works). The 60 nearest.
 */
final class EntityMotion {
	private EntityMotion() {
	}

	static final int MAX = 60, FLOATS = 16, BYTES = MAX * FLOATS * 4;
	private static final double RANGE = 192.0, INFLATE = 0.5;

	private record Pose(double x, double y, double z, float yaw, long frame) {
	}

	private static final Map<Integer, Pose> LAST = new HashMap<>();
	private static long frame;
	private static Object lastLevel;
	private static double lastCamX = Double.NaN, lastCamY, lastCamZ;
	/** set when this frame is a camera cut (a teleport, a level change): the upscaler's history is dropped */
	static boolean cut;
	private static int jitterLogged;

	private static final int[] IDX = new int[1024];
	private static final double[] DIST = new double[1024];
	private static final float[] TMP = new float[1024 * FLOATS];

	/** Fills {@code out} (BYTES bytes) and returns the count. camX/Y/Z: the camera the frame's transforms are relative to. */
	static int fill(long out, double camX, double camY, double camZ) {
		frame++;
		Minecraft mc = Minecraft.getInstance();
		var level = mc.level;
		cut = false;
		if (level != lastLevel) {
			LAST.clear();
			lastLevel = level;
			cut = true;
		}
		if (!Double.isNaN(lastCamX)) {
			double dx = camX - lastCamX, dy = camY - lastCamY, dz = camZ - lastCamZ;
			if (dx * dx + dy * dy + dz * dz > 16.0 * 16.0) cut = true;  // (no camera moves 16 blocks in one frame but a teleport)
		}
		lastCamX = camX;
		lastCamY = camY;
		lastCamZ = camZ;
		if (Settings.fxDebug == 4 && jitterLogged < 32) {
			// (the jitter sequence, as the terrain, the mobs and MetalFX get it: in the world frame's pixels)
			float tw = Math.max(1f, Math.round(mc.gameRenderer.mainRenderTarget().width * ImagineFrame.renderScale));
			float th = Math.max(1f, Math.round(mc.gameRenderer.mainRenderTarget().height * ImagineFrame.renderScale));
			System.out.printf(java.util.Locale.ROOT, "[Imagine Metal] jitter %d: %.4f %.4f px%n", jitterLogged++,
				ImagineFrame.jitterX * 0.5f * tw, ImagineFrame.jitterY * 0.5f * th);
		} else if (Settings.fxDebug != 4) jitterLogged = 0;
		if (level == null || !Settings.entityMotion) {
			LAST.clear();
			return 0;
		}
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		int n = 0;
		for (Entity e : level.entitiesForRendering()) {
			double x = Mth.lerp(pt, e.xOld, e.getX()), y = Mth.lerp(pt, e.yOld, e.getY()), z = Mth.lerp(pt, e.zOld, e.getZ());
			float yaw = e instanceof LivingEntity le ? Mth.rotLerp(pt, le.yBodyRotO, le.yBodyRot) : 0f;
			Pose was = LAST.get(e.getId());
			LAST.put(e.getId(), new Pose(x, y, z, yaw, frame));
			if (n >= IDX.length) continue;
			double rx = x - camX, ry = y - camY, rz = z - camZ;
			double d2 = rx * rx + ry * ry + rz * rz;
			if (d2 > RANGE * RANGE) continue;
			// (a mob seen for the first time, or not drawn last frame: no motion)
			Pose p = was != null && was.frame == frame - 1 ? was : null;
			double px = p != null ? p.x : x, py = p != null ? p.y : y, pz = p != null ? p.z : z;
			float turn = p != null ? Mth.wrapDegrees(yaw - p.yaw) : 0f;
			double hw = e.getBbWidth() * 0.5 + INFLATE, h = e.getBbHeight();
			int o = n * FLOATS;
			double a = Math.toRadians(turn);
			TMP[o] = (float) (rx - hw);
			TMP[o + 1] = (float) (ry - INFLATE);
			TMP[o + 2] = (float) (rz - hw);
			TMP[o + 3] = (float) Math.cos(a);
			TMP[o + 4] = (float) (rx + hw);
			TMP[o + 5] = (float) (ry + h + INFLATE);
			TMP[o + 6] = (float) (rz + hw);
			TMP[o + 7] = (float) Math.sin(a);
			TMP[o + 8] = (float) rx;
			TMP[o + 9] = (float) ry;
			TMP[o + 10] = (float) rz;
			TMP[o + 11] = 0f;
			TMP[o + 12] = (float) (px - camX);
			TMP[o + 13] = (float) (py - camY);
			TMP[o + 14] = (float) (pz - camZ);
			TMP[o + 15] = 0f;
			DIST[n] = d2;
			IDX[n] = n;
			n++;
		}
		if ((frame & 63) == 0) LAST.values().removeIf(p -> p.frame < frame - 1);
		// the nearest MAX (a selection by distance; usually there are fewer)
		int count = Math.min(n, MAX);
		if (n > MAX) {
			for (int i = 0; i < count; i++) {
				int best = i;
				for (int j = i + 1; j < n; j++) if (DIST[IDX[j]] < DIST[IDX[best]]) best = j;
				int t = IDX[i];
				IDX[i] = IDX[best];
				IDX[best] = t;
			}
		}
		for (int i = 0; i < count; i++) {
			int src = IDX[i] * FLOATS;
			for (int k = 0; k < FLOATS; k++) MemoryUtil.memPutFloat(out + (long) (i * FLOATS + k) * 4, TMP[src + k]);
		}
		return count;
	}
}
