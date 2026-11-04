package imaginemetal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * Where the water and nether portal sprites are in the block atlas (lighting.metal ImFrame waterStill, waterFlow, portalRect): the
 * translucent pass has no material bytes, so the shader tells water by its texture. Looked up again when the atlas changes
 * (resource packs) and once every few seconds.
 */
final class WaterSprites {
	static final float[] STILL = {2f, 2f, -1f, -1f}, FLOW = {2f, 2f, -1f, -1f}, PORTAL = {2f, 2f, -1f, -1f};
	private static Object atlas;
	private static int frames;
	private static boolean logged;

	private WaterSprites() {
	}

	static void update() {
		if (atlas != null && ++frames < 300) return;
		frames = 0;
		try {
			AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
			if (!(t instanceof TextureAtlas a)) return;
			atlas = a;
			rect(a, "block/water_still", STILL);
			rect(a, "block/water_flow", FLOW);
			rect(a, "block/nether_portal", PORTAL);
			if (!logged) {
				logged = true;
				System.out.println("imaginemetal: water sprite " + STILL[0] + "," + STILL[1] + " - " + STILL[2] + "," + STILL[3]);
			}
		} catch (Throwable e) {
			if (!logged) {
				logged = true;
				System.out.println("imaginemetal: water sprites not found (" + e + ")");
			}
		}
	}

	private static void rect(TextureAtlas a, String name, float[] out) {
		TextureAtlasSprite s = a.getSprite(Identifier.withDefaultNamespace(name));
		if (s == null || s == a.missingSprite()) {
			out[0] = out[1] = 2f;
			out[2] = out[3] = -1f;
			return;
		}
		out[0] = Math.min(s.getU0(), s.getU1());
		out[1] = Math.min(s.getV0(), s.getV1());
		out[2] = Math.max(s.getU0(), s.getU1());
		out[3] = Math.max(s.getV0(), s.getV1());
	}
}
