package mcopt.metal.own;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import java.io.BufferedReader;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;

/**
 * (fix9) The block atlas' "bushy" leaf sprites for BushyLeaves, as Way Better Leaves' OpaqueLeafSpriteSource makes them (MIT,
 * third_party/waybetterleaves/NOTICE): every texture a leaf block's models use (found through its blockstate and model files,
 * parents included), its transparent texels filled with the nearest opaque colour, tiled 2x2 into a sprite twice the size and
 * cut out by WBL's 32x32 bushy mask. Added to the blocks atlas' sources by SpriteSourceListMixin (no atlas json, no codec
 * registration: it is never read from a file).
 */
public final class BushyLeafSource implements SpriteSource {
	public static final BushyLeafSource INSTANCE = new BushyLeafSource();
	private static final MapCodec<BushyLeafSource> CODEC = MapCodec.unit(INSTANCE);
	/** WBL's masks/leaves_bushy.png (32x32): '#' opaque. */
	private static final String[] MASK = {
		"...............###.#............",
		"..............###...............",
		".......##.#...##..#.....#.......",
		"......####....#..###..####......",
		".......#...###..###...##........",
		"........##..###.##...#####......",
		"...##....##.######.#####.#......",
		"..#.###..###.######.###.........",
		"...#####.####.####.###...####...",
		"....#####.###.###...#####.###.#.",
		".##.#.####.#...#.##.#.####.#....",
		"..##...###.##...####...###......",
		".....#..#.####.####.##..#.......",
		"......#..#.#######.####..#......",
		"...###..##..###.#.####..##..#...",
		"..###..####..#.##.###..####.....",
		"##.#.##.####..####.#.##.####....",
		"#...####.###.####...####.###.##.",
		"..##.#.##.#..###..##.####.#..#..",
		".####.####....#..####.####......",
		".##...##...###..####..##...#....",
		".....#####..###.###..####.......",
		"....####.##.######.#.###.#......",
		"....###.##.#.######.#...###.....",
		"...#####...##.#.##.###.#.###....",
		"....###...###..##...##.##.##....",
		"....#....#.#...#.##.#...##.#....",
		"........##.##...####.....#......",
		"..........##...####.##..........",
		".........#.#...###..###.........",
		"................#....#..........",
		"................................",
	};

	private BushyLeafSource() {
	}

	@Override
	public void run(ResourceManager rm, SpriteSource.Output output) {
		int made = 0;
		try {
			Set<Identifier> textures = leafTextures(rm);
			for (Identifier tex : textures) {
				Optional<Resource> res = rm.getResource(TEXTURE_ID_CONVERTER.idToFile(tex));
				if (res.isEmpty()) continue;
				Identifier id = BushyLeaves.bushyId(tex);
				Resource r = res.get();
				output.add(id, (SpriteSource.DiscardableLoader) loader -> make(id, r));
				made++;
			}
			System.out.println("[Imagine Metal] bushy leaves: " + made + " leaf sprites from " + textures.size() + " leaf textures");
		} catch (Throwable t) {
			System.out.println("[Imagine Metal] bushy leaves: sprites not made (" + t + ")");
		}
	}

	@Override
	public MapCodec<? extends SpriteSource> codec() {
		return CODEC;
	}

	private static SpriteContents make(Identifier id, Resource r) {
		try (InputStream in = r.open(); NativeImage src = NativeImage.read(in)) {
			// (an animated texture: its first frame, square)
			int w = src.getWidth(), h = Math.min(src.getHeight(), w);
			int[] c = new int[w * h];
			for (int y = 0; y < h; y++)
				for (int x = 0; x < w; x++) c[y * w + x] = src.getPixel(x, y);
			fill(c, w, h);
			int tw = w * 2, th = h * 2;
			NativeImage out = new NativeImage(tw, th, true);
			for (int y = 0; y < th; y++)
				for (int x = 0; x < tw; x++) {
					int color = c[(y % h) * w + x % w];
					boolean on = MASK[y * 32 / th].charAt(x * 32 / tw) == '#';
					out.setPixel(x, y, color & 0xFFFFFF | (on ? 0xFF000000 : 0));
				}
			return new SpriteContents(id, new FrameSize(tw, th), out);
		} catch (Throwable t) {
			System.out.println("[Imagine Metal] bushy leaves: " + id + " not made (" + t + ")");
			return null;
		}
	}

	/** WBL's fillFrame: every transparent texel takes the colour of the nearest opaque one (breadth first, 4 neighbours). */
	private static void fill(int[] c, int w, int h) {
		int n = w * h;
		boolean[] set = new boolean[n];
		ArrayDeque<Integer> queue = new ArrayDeque<>(n);
		for (int i = 0; i < n; i++)
			if (c[i] >>> 24 != 0) {
				c[i] |= 0xFF000000;
				set[i] = true;
				queue.add(i);
			}
		while (!queue.isEmpty()) {
			int i = queue.removeFirst(), x = i % w, y = i / w;
			if (x > 0) spread(i - 1, c, set, queue, c[i]);
			if (x + 1 < w) spread(i + 1, c, set, queue, c[i]);
			if (y > 0) spread(i - w, c, set, queue, c[i]);
			if (y + 1 < h) spread(i + w, c, set, queue, c[i]);
		}
	}

	private static void spread(int i, int[] c, boolean[] set, ArrayDeque<Integer> queue, int color) {
		if (!set[i]) {
			c[i] = color;
			set[i] = true;
			queue.addLast(i);
		}
	}

	// ---- WBL's texture discovery: blockstates -> models (parents too) -> textures ----

	private static Set<Identifier> leafTextures(ResourceManager rm) {
		Set<Identifier> models = new HashSet<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
			if (!BushyLeaves.isLeaf(block.defaultBlockState()) || BushyLeaves.blacklisted(block)) continue;
			json(rm, Identifier.fromNamespaceAndPath(blockId.getNamespace(), "blockstates/" + blockId.getPath() + ".json"))
				.ifPresent(j -> modelRefs(j, blockId.getNamespace(), models));
		}
		Set<Identifier> textures = new HashSet<>(), seen = new HashSet<>();
		for (Identifier m : models) modelTextures(rm, m, textures, seen);
		return textures;
	}

	private static void modelRefs(JsonElement e, String ns, Set<Identifier> out) {
		if (e.isJsonObject()) {
			for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
				JsonElement v = en.getValue();
				if (en.getKey().equals("model") && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) ref(v.getAsString(), ns, out);
				else modelRefs(v, ns, out);
			}
		} else if (e.isJsonArray()) {
			for (JsonElement c : e.getAsJsonArray()) modelRefs(c, ns, out);
		}
	}

	private static void modelTextures(ResourceManager rm, Identifier model, Set<Identifier> textures, Set<Identifier> seen) {
		if (!seen.add(model)) return;
		Optional<JsonElement> o = json(rm, Identifier.fromNamespaceAndPath(model.getNamespace(), "models/" + model.getPath() + ".json"));
		if (o.isEmpty() || !o.get().isJsonObject()) return;
		JsonObject j = o.get().getAsJsonObject();
		if (j.has("parent") && j.get("parent").isJsonPrimitive()) {
			Set<Identifier> parent = new HashSet<>();
			ref(j.get("parent").getAsString(), model.getNamespace(), parent);
			for (Identifier p : parent) modelTextures(rm, p, textures, seen);
		}
		if (j.has("textures") && j.get("textures").isJsonObject())
			for (JsonElement v : j.getAsJsonObject("textures").asMap().values())
				if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) texture(v.getAsString(), model.getNamespace(), textures);
		customTextures(j, model.getNamespace(), textures);
	}

	private static void customTextures(JsonElement e, String ns, Set<Identifier> out) {
		if (e.isJsonObject()) {
			for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
				JsonElement v = en.getValue();
				if (en.getKey().toLowerCase(java.util.Locale.ROOT).contains("texture") && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString())
					texture(v.getAsString(), ns, out);
				else customTextures(v, ns, out);
			}
		} else if (e.isJsonArray()) {
			for (JsonElement c : e.getAsJsonArray()) customTextures(c, ns, out);
		}
	}

	private static void texture(String v, String ns, Set<Identifier> out) {
		if (!v.startsWith("#") && !v.startsWith("builtin/")) ref(v, ns, out);
	}

	private static void ref(String v, String ns, Set<Identifier> out) {
		Identifier id = v.indexOf(':') >= 0 ? Identifier.tryParse(v) : Identifier.tryBuild(ns, v);
		if (id != null) out.add(id);
	}

	private static Optional<JsonElement> json(ResourceManager rm, Identifier file) {
		Optional<Resource> r = rm.getResource(file);
		if (r.isEmpty()) return Optional.empty();
		try (BufferedReader reader = r.get().openAsReader()) {
			return Optional.of(JsonParser.parseReader(reader));
		} catch (Throwable t) {
			return Optional.empty();
		}
	}
}
