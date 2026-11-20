package mcopt.metal.own;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * (fix9) Way Better Leaves' "plus" leaves, ported one to one from its 2.0.0 jar (26.2 NeoForge, by Luc1fer1o, MIT; see
 * third_party/waybetterleaves/NOTICE). Jack asked for it 2026-11-13 after fix8's own leaf culling looked wrong.
 * <p>
 * Every leaf block that shows at least one face keeps its normal model and gets three crossed planes through its centre, each
 * two blocks wide, double-sided, turned by one of 32 rotations picked by the block's position seed. They are textured with the
 * leaf texture's "bushy" sprite (BushyLeafSource: transparent texels filled from their neighbours, tiled 2x2, cut out by WBL's
 * 32x32 mask). Leaves with no visible face (deep inside a tree) get no planes: WBL's culling (setting "Leaf culling").
 * The planes are leaves of the same block, so they sway, light and cast shadows as leaves (the material picks the block from
 * the quad's centre, which is the block's centre).
 */
public final class BushyLeaves {
	public static final String NS = "imaginemetal";
	private static final int VARIANTS = 32;
	/** WBL's default blacklist (blocks that keep their own look). */
	private static final List<String> BLACKLIST = List.of("malum:hanging_azure_runewood_leaves", "malum:hanging_runewood_leaves",
		"malum:hanging_soulwood_leaves", "bloomingnature:floating_leaves", "betterend:lucernia_outer_leaves", "betterend:tenanea_outer_leaves",
		"spawn:fallen_leaves");
	private static final PlusModel[] NONE = new PlusModel[0];
	private static final ConcurrentHashMap<BlockState, PlusModel[]> CACHE = new ConcurrentHashMap<>();
	private static volatile @Nullable TextureAtlas atlas;

	private BushyLeaves() {
	}

	/** The block atlas was (re)built (TextureAtlasMixin): the planes are made again with its sprites. */
	public static void atlasReady(TextureAtlas a) {
		atlas = a;
		CACHE.clear();
	}

	/** The sprite id BushyLeafSource gives the bushy version of texture id. */
	static Identifier bushyId(Identifier texture) {
		return Identifier.fromNamespaceAndPath(NS, "bushy/" + texture.getNamespace() + "/" + texture.getPath());
	}

	static boolean blacklisted(Block block) {
		return BLACKLIST.contains(BuiltInRegistries.BLOCK.getKey(block).toString());
	}

	/** WBL's leaf test: a model block that is a LeavesBlock, tagged leaves, or named *leaves / *leaf. */
	static boolean isLeaf(BlockState state) {
		Block block = state.getBlock();
		Boolean c = LEAF.get(block);
		if (c == null) LEAF.put(block, c = isLeaf0(block));
		return c;
	}

	private static final ConcurrentHashMap<Block, Boolean> LEAF = new ConcurrentHashMap<>();

	private static boolean isLeaf0(Block block) {
		if (block.defaultBlockState().getRenderShape() != RenderShape.MODEL) return false;
		if (block instanceof LeavesBlock || block.defaultBlockState().is(BlockTags.LEAVES)) return true;
		String id = BuiltInRegistries.BLOCK.getKey(block).toString().toLowerCase(Locale.ROOT);
		String path = id.substring(id.indexOf(':') + 1);
		return path.equals("leaf") || path.equals("leaves") || path.endsWith("_leaf") || path.endsWith("_leaves");
	}

	/** WBL's culling: a side shows unless its neighbour is leaves, a sturdy face, or a block this one skips rendering against. */
	private static boolean hasVisibleFace(BlockGetter level, BlockPos pos, BlockState state) {
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (Direction side : Direction.values()) {
			n.setWithOffset(pos, side);
			BlockState neighbor = level.getBlockState(n);
			if (blacklisted(neighbor.getBlock()) || !isLeaf(neighbor) && !neighbor.isFaceSturdy(level, n, side.getOpposite()) && !state.skipRendering(neighbor, side))
				return true;
		}
		return false;
	}

	/**
	 * The planes' model for the leaf block at pos (tesselated right after its own model, same output), or null: not leaves,
	 * the setting off, no face showing, or no bushy sprite for its texture.
	 */
	static @Nullable BlockStateModel plus(BlockGetter level, BlockPos pos, BlockState state, BlockStateModel model, long seed) {
		if (!imaginemetal.Settings.bushyLeaves || atlas == null || !isLeaf(state) || blacklisted(state.getBlock())) return null;
		if (imaginemetal.Settings.leafCulling && !hasVisibleFace(level, pos, state)) return null;
		PlusModel[] v = CACHE.get(state);
		if (v == null) {
			v = build(model);
			CACHE.put(state, v);
		}
		return v.length == 0 ? null : v[variationIndex(seed)];
	}

	private static int variationIndex(long seed) {
		long mixed = seed + -7046029254386353131L;
		mixed = (mixed ^ mixed >>> 30) * -4658895280553007687L;
		mixed = (mixed ^ mixed >>> 27) * -7723592293110705685L;
		mixed ^= mixed >>> 31;
		return Math.floorMod(Long.hashCode(mixed), VARIANTS);
	}

	private static PlusModel[] build(BlockStateModel model) {
		try {
			TextureAtlas a = atlas;
			if (a == null) return NONE;
			List<BlockStateModelPart> parts = new ArrayList<>();
			model.collectParts(RandomSource.create(42L), parts);
			TextureAtlasSprite particle = model.particleMaterial().sprite();
			BakedQuad q = find(parts, particle, true);
			if (q == null) q = find(parts, particle, false);
			BakedQuad.MaterialInfo mi = q != null ? q.materialInfo() : null;
			TextureAtlasSprite base = mi != null ? mi.sprite() : particle;
			TextureAtlasSprite bushy = a.getSprite(bushyId(base.contents().name()));
			if (bushy == null || bushy.contents().name().equals(MissingTextureAtlasSprite.getLocation())) return NONE;
			BakedQuad.MaterialInfo info = mi != null
				? new BakedQuad.MaterialInfo(bushy, mi.layer(), mi.itemRenderType(), mi.itemGlintRenderType(), mi.itemGlintSpecialRenderType(), mi.tintIndex(),
					mi.shadeDirectionOverride(), mi.lightEmission())
				: BakedQuad.MaterialInfo.of(new Material.Baked(bushy, false), com.mojang.blaze3d.platform.Transparency.TRANSPARENT, -1, null, 0);
			Material.Baked particleMat = new Material.Baked(bushy, false);
			PlusModel[] out = new PlusModel[VARIANTS];
			for (int i = 0; i < VARIANTS; i++) out[i] = new PlusModel(new PlusPart(variant(bushy, info, i), particleMat, info.flags()));
			return out;
		} catch (Throwable t) {
			System.out.println("[Imagine Metal] bushy leaves: no planes for a leaf model (" + t + ")");
			return NONE;
		}
	}

	private static @Nullable BakedQuad find(List<BlockStateModelPart> parts, TextureAtlasSprite particle, boolean requireParticle) {
		for (BlockStateModelPart part : parts) {
			for (Direction d : Direction.values())
				for (BakedQuad quad : part.getQuads(d))
					if (!requireParticle || quad.materialInfo().sprite() == particle) return quad;
			for (BakedQuad quad : part.getQuads(null))
				if (!requireParticle || quad.materialInfo().sprite() == particle) return quad;
		}
		return null;
	}

	// ---- WBL's PlusLeafQuadFactory ----

	private static List<BakedQuad> variant(TextureAtlasSprite sprite, BakedQuad.MaterialInfo info, int variant) {
		float yaw = angle(variant, 223, 71), pitch = angle(variant, 137, 17), roll = angle(variant, 89, 149);
		List<BakedQuad> quads = new ArrayList<>(6);
		plane(quads, sprite, info, rot(0, 1, 0, yaw, pitch, roll), rot(0, 0, 1, yaw, pitch, roll));
		plane(quads, sprite, info, rot(1, 0, 0, yaw, pitch, roll), rot(0, 0, 1, yaw, pitch, roll));
		plane(quads, sprite, info, rot(1, 0, 0, yaw, pitch, roll), rot(0, 1, 0, yaw, pitch, roll));
		return List.copyOf(quads);
	}

	private static float angle(int v, int m, int o) {
		return (float) Math.toRadians(Math.floorMod(v * m + o, 360));
	}

	/** value rotated about X by pitch, then Y by yaw, then Z by roll (WBL's Rotation.apply). */
	private static float[] rot(float x, float y, float z, float yaw, float pitch, float roll) {
		float c = (float) Math.cos(pitch), s = (float) Math.sin(pitch);
		float y1 = y * c - z * s, z1 = y * s + z * c;
		y = y1;
		z = z1;
		c = (float) Math.cos(yaw);
		s = (float) Math.sin(yaw);
		float x2 = x * c + z * s, z2 = -x * s + z * c;
		x = x2;
		z = z2;
		c = (float) Math.cos(roll);
		s = (float) Math.sin(roll);
		float x3 = x * c - y * s, y3 = x * s + y * c;
		return new float[] {x3, y3, z};
	}

	private static void plane(List<BakedQuad> quads, TextureAtlasSprite sprite, BakedQuad.MaterialInfo info, float[] u, float[] v) {
		float nx = u[1] * v[2] - u[2] * v[1], ny = u[2] * v[0] - u[0] * v[2], nz = u[0] * v[1] - u[1] * v[0];
		float[] a = point(u, v, -1, -1), b = point(u, v, 1, -1), c = point(u, v, 1, 1), d = point(u, v, -1, 1);
		quads.add(quad(sprite, info, a, 0, 1, b, 1, 1, c, 1, 0, d, 0, 0, nx, ny, nz));
		quads.add(quad(sprite, info, a, 0, 1, d, 0, 0, c, 1, 0, b, 1, 1, -nx, -ny, -nz));
	}

	/**
	 * The block centre + u x + v y, on the mesher's 1/2048-block grid: such coordinates are stored exactly in the compact
	 * vertex, never through the off-grid table (OwnPosTable, which is full in a big world).
	 */
	private static float[] point(float[] u, float[] v, float x, float y) {
		float[] p = new float[3];
		for (int k = 0; k < 3; k++) p[k] = Math.round((0.5F + u[k] * x + v[k] * y) * 2048F) / 2048F;
		return p;
	}

	private static BakedQuad quad(TextureAtlasSprite sprite, BakedQuad.MaterialInfo info, float[] a, float au, float av, float[] b, float bu, float bv,
		float[] c, float cu, float cv, float[] d, float du, float dv, float nx, float ny, float nz) {
		return new BakedQuad(new Vector3f(a[0], a[1], a[2]), new Vector3f(b[0], b[1], b[2]), new Vector3f(c[0], c[1], c[2]), new Vector3f(d[0], d[1], d[2]),
			UVPair.pack(sprite.getU(au), sprite.getV(av)), UVPair.pack(sprite.getU(bu), sprite.getV(bv)), UVPair.pack(sprite.getU(cu), sprite.getV(cv)),
			UVPair.pack(sprite.getU(du), sprite.getV(dv)), nearest(nx, ny, nz), info);
	}

	private static Direction nearest(float nx, float ny, float nz) {
		float x = Math.abs(nx), y = Math.abs(ny), z = Math.abs(nz);
		if (y >= x && y >= z) return ny >= 0 ? Direction.UP : Direction.DOWN;
		if (z >= x) return nz >= 0 ? Direction.SOUTH : Direction.NORTH;
		return nx >= 0 ? Direction.EAST : Direction.WEST;
	}

	/** One rotation's planes as a model (only unculled quads; no ambient occlusion, as WBL's PlusPart with its defaults). */
	record PlusModel(PlusPart part) implements BlockStateModel {
		@Override
		public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
			parts.add(this.part);
		}

		@Override
		public Material.Baked particleMaterial() {
			return this.part.particle;
		}

		@Override
		public int materialFlags() {
			return this.part.flags;
		}
	}

	record PlusPart(List<BakedQuad> quads, Material.Baked particle, int flags) implements BlockStateModelPart {
		@Override
		public List<BakedQuad> getQuads(@Nullable Direction side) {
			return side == null ? this.quads : List.of();
		}

		@Override
		public boolean useAmbientOcclusion() {
			return false;
		}

		@Override
		public Material.Baked particleMaterial() {
			return this.particle;
		}

		@Override
		public int materialFlags() {
			return this.flags;
		}
	}
}
